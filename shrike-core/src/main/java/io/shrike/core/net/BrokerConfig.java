package io.shrike.core.net;

import io.shrike.core.log.LogConfig;
import io.shrike.core.protocol.RequestFrame;
import io.shrike.core.protocol.RequestReader;
import java.nio.file.Path;
import java.util.Objects;

/**
 * The numbers and paths one broker is started with. Every field is validated here, so a broker that
 * holds a {@code BrokerConfig} holds one that already makes sense.
 *
 * @param dataDirectory   the directory every path is derived from: partition directories, the topic
 *                        registry, and the group offsets directory all live inside it
 * @param port            the TCP port to listen on, or {@value #EPHEMERAL_PORT} to let the operating
 *                        system choose one — which is what the ready file is for
 * @param maxRequestBytes {@code max.request.bytes}: the largest request frame this broker will read,
 *                        and so the most memory one connection can make it hold. It also bounds the
 *                        records a fetch is answered with, because it is the one number that says how
 *                        much memory one connection is worth
 * @param connectionCap   the most connections served at once; the one after that is accepted and
 *                        closed rather than queued
 * @param readyFilePath   the file written once the broker is listening, holding the port it bound and
 *                        the process it runs in
 * @param logConfig       the record, segment, and index sizes every partition log opens with
 */
public record BrokerConfig(Path dataDirectory, int port, int maxRequestBytes, int connectionCap, Path readyFilePath,
                           LogConfig logConfig) {

    /** Ask the operating system for a free port, and read back which one it gave. */
    public static final int EPHEMERAL_PORT = 0;

    /** The highest port a TCP socket can bind. */
    public static final int MAX_PORT = 65535;

    /**
     * Sixty-four connections at once. A connection costs a platform thread and a request buffer, so
     * the cap is what turns "how much can one client make this broker hold" into a number instead of a
     * hope: at the default request bound that is 64 threads and at most 256 MiB of request buffers.
     * It is a broker for one machine and a handful of clients; a client that wants more connections
     * than this is either misconfigured or hostile, and both are answered the same way.
     */
    public static final int DEFAULT_CONNECTION_CAP = 64;

    public BrokerConfig {
        Objects.requireNonNull(dataDirectory, "dataDirectory");
        Objects.requireNonNull(readyFilePath, "readyFilePath");
        Objects.requireNonNull(logConfig, "logConfig");
        if (port < EPHEMERAL_PORT || port > MAX_PORT) {
            throw new IllegalArgumentException("port must be " + EPHEMERAL_PORT + " to " + MAX_PORT + ", but was "
                    + port);
        }
        if (maxRequestBytes < RequestFrame.MINIMUM_LENGTH_BYTES) {
            throw new IllegalArgumentException("maxRequestBytes must be at least "
                    + RequestFrame.MINIMUM_LENGTH_BYTES + ", the size of an empty request's envelope, but was "
                    + maxRequestBytes);
        }
        if (connectionCap < 1) {
            throw new IllegalArgumentException("connectionCap must be at least 1, but was " + connectionCap);
        }
    }

    /**
     * The configuration a broker gets when the caller names only where things live: the default
     * request bound, {@value #DEFAULT_CONNECTION_CAP} connections, and {@link LogConfig#defaults()}.
     *
     * @param dataDirectory the directory every path is derived from
     * @param port          the port to listen on, or {@value #EPHEMERAL_PORT}
     * @param readyFilePath the file written once the broker is listening
     * @return the configuration
     */
    public static BrokerConfig defaults(Path dataDirectory, int port, Path readyFilePath) {
        return new BrokerConfig(dataDirectory, port, RequestReader.DEFAULT_MAX_REQUEST_BYTES, DEFAULT_CONNECTION_CAP,
                readyFilePath, LogConfig.defaults());
    }
}
