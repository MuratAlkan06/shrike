package io.shrike.core.protocol;

import io.shrike.core.log.SafeName;

/**
 * Describe what one consumer group has committed: the next offset to read for every partition it has
 * ever committed one for. It reads and changes nothing.
 *
 * <p>A group this broker has never heard of is not a broken request and is not an error either — a
 * commit is what creates a group, so a group with no commits and a group that does not exist are one
 * state, and both are answered with no entries. See {@link DescribeGroupResponse}.
 *
 * @param groupId the group to describe, which must be a {@link SafeName} because it becomes a file
 *                name
 */
public record DescribeGroupRequest(String groupId) implements Request {

    public DescribeGroupRequest {
        SafeName.require(groupId, "groupId");
    }

    @Override
    public short apiKey() {
        return ApiKeys.DESCRIBE_GROUP;
    }
}
