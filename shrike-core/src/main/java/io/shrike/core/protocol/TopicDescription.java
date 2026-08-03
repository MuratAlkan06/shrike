package io.shrike.core.protocol;

import io.shrike.core.log.SafeName;
import java.util.List;
import java.util.Objects;

/**
 * One topic, as a describe reports it: its name and one {@link PartitionDescription} per partition.
 *
 * <p><strong>The name is folded.</strong> A topic name is case-insensitive — {@code orders} and
 * {@code Orders} are one topic — so the identity of a topic is {@link SafeName#fold(String)} of its
 * name, and that is the name reported here, whatever spelling the topic was created with and whatever
 * spelling the caller asked under. It is the same name a committed offset is keyed by, so a describe of
 * a topic and a describe of a group name the same topic the same way and a caller can match one against
 * the other without folding anything itself.
 *
 * <p>How many partitions a topic has is the size of this list and cannot be anything else, which is how
 * this type says that a partition count is fixed when a topic is created.
 *
 * @param name       the topic's identity: its name, folded
 * @param partitions one entry per partition, partition 0 first
 */
public record TopicDescription(String name, List<PartitionDescription> partitions) {

    public TopicDescription {
        SafeName.require(name, "name");
        Objects.requireNonNull(partitions, "partitions");
        partitions = List.copyOf(partitions);
    }

    /**
     * @return how many partitions this topic has, for the life of the topic
     */
    public int partitionCount() {
        return partitions.size();
    }
}
