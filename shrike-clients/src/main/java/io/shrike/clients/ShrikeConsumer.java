package io.shrike.clients;

import io.shrike.core.log.MalformedFrameException;
import io.shrike.core.protocol.CommitOffsetRequest;
import io.shrike.core.protocol.CommitOffsetResponse;
import io.shrike.core.protocol.FetchRequest;
import io.shrike.core.protocol.FetchResponse;
import io.shrike.core.protocol.WireRecords;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Reads records from a partition by offset, and stores where a group has got to.
 *
 * <p>Blocking and synchronous, like {@link ShrikeProducer}: a fetch returns when the broker answers,
 * and a consumer owns the connection it was built with.
 *
 * <p>There is no group membership and no rebalancing in this build. A consumer names the partition it
 * reads and the group it commits under, and deciding which consumer reads which partition is the
 * caller's business.
 */
public final class ShrikeConsumer implements AutoCloseable {

    private final BrokerConnection connection;

    /**
     * @param connection the connection this consumer speaks through and, from here on, owns: closing
     *                   the consumer closes it
     */
    public ShrikeConsumer(BrokerConnection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    /**
     * Opens a connection of this consumer's own.
     *
     * @param config where the broker is
     * @return the consumer, which the caller closes
     */
    public static ShrikeConsumer open(ClientConfig config) {
        return new ShrikeConsumer(BrokerConnection.open(config));
    }

    /**
     * Reads records from one partition, starting at an offset.
     *
     * <p>The broker may hold this request open for up to {@code maxWaitMs} while fewer than
     * {@code minBytes} of records are readable, and answers the moment an append makes enough
     * available. A wait that expires is not a failure: it answers with whatever there is, which is
     * usually nothing, and with the current high-water mark. A {@code maxWaitMs} or {@code minBytes}
     * of 0 answers on the first pass without waiting at all.
     *
     * <p>This call's socket timeout is {@code maxWaitMs} plus the configured
     * {@code readTimeoutMillis}, so a long poll does not turn into a client-side timeout.
     *
     * @param topic       the topic to read from
     * @param partition   the partition of that topic
     * @param fetchOffset the logical record number to start at, which is what a group last committed
     * @param maxBytes    the most bytes of records to be answered with. One record larger than this
     *                    is served whole anyway, so a consumer can never be stuck on a record it
     *                    asked too little for
     * @param maxWaitMs   how long the broker may hold this request open waiting for records
     * @param minBytes    how many bytes of records are worth answering before that wait is up
     * @return the records and the partition's high-water mark
     * @throws BrokerErrorException       if the broker refused the fetch — an unknown topic or
     *                                    partition, an offset outside what it can serve, or a stored
     *                                    record that no longer matches its checksum
     * @throws MalformedResponseException if the records block is not made of record frames
     */
    public FetchedRecords fetch(String topic, int partition, long fetchOffset, int maxBytes, int maxWaitMs,
                                int minBytes) {
        FetchRequest request = new FetchRequest(topic, partition, fetchOffset, maxBytes, maxWaitMs, minBytes);
        FetchResponse response = connection.call(request, FetchResponse.class);
        try {
            return new FetchedRecords(response.highWaterMark(),
                    WireRecords.decode(ByteBuffer.wrap(response.records())));
        } catch (MalformedFrameException e) {
            // The block is copied out of the broker's log without being looked inside, so a block that
            // is not records means the bytes on the wire are not the bytes that were stored. That is a
            // connection this client cannot believe rather than a fetch that failed.
            connection.close();
            throw new MalformedResponseException("the records answering a fetch of topic=" + topic + " partition="
                    + partition + " from fetchOffset=" + fetchOffset + " are not record frames: " + e.getMessage());
        }
    }

    /**
     * Stores where a group has got to in one partition.
     *
     * <p><strong>The offset committed is the next offset to read, not the last one read.</strong> A
     * group that has consumed offsets 0 through 4 commits 5, and a group that has consumed nothing
     * commits nothing at all. {@link FetchedRecords#nextOffset(long)} is that number.
     *
     * <p>The broker answers this call only after the group's file has been written, forced, and moved
     * into place, so a commit that returns is a commit that is on the device.
     *
     * @param groupId   the consumer group committing
     * @param topic     the topic the offset belongs to
     * @param partition the partition of that topic
     * @param offset    the next offset this group should read, which may be the high-water mark
     * @throws BrokerErrorException if the broker refused the commit — an unknown topic or partition,
     *                              a negative offset, or one past the high-water mark
     */
    public void commitOffset(String groupId, String topic, int partition, long offset) {
        connection.call(new CommitOffsetRequest(groupId, topic, partition, offset), CommitOffsetResponse.class);
    }

    /**
     * Closes the connection. Calling it twice does nothing the second time.
     */
    @Override
    public void close() {
        connection.close();
    }
}
