package io.shrike.core.log;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * One segment's sparse offset index: a file of fixed-width entries that say "the record this many
 * offsets into the segment starts at this byte position".
 *
 * <pre>
 * relativeOffset:int32 | position:int32
 * </pre>
 *
 * <p>Both fields are big-endian, so an entry is {@link #ENTRY_BYTES} bytes and the file's size is
 * always a whole number of entries. The offset is stored relative to the segment's base offset and
 * the position is stored as an int32; {@link LogConfig#MAX_SEGMENT_BYTES} is what keeps both inside
 * those fields.
 *
 * <p>The index is <em>derived</em> data. Every entry restates something the log file already says, so
 * a missing, misaligned, or out-of-range index is deleted and rebuilt by scanning the log rather than
 * believed. Nothing here is load-bearing for correctness: an index with no entries at all still
 * yields correct reads, only slower ones, because a lookup that finds no entry starts its scan at the
 * first byte of the segment.
 */
final class OffsetIndex implements Closeable {

    /** {@code relativeOffset:int32 | position:int32}. */
    static final int ENTRY_BYTES = Integer.BYTES + Integer.BYTES;

    private static final int INITIAL_ENTRY_CAPACITY = 64;

    private final Path indexFile;
    private final FileChannel channel;
    private final long baseOffset;

    // The three fields below are confined to: the single thread that owns the segment this index
    // belongs to. Readers never mutate them, and a segment has one writer.
    private int[] relativeOffsets;
    private int[] positionsBytes;
    private int entryCount;

    private OffsetIndex(Path indexFile, FileChannel channel, long baseOffset, int[] relativeOffsets,
                        int[] positionsBytes, int entryCount) {
        this.indexFile = indexFile;
        this.channel = channel;
        this.baseOffset = baseOffset;
        this.relativeOffsets = relativeOffsets;
        this.positionsBytes = positionsBytes;
        this.entryCount = entryCount;
    }

    /**
     * Opens an index file with no entries in it, emptying whatever was there. The caller is about to
     * fill it, either record by record as the segment grows or in one pass over the log.
     *
     * @param indexFile  the {@code .index} file of one segment
     * @param baseOffset the base offset of that segment
     * @return the empty index
     * @throws ShrikeIOException if the file cannot be opened or emptied
     */
    static OffsetIndex createEmpty(Path indexFile, long baseOffset) {
        try {
            FileChannel channel = FileChannel.open(indexFile, StandardOpenOption.CREATE, StandardOpenOption.READ,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            return new OffsetIndex(indexFile, channel, baseOffset, new int[INITIAL_ENTRY_CAPACITY],
                    new int[INITIAL_ENTRY_CAPACITY], 0);
        } catch (IOException e) {
            throw new ShrikeIOException("cannot create index file " + indexFile, e);
        }
    }

    /**
     * Reads every entry of an existing index file into memory. The caller has already checked that the
     * file's size is a whole number of entries; whether the entries make sense is
     * {@link #isUsableFor(long)}'s question.
     *
     * @param indexFile  the {@code .index} file of one segment
     * @param baseOffset the base offset of that segment
     * @return the loaded index, positioned to append after its last entry
     * @throws ShrikeIOException if the file cannot be read
     */
    static OffsetIndex load(Path indexFile, long baseOffset) {
        try {
            FileChannel channel = FileChannel.open(indexFile, StandardOpenOption.READ, StandardOpenOption.WRITE);
            int entryCount = (int) (channel.size() / ENTRY_BYTES);
            int capacity = Math.max(entryCount, INITIAL_ENTRY_CAPACITY);
            int[] relativeOffsets = new int[capacity];
            int[] positionsBytes = new int[capacity];

            ByteBuffer entries = ByteBuffer.allocate(entryCount * ENTRY_BYTES);
            ByteChannels.readFully(channel, entries, 0L);
            entries.flip();
            for (int entry = 0; entry < entryCount; entry++) {
                relativeOffsets[entry] = entries.getInt();
                positionsBytes[entry] = entries.getInt();
            }

            channel.position((long) entryCount * ENTRY_BYTES);
            return new OffsetIndex(indexFile, channel, baseOffset, relativeOffsets, positionsBytes, entryCount);
        } catch (IOException e) {
            throw new ShrikeIOException("cannot read index file " + indexFile, e);
        }
    }

    /**
     * Empties the index, file included, so it can be rebuilt from the log it describes.
     *
     * @throws ShrikeIOException if the file cannot be emptied
     */
    void clear() {
        try {
            channel.truncate(0L);
            channel.position(0L);
            entryCount = 0;
        } catch (IOException e) {
            throw new ShrikeIOException("cannot empty index file " + indexFile, e);
        }
    }

    /**
     * Adds one entry, which must sit after every entry already there.
     *
     * @param offset        the offset of the record the entry points at
     * @param positionBytes the byte position of that record's frame within the segment
     * @throws ShrikeIOException if the entry cannot be written
     */
    void append(long offset, long positionBytes) {
        long relativeOffset = offset - baseOffset;
        if (relativeOffset < 0 || relativeOffset > Integer.MAX_VALUE || positionBytes < 0
                || positionBytes > Integer.MAX_VALUE) {
            throw new IllegalStateException("index entry offset=" + offset + " positionBytes=" + positionBytes
                    + " does not fit the int32 fields of " + indexFile + ", whose segment starts at offset "
                    + baseOffset);
        }

        ByteBuffer entry = ByteBuffer.allocate(ENTRY_BYTES);
        entry.putInt((int) relativeOffset);
        entry.putInt((int) positionBytes);
        entry.flip();
        try {
            ByteChannels.writeFully(channel, entry);
        } catch (IOException e) {
            throw new ShrikeIOException("cannot append an entry for offset " + offset + " to " + indexFile, e);
        }

        if (entryCount == relativeOffsets.length) {
            relativeOffsets = grown(relativeOffsets);
            positionsBytes = grown(positionsBytes);
        }
        relativeOffsets[entryCount] = (int) relativeOffset;
        positionsBytes[entryCount] = (int) positionBytes;
        entryCount++;
    }

    /**
     * The byte position a search for {@code offset} should start scanning from: the position of the
     * last indexed record at or before it, or the first byte of the segment when no entry is that low.
     *
     * @param offset the offset being looked up
     * @return a byte position within the segment, never past the record being looked for
     */
    long floorPositionBytes(long offset) {
        long relativeOffset = offset - baseOffset;
        int low = 0;
        int high = entryCount - 1;
        long positionBytes = 0L;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (relativeOffsets[middle] <= relativeOffset) {
                positionBytes = positionsBytes[middle];
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return positionBytes;
    }

    /**
     * Whether this index could have been derived from a log of {@code logLengthBytes} bytes: entries
     * climb in both fields and every position falls inside the log. An index with no entries passes,
     * because it claims nothing.
     *
     * @param logLengthBytes the size of the segment's log file
     * @return {@code true} when nothing in the index contradicts that log
     */
    boolean isUsableFor(long logLengthBytes) {
        long previousRelativeOffset = -1L;
        long previousPositionBytes = -1L;
        for (int entry = 0; entry < entryCount; entry++) {
            if (relativeOffsets[entry] <= previousRelativeOffset || positionsBytes[entry] <= previousPositionBytes) {
                return false;
            }
            if (positionsBytes[entry] < 0 || positionsBytes[entry] >= logLengthBytes) {
                return false;
            }
            previousRelativeOffset = relativeOffsets[entry];
            previousPositionBytes = positionsBytes[entry];
        }
        return true;
    }

    int entryCount() {
        return entryCount;
    }

    /**
     * @return the bytes this index occupies on disk
     */
    long sizeBytes() {
        return (long) entryCount * ENTRY_BYTES;
    }

    /**
     * Forces the index file to the device. Called when its segment is sealed, which is the one moment
     * the index has to be complete on disk: a sealed segment is never scanned again unless its index
     * turns out to be unusable.
     *
     * @throws IOException if the force fails
     */
    void force() throws IOException {
        channel.force(true);
    }

    /**
     * Closes the index file without forcing it. The index of a segment that is still growing is
     * rebuilt at the next open anyway, so there is nothing here worth an fsync.
     *
     * @throws ShrikeIOException if the file cannot be closed
     */
    @Override
    public void close() {
        if (!channel.isOpen()) {
            return;
        }
        try {
            channel.close();
        } catch (IOException e) {
            throw new ShrikeIOException("cannot close index file " + indexFile, e);
        }
    }

    private static int[] grown(int[] entries) {
        int[] larger = new int[entries.length * 2];
        System.arraycopy(entries, 0, larger, 0, entries.length);
        return larger;
    }
}
