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
import java.util.Arrays;
import java.util.HexFormat;
import java.util.OptionalLong;
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
                ResponseFrame.encodeError(CORRELATION_ID, ErrorCode.UNKNOWN_TOPIC_OR_PARTITION));

        ResponseDecoding decoding = ResponseFrame.decode(ApiKeys.FETCH, frame);

        ResponseDecoding.Failed failed = assertInstanceOf(ResponseDecoding.Failed.class, decoding,
                decoding.toString());
        assertEquals(CORRELATION_ID, failed.correlationId());
        assertEquals(ErrorCode.UNKNOWN_TOPIC_OR_PARTITION, failed.errorCode());
        assertEquals(OptionalLong.empty(), failed.logStartOffset(),
                "every code but offset out of range answers with the code and nothing else");
    }

    /**
     * <pre>
     * 0000000e            length         = 14 bytes follow the length field
     * 0a0b0c0d            correlationId
     * 0002                errorCode      = 2, offset out of range
     * 00000000ee6b2800    logStartOffset = 4 000 000 000, past what an int32 could carry
     * </pre>
     */
    @Test
    void encodesOffsetOutOfRangeAsACodeAndTheOffsetThePartitionNowStartsAt() {
        ByteBuffer frame = ResponseFrame.encodeOffsetOutOfRange(CORRELATION_ID, 4_000_000_000L);

        byte[] frameBytes = new byte[frame.remaining()];
        frame.duplicate().get(frameBytes);
        assertEquals("0000000e0a0b0c0d000200000000ee6b2800", HexFormat.of().formatHex(frameBytes));
    }

    @Test
    void readsOffsetOutOfRangeBackWithTheOffsetItCarries() {
        ByteBuffer frame = WireFrames.afterLength(ResponseFrame.encodeOffsetOutOfRange(CORRELATION_ID, 41L));

        ResponseDecoding decoding = ResponseFrame.decode(ApiKeys.FETCH, frame);

        ResponseDecoding.Failed failed = assertInstanceOf(ResponseDecoding.Failed.class, decoding,
                decoding.toString());
        assertEquals(CORRELATION_ID, failed.correlationId());
        assertEquals(ErrorCode.OFFSET_OUT_OF_RANGE, failed.errorCode());
        assertEquals(OptionalLong.of(41L), failed.logStartOffset());
    }

    @Test
    void refusesAnOffsetOutOfRangeResponseWithoutItsLogStartOffset() {
        for (byte[] body : new byte[][] {new byte[0], int32(41), concat(int64(41L), int32(0))}) {
            ByteBuffer frame = response(CORRELATION_ID, ErrorCode.OFFSET_OUT_OF_RANGE.code(), body);

            ResponseDecoding decoding = ResponseFrame.decode(ApiKeys.FETCH, frame);

            assertTrue(assertInstanceOf(ResponseDecoding.BrokenFrame.class, decoding).reason()
                    .contains("log start offset"), decoding.toString());
        }
    }

    @Test
    void refusesToEncodeOffsetOutOfRangeWithoutItsBody() {
        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> ResponseFrame.encodeError(CORRELATION_ID, ErrorCode.OFFSET_OUT_OF_RANGE));

        assertTrue(refusal.getMessage().contains("encodeOffsetOutOfRange"), refusal.getMessage());
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

    /**
     * A fetch whose records are sent out of the segment file writes its header alone and its records
     * after it, so the header has to be the prefix of the frame the whole-body encoder would have
     * written — length field included, which means already counting records that are not in it yet.
     * A client is not told which of the two answered it and must not be able to work it out.
     */
    @Test
    void laysOutAFetchHeaderThatIsThePrefixOfTheWholeFrameItPromises() {
        byte[] records = "these bytes stayed in the log file".getBytes(UTF_8);
        byte[] wholeFrame = bytesOf(ResponseFrame.encode(CORRELATION_ID, new FetchResponse(41L, records)));

        byte[] header = bytesOf(ResponseFrame.encodeFetchHeader(CORRELATION_ID, 41L, records.length));

        assertEquals(ResponseFrame.LENGTH_FIELD_BYTES + ResponseFrame.MINIMUM_LENGTH_BYTES
                + ResponseFrame.FETCH_RECORDS_PREFIX_BYTES, header.length, "the header stops before the records");
        assertArrayEquals(Arrays.copyOfRange(wholeFrame, 0, header.length), header);
        assertArrayEquals(wholeFrame, concat(header, records), "and the two together are that frame");
    }

    @Test
    void refusesToLayOutAFetchHeaderForRecordsThatCannotBeCounted() {
        assertThrows(IllegalArgumentException.class,
                () -> ResponseFrame.encodeFetchHeader(CORRELATION_ID, 0L, -1));
        assertThrows(IllegalArgumentException.class,
                () -> ResponseFrame.encodeFetchHeader(CORRELATION_ID, 0L, Integer.MAX_VALUE));
    }

    private static byte[] bytesOf(ByteBuffer frame) {
        byte[] bytes = new byte[frame.remaining()];
        frame.get(bytes);
        return bytes;
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
