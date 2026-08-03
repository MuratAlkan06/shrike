package io.shrike.clients;

import io.shrike.core.protocol.CreateTopicRequest;
import io.shrike.core.protocol.CreateTopicResponse;
import io.shrike.core.protocol.ErrorCode;
import java.util.Objects;

/**
 * Creates topics. It is a client of its own rather than a method on {@link ShrikeProducer} because
 * creating a topic and appending to one are two different jobs with two different lifetimes: a topic
 * is created once, usually by whoever sets a deployment up, while a producer runs for as long as
 * there is something to send. DESIGN.md records that decision and the alternative.
 *
 * <p>A partition count is fixed when a topic is created, so creating a topic that already exists is
 * refused with {@link ErrorCode#TOPIC_ALREADY_EXISTS} whatever count it asks for — including the same
 * one.
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
     * Closes the connection. Calling it twice does nothing the second time.
     */
    @Override
    public void close() {
        connection.close();
    }
}
