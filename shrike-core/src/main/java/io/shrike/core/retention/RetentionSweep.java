package io.shrike.core.retention;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import io.shrike.core.time.TimeSource;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Retention's own thread, and the one thing it does: every {@code checkIntervalMillis}, ask the
 * broker to delete the sealed segments it no longer keeps.
 *
 * <p><strong>Threads.</strong> One platform thread named {@value #THREAD_NAME}, so a thread dump says
 * which thread is deleting a file. Between sweeps it waits on the stop signal rather than sleeping:
 * the predicate is "this sweep is stopping", so a shutdown ends it at once instead of after the rest
 * of an interval, and nothing here polls anything.
 *
 * <p><strong>Time.</strong> Which segments are retired is decided from the injected
 * {@link TimeSource}, and {@link #runOnce()} is the whole of that decision, taken synchronously on the
 * caller's thread. So a test advances a clock and calls {@code runOnce()}; the only thing measured in
 * real time is how often production asks, which is not what retention means.
 *
 * <p><strong>Failures.</strong> A sweep that throws is a WARN and nothing more. The thread stays
 * alive, because a retention thread that died on one unreadable partition would let every other
 * partition grow without bound and say so only once.
 */
public final class RetentionSweep implements AutoCloseable {

    /** The name reserved for this thread. Every thread in this broker is named {@code shrike-*}. */
    public static final String THREAD_NAME = "shrike-retention";

    /**
     * One minute between sweeps. Retention is a bound on storage rather than a deadline, so the cost
     * of asking late is a partition that sits over its bound for up to a minute, and the cost of
     * asking often is a walk of every partition for nothing. A minute is the cheap end of that trade
     * for a broker holding a handful of partitions on one disk.
     */
    public static final long DEFAULT_CHECK_INTERVAL_MILLIS = 60_000L;

    private static final System.Logger LOGGER = System.getLogger(RetentionSweep.class.getName());

    /** How long {@link #close()} waits for a sweep in flight before saying it did not finish. */
    private static final long STOP_TIMEOUT_MILLIS = 10_000L;

    private final SegmentDeletion segments;
    private final TimeSource timeSource;
    private final long checkIntervalMillis;

    /** Read by the sweeping thread on every pass and set once by {@link #close()}. */
    private final AtomicBoolean stopping = new AtomicBoolean();

    /**
     * Counted down once, by {@link #close()}, immediately after {@link #stopping} is set. It is what
     * the sweeping thread waits on between sweeps, so the wait ends the moment the predicate it is
     * about becomes true rather than when its timeout runs out.
     */
    private final CountDownLatch stopSignal = new CountDownLatch(1);

    /**
     * The one sweeping thread. Not final because it starts after this object is built, and volatile
     * because {@link #close()} may be called from a thread that never watched it being set — including
     * before {@link #start()} was ever called, which is a close that has nothing to join.
     */
    private volatile Thread sweeper;

    /**
     * @param segments            what a sweep does
     * @param timeSource          the clock every age is measured against
     * @param checkIntervalMillis how long to wait between sweeps
     * @throws IllegalArgumentException if the interval is not at least one millisecond, which would be
     *                                  a thread sweeping as fast as it can
     */
    public RetentionSweep(SegmentDeletion segments, TimeSource timeSource, long checkIntervalMillis) {
        this.segments = Objects.requireNonNull(segments, "segments");
        this.timeSource = Objects.requireNonNull(timeSource, "timeSource");
        if (checkIntervalMillis < 1L) {
            throw new IllegalArgumentException("checkIntervalMillis must be at least 1, but was "
                    + checkIntervalMillis);
        }
        this.checkIntervalMillis = checkIntervalMillis;
    }

    /**
     * Starts the sweeping thread. The first sweep happens one interval from now, not immediately: a
     * broker that starts and stops again should not have deleted anything on the way through.
     */
    public void start() {
        Thread starting = new Thread(this::sweepUntilStopped, THREAD_NAME);
        sweeper = starting;
        starting.start();
    }

    /**
     * Runs one sweep on the calling thread, reading the clock once. This is the whole of what the
     * thread does, which is what lets a test advance an injected clock and call this instead of
     * waiting for an interval to pass. What a sweep deleted is in the broker's log rather than in a
     * return value, because deleting stored records is a fact an operator should be able to read
     * afterwards and not only a number this caller could have thrown away.
     */
    public void runOnce() {
        segments.deleteRetiredSegments(timeSource.currentTimeMillis());
    }

    /**
     * Stops sweeping and waits, under a bound, for a sweep already in flight. Calling it twice does
     * nothing the second time, and calling it on a sweep that was never started does nothing at all.
     */
    @Override
    public void close() {
        if (!stopping.compareAndSet(false, true)) {
            return;
        }
        stopSignal.countDown();
        joinBounded(sweeper);
    }

    private void sweepUntilStopped() {
        while (!stopping.get()) {
            awaitStopFor(checkIntervalMillis);
            if (stopping.get()) {
                return;
            }
            try {
                runOnce();
            } catch (RuntimeException e) {
                LOGGER.log(System.Logger.Level.WARNING, THREAD_NAME + " could not finish a sweep, so it waits "
                        + checkIntervalMillis + "ms and sweeps again", e);
            }
        }
    }

    /**
     * Waits for this sweep to start stopping, for at most {@code timeoutMillis}. The predicate is the
     * stop signal itself, so this returns immediately once {@link #close()} has begun.
     */
    private void awaitStopFor(long timeoutMillis) {
        try {
            stopSignal.await(timeoutMillis, MILLISECONDS);
        } catch (InterruptedException e) {
            // Somebody wants this thread to stop waiting. Stop waiting, and leave the flag for them.
            Thread.currentThread().interrupt();
        }
    }

    private static void joinBounded(Thread thread) {
        if (thread == null) {
            return;
        }
        try {
            // Elapsed time, not wall-clock time, and so not the injected clock: how long a shutdown
            // has been waiting for a thread is a fact about this machine that no test may freeze.
            thread.join(STOP_TIMEOUT_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (thread.isAlive()) {
            LOGGER.log(System.Logger.Level.WARNING, () -> THREAD_NAME + " did not stop within " + STOP_TIMEOUT_MILLIS
                    + "ms, so this broker stopped waiting for it");
        }
    }
}
