package io.shrike.core.retention;

/**
 * One sweep of retention, and the whole of what this package knows about the rest of the broker:
 * delete every sealed segment the configured policy no longer keeps, as of one epoch millisecond.
 *
 * <p>The broker's topic registry is what implements it, so nothing here names a topic, a partition,
 * or a segment file — and nothing here decides what "no longer kept" means either. That rule belongs
 * to the log, beside the bytes it is about; this package owns only when the question is asked.
 */
@FunctionalInterface
public interface SegmentDeletion {

    /**
     * @param nowMillis the epoch millisecond retention is evaluated at, which is where the injected
     *                  clock enters: every age this sweep compares is measured against this number
     */
    void deleteRetiredSegments(long nowMillis);
}
