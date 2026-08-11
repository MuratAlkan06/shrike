package io.shrike.core.time;

/**
 * How much time has gone by, which is a different question from what time it is.
 *
 * <p>{@link TimeSource} answers the second one: it is the wall clock, it is what a record's timestamp
 * is stamped from, and it is a number an operator, a time-synchronization daemon, or a virtual machine
 * resuming from a snapshot may move in either direction. This one answers the first, and is what every
 * deadline in this broker is measured against for exactly that reason — a bound that said "close this
 * connection if it has been silent for thirty seconds" must not be shortened to nothing, or stretched
 * to an hour, because something stepped the host's idea of the date.
 *
 * <p>A reading is nanoseconds since this source began, so the difference between two readings is the
 * time between them and a single reading on its own means nothing. Two rules make it usable as a
 * deadline, and implementations owe both: a reading is never negative, and no reading is smaller than
 * one taken before it.
 */
@FunctionalInterface
public interface MonotonicTimeSource {

    /**
     * @return nanoseconds since this source began: never negative, and never smaller than a reading
     *         already handed out
     */
    long elapsedNanos();
}
