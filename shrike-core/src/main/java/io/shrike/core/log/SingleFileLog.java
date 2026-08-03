package io.shrike.core.log;

import io.shrike.core.time.TimeSource;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A {@link Log} that keeps every record of one topic partition in one file:
 * {@code <dataDirectory>/<topic>-<partition>/00000000000000000000.log}. The file name is the
 * 20-digit base offset a segmented log would use, so today's files stay readable once segments
 * arrive; this class never rolls a second file and knows nothing about segments.
 *
 * <p>Durability: an append hands its bytes to the operating system and returns. Nothing is forced to
 * the device until {@link #close()}, which forces the file before closing it. The log therefore
 * promises ordering and integrity, not that an acknowledged record survives a power cut; a flush
 * policy is a later slice.
 *
 * <p>Reopening: the log refuses to open a file that already exists. Reading an existing file back
 * would mean scanning it for the last intact record, and that recovery scan is a later slice; a
 * class that cannot do it should say so rather than guess.
 */
public final class SingleFileLog implements Log {

    /**
     * {@code max.record.bytes}: the most bytes one record may occupy on disk, framing included.
     * A read allocates at most this much for a frame, so the append side and the read side agree on
     * one number.
     */
    public static final int DEFAULT_MAX_RECORD_BYTES = 1024 * 1024;

    /** Topics name a directory under the data directory, so their characters are restricted. */
    private static final Pattern LEGAL_TOPIC = Pattern.compile("[a-zA-Z0-9._-]{1,249}");

    private static final long FIRST_OFFSET = 0L;
    private static final String LOG_FILE_NAME = "%020d.log".formatted(FIRST_OFFSET);

    private final String topic;
    private final int partition;
    private final Path logFile;
    private final FileChannel channel;
    private final TimeSource timeSource;
    private final int maxRecordBytes;

    /**
     * The byte position of each offset's frame, indexed by offset. It lives in memory only: the log
     * refuses to reopen an existing file, so there is nothing to rebuild it from and nothing lost by
     * not persisting it. A persistent offset index is a later slice.
     */
    // confined to: the single thread that owns this log
    private final List<Long> positionsByOffset = new ArrayList<>();

    private SingleFileLog(String topic, int partition, Path logFile, FileChannel channel, TimeSource timeSource,
                          int maxRecordBytes) {
        this.topic = topic;
        this.partition = partition;
        this.logFile = logFile;
        this.channel = channel;
        this.timeSource = timeSource;
        this.maxRecordBytes = maxRecordBytes;
    }

    /**
     * Creates the log file for one topic partition under {@code dataDirectory}, with
     * {@link #DEFAULT_MAX_RECORD_BYTES} as the record size bound.
     *
     * @param dataDirectory the directory every path is derived from
     * @param topic         the topic name
     * @param partition     the partition number, zero or higher
     * @param timeSource    the clock that stamps appended records
     * @return the open log
     */
    public static SingleFileLog open(Path dataDirectory, String topic, int partition, TimeSource timeSource) {
        return open(dataDirectory, topic, partition, timeSource, DEFAULT_MAX_RECORD_BYTES);
    }

    /**
     * Creates the log file for one topic partition under {@code dataDirectory}.
     *
     * @param dataDirectory  the directory every path is derived from
     * @param topic          the topic name
     * @param partition      the partition number, zero or higher
     * @param timeSource     the clock that stamps appended records
     * @param maxRecordBytes the most bytes one record may occupy on disk, framing included
     * @return the open log
     * @throws IllegalArgumentException if the topic could name something other than one directory,
     *                                  if the partition is negative, or if the bound is too small to
     *                                  hold any record
     * @throws ShrikeIOException        if the file cannot be created, including when it already exists
     */
    public static SingleFileLog open(Path dataDirectory, String topic, int partition, TimeSource timeSource,
                                     int maxRecordBytes) {
        Objects.requireNonNull(dataDirectory, "dataDirectory");
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(timeSource, "timeSource");
        if (!LEGAL_TOPIC.matcher(topic).matches() || ".".equals(topic) || "..".equals(topic)) {
            throw new IllegalArgumentException("topic must match " + LEGAL_TOPIC.pattern()
                    + " so that it names one directory inside the data directory, but was: " + topic);
        }
        if (partition < 0) {
            throw new IllegalArgumentException("partition must not be negative, but was " + partition);
        }
        long smallestPossibleRecordBytes = RecordFrame.frameBytes(RecordFrame.NULL_KEY_LENGTH, 0);
        if (maxRecordBytes < smallestPossibleRecordBytes) {
            throw new IllegalArgumentException("maxRecordBytes must be at least " + smallestPossibleRecordBytes
                    + ", the size of an empty record's frame, but was " + maxRecordBytes);
        }

        Path partitionDirectory = dataDirectory.resolve(topic + "-" + partition);
        Path logFile = partitionDirectory.resolve(LOG_FILE_NAME);
        try {
            Files.createDirectories(partitionDirectory);
            FileChannel channel = FileChannel.open(logFile, StandardOpenOption.CREATE_NEW, StandardOpenOption.READ,
                    StandardOpenOption.WRITE);
            return new SingleFileLog(topic, partition, logFile, channel, timeSource, maxRecordBytes);
        } catch (FileAlreadyExistsException e) {
            throw new ShrikeIOException("log file " + logFile
                    + " already exists; reading an existing log back needs a recovery scan, which is a later slice", e);
        } catch (IOException e) {
            throw new ShrikeIOException("cannot create log file " + logFile, e);
        }
    }

    @Override
    public String topic() {
        return topic;
    }

    @Override
    public int partition() {
        return partition;
    }

    @Override
    public long nextOffset() {
        return positionsByOffset.size();
    }

    @Override
    public long append(ProducedRecord record) {
        Objects.requireNonNull(record, "record");
        byte[] value = Objects.requireNonNull(record.value(),
                "record value: a null value would be a tombstone, and compaction is a non-goal");
        byte[] key = record.key();

        int keyLength = key == null ? RecordFrame.NULL_KEY_LENGTH : key.length;
        long recordBytes = RecordFrame.frameBytes(keyLength, value.length);
        if (recordBytes > maxRecordBytes) {
            throw new RecordTooLargeException(topic, partition, recordBytes, maxRecordBytes);
        }

        long offset = nextOffset();
        ByteBuffer frame = RecordFrame.encode(offset, timeSource.currentTimeMillis(), key, value);
        try {
            long positionBytes = channel.position();
            ByteChannels.writeFully(channel, frame);
            positionsByOffset.add(positionBytes);
            return offset;
        } catch (IOException e) {
            throw new ShrikeIOException("cannot append offset " + offset + " to " + logFile, e);
        }
    }

    @Override
    public StoredRecord read(long offset) {
        if (offset < FIRST_OFFSET || offset >= nextOffset()) {
            throw new OffsetOutOfRangeException(topic, partition, offset, FIRST_OFFSET, nextOffset());
        }

        long positionBytes = positionsByOffset.get((int) offset);
        RecordLocation location = new RecordLocation(topic, partition, offset, positionBytes, logFile);
        try {
            ByteBuffer lengthField = ByteBuffer.allocate(RecordFrame.LENGTH_FIELD_BYTES);
            ByteChannels.readFully(channel, lengthField, positionBytes);
            if (lengthField.hasRemaining()) {
                throw new CorruptRecordException(location, "the file ends inside the length field");
            }
            int lengthBytes = lengthField.flip().getInt();

            // Range-check before allocating: the length is the one field no checksum can vouch for,
            // so a corrupt or hostile value must be refused rather than believed and sized to.
            int largestLengthBytes = maxRecordBytes - RecordFrame.LENGTH_FIELD_BYTES;
            if (lengthBytes < RecordFrame.MINIMUM_LENGTH_BYTES || lengthBytes > largestLengthBytes) {
                throw new CorruptRecordException(location, "frame length " + lengthBytes + " is outside ["
                        + RecordFrame.MINIMUM_LENGTH_BYTES + ", " + largestLengthBytes + "]");
            }

            ByteBuffer body = ByteBuffer.allocate(lengthBytes);
            ByteChannels.readFully(channel, body, positionBytes + RecordFrame.LENGTH_FIELD_BYTES);
            if (body.hasRemaining()) {
                throw new CorruptRecordException(location, "the file holds " + body.position() + " of the "
                        + lengthBytes + " bytes the frame declares");
            }

            return RecordFrame.decode(body.flip(), location);
        } catch (IOException e) {
            throw new ShrikeIOException("cannot read offset " + offset + " at position " + positionBytes
                    + " from " + logFile, e);
        }
    }

    /**
     * Forces the file to the device and closes it. Calling this twice is harmless.
     */
    @Override
    public void close() {
        if (!channel.isOpen()) {
            return;
        }
        try (FileChannel closing = channel) {
            closing.force(true);
        } catch (IOException e) {
            throw new ShrikeIOException("cannot close log file " + logFile, e);
        }
    }
}
