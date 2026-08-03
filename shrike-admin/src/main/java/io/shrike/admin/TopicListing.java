package io.shrike.admin;

import io.shrike.core.protocol.TopicDescription;
import java.util.List;
import java.util.Objects;

/**
 * The body of {@code GET /api/v1/topics}: every topic the broker holds, in the order the broker lists
 * them.
 *
 * <p>A broker holding no topics answers with an empty array and status 200. That is the truth about an
 * empty broker rather than a failure, and it is the same answer the describe api gives underneath.
 *
 * @param topics one entry per topic
 */
public record TopicListing(List<TopicSummary> topics) {

    public TopicListing {
        Objects.requireNonNull(topics, "topics");
        topics = List.copyOf(topics);
    }

    /**
     * @param described every topic as the broker described it
     * @return the listing those descriptions make, in the order they arrived
     */
    public static TopicListing of(List<TopicDescription> described) {
        Objects.requireNonNull(described, "described");
        return new TopicListing(described.stream().map(TopicSummary::of).toList());
    }
}
