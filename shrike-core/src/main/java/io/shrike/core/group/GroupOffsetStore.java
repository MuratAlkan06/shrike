package io.shrike.core.group;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.shrike.core.log.DurableFile;
import io.shrike.core.log.SafeName;
import io.shrike.core.log.ShrikeIOException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Where each consumer group's committed offsets live: one text file per group under
 * {@code <dataDirectory>/groups/<groupId>.offsets}, keyed by group, topic, and partition.
 *
 * <p><strong>A committed offset is the next offset to read, not the last one read.</strong> A group
 * that has consumed offsets 0 through 4 commits 5, and a group that has consumed nothing commits
 * nothing at all rather than committing -1. Every number in this class, on the wire, and in the file
 * means that same thing.
 *
 * <p>A file is one version-header line and then one {@code topic partition offset} line per key:
 *
 * <pre>
 * shrike.group.offsets v1
 * orders 0 5
 * orders 1 12
 * </pre>
 *
 * <p>Durability: a commit rewrites the group's whole file through {@link DurableFile} — temporary
 * file in the same directory, force, atomic move, force the directory — and only then returns. The
 * broker answers the client after that, so a committed offset that was acknowledged is on the device.
 * That is a stronger promise than a produce makes today, and deliberately so: a lost commit silently
 * redelivers records, while a lost produce is a record the producer was never told about.
 *
 * <p>One lock covers every group, and a commit holds it across the fsync. Commits are rare next to
 * produces and fetches, and one lock is a guard that can be named in a sentence; a lock per group,
 * created as group files appear, would be a second thing to get right for a contention that does not
 * exist yet.
 *
 * <p><strong>Names are folded.</strong> A group id is a file name and a topic is a directory name, and
 * a file name does not tell two casings apart on every filesystem, so both go through
 * {@link SafeName#fold(String)} before they are used as an identity or as a path. Group {@code readers}
 * and group {@code Readers} are one group with one file, rather than two groups whose files are the
 * same file on APFS and two files on ext4 — which is what would let one group's whole set of committed
 * offsets be overwritten by another's. A directory holding two files that fold together was written by
 * something other than this build, and it fails the open rather than picking one.
 */
public final class GroupOffsetStore {

    /** The directory holding every group's file, created when the store opens. */
    public static final String DIRECTORY_NAME = "groups";

    /** The suffix of one group's file; what comes before it is the group id. */
    public static final String FILE_SUFFIX = ".offsets";

    /** The first line of every file. A file that does not start with it is not one of ours. */
    static final String VERSION_HEADER = "shrike.group.offsets v1";

    private static final char FIELD_SEPARATOR = ' ';
    private static final char LINE_SEPARATOR = '\n';

    private final Path groupsDirectory;
    private final DurableFile.StepObserver observer;

    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Every group's committed offsets, by the fold of its group id and then by topic partition — whose
     * topic is folded too, so that a commit and a fetch of the same topic agree about which key they
     * mean whatever casing each of them spelled it in. A {@link TreeMap} inside, so a rewritten file
     * lists its keys in one order and two brokers that stored the same commits write the same bytes.
     */
    // guarded by: lock
    private final Map<String, Map<TopicPartition, Long>> offsetsByGroup = new HashMap<>();

    private GroupOffsetStore(Path groupsDirectory, DurableFile.StepObserver observer) {
        this.groupsDirectory = groupsDirectory;
        this.observer = observer;
    }

    /**
     * Opens the store under {@code dataDirectory}, creating the groups directory when it is not there
     * and loading every group file that is.
     *
     * @param dataDirectory the directory every path is derived from
     * @return the loaded store
     * @throws ShrikeIOException if the directory or one of its files cannot be read
     */
    public static GroupOffsetStore open(Path dataDirectory) {
        return open(dataDirectory, DurableFile.StepObserver.IGNORED);
    }

    /**
     * Opens the store with a seam watching the steps each commit takes. See
     * {@link DurableFile.StepObserver}: it exists so a test can prove that a commit returns only after
     * the file it wrote is on the device.
     *
     * @param dataDirectory the directory every path is derived from
     * @param observer      the seam
     * @return the loaded store
     * @throws ShrikeIOException if the directory or one of its files cannot be read
     */
    static GroupOffsetStore open(Path dataDirectory, DurableFile.StepObserver observer) {
        Objects.requireNonNull(dataDirectory, "dataDirectory");
        Objects.requireNonNull(observer, "observer");

        Path groupsDirectory = dataDirectory.resolve(DIRECTORY_NAME);
        GroupOffsetStore store = new GroupOffsetStore(groupsDirectory, observer);
        try {
            Files.createDirectories(groupsDirectory);
            store.loadEveryGroup();
        } catch (IOException e) {
            throw new ShrikeIOException("cannot open the group offsets directory " + groupsDirectory, e);
        }
        return store;
    }

    /**
     * Stores one group's next offset to read for one partition, replacing whatever was there.
     *
     * <p>Returns only after the group's file has been written, forced, moved into place, and its
     * directory forced. A caller may tell the client the commit is stored the moment this returns.
     *
     * @param groupId   the group committing, folded to its identity before it is stored
     * @param topic     the topic the offset belongs to, folded the same way
     * @param partition the partition of that topic
     * @param offset    the next offset that group should read
     * @throws IllegalArgumentException if a name is not a {@link SafeName}, if the partition is
     *                                  negative, or if the offset is negative
     * @throws ShrikeIOException        if the commit cannot be made durable
     */
    public void commit(String groupId, String topic, int partition, long offset) {
        SafeName.require(groupId, "groupId");
        SafeName.require(topic, "topic");
        requireNotNegative(partition, "partition");
        requireNotNegative(offset, "offset");

        String group = SafeName.fold(groupId);
        TopicPartition key = new TopicPartition(SafeName.fold(topic), partition);

        lock.lock();
        try {
            Map<TopicPartition, Long> offsets = offsetsByGroup.computeIfAbsent(group, id -> new TreeMap<>());
            Long previousOffset = offsets.put(key, offset);
            try {
                DurableFile.replace(fileOf(group), render(offsets), observer);
            } catch (RuntimeException e) {
                // The file is what this store is; memory that disagrees with it would answer the next
                // read with an offset no restart could produce.
                restore(offsets, key, previousOffset);
                throw e;
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * @param groupId   the group asking
     * @param topic     the topic
     * @param partition the partition of that topic
     * @return the next offset that group should read, or empty when it has committed nothing for this
     *         partition
     */
    public OptionalLong committedOffset(String groupId, String topic, int partition) {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(topic, "topic");

        lock.lock();
        try {
            Map<TopicPartition, Long> offsets = offsetsByGroup.get(SafeName.fold(groupId));
            if (offsets == null) {
                return OptionalLong.empty();
            }
            Long offset = offsets.get(new TopicPartition(SafeName.fold(topic), partition));
            return offset == null ? OptionalLong.empty() : OptionalLong.of(offset);
        } finally {
            lock.unlock();
        }
    }

    private static void restore(Map<TopicPartition, Long> offsets, TopicPartition key, Long previousOffset) {
        if (previousOffset == null) {
            offsets.remove(key);
        } else {
            offsets.put(key, previousOffset);
        }
    }

    /**
     * @param group a group id that has already been folded, which is what keeps one group to one file
     * @return that group's file
     */
    private Path fileOf(String group) {
        return groupsDirectory.resolve(group + FILE_SUFFIX);
    }

    /**
     * @param offsets one group's offsets
     * @return the whole file, header included
     */
    private static byte[] render(Map<TopicPartition, Long> offsets) {
        StringBuilder file = new StringBuilder(VERSION_HEADER).append(LINE_SEPARATOR);
        for (Map.Entry<TopicPartition, Long> entry : offsets.entrySet()) {
            file.append(entry.getKey().topic()).append(FIELD_SEPARATOR)
                    .append(entry.getKey().partition()).append(FIELD_SEPARATOR)
                    .append(entry.getValue()).append(LINE_SEPARATOR);
        }
        return file.toString().getBytes(UTF_8);
    }

    /**
     * Reads every group file in the directory. A file whose name is not a {@link SafeName} followed by
     * {@link #FILE_SUFFIX} is not one this store wrote, so it is left alone rather than parsed.
     *
     * <p>Two files whose group ids fold together fail the open. This build writes one file per folded
     * id, so a pair like that came from something else, and there is no way to tell which of them a
     * group's next read should trust — the wrong guess silently redelivers or silently skips records.
     *
     * @throws IOException       if the directory cannot be listed
     * @throws ShrikeIOException if a file that is ours cannot be read, does not parse, or shares a
     *                           folded group id with another
     */
    private void loadEveryGroup() throws IOException {
        Map<String, String> fileNamesByGroup = new HashMap<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(groupsDirectory)) {
            for (Path entry : entries) {
                String fileName = entry.getFileName().toString();
                if (!fileName.endsWith(FILE_SUFFIX)) {
                    continue;
                }
                String groupId = fileName.substring(0, fileName.length() - FILE_SUFFIX.length());
                if (!SafeName.isValid(groupId)) {
                    continue;
                }
                String group = SafeName.fold(groupId);
                String sameGroup = fileNamesByGroup.put(group, fileName);
                if (sameGroup != null) {
                    throw new ShrikeIOException("the committed offsets in " + groupsDirectory + " name one group twice:"
                            + " " + sameGroup + " and " + fileName + " differ only in case, and a group id is"
                            + " case-insensitive because the file it names is",
                            new IOException("two group files, one group"));
                }
                offsetsByGroup.put(group, parse(entry, group));
            }
        }
    }

    /**
     * @param file    one group's file
     * @param groupId the group it belongs to, for the message when it does not parse
     * @return the offsets it holds
     * @throws ShrikeIOException if the file cannot be read, or holds a line this build cannot believe
     */
    private static Map<TopicPartition, Long> parse(Path file, String groupId) {
        String contents;
        try {
            contents = Files.readString(file, UTF_8);
        } catch (IOException e) {
            throw new ShrikeIOException("cannot read the committed offsets of group " + groupId + " from " + file, e);
        } catch (UncheckedIOException e) {
            // readString reports bytes that are not UTF-8 this way, which is a file nothing here wrote.
            throw new ShrikeIOException("the committed offsets of group " + groupId + " in " + file
                    + " are not UTF-8 text", e.getCause());
        }

        String[] lines = contents.split(String.valueOf(LINE_SEPARATOR), -1);
        if (lines.length == 0 || !VERSION_HEADER.equals(lines[0])) {
            throw new ShrikeIOException("the committed offsets of group " + groupId + " in " + file
                    + " do not start with \"" + VERSION_HEADER + "\"", new IOException("unknown file version"));
        }

        Map<TopicPartition, Long> offsets = new TreeMap<>();
        for (int line = 1; line < lines.length; line++) {
            if (lines[line].isEmpty()) {
                continue;
            }
            parseInto(offsets, lines[line], line + 1, file, groupId);
        }
        return offsets;
    }

    private static void parseInto(Map<TopicPartition, Long> offsets, String line, int lineNumber, Path file,
                                  String groupId) {
        String[] fields = line.split(String.valueOf(FIELD_SEPARATOR), -1);
        if (fields.length != 3) {
            throw unreadable(file, groupId, lineNumber, "a line is \"topic partition offset\", but this one has "
                    + fields.length + " fields");
        }
        if (!SafeName.isValid(fields[0])) {
            throw unreadable(file, groupId, lineNumber, "the topic is not a name this broker writes");
        }
        int partition;
        long offset;
        try {
            partition = Integer.parseInt(fields[1]);
            offset = Long.parseLong(fields[2]);
        } catch (NumberFormatException e) {
            throw unreadable(file, groupId, lineNumber, "the partition and the offset must both be numbers");
        }
        if (partition < 0 || offset < 0) {
            throw unreadable(file, groupId, lineNumber, "partition " + partition + " and offset " + offset
                    + " must both be zero or higher");
        }
        TopicPartition key = new TopicPartition(SafeName.fold(fields[0]), partition);
        if (offsets.containsKey(key)) {
            throw unreadable(file, groupId, lineNumber, "topic \"" + fields[0] + "\" is listed twice for partition "
                    + partition + " under names that differ only in case, and this build cannot tell which offset is"
                    + " the current one");
        }
        offsets.put(key, offset);
    }

    private static ShrikeIOException unreadable(Path file, String groupId, int lineNumber, String detail) {
        return new ShrikeIOException("the committed offsets of group " + groupId + " in " + file + " are unreadable at"
                + " line " + lineNumber + ": " + detail, new IOException("unreadable group offsets"));
    }

    private static void requireNotNegative(long value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " must not be negative, but was " + value);
        }
    }
}
