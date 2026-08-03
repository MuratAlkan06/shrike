package io.shrike.core.flush;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import io.shrike.core.time.TimeSource;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The flush policy's own thread, and the one thing it does: every {@code flush.interval.ms}, ask every
 * log to force the records that have been sitting unforced for that long.
 *
 * <p><strong>Threads.</strong> One platform thread named {@value #THREAD_NAME}, so a thread dump says
 * which thread is inside an fsync. Between passes it waits on the stop signal rather than sleeping:
 * the predicate is "this sweep is stopping", so a shutdown ends it at once instead of after the rest
 * of an interval, and nothing here polls anything.
 *
 * <p><strong>Time.</strong> Which logs are due is decided from the injected {@link TimeSource}, and
 * {@link #runOnce()} is the whole of that decision, taken synchronously on the caller's thread. So a
 * test advances a clock and calls {@code runOnce()}; the only thing measured in real time is how often
 * production asks.
 *
 * <p><strong>What it is not.</strong> This thread is the time half of {@code flush.mode} and nothing
 * more. {@code flush.interval.bytes} is decided by the append that crosses it, and
 * {@link io.shrike.core.log.FlushMode#PER_RECORD} never leaves this thread anything to force, because
 * an append in that mode forces before it returns.
 *
 * <p><strong>Failures.</strong> A pass that throws is a WARN and nothing more. The thread stays alive,
 * because a flush thread that died on one unwritable partition would leave every other partition
 * unforced and say so only once.
 */
public final class FlushSweep implements AutoCloseable {

    /** The name reserved for this thread. Every thread in this broker is named {@code shrike-*}. */
    public static final String THREAD_NAME = "shrike-flush";

    private static final System.Logger LOGGER = System.getLogger(FlushSweep.class.getName());

    /** How long {@link #close()} waits for a pass in flight before saying it did not finish. */
    private static final long STOP_TIMEOUT_MILLIS = 10_000L;

    private final LogFlush logs;
    private final TimeSource timeSource;
    private final long checkIntervalMillis;

    /** Read by the flushing thread on every pass and set once by {@link #close()}. */
    private final AtomicBoolean stopping = new AtomicBoolean();

    /**
     * Counted down once, by {@link #close()}, immediately after {@link #stopping} is set. It is what
     * the flushing thread waits on between passes, so the wait ends the moment the predicate it is
     * about becomes true rather than when its timeout runs out.
     */
    private final CountDownLatch stopSignal = new CountDownLatch(1);

    /**
     * The one flushing thread. Not final because it starts after this object is built, and volatile
     * because {@link #close()} may be called from a thread that never watched it being set — including
     * before {@link #start()} was ever called, which is a close that has nothing to join.
     */
    private volatile Thread flusher;

    /**
     * @param logs                what a pass does
     * @param timeSource          the clock every interval is measured against
     * @param checkIntervalMillis how long to wait between passes, which is {@code flush.interval.ms}:
     *                            asking exactly as often as the bound is what keeps the window of
     *                            unforced records at one interval rather than at two
     * @throws IllegalArgumentException if the interval is not at least one millisecond, which would be
     *                                  a thread forcing as fast as it can
     */
    public FlushSweep(LogFlush logs, TimeSource timeSource, long checkIntervalMillis) {
        this.logs = Objects.requireNonNull(logs, "logs");
        this.timeSource = Objects.requireNonNull(timeSource, "timeSource");
        if (checkIntervalMillis < 1L) {
            throw new IllegalArgumentException("checkIntervalMillis must be at least 1, but was "
                    + checkIntervalMillis);
        }
        this.checkIntervalMillis = checkIntervalMillis;
    }

    /**
     * Starts the flushing thread. The first pass happens one interval from now, not immediately: a
     * broker that starts and stops again has nothing to force on the way through, and closing a log
     * forces it anyway.
     */
    public void start() {
        Thread starting = new Thread(this::flushUntilStopped, THREAD_NAME);
        flusher = starting;
        starting.start();
    }

    /**
     * Runs one pass on the calling thread, reading the clock once. This is the whole of what the thread
     * does, which is what lets a test advance an injected clock and call this instead of waiting for an
     * interval to pass.
     */
    public void runOnce() {
        logs.flushIfDue(timeSource.currentTimeMillis());
    }

    /**
     * Stops flushing and waits, under a bound, for a pass already in flight. Calling it twice does
     * nothing the second time, and calling it on a sweep that was never started does nothing at all.
     */
    @Override
    public void close() {
        if (!stopping.compareAndSet(false, true)) {
            return;
        }
        stopSignal.countDown();
        joinBounded(flusher);
    }

    private void flushUntilStopped() {
        while (!stopping.get()) {
            awaitStopFor(checkIntervalMillis);
            if (stopping.get()) {
                return;
            }
            try {
                runOnce();
            } catch (RuntimeException e) {
                LOGGER.log(System.Logger.Level.WARNING, THREAD_NAME + " could not finish a pass, so it waits "
                        + checkIntervalMillis + "ms and flushes again", e);
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
