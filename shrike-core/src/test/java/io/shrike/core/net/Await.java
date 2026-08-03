package io.shrike.core.net;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;

/**
 * Bounded waits for tests, so that a test which is going to fail fails instead of hanging.
 *
 * <p>Nothing here sleeps, and nothing here is a timing assertion: every bound is far longer than the
 * thing being waited for could legitimately take, so crossing one means the causality under test is
 * broken rather than slow. A test that wants to prove something happened <em>quickly</em> is a timing
 * test, and this repository allows exactly one of those per timing feature.
 */
final class Await {

    /** Long enough that reaching it means something is wrong, short enough that a build still ends. */
    static final long TIMEOUT_SECONDS = 15L;

    private Await() {
    }

    /**
     * @param latch what to wait for
     * @param what  what reaching the bound would mean, quoted in the failure
     */
    static void latch(CountDownLatch latch, String what) {
        try {
            assertTrue(latch.await(TIMEOUT_SECONDS, SECONDS), what);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("interrupted while waiting for " + what, e);
        }
    }

    /**
     * @param future what to wait for
     * @param what   what reaching the bound would mean, quoted in the failure
     * @param <T>    what the future holds
     * @return the value
     */
    static <T> T value(Future<T> future, String what) {
        try {
            return future.get(TIMEOUT_SECONDS, SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return fail("timed out waiting for " + what, e);
        } catch (ExecutionException e) {
            return fail(what + " failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fail("interrupted while waiting for " + what, e);
        }
    }
}
