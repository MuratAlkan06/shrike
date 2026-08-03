package io.shrike.core.log;

/**
 * {@code flush.mode}: when a log's records reach the device, and so what an acknowledged record
 * survives.
 *
 * <p>This is a durability setting and nothing else. Delivery is at-least-once whichever mode a broker
 * runs in, and neither axis implies the other: a mode says what a failure of the machine may cost, and
 * at-least-once says what a failure of a consumer may cost.
 *
 * <p>A mode is an enum rather than a string because a spelling this build does not understand has to
 * be a compile error rather than a broker that starts and quietly promises something else.
 */
public enum FlushMode {

    /**
     * {@code per-record}: every append forces its segment before it returns, so a produce is
     * acknowledged only after {@code force()}, and an acknowledged record survives an
     * operating-system or power failure.
     *
     * <p>The cost is one fsync per record, paid on the thread the producer is waiting on and under the
     * partition's own lock, so a partition serves one produce at a time at the speed of its device.
     */
    PER_RECORD,

    /**
     * {@code interval}: the default. Records are forced once {@code flush.interval.ms} has passed or
     * {@code flush.interval.bytes} of them have been appended without a force, whichever comes first.
     *
     * <p>Acknowledged records survive a process crash, because they are in the page cache; up to one
     * flush interval of acknowledged records can be lost on an operating-system or power failure,
     * after which recovery truncates the torn tail.
     */
    INTERVAL
}
