package io.shrike.core.net;

import io.shrike.core.log.Log;
import io.shrike.core.log.LogConfig;
import io.shrike.core.log.ProducedRecord;
import io.shrike.core.log.RecordFrame;
import io.shrike.core.log.RecordTooLargeException;
import io.shrike.core.log.SegmentedLog;
import io.shrike.core.time.TimeSource;
import java.io.Closeable;
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
 * {@link #lock} — connection threads take turns rather than sharing a file position, and so does the
 * retention thread when it deletes a segment. The high-water mark needs no field of its own for the
 * same reason: it <em>is</em> the log's next offset, read under the lock that the appending thread
 * held when it moved it, so there is no second copy of it to drift.
 *
 * <p>A fetch that has fewer bytes than it asked for checks what it has and registers as a waiter
 * <em>under the same lock</em> that a produce holds while it appends and signals. That is what makes
 * a lost wakeup impossible: an append landing "just after" the check cannot have landed, because it
 * cannot hold the lock until the fetch has released it inside {@link Condition#await(long, TimeUnit)}.
 * The wait is bounded by an absolute deadline computed once, the predicate is re-checked on every
 * wakeup, and nothing anywhere sleeps or polls.
 *
 * <p>What a fetch asks for is clamped to what this broker can answer with, in both directions: its
 * {@code maxWaitMs} to {@link BrokerConfig#maxFetchWaitMs()}, and its {@code minBytes} to the most
 * bytes a fetch can ever be served. See {@link #clampedWaitMs(int, int)} and
 * {@link #clampedMinBytes(int, int)}.
 *
 * <p>An answer may leave this class holding a file open — see {@link #readable} — and it leaves under
 * the lock rather than after it. That is what lets the connection thread go on sending a fetch's bytes
 * while this partition takes appends and deletes segments behind it.
 */
final class Partition implements Closeable {

    /** No high-water mark has been read yet, so the first pass through a fetch always reads records. */
    private static final long NOTHING_READ_YET = -1L;

    private final String topic;
    private final int partition;
    private final LogConfig logConfig;
    private final int maxFetchBytes;

    /** The longest this partition will hold a fetch open, whatever the request asked for. */
    private final int maxFetchWaitMs;

    /** Whether a fetch is answered out of the segment file rather than out of a buffer. */
    private final boolean zeroCopyFetch;

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

    private Partition(String topic, int partition, BrokerConfig config, TimeSource timeSource, Log log) {
        this.topic = topic;
        this.partition = partition;
        this.logConfig = config.logConfig();
        // The most bytes one fetch may be answered with is max.request.bytes: the one number that says
        // how much memory one connection may make this broker hold, whichever way the bytes travel.
        this.maxFetchBytes = config.maxRequestBytes();
        this.maxFetchWaitMs = config.maxFetchWaitMs();
        this.zeroCopyFetch = config.zeroCopyFetch();
        this.timeSource = timeSource;
        this.log = log;
    }

    /**
     * Opens one partition's log, recovering it when it is already on disk.
     *
     * @param config     where things live and how much of them there may be; this partition takes its
     *                   data directory, its log sizes, and the two bounds a fetch is clamped to from it
     * @param topic      the topic this partition belongs to
     * @param partition  the partition number within that topic
     * @param timeSource the clock that stamps appended records
     * @return the open partition
     */
    static Partition open(BrokerConfig config, String topic, int partition, TimeSource timeSource) {
        Log log = SegmentedLog.open(config.dataDirectory(), topic, partition, timeSource, config.logConfig());
        return new Partition(topic, partition, config, timeSource, log);
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
     * <p>Both of the caller's numbers are clamped before any of that: the wait to the broker's own
     * {@code maxFetchWaitMs}, and {@code minBytes} to the most this fetch could ever be served, so the
     * predicate is one that records can actually satisfy. A wakeup that finds the high-water mark where
     * it left it re-waits without reading the log again — the bytes readable from a fixed offset cannot
     * have changed if the mark has not moved, and re-reading them would cost every waiter a full read
     * for every append anybody makes.
     *
     * @param fetchOffset the offset to read from; the high-water mark itself is legal and answers
     *                    empty
     * @param maxBytes    the most bytes of records the caller wants, capped by the broker's own bound
     * @param maxWaitMs   how long the broker may hold this request open, capped by the broker's own
     *                    bound; 0 answers immediately
     * @param minBytes    how many bytes are worth answering before that wait is up, capped by what can
     *                    be served; 0 answers immediately
     * @return the records and the high-water mark they were read against, in whichever of the two
     *         shapes {@link #readable} produced. The caller closes it
     * @throws io.shrike.core.log.OffsetOutOfRangeException if {@code fetchOffset} is outside the range
     *                                                      the partition can serve
     * @throws io.shrike.core.log.CorruptRecordException    if a frame in the range no longer matches
     *                                                      what the log knows
     */
    FetchedRecords fetch(long fetchOffset, int maxBytes, int maxWaitMs, int minBytes) {
        int servedMaxBytes = Math.min(maxBytes, maxFetchBytes);
        int servedMinBytes = clampedMinBytes(minBytes, servedMaxBytes);
        int servedMaxWaitMs = clampedWaitMs(maxWaitMs, maxFetchWaitMs);

        lock.lock();
        try {
            // Absolute, and computed once: every wait below is for what is left of this instant, so a
            // wakeup that proves nothing cannot hand the caller another full maxWaitMs.
            long deadlineMillis = timeSource.currentTimeMillis() + servedMaxWaitMs;
            long lastReadHighWaterMark = NOTHING_READ_YET;
            while (true) {
                long highWaterMark = log.nextOffset();
                long remainingMillis = deadlineMillis - timeSource.currentTimeMillis();
                boolean answerNow = remainingMillis <= 0 || stopped;
                if (answerNow || highWaterMark != lastReadHighWaterMark) {
                    FetchedRecords records = readable(fetchOffset, highWaterMark, servedMaxBytes);
                    if (answerNow || records.recordBytes() >= servedMinBytes) {
                        return records;
                    }
                    // Not enough to answer with yet, so whatever it was holding goes back before this
                    // fetch waits again: a channel kept across a wait is a descriptor held for as long
                    // as somebody's long poll.
                    records.close();
                    lastReadHighWaterMark = highWaterMark;
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
                    return readable(fetchOffset, interruptedAt, servedMaxBytes);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Reads what a fetch can be served this instant, by whichever of the two paths this broker was
     * started with. It is the only place {@code fetch.zero.copy} decides anything: the clamps, the
     * predicate, and the wait above it are the same either way, and both paths ask the log for the
     * same range, so which one served a fetch is not something its client can tell from the bytes.
     *
     * <p>Both are called under {@link #lock}, and for the zero-copy path that is the whole of the
     * concurrency story. The channel a range holds is opened here, under the same lock retention takes
     * to delete a segment — so a range either exists before the unlink, in which case it reads the
     * inode to the end however many names are left pointing at it, or it is located after the unlink,
     * in which case the segment is already out of the log and the offset is refused with the one the
     * log now starts at. There is no third case, and nothing is reference-counted to rule one out.
     *
     * @param fetchOffset    the offset to read from
     * @param highWaterMark  the exclusive offset to stop before, which is where the log is now
     * @param servedMaxBytes the most bytes this fetch may be answered with
     * @return the records, which the caller closes
     */
    private FetchedRecords readable(long fetchOffset, long highWaterMark, int servedMaxBytes) {
        if (zeroCopyFetch) {
            return new FetchedRecords.Pinned(highWaterMark,
                    log.openRange(fetchOffset, highWaterMark, servedMaxBytes));
        }
        return new FetchedRecords.Copied(highWaterMark,
                log.readRange(fetchOffset, highWaterMark, servedMaxBytes));
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
     * @return the lowest offset this partition can still serve, which retention moves forward as it
     *         deletes segments
     */
    long logStartOffset() {
        lock.lock();
        try {
            return log.logStartOffset();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Deletes the stored records retention no longer keeps, under the same lock a produce and a fetch
     * hold.
     *
     * <p>That lock is the whole of the concurrency story here. {@code shrike-retention} is a thread of
     * its own, and the segment list it shortens is the same list a fetch walks, so it takes its turn
     * like everything else rather than mutating storage beside a reader. What it holds the lock for is
     * an unlink and a couple of file closes, not a read of the log, so a sweep does not become a pause
     * for the connections using this partition.
     *
     * <p>A fetch that is part way through sending a deleted segment's bytes is not something this has
     * to wait for. The channels it closes are the segment's own; a fetch reads through one it opened
     * for itself, under this lock, and reads it to the end. {@link #readable} is where that is set out.
     *
     * @param nowMillis the epoch millisecond retention is evaluated at
     * @return how many segments were deleted
     * @throws io.shrike.core.log.ShrikeIOException if a segment's files cannot be removed
     */
    int deleteRetiredSegments(long nowMillis) {
        lock.lock();
        try {
            return log.deleteRetiredSegments(nowMillis);
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

    /**
     * How long a fetch may actually be held open.
     *
     * <p>The wire format carries {@code maxWaitMs} as an int32, so a client may ask for a wait of
     * nearly twenty-five days, and a broker that took it at its word would hand out a connection slot
     * and a platform thread for that long. The request is a preference; the bound is the broker's.
     *
     * @param requestedMaxWaitMs what the request asked for, which is never negative
     * @param maxFetchWaitMs     what this broker allows
     * @return the smaller of the two
     */
    static int clampedWaitMs(int requestedMaxWaitMs, int maxFetchWaitMs) {
        return Math.min(requestedMaxWaitMs, maxFetchWaitMs);
    }

    /**
     * How many bytes are actually worth waiting for.
     *
     * <p>A fetch is served at most {@code min(maxBytes, max.request.bytes)} bytes of records, so a
     * {@code minBytes} above that is a predicate no append could ever satisfy: the fetch would sleep out
     * its whole deadline with the records it asked for already on disk and already readable. Clamping it
     * to what can be served turns "wait for more than exists" into "wait until you can be filled".
     *
     * @param requestedMinBytes what the request asked for, which is never negative
     * @param servedMaxBytes    the most bytes this fetch can be answered with
     * @return the smaller of the two
     */
    static int clampedMinBytes(int requestedMinBytes, int servedMaxBytes) {
        return Math.min(requestedMinBytes, servedMaxBytes);
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
