package io.shrike.core.group;

import java.util.Objects;

/**
 * One line of a group's committed offsets, as {@link GroupOffsetStore} holds it.
 *
 * <p>It exists because {@link TopicPartition} is the store's own key and stays package-private: a
 * caller enumerating a group is owed the three numbers, not the map they are kept in. The topic is the
 * folded name the store keys by, which is the name a commit and the fetch that follows it both mean.
 *
 * <p><strong>The offset is the next offset to read, not the last one read.</strong> That convention is
 * the store's, and this record carries it unchanged.
 *
 * @param topic     the topic, folded to the identity the store keys by
 * @param partition the partition of that topic
 * @param offset    the next offset the group should read
 */
public record CommittedOffset(String topic, int partition, long offset) {

    public CommittedOffset {
        Objects.requireNonNull(topic, "topic");
    }
}
