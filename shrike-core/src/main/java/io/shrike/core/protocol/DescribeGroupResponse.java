package io.shrike.core.protocol;

import java.util.List;
import java.util.Objects;

/**
 * Where one consumer group has got to: one entry per partition it has committed an offset for, in the
 * order the broker stores them — topic and then partition.
 *
 * <p><strong>No entries is an answer, not an error.</strong> A group this broker has never heard of and
 * a group that has committed nothing are the same state — a commit is what creates a group — so there
 * is no code to tell them apart with and none is invented. A caller that wants to know whether a group
 * exists is asking a question this build does not answer.
 *
 * @param offsets one entry per committed partition, topic and then partition, possibly none
 */
public record DescribeGroupResponse(List<GroupOffset> offsets) implements Response {

    public DescribeGroupResponse {
        Objects.requireNonNull(offsets, "offsets");
        offsets = List.copyOf(offsets);
    }

    @Override
    public short apiKey() {
        return ApiKeys.DESCRIBE_GROUP;
    }
}
