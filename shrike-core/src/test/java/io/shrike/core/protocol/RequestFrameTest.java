package io.shrike.core.protocol;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.shrike.core.log.ProducedRecord;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class RequestFrameTest {

    private static final int CORRELATION_ID = 0x0a0b0c0d;

    @Test
    void roundTripsAProduceRequestWithANullKeyAnEmptyKeyAndAnEmptyValue() {
        ProduceRequest request = new ProduceRequest("orders", 7, List.of(
                new ProducedRecord(null, "first".getBytes(UTF_8)),
                new ProducedRecord(new byte[0], new byte[0]),
                new ProducedRecord("k".getBytes(UTF_8), "third".getBytes(UTF_8))));

        ProduceRequest decoded = decode(request, ProduceRequest.class);

        assertEquals("orders", decoded.topic());
        assertEquals(7, decoded.partition());
        assertEquals(3, decoded.records().size());
        assertNull(decoded.records().get(0).key(), "a null key must not come back as an empty one");
        assertArrayEquals("first".getBytes(UTF_8), decoded.records().get(0).value());
        assertArrayEquals(new byte[0], decoded.records().get(1).key());
        assertArrayEquals(new byte[0], decoded.records().get(1).value());
        assertArrayEquals("k".getBytes(UTF_8), decoded.records().get(2).key());
        assertArrayEquals("third".getBytes(UTF_8), decoded.records().get(2).value());
    }

    @Test
    void roundTripsAProduceRequestOfAsManyRecordsAsTheCapAllows() {
        ProduceRequest request = new ProduceRequest("orders", 0, Collections.nCopies(
                ProduceRequest.MAX_RECORD_COUNT, new ProducedRecord(null, "v".getBytes(UTF_8))));

        ProduceRequest decoded = decode(request, ProduceRequest.class);

        assertEquals(ProduceRequest.MAX_RECORD_COUNT, decoded.records().size());
    }

    @Test
    void roundTripsAFetchRequest() {
        FetchRequest request = new FetchRequest("orders.eu-west_1", 3, 4_000_000_000L, 1024, 500, 1);

        FetchRequest decoded = decode(request, FetchRequest.class);

        assertEquals(request, decoded);
    }

    @Test
    void roundTripsACommitOffsetRequest() {
        CommitOffsetRequest request = new CommitOffsetRequest("billing-group", "orders", 2, Long.MAX_VALUE);

        CommitOffsetRequest decoded = decode(request, CommitOffsetRequest.class);

        assertEquals(request, decoded);
    }

    @Test
    void roundTripsACreateTopicRequest() {
        CreateTopicRequest request = new CreateTopicRequest("orders", CreateTopicRequest.MAX_PARTITION_COUNT);

        CreateTopicRequest decoded = decode(request, CreateTopicRequest.class);

        assertEquals(request, decoded);
    }

    @Test
    void echoesTheCorrelationIdOfEveryRequestBackToTheCaller() {
        CreateTopicRequest request = new CreateTopicRequest("orders", 1);

        RequestDecoding decoding = RequestFrame.decode(
                WireFrames.afterLength(RequestFrame.encode(Integer.MIN_VALUE, request)));

        assertEquals(Integer.MIN_VALUE, assertInstanceOf(RequestDecoding.Accepted.class, decoding).correlationId());
    }

    /**
     * <pre>
     * 00000014            length        = 20 bytes follow the length field
     * 0003                apiKey        = 3, create topic
     * 0000                apiVersion    = 0
     * 0a0b0c0d            correlationId
     * 0006                name length   = 6 bytes of UTF-8
     * 6f7264657273        name          = "orders"
     * 00000004            partitionCount = 4
     * </pre>
     */
    @Test
    void freezesTheRequestEnvelopeLayout() {
        CreateTopicRequest request = new CreateTopicRequest("orders", 4);

        ByteBuffer frame = RequestFrame.encode(CORRELATION_ID, request);

        byte[] frameBytes = new byte[frame.remaining()];
        frame.duplicate().get(frameBytes);
        assertEquals("00000014000300000a0b0c0d00066f726465727300000004", HexFormat.of().formatHex(frameBytes));
    }

    private static <T extends Request> T decode(Request request, Class<T> type) {
        RequestDecoding decoding = RequestFrame.decode(
                WireFrames.afterLength(RequestFrame.encode(CORRELATION_ID, request)));

        RequestDecoding.Accepted accepted = assertInstanceOf(RequestDecoding.Accepted.class, decoding,
                decoding.toString());
        assertEquals(CORRELATION_ID, accepted.correlationId());
        return assertInstanceOf(type, accepted.request());
    }
}
