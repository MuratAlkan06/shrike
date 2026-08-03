package io.shrike.core.net;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A topic the broker holds open: its name and its partitions, in partition-number order.
 *
 * <p>The number of partitions is the size of the list and cannot be anything else, which is how this
 * type says that a topic's partition count never changes after it is created.
 *
 * @param name       the topic name, already checked as a {@link io.shrike.core.log.SafeName}
 * @param partitions partition 0 first, one entry per partition
 */
record Topic(String name, List<Partition> partitions) {

    Topic {
        Objects.requireNonNull(name, "name");
        partitions = List.copyOf(partitions);
    }

    /**
     * @return how many partitions this topic has, for the life of the topic
     */
    int partitionCount() {
        return partitions.size();
    }

    /**
     * @param partition a partition number, which may be anything a client sent, including a negative
     *                  one
     * @return that partition, or empty when this topic has no such partition
     */
    Optional<Partition> partition(int partition) {
        if (partition < 0 || partition >= partitions.size()) {
            return Optional.empty();
        }
        return Optional.of(partitions.get(partition));
    }
}
