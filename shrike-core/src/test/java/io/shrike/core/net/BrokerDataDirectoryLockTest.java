package io.shrike.core.net;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.abort;

import io.shrike.core.log.ShrikeIOException;
import io.shrike.core.protocol.CreateTopicRequest;
import io.shrike.core.protocol.ResponseDecoding;
import java.io.IOException;
import java.lang.ref.PhantomReference;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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

    /** How many collections are asked for before the test decides the collector has had its chance. */
    private static final int COLLECTION_ROUNDS = 10;

    /** Long enough that reaching it means a collection is not coming rather than that it is slow. */
    private static final long COLLECTION_TIMEOUT_MILLIS = 5_000L;

    /** One block of a round's garbage, sized so that a round is allocation rather than a rounding error. */
    private static final int GARBAGE_BLOCK_BYTES = 64 * 1024;

    /** How many of those blocks a round makes: 64 MiB, which is a collection's worth on any heap. */
    private static final int GARBAGE_BLOCKS_PER_ROUND = 1024;

    /**
     * How many times a start already refused once is retried, which is the loop a supervisor is: enough
     * that one descriptor per attempt would be plain in the count, and few enough that a run of it
     * costs a test nothing.
     */
    private static final int REFUSED_STARTS_A_SUPERVISOR_RETRIES = 25;

    @TempDir
    Path dataDirectory;

    /**
     * A second data directory of its own, whose lock file is made the very file {@link #dataDirectory}
     * already holds — the aliasing a claim keyed on a directory cannot see.
     */
    @TempDir
    Path aliasedDataDirectory;

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

    /**
     * The same question as above, asked of two data directories that are genuinely two directories
     * whose {@code shrike.lock} is one and the same file — a hard link, a bind mount, a symbolic link
     * to the lock file rather than to the directory holding it.
     *
     * <p>This is where a claim kept under a directory's real path could not answer, because the JVM's
     * own lock table is kept under the lock file's device and inode: the second start took a claim
     * nobody else held, opened a channel, and only then met the running broker's lock. Closing that
     * channel dropped the running broker's lock with it, so the refusal meant to protect a data
     * directory was the thing that gave it away — and <em>not</em> closing it only moved when that
     * happened, because a channel nothing references is closed by its cleaner at the next collection.
     * So the claim is kept under the lock file's own identity now, and the two assertions this test
     * gained are the two halves of that: the refusal has opened nothing to be cleaned up after, and
     * the lock is still the running broker's on the far side of a collection.
     *
     * <p>The last block is the other half of the price: the refusal has to leave the claim it took
     * behind it, or a directory refused once would be refused for the life of this JVM by an entry
     * with no broker behind it.
     */
    @Test
    void keepsTheRunningBrokersLockWhenRefusingAStartOverADirectoryThatSharesItsLockFile() throws Exception {
        try (ShrikeBroker running = BrokerHarness.start(dataDirectory)) {
            makeTheAliasedDirectorysLockFileTheRunningBrokersOwn();

            ShrikeIOException refused = assertThrows(ShrikeIOException.class,
                    () -> BrokerHarness.start(aliasedDataDirectory));

            assertTrue(refused.getMessage().contains(aliasedDataDirectory.toString()),
                    "the refusal names the directory that was asked for: " + refused.getMessage());
            assertNull(whatTheRefusalHadAlreadyOpenedAChannelOver(refused),
                    "and the claim refused it before a descriptor on that lock file existed: a refusal"
                            + " carrying an OverlappingFileLockException is one that had opened a channel"
                            + " on the file a broker is holding, which is the thing there is no safe way"
                            + " to be left with");
            assertEquals(DataDirectoryLockProbe.HELD, whatAnotherProcessFindsOfTheLock("aliased"),
                    "and refusing it left the running broker's lock where it was, so nothing outside"
                            + " this JVM can start a broker over the directory it is serving");

            ShrikeIOException refusedAgain = assertThrows(ShrikeIOException.class,
                    () -> BrokerHarness.start(aliasedDataDirectory));
            assertNull(whatTheRefusalHadAlreadyOpenedAChannelOver(refusedAgain),
                    "a second aliased start is refused the same way rather than by opening a channel of"
                            + " its own, so a supervisor retrying one costs this process nothing per"
                            + " attempt: " + refusedAgain.getMessage());

            collectWhatNothingIsHoldingOnToAnyMore();

            assertEquals(DataDirectoryLockProbe.HELD, whatAnotherProcessFindsOfTheLock("collected"),
                    "and it is still the running broker's once the collector has run, which is what a"
                            + " refusal that merely declines to close cannot promise: the JDK closes an"
                            + " unreferenced channel's descriptor from a cleaner, and that close drops"
                            + " this lock exactly as an explicit one would");
        }

        try (ShrikeBroker overTheAlias = BrokerHarness.start(aliasedDataDirectory);
             WireClient client = WireClient.connectTo(overTheAlias)) {
            assertInstanceOf(ResponseDecoding.Answered.class,
                    client.call(FIRST_CORRELATION_ID, new CreateTopicRequest(TOPIC, ONLY_PARTITION_COUNT)),
                    "and a start refused once is not refused for ever: the claim that refusal took went"
                            + " back with it, so this directory is startable the moment the lock is free");
        }
    }

    /**
     * The one branch a claim cannot answer, and what a refusal that lands in it has to keep.
     *
     * <p>A claim is taken by starts, so a lock on {@code shrike.lock} that no start took is a lock no
     * claim knows about: something in this JVM that is not a broker holding it, or — the same shape,
     * without a way to build it in a test — a lock taken in the moment between a start reading the
     * file's identity and opening it. A start then reaches {@code FileChannel.open} with somebody's
     * lock on the other side, which is the branch that must not close what it opened.
     *
     * <p>Not closing it is not enough, and that is what this test is for. A channel nothing references
     * is closed by the cleaner the JDK registered with it, and that close drops every lock this process
     * holds on the file exactly as an explicit one would — so the refusal keeps a reference to the
     * channel for the life of the process, and the collection below is what tells the two apart. The
     * cost of that is one descriptor per time this happens, which is the trade this branch exists to
     * make; the block after it is the other half, that the claim went back so the refusal is not for
     * ever.
     */
    @Test
    void keepsTheLockOfANonBrokerHolderInThisJvmWhenTheStartThatMetItIsRefused() throws Exception {
        Path lockFile = dataDirectory.resolve(DataDirectoryLock.FILE_NAME);
        try (FileChannel holderInThisJvm = FileChannel.open(lockFile, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE)) {
            FileLock heldByNoBroker = holderInThisJvm.tryLock();
            assertNotNull(heldByNoBroker,
                    "the arrangement: this JVM holds the lock file without any start having claimed it");

            ShrikeIOException refused = assertThrows(ShrikeIOException.class,
                    () -> BrokerHarness.start(dataDirectory));

            assertInstanceOf(OverlappingFileLockException.class,
                    whatTheRefusalHadAlreadyOpenedAChannelOver(refused),
                    "which is refused by the branch that has already opened a channel, because a claim"
                            + " nobody took cannot refuse anything: " + refused.getMessage());

            collectWhatNothingIsHoldingOnToAnyMore();

            assertEquals(DataDirectoryLockProbe.HELD, whatAnotherProcessFindsOfTheLock("backstop"),
                    "and the channel that refusal opened is still open once the collector has run,"
                            + " because the refusal kept a reference to it: a channel nothing references"
                            + " is closed by its cleaner, and closing any descriptor on this file drops"
                            + " the lock this JVM is holding on it");
        }

        try (ShrikeBroker afterwards = BrokerHarness.start(dataDirectory);
             WireClient client = WireClient.connectTo(afterwards)) {
            assertInstanceOf(ResponseDecoding.Answered.class,
                    client.call(FIRST_CORRELATION_ID, new CreateTopicRequest(TOPIC, ONLY_PARTITION_COUNT)),
                    "and the claim that refusal took went back with it, so the directory is startable the"
                            + " moment the holder that was not a broker lets go");
        }
    }

    /**
     * What the backstop costs when the start it refuses is retried, which is a number rather than the
     * shape of a branch.
     *
     * <p>A start that meets a holder no claim knows about opens a descriptor before it finds out, and
     * that descriptor can never be closed while this process may hold a lock on the file — so it is
     * kept. The question this asks is how many of them a supervisor can make: something in this JVM
     * that is not a broker holds {@code shrike.lock} and does not let go, and a start is retried
     * against it {@link #REFUSED_STARTS_A_SUPERVISOR_RETRIES} times over. Keeping one per attempt is a
     * process that runs out of descriptors — the first thing to fail is not this broker but whatever
     * else in it needs to open a segment or accept a socket — so what is asserted is that the count is
     * flat after the first: the descriptor already kept for that file is the one every later attempt
     * asks for the lock through, and asking opens nothing.
     *
     * <p>The last block is the other half of the bound. A descriptor kept for ever would be the same
     * leak spelled differently, so the start that finally gets the lock gets it <em>through</em> the
     * kept descriptor and takes it over: the count goes back to where it started, and closing it is
     * that broker's business from then on.
     */
    @Test
    void keepsOneDescriptorPerLockFileHoweverManyStartsANonBrokerHolderRefuses() throws Exception {
        Path lockFile = dataDirectory.resolve(DataDirectoryLock.FILE_NAME);
        int keptBeforeAnyOfThis = DataDirectoryLock.pinnedChannelCount();

        try (FileChannel holderInThisJvm = FileChannel.open(lockFile, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE)) {
            FileLock heldByNoBroker = holderInThisJvm.tryLock();
            assertNotNull(heldByNoBroker,
                    "the arrangement: this JVM holds the lock file without any start having claimed it,"
                            + " which is the one holder a claim cannot see");

            assertThrows(ShrikeIOException.class, () -> BrokerHarness.start(dataDirectory));

            int keptAfterTheFirstRefusal = DataDirectoryLock.pinnedChannelCount();
            assertEquals(keptBeforeAnyOfThis + 1, keptAfterTheFirstRefusal,
                    "the first start to meet that holder opens one descriptor and keeps it, because"
                            + " closing it would drop the holder's lock");

            for (int retry = 0; retry < REFUSED_STARTS_A_SUPERVISOR_RETRIES; retry++) {
                assertThrows(ShrikeIOException.class, () -> BrokerHarness.start(dataDirectory));
            }

            assertEquals(keptAfterTheFirstRefusal, DataDirectoryLock.pinnedChannelCount(),
                    REFUSED_STARTS_A_SUPERVISOR_RETRIES + " more refusals over the same file opened"
                            + " nothing at all: what is kept is one descriptor per lock file, not one"
                            + " per attempt, or a supervisor retrying a start it is meant to be refused"
                            + " would spend this process's descriptors on being refused");
            assertTrue(heldByNoBroker.isValid(),
                    "and every one of them was refused for a holder that still holds it, so they were"
                            + " the retries this bounds rather than starts that found the file free");
            Reference.reachabilityFence(heldByNoBroker);
        }

        try (ShrikeBroker afterwards = BrokerHarness.start(dataDirectory);
             WireClient client = WireClient.connectTo(afterwards)) {
            assertEquals(keptBeforeAnyOfThis, DataDirectoryLock.pinnedChannelCount(),
                    "and the descriptor that was kept is handed to the start that can finally use it,"
                            + " which takes the lock through it rather than opening a second one: what"
                            + " the backstop holds is given back rather than held for ever");
            assertInstanceOf(ResponseDecoding.Answered.class,
                    client.call(FIRST_CORRELATION_ID, new CreateTopicRequest(TOPIC, ONLY_PARTITION_COUNT)),
                    "and the broker that took it over that directory serves");
        }
    }

    /**
     * The refusal a lock file's identity cannot make on its own: {@code shrike.lock} deleted out from
     * under the broker holding it, and a second start over that very data directory.
     *
     * <p>Deleting it leaves that broker's claim and its lock on an inode with no name. The next start
     * creates a file of its own and reads an identity nobody holds — so a claim kept under the lock
     * file's identity and nothing else let it through, and what that allowed was two brokers over one
     * data directory inside one JVM, each holding a valid lock on a different inode. That is the thing
     * this class exists to refuse, arriving through the one operation that makes a lock file stop being
     * the file it was. So a take claims the data directory's own real path as well as the lock file's
     * identity, and this is the half of that pair the identity cannot answer.
     *
     * <p>What it does not cover is said here rather than left to be found. The same deletion in front
     * of a broker in <em>another</em> process is still a start that succeeds: the kernel's lock is on
     * the inode too, so there is nothing on the new file for a second process to collide with. That
     * hole is older than any of this, it is the reason nothing here deletes the file, and no claim in
     * this JVM can close it.
     */
    @Test
    void refusesASecondBrokerOverADirectoryWhoseLockFileWasDeletedUnderTheBrokerHoldingIt() throws Exception {
        try (ShrikeBroker running = BrokerHarness.start(dataDirectory)) {
            Files.delete(dataDirectory.resolve(DataDirectoryLock.FILE_NAME));

            ShrikeIOException refused = assertThrows(ShrikeIOException.class,
                    () -> BrokerHarness.start(dataDirectory),
                    "a second broker over a directory whose lock file was deleted is still a second"
                            + " broker over that directory, and the lock file it makes for itself is a"
                            + " handle on nothing");

            assertTrue(refused.getMessage().contains(dataDirectory.toString()),
                    "the refusal names the directory an operator has to choose another of: " + refused.getMessage());
            assertNull(whatTheRefusalHadAlreadyOpenedAChannelOver(refused),
                    "and the claim refused it before a descriptor existed: the new lock file is a new"
                            + " inode, so what saw this start is the claim kept under the data"
                            + " directory's own real path rather than the one kept under the file");
            try (WireClient client = WireClient.connectTo(running)) {
                assertInstanceOf(ResponseDecoding.Answered.class,
                        client.call(FIRST_CORRELATION_ID, new CreateTopicRequest(TOPIC, ONLY_PARTITION_COUNT)),
                        "and the broker that holds the directory is serving over it still");
            }
        }

        try (ShrikeBroker afterwards = BrokerHarness.start(dataDirectory);
             WireClient client = WireClient.connectTo(afterwards)) {
            assertInstanceOf(ResponseDecoding.Answered.class,
                    client.call(FIRST_CORRELATION_ID, new CreateTopicRequest(ANOTHER_TOPIC, ONLY_PARTITION_COUNT)),
                    "and that refusal lasts exactly as long as the broker it was protecting: the next"
                            + " start over the directory makes a lock file of its own and takes it");
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

    /**
     * Hard-links {@link #aliasedDataDirectory}'s lock file onto the one the running broker is holding,
     * so that two real directories share one inode and one entry in the JVM's lock table.
     *
     * <p>A hard link is the cheapest of the several ways to arrive there — a bind mount and a symbolic
     * link to the lock file are the others — and it is the only one a test can build without root. A
     * filesystem that refuses links has nothing to say about this branch, so the test aborts rather
     * than passing on nothing; both operating systems this project is built on make them.
     */
    private void makeTheAliasedDirectorysLockFileTheRunningBrokersOwn() throws IOException {
        Path lockFile = dataDirectory.resolve(DataDirectoryLock.FILE_NAME);
        assertTrue(Files.isRegularFile(lockFile),
                "the running broker made the lock file this alias is about to become another name for");
        try {
            Files.createLink(aliasedDataDirectory.resolve(DataDirectoryLock.FILE_NAME), lockFile);
        } catch (UnsupportedOperationException | FileSystemException linksRefused) {
            abort("this filesystem will not make a hard link, so one lock file under two directories"
                    + " cannot be built here: " + linksRefused);
        }
    }

    /**
     * Which branch of {@link DataDirectoryLock#take} a refusal came out of, read off the exception it
     * came out with. Both refusals say the same sentence on purpose — an operator can do nothing
     * different about either — but only the one that has already opened a channel on the lock file has
     * an {@link java.nio.channels.OverlappingFileLockException} behind its cause, because that is the
     * exception a channel it opened threw. So a null here is the refusal that opened nothing.
     *
     * @param refused what a start was refused with
     * @return the overlap that refusal caught, or null if it never opened a channel to catch one from
     */
    private static Throwable whatTheRefusalHadAlreadyOpenedAChannelOver(ShrikeIOException refused) {
        return refused.getCause() == null ? null : refused.getCause().getCause();
    }

    /**
     * Waits until this JVM's collector has reclaimed objects nothing is holding on to any more, so that
     * the question asked after it is asked on the far side of a collection rather than the near side.
     *
     * <p>Nothing here sleeps, which §4 and §6 of PRINCIPLES.md both forbid, and nothing here asserts a
     * duration. Each round drops a canary nothing references, asks for a collection, and waits on a
     * {@link ReferenceQueue} until that canary's phantom reference is enqueued. It is rounds rather
     * than one pass because a reference an earlier frame happened to leave on the stack survives the
     * first collection and not the tenth.
     *
     * <p>What the canary witnesses is worth stating exactly, because it is less than it reads as. Its
     * enqueue says a reference-processing pass has happened; it orders nothing about the cleaner that
     * closes an unreferenced {@link java.nio.channels.FileChannel}, which runs its action on a thread
     * of its own after the reference is enqueued and could always run later still. So an unlucky
     * schedule makes the assertion after this pass for a reason it did not mean to — the descriptor is
     * open because nothing has got round to closing it yet rather than because something is holding it
     * — and that is a false pass rather than a flake. The direction that matters is the other one: this
     * cannot fail for a schedule, so a failure here is a descriptor that was closed, which is what the
     * bug was.
     *
     * @throws InterruptedException if this thread is interrupted while waiting for a collection
     */
    private static void collectWhatNothingIsHoldingOnToAnyMore() throws InterruptedException {
        int collections = 0;
        long garbageBytes = 0;
        for (int round = 0; round < COLLECTION_ROUNDS; round++) {
            ReferenceQueue<Object> collected = new ReferenceQueue<>();
            PhantomReference<Object> canary = new PhantomReference<>(new Object(), collected);

            garbageBytes += makeGarbage();
            System.gc();
            if (collected.remove(COLLECTION_TIMEOUT_MILLIS) != null) {
                collections++;
            }
            Reference.reachabilityFence(canary);
        }

        assertTrue(collections > 0, "the collector reclaimed something after " + (garbageBytes >> 20)
                + " MB of allocation and " + COLLECTION_ROUNDS + " requests, so what is asserted below"
                + " is asserted about a process that has collected rather than one that never did");
    }

    /**
     * Allocates and drops a round's worth of garbage, so that a collection has something to collect as
     * well as a request to run. The bytes are summed and the sum is used, because allocation nobody
     * looks at is allocation the compiler is entitled to decide never happened.
     *
     * @return how many bytes were allocated and dropped
     */
    private static long makeGarbage() {
        long allocated = 0;
        for (int block = 0; block < GARBAGE_BLOCKS_PER_ROUND; block++) {
            byte[] garbage = new byte[GARBAGE_BLOCK_BYTES];
            garbage[block % GARBAGE_BLOCK_BYTES] = (byte) block;
            allocated += garbage.length;
        }
        return allocated;
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
