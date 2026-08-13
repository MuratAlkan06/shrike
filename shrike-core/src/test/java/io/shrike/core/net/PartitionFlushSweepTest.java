package io.shrike.core.net;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.shrike.core.log.LogConfig;
import io.shrike.core.log.ProducedRecord;
import io.shrike.core.time.TimeSource;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What one pass of {@code shrike-flush} does to the partitions that have nothing to force: nothing at
 * all, and without taking their locks.
 *
 * <p>A lock that was never taken leaves no trace, so the counting here goes through the
 * {@code onFlushLockAcquired} seam, which fires under a partition's lock at the instant a flush has
 * taken it. That the pass reaches every partition is the registry's own loop, unchanged by this slice;
 * what these tests are about is which of them it stops at.
 *
 * <p>Nothing here sleeps and nothing waits. A sweep is one synchronous call on the test's own thread,
 * at an instant this test names, which is exactly what {@code FlushSweep.runOnce} makes of it — and the
 * clock every record is stamped from is a field this test writes.
 */
class PartitionFlushSweepTest {

    private static final String TOPIC = "orders";
    private static final int FOUR_PARTITIONS = 4;

    /** The one partition anything is produced to, chosen so that it is neither the first nor the last. */
    private static final int WRITTEN_PARTITION = 2;

    private static final long FIRST_TIMESTAMP_MS = 1_700_000_000_000L;

    /** The clock every log in this test reads, and the only thing that makes time pass here. */
    private final AtomicLong nowMillis = new AtomicLong(FIRST_TIMESTAMP_MS);

    private final TimeSource clock = nowMillis::get;

    /** How many partition locks the sweeps in one test have taken, counted through the seam. */
    private final AtomicInteger flushLocks = new AtomicInteger();

    @TempDir
    Path dataDirectory;

    @Test
    void takesTheLockOfOnlyThePartitionThatHasRecordsToForce() {
        try (TopicRegistry registry = openFourPartitions()) {
            countFlushLocks(registry);
            produceTo(registry, WRITTEN_PARTITION);

            registry.flushIfDue(nowMillis.get());
            int afterTheSweepThatFoundIt = flushLocks.get();
            passOneFlushInterval();
            registry.flushIfDue(nowMillis.get());

            assertEquals(1, afterTheSweepThatFoundIt,
                    "three of the four partitions held nothing unforced, and a sweep takes no lock to be told so");
            assertEquals(1, flushLocks.get(),
                    "and the sweep that forced the fourth left it with nothing either, so the next takes nothing");
        }
    }

    @Test
    void forcesThePartitionThatHasRecordsAndNoOtherPartition() {
        try (TopicRegistry registry = openFourPartitions()) {
            produceTo(registry, WRITTEN_PARTITION);

            List<Boolean> forcedByPartition = new ArrayList<>();
            for (int partition = 0; partition < FOUR_PARTITIONS; partition++) {
                forcedByPartition.add(partitionOf(registry, partition).flushIfDue(nowMillis.get()));
            }

            assertEquals(List.of(false, false, true, false), forcedByPartition,
                    "the records the sweep skipped past were records that were already on the device");
        }
    }

    @Test
    void forcesOnTheNextSweepAPartitionDirtiedAfterOneFoundItClean() {
        try (TopicRegistry registry = openFourPartitions()) {
            countFlushLocks(registry);

            registry.flushIfDue(nowMillis.get());
            int afterASweepOverABrokerWithNothingToForce = flushLocks.get();
            produceTo(registry, WRITTEN_PARTITION);
            passOneFlushInterval();
            boolean forcedByTheNextSweep = partitionOf(registry, WRITTEN_PARTITION).flushIfDue(nowMillis.get());

            assertEquals(0, afterASweepOverABrokerWithNothingToForce,
                    "a broker holding nothing unforced anywhere is a sweep that takes no lock at all");
            assertTrue(forcedByTheNextSweep,
                    "and a record that landed after that sweep waits one sweep rather than for another append");
            assertEquals(1, flushLocks.get(), "which is the one lock the second sweep had a reason to take");
        }
    }

    private TopicRegistry openFourPartitions() {
        TopicRegistry registry = TopicRegistry.open(BrokerHarness.config(dataDirectory), clock);
        registry.create(TOPIC, FOUR_PARTITIONS);
        return registry;
    }

    private void countFlushLocks(TopicRegistry registry) {
        for (int partition = 0; partition < FOUR_PARTITIONS; partition++) {
            partitionOf(registry, partition).onFlushLockAcquired(flushLocks::incrementAndGet);
        }
    }

    private void produceTo(TopicRegistry registry, int partition) {
        partitionOf(registry, partition)
                .produce(List.of(new ProducedRecord(null, "a record nobody has forced".getBytes(UTF_8))));
    }

    /**
     * Moves the clock on by exactly {@code flush.interval.ms}, which is how often {@code shrike-flush}
     * asks, so that the next sweep is one a log answers rather than one it turns away for being early.
     */
    private void passOneFlushInterval() {
        nowMillis.addAndGet(LogConfig.defaults().flushIntervalMs());
    }

    private static Partition partitionOf(TopicRegistry registry, int partition) {
        return registry.partition(TOPIC, partition).orElseThrow();
    }
}
