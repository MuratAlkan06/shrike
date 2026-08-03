package io.shrike.core.net;

import io.shrike.core.protocol.ErrorCode;
import io.shrike.core.protocol.Response;
import java.util.Objects;

/**
 * What the broker owes one request it understood: a body, an error code that is the whole answer, or
 * the one error code that comes with a number.
 *
 * <p>It is a sealed type rather than an exception because all three outcomes are ordinary. An unknown
 * topic, an offset outside a partition's range, and a topic that already exists are all things a
 * caller is entitled to ask about and be told, and a connection loop that handled one and forgot the
 * other would still compile if these travelled as exceptions.
 */
sealed interface Answer {

    /**
     * @param response the body to send, with {@link ErrorCode#NONE} in the envelope
     */
    record Served(Response response) implements Answer {

        public Served {
            Objects.requireNonNull(response, "response");
        }
    }

    /**
     * @param errorCode the code the response carries instead of a body
     * @param reason    why, for the broker's own log. It never goes on the wire: an error response is
     *                  a code and an empty body, so a caller probing the broker learns nothing from it
     */
    record Refused(ErrorCode errorCode, String reason) implements Answer {

        public Refused {
            Objects.requireNonNull(errorCode, "errorCode");
            Objects.requireNonNull(reason, "reason");
            if (errorCode == ErrorCode.NONE) {
                throw new IllegalArgumentException("a refusal cannot carry " + ErrorCode.NONE);
            }
            if (errorCode == ErrorCode.OFFSET_OUT_OF_RANGE) {
                throw new IllegalArgumentException("a refusal carrying " + ErrorCode.OFFSET_OUT_OF_RANGE
                        + " is an OutOfRange, because that code owes the caller a log start offset");
            }
        }
    }

    /**
     * The one refusal that answers with a number as well as a code. It is its own shape rather than a
     * field on {@link Refused} so that the connection loop cannot forget to write the number, and so
     * that the two places which produce it — a fetch below the log start offset, and a commit past the
     * high-water mark — cannot produce it without having one.
     *
     * @param logStartOffset the lowest offset that partition can still serve, read as the answer is
     *                       decided. Retention only moves it forward, so a client that resets to it is
     *                       resetting to a record that existed a moment ago rather than to one that
     *                       was already gone
     * @param reason         why, for the broker's own log; this half never goes on the wire
     */
    record OutOfRange(long logStartOffset, String reason) implements Answer {

        public OutOfRange {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
