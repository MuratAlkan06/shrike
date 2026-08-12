package io.shrike.core.net;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import io.shrike.core.time.MonotonicTimeSource;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The read bound's own thread, and the one thing it does: every {@code read.timeout.ms}, ask the broker
 * to close the connections that have been reading, or waiting to read, for longer than that.
 *
 * <p><strong>Threads.</strong> One platform thread named {@value #THREAD_NAME}, so a thread dump says
 * which thread closed a socket somebody was holding. Between passes it waits on the stop signal rather
 * than sleeping: the predicate is "this reaper is stopping", so a shutdown ends it at once instead of
 * after the rest of an interval, and nothing here polls anything.
 *
 * <p><strong>Time.</strong> Which connections are stalled is decided from the injected
 * {@link MonotonicTimeSource}, and {@link #runOnce()} is the whole of that decision, taken
 * synchronously on the caller's thread. So a test advances a clock and calls {@code runOnce()}; the
 * only thing measured in real time is how often production asks. The clock is the elapsed-time one
 * rather than the wall clock on purpose: a deadline is an amount of time that has to pass, and a host
 * whose date is stepped forward an hour by a time-synchronization daemon must not thereby close every
 * connection it is serving — nor keep a stalled one for an hour when the step goes the other way.
 *
 * <p><strong>How often it asks.</strong> Once per bound. A connection is therefore closed on the first
 * pass after it crosses the bound, which is between one and two bounds after it last made progress —
 * the same shape the flush interval has, and for the same reason: asking twice as often would double a
 * walk of every open connection to halve a number nothing depends on being exact.
 *
 * <p><strong>What a pass does not do.</strong> It closes sockets and nothing else. The place a reaped
 * connection held under the connection cap comes back on that connection's own thread as its blocking
 * read fails and {@code serve} returns, which is the one path that releases a slot however a connection
 * ends.
 *
 * <p><strong>Failures.</strong> A pass that throws is a WARN and nothing more. The thread stays alive,
 * because a reaper that died on one connection would leave every other stalled connection holding a
 * slot and say so only once.
 */
final class ConnectionReaper implements AutoCloseable {

    /** The name reserved for this thread. Every thread in this broker is named {@code shrike-*}. */
    static final String THREAD_NAME = "shrike-conn-reaper";

    private static final System.Logger LOGGER = System.getLogger(ConnectionReaper.class.getName());

    /** How long {@link #close()} waits for a pass in flight before saying it did not finish. */
    private static final long STOP_TIMEOUT_MILLIS = 10_000L;

    private final StalledConnections connections;
    private final MonotonicTimeSource monotonicTime;
    private final long checkIntervalMillis;

    /** Read by the reaping thread on every pass and set once by {@link #close()}. */
    private final AtomicBoolean stopping = new AtomicBoolean();

    /**
     * Counted down once, by {@link #close()}, immediately after {@link #stopping} is set. It is what the
     * reaping thread waits on between passes, so the wait ends the moment the predicate it is about
     * becomes true rather than when its timeout runs out.
     */
    private final CountDownLatch stopSignal = new CountDownLatch(1);

    /**
     * The one reaping thread. Not final because it starts after this object is built, and volatile
     * because {@link #close()} may be called from a thread that never watched it being set — including
     * before {@link #start()} was ever called, which is a close that has nothing to join.
     */
    private volatile Thread reaper;

    /**
     * @param connections         what a pass does
     * @param monotonicTime       the elapsed-time clock every read phase is measured against
     * @param checkIntervalMillis how long to wait between passes, which is {@code read.timeout.ms}:
     *                            asking exactly as often as the bound keeps a stalled connection's
     *                            overshoot at one bound rather than at several
     * @throws IllegalArgumentException if the interval is not at least one millisecond, which would be
     *                                  a thread walking every connection as fast as it can
     */
    ConnectionReaper(StalledConnections connections, MonotonicTimeSource monotonicTime, long checkIntervalMillis) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.monotonicTime = Objects.requireNonNull(monotonicTime, "monotonicTime");
        if (checkIntervalMillis < 1L) {
            throw new IllegalArgumentException("checkIntervalMillis must be at least 1, but was "
                    + checkIntervalMillis);
        }
        this.checkIntervalMillis = checkIntervalMillis;
    }

    /**
     * Starts the reaping thread. The first pass happens one interval from now, not immediately: no
     * connection can have crossed the bound before the bound has gone by once.
     */
    void start() {
        Thread starting = new Thread(this::reapUntilStopped, THREAD_NAME);
        reaper = starting;
        starting.start();
    }

    /**
     * Runs one pass on the calling thread, reading the clock once. This is the whole of what the thread
     * does, which is what lets a test advance an injected clock and call this instead of waiting for an
     * interval to pass.
     */
    void runOnce() {
        connections.closeStalledConnections(monotonicTime.elapsedNanos());
    }

    /**
     * Stops reaping and waits, under a bound, for a pass already in flight. Calling it twice does
     * nothing the second time, and calling it on a reaper that was never started does nothing at all.
     */
    @Override
    public void close() {
        if (!stopping.compareAndSet(false, true)) {
            return;
        }
        stopSignal.countDown();
        joinBounded(reaper);
    }

    private void reapUntilStopped() {
        while (!stopping.get()) {
            awaitStopFor(checkIntervalMillis);
            if (stopping.get()) {
                return;
            }
            try {
                runOnce();
            } catch (RuntimeException e) {
                LOGGER.log(System.Logger.Level.WARNING, THREAD_NAME + " could not finish a pass, so it waits "
                        + checkIntervalMillis + "ms and looks again", e);
            }
        }
    }

    /**
     * Waits for this reaper to start stopping, for at most {@code timeoutMillis}. The predicate is the
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
