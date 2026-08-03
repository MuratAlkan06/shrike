package io.shrike.core.group;

import java.util.Comparator;
import java.util.Objects;

/**
 * One partition of one topic, used as the key a group's committed offsets are stored under. Keyed
 * this way from the first commit: a group reads many partitions, and a store that kept one offset per
 * group would have to be rewritten the moment a topic had two.
 *
 * <p>Ordered by topic and then by partition so that a rewritten file lists its lines in one order
 * whatever order the commits arrived in.
 *
 * @param topic     the topic name
 * @param partition the partition number within that topic
 */
record TopicPartition(String topic, int partition) implements Comparable<TopicPartition> {

    private static final Comparator<TopicPartition> ORDER =
            Comparator.comparing(TopicPartition::topic).thenComparingInt(TopicPartition::partition);

    TopicPartition {
        Objects.requireNonNull(topic, "topic");
    }

    @Override
    public int compareTo(TopicPartition other) {
        return ORDER.compare(this, other);
    }
}
