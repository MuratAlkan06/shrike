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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
 * that embeds two brokers, or a test starting a second one — is refused by {@link #CLAIMED_DIRECTORIES}
 * before a channel is opened at all. Neither is a condition an operator can act on differently, so both
 * arrive as one {@link ShrikeIOException} naming the directory and saying it is another broker's.
 *
 * <p><strong>Why the same-JVM refusal is answered before a second descriptor is opened.</strong> The
 * JDK implements {@link FileLock} with POSIX {@code fcntl} record locks, and POSIX drops <em>every</em>
 * lock a process holds on a file the moment that process closes <em>any</em> descriptor on it. Asking
 * for the lock a second time in one JVM throws {@link OverlappingFileLockException} out of the JVM's own
 * lock table before a system call is made, so the second attempt never held anything — but closing the
 * channel it had opened, which a refusal must do or leak a descriptor, released the <em>running</em>
 * broker's lock as a side effect. What that left was a broker still serving over a data directory it no
 * longer held, and an outside process free to take the lock and start over it. So a start claims the
 * directory in this JVM first, and a start that cannot have the claim is refused without opening
 * anything: the descriptor whose closing did the damage is never created.
 *
 * <p>Nothing here deletes the lock file, on this path or any other: stopping a broker leaves the data
 * directory exactly as it found it, and an empty file that the next start reuses is cheaper than a
 * delete that races the start it was meant to help.
 */
final class DataDirectoryLock implements Closeable {

    /** The file the lock is taken on, directly under the data directory. It stays empty. */
    static final String FILE_NAME = "shrike.lock";

    /**
     * The data directories this JVM is holding, each under the real path it resolves to, so that two
     * spellings of one directory — a relative one, a symbolic link, a {@code ..} on the way — are one
     * entry rather than two claims on one lock file.
     *
     * <p>This is the one piece of static mutable state in this codebase, and PRINCIPLES §2 forbids it,
     * so the exception is written down here and in DESIGN.md as the preamble to those rules requires.
     * What it holds is a fact about the process rather than about any object in it — the JDK's own
     * {@code FileLockTable} is static for the same reason — and there is nowhere to inject it from:
     * {@link ShrikeBroker#start} is what takes a lock, and an embedder starting a second broker is
     * exactly the caller that must be refused, so a registry it could hand in would be a registry it
     * could hand in a second copy of.
     */
    // guarded by: its own concurrency — a set backed by a ConcurrentHashMap, entered only through the
    // atomic add below and left only through the removals in take() and close().
    private static final Set<Path> CLAIMED_DIRECTORIES = ConcurrentHashMap.newKeySet();

    private final Path lockFile;

    /** The entry in {@link #CLAIMED_DIRECTORIES} this lock owns, given back by {@link #close()}. */
    private final Path claimedDirectory;

    private final FileChannel channel;
    private final FileLock lock;

    private DataDirectoryLock(Path lockFile, Path claimedDirectory, FileChannel channel, FileLock lock) {
        this.lockFile = lockFile;
        this.claimedDirectory = claimedDirectory;
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
        Path claimedDirectory = realPathOf(dataDirectory, lockFile);
        if (!CLAIMED_DIRECTORIES.add(claimedDirectory)) {
            // Refused without opening a thing, which is the whole of the class comment's third
            // paragraph: a channel opened here and closed again would drop the running broker's lock.
            throw alreadyLocked(dataDirectory, lockFile, null);
        }
        FileChannel channel = null;
        try {
            channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = channel.tryLock();
            if (lock == null) {
                // Another process holds it, so this process holds no lock on this file — the claim
                // above is the proof — and closing the channel therefore releases nothing of ours.
                channel.close();
                CLAIMED_DIRECTORIES.remove(claimedDirectory);
                throw alreadyLocked(dataDirectory, lockFile, null);
            }
            return new DataDirectoryLock(lockFile, claimedDirectory, channel, lock);
        } catch (OverlappingFileLockException heldByThisJvm) {
            // Not a second broker, which the claim already refused, but something else in this JVM
            // holding this file: the sentence is the same, and the claim goes back so that a start
            // after that holder lets go is not refused by a claim nothing is behind.
            closeQuietlyAfterFailedTake(channel);
            CLAIMED_DIRECTORIES.remove(claimedDirectory);
            throw alreadyLocked(dataDirectory, lockFile, heldByThisJvm);
        } catch (IOException e) {
            closeQuietlyAfterFailedTake(channel);
            CLAIMED_DIRECTORIES.remove(claimedDirectory);
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
        } finally {
            // In a finally because a release that failed still ends this broker's claim on the
            // directory: leaving the entry behind would refuse every later start in this JVM over a
            // directory the operating system had already let go of.
            CLAIMED_DIRECTORIES.remove(claimedDirectory);
        }
    }

    @Override
    public String toString() {
        return "DataDirectoryLock[" + lockFile + "]";
    }

    /**
     * The identity a claim is kept under: the directory with every link, {@code .}, and {@code ..}
     * resolved, so that two names for one directory cannot become two claims on one lock file. The
     * directory exists by now — {@link ShrikeBroker#start} creates it before it locks it — so this
     * failing means the directory went away or cannot be read, which is a start that cannot happen.
     */
    private static Path realPathOf(Path dataDirectory, Path lockFile) {
        try {
            return dataDirectory.toRealPath();
        } catch (IOException e) {
            throw new ShrikeIOException("cannot lock the data directory " + dataDirectory + " through " + lockFile, e);
        }
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
