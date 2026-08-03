package io.shrike.clients;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.fail;

import io.shrike.core.net.ReadyFile;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Supplier;

/**
 * Bounded waits for tests that have to wait on another operating-system process, so that a test which
 * is going to fail fails instead of hanging.
 *
 * <p>Everything a test in this module waits for is inside a different JVM, where there is no lock to
 * hold and no condition to signal: the only thing that crosses the boundary is a file appearing and a
 * process ending. So this is the one place in the repository that polls, and it polls the way the
 * rest of the code waits — an absolute deadline computed once, a short interval between looks, and a
 * failure that says what never happened. Nothing here asserts how long something took.
 *
 * <p>This module's test classes are attached as a test-jar, so waiting for a broker's ready file is
 * public — it is the one wait another module has to do — and the rest stays inside this package.
 */
public final class Await {

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
    public static int brokerPort(Path readyFilePath, JavaProcess broker) {
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
     * Waits for a line to appear in a file another process is appending to.
     *
     * <p>It is how a test follows another process by what that process has already forced to the
     * device rather than by how long it has been running: the line is there, so the work behind it is
     * done, whatever the machine's load was. Nothing here asserts how long that took.
     *
     * @param file        the file that process is appending to, which need not exist yet
     * @param line        the line to wait for, matched whole
     * @param writer      the process writing it, so a process that died is a failure now rather than at
     *                    the deadline
     * @param diagnostics what to print when the wait gives up — the state a reader would need to tell
     *                    what happened instead
     */
    static void lineInFile(Path file, String line, JavaProcess writer, Supplier<String> diagnostics) {
        long deadlineNanos = System.nanoTime() + SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadlineNanos) {
            if (!writer.isAlive()) {
                fail(writer.name() + " ended with exit code " + writer.exitCode() + " before it wrote \"" + line
                        + "\" to " + file + ". Its output was:\n" + writer.output() + "\n" + diagnostics.get());
                return;
            }
            if (linesOf(file).contains(line)) {
                return;
            }
            pause();
        }
        fail("\"" + line + "\" did not appear in " + file + " within " + TIMEOUT_SECONDS + "s. The output of "
                + writer.name() + " was:\n" + writer.output() + "\n" + diagnostics.get());
    }

    /**
     * @param file a file another process is appending to
     * @return its lines, or none when it is not there yet. A file being appended to may end mid-line,
     *         which costs nothing here: a partial line matches no whole line, so the next look sees it
     */
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
