package io.shrike.core.net;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.shrike.core.log.ProducedRecord;
import io.shrike.core.protocol.CreateTopicRequest;
import io.shrike.core.protocol.FetchRequest;
import io.shrike.core.protocol.ProduceRequest;
import io.shrike.core.protocol.ResponseDecoding;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Starting and stopping: what a broker writes when it is ready, and what it does to the connections
 * and the long polls it is holding when it stops.
 */
class BrokerLifecycleTest {

    private static final String TOPIC = "orders";
    private static final int ONLY_PARTITION_COUNT = 1;
    private static final int PARTITION = 0;
    private static final int PLENTY_OF_BYTES = 64 * 1024;
    private static final int LONG_WAIT_MS = 60_000;

    @TempDir
    Path dataDirectory;

    @Test
    void writesAReadyFileHoldingOnlyTheBoundPortAndThePid() throws Exception {
        Path readyFilePath = dataDirectory.resolve(BrokerHarness.READY_FILE_NAME);

        try (ShrikeBroker broker = BrokerHarness.start(dataDirectory)) {
            Properties ready = new Properties();
            try (InputStream contents = Files.newInputStream(readyFilePath)) {
                ready.load(contents);
            }

            assertEquals(Set.of(ReadyFile.PORT_KEY, ReadyFile.PID_KEY), ready.keySet(),
                    "the ready file holds exactly these two keys, and a harness parses it as properties");
            assertEquals(broker.port(), Integer.parseInt(ready.getProperty(ReadyFile.PORT_KEY)),
                    "the port is the one that was bound, which is the only way to learn an ephemeral one");
            assertEquals(ProcessHandle.current().pid(), Long.parseLong(ready.getProperty(ReadyFile.PID_KEY)));
            assertTrue(broker.port() > 0, "port 0 asks the operating system to choose one");
        }
    }

    @Test
    void leavesTheReadyFileWhereItIsWhenItStops() throws Exception {
        Path readyFilePath = dataDirectory.resolve(BrokerHarness.READY_FILE_NAME);

        try (ShrikeBroker broker = BrokerHarness.start(dataDirectory)) {
            assertTrue(Files.isRegularFile(readyFilePath));
        }

        assertTrue(Files.isRegularFile(readyFilePath), "stopping deletes nothing, the ready file included");
    }

    @Test
    void endsTheConnectionsItWasServingWhenItStops() throws Exception {
        ShrikeBroker broker = BrokerHarness.start(dataDirectory);
        try (WireClient client = WireClient.connectTo(broker)) {
            client.call(1, new CreateTopicRequest(TOPIC, ONLY_PARTITION_COUNT));

            broker.close();

            assertThrows(IOException.class, () -> client.call(2, new CreateTopicRequest("later", 1)),
                    "a stopped broker has closed the sockets it was serving");
        }
    }

    /**
     * Stopping wakes the fetches that are waiting on a partition before it closes their sockets,
     * because closing a socket does not wake a thread that is sleeping on a condition. The proof is the
     * connection thread itself: if it were left to wait out its minute, stopping would give up joining
     * it and the thread would still be alive here.
     */
    @Test
    void wakesAFetchThatIsStillLongPollingSoItsConnectionThreadEnds() throws Exception {
        ExecutorService fetchThread = Executors.newSingleThreadExecutor();
        ShrikeBroker broker = BrokerHarness.start(dataDirectory);
        try (WireClient setup = WireClient.connectTo(broker)) {
            setup.call(1, new CreateTopicRequest(TOPIC, ONLY_PARTITION_COUNT));
            CountDownLatch waiterRegistered = new CountDownLatch(1);
            broker.partition(TOPIC, PARTITION).orElseThrow().onWaiterRegistered(waiterRegistered::countDown);
            fetchThread.submit(() -> {
                try (WireClient client = WireClient.connectTo(broker)) {
                    return client.call(2, new FetchRequest(TOPIC, PARTITION, 0L, PLENTY_OF_BYTES, LONG_WAIT_MS, 1));
                }
            });
            Await.latch(waiterRegistered, "the fetch to register as a waiter");
            List<Thread> serving = liveConnectionThreads();
            assertTrue(serving.size() >= 1, "the waiting fetch is being served by a thread of its own");

            broker.close();

            assertTrue(serving.stream().noneMatch(Thread::isAlive),
                    "every connection thread ended, so none of them was left waiting out its maxWaitMs");
        } finally {
            fetchThread.shutdownNow();
            broker.close();
        }
    }

    @Test
    void forcesEveryPartitionLogWhenItStops() throws Exception {
        try (ShrikeBroker broker = BrokerHarness.start(dataDirectory)) {
            try (WireClient client = WireClient.connectTo(broker)) {
                client.call(1, new CreateTopicRequest(TOPIC, ONLY_PARTITION_COUNT));
                assertInstanceOf(ResponseDecoding.Answered.class, client.call(2, new ProduceRequest(TOPIC, PARTITION,
                        List.of(new ProducedRecord(null, "payload".getBytes(UTF_8))))));
            }
        }

        Path segment = dataDirectory.resolve(TOPIC + "-" + PARTITION).resolve("00000000000000000000.log");
        assertTrue(Files.size(segment) > 0, "a stopped broker has closed its logs, which forces them");
    }

    private static List<Thread> liveConnectionThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.getName().startsWith(ShrikeBroker.CONNECTION_THREAD_PREFIX))
                .filter(Thread::isAlive)
                .toList();
    }
}
