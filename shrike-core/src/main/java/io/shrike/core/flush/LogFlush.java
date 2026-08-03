package io.shrike.core.flush;

/**
 * One pass of the flush policy's time bound, and the whole of what this package knows about the rest
 * of the broker: force every log whose records have sat unforced for longer than
 * {@code flush.interval.ms}, as of one epoch millisecond.
 *
 * <p>The broker's topic registry is what implements it, so nothing here names a topic, a partition, or
 * a segment file — and nothing here decides when a log is due either. That rule belongs to the log,
 * beside the bytes it is about; this package owns only when the question is asked.
 *
 * <p>The volume bound, {@code flush.interval.bytes}, is not asked for here at all: only an append can
 * move it, so the append that crosses it is what forces.
 */
@FunctionalInterface
public interface LogFlush {

    /**
     * @param nowMillis the epoch millisecond the flush interval is measured against, which is where the
     *                  injected clock enters
     */
    void flushIfDue(long nowMillis);
}
