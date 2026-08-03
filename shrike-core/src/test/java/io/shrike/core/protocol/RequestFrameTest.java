package io.shrike.core.protocol;

import static io.shrike.core.protocol.WireFrames.concat;
import static io.shrike.core.protocol.WireFrames.int32;
import static io.shrike.core.protocol.WireFrames.request;
import static io.shrike.core.protocol.WireFrames.string;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void roundTripsADescribeTopicsRequestThatNamesNoTopicAndSoMeansEveryTopic() {
        DescribeTopicsRequest request = DescribeTopicsRequest.everyTopic();

        DescribeTopicsRequest decoded = decode(request, DescribeTopicsRequest.class);

        assertEquals(List.of(), decoded.topics());
        assertTrue(decoded.describesEveryTopic(), "a count of zero is how the wire says every topic");
    }

    @Test
    void roundTripsADescribeTopicsRequestThatNamesSeveralTopics() {
        DescribeTopicsRequest request = new DescribeTopicsRequest(List.of("orders", "events", "orders.eu-west_1"));

        DescribeTopicsRequest decoded = decode(request, DescribeTopicsRequest.class);

        assertEquals(List.of("orders", "events", "orders.eu-west_1"), decoded.topics(),
                "the names come back in the order they were asked about");
        assertFalse(decoded.describesEveryTopic());
    }

    @Test
    void roundTripsADescribeGroupRequest() {
        DescribeGroupRequest request = new DescribeGroupRequest("billing-group");

        DescribeGroupRequest decoded = decode(request, DescribeGroupRequest.class);

        assertEquals(request, decoded);
    }

    /**
     * The body a client that does not share this build's encoder would lay out, both ways: these are the
     * bytes the encoder writes, and these are the bytes the decoder reads back as the same request.
     *
     * <pre>
     * 00000000            topicCount = 0, which is every topic
     * </pre>
     *
     * <pre>
     * 00000002            topicCount = 2
     * 0006 6f7264657273   "orders"
     * 0006 6576656e7473   "events"
     * </pre>
     */
    @Test
    void readsTheDescribeTopicsBodyOfAClientThatBuiltItByHand() {
        byte[] everyTopic = int32(0);
        byte[] twoTopics = concat(int32(2), string("orders"), string("events"));

        assertArrayEquals(everyTopic, bodyOf(DescribeTopicsRequest.everyTopic()));
        assertArrayEquals(twoTopics, bodyOf(new DescribeTopicsRequest(List.of("orders", "events"))));
        assertEquals(DescribeTopicsRequest.everyTopic(), decodeBody(ApiKeys.DESCRIBE_TOPICS, everyTopic));
        assertEquals(new DescribeTopicsRequest(List.of("orders", "events")),
                decodeBody(ApiKeys.DESCRIBE_TOPICS, twoTopics));
    }

    /**
     * <pre>
     * 0007 726561646572 73   "readers"
     * </pre>
     */
    @Test
    void readsTheDescribeGroupBodyOfAClientThatBuiltItByHand() {
        byte[] body = string("readers");

        assertArrayEquals(body, bodyOf(new DescribeGroupRequest("readers")));
        assertEquals(new DescribeGroupRequest("readers"), decodeBody(ApiKeys.DESCRIBE_GROUP, body));
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

    /** @return everything the encoder wrote after the envelope, which is the body alone */
    private static byte[] bodyOf(Request request) {
        ByteBuffer frame = WireFrames.afterLength(RequestFrame.encode(CORRELATION_ID, request));

        byte[] body = new byte[frame.remaining() - RequestFrame.MINIMUM_LENGTH_BYTES];
        frame.duplicate().position(frame.position() + RequestFrame.MINIMUM_LENGTH_BYTES).get(body);
        return body;
    }

    /** @return the request those hand-built body bytes are, under an envelope naming that api key */
    private static Request decodeBody(short apiKey, byte[] body) {
        RequestDecoding decoding = RequestFrame.decode(request(apiKey, ApiKeys.VERSION_0, CORRELATION_ID, body));

        return assertInstanceOf(RequestDecoding.Accepted.class, decoding, decoding.toString()).request();
    }
}
