package io.shrike.core.protocol;

import io.shrike.core.log.SafeName;

/**
 * One offset a consumer group has committed, for one partition of one topic.
 *
 * <p><strong>The offset is the next offset to read, not the last one read.</strong> A group that has
 * consumed offsets 0 through 4 committed 5, and that is the number here. It is the same convention the
 * commit api and the broker's own files use, and it is stated in every one of those places rather than
 * left to be inferred.
 *
 * @param topic           the topic, under the folded name a commit keyed it by
 * @param partition       the partition of that topic
 * @param committedOffset the next offset the group should read
 */
public record GroupOffset(String topic, int partition, long committedOffset) {

    public GroupOffset {
        SafeName.require(topic, "topic");
        if (partition < 0) {
            throw new IllegalArgumentException("partition must not be negative, but was " + partition);
        }
        if (committedOffset < 0) {
            throw new IllegalArgumentException("a committed offset is the next offset to read, so "
                    + committedOffset + " cannot be one");
        }
    }
}
