package io.shrike.core.net;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.shrike.core.log.ShrikeIOException;
import io.shrike.core.protocol.CreateTopicRequest;
import io.shrike.core.protocol.ResponseDecoding;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

    /** Long enough that reaching it means the probe is stuck rather than slow. */
    private static final long PROBE_TIMEOUT_SECONDS = 60L;

    @TempDir
    Path dataDirectory;

    /** Where a probe's output goes, kept out of the data directory a broker is running over. */
    @TempDir
    Path probeOutputDirectory;

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

    /**
     * The refusal above, asked about from outside this JVM: after a second broker in this process has
     * been refused, is the running broker's claim on the directory still the operating system's to
     * enforce?
     *
     * <p>It has to be asked from another process, because a {@code FileLock} is held per process and a
     * second question inside this JVM would be answered by the JDK's own table rather than by the
     * kernel. And it has to be asked at all because the answer used to be no. A refusal that opened a
     * channel on the lock file and closed it again — which is what a refusal must do rather than leak a
     * descriptor — released the <em>running</em> broker's lock, since POSIX drops every lock a process
     * holds on a file when that process closes any descriptor on it. What that left is what the three
     * assertions here rule out: a broker serving over a directory it no longer holds, and an outside
     * process free to start a second broker over it.
     *
     * <p>The last assertion is what keeps the other two honest: a probe that answered {@code HELD} to
     * everything would prove nothing, so the same question is asked once more after the broker has
     * stopped, where the answer must be that the directory is free.
     */
    @Test
    void keepsTheRunningBrokersLockOnTheDataDirectoryAfterRefusingASecondBrokerInThisJvm() throws Exception {
        try (ShrikeBroker running = BrokerHarness.start(dataDirectory)) {

            assertEquals(DataDirectoryLockProbe.HELD, whatAnotherProcessFindsOfTheLock("before"),
                    "the control: while a broker is running, its data directory is nobody else's to lock");

            assertThrows(ShrikeIOException.class, () -> BrokerHarness.start(dataDirectory));

            assertEquals(DataDirectoryLockProbe.HELD, whatAnotherProcessFindsOfTheLock("after"),
                    "and refusing that second start left the running broker's lock exactly where it was,"
                            + " so nothing outside this JVM can start a broker over the directory it is serving");
        }

        assertEquals(DataDirectoryLockProbe.FREE, whatAnotherProcessFindsOfTheLock("stopped"),
                "the probe answers the other way too, so the two answers above are the operating system's"
                        + " and not this test's");
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

    /**
     * Runs {@link DataDirectoryLockProbe} as an operating-system process of its own — the same
     * {@code java} and the same classpath this test runs on — and returns the one word it printed.
     *
     * <p>Its output goes to a file rather than a pipe, for the reason every process test here uses one:
     * a pipe nobody drains blocks the child once it fills, and a file is something a failure message can
     * quote whole. The wait is bounded and the process is destroyed however this ends, including when an
     * assertion above has already failed.
     *
     * @param when what to call this probe's output file, so a failed run says which of the three it was
     * @return {@link DataDirectoryLockProbe#HELD} or {@link DataDirectoryLockProbe#FREE}
     */
    private String whatAnotherProcessFindsOfTheLock(String when) throws Exception {
        Path lockFile = dataDirectory.resolve(DataDirectoryLock.FILE_NAME);
        Path outputFile = probeOutputDirectory.resolve("probe-" + when + ".out");
        Process probe = new ProcessBuilder(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"),
                DataDirectoryLockProbe.class.getName(),
                lockFile.toString()))
                .redirectErrorStream(true)
                .redirectOutput(outputFile.toFile())
                .start();
        try {
            assertTrue(probe.waitFor(PROBE_TIMEOUT_SECONDS, SECONDS),
                    "the probe asked about the lock and ended within " + PROBE_TIMEOUT_SECONDS + " seconds");
            String said = Files.readString(outputFile).strip();
            assertEquals(0, probe.exitValue(), "the probe asked the question rather than failing: " + said);
            return said;
        } finally {
            probe.descendants().forEach(ProcessHandle::destroyForcibly);
            probe.destroyForcibly();
        }
    }
}
