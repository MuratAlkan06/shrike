package io.shrike.core.protocol;

import io.shrike.core.log.MalformedFrameException;
import io.shrike.core.log.RecordFrame;
import io.shrike.core.log.StoredRecord;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The records block a fetch response carries, read back into records.
 *
 * <p>The broker copies a byte range of a partition's log into the response without looking inside it,
 * so what arrives here is the on-disk record frame, byte for byte:
 *
 * <pre>
 * length:int32 | crc32c:uint32 | magic:uint8 | attributes:uint8 | offset:int64 | timestamp:int64
 *              | keyLen:int32 | key | valueLen:int32 | value
 * </pre>
 *
 * <p>That is why this class parses frames rather than a format of its own: there is one record layout
 * in this system, {@link RecordFrame} owns it, and a consumer that could disagree with the disk about
 * what a record is would be a second copy of the truth.
 *
 * <p>Whole frames only. A block that ends inside a frame is a decode error rather than a short read,
 * because the broker sends whole frames and a partial one means the bytes are not what they claim.
 */
public final class WireRecords {

    private WireRecords() {
    }

    /**
     * Reads every record in a block, checking each frame's length against the bytes actually in hand
     * and each frame's checksum and magic before trusting a field of it.
     *
     * @param records the block, positioned at its first byte; the caller's buffer is not consumed
     * @return the records the block holds, in the order they were stored, which is by ascending offset
     * @throws MalformedFrameException if a frame's length is out of range, its checksum or magic
     *                                 fails, or the block ends inside a frame
     */
    public static List<StoredRecord> decode(ByteBuffer records) {
        Objects.requireNonNull(records, "records");

        // Neither consumed nor trusted to be big-endian: every field of this protocol is big-endian
        // and one duplicate says so once, rather than every read hoping the caller agreed.
        ByteBuffer block = records.duplicate().order(ByteOrder.BIG_ENDIAN);
        List<StoredRecord> decoded = new ArrayList<>();
        while (block.hasRemaining()) {
            int positionBytes = block.position();
            if (block.remaining() < RecordFrame.LENGTH_FIELD_BYTES) {
                throw new MalformedFrameException("the records block ends at positionBytes=" + positionBytes
                        + " inside a length field: " + block.remaining() + " of "
                        + RecordFrame.LENGTH_FIELD_BYTES + " bytes");
            }

            // Range-checked before anything is sized to it, and against the bytes in hand rather than
            // against what the frame would like to be true.
            int lengthBytes = block.getInt();
            if (lengthBytes < RecordFrame.MINIMUM_LENGTH_BYTES || lengthBytes > block.remaining()) {
                throw new MalformedFrameException("the frame at positionBytes=" + positionBytes + " declares "
                        + lengthBytes + " bytes, which is outside [" + RecordFrame.MINIMUM_LENGTH_BYTES + ", "
                        + block.remaining() + "]");
            }

            ByteBuffer body = block.slice(block.position(), lengthBytes).order(ByteOrder.BIG_ENDIAN);
            try {
                decoded.add(RecordFrame.decodeBody(body));
            } catch (MalformedFrameException e) {
                throw new MalformedFrameException("the frame at positionBytes=" + positionBytes
                        + " of the records block is not a record: " + e.getMessage());
            }
            block.position(block.position() + lengthBytes);
        }
        return List.copyOf(decoded);
    }
}
