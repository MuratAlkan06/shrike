package io.shrike.clients;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.net.InetAddress;

/**
 * A producer in a process of its own that lets a {@link PartitionRouter} decide where each record
 * goes, rather than naming the partition itself.
 *
 * <p>Every line of output is one record, in {@code field=value} pairs, and it names the partition the
 * router picked, the offset the broker answered with, and the checksum of the payload:
 *
 * <pre>
 * produced partition=2 offset=0 key=key-0 crc=6f4b8c1a
 * </pre>
 *
 * <p>Those lines are the test's record of what was sent, and a consumer process writes lines of the
 * same shape about what it processed, so the two can be compared record by record.
 *
 * <p>{@link ProducerProcessMain} is the other producer in these tests and it names its partitions by
 * hand; it is left alone because the flow it belongs to freezes which record lands where.
 *
 * <p>The exit code is the verdict: 0 when every record was acknowledged, 1 when anything failed.
 */
public final class RoutedProducerProcessMain {

    private static final System.Logger LOGGER = System.getLogger(RoutedProducerProcessMain.class.getName());

    private static final int USAGE_EXIT_CODE = 2;
    private static final int FAILED_EXIT_CODE = 1;

    private RoutedProducerProcessMain() {
    }

    /**
     * @param args the broker's port, the topic, its partition count, and how many records to send
     */
    public static void main(String[] args) {
        if (args.length != 4) {
            System.err.println("usage: " + RoutedProducerProcessMain.class.getName()
                    + " <port> <topic> <partitionCount> <recordCount>");
            System.exit(USAGE_EXIT_CODE);
            return;
        }

        try {
            send(Integer.parseInt(args[0]), args[1], Integer.parseInt(args[2]), Integer.parseInt(args[3]));
        } catch (RuntimeException e) {
            // A process says it failed with its exit code, so every failure has to end up here; the
            // logger writes the stack trace to standard error, which is merged into this process's
            // output file.
            LOGGER.log(System.Logger.Level.ERROR, "the producer process failed", e);
            System.exit(FAILED_EXIT_CODE);
        }
    }

    private static void send(int port, String topic, int partitionCount, int recordCount) {
        String host = InetAddress.getLoopbackAddress().getHostAddress();
        PartitionRouter partitions = new PartitionRouter(partitionCount);

        try (ShrikeProducer producer = ShrikeProducer.open(ClientConfig.defaults(host, port))) {
            for (int index = 0; index < recordCount; index++) {
                String key = KeyedRecords.key(index);
                byte[] value = KeyedRecords.value(index).getBytes(UTF_8);
                SentRecord sent = producer.send(topic, partitions, key.getBytes(UTF_8), value);

                System.out.println("produced partition=" + sent.partition() + " offset=" + sent.offset()
                        + " key=" + key + " crc=" + KeyedRecords.checksumOf(value));
            }
        }
    }
}
