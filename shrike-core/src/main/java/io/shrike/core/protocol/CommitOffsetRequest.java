package io.shrike.core.protocol;

import io.shrike.core.log.SafeName;

/**
 * Store one group's committed offset for one partition.
 *
 * <p>The group id goes through the same rule as a topic name, because it names a file the broker will
 * write just as a topic does.
 *
 * @param groupId   the consumer group committing, which must be a {@link SafeName}
 * @param topic     the topic the offset belongs to, which must be a {@link SafeName}
 * @param partition the partition of that topic
 * @param offset    the logical record number to commit
 */
public record CommitOffsetRequest(String groupId, String topic, int partition, long offset) implements Request {

    public CommitOffsetRequest {
        SafeName.require(groupId, "groupId");
        SafeName.require(topic, "topic");
    }

    @Override
    public short apiKey() {
        return ApiKeys.COMMIT_OFFSET;
    }
}
