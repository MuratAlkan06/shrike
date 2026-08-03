package io.shrike.core.protocol;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.util.Objects;

/**
 * The frame guard: the only way a request gets off a connection and into the broker.
 *
 * <p>It reads exactly four bytes, decides whether the length they carry is one this broker will
 * accept, and only then allocates a buffer for the body. A length outside
 * {@code [MINIMUM_LENGTH_BYTES, maxRequestBytes]} ends the connection with no reply and nothing
 * allocated, and so does a stream that ends inside a frame. Everything else — an api key that does
 * not exist, a string longer than the frame, a record count of two billion — is a request the caller
 * is owed an error response for, and the connection survives it.
 *
 * <p>The split matters, and it is not a taste: the correlation id a reply would be addressed to lives
 * <em>after</em> the length. If the length is nonsense then so are the bytes claiming to be a
 * correlation id, so a reply would be addressed to nobody. Refusing to answer, and refusing to
 * allocate, is the only honest response to a frame this reader cannot believe.
 *
 * <p>The channel is assumed to be blocking, which is what the socket layer hands over. Reads are
 * looped, because a channel is allowed to return fewer bytes than were asked for.
 */
public final class RequestReader {

    /** Four mebibytes: the default {@code max.request.bytes}. */
    public static final int DEFAULT_MAX_REQUEST_BYTES = 4 * 1024 * 1024;

    private final int maxRequestBytes;

    /**
     * @param maxRequestBytes the largest {@code length} this reader will believe, and so the most
     *                        memory one connection can make the broker hold for a request
     * @throws IllegalArgumentException if the bound is too small to hold an envelope
     */
    public RequestReader(int maxRequestBytes) {
        if (maxRequestBytes < RequestFrame.MINIMUM_LENGTH_BYTES) {
            throw new IllegalArgumentException("maxRequestBytes must be at least "
                    + RequestFrame.MINIMUM_LENGTH_BYTES + ", the size of an empty request's envelope, but was "
                    + maxRequestBytes);
        }
        this.maxRequestBytes = maxRequestBytes;
    }

    /**
     * @return the largest {@code length} this reader will believe
     */
    public int maxRequestBytes() {
        return maxRequestBytes;
    }

    /**
     * Reads one request from a connection.
     *
     * @param channel the connection, which is expected to block until it has bytes or ends
     * @return what the caller is owed: a request to serve, a refusal to answer with, or a broken frame
     *         to close the connection over
     * @throws IOException if the connection fails mid-read, which the caller closes over just as it
     *                     does a broken frame
     */
    public RequestDecoding readFrom(ReadableByteChannel channel) throws IOException {
        Objects.requireNonNull(channel, "channel");

        ByteBuffer lengthField = ByteBuffer.allocate(RequestFrame.LENGTH_FIELD_BYTES);
        if (!fillFully(channel, lengthField)) {
            return new RequestDecoding.BrokenFrame(lengthField.position() == 0
                    ? "the connection ended between requests"
                    : "the connection ended " + lengthField.position() + " bytes into a request length");
        }

        int lengthBytes = lengthField.flip().getInt();
        if (lengthBytes < RequestFrame.MINIMUM_LENGTH_BYTES || lengthBytes > maxRequestBytes) {
            return new RequestDecoding.BrokenFrame("request length " + lengthBytes + " is outside ["
                    + RequestFrame.MINIMUM_LENGTH_BYTES + ", " + maxRequestBytes
                    + "], so no body was read and no reply is owed");
        }

        ByteBuffer frame = ByteBuffer.allocate(lengthBytes);
        if (!fillFully(channel, frame)) {
            return new RequestDecoding.BrokenFrame("the connection ended " + frame.position() + " bytes into a "
                    + lengthBytes + "-byte request");
        }

        return RequestFrame.decode(frame.flip());
    }

    /**
     * @return whether the buffer was filled; {@code false} means the stream ended first, and how far
     *         it got is the buffer's position
     */
    private static boolean fillFully(ReadableByteChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                return false;
            }
        }
        return true;
    }
}
