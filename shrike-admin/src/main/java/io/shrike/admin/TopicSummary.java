package io.shrike.admin;

import io.shrike.core.protocol.TopicDescription;
import java.util.Objects;

/**
 * One topic in the listing: its name and how many partitions it has, and nothing else.
 *
 * <p>The listing is the answer to "what is on this broker", so it carries the two facts that identify
 * a topic and stops there; {@link TopicDetail} is where a caller goes for the offsets and the bytes of
 * one topic it has picked out.
 *
 * @param name           the topic's identity: its name, folded, exactly as the broker reports it
 * @param partitionCount how many partitions it has, which is fixed when a topic is created
 */
public record TopicSummary(String name, int partitionCount) {

    public TopicSummary {
        Objects.requireNonNull(name, "name");
    }

    /**
     * @param described one topic as the broker described it
     * @return that topic, cut down to what a listing shows
     */
    public static TopicSummary of(TopicDescription described) {
        Objects.requireNonNull(described, "described");
        return new TopicSummary(described.name(), described.partitionCount());
    }
}
