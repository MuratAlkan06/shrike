package io.shrike.clients;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.shrike.core.log.StoredRecord;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;

/**
 * A consumer in a process of its own: read one partition from the offset it was told to start at
 * until it reaches the high-water mark, print what it read, and commit the offset it would read next.
 *
 * <p>Every line of output is one fact, in {@code field=value} pairs, so the test that started this
 * process reads results rather than prose:
 *
 * <pre>
 * record partition=0 offset=0 key=key-0 value=value-0
 * committed partition=0 offset=4
 * </pre>
 *
 * <p>A start offset is an argument rather than something this process asks the broker for, because
 * this build has no api that reads a committed offset back — the test reads the offsets out of the
 * broker's own group file and hands them to the process that resumes.
 *
 * <p>The exit code is the verdict: 0 when every partition was read to its high-water mark and
 * committed, 1 when anything failed.
 */
public final class ConsumerProcessMain {

    private static final System.Logger LOGGER = System.getLogger(ConsumerProcessMain.class.getName());

    private static final int USAGE_EXIT_CODE = 2;
    private static final int FAILED_EXIT_CODE = 1;

    /** Room for far more records than this flow sends, so a fetch is never cut short by its cap. */
    private static final int MAX_BYTES = 64 * 1024;

    /** Long enough that a partition with records answers at once, bounded so a quiet one still ends. */
    private static final int MAX_WAIT_MS = 1_000;

    /** One byte is enough to answer: this consumer wants whatever is there. */
    private static final int MIN_BYTES = 1;

    private static final String PARTITION_OFFSET_SEPARATOR = ":";

    private ConsumerProcessMain() {
    }

    /**
     * @param args the broker's port, the topic, the group id, and one {@code partition:startOffset}
     *             pair per partition to read
     */
    public static void main(String[] args) {
        if (args.length < 4) {
            System.err.println("usage: " + ConsumerProcessMain.class.getName()
                    + " <port> <topic> <groupId> <partition:startOffset>...");
            System.exit(USAGE_EXIT_CODE);
            return;
        }

        try {
            consume(Integer.parseInt(args[0]), args[1], args[2], Arrays.asList(args).subList(3, args.length));
        } catch (RuntimeException e) {
            // A process says it failed with its exit code, so every failure has to end up here; the
            // logger writes the stack trace to standard error, which is merged into this process's
            // output file.
            LOGGER.log(System.Logger.Level.ERROR, "the consumer process failed", e);
            System.exit(FAILED_EXIT_CODE);
        }
    }

    private static void consume(int port, String topic, String groupId, List<String> partitionsToRead) {
        String host = InetAddress.getLoopbackAddress().getHostAddress();
        try (ShrikeConsumer consumer = ShrikeConsumer.open(ClientConfig.defaults(host, port))) {
            for (String partitionAndOffset : partitionsToRead) {
                String[] fields = partitionAndOffset.split(PARTITION_OFFSET_SEPARATOR, 2);
                if (fields.length != 2) {
                    throw new IllegalArgumentException("a partition to read is <partition>"
                            + PARTITION_OFFSET_SEPARATOR + "<startOffset>, but was " + partitionAndOffset);
                }

                int partition = Integer.parseInt(fields[0]);
                long nextOffset = readToTheHighWaterMark(consumer, topic, partition, Long.parseLong(fields[1]));

                consumer.commitOffset(groupId, topic, partition, nextOffset);
                System.out.println("committed partition=" + partition + " offset=" + nextOffset);
            }
        }
    }

    /**
     * @return the offset this consumer would read next, which is the offset it commits
     */
    private static long readToTheHighWaterMark(ShrikeConsumer consumer, String topic, int partition,
                                               long startOffset) {
        long fetchOffset = startOffset;
        FetchedRecords fetched = consumer.fetch(topic, partition, fetchOffset, MAX_BYTES, MAX_WAIT_MS, MIN_BYTES);
        print(partition, fetched.records());
        fetchOffset = fetched.nextOffset(fetchOffset);

        while (fetchOffset < fetched.highWaterMark()) {
            fetched = consumer.fetch(topic, partition, fetchOffset, MAX_BYTES, MAX_WAIT_MS, MIN_BYTES);
            if (fetched.records().isEmpty()) {
                throw new IllegalStateException("topic=" + topic + " partition=" + partition + " answered a fetch"
                        + " from offset=" + fetchOffset + " with no records, though its highWaterMark is "
                        + fetched.highWaterMark());
            }
            print(partition, fetched.records());
            fetchOffset = fetched.nextOffset(fetchOffset);
        }
        return fetchOffset;
    }

    private static void print(int partition, List<StoredRecord> records) {
        for (StoredRecord record : records) {
            System.out.println("record partition=" + partition + " offset=" + record.offset()
                    + " key=" + text(record.key()) + " value=" + text(record.value()));
        }
    }

    private static String text(byte[] bytes) {
        return bytes == null ? "" : new String(bytes, UTF_8);
    }
}
