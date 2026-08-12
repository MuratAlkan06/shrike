package io.shrike.core.net;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.shrike.core.time.MonotonicTimeSource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The reaper thread: that a pass reads the injected clock, that the thread does make passes, and that
 * closing it ends it.
 *
 * <p>The bound in {@link #reapsOnItsOwnNamedThreadUntilItIsClosed()} is a failure bound, not a timing
 * assertion: it is far longer than the two passes it waits for could legitimately take, so crossing it
 * means the thread never ran rather than that it ran slowly. Nothing here sleeps, and what the bound
 * itself means is proved against a clock a test sets, in {@link BrokerReadTimeoutTest}.
 */
class ConnectionReaperTest {

    /** Long enough that reaching it means something is wrong, short enough that a build still ends. */
    private static final long TIMEOUT_SECONDS = 15L;

    private static final long THIRTY_SECONDS_MS = 30_000L;

    /** Half an hour of elapsed time, which is a number this reaper only ever subtracts from. */
    private static final long AN_ELAPSED_READING_NANOS = 1_800_000_000_000L;

    /** What a stub of {@link io.shrike.core.time.MonotonicTimeSource} answers when nothing moves. */
    private static final long ONE_NANOSECOND_IN = 1L;

    @Test
    void looksForStalledConnectionsWithTheTimeItReadsFromTheInjectedClock() {
        AtomicLong lookedAtNanos = new AtomicLong(-1L);
        MonotonicTimeSource elapsed = () -> AN_ELAPSED_READING_NANOS;

        try (ConnectionReaper reaper = new ConnectionReaper(lookedAtNanos::set, elapsed, THIRTY_SECONDS_MS)) {
            reaper.runOnce();
        }

        assertEquals(AN_ELAPSED_READING_NANOS, lookedAtNanos.get(),
                "how long a connection has been reading is measured against the injected clock and nothing else");
    }

    @Test
    void reapsOnItsOwnNamedThreadUntilItIsClosed() {
        CountDownLatch twoPasses = new CountDownLatch(2);
        AtomicReference<String> reapingThread = new AtomicReference<>();

        ConnectionReaper reaper = new ConnectionReaper(nowNanos -> {
            reapingThread.set(Thread.currentThread().getName());
            twoPasses.countDown();
        }, () -> ONE_NANOSECOND_IN, 1L);
        try {
            reaper.start();
            awaitLatch(twoPasses, "the reaper thread to make two passes");
        } finally {
            reaper.close();
        }

        assertEquals(ConnectionReaper.THREAD_NAME, reapingThread.get(),
                "a thread dump has to say which thread closed a connection somebody was holding");
        assertTrue(Thread.getAllStackTraces().keySet().stream()
                        .noneMatch(thread -> ConnectionReaper.THREAD_NAME.equals(thread.getName())),
                "closing a reaper ends its thread rather than leaving it running");
    }

    /**
     * A pass that fails with an {@link Error} rather than with a {@link RuntimeException}.
     *
     * <p>The distinction used to decide whether this broker kept either of its connection bounds. A pass
     * walks every open connection, which allocates an iterator; it formats a DEBUG line for each one it
     * closes; and it closes sockets. Under the memory pressure where a stalled peer holding a place under
     * the cap costs the most, any of those can fail with an {@code OutOfMemoryError} — and an {@code
     * Error} is not a {@code RuntimeException}, so it ended this thread and took the read bound and the
     * write bound with it, silently, leaving nothing at all to close a connection that had stopped
     * speaking or stopped listening.
     */
    @Test
    void keepsReapingAfterAPassThrowsAnErrorRatherThanLosingBothBoundsWithThatPass() {
        CountDownLatch twoPassesAfterTheOneThatThrew = new CountDownLatch(2);
        AtomicBoolean failTheFirstPass = new AtomicBoolean(true);
        AtomicReference<String> reapingThread = new AtomicReference<>();

        ConnectionReaper reaper = new ConnectionReaper(nowNanos -> {
            if (failTheFirstPass.compareAndSet(true, false)) {
                throw new OutOfMemoryError("a pass that could not allocate (thrown by a test)");
            }
            reapingThread.set(Thread.currentThread().getName());
            twoPassesAfterTheOneThatThrew.countDown();
        }, () -> ONE_NANOSECOND_IN, 1L);
        try {
            reaper.start();
            awaitLatch(twoPassesAfterTheOneThatThrew, "the reaper to make two more passes after one threw an Error");
        } finally {
            reaper.close();
        }

        assertEquals(ConnectionReaper.THREAD_NAME, reapingThread.get(),
                "the passes after the throw are the same named thread's: one pass that failed does not take"
                        + " the read bound and the write bound down with it");
    }

    @Test
    void refusesAnIntervalThatWouldWalkEveryConnectionAsFastAsItCan() {
        assertThrows(IllegalArgumentException.class,
                () -> new ConnectionReaper(nowNanos -> { }, () -> ONE_NANOSECOND_IN, 0L));
    }

    @Test
    void closesWithoutWaitingForAnythingWhenItWasNeverStarted() {
        ConnectionReaper reaper = new ConnectionReaper(nowNanos -> fail("a reaper that never started must not run"),
                () -> ONE_NANOSECOND_IN, THIRTY_SECONDS_MS);

        reaper.close();
        reaper.close();
    }

    private static void awaitLatch(CountDownLatch latch, String what) {
        try {
            assertTrue(latch.await(TIMEOUT_SECONDS, SECONDS), what);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("interrupted while waiting for " + what, e);
        }
    }
}
