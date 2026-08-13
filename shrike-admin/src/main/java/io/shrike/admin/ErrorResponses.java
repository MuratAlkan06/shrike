package io.shrike.admin;

import io.shrike.clients.BrokerErrorException;
import io.shrike.clients.BrokerIOException;
import io.shrike.clients.BrokerTimeoutException;
import io.shrike.clients.MalformedResponseException;
import io.shrike.clients.ShrikeClientException;
import io.shrike.core.protocol.ErrorCode;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * What every failure becomes: a status code and one plain sentence in an {@link ErrorBody}.
 *
 * <p><strong>Nothing about how this facade is built reaches a caller.</strong> No stack trace, no
 * exception class name, no path on anybody's disk, and no message this process did not choose. What
 * failed is logged here for whoever is running the facade — see
 * {@link #answer(HttpStatus, String, Exception) answer} for how much of it — and what is answered is
 * the sentence below.
 *
 * <p>The mapping, in one place because it is the contract:
 * <ul>
 *   <li>a broker that refuses a describe of a topic it does not hold — 404
 *   <li>a group that has committed nothing — 404
 *   <li>a path this facade does not serve, or a method it does not answer — 404 and 405
 *   <li>a name the protocol will not carry — 400, with the rule that refused it
 *   <li>a request the framework itself refuses on the caller's behalf — the status the framework
 *       chose, which for an {@code Accept} header naming nothing this facade produces is 406
 *   <li>a broker that cannot be reached or does not answer in time — 503
 *   <li>an answer from the far end that is not a response this client can believe — 502
 *   <li>anything else at all — 500, with no detail whatsoever
 * </ul>
 */
@RestControllerAdvice
public class ErrorResponses {

    private static final System.Logger LOGGER = System.getLogger(ErrorResponses.class.getName());

    /** The whole of what a caller is told about a failure this facade did not expect. */
    static final String INTERNAL_ERROR = "internal error";

    /** A path this facade does not serve, however the caller arrived at it. */
    static final String NO_SUCH_ENDPOINT = "no such endpoint";

    /** A method this facade does not answer. Every endpoint here reads. */
    static final String GET_ONLY = "this facade answers GET only";

    /** Any other refusal the container settled, which this facade has no better sentence for. */
    static final String REQUEST_REFUSED = "request refused";

    /**
     * The broker understood the request and refused it. Only one of its codes is something the caller
     * asked for and can fix; the rest are this facade asking the wrong thing, which is its own bug.
     */
    @ExceptionHandler(BrokerErrorException.class)
    ResponseEntity<ErrorBody> answerBrokerRefusal(BrokerErrorException refused) {
        if (refused.errorCode() == ErrorCode.UNKNOWN_TOPIC_OR_PARTITION) {
            // The sentence names no topic on purpose. On the topic endpoint the caller's own path
            // already carries the name; on the lag endpoint the topic came out of the group's commits
            // rather than out of the request, and answering with it would report a name the caller
            // never mentioned. The log line below carries it either way.
            return answer(HttpStatus.NOT_FOUND, "no such topic", refused);
        }
        return answer(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_ERROR, refused);
    }

    /**
     * A group with nothing committed is not an error on the wire — the broker cannot tell it from a
     * group that never existed — but over HTTP it is a name with nothing under it.
     */
    @ExceptionHandler(NoCommittedOffsetsException.class)
    ResponseEntity<ErrorBody> answerUncommittedGroup(NoCommittedOffsetsException uncommitted) {
        return answer(HttpStatus.NOT_FOUND, uncommitted.getMessage(), uncommitted);
    }

    /**
     * The broker is not answering. That is not something the caller did, and it is not something this
     * facade can fix by being asked again immediately, so it is the one status that says "try later".
     *
     * <p>It is also the one 5xx anybody who can reach this port can ask for as often as they like, so
     * it is written down the way {@link #answerWithoutTheStack} describes rather than the way every
     * other 5xx is.
     */
    @ExceptionHandler({BrokerIOException.class, BrokerTimeoutException.class})
    ResponseEntity<ErrorBody> answerUnreachableBroker(ShrikeClientException unreachable) {
        return answerWithoutTheStack(HttpStatus.SERVICE_UNAVAILABLE, "broker unreachable", unreachable);
    }

    /**
     * Something answered on the broker's port with bytes this client will not believe. The caller's
     * request was fine, so the failure belongs to the far end.
     */
    @ExceptionHandler(MalformedResponseException.class)
    ResponseEntity<ErrorBody> answerUnbelievableAnswer(MalformedResponseException malformed) {
        return answer(HttpStatus.BAD_GATEWAY, "broker answer could not be read", malformed);
    }

    /**
     * A topic name or a group id the protocol will not carry is refused before a byte reaches a
     * socket, by the same one rule the broker applies. Its message is a sentence about the rule and a
     * quoted, cut-down copy of what was sent, which is the one piece of detail a caller is owed: it is
     * the caller's own input.
     */
    @ExceptionHandler(UnusableNameException.class)
    ResponseEntity<ErrorBody> answerUnusableName(UnusableNameException unusable) {
        return answer(HttpStatus.BAD_REQUEST, Objects.requireNonNullElse(unusable.getMessage(), "invalid request"),
                unusable);
    }

    /**
     * A path this facade does not serve. Both exceptions are handled because which one is raised
     * depends on whether a static resource handler is mapped, and this facade serves no resources.
     */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    ResponseEntity<ErrorBody> answerUnknownPath(Exception unknown) {
        return answer(HttpStatus.NOT_FOUND, NO_SUCH_ENDPOINT, unknown);
    }

    /**
     * A method this facade does not answer. Every endpoint here reads and none of them writes, so GET
     * is the only one there is.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorBody> answerUnsupportedMethod(HttpRequestMethodNotSupportedException unsupported) {
        return answer(HttpStatus.METHOD_NOT_ALLOWED, GET_ONLY, unsupported);
    }

    /**
     * Everything else. A failure nobody planned for is a bug in this facade, and a caller learns
     * nothing about it beyond that it happened — the log line is where it is described.
     *
     * <p><strong>Unless the framework has already decided what it is.</strong> Spring's own refusals
     * of a request — an {@code Accept} header naming no media type this facade produces is the one
     * this facade can be made to raise — implement {@link ErrorResponse}, and each of them carries the
     * status that describes it. Those keep the status they came with and are logged the way every
     * other 4xx is, because a header is something a caller chooses and can choose a million times, and
     * because a request for a representation this facade does not produce is the caller's 406 rather
     * than this facade failing. A framework refusal carrying a 5xx is left to the sentence below:
     * this facade has no better name for one than the name it gives its own bugs, and no reason to
     * believe a caller drove it.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorBody> answerUnexpectedFailure(Exception failure) {
        if (failure instanceof ErrorResponse framework) {
            HttpStatus refusal = HttpStatus.resolve(framework.getStatusCode().value());
            if (refusal != null && refusal.is4xxClientError()) {
                return answer(refusal, sentenceFor(refusal), failure);
            }
        }
        return answer(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_ERROR, failure);
    }

    /**
     * The sentence for a status that arrived without an exception behind it, which is what a failure
     * the servlet container settled on its own looks like by the time it reaches {@link ErrorEndpoint}.
     *
     * @param status the status the container is answering with
     * @return the sentence this facade gives for it
     */
    static String sentenceFor(HttpStatus status) {
        return switch (status) {
            case NOT_FOUND -> NO_SUCH_ENDPOINT;
            case METHOD_NOT_ALLOWED -> GET_ONLY;
            default -> status.is4xxClientError() ? REQUEST_REFUSED : INTERNAL_ERROR;
        };
    }

    /**
     * @param status  the status to answer with
     * @param message the sentence the caller is given, which is the whole of the body
     * @return the answer, for a failure with no throwable behind it to log
     */
    static ResponseEntity<ErrorBody> answer(HttpStatus status, String message) {
        return answer(status, message, null);
    }

    /**
     * <p><strong>How much of a failure is written down depends on who can cause it.</strong> A 4xx is
     * something the caller did, and a caller that can make this process write a line can make it write
     * one per request: DEBUG, and without the throwable, because forty stack frames per 404 is a disk
     * somebody else gets to fill. {@code ShrikeBroker.java:382-386} applies exactly this rule to the
     * broker's own connection logging, for the same reason. A 5xx is this facade or the broker behind
     * it being in a state an operator has to know about rather than a shape of request anyone can send,
     * so it keeps its level and its cause.
     *
     * <p><strong>A 5xx a caller can drive is neither.</strong> A broker that is not listening answers
     * every request that arrives while it is down with the same 503, so the rule above splits on who
     * <em>can</em> cause a status rather than on the digit it starts with: that one goes through
     * {@link #answerWithoutTheStack}, which keeps the level and the cause and drops the repetition.
     *
     * @param status  the status to answer with
     * @param message the sentence the caller is given, which is the whole of the body
     * @param failure what actually happened, which is logged and never sent, or null when the failure
     *                was settled by the container and there is no throwable to attach
     * @return the answer
     */
    private static ResponseEntity<ErrorBody> answer(HttpStatus status, String message, Exception failure) {
        if (status.is5xxServerError()) {
            LOGGER.log(System.Logger.Level.ERROR, () -> "answering " + status.value() + " " + message, failure);
        } else {
            LOGGER.log(System.Logger.Level.DEBUG, () -> "answering " + status.value() + " " + message);
        }
        return answerWith(status, message);
    }

    /**
     * A 5xx a caller can ask for as often as it likes, written down as one line.
     *
     * <p>{@link #answer(HttpStatus, String, Exception)} gives a 5xx its throwable because a 5xx is
     * normally a state an operator has to be told about once. A broker that is not listening is a
     * state that lasts: every request that arrives while it holds asks for the same fifty frames
     * again, and three requests against a down broker are already some four hundred lines. So the
     * failure is summarised instead — its own sentence, which names the broker's address, and the
     * class and message of whatever caused it, which is the part of a stack an operator reads first —
     * and the throwable itself is not passed to the logger. The level stays ERROR, because a broker
     * that cannot be reached is still something an operator has to know about. What is dropped is the
     * repetition, not the report.
     *
     * @param status  the status to answer with
     * @param message the sentence the caller is given, which is the whole of the body
     * @param failure what actually happened, summarised into the line and never sent
     * @return the answer
     */
    private static ResponseEntity<ErrorBody> answerWithoutTheStack(HttpStatus status, String message,
            Exception failure) {
        LOGGER.log(System.Logger.Level.ERROR,
                () -> "answering " + status.value() + " " + message + ": " + summaryOf(failure));
        return answerWith(status, message);
    }

    /**
     * @param failure what happened
     * @return that failure in one line: its own sentence, and the class and message of what caused it
     *         when something did
     */
    private static String summaryOf(Exception failure) {
        Throwable cause = failure.getCause();
        return cause == null ? failure.getMessage() : failure.getMessage() + ": " + cause;
    }

    /**
     * @param status  the status to answer with
     * @param message the sentence the caller is given, which is the whole of the body
     * @return the answer itself, once whatever had to be written down has been
     */
    private static ResponseEntity<ErrorBody> answerWith(HttpStatus status, String message) {
        // The content type is named rather than negotiated: this shape is the one answer a failure
        // gets, so a caller that will accept only HTML is given the JSON anyway instead of being told
        // its own Accept header is a second failure.
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(new ErrorBody(message));
    }
}
