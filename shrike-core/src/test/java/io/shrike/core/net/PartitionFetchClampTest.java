package io.shrike.core.net;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * What a fetch asks for against what this broker will do: plain arithmetic, tested plainly. Neither
 * number a request carries is a number the broker has to believe — one of them buys a connection slot
 * for as long as it likes, and the other can name a size no append could ever reach.
 */
class PartitionFetchClampTest {

    private static final int BROKER_MAX_FETCH_WAIT_MS = 30_000;
    private static final int SERVED_MAX_BYTES = 4 * 1024 * 1024;

    @Test
    void cutsAWaitLongerThanTheBrokersOwnBoundDownToIt() {
        assertEquals(BROKER_MAX_FETCH_WAIT_MS,
                Partition.clampedWaitMs(Integer.MAX_VALUE, BROKER_MAX_FETCH_WAIT_MS),
                "a wire field can ask for nearly twenty-five days; the broker's bound is what happens");
        assertEquals(BROKER_MAX_FETCH_WAIT_MS,
                Partition.clampedWaitMs(BROKER_MAX_FETCH_WAIT_MS + 1, BROKER_MAX_FETCH_WAIT_MS));
    }

    @Test
    void leavesAWaitTheBrokerIsWillingToServe() {
        assertEquals(0, Partition.clampedWaitMs(0, BROKER_MAX_FETCH_WAIT_MS),
                "a fetch that asked not to wait still does not wait");
        assertEquals(250, Partition.clampedWaitMs(250, BROKER_MAX_FETCH_WAIT_MS));
        assertEquals(BROKER_MAX_FETCH_WAIT_MS,
                Partition.clampedWaitMs(BROKER_MAX_FETCH_WAIT_MS, BROKER_MAX_FETCH_WAIT_MS),
                "the bound itself is allowed: it is a cap, not a ceiling to stay under");
    }

    @Test
    void cutsAMinBytesLargerThanThisFetchCouldEverBeServedDownToThatMuch() {
        assertEquals(SERVED_MAX_BYTES, Partition.clampedMinBytes(Integer.MAX_VALUE, SERVED_MAX_BYTES),
                "a predicate no append could satisfy is a fetch that sleeps out its whole deadline");
        assertEquals(SERVED_MAX_BYTES, Partition.clampedMinBytes(SERVED_MAX_BYTES + 1, SERVED_MAX_BYTES));
    }

    @Test
    void leavesAMinBytesRecordsCanReach() {
        assertEquals(0, Partition.clampedMinBytes(0, SERVED_MAX_BYTES),
                "a fetch that is happy with nothing is answered on the first pass");
        assertEquals(1, Partition.clampedMinBytes(1, SERVED_MAX_BYTES));
        assertEquals(SERVED_MAX_BYTES, Partition.clampedMinBytes(SERVED_MAX_BYTES, SERVED_MAX_BYTES));
    }
}
