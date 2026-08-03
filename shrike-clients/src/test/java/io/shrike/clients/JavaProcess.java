package io.shrike.clients;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * One child JVM: how a test starts a broker, a producer, or a consumer as a separate operating-system
 * process, and how it makes sure none of them outlives the test.
 *
 * <p>It runs the same {@code java} the test itself runs — {@code java.home} is the toolchain JDK the
 * fork was started with — with the classpath the fork was given, so a child sees exactly the classes
 * the test does.
 *
 * <p>Output is redirected to a file, with the error stream merged into it. A pipe would need a thread
 * to drain it or the child would block once the pipe filled, and a file is a thing a failure message
 * can quote whole.
 */
final class JavaProcess {

    /** Long enough that reaching it means the process is stuck rather than slow. */
    private static final long EXIT_TIMEOUT_SECONDS = 60L;

    /** How long a killed process gets to actually die before the test says it did not. */
    private static final long DESTROY_TIMEOUT_SECONDS = 15L;

    private static final String JAVA_COMMAND = Path.of(System.getProperty("java.home"), "bin", "java").toString();

    private final String name;
    private final Process process;
    private final Path outputFile;

    private JavaProcess(String name, Process process, Path outputFile) {
        this.name = name;
        this.process = process;
        this.outputFile = outputFile;
    }

    /**
     * Starts a child JVM running one main class.
     *
     * @param name       what to call it in a failure message
     * @param mainClass  the class whose {@code main} it runs
     * @param outputFile where its merged output goes
     * @param arguments  the arguments that main takes
     * @return the started process
     * @throws IOException if the process cannot be started
     */
    static JavaProcess start(String name, Class<?> mainClass, Path outputFile, List<String> arguments)
            throws IOException {
        List<String> command = new ArrayList<>(List.of(JAVA_COMMAND, "-cp", System.getProperty("java.class.path"),
                mainClass.getName()));
        command.addAll(arguments);

        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(outputFile.toFile())
                .start();
        return new JavaProcess(name, process, outputFile);
    }

    /**
     * @return whether this process is still running
     */
    boolean isAlive() {
        return process.isAlive();
    }

    /**
     * @return the exit code of a process that has ended, or -1 while it is still running
     */
    int exitCode() {
        return process.isAlive() ? -1 : process.exitValue();
    }

    /**
     * Waits, under a bound, for this process to end.
     *
     * @return its exit code
     */
    int awaitExit() {
        try {
            if (!process.waitFor(EXIT_TIMEOUT_SECONDS, SECONDS)) {
                destroyTree();
                return fail(name + " did not end within " + EXIT_TIMEOUT_SECONDS + "s. Its output was:\n" + output());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fail("interrupted while waiting for " + name + " to end");
        }
        return process.exitValue();
    }

    /**
     * @return everything the process has written so far, standard error included
     */
    String output() {
        if (!Files.exists(outputFile)) {
            return "";
        }
        try {
            return Files.readString(outputFile);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the output of " + name + " from " + outputFile, e);
        }
    }

    /**
     * @return the lines the process has written, standard error included
     */
    List<String> outputLines() {
        return output().lines().toList();
    }

    /**
     * @return what this process is called in a failure message
     */
    String name() {
        return name;
    }

    /**
     * Kills this process and everything it started, and waits under a bound for it to be gone.
     *
     * <p>Descendants first: a child that outlives its parent is a child nothing has a handle on any
     * more. It is safe to call on a process that has already ended, which is what lets a test call it
     * on every process it started whatever happened to them.
     */
    void destroyTree() {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            if (!process.waitFor(DESTROY_TIMEOUT_SECONDS, SECONDS)) {
                fail(name + " was still alive " + DESTROY_TIMEOUT_SECONDS + "s after it was destroyed");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("interrupted while destroying " + name);
        }
    }
}
