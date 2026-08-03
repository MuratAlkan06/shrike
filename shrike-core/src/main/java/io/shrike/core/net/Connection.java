package io.shrike.core.net;

import io.shrike.core.log.ByteChannels;
import io.shrike.core.log.RecordRange;
import io.shrike.core.protocol.ErrorCode;
import io.shrike.core.protocol.RequestDecoding;
import io.shrike.core.protocol.RequestReader;
import io.shrike.core.protocol.ResponseFrame;
import io.shrike.core.time.TimeSource;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Objects;

/**
 * One client connection, served start to finish by one thread named after it.
 *
 * <p>The loop is: read one request through the frame guard, answer it, repeat. Which of the three
 * things the guard says decides what the client is owed, and there are only three:
 *
 * <ul>
 *   <li>a request — dispatch it and write the answer;
 *   <li>a refusal — write the error code with an empty body and keep reading, because the caller is
 *       talking, just wrongly. The frame guard never produces {@code OFFSET_OUT_OF_RANGE}, which is
 *       the one code that owes a body, so the refusals that arrive here have none;
 *   <li>a broken frame — close the connection and send nothing at all, because a frame whose length
 *       could not be believed has no correlation id worth addressing a reply to.
 * </ul>
 *
 * <p>An unexpected exception ends the request, not the thread and not silently: it becomes an
 * {@link ErrorCode#INTERNAL} response and a WARN naming the connection, the api key, and the
 * correlation id. The connection then reads the next request. A connection thread that died without
 * saying why would leave a client waiting for an answer that is never coming, which is the failure
 * this arrangement exists to avoid.
 *
 * <p>Every response frame is written through {@link ByteChannels#writeFully}: a socket write is
 * allowed to be short, and half a response frame would desynchronize the stream for good.
 *
 * <p>A fetch is the one answer that may reach the socket in two pieces. When the broker is serving
 * fetches out of the segment file, {@link #stream} writes the header — length field and all — and then
 * sends the records themselves from the file, looping over short transfers the same way. What a client
 * reads is identical either way; what changes is only whether the bytes were in this process's memory
 * on the way.
 *
 * <p><strong>The read phase.</strong> A connection is in its read phase from the moment it is built
 * until {@link #serve} has a whole frame, and again from the moment an answer is written until the next
 * whole frame arrives. {@link #readPhaseStartedAtMillis} is when the current one began, and
 * {@link #closeIfStalled} is what {@code shrike-conn-reaper} asks with. Everything between those two
 * phases — dispatching a request, waiting out a long poll's {@code maxWaitMs}, writing an answer — is
 * serving rather than reading, and is bounded by {@code max.fetch.wait.ms} and by nothing else. That
 * split is the whole point: a fetch legitimately holds this connection for thirty seconds while the
 * broker waits to write, and a bound on the socket rather than on the read phase would close it.
 */
final class Connection implements AutoCloseable {

    private static final System.Logger LOGGER = System.getLogger(Connection.class.getName());

    /** What {@link #readPhaseStartedAtMillis} holds while this connection is serving a request. */
    private static final long NOT_READING = -1L;

    private final String name;
    private final SocketChannel socket;
    private final RequestReader reader;
    private final RequestDispatcher dispatcher;
    private final TimeSource timeSource;

    /**
     * When the read phase this connection is in began, or {@link #NOT_READING} while it is serving a
     * request rather than reading one.
     *
     * <p>volatile because this connection's own thread is the only one that writes it and
     * {@code shrike-conn-reaper} is the one that reads it, and a reaper reading a stale value would
     * either close a connection that has just spoken or keep one that stopped an hour ago.
     *
     * <p>The first read phase is stamped here, in the constructor, on the acceptor — before this
     * connection is registered and before its own thread exists — because a connection that never sends
     * a byte would otherwise have no beginning to measure from.
     */
    // guarded by: nothing. Written once by shrike-acceptor, before this connection is reachable from
    // anywhere, and after that only by this connection's own thread; read only by shrike-conn-reaper.
    private volatile long readPhaseStartedAtMillis;

    /**
     * @param name       the connection's name, which is also its thread's, so a log line and a thread
     *                   dump name the same connection
     * @param socket     the accepted, blocking socket
     * @param reader     this connection's own frame guard
     * @param dispatcher the broker's answer to a request, shared by every connection
     * @param timeSource the clock every read phase of this connection is stamped from
     */
    Connection(String name, SocketChannel socket, RequestReader reader, RequestDispatcher dispatcher,
               TimeSource timeSource) {
        this.name = Objects.requireNonNull(name, "name");
        this.socket = Objects.requireNonNull(socket, "socket");
        this.reader = Objects.requireNonNull(reader, "reader");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.timeSource = Objects.requireNonNull(timeSource, "timeSource");
        this.readPhaseStartedAtMillis = timeSource.currentTimeMillis();
    }

    /**
     * Serves this connection until the client goes away, the framing breaks, or the socket is closed
     * under it by a broker shutting down. Returns rather than throws in every one of those cases: the
     * caller's only job afterwards is to forget the connection.
     */
    void serve() {
        try (SocketChannel closing = socket) {
            while (true) {
                RequestDecoding decoding = reader.readFrom(closing);
                // A whole frame is in hand, so this connection is serving rather than reading and the
                // read bound stops applying to it. What the work below may cost is max.fetch.wait.ms
                // and the api's own time, which are somebody else's numbers.
                readPhaseStartedAtMillis = NOT_READING;
                switch (decoding) {
                    case RequestDecoding.BrokenFrame broken -> {
                        LOGGER.log(System.Logger.Level.DEBUG,
                                () -> name + " closed: " + broken.reason());
                        return;
                    }
                    case RequestDecoding.Refused refused -> {
                        LOGGER.log(System.Logger.Level.DEBUG, () -> name + " refused a request with "
                                + refused.errorCode() + ": " + refused.reason());
                        write(ResponseFrame.encodeError(refused.correlationId(), refused.errorCode()));
                    }
                    case RequestDecoding.Accepted accepted -> answer(accepted);
                }
                // The answer is on the wire, so the next request's read phase begins here rather than
                // where this connection blocks: the two are the same instant to a client, and stamping
                // before the blocking call is what keeps a reaper from measuring a phase that has no
                // beginning yet.
                readPhaseStartedAtMillis = timeSource.currentTimeMillis();
            }
        } catch (IOException e) {
            // The socket failed or was closed under this thread by a broker stopping. Either way the
            // client is gone and there is nobody left to answer.
            LOGGER.log(System.Logger.Level.DEBUG, () -> name + " ended: " + e);
        } catch (RuntimeException e) {
            // Nothing above is meant to throw one: an api that fails produces an answer, and a codec
            // that fails produces a decoding. So this is a defect, and a defect that ends a connection
            // says which connection and why here rather than reaching a thread's default handler.
            LOGGER.log(System.Logger.Level.WARNING, name + " ended on an exception nothing was expected to throw", e);
        }
    }

    /**
     * Closes this connection when it has spent at least {@code readTimeoutMs} in one read phase, and
     * leaves it alone otherwise. A connection that is serving a request is never closed here, however
     * long that request takes: a long poll waiting out its {@code maxWaitMs} is the broker waiting to
     * write rather than the client failing to speak, and closing it would break the one api this broker
     * holds a connection open for on purpose.
     *
     * <p>Closing is the whole of what happens. The socket going away is what brings this connection's
     * own thread out of its blocking read, and that thread is what gives back the map entry and the
     * place under the connection cap, exactly as it does for a client that hung up — there is no second
     * path that releases a slot, so a reaped connection cannot leak one or give one back twice.
     *
     * @param nowMillis     the epoch millisecond the read phase's age is measured against, which is
     *                      where the injected clock enters
     * @param readTimeoutMs {@code read.timeout.ms}: the bound this broker holds, which lives on
     *                      {@link BrokerConfig} rather than on each connection because it is one number
     *                      for all of them
     * @return whether this connection was closed
     */
    boolean closeIfStalled(long nowMillis, long readTimeoutMs) {
        long startedAtMillis = readPhaseStartedAtMillis;
        if (startedAtMillis == NOT_READING || nowMillis - startedAtMillis < readTimeoutMs) {
            return false;
        }
        // DEBUG rather than WARNING, and behind a supplier: which connections stall is decided by
        // whoever is connecting, and a line an anonymous caller can write as fast as it opens sockets
        // is a line that can fill a disk.
        LOGGER.log(System.Logger.Level.DEBUG, () -> name + " closed: it has been reading one request for "
                + (nowMillis - startedAtMillis) + "ms, which is past the read.timeout.ms of " + readTimeoutMs
                + "ms, so its place under the connection cap goes back");
        close();
        return true;
    }

    /**
     * Closes the socket, which is how a stopping broker gets a thread out of a blocking read. The
     * connection's own thread sees a closed channel and returns.
     */
    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException e) {
            LOGGER.log(System.Logger.Level.DEBUG, () -> name + " did not close cleanly: " + e);
        }
    }

    private void answer(RequestDecoding.Accepted accepted) throws IOException {
        try {
            switch (dispatcher.dispatch(accepted.request())) {
                case Answer.Served served -> write(ResponseFrame.encode(accepted.correlationId(),
                        served.response()));
                case Answer.Streamed streamed -> stream(accepted.correlationId(), streamed);
                case Answer.Refused refused -> {
                    LOGGER.log(System.Logger.Level.DEBUG, () -> name + " answered api key "
                            + accepted.request().apiKey() + " with " + refused.errorCode() + ": " + refused.reason());
                    write(ResponseFrame.encodeError(accepted.correlationId(), refused.errorCode()));
                }
                case Answer.OutOfRange outOfRange -> {
                    LOGGER.log(System.Logger.Level.DEBUG, () -> name + " answered api key "
                            + accepted.request().apiKey() + " with " + ErrorCode.OFFSET_OUT_OF_RANGE
                            + " and logStartOffset=" + outOfRange.logStartOffset() + ": " + outOfRange.reason());
                    write(ResponseFrame.encodeOffsetOutOfRange(accepted.correlationId(),
                            outOfRange.logStartOffset()));
                }
            }
        } catch (RuntimeException e) {
            // Not a failure any api declared, so the caller is told only that it was the broker's
            // fault. The context that would locate it goes here, where the operator can see it.
            //
            // Reaching here with bytes of this answer already written would put a second frame on top
            // of half of one, so nothing above may throw a RuntimeException after its first write:
            // a whole frame is built before it is written, and a streamed one turns everything that
            // can still fail past its header into an IOException, which ends the connection instead.
            LOGGER.log(System.Logger.Level.WARNING, name + " failed to serve api key "
                    + accepted.request().apiKey() + " correlationId=" + accepted.correlationId(), e);
            write(ResponseFrame.encodeError(accepted.correlationId(), ErrorCode.INTERNAL));
        }
    }

    /**
     * Writes one fetch answer whose records were never read into memory: the header first, with a
     * length field that already counts every record byte to come, and then those bytes straight out of
     * the segment file they are in.
     *
     * <p>The order is the whole of it. The length is a promise, so it is made only once the range is
     * known and the header is the last thing that may fail on anything other than the file or the
     * socket. After it, the response is finished or the connection is: a transfer that cannot complete
     * raises an {@link IOException}, which ends this connection rather than writing something else to
     * a client that is still counting bytes.
     *
     * @param correlationId the number the request carried
     * @param streamed      the high-water mark and the range to send, which this closes either way
     */
    private void stream(int correlationId, Answer.Streamed streamed) throws IOException {
        try (RecordRange records = streamed.records()) {
            ByteBuffer header = ResponseFrame.encodeFetchHeader(correlationId, streamed.highWaterMark(),
                    records.lengthBytes());
            write(header);
            records.transferTo(socket);
        }
    }

    private void write(ByteBuffer frame) throws IOException {
        ByteChannels.writeFully(socket, frame);
    }
}
