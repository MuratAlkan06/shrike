package io.shrike.core.net;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.shrike.core.log.LogConfig;
import io.shrike.core.log.ProducedRecord;
import io.shrike.core.protocol.PartitionDescription;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A describe of a partition while {@code shrike-retention} is deleting segments out of the same one.
 *
 * <p>This is the hazard the two slices meet in, so it is proven rather than argued.
 * {@link PartitionDescription} refuses a high-water mark below its log start offset and a segment count
 * below one — the description of a partition that never existed — and retention is the one thing in
 * this broker that moves both of those numbers from a thread of its own. A describe that read them one
 * at a time would eventually read a start offset retention had just advanced beside a high-water mark
 * from before it, and the refusal would land on a caller who asked a read-only question: an
 * {@link io.shrike.core.protocol.ErrorCode#INTERNAL} on a request anybody may send.
 *
 * <p>What rules that out is that {@link Partition#statistics()} and
 * {@link Partition#deleteRetiredSegments(long)} take the same lock, and this pins it: the describing
 * thread builds the very description {@code RequestDispatcher} builds, so a snapshot that has torn is
 * an exception rather than an assertion somebody has to remember to write. Retention is called through
 * the same method {@code TopicRegistry} calls on its sweep, on a thread of its own, exactly as
 * production does it.
 *
 * <p>Nothing here sleeps or polls: the two background threads run until the producer has finished, and
 * the test then joins them. The assertions at the end prove the race was actually run — segments were
 * deleted, and descriptions were taken after the log start offset had moved — so a build where
 * retention silently stopped working cannot pass this by racing nothing.
 */
class PartitionDescribeRetentionRaceTest {

    private static final String TOPIC = "orders";
    private static final int PARTITION = 0;

    /** length 4, crc32c 4, magic 1, attributes 1, offset 8, timestamp 8, keyLen 4, valueLen 4. */
    private static final int FRAMING_BYTES_WITHOUT_KEY = 34;

    private static final int VALUE_BYTES = 10;
    private static final int RECORD_BYTES = FRAMING_BYTES_WITHOUT_KEY + VALUE_BYTES;
    private static final int TWO_RECORD_SEGMENT_BYTES = 2 * RECORD_BYTES;

    /** Everything sealed is retired the moment it is sealed, so every sweep has something to delete. */
    private static final long DELETE_EVERY_SEALED_SEGMENT_MS = 0L;

    /** Enough appends to roll and retire hundreds of segments, so a sweep and a describe overlap many times. */
    private static final int RECORDS = 500;

    @TempDir
    Path dataDirectory;

    @Test
    void describesOneInstantOfAPartitionRetentionIsDeletingSegmentsFrom() throws InterruptedException {
        LogConfig twoRecordSegmentsThatRetireAtOnce = new LogConfig(LogConfig.DEFAULT_MAX_RECORD_BYTES,
                TWO_RECORD_SEGMENT_BYTES, LogConfig.DEFAULT_INDEX_INTERVAL_BYTES, DELETE_EVERY_SEALED_SEGMENT_MS,
                LogConfig.RETENTION_DISABLED);
        BrokerConfig config = BrokerHarness.configWithLogConfig(dataDirectory, twoRecordSegmentsThatRetireAtOnce);

        AtomicBoolean producing = new AtomicBoolean(true);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicInteger deletedSegments = new AtomicInteger();
        AtomicInteger describesPastTheFirstOffset = new AtomicInteger();

        try (Partition partition = Partition.open(config, TOPIC, PARTITION, BrokerHarness.SYSTEM_CLOCK)) {
            // The same call TopicRegistry makes on a sweep, on a thread of its own, which is the whole
            // of what shrike-retention does to a partition.
            Thread retention = new Thread(() -> whileProducing(producing, failure,
                    () -> deletedSegments.addAndGet(partition.deleteRetiredSegments(System.currentTimeMillis()))),
                    "retention-under-test");
            // The same five numbers RequestDispatcher describes a partition with, built into the same
            // record, so a snapshot that has torn throws here rather than being asserted about.
            Thread describing = new Thread(() -> whileProducing(producing, failure, () -> {
                PartitionStatistics statistics = partition.statistics();
                PartitionDescription described = new PartitionDescription(PARTITION, statistics.logStartOffset(),
                        statistics.highWaterMark(), statistics.segmentCount(),
                        statistics.logBytes() + statistics.indexBytes());
                if (described.logStartOffset() > 0L) {
                    describesPastTheFirstOffset.incrementAndGet();
                }
            }), "describing-under-test");

            retention.start();
            describing.start();
            try {
                for (int record = 0; record < RECORDS; record++) {
                    partition.produce(List.of(new ProducedRecord(null, "payload-%04d".formatted(record)
                            .getBytes(UTF_8))));
                }
            } finally {
                producing.set(false);
            }
            retention.join();
            describing.join();

            // One last sweep on this thread, so what is asserted below is where retention ends up
            // rather than wherever its thread happened to be when it was told to stop.
            partition.deleteRetiredSegments(System.currentTimeMillis());
            PartitionStatistics settled = partition.statistics();
            assertNull(failure.get(), () -> "a describe or a sweep failed: " + failure.get());
            assertEquals(RECORDS, settled.highWaterMark(), "every record this test produced was appended");
            assertTrue(deletedSegments.get() > 0, "retention deleted nothing, so this test raced nothing");
            assertTrue(describesPastTheFirstOffset.get() > 0,
                    "no description was taken after retention had moved the log start offset, so this test"
                            + " raced nothing");
            assertTrue(settled.logStartOffset() <= settled.highWaterMark(),
                    "a partition's readable range never runs backwards");
            assertEquals(1, settled.segmentCount(),
                    "every sealed segment was retired, which leaves the one still taking appends");
        }
    }

    /**
     * Runs one step over and over until the producer says it has finished, keeping the first failure so
     * that the test thread can fail on it rather than losing it to a thread nobody is watching.
     *
     * @param producing whether the producer is still appending
     * @param failure   where the first failure goes
     * @param step      what one pass does
     */
    private static void whileProducing(AtomicBoolean producing, AtomicReference<Throwable> failure, Runnable step) {
        while (producing.get()) {
            try {
                step.run();
            } catch (RuntimeException | Error e) {
                failure.compareAndSet(null, e);
                return;
            }
        }
    }
}
