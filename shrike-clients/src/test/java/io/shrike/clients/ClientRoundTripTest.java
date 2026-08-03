package io.shrike.clients;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.shrike.core.group.GroupOffsetStore;
import io.shrike.core.log.StoredRecord;
import io.shrike.core.net.BrokerConfig;
import io.shrike.core.net.ShrikeBroker;
import io.shrike.core.protocol.ErrorCode;
import io.shrike.core.time.SystemTimeSource;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The client library against a real broker on a real socket. The broker runs in this JVM, which is
 * what keeps these tests quick; what proves the same thing across a process boundary is
 * {@link ClientProcessIT}, and it is the client library these use either way.
 */
class ClientRoundTripTest {

    private static final String TOPIC = "orders";
    private static final int PARTITION_COUNT = 2;
    private static final String GROUP = "readers";
    private static final int ANSWER_IMMEDIATELY_MS = 0;
    private static final int PLENTY_OF_BYTES = 64 * 1024;
    private static final int ANY_BYTES = 0;

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
    void answersEachSendWithTheOffsetTheBrokerAppendedThatRecordAt() {
        try (ShrikeProducer producer = ShrikeProducer.open(config)) {
            long first = producer.send(TOPIC, 0, key("a"), value("first"));
            long second = producer.send(TOPIC, 0, key("b"), value("second"));
            long onAnotherPartition = producer.send(TOPIC, 1, key("c"), value("third"));

            assertEquals(0L, first, "the first record of an empty partition is offset 0");
            assertEquals(1L, second);
            assertEquals(0L, onAnotherPartition, "every partition counts its own offsets from zero");
        }
    }

    @Test
    void readsBackEveryRecordItSentWithTheKeysValuesAndOffsetsTheLogStored() {
        try (ShrikeProducer producer = ShrikeProducer.open(config);
             ShrikeConsumer consumer = ShrikeConsumer.open(config)) {
            producer.send(TOPIC, 0, key("a"), value("first"));
            producer.send(TOPIC, 0, null, value("second"));

            FetchedRecords fetched = consumer.fetch(TOPIC, 0, 0L, PLENTY_OF_BYTES, ANSWER_IMMEDIATELY_MS, ANY_BYTES);

            assertEquals(2, fetched.records().size());
            assertEquals(2L, fetched.highWaterMark(), "the high-water mark is the offset the partition appends next");
            StoredRecord first = fetched.records().get(0);
            assertEquals(0L, first.offset());
            assertArrayEquals(key("a"), first.key());
            assertArrayEquals(value("first"), first.value());
            assertNull(fetched.records().get(1).key(), "a record sent without a key comes back without one");
            assertEquals(2L, fetched.nextOffset(0L), "the offset to ask for next is one past the last record read");
        }
    }

    @Test
    void answersAFetchAtTheHighWaterMarkWithNoRecordsRatherThanAFailure() {
        try (ShrikeProducer producer = ShrikeProducer.open(config);
             ShrikeConsumer consumer = ShrikeConsumer.open(config)) {
            producer.send(TOPIC, 0, key("a"), value("first"));

            FetchedRecords caughtUp = consumer.fetch(TOPIC, 0, 1L, PLENTY_OF_BYTES, ANSWER_IMMEDIATELY_MS, ANY_BYTES);

            assertEquals(List.of(), caughtUp.records(), "there is nothing after the last record");
            assertEquals(1L, caughtUp.highWaterMark());
            assertEquals(1L, caughtUp.nextOffset(1L), "a fetch that read nothing leaves the next offset where it was");
        }
    }

    @Test
    void holdsAFetchOpenForItsMaxWaitMsEvenWhenThatIsLongerThanTheReadTimeout() {
        int maxWaitMs = 1_500;
        ClientConfig shortTimeouts = new ClientConfig(config.host(), config.port(), config.connectTimeoutMillis(),
                500, config.maxResponseBytes());

        try (ShrikeConsumer consumer = ShrikeConsumer.open(shortTimeouts)) {
            FetchedRecords quiet = consumer.fetch(TOPIC, 0, 0L, PLENTY_OF_BYTES, maxWaitMs, 1);

            assertEquals(List.of(), quiet.records(), "a wait that expires answers with what there is, which is nothing");
            assertEquals(0L, quiet.highWaterMark());
        }
    }

    @Test
    void storesTheOffsetAGroupShouldReadNextWhereTheBrokerKeepsCommittedOffsets() throws IOException {
        try (ShrikeProducer producer = ShrikeProducer.open(config);
             ShrikeConsumer consumer = ShrikeConsumer.open(config)) {
            producer.send(TOPIC, 0, key("a"), value("first"));
            producer.send(TOPIC, 0, key("b"), value("second"));

            consumer.commitOffset(GROUP, TOPIC, 0, 2L);

            Path groupFile = dataDirectory.resolve(GroupOffsetStore.DIRECTORY_NAME)
                    .resolve(GROUP + GroupOffsetStore.FILE_SUFFIX);
            assertTrue(Files.readAllLines(groupFile).contains(TOPIC + " 0 2"),
                    "the commit is on the device before the client is told about it, so it is in the file now");
        }
    }

    @Test
    void raisesTheBrokersErrorCodeAsATypedFailureAndKeepsTheConnectionUsable() {
        try (ShrikeConsumer consumer = ShrikeConsumer.open(config)) {
            BrokerErrorException unknownTopic = assertThrows(BrokerErrorException.class,
                    () -> consumer.fetch("no-such-topic", 0, 0L, PLENTY_OF_BYTES, ANSWER_IMMEDIATELY_MS, ANY_BYTES));
            FetchedRecords afterTheError = consumer.fetch(TOPIC, 0, 0L, PLENTY_OF_BYTES, ANSWER_IMMEDIATELY_MS,
                    ANY_BYTES);

            assertEquals(ErrorCode.UNKNOWN_TOPIC_OR_PARTITION, unknownTopic.errorCode());
            assertEquals(BrokerConnection.FIRST_CORRELATION_ID, unknownTopic.correlationId(),
                    "the failure names the request it answers, which is the client's own number");
            assertEquals(OptionalLong.empty(), unknownTopic.logStartOffset(),
                    "every code but offset out of range is the whole answer, with nothing behind it");
            assertEquals(0L, afterTheError.highWaterMark(), "an error answers a request without ending the connection");
        }
    }

    @Test
    void raisesOffsetOutOfRangeCarryingTheOffsetThePartitionCanStillBeReadFrom() {
        try (ShrikeProducer producer = ShrikeProducer.open(config);
             ShrikeConsumer consumer = ShrikeConsumer.open(config)) {
            producer.send(TOPIC, 0, key("a"), value("first"));

            BrokerErrorException pastTheEnd = assertThrows(BrokerErrorException.class,
                    () -> consumer.fetch(TOPIC, 0, 9L, PLENTY_OF_BYTES, ANSWER_IMMEDIATELY_MS, ANY_BYTES));

            assertEquals(ErrorCode.OFFSET_OUT_OF_RANGE, pastTheEnd.errorCode());
            assertEquals(OptionalLong.of(0L), pastTheEnd.logStartOffset(),
                    "the one refusal that answers with a number, so a consumer that has fallen behind the"
                            + " broker's retention learns where it may resume instead of guessing");
            assertTrue(pastTheEnd.getMessage().contains("logStartOffset=0"), pastTheEnd.getMessage());
        }
    }

    @Test
    void raisesTopicAlreadyExistsWhenATopicIsCreatedTwice() {
        try (ShrikeTopics topics = ShrikeTopics.open(config)) {
            BrokerErrorException repeated = assertThrows(BrokerErrorException.class,
                    () -> topics.create(TOPIC, PARTITION_COUNT));

            assertEquals(ErrorCode.TOPIC_ALREADY_EXISTS, repeated.errorCode());
        }
    }

    @Test
    void closesOnceHoweverManyTimesItIsAskedTo() {
        ShrikeProducer producer = ShrikeProducer.open(config);
        BrokerConnection connection = BrokerConnection.open(config);

        producer.close();
        producer.close();
        connection.close();
        connection.close();

        assertFalse(connection.isOpen());
        assertThrows(IllegalStateException.class, () -> producer.send(TOPIC, 0, key("a"), value("after close")),
                "a closed producer refuses to pretend it sent anything");
    }

    private static byte[] key(String key) {
        return key.getBytes(UTF_8);
    }

    private static byte[] value(String value) {
        return value.getBytes(UTF_8);
    }
}
