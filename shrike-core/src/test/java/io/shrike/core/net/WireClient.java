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
     * @return whether the broker closed this connection without sending a single byte, which is what a
     *         frame it could not believe earns
     * @throws IOException if the read fails for any other reason
     */
    boolean isClosedWithNoReply() throws IOException {
        ByteBuffer oneByte = ByteBuffer.allocate(1);
        return socket.read(oneByte) < 0;
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
