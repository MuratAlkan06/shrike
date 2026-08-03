package io.shrike.clients;

import io.shrike.core.protocol.ErrorCode;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * The broker answered with an error code instead of a body.
 *
 * <p>An error response is a code and an empty body, so the code is the whole answer and this
 * exception carries it rather than a message the broker invented. What the request was, and which
 * correlation id it travelled under, come from this client's own bookkeeping — the broker never sends
 * either back.
 *
 * <p>{@link ErrorCode#OFFSET_OUT_OF_RANGE} is the one exception, and {@link #logStartOffset()} is
 * where it arrives. A consumer whose committed offset has fallen behind the broker's retention gets
 * that code, and without the offset the partition now starts at it would have to guess between
 * re-reading everything and skipping to the end. With it, resuming is a decision the caller makes
 * with the number in hand.
 *
 * <p>The connection survives this: the broker refused a request, not the caller, and the next call on
 * the same connection is served normally.
 */
public final class BrokerErrorException extends ShrikeClientException {

    private static final long serialVersionUID = 1L;

    /** No code but {@link ErrorCode#OFFSET_OUT_OF_RANGE} carries one, and an offset is never negative. */
    private static final long NO_LOG_START_OFFSET = -1L;

    private final ErrorCode errorCode;
    private final int correlationId;

    /**
     * The offset the partition now starts at, or {@link #NO_LOG_START_OFFSET}. Held as a {@code long}
     * rather than as the {@code OptionalLong} the accessor hands out because a throwable is
     * serializable and {@code OptionalLong} is not, and a field that survived one and not the other
     * would be a getter that answers {@code null}.
     */
    private final long logStartOffset;

    /**
     * @param errorCode      the code the response envelope carried
     * @param correlationId  the number this client sent the refused request under
     * @param request        what was asked, in words, for the message
     * @param logStartOffset the offset the partition now starts at, present exactly when the code is
     *                       {@link ErrorCode#OFFSET_OUT_OF_RANGE}
     */
    BrokerErrorException(ErrorCode errorCode, int correlationId, String request, OptionalLong logStartOffset) {
        super(describe(errorCode, correlationId, request, logStartOffset));
        this.errorCode = errorCode;
        this.correlationId = correlationId;
        this.logStartOffset = logStartOffset.orElse(NO_LOG_START_OFFSET);
    }

    private static String describe(ErrorCode errorCode, int correlationId, String request,
                                   OptionalLong logStartOffset) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(errorCode, "errorCode");
        Objects.requireNonNull(logStartOffset, "logStartOffset");

        String message = "the broker answered " + request + " with " + errorCode + " (correlationId="
                + correlationId;
        if (logStartOffset.isPresent()) {
            message += ", logStartOffset=" + logStartOffset.getAsLong();
        }
        return message + ")";
    }

    /**
     * @return the code the broker answered with, which is never {@link ErrorCode#NONE}
     */
    public ErrorCode errorCode() {
        return errorCode;
    }

    /**
     * @return the correlation id the refused request travelled under
     */
    public int correlationId() {
        return correlationId;
    }

    /**
     * @return the offset the partition can still be read from, present exactly when
     *         {@link #errorCode()} is {@link ErrorCode#OFFSET_OUT_OF_RANGE}. It is the oldest record
     *         the broker still holds, so a consumer that resets to it re-reads what survived retention
     *         rather than skipping past it
     */
    public OptionalLong logStartOffset() {
        return logStartOffset == NO_LOG_START_OFFSET ? OptionalLong.empty() : OptionalLong.of(logStartOffset);
    }
}
