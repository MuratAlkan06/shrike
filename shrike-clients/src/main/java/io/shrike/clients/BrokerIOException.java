package io.shrike.clients;

import java.io.IOException;

/**
 * The connection to the broker failed, or ended while an answer was still owed. The message names the
 * broker and how far into the read or write the failure landed; the cause is the {@link IOException}
 * itself whenever there was one, so an unchecked type loses nothing.
 *
 * <p>The connection is closed by the time this is thrown. A socket that has failed part way through a
 * frame cannot be resynchronized, so reusing it would mean reading the tail of one answer as the head
 * of the next.
 */
public final class BrokerIOException extends ShrikeClientException {

    private static final long serialVersionUID = 1L;

    BrokerIOException(String message) {
        super(message);
    }

    BrokerIOException(String message, IOException cause) {
        super(message, cause);
    }
}
