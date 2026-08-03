package io.shrike.core.net;

/**
 * This create would push the broker past the partitions it may hold open.
 *
 * <p>A partition is a directory with an open log file and an open index file in it, for as long as the
 * broker runs, so the cost of a create outlives the request that asked for it. The budget is
 * {@link BrokerConfig#maxTotalPartitions()} and it is counted across every topic, because file
 * descriptors are a process-wide resource rather than a per-topic one.
 */
final class TooManyPartitionsException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    TooManyPartitionsException(String name, int partitionCount, long openPartitionCount, int maxTotalPartitions) {
        super("topic " + name + " asks for " + partitionCount + " partitions, but this broker already holds "
                + openPartitionCount + " of the " + maxTotalPartitions + " it may open");
    }
}
