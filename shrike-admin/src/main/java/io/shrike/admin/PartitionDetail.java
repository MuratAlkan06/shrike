package io.shrike.admin;

import io.shrike.core.protocol.PartitionDescription;
import java.util.Objects;

/**
 * One partition in a topic's detail, as JSON.
 *
 * <p>The five numbers are the broker's own, copied field for field. The offset and position law holds
 * here too: {@code logStartOffset} and {@code highWaterMark} count records, {@code bytes} counts bytes,
 * and the names never blur. What the partition can serve is the half-open range
 * {@code [logStartOffset, highWaterMark)}, so a partition holding nothing reports the same number
 * twice.
 *
 * @param partition       the partition number within its topic
 * @param logStartOffset  the lowest offset it can still serve
 * @param highWaterMark   the offset the next append will take
 * @param segmentCount    how many segments its log spreads over
 * @param bytes           what it occupies on disk: its log files and its index files together
 */
public record PartitionDetail(int partition, long logStartOffset, long highWaterMark, int segmentCount,
                              long bytes) {

    /**
     * @param described one partition as the broker described it
     * @return the same five numbers, under the names this facade publishes
     */
    public static PartitionDetail of(PartitionDescription described) {
        Objects.requireNonNull(described, "described");
        return new PartitionDetail(described.partition(), described.logStartOffset(), described.highWaterMark(),
                described.segmentCount(), described.bytes());
    }
}
