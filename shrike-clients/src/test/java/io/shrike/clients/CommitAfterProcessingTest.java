package io.shrike.clients;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.shrike.core.group.GroupOffsetStore;
import io.shrike.core.log.StoredRecord;
import io.shrike.core.net.BrokerConfig;
import io.shrike.core.net.ShrikeBroker;
import io.shrike.core.time.SystemTimeSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The order a group makes progress in: process, then commit, and never the other way round.
 *
 * <p>What is proved here is the ordering in one JVM, where a handler that throws stands in for a
 * process that dies. {@code ConsumerGroupRedeliveryIT} proves the same thing the hard way, with a
 * member killed by a signal it cannot catch.
 */
class CommitAfterProcessingTest {

    private static final String TOPIC = "orders";
    private static final int PARTITION = 0;
    private static final int PARTITION_COUNT = 1;
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
    void commitsNothingWhenTheHandlerThrowsAndReadsTheSameRecordsAgain() throws IOException {
        produce("first", "second", "third");
        List<String> processedBeforeItThrew = new ArrayList<>();

        try (ShrikeConsumer consumer = ShrikeConsumer.open(config)) {
            List<StoredRecord> firstRead = fetchFrom(consumer, 0L);
            assertThrows(IllegalStateException.class, () -> consumer.processThenCommit(GROUP, TOPIC, PARTITION, 0L,
                    firstRead, (partition, records) -> {
                        records.forEach(record -> processedBeforeItThrew.add(text(record.value())));
                        throw new IllegalStateException("this handler could not do its work");
                    }));

            List<StoredRecord> secondRead = fetchFrom(consumer, 0L);

            assertEquals(List.of("first", "second", "third"), processedBeforeItThrew,
                    "the handler was given every record before it failed");
            assertFalse(Files.exists(groupFile()),
                    "and nothing was committed, so this group has no committed offsets at all yet");
            assertEquals(List.of("first", "second", "third"), valuesOf(secondRead),
                    "so the very same records are read again, which is what at-least-once delivery means");
        }
    }

    @Test
    void commitsTheOffsetToReadNextOnlyOnceTheHandlerHasReturned() throws IOException {
        produce("first", "second", "third");
        List<String> committedWhenTheHandlerRan = new ArrayList<>();

        try (ShrikeConsumer consumer = ShrikeConsumer.open(config)) {
            List<StoredRecord> records = fetchFrom(consumer, 0L);

            long nextOffset = consumer.processThenCommit(GROUP, TOPIC, PARTITION, 0L, records,
                    (partition, handed) -> committedWhenTheHandlerRan.addAll(committedLines()));

            assertEquals(List.of(), committedWhenTheHandlerRan,
                    "while the handler was running, the group's file did not exist: nothing had been committed");
            assertEquals(3L, nextOffset, "a group that has read offsets 0 to 2 reads 3 next");
            assertTrue(committedLines().contains(TOPIC + " " + PARTITION + " 3"),
                    "and once the handler returned, that number is on the device");
            assertEquals(List.of(), valuesOf(fetchFrom(consumer, nextOffset)), "with nothing left to read");
        }
    }

    @Test
    void commitsNothingWhenThereWasNothingToProcess() throws IOException {
        try (ShrikeConsumer consumer = ShrikeConsumer.open(config)) {
            long nextOffset = consumer.processThenCommit(GROUP, TOPIC, PARTITION, 0L, List.of(),
                    (partition, records) -> {
                        throw new AssertionError("a handler is not called for records that are not there");
                    });

            assertEquals(0L, nextOffset, "the offset to read next is the one that was read from");
            assertFalse(Files.exists(groupFile()), "and no progress was stored, because none was made");
        }
    }

    @Test
    void commitsOnlyAsFarAsTheRecordsItWasHandedRatherThanAsFarAsTheFetchRead() throws IOException {
        produce("first", "second", "third", "fourth");

        try (ShrikeConsumer consumer = ShrikeConsumer.open(config)) {
            List<StoredRecord> everything = fetchFrom(consumer, 0L);
            List<StoredRecord> firstTwo = everything.subList(0, 2);

            long nextOffset = consumer.processThenCommit(GROUP, TOPIC, PARTITION, 0L, firstTwo,
                    (partition, records) -> { });

            assertEquals(2L, nextOffset, "handing over two of the four records commits two of them");
            assertTrue(committedLines().contains(TOPIC + " " + PARTITION + " 2"));
            assertEquals(List.of("third", "fourth"), valuesOf(fetchFrom(consumer, nextOffset)),
                    "so the records that were never handed over are still there to be read");
        }
    }

    private void produce(String... values) {
        try (ShrikeProducer producer = ShrikeProducer.open(config)) {
            for (String value : values) {
                producer.send(TOPIC, PARTITION, null, value.getBytes(UTF_8));
            }
        }
    }

    private static List<StoredRecord> fetchFrom(ShrikeConsumer consumer, long fetchOffset) {
        return consumer.fetch(TOPIC, PARTITION, fetchOffset, PLENTY_OF_BYTES, ANSWER_IMMEDIATELY_MS, ANY_BYTES)
                .records();
    }

    private Path groupFile() {
        return dataDirectory.resolve(GroupOffsetStore.DIRECTORY_NAME).resolve(GROUP + GroupOffsetStore.FILE_SUFFIX);
    }

    /**
     * @return the lines of the file the broker keeps this group's committed offsets in, or none when it
     *         has never committed. Read rather than asked for, because no api reads a committed offset
     *         back in this build
     */
    private List<String> committedLines() {
        if (!Files.exists(groupFile())) {
            return List.of();
        }
        try {
            return Files.readAllLines(groupFile());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + groupFile(), e);
        }
    }

    private static List<String> valuesOf(List<StoredRecord> records) {
        return records.stream().map(record -> text(record.value())).toList();
    }

    private static String text(byte[] bytes) {
        return new String(bytes, UTF_8);
    }
}
