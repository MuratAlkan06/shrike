package io.shrike.core.time;

/**
 * Reads the host wall clock. This is the one place in the broker allowed to call
 * {@link System#currentTimeMillis()}; everywhere else takes a {@link TimeSource}.
 */
public final class SystemTimeSource implements TimeSource {

    @Override
    public long currentTimeMillis() {
        return System.currentTimeMillis();
    }
}
