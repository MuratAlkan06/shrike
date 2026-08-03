package io.shrike.core.protocol;

import static io.shrike.core.protocol.WireFrames.concat;
import static io.shrike.core.protocol.WireFrames.int32;
import static io.shrike.core.protocol.WireFrames.request;
import static io.shrike.core.protocol.WireFrames.string;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * The frame guard against lengths a client has no business sending.
 *
 * <p>The "without allocating" half of each name is proved by {@link LengthOnlyChannel}: it serves the
 * four bytes of the length field and fails the test if anything asks it for another byte. A reader
 * that never reads a body never had a body to size a buffer to, so a run that finishes without
 * tripping the stub is a run in which nothing was allocated for one.
 */
class RequestReaderTest {

    private static final int SMALL_MAX_REQUEST_BYTES = 64;

    @Test
    void closesTheConnectionWithoutAllocatingOnAZeroLength() {
        assertClosesOnLength(0);
    }

    @Test
    void closesTheConnectionWithoutAllocatingOnANegativeLength() {
        assertClosesOnLength(-1);
        assertClosesOnLength(Integer.MIN_VALUE);
    }

    @Test
    void closesTheConnectionWithoutAllocatingOnALengthTooSmallForAnEnvelope() {
        for (int lengthBytes = 1; lengthBytes < RequestFrame.MINIMUM_LENGTH_BYTES; lengthBytes++) {
            assertClosesOnLength(lengthBytes);
        }
    }

    @Test
    void closesTheConnectionWithoutAllocatingOnALengthOverMaxRequestBytes() {
        assertClosesOnLength(SMALL_MAX_REQUEST_BYTES + 1);
        assertClosesOnLength(Integer.MAX_VALUE);
    }

    @Test
    void closesTheConnectionWhenTheStreamEndsBeforeALengthArrives() throws IOException {
        RequestReader reader = new RequestReader(RequestReader.DEFAULT_MAX_REQUEST_BYTES);

        RequestDecoding decoding = reader.readFrom(new ScriptedChannel(new byte[0]));

        assertEquals("the connection ended between requests",
                assertInstanceOf(RequestDecoding.BrokenFrame.class, decoding).reason());
    }

    @Test
    void closesTheConnectionWhenTheStreamEndsInsideALengthField() throws IOException {
        RequestReader reader = new RequestReader(RequestReader.DEFAULT_MAX_REQUEST_BYTES);

        RequestDecoding decoding = reader.readFrom(new ScriptedChannel(new byte[] {0, 0}));

        assertTrue(assertInstanceOf(RequestDecoding.BrokenFrame.class, decoding).reason()
                .contains("2 bytes into a request length"), decoding.toString());
    }

    @Test
    void closesTheConnectionWhenTheStreamEndsInsideAFrame() throws IOException {
        byte[] wholeFrame = WireFrames.framed(request(ApiKeys.CREATE_TOPIC, ApiKeys.VERSION_0, 5,
                concat(string("orders"), int32(1))));
        byte[] cutShort = Arrays.copyOf(wholeFrame, wholeFrame.length - 3);
        RequestReader reader = new RequestReader(RequestReader.DEFAULT_MAX_REQUEST_BYTES);

        RequestDecoding decoding = reader.readFrom(new ScriptedChannel(cutShort));

        assertTrue(assertInstanceOf(RequestDecoding.BrokenFrame.class, decoding).reason()
                .contains("bytes into a 20-byte request"), decoding.toString());
    }

    @Test
    void readsARequestThatArrivesOneByteAtATime() throws IOException {
        byte[] wholeFrame = WireFrames.framed(request(ApiKeys.CREATE_TOPIC, ApiKeys.VERSION_0, 5,
                concat(string("orders"), int32(1))));
        RequestReader reader = new RequestReader(RequestReader.DEFAULT_MAX_REQUEST_BYTES);

        RequestDecoding decoding = reader.readFrom(new ScriptedChannel(wholeFrame));

        RequestDecoding.Accepted accepted = assertInstanceOf(RequestDecoding.Accepted.class, decoding,
                decoding.toString());
        assertEquals(5, accepted.correlationId());
        assertEquals(new CreateTopicRequest("orders", 1), accepted.request());
    }

    @Test
    void readsOneRequestAfterAnotherFromTheSameConnection() throws IOException {
        byte[] first = WireFrames.framed(request(ApiKeys.CREATE_TOPIC, ApiKeys.VERSION_0, 1,
                concat(string("orders"), int32(1))));
        byte[] second = WireFrames.framed(request(ApiKeys.CREATE_TOPIC, ApiKeys.VERSION_0, 2,
                concat(string("payments"), int32(2))));
        ReadableByteChannel connection = new ScriptedChannel(concat(first, second));
        RequestReader reader = new RequestReader(RequestReader.DEFAULT_MAX_REQUEST_BYTES);

        RequestDecoding firstDecoding = reader.readFrom(connection);
        RequestDecoding secondDecoding = reader.readFrom(connection);

        assertEquals(new CreateTopicRequest("orders", 1),
                assertInstanceOf(RequestDecoding.Accepted.class, firstDecoding).request());
        assertEquals(new CreateTopicRequest("payments", 2),
                assertInstanceOf(RequestDecoding.Accepted.class, secondDecoding).request());
    }

    @Test
    void keepsTheConnectionWhenTheFrameIsWholeButItsBodyIsNot() throws IOException {
        byte[] frame = WireFrames.framed(request(ApiKeys.CREATE_TOPIC, ApiKeys.VERSION_0, 9,
                concat(string("../etc"), int32(1))));
        RequestReader reader = new RequestReader(RequestReader.DEFAULT_MAX_REQUEST_BYTES);

        RequestDecoding decoding = reader.readFrom(new ScriptedChannel(frame));

        RequestDecoding.Refused refused = assertInstanceOf(RequestDecoding.Refused.class, decoding,
                decoding.toString());
        assertEquals(9, refused.correlationId());
        assertEquals(ErrorCode.INVALID_REQUEST, refused.errorCode());
    }

    @Test
    void framesARequestOfExactlyMaxRequestBytesAndClosesOnOneByteMore() throws IOException {
        RequestReader reader = new RequestReader(RequestFrame.MINIMUM_LENGTH_BYTES);

        RequestDecoding decoding = reader.readFrom(new ScriptedChannel(WireFrames.framed(
                request(ApiKeys.CREATE_TOPIC, ApiKeys.VERSION_0, 4, new byte[0]))));

        assertInstanceOf(RequestDecoding.Refused.class, decoding, "an envelope with no body is framed, just empty");
        assertClosesOnLength(RequestFrame.MINIMUM_LENGTH_BYTES + 1, RequestFrame.MINIMUM_LENGTH_BYTES);
    }

    @Test
    void refusesAMaxRequestBytesTooSmallToHoldAnEnvelope() {
        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> new RequestReader(RequestFrame.MINIMUM_LENGTH_BYTES - 1));

        assertTrue(refusal.getMessage().contains("maxRequestBytes"), refusal.getMessage());
    }

    @Test
    void defaultsToAFourMebibyteRequestBound() {
        assertEquals(4 * 1024 * 1024, RequestReader.DEFAULT_MAX_REQUEST_BYTES);
        assertEquals(4 * 1024 * 1024, new RequestReader(RequestReader.DEFAULT_MAX_REQUEST_BYTES).maxRequestBytes());
    }

    private static void assertClosesOnLength(int declaredLengthBytes) {
        assertClosesOnLength(declaredLengthBytes, SMALL_MAX_REQUEST_BYTES);
    }

    private static void assertClosesOnLength(int declaredLengthBytes, int maxRequestBytes) {
        LengthOnlyChannel channel = new LengthOnlyChannel(declaredLengthBytes);
        RequestReader reader = new RequestReader(maxRequestBytes);

        RequestDecoding decoding;
        try {
            decoding = reader.readFrom(channel);
        } catch (IOException e) {
            throw new AssertionError("reading a length field must not fail", e);
        }

        RequestDecoding.BrokenFrame broken = assertInstanceOf(RequestDecoding.BrokenFrame.class, decoding,
                "length " + declaredLengthBytes + " must close the connection");
        assertTrue(broken.reason().contains("no reply is owed"), broken.reason());
    }

    /**
     * Serves the four bytes of a length field and nothing else: a fifth byte read is a body being
     * read, which is a body having been allocated, which is the bug this stub exists to catch.
     */
    private static final class LengthOnlyChannel implements ReadableByteChannel {

        private final ByteBuffer lengthField;

        // confined to: the test thread that created this stub
        private boolean open = true;

        private LengthOnlyChannel(int declaredLengthBytes) {
            this.lengthField = ByteBuffer.allocate(Integer.BYTES).putInt(declaredLengthBytes).flip();
        }

        @Override
        public int read(ByteBuffer destination) {
            if (!lengthField.hasRemaining()) {
                fail("the reader asked for " + destination.remaining()
                        + " bytes of body after a length it had already refused");
            }
            int transferredBytes = Math.min(destination.remaining(), lengthField.remaining());
            destination.put(lengthField.slice(lengthField.position(), transferredBytes));
            lengthField.position(lengthField.position() + transferredBytes);
            return transferredBytes;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }
    }

    /**
     * Serves a script of bytes one byte per call and then ends the stream, which is what a socket is
     * allowed to do and what a reader that assumes one read fills a buffer gets wrong.
     */
    private static final class ScriptedChannel implements ReadableByteChannel {

        private final byte[] script;

        // confined to: the test thread that created this stub
        private int positionBytes;
        private boolean open = true;

        private ScriptedChannel(byte[] script) {
            this.script = script;
        }

        @Override
        public int read(ByteBuffer destination) {
            if (positionBytes == script.length) {
                return -1;
            }
            if (!destination.hasRemaining()) {
                return 0;
            }
            destination.put(script[positionBytes++]);
            return 1;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }
    }
}
