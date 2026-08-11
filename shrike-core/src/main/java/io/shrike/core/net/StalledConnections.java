package io.shrike.core.net;

/**
 * One pass of the read bound, and the whole of what {@link ConnectionReaper} knows about the broker
 * around it: close every connection that has been reading, or waiting to read, since longer ago than
 * {@code read.timeout.ms}, as of one reading of the elapsed-time clock.
 *
 * <p>The broker is what implements it, so nothing in the reaper names a socket, a thread, or a place
 * under the connection cap — and nothing in the reaper decides what "stalled" means either. That rule
 * belongs to {@link Connection}, beside the read phase it is about; the reaper owns only when the
 * question is asked.
 */
@FunctionalInterface
interface StalledConnections {

    /**
     * @param nowNanos the reading of the elapsed-time clock every read phase's age is measured
     *                 against, which is where the injected clock enters
     */
    void closeStalledConnections(long nowNanos);
}
