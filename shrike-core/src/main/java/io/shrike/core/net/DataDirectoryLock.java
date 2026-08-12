package io.shrike.core.net;

import io.shrike.core.log.ShrikeIOException;
import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * One broker's claim on one data directory, held for as long as that broker is running.
 *
 * <p>Everything under a data directory has exactly one writer by design: a partition log is appended
 * to by one thread of one process, the topic registry is rewritten in place before the directories it
 * describes are opened, and a group offsets file written under an unfolded name is renamed onto its
 * folded one as the store opens. A second broker over the same directory breaks all three at once, and
 * it breaks them quietly — two brokers recovering the same torn tail, two registries disagreeing about
 * which topics exist, one start renaming a file the other is reading. So the first thing a start does,
 * before a single log is opened, is take an exclusive {@link FileLock} on
 * {@value #FILE_NAME} in that directory, and a start that cannot have it refuses to run.
 *
 * <p><strong>Why the lock is the file's rather than the file itself.</strong> The lock lives in the
 * operating system's own table, not in the bytes of {@value #FILE_NAME} — the file is a handle to hang
 * it on and holds nothing at all. A lock goes when the channel is closed, when the process exits, and
 * when the process is killed, so a broker that crashed leaves a lock file that the next start takes
 * straight away rather than a claim somebody has to clear by hand. That is the whole reason this is not
 * a pid file: a pid file outlives its process and needs a liveness probe to say whether it means
 * anything, and a probe on a recycled pid says the wrong thing.
 *
 * <p><strong>Two ways to be refused, one sentence for both.</strong> Another process holding the lock
 * makes {@link FileChannel#tryLock()} answer null; this same JVM already holding it — an application
 * that embeds two brokers, or a test starting a second one — makes the same call throw
 * {@link OverlappingFileLockException}, because a lock is held per JVM rather than per channel. Neither
 * is a condition an operator can act on differently, so both arrive as one {@link ShrikeIOException}
 * naming the directory and saying it is another broker's.
 *
 * <p>Nothing here deletes the lock file, on this path or any other: stopping a broker leaves the data
 * directory exactly as it found it, and an empty file that the next start reuses is cheaper than a
 * delete that races the start it was meant to help.
 */
final class DataDirectoryLock implements Closeable {

    /** The file the lock is taken on, directly under the data directory. It stays empty. */
    static final String FILE_NAME = "shrike.lock";

    private final Path lockFile;
    private final FileChannel channel;
    private final FileLock lock;

    private DataDirectoryLock(Path lockFile, FileChannel channel, FileLock lock) {
        this.lockFile = lockFile;
        this.channel = channel;
        this.lock = lock;
    }

    /**
     * Takes the exclusive lock on a data directory, which the caller holds until it closes this.
     *
     * @param dataDirectory the directory this broker is about to open, which must already exist
     * @return the lock, which the caller closes
     * @throws ShrikeIOException if a broker is already running over that directory, or if the lock file
     *                           cannot be opened or locked at all
     */
    static DataDirectoryLock take(Path dataDirectory) {
        Objects.requireNonNull(dataDirectory, "dataDirectory");

        Path lockFile = dataDirectory.resolve(FILE_NAME);
        FileChannel channel = null;
        try {
            channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                throw alreadyLocked(dataDirectory, lockFile, null);
            }
            return new DataDirectoryLock(lockFile, channel, lock);
        } catch (OverlappingFileLockException heldByThisJvm) {
            closeQuietlyAfterFailedTake(channel);
            throw alreadyLocked(dataDirectory, lockFile, heldByThisJvm);
        } catch (IOException e) {
            closeQuietlyAfterFailedTake(channel);
            throw new ShrikeIOException("cannot lock the data directory " + dataDirectory + " through " + lockFile, e);
        }
    }

    /**
     * Releases the lock and closes the channel holding it, which is what lets the next broker over this
     * directory start. Called once, by the shutdown that took it.
     *
     * @throws IOException if the release or the close fails, after which the lock is the operating
     *                     system's to clean up when this process ends
     */
    @Override
    public void close() throws IOException {
        try (FileChannel releasing = channel) {
            lock.release();
        }
    }

    @Override
    public String toString() {
        return "DataDirectoryLock[" + lockFile + "]";
    }

    private static ShrikeIOException alreadyLocked(Path dataDirectory, Path lockFile, Throwable heldByThisJvm) {
        return new ShrikeIOException("cannot start over the data directory " + dataDirectory + ": " + lockFile
                + " is locked by a broker that is already running over it, and one data directory is one broker's",
                new IOException("the data directory is already locked", heldByThisJvm));
    }

    /**
     * Closes the channel a failed {@link #take(Path)} had opened, so a start that does not happen does
     * not leak a file handle. A close that fails here is dropped: the start is already failing, and the
     * exception it is failing with is the useful one.
     */
    private static void closeQuietlyAfterFailedTake(FileChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException e) {
            // Dropped on purpose: see above.
        }
    }
}
