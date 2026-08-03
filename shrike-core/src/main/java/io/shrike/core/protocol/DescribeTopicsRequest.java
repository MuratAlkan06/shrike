package io.shrike.core.protocol;

import io.shrike.core.log.SafeName;
import java.util.List;
import java.util.Objects;

/**
 * Describe the topics this broker holds: their partitions, the offsets each one can serve, and what it
 * costs on disk. It reads and changes nothing.
 *
 * <p><strong>An empty list means every topic.</strong> That is not a flag smuggled into a collection:
 * the wire carries a count, and a count of zero is the only thing "describe nothing" could otherwise
 * mean, which is a request nobody sends. {@link #everyTopic()} is the name for it, and
 * {@link #describesEveryTopic()} is how a reader asks.
 *
 * <p>A topic this broker does not hold is not a broken request, so it is not refused here: the broker
 * answers a name it does not know with {@link ErrorCode#UNKNOWN_TOPIC_OR_PARTITION}.
 *
 * @param topics the topics to describe, each a {@link SafeName} because each one names a directory, or
 *               empty for every topic this broker holds
 */
public record DescribeTopicsRequest(List<String> topics) implements Request {

    /**
     * The most topics one request may name, which is the same number as
     * {@link CreateTopicRequest#MAX_PARTITION_COUNT} and for the same reason: every topic costs at
     * least one partition, and a broker's partitions are budgeted at 1024 by default, so a request
     * naming more than this is naming topics that could not all exist. The cap is what stops a count
     * read off the wire from sizing a list before the bytes to fill it are known to be there.
     */
    public static final int MAX_TOPIC_COUNT = CreateTopicRequest.MAX_PARTITION_COUNT;

    public DescribeTopicsRequest {
        Objects.requireNonNull(topics, "topics");
        topics = List.copyOf(topics);
        if (topics.size() > MAX_TOPIC_COUNT) {
            throw new IllegalArgumentException("topicCount must be at most " + MAX_TOPIC_COUNT + ", but was "
                    + topics.size());
        }
        for (String topic : topics) {
            SafeName.require(topic, "topic");
        }
    }

    /**
     * @return a request for every topic this broker holds
     */
    public static DescribeTopicsRequest everyTopic() {
        return new DescribeTopicsRequest(List.of());
    }

    /**
     * @return whether this request names no topic in particular, which is how it asks for all of them
     */
    public boolean describesEveryTopic() {
        return topics.isEmpty();
    }

    @Override
    public short apiKey() {
        return ApiKeys.DESCRIBE_TOPICS;
    }
}
