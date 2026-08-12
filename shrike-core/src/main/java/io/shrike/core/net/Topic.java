package io.shrike.core.net;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A topic the broker holds: how many partitions it has, and the partition logs it has open for them,
 * in partition-number order.
 *
 * <p>Those are the same number in every topic a create finished and every topic a start opened, and
 * {@link #Topic(String, List)} is the constructor that says so — it counts the partitions it is handed.
 * They come apart in one case, and it is the case the topic registry's rollback exists for: a create
 * whose registry file was moved into place and which then failed leaves a topic the file names and no
 * log open behind it. The count is what that file says, because the file is what every later reader of
 * the directory believes; the list is what this broker can serve, which is nothing until the next start
 * opens it.
 *
 * @param name           the topic name, already checked as a {@link io.shrike.core.log.SafeName}
 * @param partitionCount how many partitions this topic has, now and for as long as it exists
 * @param partitions     the logs this broker holds open, partition 0 first, never more than the count
 */
record Topic(String name, int partitionCount, List<Partition> partitions) {

    Topic {
        Objects.requireNonNull(name, "name");
        partitions = List.copyOf(partitions);
        if (partitionCount < partitions.size()) {
            throw new IllegalArgumentException("topic " + name + " cannot hold " + partitions.size()
                    + " partitions open while having " + partitionCount);
        }
    }

    /**
     * A topic with every one of its partitions open, which is what a finished create and a start both
     * produce.
     *
     * @param name       the topic name
     * @param partitions its partitions, partition 0 first
     */
    Topic(String name, List<Partition> partitions) {
        this(name, partitions.size(), partitions);
    }

    /**
     * @param partition a partition number, which may be anything a client sent, including a negative
     *                  one
     * @return that partition, or empty when this broker holds no such partition of this topic open
     */
    Optional<Partition> partition(int partition) {
        if (partition < 0 || partition >= partitions.size()) {
            return Optional.empty();
        }
        return Optional.of(partitions.get(partition));
    }
}
