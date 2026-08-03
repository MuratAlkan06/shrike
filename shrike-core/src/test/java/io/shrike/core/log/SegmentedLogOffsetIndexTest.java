package io.shrike.core.log;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.shrike.core.time.TimeSource;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The sparse offset index seen through the log that owns it: which records earn an entry, and that a
 * lookup lands on the right record whether the offset asked for has an entry, sits between two
 * entries, or comes before the first one.
 */
class SegmentedLogOffsetIndexTest {

    private static final String TOPIC = "orders";
    private static final int PARTITION = 0;
    private static final TimeSource FIXED_CLOCK = () -> 1_700_000_000_000L;

    /** length 4, crc32c 4, magic 1, attributes 1, offset 8, timestamp 8, keyLen 4, valueLen 4. */
    private static final int FRAMING_BYTES_WITHOUT_KEY = 34;

    private static final int VALUE_BYTES = 10;
    private static final int RECORD_BYTES = FRAMING_BYTES_WITHOUT_KEY + VALUE_BYTES;

    /**
     * An interval of 100 bytes over 44-byte records puts an entry on every third record: two records
     * are not enough to reach the interval, the third one is.
     */
    private static final int INDEX_INTERVAL_BYTES = 100;
    private static final int RECORDS_BETWEEN_ENTRIES = 3;

    @TempDir
    Path dataDirectory;

    @Test
    void indexesOneRecordEveryIndexIntervalBytesOfAppendedData() throws IOException {
        LogConfig config = new LogConfig(LogConfig.DEFAULT_MAX_RECORD_BYTES, LogConfig.DEFAULT_SEGMENT_BYTES,
                INDEX_INTERVAL_BYTES);

        try (SegmentedLog log = SegmentedLog.open(dataDirectory, TOPIC, PARTITION, FIXED_CLOCK, config)) {
            for (int record = 0; record < 10; record++) {
                log.append(recordNumber(record));
            }

            assertEquals(List.of(entry(3, 3 * RECORD_BYTES), entry(6, 6 * RECORD_BYTES), entry(9, 9 * RECORD_BYTES)),
                    indexEntriesOf(0L),
                    "the first record of a segment is never indexed, and every third one after it is");
        }
    }

    @Test
    void indexesRelativeOffsetsSoEntriesRestartWithEachSegment() throws IOException {
        int fiveRecordSegmentBytes = 5 * RECORD_BYTES;
        LogConfig config = new LogConfig(LogConfig.DEFAULT_MAX_RECORD_BYTES, fiveRecordSegmentBytes,
                INDEX_INTERVAL_BYTES);

        try (SegmentedLog log = SegmentedLog.open(dataDirectory, TOPIC, PARTITION, FIXED_CLOCK, config)) {
            for (int record = 0; record < 12; record++) {
                log.append(recordNumber(record));
            }

            assertEquals(3, log.segmentCount());
            assertEquals(List.of(entry(3, 3 * RECORD_BYTES)), indexEntriesOf(0L));
            assertEquals(List.of(entry(3, 3 * RECORD_BYTES)), indexEntriesOf(5L),
                    "an entry stores the offset relative to its own segment's base offset");
            assertEquals(List.of(), indexEntriesOf(10L), "a segment too short to reach the interval indexes nothing");
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L})
    void readsOffsetsThatFallBetweenTwoIndexEntries(long offset) {
        LogConfig config = new LogConfig(LogConfig.DEFAULT_MAX_RECORD_BYTES, LogConfig.DEFAULT_SEGMENT_BYTES,
                INDEX_INTERVAL_BYTES);

        try (SegmentedLog log = SegmentedLog.open(dataDirectory, TOPIC, PARTITION, FIXED_CLOCK, config)) {
            for (int record = 0; record < 10; record++) {
                log.append(recordNumber(record));
            }

            StoredRecord stored = log.read(offset);

            assertEquals(offset, stored.offset());
            assertArrayEquals(valueOf((int) offset), stored.value(),
                    "offset " + offset + " is " + (offset % RECORDS_BETWEEN_ENTRIES) + " record(s) past an entry");
        }
    }

    @Test
    void readsEveryOffsetWhenSegmentBoundariesAndIndexEntriesInterleave() {
        int fiveRecordSegmentBytes = 5 * RECORD_BYTES;
        LogConfig config = new LogConfig(LogConfig.DEFAULT_MAX_RECORD_BYTES, fiveRecordSegmentBytes,
                INDEX_INTERVAL_BYTES);
        int recordCount = 17;

        try (SegmentedLog log = SegmentedLog.open(dataDirectory, TOPIC, PARTITION, FIXED_CLOCK, config)) {
            for (int record = 0; record < recordCount; record++) {
                log.append(recordNumber(record));
            }

            List<byte[]> readBackwards = new ArrayList<>();
            for (long offset = recordCount - 1; offset >= 0; offset--) {
                readBackwards.add(log.read(offset).value());
            }

            assertEquals(4, log.segmentCount());
            for (int record = 0; record < recordCount; record++) {
                assertArrayEquals(valueOf(record), readBackwards.get(recordCount - 1 - record),
                        "offset " + record + " must read back whichever segment and index gap it landed in");
            }
        }
    }

    private static ProducedRecord recordNumber(int record) {
        return new ProducedRecord(null, valueOf(record));
    }

    private static byte[] valueOf(int record) {
        return "payload-%02d".formatted(record).getBytes(UTF_8);
    }

    private static String entry(int relativeOffset, int positionBytes) {
        return "relativeOffset=" + relativeOffset + " positionBytes=" + positionBytes;
    }

    /**
     * @param baseOffset the segment whose index to read
     * @return that index's entries, spelled out so a failure names the entry that is wrong
     */
    private List<String> indexEntriesOf(long baseOffset) throws IOException {
        byte[] indexBytes = Files.readAllBytes(
                dataDirectory.resolve(TOPIC + "-" + PARTITION).resolve("%020d.index".formatted(baseOffset)));
        assertEquals(0, indexBytes.length % 8, "an index file holds whole 8-byte entries");

        ByteBuffer entries = ByteBuffer.wrap(indexBytes);
        List<String> spelledOut = new ArrayList<>();
        while (entries.hasRemaining()) {
            spelledOut.add(entry(entries.getInt(), entries.getInt()));
        }
        return spelledOut;
    }
}
