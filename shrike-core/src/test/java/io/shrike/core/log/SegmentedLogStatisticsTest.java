package io.shrike.core.log;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.shrike.core.time.TimeSource;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The read-only statistics surface: what a partition reports about itself as it fills and rolls.
 */
class SegmentedLogStatisticsTest {

    private static final String TOPIC = "orders";
    private static final int PARTITION = 0;
    private static final TimeSource FIXED_CLOCK = () -> 1_700_000_000_000L;

    /** length 4, crc32c 4, magic 1, attributes 1, offset 8, timestamp 8, keyLen 4, valueLen 4. */
    private static final int FRAMING_BYTES_WITHOUT_KEY = 34;

    private static final int VALUE_BYTES = 10;
    private static final int RECORD_BYTES = FRAMING_BYTES_WITHOUT_KEY + VALUE_BYTES;
    private static final int TWO_RECORD_SEGMENT_BYTES = 2 * RECORD_BYTES;

    /** An entry every byte, so every record but the first of each segment earns one. */
    private static final int INDEX_INTERVAL_BYTES = 1;
    private static final int INDEX_ENTRY_BYTES = 8;

    @TempDir
    Path dataDirectory;

    @Test
    void reportsAnEmptyRangeAndOneSegmentForALogWithNoRecords() {
        try (SegmentedLog log = SegmentedLog.open(dataDirectory, TOPIC, PARTITION, FIXED_CLOCK)) {
            assertEquals(0L, log.logStartOffset());
            assertEquals(0L, log.highWaterMark());
            assertEquals(1, log.segmentCount(), "a new partition starts with one empty segment");
            assertEquals(0L, log.logBytes());
            assertEquals(0L, log.indexBytes());
        }
    }

    @Test
    void reportsOffsetsSegmentCountAndBytesAcrossARoll() {
        LogConfig config = new LogConfig(LogConfig.DEFAULT_MAX_RECORD_BYTES, TWO_RECORD_SEGMENT_BYTES,
                INDEX_INTERVAL_BYTES);

        try (SegmentedLog log = SegmentedLog.open(dataDirectory, TOPIC, PARTITION, FIXED_CLOCK, config)) {
            for (int record = 0; record < 5; record++) {
                log.append(new ProducedRecord(null, "payload-%02d".formatted(record).getBytes(UTF_8)));
            }

            assertEquals(0L, log.logStartOffset(), "the log starts at the base offset of its first segment");
            assertEquals(5L, log.highWaterMark(), "the high-water mark is the offset the next append takes");
            assertEquals(5L, log.nextOffset());
            assertEquals(3, log.segmentCount());
            assertEquals(5 * RECORD_BYTES, log.logBytes());
            assertEquals(2 * INDEX_ENTRY_BYTES, log.indexBytes(),
                    "each full segment indexed its second record; the segment holding one record indexed nothing");
        }
    }

    @Test
    void keepsReportingTheSameNumbersAfterARestart() {
        LogConfig config = new LogConfig(LogConfig.DEFAULT_MAX_RECORD_BYTES, TWO_RECORD_SEGMENT_BYTES,
                INDEX_INTERVAL_BYTES);

        try (SegmentedLog log = SegmentedLog.open(dataDirectory, TOPIC, PARTITION, FIXED_CLOCK, config)) {
            for (int record = 0; record < 5; record++) {
                log.append(new ProducedRecord(null, "payload-%02d".formatted(record).getBytes(UTF_8)));
            }
        }

        try (SegmentedLog reopened = SegmentedLog.open(dataDirectory, TOPIC, PARTITION, FIXED_CLOCK, config)) {
            assertEquals(0L, reopened.logStartOffset());
            assertEquals(5L, reopened.highWaterMark());
            assertEquals(3, reopened.segmentCount());
            assertEquals(5 * RECORD_BYTES, reopened.logBytes());
            assertEquals(2 * INDEX_ENTRY_BYTES, reopened.indexBytes(),
                    "a rebuilt index holds the same entries the appends had written");
        }
    }
}
