package io.shrike.core.net;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.shrike.core.log.DurableFile;
import io.shrike.core.log.ShrikeIOException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The registry's read-only view of itself, and what a create that fails leaves behind.
 *
 * <p>Everything about a create that succeeds is proved by the broker tests that go through the wire.
 * What is proved here is the enumeration a describe is answered from, including the order it comes back
 * in, and the one thing no wire test can reach: which half of a half-written create survives.
 */
class TopicRegistryTest {

    private static final String TOPIC = "orders";

    /** The same topic name spelled the way a later create might arrive under. */
    private static final String SAME_TOPIC_CAPITALISED = "Orders";

    private static final String OTHER_TOPIC = "events";

    /** What the failing creates below ask for, and what the registry file must go on listing. */
    private static final int THREE_PARTITIONS = 3;

    @TempDir
    Path dataDirectory;

    @Test
    void enumeratesEveryOpenTopicByNameWithThePartitionsItWasCreatedWith() {
        try (TopicRegistry registry = TopicRegistry.open(BrokerHarness.config(dataDirectory),
                BrokerHarness.SYSTEM_CLOCK)) {
            registry.create("orders", 3);
            registry.create("events", 1);

            List<Topic> topics = registry.topics();

            assertEquals(List.of("events", "orders"), topics.stream().map(Topic::name).toList(),
                    "topics come back by name, so two brokers holding the same topics answer the same way");
            assertEquals(List.of(1, 3), topics.stream().map(Topic::partitionCount).toList());
        }
    }

    @Test
    void enumeratesTheSpellingATopicWasCreatedWithRatherThanTheOneACallerAsksUnder() {
        try (TopicRegistry registry = TopicRegistry.open(BrokerHarness.config(dataDirectory),
                BrokerHarness.SYSTEM_CLOCK)) {
            registry.create("Orders", 1);

            List<Topic> topics = registry.topics();

            assertEquals(List.of("Orders"), topics.stream().map(Topic::name).toList(),
                    "the registry file and the partition directories carry that spelling, so a describe does too");
            assertTrue(registry.topic("ORDERS").isPresent(), "and it still answers to any casing of it");
        }
    }

    @Test
    void enumeratesNoTopicsForABrokerThatHoldsNone() {
        try (TopicRegistry registry = TopicRegistry.open(BrokerHarness.config(dataDirectory),
                BrokerHarness.SYSTEM_CLOCK)) {
            List<Topic> topics = registry.topics();

            assertEquals(List.of(), topics, "a broker with no topics has none to describe, which is not a failure");
        }
    }

    /**
     * The registry file is what says which topics exist, and the atomic move is what gives it its new
     * contents. Before that move nothing on the device carries the topic's name — the bytes are in
     * {@code topics.tmp}, which no start reads — so a create that failed there has to leave the broker
     * exactly as it found it, holding no topic and spending none of the partition budget.
     *
     * <p>The failure is injected through the same seam that watches the steps of a durable write, and
     * both steps before the move are covered, because what the rollback turns on is the move and not the
     * number of steps that ran ahead of it.
     */
    @ParameterizedTest(name = "failing after {0}")
    @EnumSource(value = DurableFile.Step.class, names = {"WRITTEN", "FORCED"})
    void forgetsATopicWhoseCreateFailedBeforeItsRegistryFileWasMovedIntoPlace(DurableFile.Step lastStepTaken)
            throws IOException {
        try (TopicRegistry registry = TopicRegistry.open(BrokerHarness.config(dataDirectory),
                BrokerHarness.SYSTEM_CLOCK, new DeviceFailingAfterOneStep(lastStepTaken))) {

            assertThrows(ShrikeIOException.class, () -> registry.create(TOPIC, THREE_PARTITIONS));

            assertTrue(registry.topic(TOPIC).isEmpty(),
                    "a create whose file never took the registry's name did not create a topic this broker holds");
            assertEquals(List.of(), registry.topics());
            assertFalse(Files.exists(dataDirectory.resolve(TopicRegistry.FILE_NAME)),
                    "and there is no registry file behind it, because the move that would have made one never ran");
            assertEquals(List.of(TopicRegistry.FILE_NAME + ".tmp"), fileNamesIn(dataDirectory),
                    "nor a partition directory, since the logs are opened only once the file is written");

            registry.create(TOPIC, THREE_PARTITIONS);

            assertEquals(THREE_PARTITIONS, registry.topic(TOPIC).orElseThrow().partitionCount(),
                    "and the name is free, so the create that comes after the failure is served in full");
        }
        try (TopicRegistry reopened = TopicRegistry.open(BrokerHarness.config(dataDirectory),
                BrokerHarness.SYSTEM_CLOCK)) {
            assertEquals(THREE_PARTITIONS, reopened.topic(TOPIC).orElseThrow().partitionCount(),
                    "which is what a start over this directory finds: one topic, three partitions, one file");
        }
    }

    /**
     * The move is what puts a topic on the device: from that instant the registry file lists it, and
     * every later reader of the directory finds it — this broker, the next start, and every rewrite of
     * that file, which is built from what this broker holds. So a create that failed after the move
     * keeps its topic, at the count the file lists, and still fails to the client, because nothing
     * confirmed the write was durable.
     *
     * <p>What memory would otherwise do is the defect: a topic the file lists and the broker has
     * forgotten is a topic the next create writes the file without, which shrinks it to whatever that
     * create asked for and leaves the surplus partition directories listed by nothing.
     */
    @ParameterizedTest(name = "failing after {0}")
    @EnumSource(value = DurableFile.Step.class, names = {"MOVED", "DIRECTORY_FORCED"})
    void keepsATopicWhoseCreateMovedItsRegistryFileIntoPlaceAndThenFailed(DurableFile.Step lastStepTaken)
            throws IOException {
        try (TopicRegistry registry = TopicRegistry.open(BrokerHarness.config(dataDirectory),
                BrokerHarness.SYSTEM_CLOCK, new DeviceFailingAfterOneStep(lastStepTaken))) {

            assertThrows(ShrikeIOException.class, () -> registry.create(TOPIC, THREE_PARTITIONS));

            assertEquals(TopicRegistry.VERSION_HEADER + "\n" + TOPIC + " " + THREE_PARTITIONS + "\n",
                    Files.readString(dataDirectory.resolve(TopicRegistry.FILE_NAME), UTF_8),
                    "the move ran, so the file lists the topic whatever failed after it");
            Topic kept = registry.topic(TOPIC).orElseThrow();
            assertEquals(THREE_PARTITIONS, kept.partitionCount(),
                    "and the broker holds it at the count that file lists, which is what a start would open");
            assertEquals(List.of(), kept.partitions(),
                    "with no log open behind it, because the create failed before it opened one");
            assertEquals(List.of(TopicRegistry.FILE_NAME), fileNamesIn(dataDirectory),
                    "and no partition directory either, so there is nothing on disk the file does not account for");

            assertThrows(TopicAlreadyExistsException.class, () -> registry.create(SAME_TOPIC_CAPITALISED, 1),
                    "a later create of that name is refused rather than served over a topic the file already lists");

            registry.create(OTHER_TOPIC, 1);

            assertEquals(TopicRegistry.VERSION_HEADER + "\n" + OTHER_TOPIC + " 1\n" + TOPIC + " "
                            + THREE_PARTITIONS + "\n",
                    Files.readString(dataDirectory.resolve(TopicRegistry.FILE_NAME), UTF_8),
                    "and a create of another topic rewrites the file without shrinking the one that failed");
        }
        try (TopicRegistry reopened = TopicRegistry.open(BrokerHarness.config(dataDirectory),
                BrokerHarness.SYSTEM_CLOCK)) {
            assertEquals(THREE_PARTITIONS, reopened.topic(TOPIC).orElseThrow().partitions().size(),
                    "so the start after the failure opens the three partitions the failed create asked for");
        }
    }

    /**
     * The other half of the same defect, and the one that cost a topic its partitions. A create writes
     * the registry file before it opens a single log, on purpose, so a log that will not open leaves a
     * topic the file lists in full — and the broker used to hold nothing for it, so the next create of
     * that name was served and rewrote the file with a count of its own, orphaning the directories above
     * it. Keeping the topic turns that create into the refusal it always should have been.
     *
     * <p>The log is made to fail by putting a regular file where partition 2's directory has to go,
     * which is a real filesystem saying no to a real create rather than a seam standing in for one.
     */
    @Test
    void refusesALaterCreateOfATopicWhoseRegistryFileLandedButWhoseLogsWouldNotOpen() throws IOException {
        Path inTheWayOfTheThirdPartition = dataDirectory.resolve(TOPIC + "-2");
        Files.writeString(inTheWayOfTheThirdPartition, "a file where a partition directory has to go\n", UTF_8);

        try (TopicRegistry registry = TopicRegistry.open(BrokerHarness.config(dataDirectory),
                BrokerHarness.SYSTEM_CLOCK)) {

            assertThrows(ShrikeIOException.class, () -> registry.create(TOPIC, THREE_PARTITIONS));

            assertEquals(TopicRegistry.VERSION_HEADER + "\n" + TOPIC + " " + THREE_PARTITIONS + "\n",
                    Files.readString(dataDirectory.resolve(TopicRegistry.FILE_NAME), UTF_8),
                    "the registry write finished every step, so the file lists the topic with three partitions");
            assertEquals(THREE_PARTITIONS, registry.topic(TOPIC).orElseThrow().partitionCount(),
                    "and the broker holds it at that count, though it has no log open behind it");

            assertThrows(TopicAlreadyExistsException.class, () -> registry.create(TOPIC, 1),
                    "so the create that used to shrink this topic to one partition is refused instead");

            registry.create(OTHER_TOPIC, 1);

            assertEquals(TopicRegistry.VERSION_HEADER + "\n" + OTHER_TOPIC + " 1\n" + TOPIC + " "
                            + THREE_PARTITIONS + "\n",
                    Files.readString(dataDirectory.resolve(TopicRegistry.FILE_NAME), UTF_8),
                    "and the rewrite another create makes still lists all three of them");
            assertEquals(List.of(OTHER_TOPIC + "-0", TOPIC + "-0", TOPIC + "-1", TOPIC + "-2",
                            TopicRegistry.FILE_NAME), fileNamesIn(dataDirectory),
                    "so the two directories the failed open left are the topic's own rather than orphans, beside"
                            + " the file this test put in the third one's way");
        }

        Files.delete(inTheWayOfTheThirdPartition);
        try (TopicRegistry reopened = TopicRegistry.open(BrokerHarness.config(dataDirectory),
                BrokerHarness.SYSTEM_CLOCK)) {
            assertEquals(THREE_PARTITIONS, reopened.topic(TOPIC).orElseThrow().partitions().size(),
                    "and once the way is clear a start opens all three, the two that were there and the one"
                            + " that never was");
        }
    }

    /**
     * @param directory a directory
     * @return the names it holds, sorted
     * @throws IOException if it cannot be listed
     */
    private static List<String> fileNamesIn(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.map(entry -> entry.getFileName().toString()).sorted().toList();
        }
    }

    /**
     * A device that takes every durable step up to and including the one it is named for, refuses once
     * there, and takes everything after that — the nearest a test can stand to a disk that fills, or to a
     * machine that stops, at a chosen point in the sequence. {@link DurableFile} reports each step as it
     * finishes, so an exception thrown when it reports one stands for a failure that happened after that
     * step ran and before the next one did.
     */
    private static final class DeviceFailingAfterOneStep implements DurableFile.StepObserver {

        private final DurableFile.Step lastStepItTakes;

        // confined to: the test thread that creates through it
        private boolean hasRefused;

        DeviceFailingAfterOneStep(DurableFile.Step lastStepItTakes) {
            this.lastStepItTakes = lastStepItTakes;
        }

        @Override
        public void completed(DurableFile.Step step) {
            if (step != lastStepItTakes || hasRefused) {
                return;
            }
            hasRefused = true;
            throw new ShrikeIOException("the device this test injected refused after " + step,
                    new IOException("no space left on device"));
        }
    }
}
