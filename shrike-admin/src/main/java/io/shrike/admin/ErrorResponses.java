package io.shrike.admin;

import io.shrike.clients.BrokerErrorException;
import io.shrike.clients.BrokerIOException;
import io.shrike.clients.BrokerTimeoutException;
import io.shrike.clients.MalformedResponseException;
import io.shrike.clients.ShrikeClientException;
import io.shrike.core.protocol.ErrorCode;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
 * failed is logged here, with its cause, for whoever is running the facade; what is answered is the
 * sentence below.
 *
 * <p>The mapping, in one place because it is the contract:
 * <ul>
 *   <li>a broker that refuses a describe of a topic it does not hold — 404
 *   <li>a group that has committed nothing — 404
 *   <li>a path this facade does not serve, or a method it does not answer — 404 and 405
 *   <li>a name the protocol will not carry — 400, with the rule that refused it
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
     */
    @ExceptionHandler({BrokerIOException.class, BrokerTimeoutException.class})
    ResponseEntity<ErrorBody> answerUnreachableBroker(ShrikeClientException unreachable) {
        return answer(HttpStatus.SERVICE_UNAVAILABLE, "broker unreachable", unreachable);
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
        return answer(HttpStatus.NOT_FOUND, "no such endpoint", unknown);
    }

    /**
     * A method this facade does not answer. Every endpoint here reads and none of them writes, so GET
     * is the only one there is.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorBody> answerUnsupportedMethod(HttpRequestMethodNotSupportedException unsupported) {
        return answer(HttpStatus.METHOD_NOT_ALLOWED, "this facade answers GET only", unsupported);
    }

    /**
     * Everything else. A failure nobody planned for is a bug in this facade, and a caller learns
     * nothing about it beyond that it happened — the log line is where it is described.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorBody> answerUnexpectedFailure(Exception failure) {
        return answer(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_ERROR, failure);
    }

    /**
     * @param status  the status to answer with
     * @param message the sentence the caller is given, which is the whole of the body
     * @param failure what actually happened, which is logged and never sent
     * @return the answer
     */
    private static ResponseEntity<ErrorBody> answer(HttpStatus status, String message, Exception failure) {
        System.Logger.Level level = status.is5xxServerError()
                ? System.Logger.Level.ERROR
                : System.Logger.Level.WARNING;
        LOGGER.log(level, () -> "answering " + status.value() + " " + message, failure);
        return ResponseEntity.status(status).body(new ErrorBody(message));
    }
}
