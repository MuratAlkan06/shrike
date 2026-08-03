package io.shrike.core.log;

import java.util.Objects;

/**
 * The bytes handed over as a record frame do not form a record: the checksum fails, the magic is not
 * this build's, or a declared length contradicts the frame.
 *
 * <p>It says what is wrong with the bytes and nothing about where they came from, because the same
 * layout arrives from two places. A caller that read the frame off disk wraps this in a
 * {@link CorruptRecordException} that names the topic, partition, offset, byte position, and file; a
 * caller that read it off a socket names the position inside the block it was parsing. Neither
 * message is possible from here, and inventing one would be a location this class cannot vouch for.
 */
public final class MalformedFrameException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public MalformedFrameException(String detail) {
        super(Objects.requireNonNull(detail, "detail"));
    }
}
