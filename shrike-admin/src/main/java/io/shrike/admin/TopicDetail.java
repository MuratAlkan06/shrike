package io.shrike.admin;

import io.shrike.core.protocol.TopicDescription;
import java.util.List;
import java.util.Objects;

/**
 * The body of {@code GET /api/v1/topics/{topic}}: one topic, with a line per partition.
 *
 * <p>The name is the one the broker answered with, which is the folded name that is a topic's
 * identity, whatever casing the caller spelled into the path.
 *
 * @param name       the topic's identity: its name, folded
 * @param partitions one entry per partition, partition 0 first
 */
public record TopicDetail(String name, List<PartitionDetail> partitions) {

    public TopicDetail {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(partitions, "partitions");
        partitions = List.copyOf(partitions);
    }

    /**
     * @param described one topic as the broker described it
     * @return the same topic, under the names this facade publishes
     */
    public static TopicDetail of(TopicDescription described) {
        Objects.requireNonNull(described, "described");
        return new TopicDetail(described.name(), described.partitions().stream().map(PartitionDetail::of).toList());
    }
}
