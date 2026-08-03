package io.shrike.core.net;

import io.shrike.core.protocol.ErrorCode;
import io.shrike.core.protocol.Response;
import java.util.Objects;

/**
 * What the broker owes one request it understood: a body, or one error code that is the whole answer.
 *
 * <p>It is a sealed type rather than an exception because both outcomes are ordinary. An unknown
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
        }
    }
}
