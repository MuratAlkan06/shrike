package io.shrike.core.net;

import io.shrike.core.log.LogConfig;
import io.shrike.core.log.ProducedRecord;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One long-poll fetch, woken a stated number of times before it can be answered, and the number of
 * segment files it opened while that happened.
 *
 * <p>It exists because that count is what Slice 1.5 changed and the fetch benchmark cannot see: a
 * fetch that waits holds the descriptor it located its range through and asks again through that one,
 * so a long poll opens its segment file once however many appends wake it. The seam that counts the
 * opens — {@code Partition.onSegmentFileOpened} — is package-private and so is {@link Partition}
 * itself, which is why this class lives beside them rather than in {@code io.shrike.core.bench}: the
 * benchmark drives it from there and never names a type it is not allowed to. Nothing in
 * {@code src/main} changed to make this measurable; both seams were already there, and
 * {@link PartitionFetchDescriptorTest} counts through the same one.
 *
 * <p><strong>The wakeups are driven rather than waited for.</strong> A thread named
 * {@code shrike-bench-produce} appends one record every time the fetch under measurement registers as
 * a waiter, which it does under the partition lock one statement before it releases it. So a fetch
 * asking for {@code wakeups} records worth of bytes registers, is woken by a record, finds too few
 * bytes, registers again — exactly {@code wakeups} times — and is answered on the wakeup that brings
 * the last one. Nothing sleeps and nothing polls: the semaphore is the handshake.
 *
 * <p>It is not named {@code *Test}, so surefire never runs it as one.
 */
public final class LongPollFetchProbe implements AutoCloseable {

    /** length 4, crc32c 4, magic 1, attributes 1, offset 8, timestamp 8, keyLen 4, valueLen 4. */
    private static final int FRAMING_BYTES_WITHOUT_KEY = 34;

    private static final int VALUE_BYTES = 16;

    /** What one appended record occupies, which is what a fetch's {@code minBytes} is counted in. */
    private static final int RECORD_BYTES = FRAMING_BYTES_WITHOUT_KEY + VALUE_BYTES;

    /** More bytes than any fetch here asks for, so the answer is bounded by records and not by a cap. */
    private static final int PLENTY_OF_BYTES = 1024 * 1024;

    /**
     * Long enough that a fetch reaching it would be a broken probe rather than a measurement, and short
     * enough that a broken probe ends. The broker's own {@code max.fetch.wait.ms} clamps it to 30s.
     */
    private static final int LONG_WAIT_MS = 30_000;

    private static final String TOPIC = "bench";
    private static final int PARTITION = 0;

    private final Partition partition;

    /** The record every wakeup appends: no key, a 16-byte value, framing to {@link #RECORD_BYTES}. */
    private final ProducedRecord record;

    /** One permit per time the fetch under measurement has registered as a waiter. */
    private final Semaphore waiterRegistrations = new Semaphore(0);

    /** How many segment files this partition's fetches have opened, counted through the seam. */
    // confined to: the thread calling serveOneLongPollWokenSeveralTimes, which is the thread that
    // opens them, but held as an atomic so that a read of it is never a torn one
    private final AtomicLong segmentFilesOpened = new AtomicLong();

    /** How many times a fetch of this partition has registered as a waiter, counted through the seam. */
    // confined to: as above — the seam fires on the fetching thread, under the partition lock
    private final AtomicLong waitsRegistered = new AtomicLong();

    private final Thread producer;

    /** Where the next fetch starts, which is after every record the fetches before it were served. */
    // confined to: the thread calling serveOneLongPollWokenSeveralTimes
    private long fetchOffset;

    /** Set before the producer is woken for the last time, so that it ends instead of appending again. */
    // guarded by: the semaphore below it — written before a release and read after the acquire it wakes
    private volatile boolean closing;

    /**
     * What the producer thread failed with, so that a measurement fails with it rather than reporting a
     * count from a partition nobody was appending to.
     */
    // guarded by: written once by shrike-bench-produce before it ends, read after that thread is joined
    private volatile RuntimeException produceFailure;

    private LongPollFetchProbe(Partition partition, ProducedRecord record) {
        this.partition = partition;
        this.record = record;
        this.producer = new Thread(this::appendOneRecordPerWait, "shrike-bench-produce");
    }

    /**
     * Opens a partition of its own under {@code dataDirectory} and starts the thread that answers this
     * probe's waits with records.
     *
     * <p>The log takes {@code segment.bytes} at its 1 GiB ceiling rather than the 128 MiB default, so
     * that no trial of any length this harness runs can roll a segment underneath a fetch. A roll is a
     * thing this repository already tests the descriptor behaviour across; leaving it out here is what
     * makes the count below a statement about wakeups alone.
     *
     * @param dataDirectory where the partition's files go, which the caller creates and removes
     * @return the probe, which the caller closes
     */
    public static LongPollFetchProbe open(Path dataDirectory) {
        Objects.requireNonNull(dataDirectory, "dataDirectory");

        LogConfig logConfig = new LogConfig(LogConfig.DEFAULT_MAX_RECORD_BYTES, LogConfig.MAX_SEGMENT_BYTES,
                LogConfig.DEFAULT_INDEX_INTERVAL_BYTES, LogConfig.DEFAULT_RETENTION_MS,
                LogConfig.DEFAULT_RETENTION_BYTES, LogConfig.DEFAULT_FLUSH_MODE,
                LogConfig.DEFAULT_FLUSH_INTERVAL_MS, LogConfig.DEFAULT_FLUSH_INTERVAL_BYTES);
        Partition partition = Partition.open(BrokerHarness.configWithLogConfig(dataDirectory, logConfig), TOPIC,
                PARTITION, BrokerHarness.SYSTEM_CLOCK);

        byte[] value = new byte[VALUE_BYTES];
        Arrays.fill(value, (byte) 'x');
        LongPollFetchProbe probe = new LongPollFetchProbe(partition, new ProducedRecord(null, value));

        partition.onSegmentFileOpened(descriptor -> probe.segmentFilesOpened.incrementAndGet());
        partition.onWaiterRegistered(() -> {
            probe.waitsRegistered.incrementAndGet();
            probe.waiterRegistrations.release();
        });
        probe.producer.start();
        return probe;
    }

    /**
     * Serves one long poll that cannot be answered until it has been woken {@code wakeups} times, and
     * says what that cost in {@code open(2)} calls.
     *
     * @param wakeups how many records the fetch waits for, which is how many times it is woken
     * @return what the fetch opened, how often it waited, and how many bytes it left with
     */
    public LongPollServed serveOneLongPollWokenSeveralTimes(int wakeups) {
        if (wakeups < 1) {
            throw new IllegalArgumentException("a long poll is woken at least once, not " + wakeups);
        }
        long filesOpenedBefore = segmentFilesOpened.get();
        long waitsRegisteredBefore = waitsRegistered.get();
        int minBytes = wakeups * RECORD_BYTES;

        try (FetchedRecords answer = partition.fetch(fetchOffset, PLENTY_OF_BYTES, LONG_WAIT_MS, minBytes)) {
            if (produceFailure != null) {
                throw produceFailure;
            }
            if (answer.recordBytes() != minBytes) {
                throw new IllegalStateException("the fetch was answered with " + answer.recordBytes()
                        + " bytes rather than the " + minBytes + " it waited for, so it reached its deadline"
                        + " instead of being woken " + wakeups + " times");
            }
            fetchOffset += wakeups;
            return new LongPollServed(segmentFilesOpened.get() - filesOpenedBefore,
                    waitsRegistered.get() - waitsRegisteredBefore, answer.recordBytes());
        }
    }

    /**
     * Ends the producer and closes the partition. The data directory is the caller's.
     */
    @Override
    public void close() {
        closing = true;
        waiterRegistrations.release();
        try {
            producer.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while ending " + producer.getName(), e);
        } finally {
            partition.close();
        }
    }

    /**
     * Appends one record every time a fetch of this partition registers as a waiter, which is what makes
     * a wakeup happen at an instant this probe knows about rather than at one it waited for.
     */
    private void appendOneRecordPerWait() {
        try {
            while (true) {
                waiterRegistrations.acquire();
                if (closing) {
                    return;
                }
                partition.produce(List.of(record));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            // Kept rather than thrown: this thread has nobody to throw to, and a fetch that was never
            // answered would otherwise wait out its deadline and report a count from a probe that had
            // stopped working. The next served fetch raises it.
            produceFailure = e;
        }
    }

    /**
     * What one long poll cost.
     *
     * @param segmentFileOpens how many segment files the fetch opened, which is one {@code open(2)} each
     * @param wakeups          how many times it registered as a waiter and was woken by a record
     * @param recordBytes      how many bytes of records it was answered with
     */
    public record LongPollServed(long segmentFileOpens, long wakeups, int recordBytes) {
    }
}
