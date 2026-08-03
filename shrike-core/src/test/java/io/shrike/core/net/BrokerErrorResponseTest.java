package io.shrike.core.net;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.shrike.core.log.LogConfig;
import io.shrike.core.log.ProducedRecord;
import io.shrike.core.protocol.CommitOffsetRequest;
import io.shrike.core.protocol.CreateTopicRequest;
import io.shrike.core.protocol.ErrorCode;
import io.shrike.core.protocol.FetchRequest;
import io.shrike.core.protocol.FetchResponse;
import io.shrike.core.protocol.ProduceRequest;
import io.shrike.core.protocol.ProduceResponse;
import io.shrike.core.protocol.RequestFrame;
import io.shrike.core.protocol.ResponseDecoding;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the broker says when a request is legal bytes but the wrong question, and what it says when the
 * bytes themselves cannot be believed.
 *
 * <p>Every one of these goes over a real socket, because the point is not only which code comes back
 * but what happens to the connection: a request the broker refuses leaves the connection open and
 * usable, and a frame it cannot believe closes the connection without a byte in reply.
 */
class BrokerErrorResponseTest {

    private static final String TOPIC = "orders";
    private static final int ONLY_PARTITION_COUNT = 1;
    private static final int PARTITION = 0;
    private static final int ANSWER_IMMEDIATELY_MS = 0;
    private static final int PLENTY_OF_BYTES = 64 * 1024;
    private static final String GROUP = "readers";

    @TempDir
    Path dataDirectory;

    private ShrikeBroker broker;
    private WireClient client;
    private ExecutorService readerThread;

    @BeforeEach
    void startBroker() throws IOException {
        broker = BrokerHarness.start(dataDirectory);
        client = WireClient.connectTo(broker);
        readerThread = Executors.newSingleThreadExecutor();
        client.call(1, new CreateTopicRequest(TOPIC, ONLY_PARTITION_COUNT));
    }

    @AfterEach
    void stopBroker() throws IOException {
        readerThread.shutdownNow();
        client.close();
        broker.close();
    }

    @Test
    void answersARequestForATopicItDoesNotHoldWithUnknownTopicOrPartition() throws Exception {
        ResponseDecoding fetched = client.call(2, new FetchRequest("no.such.topic", PARTITION, 0L, PLENTY_OF_BYTES,
                ANSWER_IMMEDIATELY_MS, 0));
        ResponseDecoding produced = client.call(3, new ProduceRequest("no.such.topic", PARTITION,
                List.of(new ProducedRecord(null, "payload".getBytes(UTF_8)))));

        assertEquals(ErrorCode.UNKNOWN_TOPIC_OR_PARTITION, failureOf(fetched));
        assertEquals(ErrorCode.UNKNOWN_TOPIC_OR_PARTITION, failureOf(produced));
    }

    @Test
    void answersARequestForAPartitionThisTopicDoesNotHaveWithUnknownTopicOrPartition() throws Exception {
        int pastTheLastPartition = ONLY_PARTITION_COUNT;

        ResponseDecoding fetched = client.call(4, new FetchRequest(TOPIC, pastTheLastPartition, 0L, PLENTY_OF_BYTES,
                ANSWER_IMMEDIATELY_MS, 0));
        ResponseDecoding negative = client.call(5, new FetchRequest(TOPIC, -1, 0L, PLENTY_OF_BYTES,
                ANSWER_IMMEDIATELY_MS, 0));

        assertEquals(ErrorCode.UNKNOWN_TOPIC_OR_PARTITION, failureOf(fetched));
        assertEquals(ErrorCode.UNKNOWN_TOPIC_OR_PARTITION, failureOf(negative),
                "a negative partition is a partition the broker does not have, not a broken frame");
    }

    @Test
    void answersAFetchPastTheHighWaterMarkWithOffsetOutOfRange() throws Exception {
        client.call(6, new ProduceRequest(TOPIC, PARTITION, List.of(new ProducedRecord(null, "one".getBytes(UTF_8)))));

        ResponseDecoding pastTheEnd = client.call(7, new FetchRequest(TOPIC, PARTITION, 2L, PLENTY_OF_BYTES,
                ANSWER_IMMEDIATELY_MS, 0));
        ResponseDecoding atTheEnd = client.call(8, new FetchRequest(TOPIC, PARTITION, 1L, PLENTY_OF_BYTES,
                ANSWER_IMMEDIATELY_MS, 0));

        assertEquals(ErrorCode.OFFSET_OUT_OF_RANGE, failureOf(pastTheEnd));
        assertInstanceOf(ResponseDecoding.Answered.class, atTheEnd,
                "the high-water mark itself is in range: it is where a caught-up consumer sits");
    }

    @Test
    void answersAProduceOfARecordOverMaxRecordBytesWithFrameTooLargeAndStoresNoneOfTheRequest() throws Exception {
        byte[] tooLarge = new byte[LogConfig.DEFAULT_MAX_RECORD_BYTES];

        ResponseDecoding refused = client.call(9, new ProduceRequest(TOPIC, PARTITION, List.of(
                new ProducedRecord(null, "small enough".getBytes(UTF_8)),
                new ProducedRecord(null, tooLarge))));

        assertEquals(ErrorCode.FRAME_TOO_LARGE, failureOf(refused));
        assertEquals(0, records(0L).records().length, "a request with one oversized record stores none of them");
        assertEquals(0L, records(0L).highWaterMark(), "and consumes no offset");
        ResponseDecoding accepted = client.call(10, new ProduceRequest(TOPIC, PARTITION,
                List.of(new ProducedRecord(null, "after the refusal".getBytes(UTF_8)))));
        assertEquals(0L, ((ProduceResponse) assertInstanceOf(ResponseDecoding.Answered.class, accepted).response())
                .baseOffset(), "the next record still takes offset 0");
    }

    @Test
    void answersARepeatCreateTopicWithTopicAlreadyExistsWhateverPartitionCountItAsksFor() throws Exception {
        ResponseDecoding sameCount = client.call(11, new CreateTopicRequest(TOPIC, ONLY_PARTITION_COUNT));
        ResponseDecoding differentCount = client.call(12, new CreateTopicRequest(TOPIC, ONLY_PARTITION_COUNT + 7));

        assertEquals(ErrorCode.TOPIC_ALREADY_EXISTS, failureOf(sameCount));
        assertEquals(ErrorCode.TOPIC_ALREADY_EXISTS, failureOf(differentCount),
                "a partition count is fixed when a topic is created, so this is not a way to change it");
    }

    @Test
    void answersACommitOfANegativeOffsetWithInvalidRequestAndOneBeyondTheEndWithOffsetOutOfRange() throws Exception {
        client.call(13, new ProduceRequest(TOPIC, PARTITION, List.of(new ProducedRecord(null, "one".getBytes(UTF_8)))));

        ResponseDecoding negative = client.call(14, new CommitOffsetRequest(GROUP, TOPIC, PARTITION, -1L));
        ResponseDecoding pastTheEnd = client.call(15, new CommitOffsetRequest(GROUP, TOPIC, PARTITION, 2L));
        ResponseDecoding atTheEnd = client.call(16, new CommitOffsetRequest(GROUP, TOPIC, PARTITION, 1L));

        assertEquals(ErrorCode.INVALID_REQUEST, failureOf(negative),
                "a committed offset is the next offset to read, and there is no such thing as a negative one");
        assertEquals(ErrorCode.OFFSET_OUT_OF_RANGE, failureOf(pastTheEnd));
        assertInstanceOf(ResponseDecoding.Answered.class, atTheEnd,
                "committing the high-water mark is how a group says it has read everything there is");
    }

    @Test
    void closesTheConnectionWithNoReplyWhenTheLengthPrefixCannotBeBelieved() throws Exception {
        try (WireClient hostile = WireClient.connectTo(broker)) {
            hostile.sendRaw(ByteBuffer.allocate(RequestFrame.LENGTH_FIELD_BYTES).putInt(Integer.MAX_VALUE).array());

            Future<Boolean> closed = readerThread.submit(hostile::isClosedWithNoReply);

            assertTrue(Await.value(closed, "the broker to close a connection it was lied to on"),
                    "a length this reader cannot believe is owed no answer, so no byte comes back");
        }
    }

    @Test
    void keepsTheConnectionUsableAfterAnsweringAnError() throws Exception {
        client.call(17, new FetchRequest("no.such.topic", PARTITION, 0L, PLENTY_OF_BYTES, ANSWER_IMMEDIATELY_MS, 0));

        ResponseDecoding afterTheError = client.call(18, new ProduceRequest(TOPIC, PARTITION,
                List.of(new ProducedRecord(null, "still talking".getBytes(UTF_8)))));

        assertInstanceOf(ResponseDecoding.Answered.class, afterTheError,
                "a refused request is the caller being wrong, not the connection being broken");
    }

    /**
     * The broker's own failures are the one thing it does not describe to a caller. Closing a
     * partition's log under a running broker is a failure nothing in the request path expects, which is
     * exactly what makes it the right way to ask what happens to one.
     */
    @Test
    void answersInternalAndKeepsTheConnectionWhenTheBrokerItselfFails() throws Exception {
        client.call(20, new ProduceRequest(TOPIC, PARTITION,
                List.of(new ProducedRecord(null, "stored".getBytes(UTF_8)))));
        broker.partition(TOPIC, PARTITION).orElseThrow().close();

        ResponseDecoding failed = client.call(21, new FetchRequest(TOPIC, PARTITION, 0L, PLENTY_OF_BYTES,
                ANSWER_IMMEDIATELY_MS, 0));
        ResponseDecoding afterTheFailure = client.call(22, new CreateTopicRequest("another", 1));

        assertEquals(ErrorCode.INTERNAL, failureOf(failed),
                "a failure the broker did not expect is a code and nothing else: the caller learns no more");
        assertInstanceOf(ResponseDecoding.Answered.class, afterTheFailure,
                "the connection thread answered and went back to reading rather than dying");
    }

    private FetchResponse records(long fetchOffset) throws IOException {
        ResponseDecoding answer = client.call(19, new FetchRequest(TOPIC, PARTITION, fetchOffset, PLENTY_OF_BYTES,
                ANSWER_IMMEDIATELY_MS, 0));
        return (FetchResponse) assertInstanceOf(ResponseDecoding.Answered.class, answer).response();
    }

    private static ErrorCode failureOf(ResponseDecoding answer) {
        return assertInstanceOf(ResponseDecoding.Failed.class, answer).errorCode();
    }
}
