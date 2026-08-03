package io.shrike.core.protocol;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * Frames arranged byte by byte, the way a client that does not share this build's code arranges them
 * — including a client that means harm. Tests written with these bytes prove the parser against the
 * format rather than against its own encoder, and they can say things the encoder never would: a
 * length that lies, a count with no records behind it, a string that runs off the end of the frame.
 */
final class WireFrames {

    private WireFrames() {
    }

    /**
     * @return everything a request frame holds after its length field: the envelope and the body
     */
    static ByteBuffer request(short apiKey, short apiVersion, int correlationId, byte[] body) {
        ByteBuffer frame = ByteBuffer.allocate(RequestFrame.MINIMUM_LENGTH_BYTES + body.length);
        frame.putShort(apiKey);
        frame.putShort(apiVersion);
        frame.putInt(correlationId);
        frame.put(body);
        return frame.flip();
    }

    /**
     * @return everything a response frame holds after its length field: the envelope and the body
     */
    static ByteBuffer response(int correlationId, short errorCode, byte[] body) {
        ByteBuffer frame = ByteBuffer.allocate(ResponseFrame.MINIMUM_LENGTH_BYTES + body.length);
        frame.putInt(correlationId);
        frame.putShort(errorCode);
        frame.put(body);
        return frame.flip();
    }

    /**
     * @return the bytes a client puts on the wire: a truthful length field and everything after it
     */
    static byte[] framed(ByteBuffer afterLength) {
        return framedWithLength(afterLength.remaining(), afterLength);
    }

    /**
     * @param declaredLengthBytes what the length field claims, true or not
     * @return a frame whose length field says whatever the caller wanted it to say
     */
    static byte[] framedWithLength(int declaredLengthBytes, ByteBuffer afterLength) {
        ByteBuffer frame = ByteBuffer.allocate(Integer.BYTES + afterLength.remaining());
        frame.putInt(declaredLengthBytes);
        frame.put(afterLength.duplicate());
        return frame.array();
    }

    /**
     * Splits a frame into the length field and the rest, checking on the way through that the length
     * field counts every byte after itself — the rule both envelopes are built on.
     *
     * @param frame a whole frame, length field included, positioned at its first byte
     * @return every byte after the length field
     */
    static ByteBuffer afterLength(ByteBuffer frame) {
        int lengthBytes = frame.getInt(frame.position());
        assertEquals(frame.remaining() - Integer.BYTES, lengthBytes,
                "the length field counts every byte after itself");
        return frame.slice(frame.position() + Integer.BYTES, lengthBytes);
    }

    /** @return a wire string: an int16 length and the UTF-8 bytes it counts */
    static byte[] string(String value) {
        byte[] utf8 = value.getBytes(UTF_8);
        return concat(int16(utf8.length), utf8);
    }

    /** @return a wire string whose declared length is whatever the caller wanted, true or not */
    static byte[] stringWithLength(int declaredLengthBytes, String value) {
        return concat(int16(declaredLengthBytes), value.getBytes(UTF_8));
    }

    /** @return one produced record on the wire: {@code keyLen | key | valueLen | value} */
    static byte[] record(byte[] key, byte[] value) {
        byte[] keyPart = key == null ? int32(-1) : concat(int32(key.length), key);
        return concat(keyPart, int32(value.length), value);
    }

    static byte[] int16(int value) {
        return ByteBuffer.allocate(Short.BYTES).putShort((short) value).array();
    }

    static byte[] int32(int value) {
        return ByteBuffer.allocate(Integer.BYTES).putInt(value).array();
    }

    static byte[] int64(long value) {
        return ByteBuffer.allocate(Long.BYTES).putLong(value).array();
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            joined.writeBytes(part);
        }
        return joined.toByteArray();
    }
}
