package io.shrike.core.log;

import java.util.Objects;

/**
 * The bytes on disk are not the bytes that were written: the stored CRC32C does not match the frame,
 * or the frame's own fields contradict each other. The log fails the read instead of returning a
 * record it cannot vouch for.
 */
public final class CorruptRecordException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    // Path is not serializable, so the location travels in the message and this field does not.
    private final transient RecordLocation location;

    public CorruptRecordException(RecordLocation location, String detail) {
        super("corrupt record at " + Objects.requireNonNull(location, "location") + ": "
                + Objects.requireNonNull(detail, "detail"));
        this.location = location;
    }

    /**
     * @return the topic, partition, offset, byte position, and file of the frame that failed
     */
    public RecordLocation location() {
        return location;
    }
}
