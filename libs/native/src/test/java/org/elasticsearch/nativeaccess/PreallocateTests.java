/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.nativeaccess;

import org.elasticsearch.test.ESTestCase;

import java.io.IOException;
import java.nio.file.Path;
import java.util.OptionalLong;

import static org.hamcrest.Matchers.equalTo;

public class PreallocateTests extends ESTestCase {
    public void testPreallocate() throws IOException {
        Path file = createTempFile();
        long size = 1024 * 1024; // 1 MB
        var nativeAccess = NativeAccess.instance();
        nativeAccess.tryPreallocate(file, size);
        OptionalLong foundSize = nativeAccess.allocatedSizeInBytes(file);
        assertTrue(foundSize.isPresent());
        // Note that on Windows the fallback setLength is used. Although that creates a sparse
        // file on Linux/MacOS, it full allocates the file on Windows
        assertThat(foundSize.getAsLong(), equalTo(size));
    }
}
