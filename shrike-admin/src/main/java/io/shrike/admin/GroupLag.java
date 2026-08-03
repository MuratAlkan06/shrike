package io.shrike.admin;

import io.shrike.core.protocol.GroupOffset;
import io.shrike.core.protocol.PartitionDescription;
import io.shrike.core.protocol.TopicDescription;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The body of {@code GET /api/v1/groups/{group}/lag}: one row per partition the group has committed an
 * offset for.
 *
 * <p>{@link #across} is the whole of the computation this facade does, and it is a pure function of the
 * two answers the broker gave: no clock, no files, no connection. That is what lets it be tested
 * without either of them.
 *
 * @param group      the group asked about, as the caller spelled it
 * @param partitions one row per committed partition, in the order the broker listed them, which is
 *                   topic and then partition
 */
public record GroupLag(String group, List<PartitionLag> partitions) {

    public GroupLag {
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(partitions, "partitions");
        partitions = List.copyOf(partitions);
    }

    /**
     * Joins what a group committed to the partitions it committed against, and subtracts.
     *
     * <p>The join is by name with nothing folded on the way: a committed offset is keyed by the folded
     * topic name and a description reports the folded topic name, which is the point of folding both —
     * a caller can match one answer against the other without a rule of its own.
     *
     * @param group     the group asked about
     * @param committed every offset that group has committed, as the broker answered
     * @param described the topics those offsets name, as the broker described them afterwards
     * @return the lag of that group, row for row with {@code committed}
     * @throws IllegalStateException if a topic or a partition a group committed to is missing from the
     *                               descriptions, which would be an answer to a question that was not
     *                               asked
     */
    public static GroupLag across(String group, List<GroupOffset> committed, List<TopicDescription> described) {
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(committed, "committed");
        Objects.requireNonNull(described, "described");

        Map<String, TopicDescription> byName = new HashMap<>();
        for (TopicDescription topic : described) {
            byName.put(topic.name(), topic);
        }

        List<PartitionLag> partitions = new ArrayList<>(committed.size());
        for (GroupOffset offset : committed) {
            TopicDescription topic = byName.get(offset.topic());
            if (topic == null) {
                throw new IllegalStateException("the group committed an offset for topic=" + offset.topic()
                        + ", which is not among the topics described: " + byName.keySet());
            }
            partitions.add(PartitionLag.between(offset, partitionOf(topic, offset.partition())));
        }
        return new GroupLag(group, partitions);
    }

    /**
     * @param topic     a described topic
     * @param partition the partition number wanted
     * @return that partition's description
     * @throws IllegalStateException if the topic has no such partition, which a group could only have
     *                               committed to if a partition count had shrunk, and it cannot
     */
    private static PartitionDescription partitionOf(TopicDescription topic, int partition) {
        for (PartitionDescription described : topic.partitions()) {
            if (described.partition() == partition) {
                return described;
            }
        }
        throw new IllegalStateException("the group committed an offset for topic=" + topic.name() + " partition="
                + partition + ", but that topic was described with " + topic.partitionCount() + " partition(s)");
    }
}
