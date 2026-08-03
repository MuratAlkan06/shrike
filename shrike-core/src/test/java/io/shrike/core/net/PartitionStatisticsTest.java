package io.shrike.core.net;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.shrike.core.log.LogConfig;
import io.shrike.core.log.ProducedRecord;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a partition says about its own storage: five numbers read in one pass under its lock.
 *
 * <p>The numbers themselves are the log's, and {@code SegmentedLogStatisticsTest} is what proves them
 * against the bytes on disk. What is proved here is the pass: that a describe of a partition sees the
 * log it is actually holding, including after a segment roll has moved the last record into a file of
 * its own.
 */
class PartitionStatisticsTest {

    private static final String TOPIC = "orders";
    private static final int PARTITION = 0;

    /** length 4, crc32c 4, magic 1, attributes 1, offset 8, timestamp 8, keyLen 4, valueLen 4. */
    private static final int FRAMING_BYTES_WITHOUT_KEY = 34;

    private static final int VALUE_BYTES = 10;
    private static final int RECORD_BYTES = FRAMING_BYTES_WITHOUT_KEY + VALUE_BYTES;
    private static final int TWO_RECORD_SEGMENT_BYTES = 2 * RECORD_BYTES;

    @TempDir
    Path dataDirectory;

    @Test
    void reportsAnEmptyRangeAndOneSegmentForAPartitionWithNoRecords() {
        BrokerConfig config = BrokerHarness.config(dataDirectory);

        try (Partition partition = Partition.open(config, TOPIC, PARTITION, BrokerHarness.SYSTEM_CLOCK)) {
            PartitionStatistics statistics = partition.statistics();

            assertEquals(new PartitionStatistics(0L, 0L, 1, 0L, 0L), statistics,
                    "a new partition starts at offset 0 with one empty segment and nothing on disk");
        }
    }

    @Test
    void reportsTheOffsetsSegmentCountAndBytesOfTheLogItIsHolding() {
        BrokerConfig config = BrokerHarness.configWithLogConfig(dataDirectory,
                new LogConfig(LogConfig.DEFAULT_MAX_RECORD_BYTES, TWO_RECORD_SEGMENT_BYTES,
                        LogConfig.DEFAULT_INDEX_INTERVAL_BYTES));

        try (Partition partition = Partition.open(config, TOPIC, PARTITION, BrokerHarness.SYSTEM_CLOCK)) {
            for (int record = 0; record < 5; record++) {
                partition.produce(List.of(new ProducedRecord(null, "payload-%02d".formatted(record).getBytes(UTF_8))));
            }
            PartitionStatistics statistics = partition.statistics();

            assertEquals(0L, statistics.logStartOffset());
            assertEquals(5L, statistics.highWaterMark(), "the high-water mark is the offset the next append takes");
            assertEquals(3, statistics.segmentCount(), "five records into two-record segments is three segments");
            assertEquals(5 * RECORD_BYTES, statistics.logBytes());
            assertEquals(partition.highWaterMark(), statistics.highWaterMark(),
                    "the mark a fetch is served against and the mark a describe reports are the same number");
        }
    }
}
