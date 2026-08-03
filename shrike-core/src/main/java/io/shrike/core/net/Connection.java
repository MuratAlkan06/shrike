package io.shrike.core.net;

import io.shrike.core.log.ByteChannels;
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
 *       talking, just wrongly;
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
        ByteBuffer frame;
        try {
            frame = switch (dispatcher.dispatch(accepted.request())) {
                case Answer.Served served -> ResponseFrame.encode(accepted.correlationId(), served.response());
                case Answer.Refused refused -> {
                    LOGGER.log(System.Logger.Level.DEBUG, () -> name + " answered api key "
                            + accepted.request().apiKey() + " with " + refused.errorCode() + ": " + refused.reason());
                    yield ResponseFrame.encodeError(accepted.correlationId(), refused.errorCode());
                }
            };
        } catch (RuntimeException e) {
            // Not a failure any api declared, so the caller is told only that it was the broker's
            // fault. The context that would locate it goes here, where the operator can see it.
            LOGGER.log(System.Logger.Level.WARNING, name + " failed to serve api key "
                    + accepted.request().apiKey() + " correlationId=" + accepted.correlationId(), e);
            frame = ResponseFrame.encodeError(accepted.correlationId(), ErrorCode.INTERNAL);
        }
        write(frame);
    }

    private void write(ByteBuffer frame) throws IOException {
        ByteChannels.writeFully(socket, frame);
    }
}
