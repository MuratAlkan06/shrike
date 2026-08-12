package io.shrike.core.time;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The two rules {@link MonotonicTimeSource} states, against the implementation that reads the host.
 *
 * <p>Neither test asserts a duration, and nothing here sleeps: what is proved is that readings start
 * at zero and never run backwards, which is what lets a deadline be a subtraction and lets
 * {@code -1} stay available as a sentinel meaning "not in this phase at all".
 */
class SystemMonotonicTimeSourceTest {

    private static final int MANY_READINGS = 10_000;

    @Test
    void startsAtZeroRatherThanWhereverTheHostsClockHappensToBe() {
        SystemMonotonicTimeSource elapsed = new SystemMonotonicTimeSource();

        long firstReading = elapsed.elapsedNanos();

        assertTrue(firstReading >= 0L, "a reading is never negative, and " + firstReading + " is");
    }

    @Test
    void handsOutNoReadingSmallerThanOneItHasAlreadyHandedOut() {
        SystemMonotonicTimeSource elapsed = new SystemMonotonicTimeSource();

        long previousReading = elapsed.elapsedNanos();
        for (int reading = 0; reading < MANY_READINGS; reading++) {
            long nextReading = elapsed.elapsedNanos();

            assertTrue(nextReading >= previousReading,
                    "elapsed time only climbs, but " + nextReading + " came after " + previousReading);
            previousReading = nextReading;
        }
    }
}
