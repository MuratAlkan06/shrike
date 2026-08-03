package io.shrike.clients;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.shrike.core.log.ByteChannels;
import io.shrike.core.log.StoredRecord;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * One member of a consumer group, in a process of its own, keeping a journal of what it has processed.
 *
 * <p>It is configured the way this build says a member is configured — a group id, its own index, and
 * how many members the group has — and it reads exactly the partitions {@link GroupAssignment} gives
 * it. Nothing about membership is asked of the broker or stored there.
 *
 * <p><strong>The journal is what makes the flagship test provable.</strong> Every record this process
 * is handed becomes one line, and that line is forced to the device <em>before</em> the record counts
 * as processed:
 *
 * <pre>
 * processed partition=2 offset=5 key=key-5 crc=6f4b8c1a
 * </pre>
 *
 * <p>So a member killed by a signal it cannot catch leaves behind, on disk, exactly the list of
 * records it had done the work for. Records are handed over {@code recordsPerCommit} at a time through
 * {@link ShrikeConsumer#processThenCommit}, which commits only after this process's handler has
 * returned — so between one commit and the next there are journal lines the broker has not been told
 * about, which is the window at-least-once delivery is about.
 *
 * <p><strong>The pause is how the kill point is decided rather than timed.</strong> Told to pause at a
 * partition and an offset, this process journals that record and then waits forever, holding a batch
 * it has not committed. The test tails the journal for that line and kills the process, so nothing in
 * the test depends on how fast anything ran.
 *
 * <p>Told to pause nowhere, it reads its partitions to their high-water marks, committing as it goes,
 * and exits 0. A start offset per partition may be given: this build has no api that reads a committed
 * offset back, so a replacement member is handed the offsets the broker stored for the member it
 * replaces.
 *
 * <p>The exit code is the verdict: 0 when every owned partition was read to its high-water mark, 1
 * when anything failed. A process that was told to pause never exits on its own.
 */
public final class GroupMemberProcessMain {

    private static final System.Logger LOGGER = System.getLogger(GroupMemberProcessMain.class.getName());

    private static final int USAGE_EXIT_CODE = 2;
    private static final int FAILED_EXIT_CODE = 1;

    /** Room for far more records than this flow sends, so a fetch is never cut short by its cap. */
    private static final int MAX_BYTES = 64 * 1024;

    /** Long enough that a partition with records answers at once, bounded so a quiet one still ends. */
    private static final int MAX_WAIT_MS = 1_000;

    /** One byte is enough to answer: this member wants whatever is there. */
    private static final int MIN_BYTES = 1;

    private static final String PARTITION_OFFSET_SEPARATOR = ":";

    /** What is passed for "pause nowhere", which is what a member that is meant to finish is given. */
    static final String NO_PAUSE = "none";

    private GroupMemberProcessMain() {
    }

    /**
     * @param args the broker's port, the topic, the group id, this member's index, how many members the
     *             group has, the topic's partition count, how many records to process before each
     *             commit, the journal to write, where to pause as {@code partition:offset} or
     *             {@value #NO_PAUSE}, and then one {@code partition:startOffset} pair per partition
     *             that starts anywhere other than 0
     */
    public static void main(String[] args) {
        if (args.length < 9) {
            System.err.println("usage: " + GroupMemberProcessMain.class.getName()
                    + " <port> <topic> <groupId> <memberIndex> <memberCount> <partitionCount> <recordsPerCommit>"
                    + " <journalFile> <pausePartition:pauseOffset|" + NO_PAUSE + "> <partition:startOffset>...");
            System.exit(USAGE_EXIT_CODE);
            return;
        }

        try {
            GroupAssignment assignment = new GroupAssignment(args[2], Integer.parseInt(args[3]),
                    Integer.parseInt(args[4]));
            consume(Integer.parseInt(args[0]), args[1], assignment, Integer.parseInt(args[5]),
                    Integer.parseInt(args[6]), Path.of(args[7]), PauseAt.parse(args[8]),
                    startOffsets(List.of(args).subList(9, args.length)));
        } catch (RuntimeException e) {
            // A process says it failed with its exit code, so every failure has to end up here; the
            // logger writes the stack trace to standard error, which is merged into this process's
            // output file.
            LOGGER.log(System.Logger.Level.ERROR, "the group member process failed", e);
            System.exit(FAILED_EXIT_CODE);
        }
    }

    private static void consume(int port, String topic, GroupAssignment assignment, int partitionCount,
                                int recordsPerCommit, Path journalFile, PauseAt pauseAt,
                                Map<Integer, Long> startOffsets) {
        String host = InetAddress.getLoopbackAddress().getHostAddress();
        Map<Integer, Long> nextOffsets = new LinkedHashMap<>();
        for (int partition : assignment.partitionsOf(partitionCount)) {
            nextOffsets.put(partition, startOffsets.getOrDefault(partition, 0L));
            System.out.println("owns partition=" + partition + " from=" + nextOffsets.get(partition));
        }

        try (FileChannel journal = FileChannel.open(journalFile, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND);
             ShrikeConsumer consumer = ShrikeConsumer.open(ClientConfig.defaults(host, port))) {
            RecordHandler handler = (partition, records) -> {
                for (StoredRecord record : records) {
                    write(journal, partition, record);
                    if (pauseAt.isAt(partition, record.offset())) {
                        pause(partition, record.offset());
                    }
                }
            };
            readEveryOwnedPartitionToItsHighWaterMark(consumer, topic, assignment.groupId(), recordsPerCommit,
                    nextOffsets, handler);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write the journal " + journalFile, e);
        }
        System.out.println("caught up");
    }

    private static void readEveryOwnedPartitionToItsHighWaterMark(ShrikeConsumer consumer, String topic,
                                                                  String groupId, int recordsPerCommit,
                                                                  Map<Integer, Long> nextOffsets,
                                                                  RecordHandler handler) {
        boolean caughtUp = false;
        while (!caughtUp) {
            caughtUp = true;
            for (Map.Entry<Integer, Long> partitionAndOffset : nextOffsets.entrySet()) {
                int partition = partitionAndOffset.getKey();
                long nextOffset = partitionAndOffset.getValue();

                FetchedRecords fetched = consumer.fetch(topic, partition, nextOffset, MAX_BYTES, MAX_WAIT_MS,
                        MIN_BYTES);
                for (List<StoredRecord> batch : batches(fetched.records(), recordsPerCommit)) {
                    nextOffset = consumer.processThenCommit(groupId, topic, partition, nextOffset, batch, handler);
                    System.out.println("committed partition=" + partition + " offset=" + nextOffset);
                }

                partitionAndOffset.setValue(nextOffset);
                if (nextOffset < fetched.highWaterMark()) {
                    caughtUp = false;
                }
            }
        }
    }

    /**
     * @param records         what one fetch answered with
     * @param recordsPerBatch how many records are handed over, and therefore committed, at a time
     * @return those records in batches of that size, the last one possibly shorter
     */
    private static List<List<StoredRecord>> batches(List<StoredRecord> records, int recordsPerBatch) {
        List<List<StoredRecord>> batches = new ArrayList<>();
        for (int first = 0; first < records.size(); first += recordsPerBatch) {
            batches.add(records.subList(first, Math.min(first + recordsPerBatch, records.size())));
        }
        return batches;
    }

    /**
     * Writes one line about a record and forces it to the device before the record counts as processed,
     * which is what lets a test read a killed process's journal and know what work was actually done.
     */
    private static void write(FileChannel journal, int partition, StoredRecord record) {
        String line = "processed partition=" + partition + " offset=" + record.offset()
                + " key=" + text(record.key()) + " crc=" + KeyedRecords.checksumOf(record.value()) + "\n";
        try {
            ByteChannels.writeFully(journal, ByteBuffer.wrap(line.getBytes(UTF_8)));
            journal.force(true);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot journal " + line.trim(), e);
        }
    }

    /**
     * Stops here, holding records that are journaled and uncommitted, and stays stopped.
     *
     * <p>Waiting on a latch nobody counts down is how that is said without a sleep — the same way
     * {@link BrokerProcessMain} waits to be destroyed. The test that started this process is tailing
     * the journal for the line just written and kills the process when it sees it, so this pause is
     * what makes the kill point a place in the data rather than a moment in time.
     */
    private static void pause(int partition, long offset) {
        System.out.println("paused partition=" + partition + " offset=" + offset);
        try {
            new CountDownLatch(1).await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while paused at partition=" + partition + " offset="
                    + offset, e);
        }
    }

    private static String text(byte[] bytes) {
        return bytes == null ? "" : new String(bytes, UTF_8);
    }

    private static Map<Integer, Long> startOffsets(List<String> arguments) {
        Map<Integer, Long> startOffsets = new LinkedHashMap<>();
        for (String argument : arguments) {
            String[] fields = split(argument, "a start offset");
            startOffsets.put(Integer.parseInt(fields[0]), Long.parseLong(fields[1]));
        }
        return startOffsets;
    }

    private static String[] split(String argument, String what) {
        String[] fields = argument.split(PARTITION_OFFSET_SEPARATOR, 2);
        if (fields.length != 2) {
            throw new IllegalArgumentException(what + " is <partition>" + PARTITION_OFFSET_SEPARATOR + "<offset>, but"
                    + " was " + argument);
        }
        return fields;
    }

    /**
     * Where this member stops and waits to be killed, or {@link #NEVER} for a member that is meant to
     * finish.
     */
    private record PauseAt(int partition, long offset) {

        private static final PauseAt NEVER = new PauseAt(-1, -1L);

        static PauseAt parse(String argument) {
            if (NO_PAUSE.equals(argument)) {
                return NEVER;
            }
            String[] fields = split(argument, "a pause point");
            return new PauseAt(Integer.parseInt(fields[0]), Long.parseLong(fields[1]));
        }

        boolean isAt(int partition, long offset) {
            return this.partition == partition && this.offset == offset;
        }
    }
}
