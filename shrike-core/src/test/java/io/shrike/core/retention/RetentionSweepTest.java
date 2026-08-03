package io.shrike.core.retention;

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
 * The retention thread: that a sweep reads the injected clock, that the thread does sweep, and that
 * closing it ends it.
 *
 * <p>The bound in {@link #sweepsOnItsOwnNamedThreadUntilItIsClosed()} is a failure bound, not a timing
 * assertion: it is far longer than the sweeps it waits for could legitimately take, so crossing it
 * means the thread never ran rather than that it ran slowly. Nothing here sleeps.
 */
class RetentionSweepTest {

    /** Long enough that reaching it means something is wrong, short enough that a build still ends. */
    private static final long TIMEOUT_SECONDS = 15L;

    private static final long ONE_MINUTE_MS = 60_000L;

    @Test
    void sweepsWithTheTimeItReadsFromTheInjectedClock() {
        AtomicLong sweptAtMillis = new AtomicLong(-1L);
        TimeSource clock = () -> 1_700_000_000_000L;

        try (RetentionSweep sweep = new RetentionSweep(sweptAtMillis::set, clock, ONE_MINUTE_MS)) {
            sweep.runOnce();
        }

        assertEquals(1_700_000_000_000L, sweptAtMillis.get(),
                "what counts as old is measured against the injected clock and nothing else");
    }

    @Test
    void sweepsOnItsOwnNamedThreadUntilItIsClosed() {
        CountDownLatch twoSweeps = new CountDownLatch(2);
        AtomicReference<String> sweepingThread = new AtomicReference<>();

        RetentionSweep sweep = new RetentionSweep(nowMillis -> {
            sweepingThread.set(Thread.currentThread().getName());
            twoSweeps.countDown();
        }, () -> 1L, 1L);
        try {
            sweep.start();
            awaitLatch(twoSweeps, "the retention thread to sweep twice");
        } finally {
            sweep.close();
        }

        assertEquals(RetentionSweep.THREAD_NAME, sweepingThread.get(),
                "a thread dump has to say which thread is deleting a file");
        assertTrue(Thread.getAllStackTraces().keySet().stream()
                        .noneMatch(thread -> RetentionSweep.THREAD_NAME.equals(thread.getName())),
                "closing a sweep ends its thread rather than leaving it running");
    }

    @Test
    void refusesAnIntervalThatWouldSweepAsFastAsItCan() {
        assertThrows(IllegalArgumentException.class,
                () -> new RetentionSweep(nowMillis -> { }, () -> 1L, 0L));
    }

    @Test
    void closesWithoutWaitingForAnythingWhenItWasNeverStarted() {
        RetentionSweep sweep = new RetentionSweep(nowMillis -> fail("a sweep that never started must not run"),
                () -> 1L, ONE_MINUTE_MS);

        sweep.close();
        sweep.close();
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
