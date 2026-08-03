package io.shrike.core.log;

import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/**
 * The on-disk record frame: the one place that knows the byte layout of a stored record.
 *
 * <p>Every field is big-endian and every header field is fixed width:
 *
 * <pre>
 * length:int32 | crc32c:uint32 | magic:uint8 | attributes:uint8 | offset:int64 | timestamp:int64
 *              | keyLen:int32 | key | valueLen:int32 | value
 * </pre>
 *
 * <ul>
 *   <li>{@code length} counts every byte after itself, so a frame occupies {@code 4 + length} bytes.
 *   <li>{@code crc32c} covers {@code magic} through the last byte of {@code value}: the length is
 *       excluded because a reader must range-check it before it allocates anything, which is before
 *       it could possibly have verified a checksum.
 *   <li>{@code keyLen} is -1 for a record appended without a key and 0 for a key of zero bytes; the
 *       two stay distinguishable on disk. {@code valueLen} is never negative.
 *   <li>{@code attributes} is reserved and written as 0. A reader ignores its bits so a later slice
 *       can spend them without invalidating today's files.
 * </ul>
 */
final class RecordFrame {

    static final int LENGTH_FIELD_BYTES = Integer.BYTES;
    static final int CRC_FIELD_BYTES = Integer.BYTES;

    /** The value of {@code keyLen} for a record appended without a key. */
    static final int NULL_KEY_LENGTH = -1;

    static final byte MAGIC = 0;
    static final byte ATTRIBUTES = 0;

    /** The smallest legal {@code length}: crc, magic, attributes, offset, timestamp, and both lengths. */
    static final int MINIMUM_LENGTH_BYTES = CRC_FIELD_BYTES + Byte.BYTES + Byte.BYTES + Long.BYTES + Long.BYTES
            + Integer.BYTES + Integer.BYTES;

    private RecordFrame() {
    }

    /**
     * The bytes a record occupies on disk, framing included. Computed in long arithmetic so that a
     * hostile pair of lengths cannot overflow into a small positive number and slip past a bound.
     *
     * @param keyLength   the key's length in bytes, or {@link #NULL_KEY_LENGTH} for no key
     * @param valueLength the value's length in bytes
     * @return the total frame size in bytes
     */
    static long frameBytes(int keyLength, int valueLength) {
        long keyBytes = keyLength == NULL_KEY_LENGTH ? 0L : keyLength;
        return (long) LENGTH_FIELD_BYTES + MINIMUM_LENGTH_BYTES + keyBytes + valueLength;
    }

    /**
     * Lays out one record, checksum included, into a buffer positioned at 0 and ready to be written.
     * The caller has already checked the frame size against {@code max.record.bytes}.
     *
     * @param offset          the offset the log assigned to this record
     * @param timestampMillis epoch milliseconds from the log's time source
     * @param key             the key bytes, or {@code null} for no key
     * @param value           the value bytes, never {@code null}
     * @return the encoded frame
     */
    static ByteBuffer encode(long offset, long timestampMillis, byte[] key, byte[] value) {
        int keyLength = key == null ? NULL_KEY_LENGTH : key.length;
        int frameBytes = (int) frameBytes(keyLength, value.length);

        ByteBuffer frame = ByteBuffer.allocate(frameBytes);
        frame.putInt(frameBytes - LENGTH_FIELD_BYTES);
        frame.putInt(0);
        frame.put(MAGIC);
        frame.put(ATTRIBUTES);
        frame.putLong(offset);
        frame.putLong(timestampMillis);
        frame.putInt(keyLength);
        if (key != null) {
            frame.put(key);
        }
        frame.putInt(value.length);
        frame.put(value);
        frame.flip();

        CRC32C checksum = new CRC32C();
        checksum.update(frame.duplicate().position(LENGTH_FIELD_BYTES + CRC_FIELD_BYTES));
        frame.putInt(LENGTH_FIELD_BYTES, (int) checksum.getValue());
        return frame;
    }

    /**
     * Reads one record out of the bytes that follow the length field, verifying the checksum before
     * trusting any field and bounds-checking every length before allocating for it.
     *
     * @param body     every byte after the length field, positioned at 0 and limited to {@code length}
     * @param location where these bytes came from, quoted in any error
     * @return the decoded record
     * @throws CorruptRecordException if the checksum fails or a field contradicts the frame
     */
    static StoredRecord decode(ByteBuffer body, RecordLocation location) {
        int storedChecksum = body.getInt();
        CRC32C checksum = new CRC32C();
        checksum.update(body.duplicate());
        int computedChecksum = (int) checksum.getValue();
        if (storedChecksum != computedChecksum) {
            throw new CorruptRecordException(location, "crc32c mismatch: the frame stores 0x%08x but its bytes hash to 0x%08x"
                    .formatted(storedChecksum, computedChecksum));
        }

        byte magic = body.get();
        if (magic != MAGIC) {
            throw new CorruptRecordException(location, "unknown frame magic " + magic + ", this build writes " + MAGIC);
        }
        body.get(); // attributes: reserved, so its bits are stepped over rather than judged.

        long storedOffset = body.getLong();
        if (storedOffset != location.offset()) {
            throw new CorruptRecordException(location, "the frame stores offset " + storedOffset + ", not the offset that was read");
        }
        long timestampMillis = body.getLong();

        int keyLength = body.getInt();
        if (keyLength < NULL_KEY_LENGTH || keyLength > body.remaining() - Integer.BYTES) {
            throw new CorruptRecordException(location, "key length " + keyLength + " does not fit the frame");
        }
        byte[] key = null;
        if (keyLength != NULL_KEY_LENGTH) {
            key = new byte[keyLength];
            body.get(key);
        }

        int valueLength = body.getInt();
        if (valueLength != body.remaining()) {
            throw new CorruptRecordException(location, "value length " + valueLength + " does not match the " + body.remaining()
                    + " bytes left in the frame");
        }
        byte[] value = new byte[valueLength];
        body.get(value);

        return new StoredRecord(storedOffset, timestampMillis, key, value);
    }
}
