package com.gmail.nossr50.util.blockmeta;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.DeflaterOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Checks compatibility with region headers produced independently of the region writer. */
class RegionHeaderCompatibilityTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(ints = {9, 10, 12})
    void readsEveryLegacyHeaderEntryAndRetainsRecordsAfterWrite(final int exponent) throws Exception {
        final int segmentSize = 1 << exponent;
        final int firstSegment = 12288 / segmentSize;
        final byte[] legacy = new byte[12288 + 1024 * segmentSize];
        final ByteBuffer header = ByteBuffer.wrap(legacy);
        header.putInt(8192, exponent);
        for (int i = 0; i < 1024; i++) {
            final var compressed = new ByteArrayOutputStream();
            try (var output = new DeflaterOutputStream(compressed)) {
                output.write(ByteBuffer.allocate(4).putInt(i * 7919).array());
            }
            final byte[] payload = compressed.toByteArray();
            header.putInt(i * 4, firstSegment + i);
            header.putInt(4096 + i * 4, payload.length);
            System.arraycopy(payload, 0, legacy, 12288 + i * segmentSize, payload.length);
        }
        final Path path = directory.resolve("legacy.mcm");
        Files.write(path, legacy);
        // Negative region coordinates also exercise the existing chunk-index mapping.
        try (var region = new McMMOSimpleRegionFile(path.toFile(), -2, 3)) {
            for (int i = 0; i < 1024; i++) {
                try (var input = region.getInputStream(-64 + (i >> 5), 96 + (i & 31))) {
                    assertEquals(i * 7919, input.readInt(), "chunk " + i);
                }
            }
            assertArrayEquals(legacy, Files.readAllBytes(path));
            try (var output = region.getOutputStream(-33, 127)) {
                output.writeInt(123456);
            }
        }
        try (var region = new McMMOSimpleRegionFile(path.toFile(), -2, 3)) {
            try (var input = region.getInputStream(-33, 127)) {
                assertEquals(123456, input.readInt());
            }
            try (var input = region.getInputStream(-64, 96)) {
                assertEquals(0, input.readInt());
            }
        }
    }

    @Test
    void newRegionReopensWithEmptyHeader() throws Exception {
        final Path path = directory.resolve("empty.mcm");
        try (var region = new McMMOSimpleRegionFile(path.toFile(), 0, 0)) {
            assertNull(region.getInputStream(31, 31));
        }
        try (var region = new McMMOSimpleRegionFile(path.toFile(), 0, 0)) {
            assertNull(region.getInputStream(0, 0));
        }
        final byte[] expected = new byte[12288];
        ByteBuffer.wrap(expected).putInt(8192, 10);
        assertArrayEquals(expected, Files.readAllBytes(path));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1023})
    void rejectsNegativeHeaderValuesAndReleasesHandle(final int index) throws Exception {
        final Path path = directory.resolve("corrupt.mcm");
        final byte[] bytes = new byte[12288];
        ByteBuffer.wrap(bytes).putInt(8192, 10).putInt(4096 + index * 4, -1);
        Files.write(path, bytes);
        assertThrows(RuntimeException.class, () -> new McMMOSimpleRegionFile(path.toFile(), 0, 0));
        assertArrayEquals(bytes, Files.readAllBytes(path));
        Files.delete(path);
    }
}
