package io.shrike.core.protocol;

/**
 * One partition of one topic, as a describe reports it.
 *
 * <p>The offsets keep this repository's law: {@code logStartOffset} and {@code highWaterMark} are
 * logical record numbers and {@code bytes} is a byte count, and the names never blur. What a partition
 * can serve is the half-open range {@code [logStartOffset, highWaterMark)}, so a partition holding no
 * records at all reports the same number twice.
 *
 * <p>The rules below are what makes a description a description: a caller decoding one has already had
 * the impossible answers refused, so it can subtract two of these numbers without checking the sign.
 *
 * @param partition       the partition number within its topic
 * @param logStartOffset  the lowest offset the partition can still serve
 * @param highWaterMark   the offset the next append will take, which is the exclusive end of that range
 * @param segmentCount    how many segments the partition's log spreads over, the last of which is the
 *                        one taking appends; a partition always has at least that one
 * @param bytes           what the partition occupies on disk: its log files and its index files
 *                        together
 */
public record PartitionDescription(int partition, long logStartOffset, long highWaterMark, int segmentCount,
                                   long bytes) {

    /** Even a partition with no records has the segment its next append will go into. */
    public static final int MIN_SEGMENT_COUNT = 1;

    public PartitionDescription {
        requireNotNegative(partition, "partition");
        requireNotNegative(logStartOffset, "logStartOffset");
        requireNotNegative(bytes, "bytes");
        if (highWaterMark < logStartOffset) {
            throw new IllegalArgumentException("highWaterMark " + highWaterMark + " is below logStartOffset "
                    + logStartOffset + ", which would be a readable range running backwards");
        }
        if (segmentCount < MIN_SEGMENT_COUNT) {
            throw new IllegalArgumentException("segmentCount must be at least " + MIN_SEGMENT_COUNT + ", but was "
                    + segmentCount);
        }
    }

    private static void requireNotNegative(long value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " must not be negative, but was " + value);
        }
    }
}
