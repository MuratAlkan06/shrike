package io.shrike.core.net;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.shrike.core.log.ShrikeIOException;
import io.shrike.core.protocol.CreateTopicRequest;
import io.shrike.core.protocol.ResponseDecoding;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Who owns a data directory, and what a second broker over one is told.
 *
 * <p>Everything in a data directory has one writer by design — a partition log, the topic registry, the
 * rename a group offsets file gets as the store opens — and two brokers sharing one would break all
 * three without either of them noticing. The lock is what makes that a refusal instead: a start takes
 * it before it opens anything, and a start that cannot have it says which directory is somebody else's.
 *
 * <p>The second broker here is in this JVM, which is the harder half of the refusal rather than the
 * easier one: a lock is held per JVM, so this is the path where {@code tryLock()} throws
 * {@link java.nio.channels.OverlappingFileLockException} instead of answering null, and a broker that
 * only handled the null would refuse the second process and blow up on the second instance.
 */
class BrokerDataDirectoryLockTest {

    private static final String TOPIC = "orders";
    private static final String ANOTHER_TOPIC = "shipments";
    private static final int ONLY_PARTITION_COUNT = 1;
    private static final int FIRST_CORRELATION_ID = 1;

    @TempDir
    Path dataDirectory;

    @Test
    void refusesToStartOverADataDirectoryABrokerIsAlreadyRunningOverAndLeavesThatBrokerServing() throws Exception {
        try (ShrikeBroker running = BrokerHarness.start(dataDirectory)) {

            ShrikeIOException refused = assertThrows(ShrikeIOException.class,
                    () -> BrokerHarness.start(dataDirectory));

            assertTrue(refused.getMessage().contains(dataDirectory.toString()),
                    "the refusal names the directory an operator has to choose another of: " + refused.getMessage());
            assertTrue(refused.getMessage().contains(DataDirectoryLock.FILE_NAME),
                    "and it names what holds it, so the cause is not something to guess at: " + refused.getMessage());
            try (WireClient client = WireClient.connectTo(running)) {
                assertInstanceOf(ResponseDecoding.Answered.class,
                        client.call(FIRST_CORRELATION_ID, new CreateTopicRequest(TOPIC, ONLY_PARTITION_COUNT)),
                        "the broker that holds the directory is untouched by the start that was refused it");
            }
        }
    }

    @Test
    void startsOverTheSameDataDirectoryOnceTheBrokerHoldingItHasStoppedCleanly() throws Exception {
        try (ShrikeBroker first = BrokerHarness.start(dataDirectory);
             WireClient client = WireClient.connectTo(first)) {
            client.call(FIRST_CORRELATION_ID, new CreateTopicRequest(TOPIC, ONLY_PARTITION_COUNT));
        }

        try (ShrikeBroker second = BrokerHarness.start(dataDirectory);
             WireClient client = WireClient.connectTo(second)) {
            assertTrue(Files.isRegularFile(dataDirectory.resolve(DataDirectoryLock.FILE_NAME)),
                    "stopping releases the lock and deletes nothing, the lock file included");
            assertInstanceOf(ResponseDecoding.Answered.class,
                    client.call(FIRST_CORRELATION_ID, new CreateTopicRequest(ANOTHER_TOPIC, ONLY_PARTITION_COUNT)),
                    "a broker that stopped let go of the directory, so the next one over it starts and serves");
        }
    }

    @Test
    void startsOverALockFileLeftBehindByABrokerThatIsNoLongerRunning() throws Exception {
        Path lockFile = dataDirectory.resolve(DataDirectoryLock.FILE_NAME);
        Files.write(lockFile, "left where a killed broker dropped it".getBytes(UTF_8));

        try (ShrikeBroker broker = BrokerHarness.start(dataDirectory);
             WireClient client = WireClient.connectTo(broker)) {
            assertInstanceOf(ResponseDecoding.Answered.class,
                    client.call(FIRST_CORRELATION_ID, new CreateTopicRequest(TOPIC, ONLY_PARTITION_COUNT)),
                    "a lock file nobody holds is a file and not a claim, so it costs the next start nothing"
                            + " and there is nothing to delete by hand");
        }
    }
}
