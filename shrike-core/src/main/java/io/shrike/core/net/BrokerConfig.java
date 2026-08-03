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
 * @param maxRequestBytes   {@code max.request.bytes}: the largest request frame this broker will read,
 *                          and so the most memory one connection can make it hold. It also bounds the
 *                          records a fetch is answered with, because it is the one number that says how
 *                          much memory one connection is worth
 * @param maxFetchWaitMs    {@code max.fetch.wait.ms}: the longest a fetch may be held open, whatever
 *                          {@code maxWaitMs} it asks for. A request's wait is clamped to it, because a
 *                          waiting fetch holds a connection slot and a platform thread, and the wire
 *                          format lets a client ask for a wait of nearly 25 days
 * @param connectionCap     the most connections served at once; the one after that is accepted and
 *                          closed rather than queued
 * @param maxTotalPartitions the most partitions this broker will hold open across every topic. Each one
 *                          costs a directory and two open file handles for as long as the broker runs,
 *                          so a create that would pass this number is refused rather than answered with
 *                          a process out of file descriptors
 * @param readyFilePath     the file written once the broker is listening, holding the port it bound and
 *                          the process it runs in
 * @param logConfig         the record, segment, and index sizes every partition log opens with
 */
public record BrokerConfig(Path dataDirectory, int port, int maxRequestBytes, int maxFetchWaitMs, int connectionCap,
                           int maxTotalPartitions, Path readyFilePath, LogConfig logConfig) {

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

    /**
     * Thirty seconds. A long poll exists so that a consumer with nothing to read costs one waiting
     * thread instead of a loop of empty fetches, and thirty seconds of waiting already buys all of that.
     * What the cap buys is the other side: a fetch's wait is time a connection slot cannot be reused, so
     * how long this broker may be made to hold one is a number it decides rather than the caller.
     */
    public static final int DEFAULT_MAX_FETCH_WAIT_MILLIS = 30_000;

    /**
     * A thousand and twenty-four partitions across every topic — the same number one create may ask for,
     * so a single topic can still use the whole budget. Two open file handles each puts the worst case
     * at 2048 descriptors, which a default {@code ulimit} of 256 on macOS or 1024 on Linux would not
     * survive; the budget is what turns "the acceptor stopped, out of descriptors" into a refused
     * create naming the number it crossed.
     */
    public static final int DEFAULT_MAX_TOTAL_PARTITIONS = 1024;

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
        if (maxFetchWaitMs < 0) {
            throw new IllegalArgumentException("maxFetchWaitMs must not be negative, but was " + maxFetchWaitMs);
        }
        if (connectionCap < 1) {
            throw new IllegalArgumentException("connectionCap must be at least 1, but was " + connectionCap);
        }
        if (maxTotalPartitions < 1) {
            throw new IllegalArgumentException("maxTotalPartitions must be at least 1, but was " + maxTotalPartitions);
        }
    }

    /**
     * The configuration a broker gets when the caller names only where things live: the default
     * request bound, a fetch wait capped at {@value #DEFAULT_MAX_FETCH_WAIT_MILLIS}ms,
     * {@value #DEFAULT_CONNECTION_CAP} connections, {@value #DEFAULT_MAX_TOTAL_PARTITIONS} partitions
     * across every topic, and {@link LogConfig#defaults()}.
     *
     * @param dataDirectory the directory every path is derived from
     * @param port          the port to listen on, or {@value #EPHEMERAL_PORT}
     * @param readyFilePath the file written once the broker is listening
     * @return the configuration
     */
    public static BrokerConfig defaults(Path dataDirectory, int port, Path readyFilePath) {
        return new BrokerConfig(dataDirectory, port, RequestReader.DEFAULT_MAX_REQUEST_BYTES,
                DEFAULT_MAX_FETCH_WAIT_MILLIS, DEFAULT_CONNECTION_CAP, DEFAULT_MAX_TOTAL_PARTITIONS, readyFilePath,
                LogConfig.defaults());
    }
}
