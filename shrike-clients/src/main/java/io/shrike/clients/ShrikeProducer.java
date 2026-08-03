package io.shrike.clients;

import io.shrike.core.log.ProducedRecord;
import io.shrike.core.protocol.ProduceRequest;
import io.shrike.core.protocol.ProduceResponse;
import java.util.List;
import java.util.Objects;

/**
 * Appends records to a partition and tells the caller the offset each one was given.
 *
 * <p>Blocking and synchronous: {@link #send} returns when the broker has answered, and there is no
 * buffer, no background sender, and nothing in flight afterwards. A producer owns the connection it
 * was built with and is used by one thread, for the reason {@link BrokerConnection} gives.
 *
 * <p><strong>What an acknowledged send promises.</strong> The offset comes back once the record's
 * bytes have been handed to the operating system on the broker's machine — ordering and integrity,
 * not the survival of a power cut. The README says the same thing in one paragraph, and it is the
 * whole promise.
 */
public final class ShrikeProducer implements AutoCloseable {

    private final BrokerConnection connection;

    /**
     * @param connection the connection this producer speaks through and, from here on, owns: closing
     *                   the producer closes it
     */
    public ShrikeProducer(BrokerConnection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    /**
     * Opens a connection of this producer's own.
     *
     * @param config where the broker is
     * @return the producer, which the caller closes
     */
    public static ShrikeProducer open(ClientConfig config) {
        return new ShrikeProducer(BrokerConnection.open(config));
    }

    /**
     * Appends one record to the end of the partition the caller names.
     *
     * <p>The partition the caller names is the partition the record goes to, whatever its key would
     * have routed to: naming one is how a caller that already knows where a record belongs says so.
     * {@link #send(String, PartitionRouter, byte[], byte[])} is the other half of that rule.
     *
     * @param topic     the topic to append to
     * @param partition the partition of that topic
     * @param key       the record key, or {@code null} for a record with no key. An empty array is a
     *                  key of zero bytes and stays distinct from {@code null} on disk
     * @param value     the payload, which is never {@code null}
     * @return the offset the record was appended at, which is the only half of its name the caller
     *         does not already hold
     * @throws BrokerErrorException if the broker refused the append — an unknown topic or partition,
     *                              or a record larger than it will store
     */
    public long send(String topic, int partition, byte[] key, byte[] value) {
        Objects.requireNonNull(value, "value");

        ProduceRequest request = new ProduceRequest(topic, partition, List.of(new ProducedRecord(key, value)));
        return connection.call(request, ProduceResponse.class).baseOffset();
    }

    /**
     * Appends one record to the partition a {@link PartitionRouter} picks for it: the partition its key
     * hashes to, or the next partition in turn when it has no key.
     *
     * <p>Routing happens here rather than on the broker — the produce request on the wire carries an
     * explicit partition either way, and this is the client deciding which one to put in it.
     *
     * @param topic      the topic to append to
     * @param partitions the router deciding which partition this record belongs to
     * @param key        the record key, or {@code null} for a record with no key
     * @param value      the payload, which is never {@code null}
     * @return the partition the record was routed to and the offset it was appended at
     * @throws BrokerErrorException if the broker refused the append — an unknown topic or partition,
     *                              or a record larger than it will store
     */
    public SentRecord send(String topic, PartitionRouter partitions, byte[] key, byte[] value) {
        Objects.requireNonNull(partitions, "partitions");

        int partition = partitions.partitionFor(key);
        return new SentRecord(partition, send(topic, partition, key, value));
    }

    /**
     * Closes the connection. Calling it twice does nothing the second time.
     */
    @Override
    public void close() {
        connection.close();
    }
}
