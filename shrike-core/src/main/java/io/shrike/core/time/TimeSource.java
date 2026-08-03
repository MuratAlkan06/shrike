package io.shrike.core.time;

/**
 * The clock the broker reads. Every component that needs the current time takes one of these
 * through its constructor, so tests state the exact timestamp a record must carry instead of
 * waiting for one.
 */
@FunctionalInterface
public interface TimeSource {

    /**
     * The current instant as milliseconds since the Unix epoch.
     *
     * @return epoch milliseconds
     */
    long currentTimeMillis();
}
