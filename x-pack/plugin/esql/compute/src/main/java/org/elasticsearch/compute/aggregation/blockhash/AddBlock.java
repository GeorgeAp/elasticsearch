/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.aggregation.blockhash;

import org.elasticsearch.compute.aggregation.GroupingAggregatorFunction;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BlockFactory;
import org.elasticsearch.compute.data.IntBlock;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.core.Releasable;

/**
 * Helper for adding a {@link Page} worth of {@link Block}s to a {@link BlockHash}
 * while flushing the ordinals to the aggregations when we've accumulated
 * {@link #emitBatchSize} ordinals. See {@link #appendOrdSv} and {@link #appendOrdInMv}
 * for how to add values to it. After adding all values, call {@link #emitOrds} to
 * flush the last batch of values to the aggs.
 */
public class AddBlock implements Releasable {
    private final BlockFactory blockFactory;
    private final int emitBatchSize;
    private final GroupingAggregatorFunction.AddInput addInput;

    private int positionOffset = 0;
    private int added = 0;
    private IntBlock.Builder ords;
    /**
     * State of the current position.
     * <ul>
     *     <li>If {@code -1} then this position is "empty". It hasn't
     *     received any calls to {@link #appendOrdInMv}. When
     *     {@link #appendOrdInMv} is called this will shift into the
     *     "buffering" state by setting this to the provided ord.</li>
     *     <li>If {@code >= 0} this position is "buffering" a single
     *     ordinal. When {@link #appendOrdInMv} is called this will
     *     {@link Block.Builder#beginPositionEntry() begin} a multivalued
     *     field, add the buffered ordinal, add the provided ordinal,
     *     and shift to {@code -2}.</li>
     *     <li>If {@code -2} then this position is "streaming" and
     *     calling {@link #appendOrdInMv} will add values immediately.</li>
     * </ul>
     * There's some extra complexity around emitting buffered values and shifting
     * back into {@code -1}, but that's the gist of the states.
     */
    private int firstOrd = -1;

    public AddBlock(BlockFactory blockFactory, int emitBatchSize, GroupingAggregatorFunction.AddInput addInput) {
        this.blockFactory = blockFactory;
        this.emitBatchSize = emitBatchSize;
        this.addInput = addInput;

        this.ords = blockFactory.newIntBlockBuilder(emitBatchSize);
    }

    /**
     * Append a single valued ordinal. This will flush the ordinals to the aggs
     * if we've added {@link #emitBatchSize}.
     */
    protected final void appendOrdSv(int position, int ord) {
        assert firstOrd == -1 : "currently in a multivalue position";
        ords.appendInt(ord);
        if (++added % emitBatchSize == 0) {
            rollover(position + 1);
        }
    }

    /**
     * Append a {@code null} valued ordinal. This will flush the ordinals
     * to the aggs if we've added {@link #emitBatchSize}.
     * @deprecated nulls should resolve to some value.
     */
    @Deprecated
    protected final void appendNullSv(int position) {
        ords.appendNull();
        if (++added % emitBatchSize == 0) {
            rollover(position + 1);
        }
    }

    /**
     * Append a value inside a multivalued ordinal. If the current position is
     * not started this will begin the position. This will flush the ordinals to
     * the aggs if we've added {@link #emitBatchSize}.This should be used by like:
     * <pre>{@code
     *  appendOrdInMv(position, ord);
     *  appendOrdInMv(position, ord);
     *  appendOrdInMv(position, ord);
     *  finishMv();
     * }</pre>
     */
    protected final void appendOrdInMv(int position, int ord) {
        if (++added % emitBatchSize == 0) {
            switch (firstOrd) {
                case -1 -> ords.appendInt(ord);
                case -2 -> {
                    ords.appendInt(ord);
                    ords.endPositionEntry();
                }
                default -> {
                    assert firstOrd >= 0;
                    ords.beginPositionEntry();
                    ords.appendInt(firstOrd);
                    ords.appendInt(ord);
                    ords.endPositionEntry();
                }
            }
            rollover(position);
            firstOrd = -1;
            return;
        }
        switch (firstOrd) {
            case -1 -> firstOrd = ord;
            case -2 -> ords.appendInt(ord);
            default -> {
                assert firstOrd >= 0;
                ords.beginPositionEntry();
                ords.appendInt(firstOrd);
                ords.appendInt(ord);
                firstOrd = -2;
            }
        }
    }

    protected final void finishMv() {
        switch (firstOrd) {
            case -1 -> ords.appendNull();
            case -2 -> ords.endPositionEntry();
            default -> ords.appendInt(firstOrd);
        }
        firstOrd = -1;
    }

    protected final void emitOrds() {
        try (IntBlock ordsBlock = ords.build()) {
            addInput.add(positionOffset, ordsBlock);
        }
    }

    private void rollover(int position) {
        emitOrds();
        positionOffset = position;
        ords = blockFactory.newIntBlockBuilder(emitBatchSize); // TODO add a clear method to the builder?
    }

    @Override
    public void close() {
        ords.close();
    }
}
