package io.shrike.admin;

import io.shrike.core.protocol.GroupOffset;
import io.shrike.core.protocol.PartitionDescription;
import java.util.Objects;

/**
 * How far one consumer group is behind one partition: what it committed, where the partition ends, and
 * the difference.
 *
 * <p><strong>The broker does not compute this.</strong> It answers two questions — what a group
 * committed, and what a partition holds — and the subtraction happens here, because it is the only
 * number in this facade that is not something the broker already said.
 *
 * <p>{@code committedOffset} is the next offset the group should read, which is the convention the
 * commit api and the broker's files both use. So a group that has read a partition to its end has
 * committed the high-water mark itself and its lag is zero, not one.
 *
 * @param topic           the topic, under the folded name the commit was keyed by
 * @param partition       the partition of that topic
 * @param committedOffset the next offset the group should read
 * @param highWaterMark   the offset the partition's next append will take
 * @param lag             how many records the group has not read yet
 */
public record PartitionLag(String topic, int partition, long committedOffset, long highWaterMark, long lag) {

    public PartitionLag {
        Objects.requireNonNull(topic, "topic");
    }

    /**
     * Subtracts what a group committed from where its partition ends.
     *
     * <p>The two numbers are read from the broker in a frozen order — the group first, the partition
     * second — which is what makes this subtraction safe: the committed offset is from before the
     * high-water mark was read, a committed offset never runs past the high-water mark of its own
     * instant, and a high-water mark only ever climbs. So
     * {@code committed_before <= committed_now <= highWaterMark_now <= highWaterMark_after}, and the
     * difference cannot come out negative. It is checked anyway rather than trusted, because a
     * negative lag would be this facade reporting a number that cannot be true.
     *
     * @param committed what the group committed for this partition
     * @param described that partition, as the broker described it afterwards
     * @return the lag row for that partition
     * @throws IllegalStateException if the difference is negative, which means the two answers did not
     *                               arrive in the order this method requires
     */
    public static PartitionLag between(GroupOffset committed, PartitionDescription described) {
        Objects.requireNonNull(committed, "committed");
        Objects.requireNonNull(described, "described");

        long lag = described.highWaterMark() - committed.committedOffset();
        if (lag < 0) {
            throw new IllegalStateException("topic=" + committed.topic() + " partition=" + described.partition()
                    + " was described with highWaterMark=" + described.highWaterMark() + ", below the "
                    + committed.committedOffset() + " the group had already committed, so the group was described"
                    + " after the topic rather than before it");
        }
        return new PartitionLag(committed.topic(), described.partition(), committed.committedOffset(),
                described.highWaterMark(), lag);
    }
}
