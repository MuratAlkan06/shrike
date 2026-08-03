package io.shrike.clients;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.shrike.core.group.GroupOffsetStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The at-least-once evidence: a group member is killed with a signal it cannot catch while it is
 * holding records it has processed and not committed, and everything it did that work for is delivered
 * again to the process that replaces it.
 *
 * <p>Five operating-system processes, none of them sharing a heap with this test: a broker, a producer
 * that lets a {@link PartitionRouter} pick each record's partition, the member that is killed, the
 * member started in its place with the same index, and the group's other member.
 *
 * <p><strong>How the kill point is decided rather than timed.</strong> Each member writes one line per
 * record into a journal of its own and forces that line to the device <em>before</em> the record counts
 * as processed, then hands records over {@value #RECORDS_PER_COMMIT} at a time, which is what commits
 * them. The member that is going to die is told to pause at one partition and offset: it journals that
 * record and waits forever, holding a batch it has not committed. This test tails its journal for that
 * exact line and only then kills it, so the moment of the kill is a place in the data. Nothing here
 * sleeps, and nothing asserts how long anything took.
 *
 * <p><strong>What is proved.</strong> Over the multiset of every journal: every record produced was
 * processed at least once; every record the killed member had processed past its last committed offset
 * was processed again by its replacement; the replacement began exactly at the offset the broker had
 * stored, for each partition it owns; and no partition was read by two members. The counts that make
 * those statements interesting — the records produced, and the records that came twice — are asserted
 * to be non-empty, so a run in which nothing happened fails rather than passes.
 */
class ConsumerGroupRedeliveryIT {

    private static final String TOPIC = "orders";
    private static final int PARTITION_COUNT = 4;
    private static final String GROUP = "readers";

    /** Enough records that a member commits several times over and still has work in hand when it dies. */
    private static final int RECORD_COUNT = 40;

    private static final int MEMBER_COUNT = 2;

    /** More than one, so there is always processed work the broker has not been told about. */
    private static final int RECORDS_PER_COMMIT = 4;

    /** The member that is killed and then started again, which owns the even-numbered partitions. */
    private static final int KILLED_MEMBER_INDEX = 0;

    /** The other member of the group, which owns the odd-numbered ones and runs to the end. */
    private static final int OTHER_MEMBER_INDEX = 1;

    /**
     * Where the doomed member stops: a partition it owns, at the second record of its second batch. By
     * then it has committed exactly one batch of that partition, and the two records it has journaled
     * since are work the broker will never hear about.
     */
    private static final int PAUSE_PARTITION = 2;
    private static final long PAUSE_OFFSET = RECORDS_PER_COMMIT + 1;

    private static final int SUCCESS_EXIT_CODE = 0;

    @TempDir
    Path workingDirectory;

    /** Every process this test started, so that every one of them is destroyed however it ends. */
    private final List<JavaProcess> started = new ArrayList<>();

    /**
     * Runs whatever the test did — passing, failing an assertion, or timing out inside a bounded
     * wait — because a leaked broker holds a port and a leaked member holds a connection.
     */
    @AfterEach
    void destroyEveryProcessThisTestStarted() {
        for (JavaProcess process : started) {
            process.destroyTree();
        }
    }

    @Test
    void redeliversTheRecordsAKilledMemberProcessedButNeverCommittedToTheMemberThatReplacesIt() throws Exception {
        Path dataDirectory = workingDirectory.resolve("data");
        Path readyFilePath = workingDirectory.resolve("shrike.ready");
        JavaProcess broker = start("broker", BrokerProcessMain.class,
                List.of(dataDirectory.toString(), readyFilePath.toString()));
        int port = Await.brokerPort(readyFilePath, broker);
        createTopic(port);

        JavaProcess producer = start("producer", RoutedProducerProcessMain.class, List.of(String.valueOf(port),
                TOPIC, String.valueOf(PARTITION_COUNT), String.valueOf(RECORD_COUNT)));
        assertEquals(SUCCESS_EXIT_CODE, producer.awaitExit(), () -> outputOf(producer));
        List<ProcessedRecord> produced = recordsIn(producer.outputLines(), "produced");
        Map<Integer, Long> highWaterMarks = highWaterMarksOf(produced);

        Path killedJournal = workingDirectory.resolve("member-killed.journal");
        JavaProcess killedMember = start("member-killed", GroupMemberProcessMain.class, memberArguments(port,
                KILLED_MEMBER_INDEX, killedJournal, PAUSE_PARTITION + ":" + PAUSE_OFFSET, Map.of()));
        Await.lineInFile(killedJournal, journalLineOf(produced, PAUSE_PARTITION, PAUSE_OFFSET), killedMember,
                () -> journalsAndCommittedOffsets(dataDirectory));
        // destroyForcibly is SIGKILL here: no shutdown hook runs, no buffer is flushed, and nothing this
        // process was holding can be committed on the way out.
        killedMember.destroyTree();
        Map<Integer, Long> committedWhenItDied = committedOffsetsInTheBrokersGroupFile(dataDirectory);

        Path replacementJournal = workingDirectory.resolve("member-replacement.journal");
        JavaProcess replacement = start("member-replacement", GroupMemberProcessMain.class, memberArguments(port,
                KILLED_MEMBER_INDEX, replacementJournal, GroupMemberProcessMain.NO_PAUSE, committedWhenItDied));
        Path otherJournal = workingDirectory.resolve("member-other.journal");
        JavaProcess otherMember = start("member-other", GroupMemberProcessMain.class, memberArguments(port,
                OTHER_MEMBER_INDEX, otherJournal, GroupMemberProcessMain.NO_PAUSE, Map.of()));
        assertEquals(SUCCESS_EXIT_CODE, replacement.awaitExit(), () -> outputOf(replacement));
        assertEquals(SUCCESS_EXIT_CODE, otherMember.awaitExit(), () -> outputOf(otherMember));

        List<ProcessedRecord> byTheKilledMember = journal(killedJournal);
        List<ProcessedRecord> byTheReplacement = journal(replacementJournal);
        List<ProcessedRecord> byTheOtherMember = journal(otherJournal);
        Map<ProcessedRecord, Long> timesProcessed = timesProcessed(byTheKilledMember, byTheReplacement,
                byTheOtherMember);
        List<ProcessedRecord> neverCommitted = byTheKilledMember.stream()
                .filter(record -> record.offset() >= committedWhenItDied.getOrDefault(record.partition(), 0L))
                .toList();

        assertNotEquals(SUCCESS_EXIT_CODE, killedMember.exitCode(),
                "the member did not end: it was killed while it was holding work it had not committed");
        assertEquals(RECORD_COUNT, produced.size(), "the producer routed and acknowledged every record");
        assertFalse(produced.isEmpty(), "there is something to prove: records were produced");
        assertEquals(List.of(), produced.stream().filter(record -> !timesProcessed.containsKey(record)).toList(),
                "every record produced was processed at least once, across every member's journal");
        assertFalse(neverCommitted.isEmpty(),
                "there is something to prove: the killed member had processed records it never committed");
        assertTrue(neverCommitted.contains(recordAt(produced, PAUSE_PARTITION, PAUSE_OFFSET)),
                "the record it was paused on is one of them");
        for (ProcessedRecord record : neverCommitted) {
            assertEquals(2L, timesProcessed.get(record), record + " was processed by the member that was killed and"
                    + " then again by the one that replaced it, because its offset was never committed");
        }
        for (int partition : new GroupAssignment(GROUP, KILLED_MEMBER_INDEX, MEMBER_COUNT).partitionsOf(
                PARTITION_COUNT)) {
            long committed = committedWhenItDied.getOrDefault(partition, 0L);
            assertEquals(offsets(committed, highWaterMarks.get(partition)), offsetsIn(byTheReplacement, partition),
                    "the replacement read partition " + partition + " from the offset the broker had stored — the"
                            + " next offset to read, one past the last record its predecessor committed — to the end");
        }
        assertEquals(Set.of(0, 2), partitionsIn(byTheKilledMember, byTheReplacement),
                "a member reads the partitions its index owns and no others, whichever process it is");
        assertEquals(Set.of(1, 3), partitionsIn(byTheOtherMember));
        assertEquals(highWaterMarks, committedOffsetsInTheBrokersGroupFile(dataDirectory),
                "and the group ends up committed to the end of every partition");
        assertTrue(broker.isAlive(), "one broker process served all of that and is still listening");
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

    private static List<String> memberArguments(int port, int memberIndex, Path journalFile, String pauseAt,
                                                Map<Integer, Long> startOffsets) {
        List<String> arguments = new ArrayList<>(List.of(String.valueOf(port), TOPIC, GROUP,
                String.valueOf(memberIndex), String.valueOf(MEMBER_COUNT), String.valueOf(PARTITION_COUNT),
                String.valueOf(RECORDS_PER_COMMIT), journalFile.toString(), pauseAt));
        startOffsets.forEach((partition, offset) -> arguments.add(partition + ":" + offset));
        return arguments;
    }

    /**
     * @return the journal line the killed member writes for one record, which is what this test waits
     *         for before it kills that member
     */
    private static String journalLineOf(List<ProcessedRecord> produced, int partition, long offset) {
        return recordAt(produced, partition, offset).journalLine();
    }

    private static ProcessedRecord recordAt(List<ProcessedRecord> produced, int partition, long offset) {
        return produced.stream()
                .filter(record -> record.partition() == partition && record.offset() == offset)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no record was produced to partition " + partition
                        + " at offset " + offset + ", so this flow's numbers no longer describe it: " + produced));
    }

    /**
     * @return how many times each record was processed, over every member's journal. A record processed
     *         twice is a redelivery, which is what at-least-once delivery allows and this test measures
     */
    @SafeVarargs
    private static Map<ProcessedRecord, Long> timesProcessed(List<ProcessedRecord>... journals) {
        List<ProcessedRecord> everything = new ArrayList<>();
        for (List<ProcessedRecord> journal : journals) {
            everything.addAll(journal);
        }
        return everything.stream().collect(Collectors.groupingBy(record -> record, Collectors.counting()));
    }

    /**
     * @return the offset each partition would append next, worked out from what the producer said it
     *         sent, which is what every member has to reach
     */
    private static Map<Integer, Long> highWaterMarksOf(List<ProcessedRecord> produced) {
        Map<Integer, Long> highWaterMarks = new TreeMap<>();
        for (ProcessedRecord record : produced) {
            highWaterMarks.merge(record.partition(), record.offset() + 1, Math::max);
        }
        return highWaterMarks;
    }

    private static List<Long> offsets(long fromInclusive, long toExclusive) {
        return LongStream.range(fromInclusive, toExclusive).boxed().toList();
    }

    private static List<Long> offsetsIn(List<ProcessedRecord> journal, int partition) {
        return journal.stream().filter(record -> record.partition() == partition).map(ProcessedRecord::offset).toList();
    }

    @SafeVarargs
    private static Set<Integer> partitionsIn(List<ProcessedRecord>... journals) {
        Set<Integer> partitions = new LinkedHashSet<>();
        for (List<ProcessedRecord> journal : journals) {
            journal.forEach(record -> partitions.add(record.partition()));
        }
        return partitions;
    }

    private static List<ProcessedRecord> journal(Path journalFile) {
        return recordsIn(linesOf(journalFile), "processed");
    }

    private static List<ProcessedRecord> recordsIn(List<String> lines, String lineKind) {
        return lines.stream()
                .filter(line -> line.startsWith(lineKind + " "))
                .map(ConsumerGroupRedeliveryIT::fieldsOf)
                .map(fields -> new ProcessedRecord(Integer.parseInt(fields.get("partition")),
                        Long.parseLong(fields.get("offset")), fields.get("key"), fields.get("crc")))
                .toList();
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

    /**
     * Reads the offsets out of the file the broker wrote when the commits were acknowledged:
     * {@code groups/<groupId>.offsets}, a version header and then one {@code topic partition offset}
     * line per key. It is read rather than asked for because this build has no api that reads a
     * committed offset back, which is also why a replacement member is handed its start offsets.
     *
     * @param dataDirectory the directory the broker process was started with
     * @return the offsets that broker holds for this test's group and topic, by partition
     */
    private static Map<Integer, Long> committedOffsetsInTheBrokersGroupFile(Path dataDirectory) {
        Path groupFile = dataDirectory.resolve(GroupOffsetStore.DIRECTORY_NAME)
                .resolve(GROUP + GroupOffsetStore.FILE_SUFFIX);

        Map<Integer, Long> committed = new TreeMap<>();
        List<String> lines = linesOf(groupFile);
        for (String line : lines.subList(Math.min(1, lines.size()), lines.size())) {
            String[] fields = line.split(" ");
            if (fields.length == 3 && fields[0].equals(TOPIC)) {
                committed.put(Integer.parseInt(fields[1]), Long.parseLong(fields[2]));
            }
        }
        return committed;
    }

    private static List<String> linesOf(Path file) {
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            return Files.readAllLines(file);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    /**
     * @return every journal and the offsets the broker has stored, for a wait that gave up: what a
     *         reader needs to tell "the member never got that far" from "the member got further"
     */
    private String journalsAndCommittedOffsets(Path dataDirectory) {
        StringBuilder state = new StringBuilder("committed offsets in the broker's group file: ")
                .append(committedOffsetsInTheBrokersGroupFile(dataDirectory)).append('\n');
        try (Stream<Path> files = Files.list(workingDirectory)) {
            files.filter(file -> file.getFileName().toString().endsWith(".journal")).sorted().forEach(file ->
                    state.append(file.getFileName()).append(":\n").append(String.join("\n", linesOf(file)))
                            .append('\n'));
        } catch (IOException e) {
            state.append("cannot list ").append(workingDirectory).append(": ").append(e);
        }
        return state.toString();
    }

    private static String outputOf(JavaProcess process) {
        return process.name() + " did not exit " + SUCCESS_EXIT_CODE + ". Its output was:\n" + process.output();
    }

    /**
     * One record as a process wrote it down: the producer about a record it sent, or a member about a
     * record it processed. Two of these are the same record when all four fields match, which is what
     * lets the journals be counted against what was produced.
     *
     * @param partition the partition it belongs to
     * @param offset    the offset it was appended at
     * @param key       its key
     * @param checksum  the CRC32C of its payload, so the bytes are compared without being quoted
     */
    private record ProcessedRecord(int partition, long offset, String key, String checksum) {

        String journalLine() {
            return "processed partition=" + partition + " offset=" + offset + " key=" + key + " crc=" + checksum;
        }

        @Override
        public String toString() {
            return "partition=" + partition + " offset=" + offset + " key=" + key;
        }
    }
}
