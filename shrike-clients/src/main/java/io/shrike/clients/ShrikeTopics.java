package io.shrike.clients;

import io.shrike.core.protocol.CreateTopicRequest;
import io.shrike.core.protocol.CreateTopicResponse;
import io.shrike.core.protocol.DescribeTopicsRequest;
import io.shrike.core.protocol.DescribeTopicsResponse;
import io.shrike.core.protocol.ErrorCode;
import io.shrike.core.protocol.TopicDescription;
import java.util.List;
import java.util.Objects;

/**
 * Creates topics and describes them. It is a client of its own rather than a method on
 * {@link ShrikeProducer} because creating a topic and appending to one are two different jobs with two
 * different lifetimes: a topic is created once, usually by whoever sets a deployment up, while a
 * producer runs for as long as there is something to send. DESIGN.md records that decision and the
 * alternative.
 *
 * <p>A partition count is fixed when a topic is created, so creating a topic that already exists is
 * refused with {@link ErrorCode#TOPIC_ALREADY_EXISTS} whatever count it asks for — including the same
 * one.
 *
 * <p>Describing is read-only, and it lives here because it is about topics. Naming a topic this broker
 * does not hold is refused with {@link ErrorCode#UNKNOWN_TOPIC_OR_PARTITION}; asking about all of them
 * on a broker that holds none answers with none, which is not a failure.
 */
public final class ShrikeTopics implements AutoCloseable {

    private final BrokerConnection connection;

    /**
     * @param connection the connection this client speaks through and, from here on, owns: closing
     *                   this closes it
     */
    public ShrikeTopics(BrokerConnection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    /**
     * Opens a connection of its own.
     *
     * @param config where the broker is
     * @return the client, which the caller closes
     */
    public static ShrikeTopics open(ClientConfig config) {
        return new ShrikeTopics(BrokerConnection.open(config));
    }

    /**
     * Creates a topic with a fixed number of partitions.
     *
     * @param name           the topic to create
     * @param partitionCount how many partitions it has, which cannot change afterwards
     * @throws BrokerErrorException if the broker refused the create, which a topic of that name
     *                              already existing is
     */
    public void create(String name, int partitionCount) {
        connection.call(new CreateTopicRequest(name, partitionCount), CreateTopicResponse.class);
    }

    /**
     * Reads what the broker holds of the topics named, changing nothing.
     *
     * @param names the topics to describe, each of which the broker must hold
     * @return one description per name, in the order they were asked about, each carrying the topic's
     *         folded name and one entry per partition
     * @throws BrokerErrorException if the broker refused the describe, which naming a topic it does not
     *                              hold is
     */
    public List<TopicDescription> describe(List<String> names) {
        return connection.call(new DescribeTopicsRequest(names), DescribeTopicsResponse.class).topics();
    }

    /**
     * Reads what the broker holds of every topic it has, changing nothing. A broker holding none
     * answers with none rather than failing.
     *
     * @return one description per topic, each carrying the topic's folded name and one entry per
     *         partition
     */
    public List<TopicDescription> describeAll() {
        return connection.call(DescribeTopicsRequest.everyTopic(), DescribeTopicsResponse.class).topics();
    }

    /**
     * Closes the connection. Calling it twice does nothing the second time.
     */
    @Override
    public void close() {
        connection.close();
    }
}
