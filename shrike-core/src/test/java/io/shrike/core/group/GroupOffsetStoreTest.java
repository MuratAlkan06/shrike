package io.shrike.core.group;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.shrike.core.log.DurableFile;
import io.shrike.core.log.ShrikeIOException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
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

    @Test
    void refusesANegativeOffsetAndAnUnsafeName() {
        GroupOffsetStore store = GroupOffsetStore.open(dataDirectory);

        assertThrows(IllegalArgumentException.class, () -> store.commit(GROUP, TOPIC, 0, -1L));
        assertThrows(IllegalArgumentException.class, () -> store.commit("../escape", TOPIC, 0, 1L));
        assertThrows(IllegalArgumentException.class, () -> store.commit(GROUP, "../escape", 0, 1L));
        assertThrows(IllegalArgumentException.class, () -> store.commit(GROUP, TOPIC, -1, 1L));
    }
}
