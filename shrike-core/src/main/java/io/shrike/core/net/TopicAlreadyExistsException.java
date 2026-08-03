package io.shrike.core.net;

/**
 * A topic by that name is already here. A repeat create is refused whatever partition count it asks
 * for — the same count included — because a partition count is fixed when a topic is created and a
 * request that agrees with it is still a request to create something that exists.
 */
final class TopicAlreadyExistsException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String name;
    private final int existingPartitionCount;

    TopicAlreadyExistsException(String name, int existingPartitionCount) {
        super("topic " + name + " already exists with " + existingPartitionCount + " partitions");
        this.name = name;
        this.existingPartitionCount = existingPartitionCount;
    }

    String name() {
        return name;
    }

    /**
     * @return the partition count the topic was created with, which nothing can change
     */
    int existingPartitionCount() {
        return existingPartitionCount;
    }
}
