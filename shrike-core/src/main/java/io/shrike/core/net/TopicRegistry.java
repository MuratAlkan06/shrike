package io.shrike.core.net;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.shrike.core.log.DurableFile;
import io.shrike.core.log.LogConfig;
import io.shrike.core.log.ShrikeIOException;
import io.shrike.core.protocol.CreateTopicRequest;
import io.shrike.core.protocol.SafeName;
import io.shrike.core.time.TimeSource;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 */
final class TopicRegistry implements Closeable {

    /** The file listing every topic, directly under the data directory. */
    static final String FILE_NAME = "topics";

    /** The first line of that file. A file that does not start with it is not one of ours. */
    static final String VERSION_HEADER = "shrike.topics v1";

    private static final char FIELD_SEPARATOR = ' ';
    private static final char LINE_SEPARATOR = '\n';

    private final Path dataDirectory;
    private final Path registryFile;
    private final TimeSource timeSource;
    private final LogConfig logConfig;
    private final int maxFetchBytes;

    /** Held for the whole of a create: the check, the file rewrite, and the publish are one step. */
    private final ReentrantLock createLock = new ReentrantLock();

    /**
     * The open topics, by name.
     */
    // guarded by: createLock for every write. Reads take no lock at all, which is why this is a
    // concurrent map: a produce or a fetch must not queue behind somebody's create.
    private final Map<String, Topic> topicsByName = new ConcurrentHashMap<>();

    private TopicRegistry(Path dataDirectory, TimeSource timeSource, LogConfig logConfig, int maxFetchBytes) {
        this.dataDirectory = dataDirectory;
        this.registryFile = dataDirectory.resolve(FILE_NAME);
        this.timeSource = timeSource;
        this.logConfig = logConfig;
        this.maxFetchBytes = maxFetchBytes;
    }

    /**
     * Opens the registry under {@code dataDirectory} and, with it, every partition log of every topic
     * it lists. Each log recovers itself as it opens.
     *
     * @param dataDirectory the directory every path is derived from
     * @param timeSource    the clock that stamps appended records and times fetch waits
     * @param logConfig     the record, segment, and index sizes every partition log opens with
     * @param maxFetchBytes the most bytes of records one fetch may be answered with
     * @return the open registry
     * @throws ShrikeIOException if the file cannot be read, does not parse, or a log cannot be opened
     */
    static TopicRegistry open(Path dataDirectory, TimeSource timeSource, LogConfig logConfig, int maxFetchBytes) {
        Objects.requireNonNull(dataDirectory, "dataDirectory");
        Objects.requireNonNull(timeSource, "timeSource");
        Objects.requireNonNull(logConfig, "logConfig");

        TopicRegistry registry = new TopicRegistry(dataDirectory, timeSource, logConfig, maxFetchBytes);
        Map<String, Integer> partitionCounts = registry.readRegistryFile();
        try {
            for (Map.Entry<String, Integer> topic : partitionCounts.entrySet()) {
                registry.topicsByName.put(topic.getKey(), registry.openTopic(topic.getKey(), topic.getValue()));
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
     * @throws TopicAlreadyExistsException  if a topic by that name is already here
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
            Topic existing = topicsByName.get(name);
            if (existing != null) {
                throw new TopicAlreadyExistsException(name, existing.partitionCount());
            }

            Map<String, Integer> partitionCounts = currentPartitionCounts();
            partitionCounts.put(name, partitionCount);
            DurableFile.replace(registryFile, render(partitionCounts));

            topicsByName.put(name, openTopic(name, partitionCount));
        } finally {
            createLock.unlock();
        }
    }

    /**
     * @param name a topic name, which may be anything a client sent
     * @return the topic, or empty when the broker holds no topic by that name
     */
    Optional<Topic> topic(String name) {
        return Optional.ofNullable(topicsByName.get(name));
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
        for (Topic topic : topicsByName.values()) {
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
        for (Topic topic : topicsByName.values()) {
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

    private Topic openTopic(String name, int partitionCount) {
        List<Partition> partitions = new ArrayList<>(partitionCount);
        try {
            for (int partition = 0; partition < partitionCount; partition++) {
                partitions.add(Partition.open(dataDirectory, name, partition, timeSource, logConfig, maxFetchBytes));
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
        for (Topic topic : topicsByName.values()) {
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
        for (int line = 1; line < lines.length; line++) {
            if (lines[line].isEmpty()) {
                continue;
            }
            parseInto(partitionCounts, lines[line], line + 1);
        }
        return partitionCounts;
    }

    private void parseInto(Map<String, Integer> partitionCounts, String line, int lineNumber) {
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
