package io.shrike.clients;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.shrike.core.group.GroupOffsetStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The whole thing, across four operating-system processes: a broker, two producers, and two
 * consumers, none of them sharing a heap with the test or with each other.
 *
 * <p>Threads would have proved less. Every test in {@code shrike-core} starts a broker in the JVM
 * that tests it, which means the broker's objects, the client's objects, and the test's assertions
 * all live in one heap — and a class that accidentally reached across that boundary would still pass.
 * Here the only things that cross are a TCP socket, a ready file, an exit code, and lines of output,
 * so what is proved is that the protocol carries the meaning, not the objects.
 *
 * <p>The resume is the point of the second half. There is no api that reads a committed offset back
 * in this build, so the test reads the offsets out of the broker's own group file — the file the
 * broker wrote when the first consumer committed — and starts the second consumer process from them.
 * A consumer that resumed from zero instead would read every record again, and the assertion on what
 * it received is what tells the two apart.
 */
class ClientProcessIT {

    private static final String TOPIC = "orders";
    private static final int PARTITION_COUNT = 2;
    private static final String GROUP = "readers";

    /** Eight records over two partitions: four each, so neither partition is trivially empty. */
    private static final int FIRST_RUN_RECORD_COUNT = 8;

    /** Four more after the commit: two each, so both partitions have something new to resume to. */
    private static final int SECOND_RUN_RECORD_COUNT = 4;

    /** What every one of these processes exits with when it did what it was started to do. */
    private static final int SUCCESS_EXIT_CODE = 0;

    @TempDir
    Path workingDirectory;

    /** Every process this test started, so that every one of them is destroyed however it ends. */
    private final List<JavaProcess> started = new ArrayList<>();

    /**
     * Runs whatever the test did — passing, failing an assertion, or timing out inside a bounded
     * wait — because a leaked broker holds a port and a leaked consumer holds a connection.
     */
    @AfterEach
    void destroyEveryProcessThisTestStarted() {
        for (JavaProcess process : started) {
            process.destroyTree();
        }
    }

    @Test
    void carriesRecordsBetweenSeparateProducerAndConsumerProcessesAndResumesFromTheCommittedOffsets()
            throws Exception {
        Path dataDirectory = workingDirectory.resolve("data");
        Path readyFilePath = workingDirectory.resolve("shrike.ready");
        JavaProcess broker = start("broker", BrokerProcessMain.class,
                List.of(dataDirectory.toString(), readyFilePath.toString()));
        int port = Await.brokerPort(readyFilePath, broker);
        createTopic(port);

        JavaProcess firstProducer = start("producer-1", ProducerProcessMain.class,
                arguments(port, TOPIC, PARTITION_COUNT, 0, FIRST_RUN_RECORD_COUNT));
        assertEquals(SUCCESS_EXIT_CODE, firstProducer.awaitExit(), () -> outputOf(firstProducer));
        JavaProcess firstConsumer = start("consumer-1", ConsumerProcessMain.class,
                List.of(String.valueOf(port), TOPIC, GROUP, "0:0", "1:0"));
        assertEquals(SUCCESS_EXIT_CODE, firstConsumer.awaitExit(), () -> outputOf(firstConsumer));
        Map<Integer, Long> committed = committedOffsetsInTheBrokersGroupFile(dataDirectory);

        assertTrue(broker.output().contains("listening port=" + port),
                "the port came out of the ready file another process wrote, and that process says the same");
        assertEquals(FIRST_RUN_RECORD_COUNT, acknowledgedRecordsOf(firstProducer),
                "the producer process was given an offset for every record it sent");
        assertEquals(keysOfIndexes(0, 2, 4, 6), keysReadFrom(firstConsumer, 0),
                "partition 0 held every record the producer sent it, in the order it sent them");
        assertEquals(keysOfIndexes(1, 3, 5, 7), keysReadFrom(firstConsumer, 1));
        assertEquals(List.of(0L, 1L, 2L, 3L), offsetsReadFrom(firstConsumer, 0),
                "a partition's offsets climb from zero, whatever the other partition is doing");
        assertEquals(List.of(0L, 1L, 2L, 3L), offsetsReadFrom(firstConsumer, 1));
        assertEquals(Map.of(0, 4L, 1, 4L), committedOffsetsOf(firstConsumer),
                "a consumer commits the offset it would read next, which is one past the last it read");
        assertEquals(Map.of(0, 4L, 1, 4L), committed,
                "and the broker stored those very offsets, under the group the consumer named");

        JavaProcess secondProducer = start("producer-2", ProducerProcessMain.class,
                arguments(port, TOPIC, PARTITION_COUNT, FIRST_RUN_RECORD_COUNT, SECOND_RUN_RECORD_COUNT));
        assertEquals(SUCCESS_EXIT_CODE, secondProducer.awaitExit(), () -> outputOf(secondProducer));
        JavaProcess resumingConsumer = start("consumer-2", ConsumerProcessMain.class,
                List.of(String.valueOf(port), TOPIC, GROUP, "0:" + committed.get(0), "1:" + committed.get(1)));
        assertEquals(SUCCESS_EXIT_CODE, resumingConsumer.awaitExit(), () -> outputOf(resumingConsumer));

        assertEquals(SECOND_RUN_RECORD_COUNT, acknowledgedRecordsOf(secondProducer),
                "the second producer process appended after everything the first one had sent");
        assertEquals(keysOfIndexes(8, 10), keysReadFrom(resumingConsumer, 0),
                "the process that resumed read only what was produced after the commit it started from");
        assertEquals(keysOfIndexes(9, 11), keysReadFrom(resumingConsumer, 1));
        assertEquals(List.of(4L, 5L), offsetsReadFrom(resumingConsumer, 0),
                "and it started at the committed offset rather than at zero");
        assertEquals(Map.of(0, 6L, 1, 6L), committedOffsetsInTheBrokersGroupFile(dataDirectory),
                "the resumed consumer moved the group on from where the first one left it");
        assertTrue(broker.isAlive(), "one broker process served all four of those clients and is still listening");
    }

    /**
     * Creating the topic is the one thing this test does itself, over a socket to the broker process,
     * because it is the setup the flow needs rather than part of the flow.
     */
    private static void createTopic(int port) {
        String host = InetAddress.getLoopbackAddress().getHostAddress();
        try (ShrikeTopics topics = ShrikeTopics.open(ClientConfig.defaults(host, port))) {
            topics.create(TOPIC, PARTITION_COUNT);
        }
    }

    private JavaProcess start(String name, Class<?> mainClass, List<String> arguments) throws IOException {
        JavaProcess process = JavaProcess.start(name, mainClass, workingDirectory.resolve(name + ".out"), arguments);
        started.add(process);
        return process;
    }

    private static List<String> arguments(int port, String topic, int partitionCount, int firstIndex,
                                          int recordCount) {
        return List.of(String.valueOf(port), topic, String.valueOf(partitionCount), String.valueOf(firstIndex),
                String.valueOf(recordCount));
    }

    private static List<String> keysOfIndexes(int... indexes) {
        return IntStream.of(indexes).mapToObj(KeyedRecords::key).toList();
    }

    /**
     * @return how many records the producer process was answered with an offset for
     */
    private static long acknowledgedRecordsOf(JavaProcess producer) {
        return producer.outputLines().stream().filter(line -> line.startsWith("produced ")).count();
    }

    private static List<String> keysReadFrom(JavaProcess consumer, int partition) {
        return fieldsOfLines(consumer, "record", partition).map(fields -> fields.get("key")).toList();
    }

    private static List<Long> offsetsReadFrom(JavaProcess consumer, int partition) {
        return fieldsOfLines(consumer, "record", partition).map(fields -> Long.parseLong(fields.get("offset")))
                .toList();
    }

    /**
     * @return what the consumer process says it committed, by partition
     */
    private static Map<Integer, Long> committedOffsetsOf(JavaProcess consumer) {
        Map<Integer, Long> committed = new LinkedHashMap<>();
        for (int partition = 0; partition < PARTITION_COUNT; partition++) {
            List<Long> offsets = fieldsOfLines(consumer, "committed", partition)
                    .map(fields -> Long.parseLong(fields.get("offset"))).toList();
            assertEquals(1, offsets.size(), "a consumer commits one offset per partition it read");
            committed.put(partition, offsets.get(0));
        }
        return committed;
    }

    /**
     * Reads the offsets out of the file the broker wrote when the commits were acknowledged:
     * {@code groups/<groupId>.offsets}, a version header and then one {@code topic partition offset}
     * line per key.
     *
     * @param dataDirectory the directory the broker process was started with
     * @return the offsets that broker holds for this test's group and topic, by partition
     */
    private static Map<Integer, Long> committedOffsetsInTheBrokersGroupFile(Path dataDirectory) {
        Path groupFile = dataDirectory.resolve(GroupOffsetStore.DIRECTORY_NAME)
                .resolve(GROUP + GroupOffsetStore.FILE_SUFFIX);
        List<String> lines;
        try {
            lines = Files.readAllLines(groupFile);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the committed offsets the broker stored in " + groupFile, e);
        }

        Map<Integer, Long> committed = new LinkedHashMap<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] fields = line.split(" ");
            if (fields.length == 3 && fields[0].equals(TOPIC)) {
                committed.put(Integer.parseInt(fields[1]), Long.parseLong(fields[2]));
            }
        }
        return committed;
    }

    /**
     * @param process   the process whose output to read
     * @param lineKind  the first word of the lines wanted
     * @param partition the partition those lines must name
     * @return one map of {@code field=value} pairs per matching line
     */
    private static Stream<Map<String, String>> fieldsOfLines(JavaProcess process, String lineKind, int partition) {
        return process.outputLines().stream()
                .filter(line -> line.startsWith(lineKind + " "))
                .map(ClientProcessIT::fieldsOf)
                .filter(fields -> Integer.parseInt(fields.get("partition")) == partition);
    }

    private static Map<String, String> fieldsOf(String line) {
        Map<String, String> fields = new LinkedHashMap<>();
        String[] words = line.split(" ");
        for (int index = 1; index < words.length; index++) {
            int separator = words[index].indexOf('=');
            fields.put(words[index].substring(0, separator), words[index].substring(separator + 1));
        }
        return fields;
    }

    private static String outputOf(JavaProcess process) {
        return process.name() + " did not exit " + SUCCESS_EXIT_CODE + ". Its output was:\n" + process.output();
    }
}
