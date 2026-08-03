package io.shrike.core.log;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * One file of a segmented log, plus the sparse index that points into it. A segment is named after
 * the offset of its first record: {@code <20-digit baseOffset>.log} beside
 * {@code <20-digit baseOffset>.index}.
 *
 * <p>A segment is either active or sealed. The active segment is the only one that takes appends. It
 * is sealed when a roll moves the writer on: the log file is forced, then the index file is forced,
 * and only then is the pair treated as immutable. That order is what makes recovery cheap — a sealed
 * segment's bytes were on the device before anything else happened, so only the active segment can
 * have a torn tail.
 *
 * <p>A sealed segment is also the unit retention deletes. It knows the largest record timestamp it
 * holds, which is what {@code retention.ms} measures its age from, and {@link #delete()} unlinks its
 * two files. Neither number comes from the filesystem: a file's modification time says when the bytes
 * were written to <em>this</em> copy of them, and a restore or a backup resets it while the records
 * inside do not change.
 */
final class LogSegment implements Closeable {

    /**
     * The JDK's own logger, because {@code shrike-core} depends on the JDK alone. The only thing this
     * class logs is a truncation, and a truncation is worth a WARN.
     */
    private static final System.Logger LOGGER = System.getLogger(LogSegment.class.getName());

    /**
     * What {@link #largestTimestampMillis} holds when this segment has no record whose timestamp could
     * be read: an empty segment, or one whose last frame is damaged. Time-based retention keeps such a
     * segment rather than deleting it, because deleting on an age nobody could measure would turn a
     * corrupt frame into the loss of every healthy record beside it.
     */
    private static final long NO_TIMESTAMP_MILLIS = Long.MIN_VALUE;

    private final String topic;
    private final int partition;
    private final long baseOffset;
    private final Path logFile;
    private final FileChannel channel;
    private final OffsetIndex index;
    private final LogConfig config;

    // The five fields below are confined to: the single thread that owns this segment's log.
    private long sizeBytes;
    private long recordCount;

    /** Bytes appended since the last index entry: the index rule's only state. */
    private long bytesSinceLastIndexEntry;

    /**
     * The largest timestamp any record in this segment carries, which is the age {@code retention.ms}
     * judges a sealed segment by. It climbs with every append while the segment is active and stops
     * moving when the segment is sealed; a segment reopened as sealed reads it back out of the log
     * file rather than remembering it, because nothing writes it down.
     */
    private long largestTimestampMillis = NO_TIMESTAMP_MILLIS;

    private boolean sealed;

    private LogSegment(String topic, int partition, long baseOffset, Path logFile, FileChannel channel,
                       OffsetIndex index, LogConfig config, long sizeBytes, boolean sealed) {
        this.topic = topic;
        this.partition = partition;
        this.baseOffset = baseOffset;
        this.logFile = logFile;
        this.channel = channel;
        this.index = index;
        this.config = config;
        this.sizeBytes = sizeBytes;
        this.sealed = sealed;
    }

    /**
     * @param directory  a partition's directory
     * @param baseOffset the offset of the segment's first record
     * @return the path of that segment's log file
     */
    static Path logFileFor(Path directory, long baseOffset) {
        return directory.resolve("%020d.log".formatted(baseOffset));
    }

    /**
     * @param directory  a partition's directory
     * @param baseOffset the offset of the segment's first record
     * @return the path of that segment's index file
     */
    static Path indexFileFor(Path directory, long baseOffset) {
        return directory.resolve("%020d.index".formatted(baseOffset));
    }

    /**
     * Creates a segment that does not exist yet: an empty log file and an empty index file. Used for
     * the first segment of a new partition and for every segment a roll starts.
     *
     * @param topic      the topic the segment belongs to
     * @param partition  the partition within that topic
     * @param directory  the partition's directory
     * @param baseOffset the offset the segment's first record will take
     * @param config     the log's configuration
     * @return the new, empty, active segment
     * @throws ShrikeIOException if either file cannot be created, including when one already exists
     */
    static LogSegment create(String topic, int partition, Path directory, long baseOffset, LogConfig config) {
        Path logFile = logFileFor(directory, baseOffset);
        try {
            FileChannel channel = FileChannel.open(logFile, StandardOpenOption.CREATE_NEW, StandardOpenOption.READ,
                    StandardOpenOption.WRITE);
            OffsetIndex index = OffsetIndex.createEmpty(indexFileFor(directory, baseOffset), baseOffset);
            return new LogSegment(topic, partition, baseOffset, logFile, channel, index, config, 0L, false);
        } catch (IOException e) {
            throw new ShrikeIOException("cannot create segment file " + logFile, e);
        }
    }

    /**
     * Opens a segment that is already sealed, which is every segment but the last. The log file is not
     * scanned: it was forced before it was sealed, so its frames are whole. Only its index is checked,
     * and only cheaply — the file exists, its size is a whole number of entries, and no entry points
     * past the end of the log. An index that fails any of those is emptied and rebuilt from the log.
     *
     * <p>The one thing that <em>is</em> read out of the log file is the last frame, for the timestamp
     * retention judges this segment's age by: the index says which byte to start walking from, so the
     * walk covers at most {@code index.interval.bytes} rather than the whole file. A rebuilt index has
     * already decoded every frame, so that case takes the timestamp from the rebuild instead of
     * reading anything twice.
     *
     * @param topic      the topic the segment belongs to
     * @param partition  the partition within that topic
     * @param directory  the partition's directory
     * @param baseOffset the offset of the segment's first record
     * @param config     the log's configuration
     * @return the sealed segment, ready for reads
     * @throws ShrikeIOException if the segment cannot be read
     */
    static LogSegment openSealed(String topic, int partition, Path directory, long baseOffset, LogConfig config) {
        Path logFile = logFileFor(directory, baseOffset);
        Path indexFile = indexFileFor(directory, baseOffset);
        try {
            FileChannel channel = FileChannel.open(logFile, StandardOpenOption.READ);
            long logLengthBytes = channel.size();

            boolean indexIsWholeEntries = Files.isRegularFile(indexFile)
                    && Files.size(indexFile) % OffsetIndex.ENTRY_BYTES == 0;
            OffsetIndex index = indexIsWholeEntries
                    ? OffsetIndex.load(indexFile, baseOffset)
                    : OffsetIndex.createEmpty(indexFile, baseOffset);

            LogSegment segment = new LogSegment(topic, partition, baseOffset, logFile, channel, index, config,
                    logLengthBytes, true);
            if (!indexIsWholeEntries || !index.isUsableFor(logLengthBytes)) {
                segment.rebuildIndex();
            } else {
                segment.readLargestTimestampFromLastFrame();
            }
            return segment;
        } catch (IOException e) {
            throw new ShrikeIOException("cannot open sealed segment " + logFile, e);
        }
    }

    /**
     * Opens the last segment of a partition and recovers it. Every frame is walked from the first byte
     * with the same checks a read applies — length range, checksum, magic, and the offset the frame
     * carries — and the first frame that fails ends the log: the file is truncated to the byte after
     * the last whole frame and the caller is told, so a torn tail costs the records that were being
     * written and nothing else. The index is rebuilt from that same walk, which is why a rebuilt index
     * and one grown record by record agree entry for entry.
     *
     * @param topic      the topic the segment belongs to
     * @param partition  the partition within that topic
     * @param directory  the partition's directory
     * @param baseOffset the offset of the segment's first record
     * @param config     the log's configuration
     * @return the recovered, active segment
     * @throws ShrikeIOException if the segment cannot be read or truncated
     */
    static LogSegment recoverTail(String topic, int partition, Path directory, long baseOffset, LogConfig config) {
        Path logFile = logFileFor(directory, baseOffset);
        try {
            FileChannel channel = FileChannel.open(logFile, StandardOpenOption.READ, StandardOpenOption.WRITE);
            OffsetIndex index = OffsetIndex.createEmpty(indexFileFor(directory, baseOffset), baseOffset);
            long logLengthBytes = channel.size();

            LogSegment segment = new LogSegment(topic, partition, baseOffset, logFile, channel, index, config,
                    logLengthBytes, false);
            segment.truncateTornTail(logLengthBytes);
            return segment;
        } catch (IOException e) {
            throw new ShrikeIOException("cannot recover segment " + logFile, e);
        }
    }

    long baseOffset() {
        return baseOffset;
    }

    /**
     * @return the offset the next record appended to this segment would take
     */
    long nextOffset() {
        return baseOffset + recordCount;
    }

    /**
     * @return the bytes this segment's log file holds
     */
    long sizeBytes() {
        return sizeBytes;
    }

    /**
     * @return the bytes this segment's index file holds
     */
    long indexBytes() {
        return index.sizeBytes();
    }

    boolean isEmpty() {
        return sizeBytes == 0L;
    }

    /**
     * Whether every record in this segment is at least {@code retentionMs} old, which is what makes a
     * sealed segment one retention deletes. A segment whose largest timestamp could not be read is
     * never expired: see {@link #NO_TIMESTAMP_MILLIS}.
     *
     * @param nowMillis   the epoch millisecond retention is being evaluated at
     * @param retentionMs how long a segment is kept after the newest record in it
     * @return whether this segment is past that bound
     */
    boolean isExpiredAt(long nowMillis, long retentionMs) {
        if (largestTimestampMillis == NO_TIMESTAMP_MILLIS) {
            return false;
        }
        return nowMillis - largestTimestampMillis >= retentionMs;
    }

    /**
     * @param frameBytes the size of a frame about to be appended
     * @return whether the frame fits without pushing the segment past {@code segment.bytes}
     */
    boolean hasCapacity(long frameBytes) {
        return sizeBytes + frameBytes <= config.segmentBytes();
    }

    /**
     * Appends one already-encoded frame to the end of the segment.
     *
     * @param offset          the offset the frame carries
     * @param timestampMillis the timestamp the frame carries, which is passed alongside the bytes
     *                        rather than read back out of them so that the segment can keep the
     *                        largest one without decoding what it just wrote
     * @param frame           the encoded frame, positioned at its first byte
     * @throws ShrikeIOException if the write fails
     */
    void append(long offset, long timestampMillis, ByteBuffer frame) {
        if (sealed) {
            throw new IllegalStateException("segment " + logFile + " is sealed and takes no more records");
        }

        int frameBytes = frame.remaining();
        long positionBytes = sizeBytes;
        try {
            ByteChannels.writeFully(channel, frame);
        } catch (IOException e) {
            throw new ShrikeIOException("cannot append offset " + offset + " at position " + positionBytes + " of "
                    + logFile, e);
        }

        sizeBytes += frameBytes;
        indexIfDue(offset, positionBytes, frameBytes);
        recordCount++;
        rememberTimestamp(timestampMillis);
    }

    /**
     * Reads the record stored at {@code offset}. The index says where to start; from there the frames
     * are walked, each one's stored offset compared with the one asked for, and only the frame that
     * matches is decoded. The walk is bounded by the index interval, because the index holds an entry
     * every {@code index.interval.bytes}.
     *
     * @param offset the offset to read, which this segment is known to cover
     * @return the stored record
     * @throws CorruptRecordException if the frame's bytes no longer match their checksum, or if the
     *                                segment ends before the offset is found
     * @throws ShrikeIOException      if the read fails
     */
    StoredRecord read(long offset) {
        long positionBytes = index.floorPositionBytes(offset);
        try {
            while (positionBytes < sizeBytes) {
                RecordLocation location = new RecordLocation(topic, partition, offset, positionBytes, logFile);
                FrameHeader header = headerAt(positionBytes, location);
                if (header.storedOffset() == offset) {
                    return decodeFrameAt(positionBytes, header.lengthBytes(), location);
                }
                if (header.storedOffset() > offset) {
                    throw new CorruptRecordException(location, "the frame here stores offset " + header.storedOffset()
                            + ", which is past the offset that was asked for");
                }
                positionBytes += header.frameBytes();
            }
        } catch (IOException e) {
            throw new ShrikeIOException("cannot read offset " + offset + " near position " + positionBytes + " of "
                    + logFile, e);
        }

        throw new CorruptRecordException(new RecordLocation(topic, partition, offset, positionBytes, logFile),
                "the segment ends before this offset was found");
    }

    /**
     * Reads whole frames out of this segment verbatim, from {@code fetchOffset} up to but not
     * including {@code limitOffset}, at most {@code maxBytes} of them, and never past the end of this
     * segment. The bytes are not decoded: this is the range a fetch response carries, so what leaves
     * here is what is on disk.
     *
     * <p>{@code maxBytes} yields to the whole-frame rule twice over. A frame that would cross the cap
     * is left for the next fetch, and a first frame that is larger than the cap all by itself is
     * returned regardless — a consumer that asked for less than one record still has to be able to
     * move past it.
     *
     * @param fetchOffset the offset to start at, which this segment is known to cover
     * @param limitOffset the exclusive offset to stop before, already clamped to the high-water mark
     * @param maxBytes    the most bytes to return, subject to the whole-frame rule
     * @return the frames in that range, back to back
     * @throws CorruptRecordException if a frame contradicts the offset the walk expects
     * @throws ShrikeIOException      if the read fails
     */
    byte[] readRange(long fetchOffset, long limitOffset, int maxBytes) {
        FramePositions range = locateRange(fetchOffset, limitOffset, maxBytes);
        int rangeBytes = range.lengthBytes();
        ByteBuffer records = ByteBuffer.allocate(rangeBytes);
        try {
            ByteChannels.readFully(channel, records, range.positionBytes());
        } catch (IOException e) {
            throw new ShrikeIOException("cannot read the range from offset " + fetchOffset + " near position "
                    + range.positionBytes() + " of " + logFile, e);
        }
        if (records.hasRemaining()) {
            throw new CorruptRecordException(
                    new RecordLocation(topic, partition, fetchOffset, range.positionBytes(), logFile),
                    "the segment holds " + records.position() + " of the " + rangeBytes
                            + " bytes its frames declare");
        }
        return records.array();
    }

    /**
     * Locates the same frames {@link #readRange} would copy and opens the file over them instead, so
     * that the caller can send them without any of them passing through memory here.
     *
     * <p>The channel is a second descriptor on this segment's log file rather than the one this
     * segment reads and writes through, and the {@link RecordRange} that carries it is what closes
     * it. {@link RecordRange} says what that buys; the short version is that {@link #delete()} may
     * run while the range is still being sent.
     *
     * @param fetchOffset the offset to start at, which this segment is known to cover
     * @param limitOffset the exclusive offset to stop before, already clamped to the high-water mark
     * @param maxBytes    the most bytes to cover, subject to the whole-frame rule
     * @return the range, which the caller closes
     * @throws CorruptRecordException if a frame contradicts the offset the walk expects
     * @throws ShrikeIOException      if the segment cannot be read or opened
     */
    RecordRange openRange(long fetchOffset, long limitOffset, int maxBytes) {
        FramePositions range = locateRange(fetchOffset, limitOffset, maxBytes);
        if (range.lengthBytes() == 0) {
            return RecordRange.empty();
        }
        try {
            return RecordRange.of(logFile, FileChannel.open(logFile, StandardOpenOption.READ),
                    range.positionBytes(), range.lengthBytes());
        } catch (IOException e) {
            throw new ShrikeIOException("cannot open " + logFile + " to send the range from offset " + fetchOffset
                    + " at position " + range.positionBytes(), e);
        }
    }

    /**
     * Walks this segment's frames and decides which bytes a fetch from {@code fetchOffset} is owed.
     * It is the one place that decision is made: {@link #readRange} copies what it names and
     * {@link #openRange} opens the file over it, so the two cannot come to different conclusions
     * about which frames a consumer was promised.
     *
     * @param fetchOffset the offset to start at, which this segment is known to cover
     * @param limitOffset the exclusive offset to stop before, already clamped to the high-water mark
     * @param maxBytes    the most bytes to cover, subject to the whole-frame rule
     * @return where the range starts and how long it is
     * @throws CorruptRecordException if a frame contradicts the offset the walk expects
     * @throws ShrikeIOException      if the read fails
     */
    private FramePositions locateRange(long fetchOffset, long limitOffset, int maxBytes) {
        long positionBytes = index.floorPositionBytes(fetchOffset);
        long startPositionBytes = -1L;
        long endPositionBytes = -1L;
        try {
            while (positionBytes < sizeBytes) {
                RecordLocation location = new RecordLocation(topic, partition, fetchOffset, positionBytes, logFile);
                FrameHeader header = headerAt(positionBytes, location);

                if (startPositionBytes < 0) {
                    if (header.storedOffset() > fetchOffset) {
                        throw new CorruptRecordException(location, "the frame here stores offset "
                                + header.storedOffset() + ", which is past the offset that was asked for");
                    }
                    if (header.storedOffset() < fetchOffset) {
                        positionBytes += header.frameBytes();
                        continue;
                    }
                    startPositionBytes = positionBytes;
                    endPositionBytes = positionBytes;
                }

                // The frames say where the range must stop, rather than a count kept alongside them:
                // a frame at or past the limit is one the caller has not been promised yet.
                if (header.storedOffset() >= limitOffset) {
                    break;
                }
                long rangeBytes = endPositionBytes - startPositionBytes;
                if (rangeBytes > 0 && rangeBytes + header.frameBytes() > maxBytes) {
                    break;
                }
                positionBytes += header.frameBytes();
                endPositionBytes = positionBytes;
            }
        } catch (IOException e) {
            throw new ShrikeIOException("cannot read the range from offset " + fetchOffset + " near position "
                    + positionBytes + " of " + logFile, e);
        }

        if (startPositionBytes < 0) {
            throw new CorruptRecordException(
                    new RecordLocation(topic, partition, fetchOffset, positionBytes, logFile),
                    "the segment ends before this offset was found");
        }
        return new FramePositions(startPositionBytes, (int) (endPositionBytes - startPositionBytes));
    }

    /**
     * Forces this segment's log file to the device, metadata included, which is what the flush policy
     * asks for: {@link FlushMode#PER_RECORD} after every append, {@link FlushMode#INTERVAL} once one of
     * its two bounds is reached.
     *
     * <p>The index is deliberately not forced with it. An index is derived data, and the tail segment's
     * index is rebuilt from its log at every start, so forcing it would be a second fsync per flush
     * buying a file that recovery is going to rewrite anyway. {@link #seal()} does force it, because a
     * sealed segment's index is only checked at startup and never rebuilt unless that check fails.
     *
     * <p>{@code force(true)} rather than {@code force(false)}: the file's new length is metadata, and
     * bytes the device has without a length that reaches them are bytes the next open cannot find.
     *
     * @throws ShrikeIOException if the file cannot be forced
     */
    void force() {
        try {
            channel.force(true);
        } catch (IOException e) {
            throw new ShrikeIOException("cannot force segment " + logFile, e);
        }
    }

    /**
     * Forces the log file, then the index file, and only then treats the pair as immutable. Called by
     * the roll that moves the writer to the next segment; calling it twice is harmless.
     *
     * @throws ShrikeIOException if either file cannot be forced
     */
    void seal() {
        if (sealed) {
            return;
        }
        try {
            channel.force(true);
            index.force();
        } catch (IOException e) {
            throw new ShrikeIOException("cannot seal segment " + logFile, e);
        }
        sealed = true;
    }

    /**
     * Closes both of this segment's files and unlinks them, which is the last thing retention does to
     * a segment it has decided not to keep.
     *
     * <p>Unlinking is not the same as freeing: it removes the two names from the directory, and a
     * reader that already has one of those files open keeps reading the bytes it opened until it
     * closes. That is what makes the deletion safe to do while somebody is reading — the reader
     * finishes with the whole record it asked for rather than half of one.
     *
     * @throws ShrikeIOException if a file cannot be closed or removed; both are attempted first
     */
    void delete() {
        ShrikeIOException closeFailure = null;
        try {
            close();
        } catch (ShrikeIOException e) {
            // Kept rather than thrown here: the names still have to go, or this segment would be read
            // again at the next start as though retention had never run.
            closeFailure = e;
        }

        try {
            Files.deleteIfExists(logFile);
        } catch (IOException e) {
            throw new ShrikeIOException("cannot delete segment file " + logFile, e);
        }
        index.delete();

        if (closeFailure != null) {
            throw closeFailure;
        }
    }

    /**
     * Forces an active segment to the device and closes both files. A sealed segment was forced when
     * it was sealed, so closing it only closes. Calling this twice is harmless.
     *
     * @throws ShrikeIOException if either file cannot be closed
     */
    @Override
    public void close() {
        boolean forceNeeded = channel.isOpen() && !sealed;
        try (FileChannel closingLog = channel; OffsetIndex closingIndex = index) {
            if (forceNeeded) {
                closingLog.force(true);
            }
        } catch (IOException e) {
            throw new ShrikeIOException("cannot close segment " + logFile, e);
        }
    }

    /**
     * What a frame's fixed prefix says about it: how long it is, and which offset it stores. That is
     * everything a walk stepping over frames needs, and it is deliberately less than a decode.
     *
     * @param lengthBytes  the frame's {@code length} field, already range-checked
     * @param storedOffset the offset the frame carries
     */
    private record FrameHeader(int lengthBytes, long storedOffset) {

        /** @return the bytes the whole frame occupies, length field included */
        long frameBytes() {
            return RecordFrame.LENGTH_FIELD_BYTES + (long) lengthBytes;
        }
    }

    /**
     * Where a range of whole frames starts inside this segment and how many bytes it occupies. Both
     * ways of serving a fetch are built on one of these, which is what keeps them agreeing.
     *
     * @param positionBytes the byte position the first frame of the range starts at
     * @param lengthBytes   how many bytes of whole frames the range covers, never a partial one
     */
    private record FramePositions(long positionBytes, int lengthBytes) {
    }

    /**
     * Reads the prefix of the frame at {@code positionBytes} and range-checks its length before
     * anything is sized to it: the length is the one field no checksum can vouch for, so a corrupt or
     * hostile value is refused rather than believed.
     *
     * @param positionBytes where the frame starts
     * @param location      what to quote if the bytes are not a frame header
     * @return what the prefix says
     * @throws CorruptRecordException if the segment ends inside the header or the length is impossible
     * @throws IOException            if the read fails
     */
    private FrameHeader headerAt(long positionBytes, RecordLocation location) throws IOException {
        ByteBuffer prefix = ByteBuffer.allocate(RecordFrame.PREFIX_BYTES);
        ByteChannels.readFully(channel, prefix, positionBytes);
        if (prefix.hasRemaining()) {
            throw new CorruptRecordException(location, "the segment ends inside a frame header");
        }
        prefix.flip();

        int lengthBytes = prefix.getInt(0);
        int largestLengthBytes = config.maxRecordBytes() - RecordFrame.LENGTH_FIELD_BYTES;
        if (lengthBytes < RecordFrame.MINIMUM_LENGTH_BYTES || lengthBytes > largestLengthBytes) {
            throw new CorruptRecordException(location, "frame length " + lengthBytes + " is outside ["
                    + RecordFrame.MINIMUM_LENGTH_BYTES + ", " + largestLengthBytes + "]");
        }
        return new FrameHeader(lengthBytes, prefix.getLong(RecordFrame.OFFSET_FIELD_POSITION_BYTES));
    }

    private StoredRecord decodeFrameAt(long positionBytes, int lengthBytes, RecordLocation location)
            throws IOException {
        ByteBuffer body = ByteBuffer.allocate(lengthBytes);
        ByteChannels.readFully(channel, body, positionBytes + RecordFrame.LENGTH_FIELD_BYTES);
        if (body.hasRemaining()) {
            throw new CorruptRecordException(location, "the segment holds " + body.position() + " of the "
                    + lengthBytes + " bytes the frame declares");
        }
        return RecordFrame.decode(body.flip(), location);
    }

    /**
     * Rebuilds the index of a sealed segment from its log. The log itself is left alone: damage inside
     * a sealed segment is reported when the record is read, never repaired at startup.
     */
    private void rebuildIndex() {
        walkFramesRebuildingIndex();
    }

    /**
     * Reads the timestamp of this segment's last record, which is the largest timestamp it holds:
     * within one segment the records were appended in order, by one thread, from one clock. The index
     * is what makes it cheap — the lookup starts at the last indexed record, so the walk that follows
     * covers at most {@code index.interval.bytes} of frames rather than the whole file.
     *
     * <p>Reading one frame rather than all of them is exact for a clock that only moves forward, which
     * is what {@code TimeSource} is. A clock that stepped backwards inside this segment would leave the
     * last record short of the largest timestamp by however far it stepped, so the segment would be
     * judged that much older than it is — bounded by the step, and only ever in the direction of
     * deleting a segment slightly early rather than keeping one past its bound.
     *
     * <p>A frame this walk cannot believe leaves the timestamp unknown and says so in a WARN, rather
     * than failing the start or reporting an age it does not have. That is the same rule the rest of
     * recovery follows: damage inside a sealed segment is reported when the record is read, and the
     * healthy records beside it go on being served — which they could not do if a segment nobody could
     * date were a segment retention deleted.
     */
    private void readLargestTimestampFromLastFrame() {
        if (isEmpty()) {
            return;
        }

        long positionBytes = index.floorPositionBytes(Long.MAX_VALUE);
        try {
            long lastFramePositionBytes = positionBytes;
            FrameHeader lastHeader = null;
            while (positionBytes < sizeBytes) {
                RecordLocation stepping = new RecordLocation(topic, partition, baseOffset, positionBytes, logFile);
                lastHeader = headerAt(positionBytes, stepping);
                lastFramePositionBytes = positionBytes;
                positionBytes += lastHeader.frameBytes();
            }
            if (lastHeader == null) {
                // The index pointed at or past the end of a log that is not empty, which isUsableFor
                // has already refused; reaching here would mean that check and this one disagree.
                throw new CorruptRecordException(new RecordLocation(topic, partition, baseOffset, positionBytes,
                        logFile), "the index starts this walk at the end of the segment");
            }

            // The location names the offset the frame itself carries, because which record is last is
            // exactly what this walk was trying to find out.
            RecordLocation last = new RecordLocation(topic, partition, lastHeader.storedOffset(),
                    lastFramePositionBytes, logFile);
            rememberTimestamp(decodeFrameAt(lastFramePositionBytes, lastHeader.lengthBytes(), last)
                    .timestampMillis());
        } catch (CorruptRecordException e) {
            LOGGER.log(System.Logger.Level.WARNING, () -> "cannot date the sealed segment " + logFile + ": " + e
                    + "; retention will not delete it on age, and its healthy records still read");
        } catch (IOException e) {
            throw new ShrikeIOException("cannot read the last frame of " + logFile + " near position " + positionBytes,
                    e);
        }
    }

    /**
     * Keeps the larger of the timestamp a record carries and the largest one seen so far, so that a
     * clock which stepped backwards between two appends cannot make this segment look newer than the
     * records in it.
     *
     * @param timestampMillis the timestamp of a record just written or just read back
     */
    private void rememberTimestamp(long timestampMillis) {
        if (largestTimestampMillis == NO_TIMESTAMP_MILLIS || timestampMillis > largestTimestampMillis) {
            largestTimestampMillis = timestampMillis;
        }
    }

    /**
     * Walks the tail segment and cuts off whatever follows its last whole frame.
     *
     * @param logLengthBytes the size of the log file before recovery
     * @throws IOException if the truncation fails
     */
    private void truncateTornTail(long logLengthBytes) throws IOException {
        long validBytes = walkFramesRebuildingIndex();
        sizeBytes = validBytes;
        if (validBytes < logLengthBytes) {
            channel.truncate(validBytes);
            long tornBytes = logLengthBytes - validBytes;
            long nextOffset = nextOffset();
            LOGGER.log(System.Logger.Level.WARNING, () -> "truncated a torn tail: dropped " + tornBytes
                    + " bytes from positionBytes=" + validBytes + " onwards, topic=" + topic + " partition="
                    + partition + " file=" + logFile + "; the next offset is " + nextOffset);
        }
        // The write position is set by hand: truncate only moves it when it was past the new end, and
        // an append that started from the wrong position would overwrite records that survived.
        channel.position(validBytes);
    }

    /**
     * Walks the frames of this segment from its first byte, validating each one the way a read does,
     * and rebuilds the index as it goes. The walk stops at the first frame that is incomplete or fails
     * a check.
     *
     * @return the byte position just past the last whole, valid frame
     */
    private long walkFramesRebuildingIndex() {
        index.clear();
        recordCount = 0;
        bytesSinceLastIndexEntry = 0;
        largestTimestampMillis = NO_TIMESTAMP_MILLIS;

        long logLengthBytes = sizeBytes;
        long positionBytes = 0L;
        try {
            while (positionBytes < logLengthBytes) {
                long remainingBytes = logLengthBytes - positionBytes;
                if (remainingBytes < RecordFrame.LENGTH_FIELD_BYTES) {
                    break;
                }

                ByteBuffer lengthField = ByteBuffer.allocate(RecordFrame.LENGTH_FIELD_BYTES);
                ByteChannels.readFully(channel, lengthField, positionBytes);
                if (lengthField.hasRemaining()) {
                    break;
                }
                int lengthBytes = lengthField.flip().getInt();
                int largestLengthBytes = config.maxRecordBytes() - RecordFrame.LENGTH_FIELD_BYTES;
                if (lengthBytes < RecordFrame.MINIMUM_LENGTH_BYTES || lengthBytes > largestLengthBytes) {
                    break;
                }
                long frameBytes = RecordFrame.LENGTH_FIELD_BYTES + (long) lengthBytes;
                if (frameBytes > remainingBytes) {
                    break;
                }

                ByteBuffer body = ByteBuffer.allocate(lengthBytes);
                ByteChannels.readFully(channel, body, positionBytes + RecordFrame.LENGTH_FIELD_BYTES);
                if (body.hasRemaining()) {
                    break;
                }

                long offset = baseOffset + recordCount;
                RecordLocation location = new RecordLocation(topic, partition, offset, positionBytes, logFile);
                try {
                    // The walk already decodes every frame it steps over, so the largest timestamp in
                    // the segment is something it learns rather than something it costs.
                    rememberTimestamp(RecordFrame.decode(body.flip(), location).timestampMillis());
                } catch (CorruptRecordException e) {
                    // A frame that fails its checksum, its magic, or its own offset is where this log
                    // stops being trustworthy, so it ends the walk rather than being reported.
                    break;
                }

                indexIfDue(offset, positionBytes, (int) frameBytes);
                recordCount++;
                positionBytes += frameBytes;
            }
        } catch (IOException e) {
            throw new ShrikeIOException("cannot scan segment " + logFile + " from position " + positionBytes, e);
        }
        return positionBytes;
    }

    /**
     * The index rule, and the only place that decides what goes into an index: a record is indexed
     * when {@code index.interval.bytes} of records have gone by since the last entry. It reads nothing
     * but the bytes of the log, so growing an index record by record and rebuilding it in one pass
     * produce the same entries.
     *
     * @param offset        the offset of the record just written or just walked
     * @param positionBytes where that record's frame starts
     * @param frameBytes    how many bytes it occupies
     */
    private void indexIfDue(long offset, long positionBytes, int frameBytes) {
        if (bytesSinceLastIndexEntry >= config.indexIntervalBytes()) {
            index.append(offset, positionBytes);
            bytesSinceLastIndexEntry = 0;
        }
        bytesSinceLastIndexEntry += frameBytes;
    }
}
