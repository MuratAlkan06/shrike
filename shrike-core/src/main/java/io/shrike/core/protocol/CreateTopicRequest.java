package io.shrike.core.protocol;

/**
 * Create a topic with a fixed number of partitions.
 *
 * <p>A topic that already exists is not a broken request, so it is not refused here: the broker
 * answers that with {@link ErrorCode#TOPIC_ALREADY_EXISTS}.
 *
 * @param name           the topic to create, which must be a {@link SafeName} because it becomes a
 *                       directory
 * @param partitionCount how many partitions it has, {@value #MIN_PARTITION_COUNT} to
 *                       {@value #MAX_PARTITION_COUNT}; each one is a directory with open files in it
 */
public record CreateTopicRequest(String name, int partitionCount) implements Request {

    /** A topic with no partitions could hold nothing. */
    public static final int MIN_PARTITION_COUNT = 1;

    /** Every partition costs a directory and open file handles, so one request cannot ask for more. */
    public static final int MAX_PARTITION_COUNT = 1024;

    public CreateTopicRequest {
        SafeName.require(name, "name");
        if (partitionCount < MIN_PARTITION_COUNT || partitionCount > MAX_PARTITION_COUNT) {
            throw new IllegalArgumentException("partitionCount must be " + MIN_PARTITION_COUNT + " to "
                    + MAX_PARTITION_COUNT + ", but was " + partitionCount);
        }
    }

    @Override
    public short apiKey() {
        return ApiKeys.CREATE_TOPIC;
    }
}
