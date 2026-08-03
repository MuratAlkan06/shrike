package io.shrike.clients;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.shrike.core.log.StoredRecord;
import io.shrike.core.net.BrokerConfig;
import io.shrike.core.net.ShrikeBroker;
import io.shrike.core.time.SystemTimeSource;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Routing as the broker sees it: which partition a record actually lands in when the client decides.
 *
 * <p>{@link PartitionRouterTest} pins the mapping itself. These tests are about the other half — that
 * the partition the router picked is the partition the record was appended to, and that naming a
 * partition still overrides the key.
 */
class ProducerRoutingTest {

    private static final String TOPIC = "orders";
    private static final int PARTITION_COUNT = 4;
    private static final int ANSWER_IMMEDIATELY_MS = 0;
    private static final int PLENTY_OF_BYTES = 64 * 1024;
    private static final int ANY_BYTES = 0;

    /** From the frozen table in {@link PartitionRouterTest}: this key belongs to partition 2 of 4. */
    private static final byte[] KEY_OF_PARTITION_TWO = "key-0".getBytes(UTF_8);
    private static final int PARTITION_OF_THAT_KEY = 2;

    @TempDir
    Path dataDirectory;

    private ShrikeBroker broker;
    private ClientConfig config;

    @BeforeEach
    void startBrokerAndCreateTheTopic() {
        broker = ShrikeBroker.start(BrokerConfig.defaults(dataDirectory, BrokerConfig.EPHEMERAL_PORT,
                dataDirectory.resolve("shrike.ready")), new SystemTimeSource());
        config = ClientConfig.defaults(InetAddress.getLoopbackAddress().getHostAddress(), broker.port());

        try (ShrikeTopics topics = ShrikeTopics.open(config)) {
            topics.create(TOPIC, PARTITION_COUNT);
        }
    }

    @AfterEach
    void stopBroker() {
        broker.close();
    }

    @Test
    void appendsAKeyedRecordToThePartitionItsKeyRoutesTo() {
        PartitionRouter partitions = new PartitionRouter(PARTITION_COUNT);

        try (ShrikeProducer producer = ShrikeProducer.open(config);
             ShrikeConsumer consumer = ShrikeConsumer.open(config)) {
            SentRecord sent = producer.send(TOPIC, partitions, KEY_OF_PARTITION_TWO, "first".getBytes(UTF_8));

            assertEquals(PARTITION_OF_THAT_KEY, sent.partition(), "the routed send answers with where it went");
            assertEquals(0L, sent.offset());
            List<StoredRecord> onThatPartition = recordsOf(consumer, PARTITION_OF_THAT_KEY);
            assertEquals(1, onThatPartition.size(), "and the broker holds it there");
            assertArrayEquals(KEY_OF_PARTITION_TWO, onThatPartition.get(0).key());
            assertEquals(0, recordsOf(consumer, 1).size(), "no other partition was written to");
        }
    }

    @Test
    void appendsToThePartitionTheCallerNamedRatherThanTheOneTheKeyRoutesTo() {
        int namedPartition = 1;

        try (ShrikeProducer producer = ShrikeProducer.open(config);
             ShrikeConsumer consumer = ShrikeConsumer.open(config)) {
            long offset = producer.send(TOPIC, namedPartition, KEY_OF_PARTITION_TWO, "first".getBytes(UTF_8));

            assertEquals(0L, offset);
            assertEquals(1, recordsOf(consumer, namedPartition).size(),
                    "a caller that names a partition is obeyed, whatever its key would have routed to");
            assertEquals(0, recordsOf(consumer, PARTITION_OF_THAT_KEY).size(),
                    "so the partition that key routes to was never appended to");
        }
    }

    @Test
    void appendsRecordsWithNoKeyToEveryPartitionInTurn() {
        PartitionRouter partitions = new PartitionRouter(PARTITION_COUNT);

        try (ShrikeProducer producer = ShrikeProducer.open(config);
             ShrikeConsumer consumer = ShrikeConsumer.open(config)) {
            List<Integer> partitionsWrittenTo = new ArrayList<>();
            for (int record = 0; record < PARTITION_COUNT; record++) {
                partitionsWrittenTo.add(producer.send(TOPIC, partitions, null, ("value-" + record).getBytes(UTF_8))
                        .partition());
            }

            assertEquals(List.of(0, 1, 2, 3), partitionsWrittenTo);
            for (int partition = 0; partition < PARTITION_COUNT; partition++) {
                assertEquals(1, recordsOf(consumer, partition).size(),
                        "partition " + partition + " was given one of the four records that had no key");
            }
        }
    }

    private static List<StoredRecord> recordsOf(ShrikeConsumer consumer, int partition) {
        return consumer.fetch(TOPIC, partition, 0L, PLENTY_OF_BYTES, ANSWER_IMMEDIATELY_MS, ANY_BYTES).records();
    }
}
