package io.shrike.core.net;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * A JVM of its own, asking the operating system whether a broker's lock file is anybody's, and
 * printing the one word it found out.
 *
 * <p>It exists because the question cannot be asked from inside the test's own JVM. A {@link FileLock}
 * is held per process: a second {@code tryLock()} in the process that already holds one is answered by
 * the JDK's own lock table before a system call happens, so it says what this JVM knows rather than
 * what the kernel knows — and what the kernel knows is exactly what a second broker on the machine
 * would find. So a separate process asks, and it does the one thing an outside process would do.
 *
 * <p>It is not named {@code *Test}, so surefire never runs it as one; it is started by
 * {@link BrokerDataDirectoryLockTest} with the {@code java} and the classpath that test itself runs on.
 */
public final class DataDirectoryLockProbe {

    /** What it prints when the lock file belongs to somebody: nothing outside could start over it. */
    public static final String HELD = "HELD";

    /** What it prints when nobody holds the lock file, so this process took it and gave it straight back. */
    public static final String FREE = "FREE";

    private DataDirectoryLockProbe() {
    }

    /**
     * @param args the one lock file to ask about
     * @throws Exception if the file cannot be opened or asked about at all, which fails the test that
     *                   started this process with a non-zero exit code and the stack on its output
     */
    public static void main(String[] args) throws Exception {
        Path lockFile = Path.of(args[0]);
        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock taken = channel.tryLock();
            if (taken == null) {
                System.out.println(HELD);
                return;
            }
            taken.release();
            System.out.println(FREE);
        }
    }
}
