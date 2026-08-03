package io.shrike.core.protocol;

import java.util.Optional;

/**
 * The single int16 a response envelope carries to say what happened. {@link #NONE} means the body
 * that follows is the answer; anything else means the body is empty and this code is the whole
 * answer.
 *
 * <p>The numbers are the wire, so they are frozen: a code may be added, never renumbered.
 */
public enum ErrorCode {

    /** The request succeeded and the response body holds its answer. */
    NONE(0),

    /** The topic, or that partition of it, is not one the broker knows. */
    UNKNOWN_TOPIC_OR_PARTITION(1),

    /** The offset asked for is outside the range the partition can serve. */
    OFFSET_OUT_OF_RANGE(2),

    /** A record's stored bytes no longer match their checksum. */
    CORRUPT_RECORD(3),

    /** A record is larger than the broker will store. */
    FRAME_TOO_LARGE(4),

    /** The bytes parsed as an envelope, but their contents break a rule of the protocol. */
    INVALID_REQUEST(5),

    /** The api key exists, but not at the version the request asked for. */
    UNSUPPORTED_VERSION(6),

    /** A topic by that name already exists. */
    TOPIC_ALREADY_EXISTS(7),

    /** The broker failed for a reason of its own, which it does not describe to the caller. */
    INTERNAL(99);

    /** {@code values()} copies its array on every call, so the lookup reads one made once. */
    private static final ErrorCode[] ALL = values();

    private final short code;

    ErrorCode(int code) {
        this.code = (short) code;
    }

    /**
     * @return the number this code travels as
     */
    public short code() {
        return code;
    }

    /**
     * @param code the number read out of a response envelope
     * @return the code it names, or empty when this build does not know that number
     */
    public static Optional<ErrorCode> fromCode(short code) {
        for (ErrorCode candidate : ALL) {
            if (candidate.code == code) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }
}
