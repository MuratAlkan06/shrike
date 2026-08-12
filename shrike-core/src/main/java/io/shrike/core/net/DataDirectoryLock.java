package io.shrike.core.net;

import io.shrike.core.log.ShrikeIOException;
import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

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
 * that embeds two brokers, or a test starting a second one — is refused by {@link #CLAIMED_LOCK_FILES}
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
 * anything: on that path the descriptor whose closing did the damage is never created, and that is
 * where every retry over a directory this JVM already holds now ends.
 *
 * <p><strong>A claim is kept under the lock file's identity, not the directory's name.</strong> The
 * JVM's own lock table is kept under the lock file's device and inode, so a claim kept under the data
 * directory's real path was keyed on something else: two directories that really are two directories
 * whose {@value #FILE_NAME} is one file — a hard link, a bind mount, a symbolic link to the lock file
 * rather than to the directory holding it — were two claims over one lock, and a start over the second
 * of them took its claim, opened a channel, and only then met the lock the first broker was holding. So
 * a claim is kept under what the lock table is kept under: the file's own identity, the
 * {@link BasicFileAttributes#fileKey() fileKey} a stat gives, which opens no descriptor to read. An
 * aliased start maps to the claim the running broker already holds and is refused before
 * {@link FileChannel#open}, having opened nothing that could be closed.
 *
 * <p>Reading that identity wants the file to be there, and the first start over a directory is the one
 * where it is not, so {@link #take} creates it with {@link Files#createFile} — {@code O_CREAT|O_EXCL}
 * and nothing more. That is the whole of why it is that call and not an open: on a file that is already
 * there it fails in the kernel with no descriptor ever existing, so the one thing this class must never
 * do to a lock file somebody is holding — open it and close it again — is not something this path can
 * do by accident. A file it does create is one that did not exist a moment ago, which is a file nobody
 * can hold a lock on.
 *
 * <p>Two caveats, stated rather than hidden. A filesystem with no identity to give answers null to
 * {@code fileKey()} — Windows is the usual one — and the claim there falls back to the data directory's
 * real path, which is what it was keyed on before and what the backstop below covers. And an identity is
 * the file's, so a {@value #FILE_NAME} unlinked out from under a running broker leaves that broker's
 * claim and lock on an inode with no name: the next start creates a new file, claims it, and locks it.
 * Deleting that file already did exactly this to another process — the kernel's lock is on the inode too
 * — so it is the same hole rather than a new one, and it is one more reason nothing here deletes it.
 *
 * <p><strong>The overlap branch is a backstop, and what it opens it keeps.</strong> Two things can still
 * carry a start past the claim into {@link FileChannel#open} with somebody's lock on the other side: the
 * moment between reading the file's identity and opening it, in which another thread of this JVM can
 * take the lock, and a holder in this JVM that is not a broker at all. {@link FileChannel#tryLock()}
 * then throws {@link OverlappingFileLockException}, and closing that channel would drop the lock its
 * real holder is running on. So the refusal does not close it — and not closing it is not enough on its
 * own, which is the finding this paragraph exists for. A channel the JDK opened for itself registers a
 * cleaner that closes its descriptor once nothing references the channel, so a channel merely left alone
 * is closed by the next collection, and that close drops the lock exactly as an explicit one would. The
 * refusal therefore keeps the channel in {@link #PINNED_REFUSED_CHANNELS}, under the key its claim was
 * kept under, because a strong reference is the only thing that keeps a descriptor open in a
 * garbage-collected runtime. Anything else thrown out of the open or the lock is treated the same way: a
 * channel an {@link Error} left behind is on the same hazard as one an overlap left behind.
 *
 * <p><strong>What that costs is one descriptor per lock file, and a retry pays it once.</strong> A take
 * reads {@link #PINNED_REFUSED_CHANNELS} before it opens anything, so a start over a file this process
 * is already keeping a descriptor on opens none at all: it asks the descriptor already there for the
 * lock, and is refused by the same two answers as any other start, or granted the lock and handed that
 * descriptor with it. The bound that follows is exact rather than hopeful — one descriptor per lock
 * file, held until a start takes the lock through it or until this process ends, whichever comes first
 * — and what makes it exact is the claim: a take reads or writes the entry for a key only while it
 * holds the claim on that key, and a claim admits one holder at a time, so two takes cannot both be
 * opening a descriptor for one file. A supervisor retrying a start that a non-broker holder goes on
 * refusing therefore pays one descriptor for the whole loop rather than one for every turn of it, which
 * is the difference between a cost and a leak. It is not free, and the trade is written down rather than
 * hidden: a descriptor is held on a file this process was refused over, an operator can see it in
 * {@code lsof}, and one per lock file is slow and visible where a data directory two brokers are writing
 * to is neither.
 *
 * <p>Nothing here deletes the lock file, on this path or any other: stopping a broker leaves the data
 * directory exactly as it found it, and an empty file that the next start reuses is cheaper than a
 * delete that races the start it was meant to help.
 */
final class DataDirectoryLock implements Closeable {

    /** The file the lock is taken on, directly under the data directory. It stays empty. */
    static final String FILE_NAME = "shrike.lock";

    /**
     * The lock files this JVM is holding, each under its own identity — the device and inode a
     * {@link BasicFileAttributes#fileKey() fileKey} is, which is what the JDK's own lock table is keyed
     * on, so that two directories whose {@value #FILE_NAME} is one file are one entry rather than two
     * claims on one lock. Where a filesystem has no identity to give, the entry is the data directory's
     * real path instead, which is still one entry for every spelling of one directory.
     *
     * <p>This is one of the two pieces of static mutable state in this codebase, and PRINCIPLES §2
     * forbids it, so the exception is written down here and in DESIGN.md as the preamble to those rules
     * requires. What it holds is a fact about the process rather than about any object in it — the JDK's
     * own {@code FileLockTable} is static for the same reason — and there is nowhere to inject it from:
     * {@link ShrikeBroker#start} is what takes a lock, and an embedder starting a second broker is
     * exactly the caller that must be refused, so a registry it could hand in would be a registry it
     * could hand in a second copy of.
     */
    // guarded by: its own concurrency — a set backed by a ConcurrentHashMap, entered only through the
    // atomic add below and left only through the removals in take() and close().
    private static final Set<Object> CLAIMED_LOCK_FILES = ConcurrentHashMap.newKeySet();

    /**
     * The channels a refusal opened and must not close, one to a lock file, kept reachable so that
     * nothing else closes them either. A {@link FileChannel} the JDK opened for itself closes its
     * descriptor from a cleaner once the channel is unreachable, and on this file that close is what
     * drops a running broker's lock, so declining to close is only half of keeping it: the other half is
     * the reference held here.
     *
     * <p>It is keyed by what the claim is keyed by rather than being a bare collection, because the
     * entry is also what the next start over that file asks for the lock instead of opening a descriptor
     * of its own. That is what holds this to one descriptor per lock file however many times a start is
     * retried, and it is the whole of the difference between a bound and a leak. An entry is given up
     * only to the start that finally takes the lock through it, which owns that channel from then on and
     * closes it with the lock.
     *
     * <p>It is the second of the two static mutable fields PRINCIPLES §2 forbids and this class
     * documents, for the same reason as the first: an open descriptor is a fact about the process.
     */
    // guarded by: the claim on the same key, and by its own concurrency for the rest. A take reads or
    // writes the entry for a key only while it holds that key in CLAIMED_LOCK_FILES, which admits one
    // holder of a key at a time, so the read that decides whether to open a descriptor and the write
    // that keeps the one it opened cannot interleave with another take's over the same file.
    private static final ConcurrentMap<Object, FileChannel> PINNED_REFUSED_CHANNELS = new ConcurrentHashMap<>();

    private final Path lockFile;

    /** The entry in {@link #CLAIMED_LOCK_FILES} this lock owns, given back by {@link #close()}. */
    private final Object claim;

    /**
     * Whether the entry above has already gone back, so that closing this lock twice gives it back
     * once. The entry names a file and says nothing about who put it there, so a second close — after
     * another broker had started over the same directory — would otherwise take that broker's claim.
     */
    // guarded by: its own atomicity — read and written only by the compare-and-set in close().
    private final AtomicBoolean claimReleased = new AtomicBoolean();

    private final FileChannel channel;
    private final FileLock lock;

    private DataDirectoryLock(Path lockFile, Object claim, FileChannel channel, FileLock lock) {
        this.lockFile = lockFile;
        this.claim = claim;
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
        Object claim = claimOn(dataDirectory, lockFile);
        if (!CLAIMED_LOCK_FILES.add(claim)) {
            // Refused without opening a thing, which is the whole of what keying a claim on the lock
            // file's identity buys: a channel opened here would drop the running broker's lock when it
            // was closed, and every way a channel has of being closed counts, the collector's included.
            throw alreadyLocked(dataDirectory, lockFile, null);
        }
        boolean taken = false;
        FileChannel opened = null;
        try {
            // A descriptor the backstop is already keeping on this file is the one to ask, because
            // opening a second is the thing a retry must not be allowed to repeat. Reading the entry
            // here is safe without any lock of this class's own: this take holds the claim on the key,
            // and nothing else can be filling or emptying that entry while it does.
            FileChannel kept = PINNED_REFUSED_CHANNELS.get(claim);
            if (kept == null) {
                opened = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            }
            FileChannel channel = kept != null ? kept : opened;
            FileLock lock = channel.tryLock();
            if (lock == null) {
                // Another process holds it, so this process holds no lock on this file — an overlap
                // would have arrived below instead — and closing the channel releases nothing of ours.
                // Only a channel this take opened is this take's to close: a kept one belongs to the
                // backstop, and closing that is the damage this class exists to not do.
                if (opened != null) {
                    opened.close();
                }
                throw alreadyLocked(dataDirectory, lockFile, null);
            }
            DataDirectoryLock held = new DataDirectoryLock(lockFile, claim, channel, lock);
            taken = true;
            // A kept channel that has just been granted the lock is this lock's own from here: it
            // leaves the map so that close() is free to close it, and so that the next start over this
            // file opens one of its own rather than asking a descriptor this broker has closed.
            PINNED_REFUSED_CHANNELS.remove(claim, channel);
            return held;
        } catch (OverlappingFileLockException heldByThisJvm) {
            // Something in this JVM holds a lock on this very file, which the claim above did not see:
            // a lock taken between the identity being read and the file being opened, or a holder in
            // this process that is not a broker at all. A channel this take opened is neither closed
            // nor let go of — the finally below keeps it — because closing any descriptor on that file,
            // whether this code closes it or the collector's cleaner does, drops the lock its real
            // holder is running on. A kept one is already where it belongs, and this take opened none.
            throw alreadyLocked(dataDirectory, lockFile, heldByThisJvm);
        } catch (IOException e) {
            // Nothing in this JVM holds this file — an overlap would have arrived above — so closing
            // what this failed take opened releases nothing anybody has. What it did not open, it does
            // not close: a kept channel outlives every take that asks it for the lock.
            closeQuietlyAfterFailedTake(opened);
            throw new ShrikeIOException("cannot lock the data directory " + dataDirectory + " through " + lockFile, e);
        } finally {
            // Both of a failed take's leavings go back here, on every way out that is not the one
            // success above: the two refusals, an IOException, and equally an Error from opening or
            // locking under memory pressure.
            //
            // A channel still open is a channel one of those paths could not safely close, so it is
            // kept rather than dropped; the two paths where closing is safe have closed it already,
            // and a channel that is closed is not kept. It is kept before the claim goes back, and that
            // order is load-bearing: the claim is the only thing keeping the next take from opening a
            // second descriptor on this file, so the entry has to be there before that take can look.
            // A claim left behind by a take that failed is worse than the failure that left it, because
            // the entry names a file and nothing else: every later start over that file in this JVM
            // would be refused for the life of the process by a claim with no broker behind it.
            if (!taken) {
                pinIfAFailedTakeLeftItOpen(claim, opened);
                CLAIMED_LOCK_FILES.remove(claim);
            }
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
            // directory the operating system had already let go of. Once, because the entry names the
            // lock file rather than this lock: a second close of an instance that has already given
            // its claim back would take away whatever broker holds that file now.
            if (claimReleased.compareAndSet(false, true)) {
                CLAIMED_LOCK_FILES.remove(claim);
            }
        }
    }

    @Override
    public String toString() {
        return "DataDirectoryLock[" + lockFile + "]";
    }

    /**
     * How many descriptors the backstop is keeping open in this process, which is what turns the bound
     * above from a sentence into a number: one per lock file, whatever a supervisor retries.
     *
     * <p>It is here for
     * {@code BrokerDataDirectoryLockTest#keepsOneDescriptorPerLockFileHoweverManyStartsANonBrokerHolderRefuses},
     * which counts them across a run of refused starts, because a cost nobody counts is a cost nobody
     * knows has changed. Nothing in the broker reads it.
     *
     * @return how many channels refusals are keeping open, across every lock file this process has been
     *         refused over
     */
    static int pinnedChannelCount() {
        return PINNED_REFUSED_CHANNELS.size();
    }

    /**
     * The identity a claim is kept under: the lock file's own, so that two directories whose
     * {@value #FILE_NAME} is one file are one claim rather than two claims over one lock. It is read
     * with a stat and nothing else — no descriptor is opened on a file another broker may be holding,
     * which is the point of doing it this way rather than by opening the file and asking it.
     *
     * <p>Where the identity cannot be had — a filesystem that has none to give, which answers null, or
     * a lock file that cannot be stat'ed at all, which is a symbolic link to a name that is not there —
     * the claim falls back to the data directory's real path. That is what every claim was kept under
     * before, so the fallback is a claim that sees one directory's several spellings and not one file's
     * several directories; the backstop in {@link #take(Path)} is what covers the difference.
     */
    private static Object claimOn(Path dataDirectory, Path lockFile) {
        createLockFileIfAbsent(dataDirectory, lockFile);
        try {
            Object fileIdentity = Files.readAttributes(lockFile, BasicFileAttributes.class).fileKey();
            return fileIdentity != null ? fileIdentity : realPathOf(dataDirectory, lockFile);
        } catch (IOException cannotStatTheLockFile) {
            // Handled by keying on the directory instead, which is what the paragraph above says this
            // costs. It is not rethrown because a lock file that cannot be stat'ed can still be opened
            // and locked — a symbolic link to a name that does not exist yet is the case — and a start
            // this class used to allow is not one to refuse over the way its claim is keyed.
            return realPathOf(dataDirectory, lockFile);
        }
    }

    /**
     * Creates {@value #FILE_NAME} if it is not there yet, so that the claim above has a file whose
     * identity it can read. The first start over a data directory is the one that needs it; every other
     * start finds the file already there.
     *
     * <p>It is {@link Files#createFile} — {@code O_CREAT|O_EXCL} — rather than an open, and that is the
     * whole of why this is safe: on a file that already exists the call fails in the kernel without a
     * descriptor ever existing, so it cannot close one on a file another broker is holding and drop
     * that broker's lock. A file it does create is one that did not exist a moment ago, which is a file
     * no lock can be held on.
     */
    private static void createLockFileIfAbsent(Path dataDirectory, Path lockFile) {
        try {
            Files.createFile(lockFile);
        } catch (FileAlreadyExistsException theUsualCase) {
            // Nothing to do: what this wanted is the file, not the making of it.
        } catch (IOException e) {
            throw new ShrikeIOException("cannot lock the data directory " + dataDirectory + " through " + lockFile, e);
        }
    }

    /**
     * The identity a claim falls back to: the directory with every link, {@code .}, and {@code ..}
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

    /**
     * Keeps a channel a failed {@link #take(Path)} opened and could not safely close from being closed
     * by anything else, under the claim that take was refused over.
     *
     * <p>Being left alone is not the same as being kept: a {@link FileChannel} the JDK opened for itself
     * registers a cleaner that closes its descriptor once nothing references the channel, and on this
     * file that close drops whatever lock this process holds on it — which, on the path that gets here,
     * is a lock a running broker is serving on. So the reference in {@link #PINNED_REFUSED_CHANNELS} is
     * the thing that keeps it, and the only take that gets it back is the one that ends up holding the
     * lock through it.
     *
     * <p>A channel that is already closed is not kept: the two failure paths where closing is safe have
     * closed it by the time this runs, and a descriptor that is gone cannot be dropped twice. Nor is one
     * this take never opened, because a take that was given a kept channel opened nothing.
     *
     * <p>It is {@code putIfAbsent} rather than {@code put} because the entry a take found empty is the
     * entry it is filling, and the claim it holds is what says those are the same entry. A {@code put}
     * would read as though overwriting were allowed, and overwriting here means dropping the last
     * reference to a descriptor that must not be closed.
     *
     * @param claim  the key this take was refused over, which is what the descriptor is kept under
     * @param opened the channel this take opened, or null if it was given one that was already kept
     */
    private static void pinIfAFailedTakeLeftItOpen(Object claim, FileChannel opened) {
        if (opened != null && opened.isOpen()) {
            PINNED_REFUSED_CHANNELS.putIfAbsent(claim, opened);
        }
    }

    private static ShrikeIOException alreadyLocked(Path dataDirectory, Path lockFile, Throwable heldByThisJvm) {
        return new ShrikeIOException("cannot start over the data directory " + dataDirectory + ": " + lockFile
                + " is locked by a broker that is already running over it, and one data directory is one broker's",
                new IOException("the data directory is already locked", heldByThisJvm));
    }

    /**
     * Closes the channel a failed {@link #take(Path)} had opened, so a start that does not happen does
     * not keep a file handle for ever. It is called from the one failure path where closing is safe —
     * an {@link IOException}, which means no lock on this file is this process's to lose — and never
     * from the overlap path, where closing is the damage and the channel is kept instead. It is given
     * only what that take opened, never a channel it was handed from {@link #PINNED_REFUSED_CHANNELS},
     * which no take closes. A close that fails here is dropped: the start is already failing, and the
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
