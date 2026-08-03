package io.shrike.core.net;

import io.shrike.core.log.ByteChannels;
import io.shrike.core.log.RecordRange;
import io.shrike.core.protocol.ErrorCode;
import io.shrike.core.protocol.RequestDecoding;
import io.shrike.core.protocol.RequestReader;
import io.shrike.core.protocol.ResponseFrame;
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
 */
final class Connection implements AutoCloseable {

    private static final System.Logger LOGGER = System.getLogger(Connection.class.getName());

    private final String name;
    private final SocketChannel socket;
    private final RequestReader reader;
    private final RequestDispatcher dispatcher;

    /**
     * @param name       the connection's name, which is also its thread's, so a log line and a thread
     *                   dump name the same connection
     * @param socket     the accepted, blocking socket
     * @param reader     this connection's own frame guard
     * @param dispatcher the broker's answer to a request, shared by every connection
     */
    Connection(String name, SocketChannel socket, RequestReader reader, RequestDispatcher dispatcher) {
        this.name = Objects.requireNonNull(name, "name");
        this.socket = Objects.requireNonNull(socket, "socket");
        this.reader = Objects.requireNonNull(reader, "reader");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
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
