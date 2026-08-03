package io.shrike.core.group;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.shrike.core.log.DurableFile;
import io.shrike.core.log.ShrikeIOException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Committed offsets: keyed by group, topic, and partition, written durably, and read back after a
 * restart.
 *
 * <p>A committed offset is the next offset to read. Every number in this test means that, and the
 * names say so.
 */
class GroupOffsetStoreTest {

    private static final String GROUP = "readers";

    /** The same group id, spelled the way a file written before the fold could have been named. */
    private static final String SAME_GROUP_CAPITALISED = "Readers";

    private static final String OTHER_GROUP = "auditors";

    /** A third group, for the tests that fill a budget of two and then ask for one more. */
    private static final String THIRD_GROUP = "watchers";

    private static final String TOPIC = "orders";
    private static final String OTHER_TOPIC = "events";

    /** Roomier than any test that is not about the budget will ever fill. */
    private static final int GROUP_BUDGET = 8;

    /** The budget of the tests that are about it: two commits fill it, and the third is refused. */
    private static final int TWO_GROUPS = 2;

    @TempDir
    Path dataDirectory;

    @Test
    void readsBackEveryCommittedOffsetAfterTheStoreIsReopened() {
        GroupOffsetStore store = GroupOffsetStore.open(dataDirectory, GROUP_BUDGET);
        store.commit(GROUP, TOPIC, 0, 5L);
        store.commit(GROUP, TOPIC, 1, 12L);
        store.commit(GROUP, OTHER_TOPIC, 0, 1L);
        store.commit(OTHER_GROUP, TOPIC, 0, 3L);

        GroupOffsetStore reopened = GroupOffsetStore.open(dataDirectory, GROUP_BUDGET);

        assertEquals(OptionalLong.of(5L), reopened.committedOffset(GROUP, TOPIC, 0));
        assertEquals(OptionalLong.of(12L), reopened.committedOffset(GROUP, TOPIC, 1),
                "two partitions of one topic are two keys, not one");
        assertEquals(OptionalLong.of(1L), reopened.committedOffset(GROUP, OTHER_TOPIC, 0));
        assertEquals(OptionalLong.of(3L), reopened.committedOffset(OTHER_GROUP, TOPIC, 0),
                "two groups reading the same partition keep their own offsets");
        assertEquals(OptionalLong.empty(), reopened.committedOffset(GROUP, TOPIC, 9),
                "a partition this group never committed has no offset, which is not the same as offset 0");
    }

    @Test
    void replacesTheOffsetOfAKeyThatIsCommittedAgain() {
        GroupOffsetStore store = GroupOffsetStore.open(dataDirectory, GROUP_BUDGET);
        store.commit(GROUP, TOPIC, 0, 5L);
        store.commit(GROUP, TOPIC, 0, 9L);

        GroupOffsetStore reopened = GroupOffsetStore.open(dataDirectory, GROUP_BUDGET);

        assertEquals(OptionalLong.of(9L), store.committedOffset(GROUP, TOPIC, 0));
        assertEquals(OptionalLong.of(9L), reopened.committedOffset(GROUP, TOPIC, 0));
    }

    /**
     * The ack-after-durable proof, at the level the broker calls: every durability step has already
     * happened by the time {@code commit} returns, so a broker that answers the client next is
     * answering about bytes that are on the device. An fsync leaves no trace a JVM can see, so the
     * steps report themselves as they run and this test reads the report.
     */
    @Test
    void returnsFromACommitOnlyAfterTheFileIsWrittenForcedMovedAndItsDirectoryForced() throws IOException {
        List<DurableFile.Step> steps = new ArrayList<>();
        GroupOffsetStore store = GroupOffsetStore.open(dataDirectory, GROUP_BUDGET, steps::add);

        store.commit(GROUP, TOPIC, 0, 5L);

        assertEquals(List.of(DurableFile.Step.WRITTEN, DurableFile.Step.FORCED, DurableFile.Step.MOVED,
                DurableFile.Step.DIRECTORY_FORCED), steps, "in this order, and all of them before commit returned");
        Path file = dataDirectory.resolve(GroupOffsetStore.DIRECTORY_NAME)
                .resolve(GROUP + GroupOffsetStore.FILE_SUFFIX);
        assertEquals(GroupOffsetStore.VERSION_HEADER + "\n" + TOPIC + " 0 5\n", Files.readString(file, UTF_8),
                "the file a reader would find is the whole file, header included");
        assertFalse(Files.exists(file.resolveSibling(file.getFileName() + ".tmp")),
                "the temporary file it was written through is gone, because it was moved rather than copied");
    }

    @Test
    void createsItsDirectoryUnderTheInjectedDataDirectory() {
        GroupOffsetStore.open(dataDirectory, GROUP_BUDGET);

        assertTrue(Files.isDirectory(dataDirectory.resolve(GroupOffsetStore.DIRECTORY_NAME)),
                "every path is derived from the data directory that was injected");
    }

    @Test
    void refusesToOpenAGroupFileItDidNotWrite() throws IOException {
        Path groups = Files.createDirectories(dataDirectory.resolve(GroupOffsetStore.DIRECTORY_NAME));
        Files.writeString(groups.resolve(GROUP + GroupOffsetStore.FILE_SUFFIX), "not a header at all\n", UTF_8);

        ShrikeIOException refused = assertThrows(ShrikeIOException.class,
                () -> GroupOffsetStore.open(dataDirectory, GROUP_BUDGET));

        assertTrue(refused.getMessage().contains(GroupOffsetStore.VERSION_HEADER),
                "committed offsets are not something to guess at: an unreadable file fails the start");
    }

    /**
     * A group id is a file name, so two ids that differ only in case are one group here — the way they
     * are one file on APFS and on Windows. If they were two groups, the second one's whole-file rewrite
     * would replace the first one's committed offsets with its own on any filesystem that folds case.
     *
     * <p>Nothing here asks the filesystem anything: the store is asked what it holds.
     */
    @Test
    void keepsOneSetOfCommittedOffsetsForGroupIdsThatDifferOnlyInCase() {
        GroupOffsetStore store = GroupOffsetStore.open(dataDirectory, GROUP_BUDGET);
        store.commit(GROUP, TOPIC, 0, 5L);
        store.commit(GROUP.toUpperCase(Locale.ROOT), TOPIC, 1, 7L);

        GroupOffsetStore reopened = GroupOffsetStore.open(dataDirectory, GROUP_BUDGET);

        assertEquals(OptionalLong.of(5L), reopened.committedOffset(GROUP, TOPIC, 0),
                "the second casing committed a second partition, it did not replace the file of the first");
        assertEquals(OptionalLong.of(7L), reopened.committedOffset(GROUP, TOPIC, 1));
        assertEquals(OptionalLong.of(5L), reopened.committedOffset("ReAdErS", TOPIC, 0),
                "and every casing asks about the same group");
    }

    /**
     * A topic name is folded for the same reason a group id is: the broker holds one topic per folded
     * name, so a commit and the fetch that follows it must agree about which key they mean.
     */
    @Test
    void keepsOneCommittedOffsetForTopicNamesThatDifferOnlyInCase() {
        GroupOffsetStore store = GroupOffsetStore.open(dataDirectory, GROUP_BUDGET);
        store.commit(GROUP, TOPIC, 0, 5L);
        store.commit(GROUP, TOPIC.toUpperCase(Locale.ROOT), 0, 9L);

        GroupOffsetStore reopened = GroupOffsetStore.open(dataDirectory, GROUP_BUDGET);

        assertEquals(OptionalLong.of(9L), reopened.committedOffset(GROUP, TOPIC, 0),
                "the second commit replaced the first rather than becoming a second key");
    }

    /**
     * A data directory written before group ids were folded holds one file whose name is not the folded
     * one. Opening it renames that file, because leaving it would open today and refuse every start
     * after the next commit: the commit writes the folded name, and the two files together are the pair
     * the next open refuses.
     *
     * <p>Nothing here depends on the filesystem. A case-sensitive one performs a rename and a
     * case-insensitive one performs a rename that only changes the spelling; either way the name the
     * directory lists afterwards is the folded one, which is what this asserts.
     */
    @Test
    void renamesAGroupFileWrittenUnderAnUnfoldedNameOntoItsFoldedName() throws IOException {
        Path groups = Files.createDirectories(dataDirectory.resolve(GroupOffsetStore.DIRECTORY_NAME));
        Files.writeString(groups.resolve(SAME_GROUP_CAPITALISED + GroupOffsetStore.FILE_SUFFIX),
                GroupOffsetStore.VERSION_HEADER + "\n" + TOPIC + " 0 5\n", UTF_8);

        GroupOffsetStore store = GroupOffsetStore.open(dataDirectory, GROUP_BUDGET);

        assertEquals(List.of(GROUP + GroupOffsetStore.FILE_SUFFIX), fileNamesIn(groups),
                "the name the directory lists is the folded one, and it is the only file there");
        assertEquals(OptionalLong.of(5L), store.committedOffset(GROUP, TOPIC, 0),
                "the offsets that file held are the offsets the store holds");

        store.commit(GROUP, TOPIC, 1, 7L);

        assertEquals(List.of(GROUP + GroupOffsetStore.FILE_SUFFIX), fileNamesIn(groups),
                "a commit after the rename writes that same file rather than a second one beside it");
        GroupOffsetStore reopened = GroupOffsetStore.open(dataDirectory, GROUP_BUDGET);
        assertEquals(OptionalLong.of(5L), reopened.committedOffset(GROUP, TOPIC, 0),
                "and the next start opens rather than refusing, with everything that was committed");
        assertEquals(OptionalLong.of(7L), reopened.committedOffset(GROUP, TOPIC, 1));
    }

    /**
     * Two files that name one group are state this build never writes and cannot act on, so the open
     * says so instead of picking one. The pair can only exist on a filesystem that keeps two casings
     * apart, which is why the test asks whether it got two files before asserting anything — and asserts
     * only the refusal, never what the directory did.
     */
    @Test
    void refusesToOpenTwoGroupFilesThatDifferOnlyInCase() throws IOException {
        Path groups = Files.createDirectories(dataDirectory.resolve(GroupOffsetStore.DIRECTORY_NAME));
        Path folded = groups.resolve(GROUP + GroupOffsetStore.FILE_SUFFIX);
        Path shouted = groups.resolve(GROUP.toUpperCase(Locale.ROOT) + GroupOffsetStore.FILE_SUFFIX);
        Files.writeString(folded, GroupOffsetStore.VERSION_HEADER + "\n" + TOPIC + " 0 5\n", UTF_8);
        Files.writeString(shouted, GroupOffsetStore.VERSION_HEADER + "\n" + TOPIC + " 0 9\n", UTF_8);
        assumeTrue(Files.readString(folded, UTF_8).endsWith("0 5\n"),
                "this filesystem folds file names, so one group can never have two files on it");

        ShrikeIOException refused = assertThrows(ShrikeIOException.class,
                () -> GroupOffsetStore.open(dataDirectory, GROUP_BUDGET));

        assertTrue(refused.getMessage().contains("differ only in case"), refused.getMessage());
    }

    @Test
    void enumeratesOneGroupsCommittedOffsetsInTopicThenPartitionOrder() {
        GroupOffsetStore store = GroupOffsetStore.open(dataDirectory, GROUP_BUDGET);
        store.commit(GROUP, TOPIC, 1, 12L);
        store.commit(GROUP, OTHER_TOPIC, 0, 1L);
        store.commit(GROUP, TOPIC, 0, 5L);
        store.commit(OTHER_GROUP, TOPIC, 0, 3L);

        List<CommittedOffset> committed = store.committedOffsets(GROUP);

        assertEquals(List.of(new CommittedOffset(OTHER_TOPIC, 0, 1L), new CommittedOffset(TOPIC, 0, 5L),
                new CommittedOffset(TOPIC, 1, 12L)), committed,
                "the order is the file's: topic and then partition, whatever order the commits arrived in");
    }

    @Test
    void enumeratesNoCommittedOffsetsForAGroupThatHasNeverCommitted() {
        GroupOffsetStore store = GroupOffsetStore.open(dataDirectory, GROUP_BUDGET);
        store.commit(GROUP, TOPIC, 0, 5L);

        List<CommittedOffset> neverSeen = store.committedOffsets("nobody");

        assertEquals(List.of(), neverSeen,
                "a commit is what creates a group, so a group with no commits and no such group are one state");
    }

    @Test
    void enumeratesTheOffsetsOfAGroupIdSpelledInAnotherCasing() {
        GroupOffsetStore store = GroupOffsetStore.open(dataDirectory, GROUP_BUDGET);
        store.commit(GROUP, TOPIC, 0, 5L);

        List<CommittedOffset> committed = store.committedOffsets(SAME_GROUP_CAPITALISED);

        assertEquals(List.of(new CommittedOffset(TOPIC, 0, 5L)), committed,
                "one group has one set of offsets whichever casing asks for them");
    }

    @Test
    void refusesANegativeOffsetAndAnUnsafeName() {
        GroupOffsetStore store = GroupOffsetStore.open(dataDirectory, GROUP_BUDGET);

        assertThrows(IllegalArgumentException.class, () -> store.commit(GROUP, TOPIC, 0, -1L));
        assertThrows(IllegalArgumentException.class, () -> store.commit("../escape", TOPIC, 0, 1L));
        assertThrows(IllegalArgumentException.class, () -> store.commit(GROUP, "../escape", 0, 1L));
        assertThrows(IllegalArgumentException.class, () -> store.commit(GROUP, TOPIC, -1, 1L));
    }

    /**
     * A commit is the only thing that creates a group, and nothing removes one, so the budget is what
     * stands between a directory and anything that can send commits. The refusal names all three
     * numbers a reader of it needs: which group was asked for, how many are held, and how many may be.
     */
    @Test
    void refusesTheCommitThatWouldCreateAGroupPastTheBudgetAndWritesNothingForIt() throws IOException {
        GroupOffsetStore store = GroupOffsetStore.open(dataDirectory, TWO_GROUPS);
        store.commit(GROUP, TOPIC, 0, 5L);
        store.commit(OTHER_GROUP, TOPIC, 0, 3L);

        TooManyGroupsException refused = assertThrows(TooManyGroupsException.class,
                () -> store.commit(THIRD_GROUP, TOPIC, 0, 1L));

        assertTrue(refused.getMessage().contains(THIRD_GROUP), refused.getMessage());
        assertTrue(refused.getMessage().contains("already holds 2 of the 2"),
                "the count held and the budget are both in the sentence: " + refused.getMessage());
        assertEquals(List.of(OTHER_GROUP + GroupOffsetStore.FILE_SUFFIX, GROUP + GroupOffsetStore.FILE_SUFFIX),
                fileNamesIn(dataDirectory.resolve(GroupOffsetStore.DIRECTORY_NAME)),
                "the refusal comes before anything is written, so the directory holds only the groups it held");
        assertEquals(List.of(), GroupOffsetStore.open(dataDirectory, TWO_GROUPS).committedOffsets(THIRD_GROUP),
                "and a store reopened over that directory has never heard of the group that was refused");
    }

    @Test
    void commitsAgainForAGroupItAlreadyHoldsWhenTheBudgetIsFull() {
        GroupOffsetStore store = GroupOffsetStore.open(dataDirectory, TWO_GROUPS);
        store.commit(GROUP, TOPIC, 0, 5L);
        store.commit(OTHER_GROUP, TOPIC, 0, 3L);

        store.commit(GROUP, OTHER_TOPIC, 1, 9L);

        assertEquals(OptionalLong.of(9L), store.committedOffset(GROUP, OTHER_TOPIC, 1),
                "the budget counts groups, so a group that is already one of them commits whatever it says");
        assertEquals(OptionalLong.of(9L),
                GroupOffsetStore.open(dataDirectory, TWO_GROUPS).committedOffset(GROUP, OTHER_TOPIC, 1),
                "and that commit is on the device like any other");
    }

    /**
     * A budget somebody lowered is not a reason to strand committed offsets: every file in the directory
     * was written under the budget of the day, so all of them load and go on committing, and it is the
     * next group that pays. The same call the partition budget makes about a registry it opens over.
     */
    @Test
    void loadsEveryGroupFileWhenTheDirectoryHoldsMoreThanTheBudgetAndRefusesTheNextNewGroup() throws IOException {
        Path groups = Files.createDirectories(dataDirectory.resolve(GroupOffsetStore.DIRECTORY_NAME));
        writeGroupFile(groups, GROUP, 5L);
        writeGroupFile(groups, OTHER_GROUP, 3L);
        writeGroupFile(groups, THIRD_GROUP, 1L);

        GroupOffsetStore store = GroupOffsetStore.open(dataDirectory, TWO_GROUPS);

        assertEquals(OptionalLong.of(5L), store.committedOffset(GROUP, TOPIC, 0),
                "a broker that would not open these would be one a lowered number had turned into an outage");
        assertEquals(OptionalLong.of(3L), store.committedOffset(OTHER_GROUP, TOPIC, 0));
        assertEquals(OptionalLong.of(1L), store.committedOffset(THIRD_GROUP, TOPIC, 0));
        store.commit(THIRD_GROUP, TOPIC, 0, 4L);
        assertEquals(OptionalLong.of(4L), store.committedOffset(THIRD_GROUP, TOPIC, 0),
                "and every one of them still commits, budget or no budget");
        assertThrows(TooManyGroupsException.class, () -> store.commit("newcomers", TOPIC, 0, 1L),
                "what the budget stops is the group after them");
    }

    /**
     * @param groups the groups directory
     * @param group  the group whose file to write, under the name this build stores it under
     * @param offset the next offset to read that file records for partition 0 of {@link #TOPIC}
     * @throws IOException if it cannot be written
     */
    private static void writeGroupFile(Path groups, String group, long offset) throws IOException {
        Files.writeString(groups.resolve(group + GroupOffsetStore.FILE_SUFFIX),
                GroupOffsetStore.VERSION_HEADER + "\n" + TOPIC + " 0 " + offset + "\n", UTF_8);
    }

    /**
     * @param directory a directory
     * @return the names it lists, which on a filesystem that folds case is still the spelling each name
     *         is stored under
     * @throws IOException if it cannot be listed
     */
    private static List<String> fileNamesIn(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.map(entry -> entry.getFileName().toString()).sorted().toList();
        }
    }
}
