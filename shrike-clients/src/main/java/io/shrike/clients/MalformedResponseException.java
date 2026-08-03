package io.shrike.clients;

/**
 * The bytes that came back are not a response this client can believe: a length outside the guard, a
 * correlation id that answers a different question, a body that does not parse, or an error response
 * carrying a body it should not have.
 *
 * <p>The connection is closed by the time this is thrown. A stream whose framing cannot be trusted
 * has no next frame to look for — the only honest thing to do with it is to stop reading it, which is
 * the mirror of what the broker does to a request length it cannot believe.
 */
public final class MalformedResponseException extends ShrikeClientException {

    private static final long serialVersionUID = 1L;

    MalformedResponseException(String reason) {
        super(reason);
    }
}
