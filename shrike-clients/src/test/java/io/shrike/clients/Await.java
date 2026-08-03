package io.shrike.clients;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.fail;

import io.shrike.core.net.ReadyFile;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

/**
 * Bounded waits for tests that have to wait on another operating-system process, so that a test which
 * is going to fail fails instead of hanging.
 *
 * <p>Everything a test in this module waits for is inside a different JVM, where there is no lock to
 * hold and no condition to signal: the only thing that crosses the boundary is a file appearing and a
 * process ending. So this is the one place in the repository that polls, and it polls the way the
 * rest of the code waits — an absolute deadline computed once, a short interval between looks, and a
 * failure that says what never happened. Nothing here asserts how long something took.
 */
final class Await {

    /** Long enough that reaching it means something is wrong, short enough that a build still ends. */
    static final long TIMEOUT_SECONDS = 60L;

    /** Short enough that a test does not wait noticeably longer than the thing it waits for. */
    private static final long POLL_INTERVAL_MILLIS = 20L;

    private Await() {
    }

    /**
     * Waits for a broker process to write its ready file and reads the port out of it.
     *
     * @param readyFilePath where that broker was told to write it
     * @param broker        the process that should be writing it, so a broker that died is a failure
     *                      now rather than at the deadline
     * @return the port the broker bound
     */
    static int brokerPort(Path readyFilePath, JavaProcess broker) {
        long deadlineNanos = System.nanoTime() + SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadlineNanos) {
            if (!broker.isAlive()) {
                return fail("the broker process ended with exit code " + broker.exitCode() + " before it wrote "
                        + readyFilePath + ". Its output was:\n" + broker.output());
            }
            Optional<Properties> ready = readReadyFile(readyFilePath);
            if (ready.isPresent()) {
                return Integer.parseInt(ready.get().getProperty(ReadyFile.PORT_KEY).trim());
            }
            pause();
        }
        return fail("the broker did not write " + readyFilePath + " within " + TIMEOUT_SECONDS + "s. Its output"
                + " was:\n" + broker.output());
    }

    /**
     * @return the ready file, once it holds both of the keys it is frozen to hold. It is written
     *         through one atomic move, so a file that is there is whole; a file that is not there yet
     *         reads as empty rather than as a failure
     */
    private static Optional<Properties> readReadyFile(Path readyFilePath) {
        if (!Files.exists(readyFilePath)) {
            return Optional.empty();
        }

        Properties ready = new Properties();
        try (InputStream contents = Files.newInputStream(readyFilePath)) {
            ready.load(contents);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the ready file " + readyFilePath, e);
        }
        return ready.getProperty(ReadyFile.PORT_KEY) == null || ready.getProperty(ReadyFile.PID_KEY) == null
                ? Optional.empty()
                : Optional.of(ready);
    }

    private static void pause() {
        try {
            Thread.sleep(POLL_INTERVAL_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("interrupted while waiting for another process");
        }
    }
}
