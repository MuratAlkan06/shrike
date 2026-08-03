package io.shrike.core.protocol;

import java.util.Objects;

/**
 * What came of reading one request, in the three shapes a connection owes a different thing to.
 *
 * <ul>
 *   <li>{@link Accepted} — a whole frame holding a request this build understands: serve it and
 *       answer with its correlation id.
 *   <li>{@link Refused} — the envelope parsed but its contents did not: answer with this error code
 *       and an empty body, and keep the connection, because the caller is talking, just wrongly.
 *   <li>{@link BrokenFrame} — the framing itself is broken: close the connection and send nothing,
 *       because there is no correlation id worth trusting to address a reply to.
 * </ul>
 */
public sealed interface RequestDecoding {

    /**
     * @param correlationId the client's number, to be echoed in the response
     * @param request       the request the body holds
     */
    record Accepted(int correlationId, Request request) implements RequestDecoding {

        public Accepted {
            Objects.requireNonNull(request, "request");
        }
    }

    /**
     * @param correlationId the client's number, to be echoed in the error response
     * @param errorCode     the code the response carries
     * @param reason        why the request was refused, for the broker's own log. It never goes on
     *                      the wire: an error response is a code and an empty body, so a caller
     *                      probing the parser learns nothing from it.
     */
    record Refused(int correlationId, ErrorCode errorCode, String reason) implements RequestDecoding {

        public Refused {
            Objects.requireNonNull(errorCode, "errorCode");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * @param reason why the framing could not be trusted, for the broker's own log
     */
    record BrokenFrame(String reason) implements RequestDecoding {

        public BrokenFrame {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
