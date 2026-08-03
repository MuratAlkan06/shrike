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
    private static final String TOPIC = "orders";
    private static final String OTHER_TOPIC = "events";

    @TempDir
    Path dataDirectory;

    @Test
    void readsBackEveryCommittedOffsetAfterTheStoreIsReopened() {
        GroupOffsetStore store = GroupOffsetStore.open(dataDirectory);
        store.commit(GROUP, TOPIC, 0, 5L);
        store.commit(GROUP, TOPIC, 1, 12L);
        store.commit(GROUP, OTHER_TOPIC, 0, 1L);
        store.commit(OTHER_GROUP, TOPIC, 0, 3L);

        GroupOffsetStore reopened = GroupOffsetStore.open(dataDirectory);

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
        GroupOffsetStore store = GroupOffsetStore.open(dataDirectory);
        store.commit(GROUP, TOPIC, 0, 5L);
        store.commit(GROUP, TOPIC, 0, 9L);

        GroupOffsetStore reopened = GroupOffsetStore.open(dataDirectory);

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
        GroupOffsetStore store = GroupOffsetStore.open(dataDirectory, steps::add);

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
        GroupOffsetStore.open(dataDirectory);

        assertTrue(Files.isDirectory(dataDirectory.resolve(GroupOffsetStore.DIRECTORY_NAME)),
                "every path is derived from the data directory that was injected");
    }

    @Test
    void refusesToOpenAGroupFileItDidNotWrite() throws IOException {
        Path groups = Files.createDirectories(dataDirectory.resolve(GroupOffsetStore.DIRECTORY_NAME));
        Files.writeString(groups.resolve(GROUP + GroupOffsetStore.FILE_SUFFIX), "not a header at all\n", UTF_8);

        ShrikeIOException refused = assertThrows(ShrikeIOException.class, () -> GroupOffsetStore.open(dataDirectory));

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
        GroupOffsetStore store = GroupOffsetStore.open(dataDirectory);
        store.commit(GROUP, TOPIC, 0, 5L);
        store.commit(GROUP.toUpperCase(Locale.ROOT), TOPIC, 1, 7L);

        GroupOffsetStore reopened = GroupOffsetStore.open(dataDirectory);

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
        GroupOffsetStore store = GroupOffsetStore.open(dataDirectory);
        store.commit(GROUP, TOPIC, 0, 5L);
        store.commit(GROUP, TOPIC.toUpperCase(Locale.ROOT), 0, 9L);

        GroupOffsetStore reopened = GroupOffsetStore.open(dataDirectory);

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

        GroupOffsetStore store = GroupOffsetStore.open(dataDirectory);

        assertEquals(List.of(GROUP + GroupOffsetStore.FILE_SUFFIX), fileNamesIn(groups),
                "the name the directory lists is the folded one, and it is the only file there");
        assertEquals(OptionalLong.of(5L), store.committedOffset(GROUP, TOPIC, 0),
                "the offsets that file held are the offsets the store holds");

        store.commit(GROUP, TOPIC, 1, 7L);

        assertEquals(List.of(GROUP + GroupOffsetStore.FILE_SUFFIX), fileNamesIn(groups),
                "a commit after the rename writes that same file rather than a second one beside it");
        GroupOffsetStore reopened = GroupOffsetStore.open(dataDirectory);
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

        ShrikeIOException refused = assertThrows(ShrikeIOException.class, () -> GroupOffsetStore.open(dataDirectory));

        assertTrue(refused.getMessage().contains("differ only in case"), refused.getMessage());
    }

    @Test
    void refusesANegativeOffsetAndAnUnsafeName() {
        GroupOffsetStore store = GroupOffsetStore.open(dataDirectory);

        assertThrows(IllegalArgumentException.class, () -> store.commit(GROUP, TOPIC, 0, -1L));
        assertThrows(IllegalArgumentException.class, () -> store.commit("../escape", TOPIC, 0, 1L));
        assertThrows(IllegalArgumentException.class, () -> store.commit(GROUP, "../escape", 0, 1L));
        assertThrows(IllegalArgumentException.class, () -> store.commit(GROUP, TOPIC, -1, 1L));
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
