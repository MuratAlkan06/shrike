package io.shrike.core.protocol;

import java.util.List;
import java.util.Objects;

/**
 * What this broker holds, topic by topic.
 *
 * <p>No topics at all is an answer rather than a failure: a describe of every topic on a broker that
 * has none is {@link ErrorCode#NONE} with an empty list. A describe that <em>named</em> a topic this
 * broker does not hold never reaches this record — it is answered
 * {@link ErrorCode#UNKNOWN_TOPIC_OR_PARTITION} instead, because a caller that asked about something by
 * name is owed the news that it is not here.
 *
 * @param topics one entry per topic described, in the order the broker holds them
 */
public record DescribeTopicsResponse(List<TopicDescription> topics) implements Response {

    public DescribeTopicsResponse {
        Objects.requireNonNull(topics, "topics");
        topics = List.copyOf(topics);
    }

    @Override
    public short apiKey() {
        return ApiKeys.DESCRIBE_TOPICS;
    }
}
