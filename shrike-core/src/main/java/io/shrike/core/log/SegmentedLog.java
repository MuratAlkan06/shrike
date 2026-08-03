package io.shrike.core.log;

import io.shrike.core.time.TimeSource;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A {@link Log} that stores one topic partition as a sequence of segment files under
 * {@code <dataDirectory>/<topic>-<partition>/}. Each segment is named after the offset of its first
 * record: {@code 00000000000000000000.log} beside {@code 00000000000000000000.index}. Files that do
 * not follow that 20-digit name are not segments and are left alone.
 *
 * <p>Rolling: a record that would push a non-empty active segment past {@code segment.bytes} starts a
 * new segment instead, and that segment's base offset is the record's own offset. An empty active
 * segment accepts any record {@code max.record.bytes} allows, even one larger than
 * {@code segment.bytes}, because a record no segment would take could never be stored at all.
 *
 * <p>Durability: an append hands its bytes to the operating system and returns. A segment is forced
 * when it is sealed — which happens the moment a roll leaves it behind — and the active segment is
 * forced by {@link #close()}. So the log promises ordering and integrity, plus that a sealed segment
 * reached the device before the next one took a record; it does not promise that an acknowledged
 * record survives a power cut. A flush policy is a later slice.
 *
 * <p>Recovery: opening an existing partition directory recovers it. Every segment but the last was
 * forced before it was sealed, so only the last one is walked frame by frame; whatever follows its
 * last whole frame is truncated and logged. Sealed segments are not repaired: damage inside one is
 * reported as a {@link CorruptRecordException} when the damaged record is read, and its neighbours
 * still read.
 *
 * <p>A log has a single writer. Nothing here is safe to call from two threads at once.
 */
public final class SegmentedLog implements Log, LogStatistics {

    /** Topics name a directory under the data directory, so their characters are restricted. */
    private static final Pattern LEGAL_TOPIC = Pattern.compile("[a-zA-Z0-9._-]{1,249}");

    /** The name of a segment's log file: its base offset, zero-padded to 20 digits. */
    private static final Pattern SEGMENT_LOG_FILE_NAME = Pattern.compile("(\\d{20})\\.log");

    private static final long FIRST_OFFSET = 0L;

    private final String topic;
    private final int partition;
    private final Path directory;
    private final TimeSource timeSource;
    private final LogConfig config;

    /**
     * The segments in base-offset order; the last one is the active segment, the only one that takes
     * appends. Rolling appends to this list and nothing ever removes from it, because retention is a
     * later slice.
     */
    // confined to: the single thread that owns this log
    private final List<LogSegment> segments;

    private SegmentedLog(String topic, int partition, Path directory, TimeSource timeSource, LogConfig config,
                         List<LogSegment> segments) {
        this.topic = topic;
        this.partition = partition;
        this.directory = directory;
        this.timeSource = timeSource;
        this.config = config;
        this.segments = segments;
    }

    /**
     * Opens the log of one topic partition under {@code dataDirectory} with {@link LogConfig#defaults()}.
     *
     * @param dataDirectory the directory every path is derived from
     * @param topic         the topic name
     * @param partition     the partition number, zero or higher
     * @param timeSource    the clock that stamps appended records
     * @return the open log
     */
    public static SegmentedLog open(Path dataDirectory, String topic, int partition, TimeSource timeSource) {
        return open(dataDirectory, topic, partition, timeSource, LogConfig.defaults());
    }

    /**
     * Opens the log of one topic partition under {@code dataDirectory}, creating its directory and
     * first segment when there is nothing there yet and recovering what is there when there is.
     *
     * @param dataDirectory the directory every path is derived from
     * @param topic         the topic name
     * @param partition     the partition number, zero or higher
     * @param timeSource    the clock that stamps appended records
     * @param config        the record, segment, and index sizes to open with
     * @return the open, recovered log
     * @throws IllegalArgumentException if the topic could name something other than one directory, or
     *                                  if the partition is negative
     * @throws ShrikeIOException        if the directory or its segments cannot be opened
     */
    public static SegmentedLog open(Path dataDirectory, String topic, int partition, TimeSource timeSource,
                                    LogConfig config) {
        Objects.requireNonNull(dataDirectory, "dataDirectory");
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(timeSource, "timeSource");
        Objects.requireNonNull(config, "config");
        if (!LEGAL_TOPIC.matcher(topic).matches() || ".".equals(topic) || "..".equals(topic)) {
            throw new IllegalArgumentException("topic must match " + LEGAL_TOPIC.pattern()
                    + " so that it names one directory inside the data directory, but was: " + topic);
        }
        if (partition < 0) {
            throw new IllegalArgumentException("partition must not be negative, but was " + partition);
        }

        Path directory = dataDirectory.resolve(topic + "-" + partition);
        List<LogSegment> segments = new ArrayList<>();
        try {
            Files.createDirectories(directory);
            List<Long> baseOffsets = segmentBaseOffsets(directory);
            if (baseOffsets.isEmpty()) {
                segments.add(LogSegment.create(topic, partition, directory, FIRST_OFFSET, config));
            } else {
                for (int segment = 0; segment < baseOffsets.size() - 1; segment++) {
                    segments.add(LogSegment.openSealed(topic, partition, directory, baseOffsets.get(segment), config));
                }
                long tailBaseOffset = baseOffsets.get(baseOffsets.size() - 1);
                segments.add(LogSegment.recoverTail(topic, partition, directory, tailBaseOffset, config));
            }
        } catch (IOException e) {
            closeQuietlyAfterFailedOpen(segments);
            throw new ShrikeIOException("cannot open the log directory " + directory, e);
        } catch (RuntimeException e) {
            closeQuietlyAfterFailedOpen(segments);
            throw e;
        }
        return new SegmentedLog(topic, partition, directory, timeSource, config, segments);
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
        return activeSegment().nextOffset();
    }

    @Override
    public long append(ProducedRecord record) {
        Objects.requireNonNull(record, "record");
        byte[] value = Objects.requireNonNull(record.value(),
                "record value: a null value would be a tombstone, and compaction is a non-goal");
        byte[] key = record.key();

        int keyLength = key == null ? RecordFrame.NULL_KEY_LENGTH : key.length;
        long recordBytes = RecordFrame.frameBytes(keyLength, value.length);
        if (recordBytes > config.maxRecordBytes()) {
            throw new RecordTooLargeException(topic, partition, recordBytes, config.maxRecordBytes());
        }

        long offset = nextOffset();
        ByteBuffer frame = RecordFrame.encode(offset, timeSource.currentTimeMillis(), key, value);
        rollIfNeeded(offset, frame.remaining());
        activeSegment().append(offset, frame);
        return offset;
    }

    @Override
    public StoredRecord read(long offset) {
        if (offset < logStartOffset() || offset >= nextOffset()) {
            throw new OffsetOutOfRangeException(topic, partition, offset, logStartOffset(), nextOffset());
        }
        return segmentHolding(offset).read(offset);
    }

    @Override
    public long logStartOffset() {
        return segments.get(0).baseOffset();
    }

    @Override
    public long highWaterMark() {
        return nextOffset();
    }

    @Override
    public int segmentCount() {
        return segments.size();
    }

    @Override
    public long logBytes() {
        long logBytes = 0L;
        for (LogSegment segment : segments) {
            logBytes += segment.sizeBytes();
        }
        return logBytes;
    }

    @Override
    public long indexBytes() {
        long indexBytes = 0L;
        for (LogSegment segment : segments) {
            indexBytes += segment.indexBytes();
        }
        return indexBytes;
    }

    /**
     * Forces the active segment to the device and closes every segment. Calling this twice is
     * harmless.
     *
     * @throws ShrikeIOException if a segment cannot be closed; the rest are closed first
     */
    @Override
    public void close() {
        ShrikeIOException firstFailure = null;
        for (LogSegment segment : segments) {
            try {
                segment.close();
            } catch (ShrikeIOException e) {
                if (firstFailure == null) {
                    firstFailure = e;
                }
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    /**
     * Seals the active segment and starts a new one when the record about to be written would push a
     * non-empty segment past {@code segment.bytes}.
     *
     * @param offset     the offset of the record about to be appended, and the base offset of the new
     *                   segment when one is started
     * @param frameBytes the bytes that record's frame occupies
     */
    private void rollIfNeeded(long offset, int frameBytes) {
        LogSegment active = activeSegment();
        if (active.isEmpty() || active.hasCapacity(frameBytes)) {
            return;
        }
        active.seal();
        segments.add(LogSegment.create(topic, partition, directory, offset, config));
    }

    private LogSegment activeSegment() {
        return segments.get(segments.size() - 1);
    }

    /**
     * @param offset an offset inside the readable range
     * @return the segment whose base offset is the highest one at or below {@code offset}
     */
    private LogSegment segmentHolding(long offset) {
        int low = 0;
        int high = segments.size() - 1;
        int holder = 0;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (segments.get(middle).baseOffset() <= offset) {
                holder = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return segments.get(holder);
    }

    /**
     * @param directory a partition's directory
     * @return the base offsets of its segments, in ascending order
     * @throws IOException if the directory cannot be listed
     */
    private static List<Long> segmentBaseOffsets(Path directory) throws IOException {
        List<Long> baseOffsets = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                Matcher name = SEGMENT_LOG_FILE_NAME.matcher(entry.getFileName().toString());
                if (!name.matches()) {
                    continue;
                }
                try {
                    baseOffsets.add(Long.parseLong(name.group(1)));
                } catch (NumberFormatException e) {
                    // Twenty digits that overflow a long are not a name this log ever wrote, so the
                    // file is not one of its segments.
                    continue;
                }
            }
        }
        Collections.sort(baseOffsets);
        return baseOffsets;
    }

    /**
     * Closes the segments an open had already taken over before it failed, so a failed open does not
     * leak file handles. A close that fails here is dropped: the open is already failing, and the
     * exception it is failing with is the useful one.
     *
     * @param segments the segments opened so far
     */
    private static void closeQuietlyAfterFailedOpen(List<LogSegment> segments) {
        for (LogSegment segment : segments) {
            try {
                segment.close();
            } catch (ShrikeIOException e) {
                // Dropped on purpose: see above.
            }
        }
    }
}
