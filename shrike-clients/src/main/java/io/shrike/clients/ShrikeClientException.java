package io.shrike.clients;

/**
 * What a call through this client can fail with. There are four of them and they are kept apart
 * because a caller does something different about each:
 *
 * <ul>
 *   <li>{@link BrokerErrorException} — the broker understood the request and refused it. The
 *       connection is still usable and the error code says what to change.
 *   <li>{@link BrokerTimeoutException} — the broker did not answer inside the bound this client was
 *       given. The connection is closed, because a late answer would arrive in the middle of the next
 *       one.
 *   <li>{@link BrokerIOException} — the connection failed or ended. The connection is closed.
 *   <li>{@link MalformedResponseException} — the bytes that came back are not a response this client
 *       can believe. The connection is closed, because a stream that has lied about its framing
 *       cannot be resynchronized.
 * </ul>
 *
 * <p>They are unchecked for the same reason {@code ShrikeIOException} is in the broker: a client call
 * fails for reasons the caller usually cannot handle where it stands, and the type carries the detail
 * a checked exception would have carried.
 */
public abstract sealed class ShrikeClientException extends RuntimeException
        permits BrokerErrorException, BrokerIOException, BrokerTimeoutException, MalformedResponseException {

    private static final long serialVersionUID = 1L;

    ShrikeClientException(String message) {
        super(message);
    }

    ShrikeClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
