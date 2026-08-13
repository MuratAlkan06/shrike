package io.shrike.core.net;

import io.shrike.core.log.ByteChannels;
import io.shrike.core.protocol.Request;
import io.shrike.core.protocol.RequestFrame;
import io.shrike.core.protocol.ResponseDecoding;
import io.shrike.core.protocol.ResponseFrame;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

/**
 * A client that speaks the wire protocol over a plain socket, so the tests exercise the broker the way
 * anything else on the network would: bytes in, bytes out, no shared objects.
 *
 * <p>It encodes with the same codec the broker decodes with, which is deliberate for everything except
 * the hostile cases — those send bytes by hand through {@link #sendRaw(byte[])}, because an encoder
 * would never produce them.
 */
final class WireClient implements AutoCloseable {

    private final SocketChannel socket;

    private WireClient(SocketChannel socket) {
        this.socket = socket;
    }

    /**
     * @param broker a started broker
     * @return a connected client
     * @throws IOException if the connection is refused
     */
    static WireClient connectTo(ShrikeBroker broker) throws IOException {
        return new WireClient(SocketChannel.open(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), broker.port())));
    }

    /**
     * Connects with a receive buffer small enough that the broker cannot hand a large answer to the
     * kernel and walk away from it. A test that has to act while a response is still being written
     * needs the response to still be being written, and the buffer is set before the connect because
     * that is the only point at which it can change the window the peer is told about.
     *
     * @param broker             a started broker
     * @param receiveBufferBytes what to ask for as {@code SO_RCVBUF}
     * @return a connected client
     * @throws IOException if the connection is refused
     */
    static WireClient connectWithASmallReceiveBuffer(ShrikeBroker broker, int receiveBufferBytes) throws IOException {
        SocketChannel socket = SocketChannel.open();
        try {
            socket.setOption(StandardSocketOptions.SO_RCVBUF, receiveBufferBytes);
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), broker.port()));
        } catch (IOException e) {
            socket.close();
            throw e;
        }
        return new WireClient(socket);
    }

    /**
     * Sends one request and reads its answer.
     *
     * @param correlationId the number the answer must echo
     * @param request       the request to send
     * @return what came back
     * @throws IOException if the connection fails
     */
    ResponseDecoding call(int correlationId, Request request) throws IOException {
        send(correlationId, request);
        return receive(request.apiKey());
    }

    void send(int correlationId, Request request) throws IOException {
        ByteChannels.writeFully(socket, RequestFrame.encode(correlationId, request));
    }

    void sendRaw(byte[] bytes) throws IOException {
        ByteChannels.writeFully(socket, ByteBuffer.wrap(bytes));
    }

    /**
     * @param apiKey the api key of the request this answers, which the envelope does not carry
     * @return the decoded answer
     * @throws EOFException if the broker closed the connection instead of answering
     * @throws IOException  if the connection fails
     */
    ResponseDecoding receive(short apiKey) throws IOException {
        int lengthBytes = readFully(ResponseFrame.LENGTH_FIELD_BYTES).getInt();
        return ResponseFrame.decode(apiKey, readFully(lengthBytes));
    }

    /**
     * Sends one request and reads its answer as the bytes that came off the socket, length field and
     * all, without decoding any of them. Used where the claim is about the bytes rather than about
     * what they mean.
     *
     * @param correlationId the number the answer must echo
     * @param request       the request to send
     * @return every byte of the response frame
     * @throws IOException if the connection fails
     */
    byte[] callForBytes(int correlationId, Request request) throws IOException {
        send(correlationId, request);
        int lengthBytes = receiveResponseLength();
        ByteBuffer frame = ByteBuffer.allocate(ResponseFrame.LENGTH_FIELD_BYTES + lengthBytes);
        return frame.putInt(lengthBytes).put(readFully(lengthBytes)).array();
    }

    /**
     * Reads the length field of the next response and nothing else, which is the broker's promise
     * about how many bytes are still to come.
     *
     * @return what that field says
     * @throws IOException if the connection fails
     */
    int receiveResponseLength() throws IOException {
        return readFully(ResponseFrame.LENGTH_FIELD_BYTES).getInt();
    }

    /**
     * @param lengthBytes how many bytes to take off the socket
     * @return exactly that many bytes
     * @throws java.io.EOFException if the broker closed the connection part way through them
     * @throws IOException          if the connection fails
     */
    byte[] receiveExactly(int lengthBytes) throws IOException {
        return readFully(lengthBytes).array();
    }

    /**
     * @return whether the broker closed this connection without sending a single byte, which is what a
     *         frame it could not believe earns. A clean end of stream and a connection reset both count:
     *         when the broker closes a connection whose client had sent bytes it had not yet read — a
     *         reaped stall being the case that matters — those unread bytes make the close an abortive
     *         one, so the peer sees an RST rather than a FIN, and neither carries a reply. This is the
     *         same reading {@code BrokerWriteTimeoutTest.socketEndsBefore} takes of a reset from the
     *         other side of the connection.
     * @throws IOException if the read fails for any reason other than the peer resetting the connection
     */
    boolean isClosedWithNoReply() throws IOException {
        ByteBuffer oneByte = ByteBuffer.allocate(1);
        try {
            return socket.read(oneByte) < 0;
        } catch (SocketException connectionReset) {
            // An abortive close is still a close with no reply. The bytes this client sent and the broker
            // reaped before reading are what turn the FIN into an RST; no answer byte was ever delivered,
            // which is the whole of what this asks, so a reset is an affirmative answer and not an error.
            return true;
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }

    private ByteBuffer readFully(int lengthBytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(lengthBytes);
        while (buffer.hasRemaining()) {
            if (socket.read(buffer) < 0) {
                throw new EOFException("the broker closed the connection " + buffer.position() + " bytes into a "
                        + lengthBytes + "-byte read");
            }
        }
        return buffer.flip();
    }
}
