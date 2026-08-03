package io.shrike.clients;

import java.io.IOException;

/**
 * The broker did not answer, or did not accept a connection, inside the bound this client was given.
 * It is a type of its own rather than a {@link BrokerIOException} because the two mean different
 * things: a timeout says the broker may still be working, while an I/O failure says the connection is
 * gone.
 *
 * <p>The connection is closed by the time this is thrown, for the reason a timeout is worse than it
 * looks: the answer may still be on its way, and a connection that is reused after a timeout can
 * read that late answer as the reply to the next request.
 */
public final class BrokerTimeoutException extends ShrikeClientException {

    private static final long serialVersionUID = 1L;

    BrokerTimeoutException(String message, IOException cause) {
        super(message, cause);
    }
}
