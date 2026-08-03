package io.shrike.core.net;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.shrike.core.time.TimeSource;
import java.util.concurrent.CountDownLatch;
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

    @Test
    void looksForStalledConnectionsWithTheTimeItReadsFromTheInjectedClock() {
        AtomicLong lookedAtMillis = new AtomicLong(-1L);
        TimeSource clock = () -> 1_700_000_000_000L;

        try (ConnectionReaper reaper = new ConnectionReaper(lookedAtMillis::set, clock, THIRTY_SECONDS_MS)) {
            reaper.runOnce();
        }

        assertEquals(1_700_000_000_000L, lookedAtMillis.get(),
                "how long a connection has been reading is measured against the injected clock and nothing else");
    }

    @Test
    void reapsOnItsOwnNamedThreadUntilItIsClosed() {
        CountDownLatch twoPasses = new CountDownLatch(2);
        AtomicReference<String> reapingThread = new AtomicReference<>();

        ConnectionReaper reaper = new ConnectionReaper(nowMillis -> {
            reapingThread.set(Thread.currentThread().getName());
            twoPasses.countDown();
        }, () -> 1L, 1L);
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

    @Test
    void refusesAnIntervalThatWouldWalkEveryConnectionAsFastAsItCan() {
        assertThrows(IllegalArgumentException.class,
                () -> new ConnectionReaper(nowMillis -> { }, () -> 1L, 0L));
    }

    @Test
    void closesWithoutWaitingForAnythingWhenItWasNeverStarted() {
        ConnectionReaper reaper = new ConnectionReaper(nowMillis -> fail("a reaper that never started must not run"),
                () -> 1L, THIRTY_SECONDS_MS);

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
