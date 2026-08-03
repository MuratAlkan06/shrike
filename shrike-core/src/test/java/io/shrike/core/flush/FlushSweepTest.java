package io.shrike.core.flush;

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
 * The flush thread: that a pass reads the injected clock, that the thread does flush, and that closing
 * it ends it.
 *
 * <p>The bound in {@link #flushesOnItsOwnNamedThreadUntilItIsClosed()} is a failure bound, not a
 * timing assertion: it is far longer than the passes it waits for could legitimately take, so crossing
 * it means the thread never ran rather than that it ran slowly. Nothing here sleeps.
 */
class FlushSweepTest {

    /** Long enough that reaching it means something is wrong, short enough that a build still ends. */
    private static final long TIMEOUT_SECONDS = 15L;

    private static final long ONE_HUNDRED_MS = 100L;

    @Test
    void flushesWithTheTimeItReadsFromTheInjectedClock() {
        AtomicLong flushedAtMillis = new AtomicLong(-1L);
        TimeSource clock = () -> 1_700_000_000_000L;

        try (FlushSweep flush = new FlushSweep(flushedAtMillis::set, clock, ONE_HUNDRED_MS)) {
            flush.runOnce();
        }

        assertEquals(1_700_000_000_000L, flushedAtMillis.get(),
                "what counts as overdue is measured against the injected clock and nothing else");
    }

    @Test
    void flushesOnItsOwnNamedThreadUntilItIsClosed() {
        CountDownLatch twoPasses = new CountDownLatch(2);
        AtomicReference<String> flushingThread = new AtomicReference<>();

        FlushSweep flush = new FlushSweep(nowMillis -> {
            flushingThread.set(Thread.currentThread().getName());
            twoPasses.countDown();
        }, () -> 1L, 1L);
        try {
            flush.start();
            awaitLatch(twoPasses, "the flush thread to make two passes");
        } finally {
            flush.close();
        }

        assertEquals(FlushSweep.THREAD_NAME, flushingThread.get(),
                "a thread dump has to say which thread is inside an fsync");
        assertTrue(Thread.getAllStackTraces().keySet().stream()
                        .noneMatch(thread -> FlushSweep.THREAD_NAME.equals(thread.getName())),
                "closing a sweep ends its thread rather than leaving it running");
    }

    @Test
    void refusesAnIntervalThatWouldFlushAsFastAsItCan() {
        assertThrows(IllegalArgumentException.class,
                () -> new FlushSweep(nowMillis -> { }, () -> 1L, 0L));
    }

    @Test
    void closesWithoutWaitingForAnythingWhenItWasNeverStarted() {
        FlushSweep flush = new FlushSweep(nowMillis -> fail("a sweep that never started must not run"),
                () -> 1L, ONE_HUNDRED_MS);

        flush.close();
        flush.close();
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
