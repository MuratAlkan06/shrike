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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
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
 * offsets be overwritten by another's.
 *
 * <p>A file whose name is not already folded is renamed onto its folded name as the store opens, so
 * that memory and disk agree from then on: the alternative is a directory that opens today and refuses
 * every start after the first commit, because the commit would write {@code readers.offsets} beside the
 * {@code Readers.offsets} that was already there. A directory holding <em>both</em> casings at once is
 * the one case a rename cannot settle — there is no way to tell which file a group's next read should
 * trust — so it fails the open, naming both files and the repair.
 *
 * <p><strong>How many groups there may be is budgeted.</strong> A commit naming a group this store does
 * not hold creates one, and nothing removes it once it is on the device, so the budget the store is
 * opened with is what keeps anything that can send a commit from filling this directory: a commit that
 * would create a group past it raises {@link TooManyGroupsException} instead, before anything is
 * written. A commit that creates a group and fails before its file takes the group's name removes it
 * again, so what holds a place under the budget is a group with a file behind it and never a failure —
 * and a commit that fails after that name is on the device keeps both, because the file is there. A
 * commit for a group that is already here is never refused by the budget, including a group loaded from
 * a directory that already held more of them than the budget allows — that is a start, not a request,
 * and it opens with a WARN and every group it found.
 */
public final class GroupOffsetStore {

    private static final System.Logger LOGGER = System.getLogger(GroupOffsetStore.class.getName());

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

    /** The most groups a commit may create. What is already on disk is loaded whatever this says. */
    private final int maxTotalGroups;

    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Every group's committed offsets, by the fold of its group id and then by topic partition — whose
     * topic is folded too, so that a commit and a fetch of the same topic agree about which key they
     * mean whatever casing each of them spelled it in. A {@link TreeMap} inside, so a rewritten file
     * lists its keys in one order and two brokers that stored the same commits write the same bytes.
     */
    // guarded by: lock
    private final Map<String, Map<TopicPartition, Long>> offsetsByGroup = new HashMap<>();

    private GroupOffsetStore(Path groupsDirectory, int maxTotalGroups, DurableFile.StepObserver observer) {
        this.groupsDirectory = groupsDirectory;
        this.maxTotalGroups = maxTotalGroups;
        this.observer = observer;
    }

    /**
     * Opens the store under {@code dataDirectory}, creating the groups directory when it is not there
     * and loading every group file that is — including more of them than the budget allows, which is a
     * WARN and not a refusal.
     *
     * @param dataDirectory  the directory every path is derived from
     * @param maxTotalGroups the most groups a commit may create from here on
     * @return the loaded store
     * @throws IllegalArgumentException if the budget is below 1, which is a store no commit could use
     * @throws ShrikeIOException        if the directory or one of its files cannot be read
     */
    public static GroupOffsetStore open(Path dataDirectory, int maxTotalGroups) {
        return open(dataDirectory, maxTotalGroups, DurableFile.StepObserver.IGNORED);
    }

    /**
     * Opens the store with a seam watching the steps each commit takes. See
     * {@link DurableFile.StepObserver}: it exists so a test can prove that a commit returns only after
     * the file it wrote is on the device.
     *
     * @param dataDirectory  the directory every path is derived from
     * @param maxTotalGroups the most groups a commit may create from here on
     * @param observer       the seam
     * @return the loaded store
     * @throws IllegalArgumentException if the budget is below 1, which is a store no commit could use
     * @throws ShrikeIOException        if the directory or one of its files cannot be read
     */
    static GroupOffsetStore open(Path dataDirectory, int maxTotalGroups, DurableFile.StepObserver observer) {
        Objects.requireNonNull(dataDirectory, "dataDirectory");
        Objects.requireNonNull(observer, "observer");
        if (maxTotalGroups < 1) {
            throw new IllegalArgumentException("maxTotalGroups must be at least 1, but was " + maxTotalGroups);
        }

        Path groupsDirectory = dataDirectory.resolve(DIRECTORY_NAME);
        GroupOffsetStore store = new GroupOffsetStore(groupsDirectory, maxTotalGroups, observer);
        try {
            Files.createDirectories(groupsDirectory);
            store.loadEveryGroup();
        } catch (IOException e) {
            throw new ShrikeIOException("cannot open the group offsets directory " + groupsDirectory, e);
        }
        store.warnIfPastTheBudget();
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
     * @throws TooManyGroupsException   if this commit would create a group and the broker already holds
     *                                  all the groups it may. A group that is already here is never
     *                                  refused for it, and nothing is written when one is
     * @throws ShrikeIOException        if the commit cannot be made durable, in which case this store
     *                                  holds whatever the device does. A failure before the file took the
     *                                  group's name puts everything back: the offset goes to what it was,
     *                                  and a commit that had just created the group un-creates it, so a
     *                                  failure that wrote nothing takes no place under the budget. A
     *                                  failure after it keeps the offset and the group, because the file
     *                                  is there for the next start to read — and still throws, because
     *                                  nothing confirmed the write was durable
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
            Map<TopicPartition, Long> offsets = offsetsByGroup.get(group);
            boolean creatingTheGroup = offsets == null;
            if (creatingTheGroup) {
                // Asked and answered under the one lock every commit holds, which is what makes the
                // budget exact: a group is created here and nowhere else, so two commits arriving at
                // once cannot both find the last place free. Nothing has been written or changed yet
                // when this throws, so a refused commit leaves the store exactly as it found it; a
                // commit that gets past here and then fails leaves it holding what the device holds,
                // which the rollback below is what decides.
                if (offsetsByGroup.size() >= maxTotalGroups) {
                    throw new TooManyGroupsException(group, offsetsByGroup.size(), maxTotalGroups);
                }
                offsets = new TreeMap<>();
                offsetsByGroup.put(group, offsets);
            }
            Long previousOffset = offsets.put(key, offset);
            StepsTaken steps = new StepsTaken(observer);
            try {
                DurableFile.replace(fileOf(group), render(offsets), steps);
            } catch (RuntimeException e) {
                // The file is what this store is; memory that disagrees with it would answer the next
                // read something no restart over this directory could produce. So what a failure rolls
                // back is decided by the move and by nothing else: before it, the group's name has
                // nothing behind it and the whole change goes; at it, <group>.offsets is a file every
                // later reader finds, and the store keeps what that file holds even though the commit
                // still fails, because the write was never confirmed durable.
                if (!steps.hasMoved()) {
                    if (creatingTheGroup) {
                        // A group whose first commit never took its name on the device is a group with
                        // no file behind it, and leaving the entry would hold a place under the budget
                        // for the life of the process. Anything that can make a write fail — a full
                        // disk is enough — could take every place that way, one failed commit at a
                        // time, so this undoes the creation as well as the offset.
                        offsetsByGroup.remove(group);
                    } else {
                        restore(offsets, key, previousOffset);
                    }
                }
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
     * Reads every group file in the directory, having first put each one under the folded name this
     * build stores it under.
     *
     * <p>Two files whose group ids fold together fail the open, and they fail it before anything is
     * renamed, so a directory this refuses is a directory it has not touched. This build writes one file
     * per folded id, so a pair like that came from something else, and there is no way to tell which of
     * them a group's next read should trust — the wrong guess silently redelivers or silently skips
     * records. A single file that is merely spelled in another casing is a different thing: nothing is
     * ambiguous about it, so it is renamed rather than refused.
     *
     * @throws IOException       if the directory cannot be listed
     * @throws ShrikeIOException if a file that is ours cannot be renamed, read, or parsed, or shares a
     *                           folded group id with another
     */
    private void loadEveryGroup() throws IOException {
        Map<String, String> fileNamesByGroup = new HashMap<>();
        for (String fileName : ourFileNames()) {
            String group = SafeName.fold(fileName.substring(0, fileName.length() - FILE_SUFFIX.length()));
            String sameGroup = fileNamesByGroup.put(group, fileName);
            if (sameGroup != null) {
                throw twoFilesOneGroup(group, sameGroup, fileName);
            }
        }

        for (Map.Entry<String, String> group : fileNamesByGroup.entrySet()) {
            Path file = foldOnDisk(group.getKey(), group.getValue());
            offsetsByGroup.put(group.getKey(), parse(file, group.getKey()));
        }
    }

    /**
     * Says out loud that this directory already holds more groups than the configured budget, which is
     * what a lowered budget looks like from the inside. Every group that was there is loaded — see
     * {@link #open(Path, int)} — and the next commit that would create a new one is what pays for it.
     *
     * <p>A WARN because this is a fact about the data directory a broker was started over rather than
     * an answer to anybody's request: nobody is being refused yet, and the operator who lowered the
     * number is the one who needs to read it.
     *
     * <p>It reads {@link #offsetsByGroup} without taking {@link #lock}, which is the one place that
     * does: it runs inside {@code open}, on the thread that is building the store, before any other
     * thread has a reference to it and therefore before any commit can exist.
     */
    private void warnIfPastTheBudget() {
        int held = offsetsByGroup.size();
        if (held > maxTotalGroups) {
            LOGGER.log(System.Logger.Level.WARNING, () -> "the committed offsets in " + groupsDirectory + " name "
                    + held + " groups, past the budget of " + maxTotalGroups + " this broker was started with; every"
                    + " one of them is loaded and can still commit, and no group beyond them is created until the"
                    + " budget is raised");
        }
    }

    /**
     * @return the names of the files in the groups directory that are this store's, sorted so that a
     *         refusal names the same two files whichever order the directory listed them in. A file
     *         whose name is not a {@link SafeName} followed by {@link #FILE_SUFFIX} is not one this
     *         store wrote, so it is left alone rather than parsed
     * @throws IOException if the directory cannot be listed
     */
    private List<String> ourFileNames() throws IOException {
        List<String> fileNames = new ArrayList<>();
        // Listed to the end before anything is renamed: what a directory stream shows for an entry that
        // is renamed while the stream is open is a question POSIX does not answer.
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(groupsDirectory)) {
            for (Path entry : entries) {
                String fileName = entry.getFileName().toString();
                if (!fileName.endsWith(FILE_SUFFIX)) {
                    continue;
                }
                if (SafeName.isValid(fileName.substring(0, fileName.length() - FILE_SUFFIX.length()))) {
                    fileNames.add(fileName);
                }
            }
        }
        Collections.sort(fileNames);
        return fileNames;
    }

    /**
     * Renames a group file whose name is not the folded one onto the folded one, durably, so that the
     * file this store reads and the file its next commit writes are the same file. Without it, a
     * directory holding {@code Readers.offsets} opens, commits once — writing {@code readers.offsets}
     * beside it — and refuses every start after that.
     *
     * <p>The pair a rename could not settle has already failed the open, so the folded name is one
     * nothing else in this directory claims.
     *
     * @param group    the folded group id
     * @param fileName the name the file has now
     * @return the file this group's offsets are read from
     * @throws ShrikeIOException if the rename cannot be made durable
     */
    private Path foldOnDisk(String group, String fileName) {
        Path file = groupsDirectory.resolve(fileName);
        Path folded = fileOf(group);
        if (file.equals(folded)) {
            return file;
        }

        // A file this build renamed is a fact about somebody's data directory, so it is said out loud
        // rather than done quietly.
        LOGGER.log(System.Logger.Level.WARNING, () -> "renaming " + file + " to " + folded + ": a group id is"
                + " case-insensitive because the file it names is, so one group's committed offsets live under one"
                + " folded file name");
        DurableFile.rename(file, folded);
        return folded;
    }

    /**
     * @param group  the folded id both files claim
     * @param first  one file's name
     * @param second the other's
     * @return the refusal, which names the repair because only a human can make it: which of the two
     *         files holds the offsets this group should resume from is not a question the bytes answer
     */
    private ShrikeIOException twoFilesOneGroup(String group, String first, String second) {
        return new ShrikeIOException("the committed offsets in " + groupsDirectory + " name one group twice: "
                + first + " and " + second + " differ only in case, and a group id is case-insensitive because the"
                + " file it names is; to repair it by hand, keep whichever of the two holds the offsets this group"
                + " should resume from, name it " + group + FILE_SUFFIX + ", and remove the other",
                new IOException("two group files, one group"));
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

    /**
     * Every offset one group has committed, as a snapshot taken under {@link #lock}.
     *
     * <p>The order is the {@link TreeMap}'s — topic and then partition — which is the same order that
     * group's file lists its lines in, so what this answers and what is on disk read the same way.
     *
     * <p>A group nothing has ever committed for has no entry here and no file on disk, and it is
     * answered with an empty list rather than refused: a commit creates a group, so "no such group" and
     * "a group that has committed nothing" are the same state, and there is no way to tell a caller
     * apart from the other. That is why this method has no failure of its own.
     *
     * @param groupId the group asking, which may be anything a client sent
     * @return that group's committed offsets, topic and then partition, or an empty list
     */
    public List<CommittedOffset> committedOffsets(String groupId) {
        Objects.requireNonNull(groupId, "groupId");

        lock.lock();
        try {
            Map<TopicPartition, Long> offsets = offsetsByGroup.get(SafeName.fold(groupId));
            if (offsets == null) {
                return List.of();
            }
            List<CommittedOffset> committed = new ArrayList<>(offsets.size());
            for (Map.Entry<TopicPartition, Long> entry : offsets.entrySet()) {
                committed.add(new CommittedOffset(entry.getKey().topic(), entry.getKey().partition(),
                        entry.getValue()));
            }
            return committed;
        } finally {
            lock.unlock();
        }
    }

    /**
     * How far one commit's write got, which is the only thing that says what a failure has to undo: the
     * atomic move is what gives a file the group's name, so a failure before it left nothing behind that
     * name and a failure after it left a file the next start will read.
     *
     * <p>It records the step before passing it on, and the order is the point: a step is reported once it
     * has run, so an observer that throws is standing in for a device that failed <em>after</em> that step
     * — and a move that ran is a move this store must not pretend away.
     */
    private static final class StepsTaken implements DurableFile.StepObserver {

        private final DurableFile.StepObserver watching;

        // confined to: the thread inside commit that made it, which holds lock for the whole write
        private boolean hasMoved;

        StepsTaken(DurableFile.StepObserver watching) {
            this.watching = watching;
        }

        @Override
        public void completed(DurableFile.Step step) {
            if (step == DurableFile.Step.MOVED) {
                hasMoved = true;
            }
            watching.completed(step);
        }

        /**
         * @return whether the file has taken the group's name, so that what is on the device is what this
         *         commit wrote rather than what was there before it
         */
        boolean hasMoved() {
            return hasMoved;
        }
    }
}
