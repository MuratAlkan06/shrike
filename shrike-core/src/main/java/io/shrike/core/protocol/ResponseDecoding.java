package io.shrike.core.protocol;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * What came of reading one response, in the three shapes a client owes a different thing to.
 *
 * <ul>
 *   <li>{@link Answered} — the request succeeded and here is its body.
 *   <li>{@link Failed} — the broker refused, and the error code is the whole answer, except for
 *       {@link ErrorCode#OFFSET_OUT_OF_RANGE}, which comes with the offset the partition now starts
 *       at.
 *   <li>{@link BrokenFrame} — the bytes are not a response this build can read, which is a broker or
 *       a middlebox misbehaving rather than an answer to anything.
 * </ul>
 */
public sealed interface ResponseDecoding {

    /**
     * @param correlationId the number the client sent with its request
     * @param response      the body of the answer
     */
    record Answered(int correlationId, Response response) implements ResponseDecoding {

        public Answered {
            Objects.requireNonNull(response, "response");
        }
    }

    /**
     * @param correlationId   the number the client sent with its request
     * @param errorCode       what went wrong, which an error response carries instead of a body
     * @param logStartOffset  the offset the partition now starts at, present exactly when the code is
     *                        {@link ErrorCode#OFFSET_OUT_OF_RANGE}. It is the one error this protocol
     *                        answers with a number, because it is the one error a client can act on
     *                        without asking a second question
     */
    record Failed(int correlationId, ErrorCode errorCode, OptionalLong logStartOffset)
            implements ResponseDecoding {

        public Failed {
            Objects.requireNonNull(errorCode, "errorCode");
            Objects.requireNonNull(logStartOffset, "logStartOffset");
            if (logStartOffset.isPresent() != (errorCode == ErrorCode.OFFSET_OUT_OF_RANGE)) {
                throw new IllegalArgumentException("a log start offset belongs to "
                        + ErrorCode.OFFSET_OUT_OF_RANGE + " and to no other code, but " + errorCode + " carried "
                        + logStartOffset);
            }
        }
    }

    /**
     * @param reason why the bytes are not a response, for the client's own log
     */
    record BrokenFrame(String reason) implements ResponseDecoding {

        public BrokenFrame {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
