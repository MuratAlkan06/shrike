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
 * @param zeroCopyFetch     {@code fetch.zero.copy}: whether a fetch's records go from the segment file
 *                          to the socket without being read into memory on the way. On by default.
 *                          Turning it off selects the path that reads the range into a buffer and
 *                          writes that, which is what this broker did before the setting existed. One
 *                          setting does two jobs on purpose: it is the way back to the older path if
 *                          the newer one ever misbehaves on some filesystem or kernel, and it is the
 *                          A and the B of the benchmark that measures the difference. Both paths ask
 *                          the log for the same range and put the same bytes on the wire
 * @param connectionCap     the most connections served at once; the one after that is accepted and
 *                          closed rather than queued
 * @param readTimeoutMs     {@code read.timeout.ms}: how long one connection may hold its place under
 *                          {@code connectionCap} without finishing a request. It covers exactly two
 *                          things — the time a connection sits idle between requests, and the time it
 *                          spends part way through a request frame — and it never covers serving one,
 *                          so a long-poll fetch held open for {@code maxFetchWaitMs} is not reading and
 *                          is not closed for it. A connection past the bound is closed by
 *                          {@code shrike-conn-reaper}, which asks once per bound; there is no value
 *                          that turns the bound off, because a broker that wants a longer one sets a
 *                          longer one
 * @param maxTotalPartitions the most partitions this broker will hold open across every topic. Each one
 *                          costs a directory and two open file handles for as long as the broker runs,
 *                          so a create that would pass this number is refused rather than answered with
 *                          a process out of file descriptors
 * @param maxTotalGroups    the most consumer groups this broker will hold committed offsets for. A
 *                          commit is what creates a group, and the group it creates costs a file and a
 *                          resident map entry that outlive the connection that asked for it, so a commit
 *                          that would create one past this number is refused rather than leaving a
 *                          directory anybody with a socket can fill
 * @param readyFilePath     the file written once the broker is listening, holding the port it bound and
 *                          the process it runs in
 * @param logConfig         the record, segment, and index sizes every partition log opens with
 */
public record BrokerConfig(Path dataDirectory, int port, int maxRequestBytes, int maxFetchWaitMs,
                           boolean zeroCopyFetch, int connectionCap, int readTimeoutMs, int maxTotalPartitions,
                           int maxTotalGroups, Path readyFilePath, LogConfig logConfig) {

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
     * Thirty seconds of reading, which is the same number the longest fetch wait takes and means
     * something else entirely: this one is time a connection spends saying nothing, or saying half of
     * something, while it holds a slot and up to {@code max.request.bytes} of the heap. Thirty seconds
     * is far longer than any client on a machine this broker will talk to needs to put four length
     * bytes and a frame on a socket, and short enough that a connection nobody is driving gives its
     * slot back while an operator is still looking at the problem rather than after it.
     */
    public static final int DEFAULT_READ_TIMEOUT_MILLIS = 30_000;

    /**
     * A thousand and twenty-four partitions across every topic — the same number one create may ask for,
     * so a single topic can still use the whole budget. Two open file handles each puts the worst case
     * at 2048 descriptors, which a default {@code ulimit} of 256 on macOS or 1024 on Linux would not
     * survive; the budget is what turns "the acceptor stopped, out of descriptors" into a refused
     * create naming the number it crossed.
     */
    public static final int DEFAULT_MAX_TOTAL_PARTITIONS = 1024;

    /**
     * A thousand and twenty-four consumer groups — the same number as the partition budget, on purpose:
     * both are "how many of these may one broker for one machine and a handful of clients accumulate",
     * and one number is one thing for an operator to raise. A group is cheaper than a partition, since
     * it costs a file and a map entry rather than two open file handles, but its cost is permanent in a
     * way a partition's is not: nothing deletes a group, so a broker that has held a group for a minute
     * holds it for as long as its data directory lives, and every one of them is read back at every
     * start. A commit is the only thing that creates one, and a commit needs no more than a socket.
     */
    public static final int DEFAULT_MAX_TOTAL_GROUPS = 1024;

    /**
     * On. A fetch response is already the log's own bytes rather than a re-encoding of its records, so
     * sending them out of the file they are in is what the format was chosen for. The buffered path is
     * kept beside it, one setting away, because a broker with no way back to the code it had before is
     * a broker whose operator has no move to make when something goes wrong.
     */
    public static final boolean DEFAULT_ZERO_COPY_FETCH = true;

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
        if (readTimeoutMs < 1) {
            throw new IllegalArgumentException("readTimeoutMs must be at least 1, because a bound of zero would "
                    + "close every connection the first time the reaper looked at one, but was " + readTimeoutMs);
        }
        if (maxTotalPartitions < 1) {
            throw new IllegalArgumentException("maxTotalPartitions must be at least 1, but was " + maxTotalPartitions);
        }
        if (maxTotalGroups < 1) {
            throw new IllegalArgumentException("maxTotalGroups must be at least 1, but was " + maxTotalGroups);
        }
    }

    /**
     * The configuration a broker gets when the caller names only where things live: the default
     * request bound, a fetch wait capped at {@value #DEFAULT_MAX_FETCH_WAIT_MILLIS}ms, fetches served
     * out of the segment file, {@value #DEFAULT_CONNECTION_CAP} connections, a read bound of
     * {@value #DEFAULT_READ_TIMEOUT_MILLIS}ms, {@value #DEFAULT_MAX_TOTAL_PARTITIONS} partitions across
     * every topic, {@value #DEFAULT_MAX_TOTAL_GROUPS} consumer groups, and {@link LogConfig#defaults()}.
     *
     * @param dataDirectory the directory every path is derived from
     * @param port          the port to listen on, or {@value #EPHEMERAL_PORT}
     * @param readyFilePath the file written once the broker is listening
     * @return the configuration
     */
    public static BrokerConfig defaults(Path dataDirectory, int port, Path readyFilePath) {
        return new BrokerConfig(dataDirectory, port, RequestReader.DEFAULT_MAX_REQUEST_BYTES,
                DEFAULT_MAX_FETCH_WAIT_MILLIS, DEFAULT_ZERO_COPY_FETCH, DEFAULT_CONNECTION_CAP,
                DEFAULT_READ_TIMEOUT_MILLIS, DEFAULT_MAX_TOTAL_PARTITIONS, DEFAULT_MAX_TOTAL_GROUPS, readyFilePath,
                LogConfig.defaults());
    }
}
