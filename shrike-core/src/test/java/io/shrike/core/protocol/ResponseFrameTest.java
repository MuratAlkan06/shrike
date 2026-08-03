package io.shrike.core.protocol;

import static io.shrike.core.protocol.WireFrames.concat;
import static io.shrike.core.protocol.WireFrames.int32;
import static io.shrike.core.protocol.WireFrames.int64;
import static io.shrike.core.protocol.WireFrames.response;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class ResponseFrameTest {

    private static final int CORRELATION_ID = 0x0a0b0c0d;

    @Test
    void roundTripsAProduceResponse() {
        ProduceResponse response = new ProduceResponse(4_000_000_000L);

        ProduceResponse decoded = decode(response, ProduceResponse.class);

        assertEquals(response, decoded);
    }

    @Test
    void roundTripsAFetchResponseWithItsRecordsCopiedByteForByte() {
        byte[] records = "these bytes came off a log file and are not looked inside".getBytes(UTF_8);
        FetchResponse response = new FetchResponse(41L, records);

        FetchResponse decoded = decode(response, FetchResponse.class);

        assertEquals(41L, decoded.highWaterMark());
        assertArrayEquals(records, decoded.records());
    }

    @Test
    void roundTripsAFetchResponseThatCarriesNoRecords() {
        FetchResponse response = new FetchResponse(0L, new byte[0]);

        FetchResponse decoded = decode(response, FetchResponse.class);

        assertEquals(0L, decoded.highWaterMark());
        assertArrayEquals(new byte[0], decoded.records());
    }

    @Test
    void roundTripsTheEmptyBodiesOfCommitOffsetAndCreateTopic() {
        assertEquals(new CommitOffsetResponse(), decode(new CommitOffsetResponse(), CommitOffsetResponse.class));
        assertEquals(new CreateTopicResponse(), decode(new CreateTopicResponse(), CreateTopicResponse.class));
    }

    /**
     * <pre>
     * 00000006            length        = 6 bytes follow the length field
     * 0a0b0c0d            correlationId
     * 0005                errorCode     = 5, invalid request
     * </pre>
     */
    @Test
    void encodesAnErrorResponseAsACodeAndAnEmptyBody() {
        ByteBuffer frame = ResponseFrame.encodeError(CORRELATION_ID, ErrorCode.INVALID_REQUEST);

        byte[] frameBytes = new byte[frame.remaining()];
        frame.duplicate().get(frameBytes);
        assertEquals("000000060a0b0c0d0005", HexFormat.of().formatHex(frameBytes));
    }

    @Test
    void readsAnErrorResponseBackAsAFailureWhicheverRequestItAnswers() {
        ByteBuffer frame = WireFrames.afterLength(
                ResponseFrame.encodeError(CORRELATION_ID, ErrorCode.OFFSET_OUT_OF_RANGE));

        ResponseDecoding decoding = ResponseFrame.decode(ApiKeys.FETCH, frame);

        ResponseDecoding.Failed failed = assertInstanceOf(ResponseDecoding.Failed.class, decoding,
                decoding.toString());
        assertEquals(CORRELATION_ID, failed.correlationId());
        assertEquals(ErrorCode.OFFSET_OUT_OF_RANGE, failed.errorCode());
    }

    @Test
    void refusesToEncodeAnErrorResponseThatSaysNothingWentWrong() {
        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> ResponseFrame.encodeError(CORRELATION_ID, ErrorCode.NONE));

        assertTrue(refusal.getMessage().contains("NONE"), refusal.getMessage());
    }

    @Test
    void refusesAnErrorCodeThisBuildDoesNotKnow() {
        ByteBuffer frame = response(CORRELATION_ID, (short) 42, new byte[0]);

        ResponseDecoding decoding = ResponseFrame.decode(ApiKeys.PRODUCE, frame);

        assertTrue(assertInstanceOf(ResponseDecoding.BrokenFrame.class, decoding).reason().contains("42"),
                decoding.toString());
    }

    @Test
    void refusesAnErrorResponseThatCarriesABody() {
        ByteBuffer frame = response(CORRELATION_ID, ErrorCode.INTERNAL.code(), int64(7));

        ResponseDecoding decoding = ResponseFrame.decode(ApiKeys.PRODUCE, frame);

        assertTrue(assertInstanceOf(ResponseDecoding.BrokenFrame.class, decoding).reason().contains("empty body"),
                decoding.toString());
    }

    @Test
    void refusesAResponseFrameTooShortToHoldAnEnvelope() {
        ByteBuffer frame = ByteBuffer.wrap(int32(CORRELATION_ID));

        ResponseDecoding decoding = ResponseFrame.decode(ApiKeys.PRODUCE, frame);

        assertInstanceOf(ResponseDecoding.BrokenFrame.class, decoding, decoding.toString());
    }

    @Test
    void refusesABodyThatEndsInsideAField() {
        ByteBuffer frame = response(CORRELATION_ID, ErrorCode.NONE.code(), int32(0));

        ResponseDecoding decoding = ResponseFrame.decode(ApiKeys.PRODUCE, frame);

        assertTrue(assertInstanceOf(ResponseDecoding.BrokenFrame.class, decoding).reason().contains("baseOffset"),
                decoding.toString());
    }

    @Test
    void refusesARecordsSizeThatDoesNotMatchTheBytesInHand() {
        for (int recordsSizeBytes : new int[] {-1, 1, Integer.MAX_VALUE}) {
            ByteBuffer frame = response(CORRELATION_ID, ErrorCode.NONE.code(),
                    concat(int64(0), int32(recordsSizeBytes), "ab".getBytes(UTF_8)));

            ResponseDecoding decoding = ResponseFrame.decode(ApiKeys.FETCH, frame);

            assertTrue(assertInstanceOf(ResponseDecoding.BrokenFrame.class, decoding).reason()
                    .contains("recordsSizeBytes " + recordsSizeBytes), decoding.toString());
        }
    }

    @Test
    void refusesTrailingBytesAfterACompleteBody() {
        ByteBuffer frame = response(CORRELATION_ID, ErrorCode.NONE.code(), concat(int64(7), int32(0)));

        ResponseDecoding decoding = ResponseFrame.decode(ApiKeys.PRODUCE, frame);

        assertTrue(assertInstanceOf(ResponseDecoding.BrokenFrame.class, decoding).reason().contains("left over"),
                decoding.toString());
    }

    @Test
    void refusesToDecodeAResponseToAnApiKeyThisBuildNeverSent() {
        ByteBuffer frame = response(CORRELATION_ID, ErrorCode.NONE.code(), new byte[0]);

        assertThrows(IllegalArgumentException.class, () -> ResponseFrame.decode(ApiKeys.DESCRIBE_GROUP, frame));
    }

    private static <T extends Response> T decode(Response response, Class<T> type) {
        ResponseDecoding decoding = ResponseFrame.decode(response.apiKey(),
                WireFrames.afterLength(ResponseFrame.encode(CORRELATION_ID, response)));

        ResponseDecoding.Answered answered = assertInstanceOf(ResponseDecoding.Answered.class, decoding,
                decoding.toString());
        assertEquals(CORRELATION_ID, answered.correlationId());
        return assertInstanceOf(type, answered.response());
    }
}
