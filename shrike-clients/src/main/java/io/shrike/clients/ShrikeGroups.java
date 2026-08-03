package io.shrike.clients;

import io.shrike.core.protocol.DescribeGroupRequest;
import io.shrike.core.protocol.DescribeGroupResponse;
import io.shrike.core.protocol.GroupOffset;
import java.util.List;
import java.util.Objects;

/**
 * Reads where a consumer group has got to. It changes nothing: committing is
 * {@link ShrikeConsumer#commitOffset}, which belongs to the member doing the reading, and this is the
 * client for whoever is watching that member from outside.
 *
 * <p>It is a client of its own for the same reason {@link ShrikeTopics} is, and mirrors it: one
 * connection, opened here or handed in, closed when this is. A single type collecting every api that
 * is neither produce nor fetch would be a type named after what it is not, which DESIGN.md rejected
 * when {@code ShrikeTopics} was written and rejects again here.
 *
 * <p><strong>A group this broker has never heard of is answered with no offsets rather than a
 * failure.</strong> A commit is what creates a group, so a group that has committed nothing and a
 * group that does not exist are one state, and this library does not invent a way to tell them apart.
 */
public final class ShrikeGroups implements AutoCloseable {

    private final BrokerConnection connection;

    /**
     * @param connection the connection this client speaks through and, from here on, owns: closing
     *                   this closes it
     */
    public ShrikeGroups(BrokerConnection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    /**
     * Opens a connection of its own.
     *
     * @param config where the broker is
     * @return the client, which the caller closes
     */
    public static ShrikeGroups open(ClientConfig config) {
        return new ShrikeGroups(BrokerConnection.open(config));
    }

    /**
     * Reads every offset one group has committed.
     *
     * @param groupId the group to describe
     * @return one entry per partition that group has committed an offset for, topic and then
     *         partition, or none at all when it has committed nothing. Each offset is the next offset
     *         that group should read, which is the number a commit stores
     */
    public List<GroupOffset> describe(String groupId) {
        return connection.call(new DescribeGroupRequest(groupId), DescribeGroupResponse.class).offsets();
    }

    /**
     * Closes the connection. Calling it twice does nothing the second time.
     */
    @Override
    public void close() {
        connection.close();
    }
}
