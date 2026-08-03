package io.shrike.core.net;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.shrike.core.log.DurableFile;
import io.shrike.core.log.SafeName;
import io.shrike.core.log.ShrikeIOException;
import io.shrike.core.protocol.CreateTopicRequest;
import io.shrike.core.time.TimeSource;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Which topics exist, how many partitions each one has, and the open partition logs behind them.
 *
 * <p>The list of topics is one text file, {@code <dataDirectory>/topics}: a version header line and
 * then one {@code name partitionCount} line per topic.
 *
 * <pre>
 * shrike.topics v1
 * events 1
 * orders 4
 * </pre>
 *
 * <p>That file is the truth about which topics exist, and a create writes it through
 * {@link DurableFile} — temporary file, force, atomic move, force the directory — <em>before</em> it
 * opens a single log. A crash in that window leaves a topic listed with no directories yet, which the
 * next start creates as it opens them; the other order would leave directories nothing lists, which is
 * storage no restart accounts for.
 *
 * <p>Partition directories keep the layout the log package already uses,
 * {@code <dataDirectory>/<topic>-<partition>/}, so nothing about a topic's storage changed when the
 * broker learned to create one. The {@code -<partition>} suffix is also what keeps a topic from ever
 * colliding with {@code topics} or with the {@code groups} directory beside it.
 *
 * <p>A topic is held under {@link SafeName#fold(String)} of its name, so {@code orders} and
 * {@code Orders} are one topic: a directory name is what identifies a partition's storage, and a
 * directory name does not tell two casings apart on every filesystem. A create whose name folds onto a
 * topic that is already here is {@code TOPIC_ALREADY_EXISTS}, and a registry file listing two such
 * names fails the start rather than opening two topics over one directory.
 *
 * <p>The partitions this broker will hold open are budgeted by
 * {@link BrokerConfig#maxTotalPartitions()}, counted across every topic, because each one costs a
 * directory and two open file handles for as long as the broker runs.
 */
final class TopicRegistry implements Closeable {

    /** The file listing every topic, directly under the data directory. */
    static final String FILE_NAME = "topics";

    /** The first line of that file. A file that does not start with it is not one of ours. */
    static final String VERSION_HEADER = "shrike.topics v1";

    private static final System.Logger LOGGER = System.getLogger(TopicRegistry.class.getName());

    private static final char FIELD_SEPARATOR = ' ';
    private static final char LINE_SEPARATOR = '\n';

    private final BrokerConfig config;
    private final Path registryFile;
    private final TimeSource timeSource;

    /** Held for the whole of a create: the check, the file rewrite, and the publish are one step. */
    private final ReentrantLock createLock = new ReentrantLock();

    /**
     * The open topics, by the fold of their names. A topic keeps the spelling it was created with —
     * that is what {@link Topic#name()} holds and what the registry file lists — but it answers to any
     * casing of it, because the directory it lives in does.
     */
    // guarded by: createLock for every write. Reads take no lock at all, which is why this is a
    // concurrent map: a produce or a fetch must not queue behind somebody's create.
    private final Map<String, Topic> topicsByFoldedName = new ConcurrentHashMap<>();

    private TopicRegistry(BrokerConfig config, TimeSource timeSource) {
        this.config = config;
        this.registryFile = config.dataDirectory().resolve(FILE_NAME);
        this.timeSource = timeSource;
    }

    /**
     * Opens the registry under the configured data directory and, with it, every partition log of every
     * topic it lists. Each log recovers itself as it opens.
     *
     * <p>A registry listing more partitions than {@link BrokerConfig#maxTotalPartitions()} still opens
     * all of them: they were created under whatever budget was configured then, and refusing to start
     * would strand stored records behind a number somebody lowered. The budget stops the next create
     * instead, and a start over one says so in a WARN.
     *
     * @param config     where things live and how much of them there may be
     * @param timeSource the clock that stamps appended records and times fetch waits
     * @return the open registry
     * @throws ShrikeIOException if the file cannot be read, does not parse, lists two names that differ
     *                           only in case, or a log cannot be opened
     */
    static TopicRegistry open(BrokerConfig config, TimeSource timeSource) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(timeSource, "timeSource");

        TopicRegistry registry = new TopicRegistry(config, timeSource);
        Map<String, Integer> partitionCounts = registry.readRegistryFile();
        registry.warnIfPastTheBudget(partitionCounts);
        try {
            for (Map.Entry<String, Integer> topic : partitionCounts.entrySet()) {
                registry.topicsByFoldedName.put(SafeName.fold(topic.getKey()),
                        registry.openTopic(topic.getKey(), topic.getValue()));
            }
        } catch (RuntimeException e) {
            registry.close();
            throw e;
        }
        return registry;
    }

    /**
     * Creates a topic and opens its partitions.
     *
     * @param name           the topic name
     * @param partitionCount how many partitions it has, now and for as long as it exists
     * @throws IllegalArgumentException     if the name is not a {@link SafeName} or the count is
     *                                      outside what {@link CreateTopicRequest} allows
     * @throws TopicAlreadyExistsException  if a topic whose name folds onto this one is already here
     * @throws TooManyPartitionsException   if this create would push the broker past
     *                                      {@link BrokerConfig#maxTotalPartitions()}
     * @throws ShrikeIOException            if the registry file or a partition log cannot be written
     */
    void create(String name, int partitionCount) {
        SafeName.require(name, "name");
        if (partitionCount < CreateTopicRequest.MIN_PARTITION_COUNT
                || partitionCount > CreateTopicRequest.MAX_PARTITION_COUNT) {
            throw new IllegalArgumentException("partitionCount must be " + CreateTopicRequest.MIN_PARTITION_COUNT
                    + " to " + CreateTopicRequest.MAX_PARTITION_COUNT + ", but was " + partitionCount);
        }

        createLock.lock();
        try {
            Topic existing = topicsByFoldedName.get(SafeName.fold(name));
            if (existing != null) {
                throw new TopicAlreadyExistsException(existing.name(), existing.partitionCount());
            }
            long openPartitionCount = openPartitionCount();
            if (openPartitionCount + partitionCount > config.maxTotalPartitions()) {
                throw new TooManyPartitionsException(name, partitionCount, openPartitionCount,
                        config.maxTotalPartitions());
            }

            Map<String, Integer> partitionCounts = currentPartitionCounts();
            partitionCounts.put(name, partitionCount);
            DurableFile.replace(registryFile, render(partitionCounts));

            topicsByFoldedName.put(SafeName.fold(name), openTopic(name, partitionCount));
        } finally {
            createLock.unlock();
        }
    }

    /**
     * @param name a topic name, which may be anything a client sent
     * @return the topic, or empty when the broker holds no topic whose name folds onto this one
     */
    Optional<Topic> topic(String name) {
        return Optional.ofNullable(topicsByFoldedName.get(SafeName.fold(name)));
    }

    /**
     * @param topic     a topic name, which may be anything a client sent
     * @param partition a partition number, which may be anything a client sent
     * @return that partition, or empty when either the topic or the partition is not one the broker
     *         holds
     */
    Optional<Partition> partition(String topic, int partition) {
        return topic(topic).flatMap(found -> found.partition(partition));
    }

    /**
     * Wakes every fetch waiting on any partition, so a broker on its way down stops holding long polls
     * open.
     */
    void stopServing() {
        for (Topic topic : topicsByFoldedName.values()) {
            for (Partition partition : topic.partitions()) {
                partition.stopServing();
            }
        }
    }

    /**
     * Closes every partition log, which forces the segment each one is still writing. The first
     * failure is thrown after every other log has had its chance to close.
     *
     * @throws ShrikeIOException if a log cannot be closed
     */
    @Override
    public void close() {
        ShrikeIOException firstFailure = null;
        for (Topic topic : topicsByFoldedName.values()) {
            for (Partition partition : topic.partitions()) {
                try {
                    partition.close();
                } catch (ShrikeIOException e) {
                    if (firstFailure == null) {
                        firstFailure = e;
                    }
                }
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    /**
     * @return how many partitions this broker holds open, counted across every topic. Each one costs a
     *         directory and two open file handles, which is what the budget is about
     */
    // guarded by: createLock, the only place that asks
    private long openPartitionCount() {
        long partitions = 0L;
        for (Topic topic : topicsByFoldedName.values()) {
            partitions += topic.partitionCount();
        }
        return partitions;
    }

    /**
     * Says out loud that this data directory already holds more partitions than the configured budget,
     * which is what a lowered budget looks like from the inside. Everything listed is still opened —
     * see {@link #open(BrokerConfig, TimeSource)} — and the next create is what pays for it.
     */
    private void warnIfPastTheBudget(Map<String, Integer> partitionCounts) {
        long listedPartitionCount = 0L;
        for (int partitionCount : partitionCounts.values()) {
            listedPartitionCount += partitionCount;
        }
        long listed = listedPartitionCount;
        if (listed > config.maxTotalPartitions()) {
            LOGGER.log(System.Logger.Level.WARNING, () -> "the topic registry " + registryFile + " lists " + listed
                    + " partitions, past the budget of " + config.maxTotalPartitions() + " this broker was started"
                    + " with; every one of them is opened, and no topic can be created until the budget is raised");
        }
    }

    private Topic openTopic(String name, int partitionCount) {
        List<Partition> partitions = new ArrayList<>(partitionCount);
        try {
            for (int partition = 0; partition < partitionCount; partition++) {
                partitions.add(Partition.open(config, name, partition, timeSource));
            }
        } catch (RuntimeException e) {
            closeQuietlyAfterFailedOpen(partitions);
            throw e;
        }
        return new Topic(name, partitions);
    }

    /**
     * @return the partition count of every open topic, ordered by name so that a rewritten file lists
     *         its topics the same way whatever order they were created in
     */
    private Map<String, Integer> currentPartitionCounts() {
        Map<String, Integer> partitionCounts = new TreeMap<>();
        for (Topic topic : topicsByFoldedName.values()) {
            partitionCounts.put(topic.name(), topic.partitionCount());
        }
        return partitionCounts;
    }

    private static byte[] render(Map<String, Integer> partitionCounts) {
        StringBuilder file = new StringBuilder(VERSION_HEADER).append(LINE_SEPARATOR);
        for (Map.Entry<String, Integer> topic : partitionCounts.entrySet()) {
            file.append(topic.getKey()).append(FIELD_SEPARATOR).append(topic.getValue()).append(LINE_SEPARATOR);
        }
        return file.toString().getBytes(UTF_8);
    }

    /**
     * @return every topic the file lists, or an empty map when there is no file yet
     * @throws ShrikeIOException if the file exists but cannot be read or believed
     */
    private Map<String, Integer> readRegistryFile() {
        if (!Files.isRegularFile(registryFile)) {
            return Map.of();
        }

        String contents;
        try {
            contents = Files.readString(registryFile, UTF_8);
        } catch (IOException e) {
            throw new ShrikeIOException("cannot read the topic registry " + registryFile, e);
        } catch (UncheckedIOException e) {
            // readString reports bytes that are not UTF-8 this way, which is a file nothing here wrote.
            throw new ShrikeIOException("the topic registry " + registryFile + " is not UTF-8 text", e.getCause());
        }

        String[] lines = contents.split(String.valueOf(LINE_SEPARATOR), -1);
        if (lines.length == 0 || !VERSION_HEADER.equals(lines[0])) {
            throw unreadable(1, "the file does not start with \"" + VERSION_HEADER + "\"");
        }

        Map<String, Integer> partitionCounts = new TreeMap<>();
        Map<String, String> namesByIdentity = new HashMap<>();
        for (int line = 1; line < lines.length; line++) {
            if (lines[line].isEmpty()) {
                continue;
            }
            parseInto(partitionCounts, namesByIdentity, lines[line], line + 1);
        }
        return partitionCounts;
    }

    private void parseInto(Map<String, Integer> partitionCounts, Map<String, String> namesByIdentity, String line,
                           int lineNumber) {
        String[] fields = line.split(String.valueOf(FIELD_SEPARATOR), -1);
        if (fields.length != 2) {
            throw unreadable(lineNumber, "a line is \"name partitionCount\", but this one has " + fields.length
                    + " fields");
        }
        if (!SafeName.isValid(fields[0])) {
            throw unreadable(lineNumber, "the topic name is not one this broker writes");
        }
        int partitionCount;
        try {
            partitionCount = Integer.parseInt(fields[1]);
        } catch (NumberFormatException e) {
            throw unreadable(lineNumber, "the partition count is not a number");
        }
        if (partitionCount < CreateTopicRequest.MIN_PARTITION_COUNT
                || partitionCount > CreateTopicRequest.MAX_PARTITION_COUNT) {
            throw unreadable(lineNumber, "the partition count " + partitionCount + " is outside ["
                    + CreateTopicRequest.MIN_PARTITION_COUNT + ", " + CreateTopicRequest.MAX_PARTITION_COUNT + "]");
        }
        // Two listed names that fold onto one identity are a file this broker cannot act on: they name
        // one topic here and, on a filesystem that folds case, one directory too. Unlike a torn tail —
        // records nobody was told about — this is state a start cannot make sense of, so it says so.
        String sameIdentity = namesByIdentity.put(SafeName.fold(fields[0]), fields[0]);
        if (sameIdentity != null) {
            throw unreadable(lineNumber, "topics \"" + sameIdentity + "\" and \"" + fields[0] + "\" differ only in"
                    + " case, and a topic name is case-insensitive because the directory it names is");
        }
        partitionCounts.put(fields[0], partitionCount);
    }

    private ShrikeIOException unreadable(int lineNumber, String detail) {
        return new ShrikeIOException("the topic registry " + registryFile + " is unreadable at line " + lineNumber
                + ": " + detail, new IOException("unreadable topic registry"));
    }

    /**
     * Closes the partitions an open had already taken over before it failed, so a failed open does not
     * leak file handles. A close that fails here is dropped: the open is already failing, and the
     * exception it is failing with is the useful one.
     */
    private static void closeQuietlyAfterFailedOpen(List<Partition> partitions) {
        for (Partition partition : partitions) {
            try {
                partition.close();
            } catch (ShrikeIOException e) {
                // Dropped on purpose: see above.
            }
        }
    }
}
