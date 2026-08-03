package io.shrike.clients;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.net.InetAddress;

/**
 * A producer in a process of its own: send a run of keyed records across every partition of a topic
 * through {@link ShrikeProducer}, and print the offset the broker gave each one.
 *
 * <p>Every line of output is one record, in {@code field=value} pairs, so the test that started this
 * process reads results rather than prose:
 *
 * <pre>
 * produced partition=0 key=key-0 offset=0
 * </pre>
 *
 * <p>The exit code is the verdict: 0 when every record was acknowledged, 1 when anything failed.
 */
public final class ProducerProcessMain {

    private static final System.Logger LOGGER = System.getLogger(ProducerProcessMain.class.getName());

    private static final int USAGE_EXIT_CODE = 2;
    private static final int FAILED_EXIT_CODE = 1;

    private ProducerProcessMain() {
    }

    /**
     * @param args the broker's port, the topic, its partition count, the index to start at, and how
     *             many records to send
     */
    public static void main(String[] args) {
        if (args.length != 5) {
            System.err.println("usage: " + ProducerProcessMain.class.getName()
                    + " <port> <topic> <partitionCount> <firstIndex> <recordCount>");
            System.exit(USAGE_EXIT_CODE);
            return;
        }

        try {
            send(Integer.parseInt(args[0]), args[1], Integer.parseInt(args[2]), Integer.parseInt(args[3]),
                    Integer.parseInt(args[4]));
        } catch (RuntimeException e) {
            // A process says it failed with its exit code, so every failure has to end up here; the
            // logger writes the stack trace to standard error, which is merged into this process's
            // output file.
            LOGGER.log(System.Logger.Level.ERROR, "the producer process failed", e);
            System.exit(FAILED_EXIT_CODE);
        }
    }

    private static void send(int port, String topic, int partitionCount, int firstIndex, int recordCount) {
        String host = InetAddress.getLoopbackAddress().getHostAddress();
        try (ShrikeProducer producer = ShrikeProducer.open(ClientConfig.defaults(host, port))) {
            for (int index = firstIndex; index < firstIndex + recordCount; index++) {
                int partition = KeyedRecords.partitionOf(index, partitionCount);
                String key = KeyedRecords.key(index);
                long offset = producer.send(topic, partition, key.getBytes(UTF_8),
                        KeyedRecords.value(index).getBytes(UTF_8));

                System.out.println("produced partition=" + partition + " key=" + key + " offset=" + offset);
            }
        }
    }
}
