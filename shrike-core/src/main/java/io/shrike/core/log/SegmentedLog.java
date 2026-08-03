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
 * <p>Durability: what an append promises depends on {@code flush.mode}, and on nothing else.
 *
 * <ul>
 *   <li>{@link FlushMode#PER_RECORD} — a produce is acknowledged only after {@code force()}, so an
 *       acknowledged record survives an operating-system or power failure.
 *   <li>{@link FlushMode#INTERVAL}, the default — acknowledged records survive a process crash (they
 *       are in the page cache), but up to one flush interval of acknowledged records can be lost on an
 *       operating-system or power failure, after which recovery truncates the torn tail.
 * </ul>
 *
 * <p>In {@link FlushMode#INTERVAL} the force happens on whichever of the two bounds comes first: the
 * append that carries the log past {@code flush.interval.bytes} of unforced records does it there and
 * then, and {@link #flushIfDue(long)} does it once {@code flush.interval.ms} has passed. Two force
 * points predate the policy and are unchanged by it — sealing a segment, which a roll does, and
 * {@link #close()}, which forces the segment still taking records. A seal resets what counts as
 * unforced, because the bytes it forced are the ones that were being counted.
 *
 * <p>Recovery: opening an existing partition directory recovers it. Every segment but the last was
 * forced before it was sealed, so only the last one is walked frame by frame; whatever follows its
 * last whole frame is truncated and logged. Sealed segments are not repaired: damage inside one is
 * reported as a {@link CorruptRecordException} when the damaged record is read, and its neighbours
 * still read.
 *
 * <p>Retention: {@link #deleteRetiredSegments(long)} deletes whole sealed segments off the front of
 * the log — the oldest first — once {@code retention.ms} or {@code retention.bytes} says they are no
 * longer kept. Both bounds are off by default. The segment still taking appends is never deleted,
 * however old it is and however large the partition has grown, so a partition can sit above
 * {@code retention.bytes} by the size of that one segment. Deleting advances
 * {@link #logStartOffset()} before the segment leaves the list and before its files are unlinked, so
 * a reader that can no longer find an offset has already been told where the log now starts.
 *
 * <p>A log has a single writer. Nothing here is safe to call from two threads at once — retention and
 * flushing included: whatever owns a log is what has to call {@code deleteRetiredSegments} and
 * {@code flushIfDue} on it, under whatever guards an append and a read already take.
 */
public final class SegmentedLog implements Log, LogStatistics {

    /** The JDK's own logger, because {@code shrike-core} depends on the JDK alone. */
    private static final System.Logger LOGGER = System.getLogger(SegmentedLog.class.getName());

    /** The name of a segment's log file: its base offset, zero-padded to 20 digits. */
    private static final Pattern SEGMENT_LOG_FILE_NAME = Pattern.compile("(\\d{20})\\.log");

    private static final long FIRST_OFFSET = 0L;

    /**
     * What the shared range check answers with when the range holds nothing. An offset is never
     * negative, so no readable end offset can be mistaken for it.
     */
    private static final long NOTHING_READABLE = -1L;

    /** Shared because it is empty and never handed out for writing. */
    private static final byte[] NO_RECORDS = new byte[0];

    /**
     * What {@link #lastFlushMillis} holds before this log has ever flushed on the interval. There is no
     * earlier flush to measure one from, so the first pass that finds unforced records forces them —
     * which is what keeps the window at one interval rather than at the two a phase this log guessed at
     * would cost. No epoch millisecond can be mistaken for it.
     */
    private static final long NEVER_FLUSHED = Long.MIN_VALUE;

    private final String topic;
    private final int partition;
    private final Path directory;
    private final TimeSource timeSource;
    private final LogConfig config;

    /**
     * The segments in base-offset order; the last one is the active segment, the only one that takes
     * appends. Rolling appends to this list and retention removes from the front of it, so every
     * segment in it is one this log can still serve.
     */
    // confined to: the single thread that owns this log
    private final List<LogSegment> segments;

    /**
     * The lowest offset this log can still serve. It is always the base offset of the first segment,
     * and it lives in a field of its own so that retention can advance it <em>before</em> that segment
     * leaves {@link #segments}.
     */
    // volatile because that order is the whole guarantee: a reader on another thread that sees the
    // shortened list must not be able to see the old start offset beside it.
    private volatile long logStartOffset;

    /**
     * Bytes appended since the last force this log made: a flush, or the seal a roll makes.
     * {@link #close()} forces too and leaves this alone, because nothing appends after a close. It is
     * the volume half of {@code flush.mode} and the reason {@link #flushIfDue(long)} can tell "nothing
     * to do" from "not time yet".
     */
    // confined to: the single thread that owns this log
    private long unflushedBytes;

    /**
     * The epoch millisecond {@code flush.interval.ms} is measured from, or {@link #NEVER_FLUSHED}.
     *
     * <p>Only {@link #flushIfDue(long)} moves it, and a force that happens for another reason — the
     * volume bound, a seal, per-record mode — deliberately does not. That is what keeps it in step with
     * the thread that asks: every value it ever holds is an instant that thread asked at, so the next
     * ask is a whole interval later and a record is forced at the first pass after it was appended.
     * Restarting the interval on an off-schedule force would let a record appended just after one wait
     * almost two intervals for the next, and one interval is the whole of what this mode promises.
     */
    // confined to: the single thread that owns this log
    private long lastFlushMillis = NEVER_FLUSHED;

    /**
     * A test seam, and the only field here that production code never writes. It runs on the thread
     * that forced, immediately after the force returned and before the caller that asked for it moves
     * on — which is the only way to observe an fsync at all, since one leaves no trace a JVM can see.
     * It is the same trick {@link DurableFile.StepObserver} plays for a durable rename. Production
     * leaves it doing nothing.
     */
    // confined to: the single thread that owns this log, which is also the thread a test installs it on
    private Runnable forced = () -> {
    };

    private SegmentedLog(String topic, int partition, Path directory, TimeSource timeSource, LogConfig config,
                         List<LogSegment> segments) {
        this.topic = topic;
        this.partition = partition;
        this.directory = directory;
        this.timeSource = timeSource;
        this.config = config;
        this.segments = segments;
        this.logStartOffset = segments.get(0).baseOffset();
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
     * @throws IllegalArgumentException if the topic is not a {@link SafeName}, so that it could name
     *                                  something other than one directory, or if the partition is
     *                                  negative
     * @throws ShrikeIOException        if the directory or its segments cannot be opened
     */
    public static SegmentedLog open(Path dataDirectory, String topic, int partition, TimeSource timeSource,
                                    LogConfig config) {
        Objects.requireNonNull(dataDirectory, "dataDirectory");
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(timeSource, "timeSource");
        Objects.requireNonNull(config, "config");
        // The one name rule, the same one the protocol applies: a topic names a directory inside the
        // data directory, and a second, looser copy of that rule here would be a second chance to be
        // more generous than the check a request already passed.
        SafeName.require(topic, "topic");
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
        long timestampMillis = timeSource.currentTimeMillis();
        ByteBuffer frame = RecordFrame.encode(offset, timestampMillis, key, value);
        int frameBytes = frame.remaining();
        rollIfNeeded(offset, frameBytes);
        activeSegment().append(offset, timestampMillis, frame);
        unflushedBytes += frameBytes;
        flushIfAppendDue();
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
    public byte[] readRange(long fetchOffset, long limitOffset, int maxBytes) {
        long endOffset = readableEndOffset(fetchOffset, limitOffset, maxBytes);
        if (endOffset == NOTHING_READABLE) {
            return NO_RECORDS;
        }
        return segmentHolding(fetchOffset).readRange(fetchOffset, endOffset, maxBytes);
    }

    @Override
    public RecordRange openRange(long fetchOffset, long limitOffset, int maxBytes) {
        long endOffset = readableEndOffset(fetchOffset, limitOffset, maxBytes);
        if (endOffset == NOTHING_READABLE) {
            return RecordRange.empty();
        }
        return segmentHolding(fetchOffset).openRange(fetchOffset, endOffset, maxBytes);
    }

    /**
     * The checks both ways of serving a range make, in one place so that the two cannot disagree
     * about which offsets a fetch may be answered with or about when the answer is empty.
     *
     * @param fetchOffset the offset the range starts at
     * @param limitOffset the exclusive offset the caller wants to stop before
     * @param maxBytes    the most bytes the caller wants, checked here and used by the segment
     * @return the exclusive offset the range stops before, or {@link #NOTHING_READABLE} when there is
     *         nothing in it
     * @throws IllegalArgumentException  if {@code maxBytes} is negative
     * @throws OffsetOutOfRangeException if {@code fetchOffset} is outside the readable range
     */
    private long readableEndOffset(long fetchOffset, long limitOffset, int maxBytes) {
        if (maxBytes < 0) {
            throw new IllegalArgumentException("maxBytes must not be negative, but was " + maxBytes);
        }
        // A fetch at the high-water mark is a question with an empty answer rather than a bad offset:
        // it is where a caught-up consumer sits. One past it is out of range like any other.
        if (fetchOffset < logStartOffset() || fetchOffset > nextOffset()) {
            throw new OffsetOutOfRangeException(topic, partition, fetchOffset, logStartOffset(), nextOffset());
        }
        if (fetchOffset == nextOffset()) {
            return NOTHING_READABLE;
        }

        // Clamped here rather than trusted: the high-water mark is the only bound that keeps a
        // consumer from reading a record the broker has not finished acknowledging.
        long endOffset = Math.min(limitOffset, nextOffset());
        if (fetchOffset >= endOffset) {
            return NOTHING_READABLE;
        }
        return endOffset;
    }

    @Override
    public long logStartOffset() {
        return logStartOffset;
    }

    /**
     * Forces the records this log has not put on the device yet, once {@code flush.interval.ms} has
     * passed since the last time this method did. A log with nothing unforced is not due whatever the
     * clock says, which is also why {@link FlushMode#PER_RECORD} never finds work here: an append in
     * that mode has already forced what it wrote. A log that has never flushed on the interval has no
     * earlier flush to measure one from, so the first ask that finds unforced records answers it.
     *
     * @param nowMillis the epoch millisecond the interval is measured against
     * @return whether anything was forced
     * @throws ShrikeIOException if the force fails
     */
    @Override
    public boolean flushIfDue(long nowMillis) {
        if (unflushedBytes == 0L) {
            return false;
        }
        if (lastFlushMillis != NEVER_FLUSHED && nowMillis - lastFlushMillis < config.flushIntervalMs()) {
            return false;
        }
        force();
        lastFlushMillis = nowMillis;
        return true;
    }

    /**
     * Deletes the sealed segments this log's configuration no longer keeps, oldest first: first the
     * ones older than {@code retention.ms}, then, while the partition still holds more than
     * {@code retention.bytes}, the oldest of what is left. Either bound set to
     * {@link LogConfig#RETENTION_DISABLED} deletes nothing, which is the default for both.
     *
     * <p>Whole segments only, and never the segment still taking appends: a partition of one segment
     * deletes nothing, whatever its age or size. Each deletion happens in one order and it is the
     * order that makes it safe — advance {@link #logStartOffset()}, take the segment out of the list,
     * then unlink its two files. A reader looking for an offset that has just been deleted therefore
     * meets the refusal and the new start offset rather than a segment that is no longer there, and a
     * reader already inside a file it opened keeps reading it, because unlinking removes a name and
     * not the bytes behind it.
     *
     * @param nowMillis the epoch millisecond retention is evaluated at, from the caller's clock
     * @return how many segments were deleted
     * @throws ShrikeIOException if a segment's files cannot be removed
     */
    @Override
    public int deleteRetiredSegments(long nowMillis) {
        int deletedSegmentCount = 0;
        if (config.deletesOnAge()) {
            while (hasDeletableSegment() && segments.get(0).isExpiredAt(nowMillis, config.retentionMs())) {
                deleteOldestSegment("it holds no record newer than retention.ms=" + config.retentionMs());
                deletedSegmentCount++;
            }
        }
        if (config.deletesOnSize()) {
            // Counted once and then kept in step, because logBytes() walks every segment and the loop
            // below already knows exactly how many bytes each deletion gives back.
            long logBytes = logBytes();
            while (hasDeletableSegment() && logBytes > config.retentionBytes()) {
                logBytes -= segments.get(0).sizeBytes();
                deleteOldestSegment("this partition holds more than retention.bytes=" + config.retentionBytes());
                deletedSegmentCount++;
            }
        }
        return deletedSegmentCount;
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
     * Installs the test seam described on {@link #forced}. Package-private because it is a seam for
     * this package's own tests: proving that a produce is acknowledged only after {@code force()} means
     * observing the force, and an fsync is otherwise invisible.
     *
     * @param seam what to run once a force has returned
     */
    void onForced(Runnable seam) {
        this.forced = Objects.requireNonNull(seam, "seam");
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
        // Sealing forced everything that was being counted, so the volume bound starts again from the
        // first record of the segment that is about to be created rather than carrying a debt into it.
        unflushedBytes = 0L;
        segments.add(LogSegment.create(topic, partition, directory, offset, config));
    }

    /**
     * The half of the flush policy an append decides for itself: {@link FlushMode#PER_RECORD} forces
     * every record before {@link #append(ProducedRecord)} returns, and {@link FlushMode#INTERVAL}
     * forces once {@code flush.interval.bytes} of records have gone by without one.
     *
     * <p>The volume bound lives here rather than on {@code shrike-flush} because an append is the only
     * thing that can move it: a bound checked once an interval would be a bound the log crosses by
     * however many records arrived in between, which is the same reason a roll happens on the record
     * that would exceed {@code segment.bytes} rather than on the sweep that notices afterwards.
     */
    private void flushIfAppendDue() {
        if (config.forcesEveryRecord() || unflushedBytes >= config.flushIntervalBytes()) {
            force();
        }
    }

    /**
     * Forces the active segment and starts counting unforced bytes again. It does not touch
     * {@link #lastFlushMillis}: see that field for why the interval is measured from the last interval
     * flush rather than from the last force of any kind.
     *
     * @throws ShrikeIOException if the force fails
     */
    private void force() {
        activeSegment().force();
        unflushedBytes = 0L;
        forced.run();
    }

    /**
     * @return whether there is a segment retention is allowed to delete. Only the active segment is
     *         off limits, so this asks whether there is more than one
     */
    private boolean hasDeletableSegment() {
        return segments.size() > 1;
    }

    /**
     * Deletes the first segment, in the one order that keeps a concurrent reader honest.
     *
     * @param why what to say in the log line, because deleting stored records is not something a
     *            broker should have to be asked to explain afterwards
     */
    private void deleteOldestSegment(String why) {
        LogSegment oldest = segments.get(0);
        long deletedBytes = oldest.sizeBytes();

        // 1. The start offset first. From here a read of anything this segment held is refused with
        //    the offset the log now starts at, whether or not the file is still on disk.
        logStartOffset = segments.get(1).baseOffset();
        // 2. Then the list, so nothing new can be routed into a segment that is on its way out.
        segments.remove(0);
        // 3. Then the files. Unlinking removes their names; a reader holding one of them open reads
        //    on to the end of what it asked for.
        oldest.delete();

        long newLogStartOffset = logStartOffset;
        LOGGER.log(System.Logger.Level.INFO, () -> "retention deleted segment " + oldest.baseOffset() + " of topic="
                + topic + " partition=" + partition + ": " + deletedBytes + " bytes, because " + why
                + "; this partition now starts at offset " + newLogStartOffset);
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
