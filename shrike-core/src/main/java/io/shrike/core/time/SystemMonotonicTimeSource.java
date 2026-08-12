package io.shrike.core.time;

/**
 * Reads the host's elapsed-time clock. This is the one place in the broker allowed to call
 * {@link System#nanoTime()} for a deadline; everywhere else takes a {@link MonotonicTimeSource}.
 *
 * <p>{@code System.nanoTime()} has no defined origin — it may be any value, including a negative one —
 * so the origin this source uses is the moment it was built, and every reading is measured from there.
 * That is what makes the two rules of the interface true of this implementation: readings start at
 * zero rather than wherever the host happens to be, and they only climb, because the underlying clock
 * does. A broker would have to run for about 292 years for the subtraction below to overflow.
 */
public final class SystemMonotonicTimeSource implements MonotonicTimeSource {

    private final long originNanos = System.nanoTime();

    @Override
    public long elapsedNanos() {
        return System.nanoTime() - originNanos;
    }
}
