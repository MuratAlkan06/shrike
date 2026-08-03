package io.shrike.core.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.shrike.core.protocol.CreateTopicRequest;
import io.shrike.core.protocol.ErrorCode;
import io.shrike.core.protocol.ResponseDecoding;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The connection cap: a hard bound, enforced by closing rather than by queueing.
 *
 * <p>The cap here is small so that the test can reach it with a handful of sockets. What it proves is
 * the shape of the rule, not the number: the connections up to the cap are served, the one after it is
 * accepted and immediately closed, and the acceptor is never blocked by either.
 */
class BrokerConnectionCapTest {

    private static final int CONNECTION_CAP = 4;
    private static final String TOPIC = "orders";
    private static final int ONLY_PARTITION_COUNT = 1;

    @TempDir
    Path dataDirectory;

    private ShrikeBroker broker;
    private ExecutorService readerThread;

    @BeforeEach
    void startBroker() {
        broker = ShrikeBroker.start(BrokerHarness.config(dataDirectory, CONNECTION_CAP), BrokerHarness.SYSTEM_CLOCK);
        readerThread = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void stopBroker() {
        readerThread.shutdownNow();
        broker.close();
    }

    @Test
    void closesTheConnectionPastTheCapAndKeepsServingTheOnesBeforeIt() throws Exception {
        List<WireClient> served = new ArrayList<>();
        try {
            for (int connection = 0; connection < CONNECTION_CAP; connection++) {
                WireClient client = WireClient.connectTo(broker);
                served.add(client);
                // Answered, so this connection is certainly being served and certainly counted.
                assertInstanceOf(ResponseDecoding.Answered.class,
                        client.call(connection, new CreateTopicRequest(TOPIC + connection, ONLY_PARTITION_COUNT)),
                        "the connections up to the cap are served");
            }

            try (WireClient overTheCap = WireClient.connectTo(broker)) {
                Future<Boolean> closed = readerThread.submit(overTheCap::isClosedWithNoReply);

                assertTrue(Await.value(closed, "the broker to close the connection past its cap"),
                        "at the cap a connection is accepted and closed, never queued");
            }

            for (int connection = 0; connection < CONNECTION_CAP; connection++) {
                ResponseDecoding stillTalking = served.get(connection)
                        .call(100 + connection, new CreateTopicRequest(TOPIC + connection, ONLY_PARTITION_COUNT));

                assertEquals(ErrorCode.TOPIC_ALREADY_EXISTS,
                        assertInstanceOf(ResponseDecoding.Failed.class, stillTalking).errorCode(),
                        "a connection that was already being served is untouched by one that was refused");
            }
        } finally {
            for (WireClient client : served) {
                closeQuietly(client);
            }
        }
    }

    private static void closeQuietly(WireClient client) {
        try {
            client.close();
        } catch (IOException e) {
            // The test is finishing; a socket that will not close cleanly proves nothing about it.
        }
    }
}
