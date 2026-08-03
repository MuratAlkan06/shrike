package io.shrike.clients;

import io.shrike.core.protocol.ErrorCode;
import java.util.Objects;

/**
 * The broker answered with an error code instead of a body.
 *
 * <p>An error response is a code and an empty body, so the code is the whole answer and this
 * exception carries it rather than a message the broker invented. What the request was, and which
 * correlation id it travelled under, come from this client's own bookkeeping — the broker never sends
 * either back.
 *
 * <p>The connection survives this: the broker refused a request, not the caller, and the next call on
 * the same connection is served normally.
 */
public final class BrokerErrorException extends ShrikeClientException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;
    private final int correlationId;

    /**
     * @param errorCode     the code the response envelope carried
     * @param correlationId the number this client sent the refused request under
     * @param request       what was asked, in words, for the message
     */
    BrokerErrorException(ErrorCode errorCode, int correlationId, String request) {
        super("the broker answered " + Objects.requireNonNull(request, "request") + " with "
                + Objects.requireNonNull(errorCode, "errorCode") + " (correlationId=" + correlationId + ")");
        this.errorCode = errorCode;
        this.correlationId = correlationId;
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
}
