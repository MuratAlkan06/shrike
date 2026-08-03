package io.shrike.core.net;

import io.shrike.core.log.Log;
import io.shrike.core.log.LogConfig;
import io.shrike.core.log.ProducedRecord;
import io.shrike.core.log.RecordFrame;
import io.shrike.core.log.RecordTooLargeException;
import io.shrike.core.log.SegmentedLog;
import io.shrike.core.protocol.FetchResponse;
import io.shrike.core.time.TimeSource;
import java.io.Closeable;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One partition of one topic while the broker is running: its log, the lock that makes it a single
 * writer, and the condition every waiting fetch sleeps on.
 *
 * <p>The lock is the whole design. {@link SegmentedLog} says plainly that nothing in it is safe to
 * call from two threads at once, so every append and every read of this partition happens under
 * {@link #lock} — connection threads take turns rather than sharing a file position. The high-water
 * mark needs no field of its own for the same reason: it <em>is</em> the log's next offset, read
 * under the lock that the appending thread held when it moved it, so there is no second copy of it to
 * drift.
 *
 * <p>A fetch that has fewer bytes than it asked for checks what it has and registers as a waiter
 * <em>under the same lock</em> that a produce holds while it appends and signals. That is what makes
 * a lost wakeup impossible: an append landing "just after" the check cannot have landed, because it
 * cannot hold the lock until the fetch has released it inside {@link Condition#await(long, TimeUnit)}.
 * The wait is bounded by an absolute deadline computed once, the predicate is re-checked on every
 * wakeup, and nothing anywhere sleeps or polls.
 */
final class Partition implements Closeable {

    private final String topic;
    private final int partition;
    private final LogConfig logConfig;
    private final int maxFetchBytes;

    /** The clock a fetch's deadline is measured on, injected like every other clock in this broker. */
    private final TimeSource timeSource;

    private final ReentrantLock lock = new ReentrantLock();

    /** Signalled by every append, awaited by every fetch that wants more bytes than it can see. */
    // guarded by: lock
    private final Condition recordsAppended = lock.newCondition();

    /**
     * This partition's storage. Every call on it — append, read, next offset — is made under
     * {@link #lock}, which is what turns a log with a single writer into one many connections share.
     */
    // guarded by: lock
    private final Log log;

    /** Set once when the broker stops, so a waiter does not have to wait out its deadline first. */
    // guarded by: lock
    private boolean stopped;

    /**
     * A test seam, and the only field here that production code never writes. It fires under
     * {@link #lock} at the instant a fetch has decided to wait and has not released the lock yet,
     * which is the window a lost wakeup would have to live in. A test uses it to land an append in
     * exactly that window; production leaves it doing nothing.
     */
    // guarded by: lock
    private Runnable waiterRegistered = () -> {
    };

    private Partition(String topic, int partition, LogConfig logConfig, int maxFetchBytes, TimeSource timeSource,
                      Log log) {
        this.topic = topic;
        this.partition = partition;
        this.logConfig = logConfig;
        this.maxFetchBytes = maxFetchBytes;
        this.timeSource = timeSource;
        this.log = log;
    }

    /**
     * Opens one partition's log, recovering it when it is already on disk.
     *
     * @param dataDirectory the directory every path is derived from
     * @param topic         the topic this partition belongs to
     * @param partition     the partition number within that topic
     * @param timeSource    the clock that stamps appended records
     * @param logConfig     the record, segment, and index sizes to open the log with
     * @param maxFetchBytes the most bytes of records one fetch may be answered with, whatever it asks
     *                      for; a single frame larger than this is still served whole
     * @return the open partition
     */
    static Partition open(Path dataDirectory, String topic, int partition, TimeSource timeSource, LogConfig logConfig,
                          int maxFetchBytes) {
        Log log = SegmentedLog.open(dataDirectory, topic, partition, timeSource, logConfig);
        return new Partition(topic, partition, logConfig, maxFetchBytes, timeSource, log);
    }

    String topic() {
        return topic;
    }

    int partition() {
        return partition;
    }

    /**
     * Appends every record of one produce request, in order, and wakes the fetches waiting for them.
     *
     * <p>The frame sizes are all checked before the first append, so a request carrying one record too
     * large is refused whole rather than half-stored.
     *
     * <p>Durability: the records are handed to the operating system and nothing is forced. That is the
     * same promise {@code SegmentedLog.append} makes and the same one this broker makes today; a flush
     * mode that can promise more is a later slice.
     *
     * @param records the records to append, at least one
     * @return the offset the first record was appended at; the rest follow it in order
     * @throws RecordTooLargeException if any record's frame would exceed {@code max.record.bytes}
     */
    long produce(List<ProducedRecord> records) {
        Objects.requireNonNull(records, "records");

        lock.lock();
        try {
            for (ProducedRecord record : records) {
                refuseIfTooLarge(record);
            }

            long baseOffset = log.nextOffset();
            for (ProducedRecord record : records) {
                log.append(record);
            }
            // Under the same lock a waiter checks its predicate under, which is the only reason a
            // waiter can trust that "not enough bytes" was still true when it went to sleep.
            recordsAppended.signalAll();
            return baseOffset;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Answers one fetch, waiting for records when there are not yet enough of them.
     *
     * <p>The predicate is "at least {@code minBytes} of records are readable from {@code fetchOffset}",
     * and it is checked under {@link #lock} before every wait and after every wakeup. The deadline is
     * absolute: it is computed once from the injected clock, and each wait is for whatever is left of
     * it, so a spurious wakeup cannot extend the wait. When the deadline passes with too few bytes the
     * answer is what there is — often nothing at all — with the current high-water mark and no error,
     * because "there is nothing new yet" is an answer rather than a failure.
     *
     * @param fetchOffset the offset to read from; the high-water mark itself is legal and answers
     *                    empty
     * @param maxBytes    the most bytes of records the caller wants, capped by the broker's own bound
     * @param maxWaitMs   how long the broker may hold this request open; 0 answers immediately
     * @param minBytes    how many bytes are worth answering before that wait is up; 0 answers
     *                    immediately
     * @return the records and the high-water mark they were read against
     * @throws io.shrike.core.log.OffsetOutOfRangeException if {@code fetchOffset} is outside the range
     *                                                      the partition can serve
     * @throws io.shrike.core.log.CorruptRecordException    if a frame in the range no longer matches
     *                                                      what the log knows
     */
    FetchResponse fetch(long fetchOffset, int maxBytes, int maxWaitMs, int minBytes) {
        int servedMaxBytes = Math.min(maxBytes, maxFetchBytes);

        lock.lock();
        try {
            // Absolute, and computed once: every wait below is for what is left of this instant, so a
            // wakeup that proves nothing cannot hand the caller another full maxWaitMs.
            long deadlineMillis = timeSource.currentTimeMillis() + maxWaitMs;
            while (true) {
                long highWaterMark = log.nextOffset();
                byte[] records = log.readRange(fetchOffset, highWaterMark, servedMaxBytes);
                long remainingMillis = deadlineMillis - timeSource.currentTimeMillis();
                if (records.length >= minBytes || remainingMillis <= 0 || stopped) {
                    return new FetchResponse(highWaterMark, records);
                }

                waiterRegistered.run();
                try {
                    recordsAppended.await(remainingMillis, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    // Nothing in this broker interrupts a connection thread, so this is somebody else's
                    // signal to stop. The fetch answers with what it has and leaves the flag set for
                    // whoever set it, rather than swallowing it or failing a request that is fine.
                    Thread.currentThread().interrupt();
                    long interruptedAt = log.nextOffset();
                    return new FetchResponse(interruptedAt, log.readRange(fetchOffset, interruptedAt,
                            servedMaxBytes));
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * @return the offset this partition will append next, which is the exclusive end of what a fetch
     *         can be served
     */
    long highWaterMark() {
        lock.lock();
        try {
            return log.nextOffset();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Wakes every waiting fetch so that a broker on its way down does not have to wait out somebody's
     * long poll. The woken fetches answer with what they have; their connections are being closed
     * anyway.
     */
    void stopServing() {
        lock.lock();
        try {
            stopped = true;
            recordsAppended.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Closes the log, which forces the segment still taking records.
     *
     * @throws io.shrike.core.log.ShrikeIOException if the log cannot be closed
     */
    @Override
    public void close() {
        lock.lock();
        try {
            log.close();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Installs the test seam described on {@link #waiterRegistered}.
     *
     * @param seam what to run when a fetch registers as a waiter
     */
    void onWaiterRegistered(Runnable seam) {
        Objects.requireNonNull(seam, "seam");
        lock.lock();
        try {
            waiterRegistered = seam;
        } finally {
            lock.unlock();
        }
    }

    private void refuseIfTooLarge(ProducedRecord record) {
        byte[] key = record.key();
        int keyLength = key == null ? RecordFrame.NULL_KEY_LENGTH : key.length;
        long recordBytes = RecordFrame.frameBytes(keyLength, record.value().length);
        if (recordBytes > logConfig.maxRecordBytes()) {
            throw new RecordTooLargeException(topic, partition, recordBytes, logConfig.maxRecordBytes());
        }
    }
}
