package io.shrike.core.log;

/**
 * What one partition's storage looks like right now: the offsets it can serve, how many segments it
 * spreads over, and what it costs on disk. Every method reads state and changes nothing.
 *
 * <p>This is the surface an admin view will read from. It is deliberately numbers only — no
 * formatting, no transport — so the thing that answers "how big is this partition" stays the thing
 * that stores it.
 */
public interface LogStatistics {

    /**
     * @return the lowest offset the log can still serve: the base offset of its first segment, which
     *         retention moves forward as it deletes the segments before it
     */
    long logStartOffset();

    /**
     * @return the offset the next append will take, which is the exclusive end of the readable range
     */
    long highWaterMark();

    /**
     * @return how many segments the log spreads over, the last of which is the one taking appends
     */
    int segmentCount();

    /**
     * @return the bytes the log files of every segment occupy
     */
    long logBytes();

    /**
     * @return the bytes the index files of every segment occupy
     */
    long indexBytes();
}
