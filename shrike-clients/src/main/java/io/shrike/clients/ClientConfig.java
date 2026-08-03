package io.shrike.clients;

import io.shrike.core.protocol.RequestReader;
import io.shrike.core.protocol.ResponseFrame;
import java.util.Objects;

/**
 * Where a client connects and how long it waits there. Every field is validated here, so a connection
 * that holds a {@code ClientConfig} holds one that already makes sense.
 *
 * @param host                 the host the broker listens on
 * @param port                 the port it listens on, which for a broker started on port 0 is the one
 *                             its ready file names
 * @param connectTimeoutMillis how long {@code connect} may take before the attempt is given up
 * @param readTimeoutMillis    how long the broker gets to answer <em>after</em> it has stopped
 *                             waiting on the caller's behalf. A fetch may hold its request open for
 *                             {@code maxWaitMs}, so a fetch's socket timeout is {@code maxWaitMs}
 *                             plus this number, and this number alone is the bound on every other
 *                             api. It is a margin rather than a total, so raising a fetch's wait
 *                             never turns into a client timeout the caller did not ask for
 * @param maxResponseBytes     the largest response {@code length} this client will believe, and so
 *                             the most memory one answer can make it allocate
 */
public record ClientConfig(String host, int port, int connectTimeoutMillis, int readTimeoutMillis,
                           int maxResponseBytes) {

    /** The lowest port a client can connect to; 0 asks a listener for a free port, not a caller. */
    public static final int MIN_PORT = 1;

    /** The highest port a TCP socket can bind. */
    public static final int MAX_PORT = 65535;

    /** Ten seconds to open a socket to a broker on a machine that is up. */
    public static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = 10_000;

    /** Thirty seconds for a broker to answer once it has stopped waiting for records. */
    public static final int DEFAULT_READ_TIMEOUT_MILLIS = 30_000;

    /**
     * How much a response may exceed the records it carries. A fetch's records are capped by the
     * broker's own {@code max.request.bytes}, and the envelope around them adds eighteen bytes —
     * length, correlation id, error code, high-water mark, and the size of the block. A kibibyte of
     * headroom keeps the guard a statement about the size of the records rather than an arithmetic
     * identity that a later api version would break.
     */
    public static final int RESPONSE_ENVELOPE_HEADROOM_BYTES = 1024;

    /**
     * The default response bound: the broker's default serving cap of
     * {@value RequestReader#DEFAULT_MAX_REQUEST_BYTES} bytes plus
     * {@value #RESPONSE_ENVELOPE_HEADROOM_BYTES} of envelope headroom. A client pointed at a broker
     * configured with a larger {@code max.request.bytes} says so here rather than discovering it as a
     * broken frame.
     */
    public static final int DEFAULT_MAX_RESPONSE_BYTES =
            RequestReader.DEFAULT_MAX_REQUEST_BYTES + RESPONSE_ENVELOPE_HEADROOM_BYTES;

    public ClientConfig {
        Objects.requireNonNull(host, "host");
        if (host.isBlank()) {
            throw new IllegalArgumentException("host must name a broker, but was blank");
        }
        if (port < MIN_PORT || port > MAX_PORT) {
            throw new IllegalArgumentException("port must be " + MIN_PORT + " to " + MAX_PORT + ", but was " + port);
        }
        requirePositive(connectTimeoutMillis, "connectTimeoutMillis");
        requirePositive(readTimeoutMillis, "readTimeoutMillis");
        if (maxResponseBytes < ResponseFrame.MINIMUM_LENGTH_BYTES) {
            throw new IllegalArgumentException("maxResponseBytes must be at least "
                    + ResponseFrame.MINIMUM_LENGTH_BYTES + ", the size of an error response's envelope, but was "
                    + maxResponseBytes);
        }
    }

    /**
     * The configuration a client gets when the caller names only where the broker is.
     *
     * @param host the host the broker listens on
     * @param port the port it listens on
     * @return the configuration, with every default
     */
    public static ClientConfig defaults(String host, int port) {
        return new ClientConfig(host, port, DEFAULT_CONNECT_TIMEOUT_MILLIS, DEFAULT_READ_TIMEOUT_MILLIS,
                DEFAULT_MAX_RESPONSE_BYTES);
    }

    /**
     * @return the broker this configuration points at, for a message
     */
    String address() {
        return host + ":" + port;
    }

    private static void requirePositive(int valueMillis, String field) {
        if (valueMillis < 1) {
            throw new IllegalArgumentException(field + " must be at least 1, because a timeout of 0 means no bound"
                    + " at all, but was " + valueMillis);
        }
    }
}
