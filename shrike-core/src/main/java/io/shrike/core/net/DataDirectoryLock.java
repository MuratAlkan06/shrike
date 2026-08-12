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
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * that embeds two brokers, or a test starting a second one — is refused by
 * {@link #CLAIMED_LOCK_FILES_AND_DIRECTORIES} before a channel is opened at all. Neither is a condition an operator can act on differently, so both
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
 * <p><strong>A claim is kept under two keys, because either one alone is the wrong one.</strong> The
 * JVM's own lock table is kept under the lock file's device and inode, so a claim kept under the data
 * directory's real path was keyed on something else: two directories that really are two directories
 * whose {@value #FILE_NAME} is one file — a hard link, a bind mount, a symbolic link to the lock file
 * rather than to the directory holding it — were two claims over one lock, and a start over the second
 * of them took its claim, opened a channel, and only then met the lock the first broker was holding.
 * Keying on the file's identity instead answers that and gives up the other half, because an identity is
 * a file's: {@value #FILE_NAME} deleted out from under a running broker leaves that broker's lock on an
 * inode with no name, so the next start over the same directory creates a file of its own, reads an
 * identity nobody holds, and takes it — two brokers over one data directory inside one process, which is
 * the thing this class exists to refuse. So a take claims both, and either being somebody's already is
 * the refusal: the {@link BasicFileAttributes#fileKey() fileKey} a stat gives, which opens no descriptor
 * to read, and the directory's real path, which is what several spellings of one directory come to. A
 * take that cannot have both takes neither, and both go back together. An aliased start and a start over
 * a directory whose lock file has been replaced are refused the same way and in the same place: before
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
 * <p>Two edges, stated rather than hidden. A filesystem with no identity to give answers null to
 * {@code fileKey()} — Windows is the usual one — and the claim is then the data directory's real path
 * alone, which is what every claim used to be and what the backstop below covers. And a claim is this
 * JVM's, so what the second key closes is the half of the deletion this process can see: a broker in
 * <em>another</em> process still starts over a directory whose {@value #FILE_NAME} was deleted under the
 * broker holding it, because the kernel's lock went with the inode and the file the new start makes has
 * nothing on it to collide with. Nothing in this JVM can refuse that one, and it is why nothing here
 * deletes the lock file.
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
 * refusal therefore keeps the channel — in {@link #PINNED_REFUSED_CHANNELS} under the lock file's own
 * identity, or in {@link #PINNED_REFUSED_CHANNELS_WITH_NO_IDENTITY} where the file has none to give —
 * because a strong reference is the only thing that keeps a descriptor open in a garbage-collected
 * runtime. Anything else thrown out of the open or the lock is treated the same way: a
 * channel an {@link Error} left behind is on the same hazard as one an overlap left behind.
 *
 * <p><strong>What that costs is one descriptor per lock file the filesystem can identify, and a retry
 * pays it once.</strong> A take reads {@link #PINNED_REFUSED_CHANNELS} before it opens anything, so a
 * start over a file this process is already keeping a descriptor on opens none at all: it asks the
 * descriptor already there for the lock, and is refused by the same two answers as any other start, or
 * granted the lock and handed that descriptor with it. The bound that follows is exact rather than
 * hopeful — one descriptor per lock file wherever {@code fileKey()} answers, held until a start takes
 * the lock through it or until this process ends, whichever comes first — and what makes it exact is
 * the claim: a take reads or writes the entry for a key only while it holds the claim on that key, and
 * a claim admits one holder at a time, so two takes cannot both be opening a descriptor for one file. A
 * supervisor retrying a start that a non-broker holder goes on refusing therefore pays one descriptor
 * for the whole loop rather than one for every turn of it, which is the difference between a cost and a
 * leak. It is not free, and the trade is written down rather than hidden: a descriptor is held on a file
 * this process was refused over, an operator can see it in {@code lsof}, and one per lock file is slow
 * and visible where a data directory two brokers are writing to is neither.
 *
 * <p><strong>And it is asked for the lock only where the key is the file's own identity, which is the
 * other half of that sentence.</strong> An identity <em>is</em> the device and inode, so a descriptor
 * kept under one is open on the file the next start is asking about and on no other. A key that names a
 * directory — every key on a filesystem with no {@code fileKey()} to give, and the key of a lock file
 * that cannot be stat'ed — proves nothing of the sort: the file behind a descriptor kept under it may
 * be an inode {@value #FILE_NAME} stopped naming since, and a start handed that one would hold a lock on
 * an orphan without ever having opened {@value #FILE_NAME}, leaving it free for the next broker to take.
 * So on that path nothing kept is ever asked, every take opens a descriptor of its own, and every
 * descriptor a refusal leaves open is kept rather than one of them — refusing to reuse and keeping all
 * of them are one change, because a channel kept nowhere is a channel the cleaner closes. The cost there
 * is one descriptor per refused attempt, which is what this cost before the map above was keyed, and it
 * is what a filesystem with no identity to give is charged for having none.
 *
 * <p>Nothing here deletes the lock file, on this path or any other: stopping a broker leaves the data
 * directory exactly as it found it, and an empty file that the next start reuses is cheaper than a
 * delete that races the start it was meant to help.
 */
final class DataDirectoryLock implements Closeable {

    /** The file the lock is taken on, directly under the data directory. It stays empty. */
    static final String FILE_NAME = "shrike.lock";

    /**
     * What this JVM is holding, under two keys for every lock a start takes: the lock file's own
     * identity — the device and inode a {@link BasicFileAttributes#fileKey() fileKey} is, which is what
     * the JDK's own lock table is keyed on — and the data directory's real path. Either of them being
     * taken already is a refusal, and it takes both to make one, because each answers a question the
     * other cannot. The identity is what sees two directories whose {@value #FILE_NAME} is one file; the
     * real path is what sees one directory whose {@value #FILE_NAME} has been replaced underneath the
     * broker holding it. Where a filesystem has no identity to give, the real path is the only key and
     * is still one entry for every spelling of one directory.
     *
     * <p>This is one of the three pieces of static mutable state in this codebase, and PRINCIPLES §2
     * forbids it, so the exception is written down here and in DESIGN.md as the preamble to those rules
     * requires. What it holds is a fact about the process rather than about any object in it — the JDK's
     * own {@code FileLockTable} is static for the same reason — and there is nowhere to inject it from:
     * {@link ShrikeBroker#start} is what takes a lock, and an embedder starting a second broker is
     * exactly the caller that must be refused, so a registry it could hand in would be a registry it
     * could hand in a second copy of.
     */
    // guarded by: its own concurrency — a set backed by a ConcurrentHashMap, entered only through the
    // atomic adds in claimAllOrNone and left only through releaseClaims, which take() and close() call.
    private static final Set<Object> CLAIMED_LOCK_FILES_AND_DIRECTORIES = ConcurrentHashMap.newKeySet();

    /**
     * The channels a refusal opened and must not close, one to a lock file wherever
     * {@link BasicFileAttributes#fileKey() fileKey} answers, kept reachable so that nothing else closes
     * them either. A {@link FileChannel} the JDK opened for itself closes its descriptor from a cleaner
     * once the channel is unreachable, and on this file that close is what drops a running broker's
     * lock, so declining to close is only half of keeping it: the other half is the reference held here.
     *
     * <p>It is keyed by the lock file's own identity rather than being a bare collection, because the
     * entry is also what the next start over that file asks for the lock instead of opening a descriptor
     * of its own — and an identity is what makes that sound, since it is the device and inode the
     * descriptor is open on. That is what holds this to one descriptor per lock file however many times
     * a start is retried, and it is the whole of the difference between a bound and a leak. An entry is
     * given up only to the start that finally takes the lock through it, which owns that channel from
     * then on and closes it with the lock. A refusal with no identity to key on is kept in
     * {@link #PINNED_REFUSED_CHANNELS_WITH_NO_IDENTITY} instead and never asked, which is where the
     * other half of the bound is written down.
     *
     * <p>It is the second of the three static mutable fields PRINCIPLES §2 forbids and this class
     * documents, for the same reason as the first: an open descriptor is a fact about the process.
     */
    // guarded by: the claim on the same key, and by its own concurrency for the rest. A take reads or
    // writes the entry for a key only while it holds that key in CLAIMED_LOCK_FILES_AND_DIRECTORIES,
    // which admits one holder of a key at a time, so the read that decides whether to open a descriptor
    // and the write that keeps the one it opened cannot interleave with another take's over the same
    // file. The key is always the lock file's identity, which is one of the keys the take holding it
    // claimed.
    private static final ConcurrentMap<Object, FileChannel> PINNED_REFUSED_CHANNELS = new ConcurrentHashMap<>();

    /**
     * The channels a refusal opened over a lock file this process could not identify: kept for the life
     * of the process like the ones above, and unlike them never asked for a lock.
     *
     * <p>Asking one of them would be unsound, which is the whole reason this field is separate. An
     * identity <em>is</em> the device and inode, so a descriptor kept under one is open on the file the
     * next start is asking about and on no other; a key that names a directory says only which directory
     * somebody was refused over, and the file behind the descriptor kept under it may be an inode
     * {@value #FILE_NAME} stopped naming since. A start handed that descriptor would be granted a lock on
     * an orphan and would never open {@value #FILE_NAME} at all — so the next start over the directory
     * would find that file free and take it, which is two brokers over one data directory, the thing
     * this class exists to refuse.
     *
     * <p>A list per key rather than the one slot the map above holds, and that is load-bearing rather
     * than tidy. Keeping one and refusing to reuse it would leave every later refusal's channel
     * referenced by nothing, and a channel nothing references is closed by its cleaner — which is the
     * close that drops this process's lock on that file. Refusing to reuse and keeping every one of them
     * are therefore one change and not two. What it costs is a descriptor per refused attempt rather
     * than per lock file, which is what this cost before the map above was keyed, and it is the price of
     * a filesystem that has no identity to give.
     *
     * <p>It is the third of the three static mutable fields PRINCIPLES §2 forbids and this class
     * documents, for the reason the other two carry: an open descriptor is a fact about the process.
     */
    // guarded by: the claim on the same key, and by its own concurrency for the rest — the same guard
    // as the map above and for the same reason, since a take adds to the list for a key only while it
    // holds that key. Nothing reads it for a lock, so there is no read to order a write against.
    private static final ConcurrentMap<Path, List<FileChannel>> PINNED_REFUSED_CHANNELS_WITH_NO_IDENTITY =
            new ConcurrentHashMap<>();

    private final Path lockFile;

    /**
     * The entries in {@link #CLAIMED_LOCK_FILES_AND_DIRECTORIES} this lock owns — the lock file's
     * identity and the data directory's real path, or the real path alone where there is no identity to
     * be had — given back together by {@link #close()}.
     */
    private final List<Object> claims;

    /**
     * Whether the entries above have already gone back, so that closing this lock twice gives them back
     * once. An entry names a file or a directory and says nothing about who put it there, so a second
     * close — after another broker had started over the same directory — would otherwise take that
     * broker's claim.
     */
    // guarded by: its own atomicity — read and written only by the compare-and-set in close().
    private final AtomicBoolean claimsReleased = new AtomicBoolean();

    private final FileChannel channel;
    private final FileLock lock;

    private DataDirectoryLock(Path lockFile, List<Object> claims, FileChannel channel, FileLock lock) {
        this.lockFile = lockFile;
        this.claims = claims;
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
        createLockFileIfAbsent(dataDirectory, lockFile);
        Object fileIdentity = identityOf(lockFile);
        Path directoryPath = realPathOf(dataDirectory, lockFile);
        List<Object> claims = fileIdentity != null ? List.of(fileIdentity, directoryPath) : List.of(directoryPath);

        if (!claimAllOrNone(claims)) {
            // Refused without opening a thing, which is the whole of what claiming before opening buys:
            // a channel opened here would drop the running broker's lock when it was closed, and every
            // way a channel has of being closed counts, the collector's included.
            throw alreadyLocked(dataDirectory, lockFile, null);
        }
        boolean taken = false;
        FileChannel opened = null;
        try {
            // A descriptor the backstop is already keeping on this file is the one to ask, because
            // opening a second is the thing a retry must not be allowed to repeat. It is asked only
            // where the key is the lock file's own identity, and that is a soundness condition rather
            // than an optimisation: an identity is the device and inode, so a descriptor kept under one
            // is provably open on the file this take is asking about. A key that names a directory
            // proves nothing of the sort — the file behind a descriptor kept under it may be an inode
            // shrike.lock stopped naming since — so a take that has no identity to ask by opens its own
            // and asks nothing. Reading the entry here is safe without any lock of this class's own:
            // this take holds the claim on the key, and nothing else can be filling or emptying that
            // entry while it does.
            FileChannel kept = fileIdentity == null ? null : PINNED_REFUSED_CHANNELS.get(fileIdentity);
            if (kept == null) {
                opened = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            }
            FileChannel channel = kept != null ? kept : opened;
            FileLock lock = channel.tryLock();
            if (lock == null) {
                // The kernel refused this lock, which says another process holds one that conflicts
                // with it, which says this process was granted no whole-file lock on it: two processes
                // cannot both have one. That is what closing here rests on, and being exact about what
                // it does not rest on is the point of this comment. The overlap below not having been
                // thrown proves less than it looks: the JVM's lock table holds its FileLocks weakly, so
                // a lock in this process whose FileLock object has been collected is not in the table to
                // be found. That is a narrower window than it reads as, and the width is worth having
                // right: a FileLock cannot be collected while its channel is, because the channel's own
                // FileLockTable keeps a strong Set of the locks taken through it, so dropping a FileLock
                // and keeping its channel keeps the entry. What is left is the moment between a channel
                // becoming unreachable and the cleaner that closes it running — and that close drops the
                // very lock in question. A lock over some other range of this file would not have
                // conflicted with the answer above either. Nothing in this codebase takes either kind,
                // and the window that leaves is accepted rather than closed by keeping the descriptor:
                // this is the ordinary refusal — a second broker on the machine — and it would be a
                // descriptor kept on a file the kernel has just said this process holds nothing on.
                //
                // Only a channel this take opened is this take's to close: a kept one belongs to the
                // backstop, and closing that is the damage this class exists to not do.
                if (opened != null) {
                    opened.close();
                }
                throw alreadyLocked(dataDirectory, lockFile, null);
            }
            DataDirectoryLock held = new DataDirectoryLock(lockFile, claims, channel, lock);
            taken = true;
            // A kept channel that has just been granted the lock is this lock's own from here: it
            // leaves the map so that close() is free to close it, and so that the next start over this
            // file opens one of its own rather than asking a descriptor this broker has closed. There is
            // nothing to hand over where there is no identity, because nothing kept there is ever asked
            // for a lock, and this channel is one this take opened for itself.
            if (fileIdentity != null) {
                PINNED_REFUSED_CHANNELS.remove(fileIdentity, channel);
            }
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
            // No lock of this process's was in the JVM's lock table when tryLock consulted it, or the
            // overlap would have arrived above, so closing what this failed take opened releases
            // nothing that table knows of. That is the same window the branch above names, and it is
            // accepted here for the same reason. What this take did not open, it does not close: a
            // kept channel outlives every take that asks it for the lock.
            closeQuietlyAfterFailedTake(opened);
            throw new ShrikeIOException("cannot lock the data directory " + dataDirectory + " through " + lockFile, e);
        } finally {
            // Both of a failed take's leavings go back here, on every way out that is not the one
            // success above: the two refusals, an IOException, and equally an Error from opening or
            // locking under memory pressure.
            //
            // A channel still open is a channel one of those paths could not safely close, so it is
            // kept rather than dropped; the two paths where closing is safe have closed it already,
            // and a channel that is closed is not kept. It is kept before the claims go back, and that
            // order is load-bearing wherever there is an identity to keep it under: a claim is the only
            // thing keeping the next take from opening a second descriptor on this file, so the entry
            // has to be there before that take can look. Where there is none, no later take looks, and
            // keeping it in the same place costs that path nothing.
            //
            // Claims left behind by a take that failed are worse than the failure that left them,
            // because an entry names a file or a directory and nothing else: every later start over
            // either in this JVM would be refused for the life of the process by a claim with no broker
            // behind it. Both of this take's go back, and only this take's — a refused claim was rolled
            // back by claimAllOrNone before this ever ran.
            if (!taken) {
                pinIfAFailedTakeLeftItOpen(fileIdentity, directoryPath, opened);
                releaseClaims(claims);
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
            // In a finally because a release that failed still ends this broker's claims on the
            // directory: leaving them behind would refuse every later start in this JVM over a
            // directory the operating system had already let go of. Once, because an entry names the
            // lock file or the directory rather than this lock: a second close of an instance that has
            // already given its claims back would take away whatever broker holds them now. Exactly the
            // entries this take added go back, which is the same list it made them from.
            if (claimsReleased.compareAndSet(false, true)) {
                releaseClaims(claims);
            }
        }
    }

    @Override
    public String toString() {
        return "DataDirectoryLock[" + lockFile + "]";
    }

    /**
     * How many descriptors the backstop is keeping open in this process, which is what turns the bounds
     * above from sentences into numbers: one per lock file wherever {@code fileKey()} answers, whatever a
     * supervisor retries, and one per refused attempt where it does not.
     *
     * <p>It is here for
     * {@code BrokerDataDirectoryLockTest#keepsOneDescriptorPerLockFileHoweverManyStartsANonBrokerHolderRefuses}
     * and for
     * {@code BrokerDataDirectoryLockTest#keepsEveryRefusedDescriptorWhereTheLockFileHasNoIdentityToBoundThemBy},
     * which count them across a run of refusals, because a cost nobody counts is a cost nobody knows has
     * changed. Nothing in the broker reads it.
     *
     * @return how many channels refusals are keeping open, across every lock file this process has been
     *         refused over, under both of the keys one can be kept under
     */
    static int pinnedChannelCount() {
        int keptWithNoIdentity = 0;
        for (List<FileChannel> channels : PINNED_REFUSED_CHANNELS_WITH_NO_IDENTITY.values()) {
            keptWithNoIdentity += channels.size();
        }
        return PINNED_REFUSED_CHANNELS.size() + keptWithNoIdentity;
    }

    /**
     * The first of the two keys a claim is kept under: the lock file's own identity, so that two
     * directories whose {@value #FILE_NAME} is one file are one claim rather than two claims over one
     * lock. It is read with a stat and nothing else — no descriptor is opened on a file another broker
     * may be holding, which is the point of doing it this way rather than by opening the file and
     * asking it.
     *
     * @param lockFile the file whose identity is wanted
     * @return the identity, or null where it cannot be had: a filesystem that has none to give answers
     *         null itself, and a lock file that cannot be stat'ed at all — a symbolic link to a name
     *         that is not there yet — has none to read. Neither is a start to refuse, because both can
     *         still be opened and locked, so the claim is then the directory's real path alone and the
     *         backstop in {@link #take(Path)} covers what that cannot see.
     */
    private static Object identityOf(Path lockFile) {
        try {
            return Files.readAttributes(lockFile, BasicFileAttributes.class).fileKey();
        } catch (IOException cannotStatTheLockFile) {
            // Handled by keying on the directory alone, which is what the paragraph above says this
            // costs. It is not rethrown because a lock file that cannot be stat'ed can still be opened
            // and locked, and a start this class used to allow is not one to refuse over the way its
            // claim is keyed.
            return null;
        }
    }

    /**
     * Takes every key of a claim or none of them, so that a take is refused rather than half-registered
     * and a refusal gives back exactly what it had taken and nothing anybody else is holding.
     *
     * <p>The keys are added in one order everywhere — the lock file's identity, then the directory's
     * real path — so two takes that want the same key contend on it in the same place, and the one that
     * loses is the one that rolls back. Two takes cannot each take a key the other wants and each fail:
     * they collide on the first key they share, and the winner has the rest of its list to itself.
     *
     * @param claims the keys this take needs, in the order they are taken
     * @return true if every one of them is now this take's, false if one was somebody else's, in which
     *         case none of them is
     */
    private static boolean claimAllOrNone(List<Object> claims) {
        int taken = 0;
        for (Object claim : claims) {
            if (!CLAIMED_LOCK_FILES_AND_DIRECTORIES.add(claim)) {
                releaseClaims(claims.subList(0, taken));
                return false;
            }
            taken++;
        }
        return true;
    }

    /**
     * Gives back the keys a take added, and only those: the list is the one that take built, so nothing
     * here can take away an entry another holder put there.
     *
     * @param claims the keys to give back
     */
    private static void releaseClaims(List<Object> claims) {
        for (Object claim : claims) {
            CLAIMED_LOCK_FILES_AND_DIRECTORIES.remove(claim);
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
     * The second key of every claim, and the only key where a lock file has no identity to give: the
     * directory with every link, {@code .}, and {@code ..} resolved, so that two names for one
     * directory cannot become two claims on one lock file — and so that a lock file replaced under the
     * broker holding it is still that broker's directory. The directory exists by now —
     * {@link ShrikeBroker#start} creates it before it locks it — so this failing means the directory
     * went away or cannot be read, which is a start that cannot happen.
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
     * by anything else — under the lock file's own identity where it has one, and in a list nothing ever
     * asks for a lock where it has none.
     *
     * <p>Being left alone is not the same as being kept: a {@link FileChannel} the JDK opened for itself
     * registers a cleaner that closes its descriptor once nothing references the channel, and on this
     * file that close drops whatever lock this process holds on it — which, on the path that gets here,
     * is a lock a running broker is serving on. So the reference this leaves is the thing that keeps it,
     * and the only take that gets one back is the one that ends up holding the lock through it.
     *
     * <p>A channel that is already closed is not kept: the two failure paths where closing is safe have
     * closed it by the time this runs, and a descriptor that is gone cannot be dropped twice. Nor is one
     * this take never opened, because a take that was given a kept channel opened nothing.
     *
     * <p>Under an identity it is {@code putIfAbsent} rather than {@code put} because the entry a take
     * found empty is the entry it is filling, and the claim it holds is what says those are the same
     * entry. A {@code put} would read as though overwriting were allowed, and overwriting here means
     * dropping the last reference to a descriptor that must not be closed.
     *
     * <p>With no identity there is no entry to fill, because nothing kept on that path is ever asked for
     * a lock: {@link #PINNED_REFUSED_CHANNELS_WITH_NO_IDENTITY} says why. So every one of them is added
     * to that directory's list, and one slot with {@code putIfAbsent} would be the worse mistake rather
     * than a tighter bound — a second refusal's channel would then be referenced by nothing, and a
     * channel nothing references is closed by its cleaner, which is the close this whole branch exists
     * to prevent.
     *
     * <p>It is not private because {@code BrokerDataDirectoryLockTest} calls it, and that is worth
     * saying plainly. A refusal that reaches here with no identity cannot be built on a filesystem that
     * answers {@code fileKey()}: it takes an open that lands on an inode this JVM already locks, and a
     * lock file that cannot be stat'ed is one whose open either creates a fresh inode or would have been
     * stat'ed. So the two tests for that path hand this method what such a refusal hands it. Nothing
     * outside this class and that test calls it.
     *
     * @param fileIdentity  the lock file's identity, or null where it has none to give, in which case
     *                      the channel is kept and never asked
     * @param directoryPath the data directory's real path, which is what a channel with no identity to
     *                      keep it under is kept beside
     * @param opened        the channel this take opened, or null if it was given one that was already
     *                      kept
     */
    static void pinIfAFailedTakeLeftItOpen(Object fileIdentity, Path directoryPath, FileChannel opened) {
        if (opened == null || !opened.isOpen()) {
            return;
        }
        if (fileIdentity != null) {
            PINNED_REFUSED_CHANNELS.putIfAbsent(fileIdentity, opened);
            return;
        }
        PINNED_REFUSED_CHANNELS_WITH_NO_IDENTITY
                .computeIfAbsent(directoryPath, directory -> new CopyOnWriteArrayList<>())
                .add(opened);
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
