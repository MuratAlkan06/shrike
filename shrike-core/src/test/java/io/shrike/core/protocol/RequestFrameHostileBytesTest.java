package io.shrike.core.protocol;

import static io.shrike.core.protocol.WireFrames.concat;
import static io.shrike.core.protocol.WireFrames.int16;
import static io.shrike.core.protocol.WireFrames.int32;
import static io.shrike.core.protocol.WireFrames.int64;
import static io.shrike.core.protocol.WireFrames.record;
import static io.shrike.core.protocol.WireFrames.request;
import static io.shrike.core.protocol.WireFrames.string;
import static io.shrike.core.protocol.WireFrames.stringWithLength;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.shrike.core.log.SafeName;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

/**
 * The body decoder against bytes no client of this build would send. Every case here is a frame whose
 * envelope parses, so the answer is always an error response and never a closed connection: the
 * caller is owed its correlation id back and the connection is owed its life.
 */
class RequestFrameHostileBytesTest {

    private static final int CORRELATION_ID = 77;

    /** The first number no api has taken, so a frame naming it is a frame this build cannot read. */
    private static final short UNTAKEN_API_KEY = 6;

    @Test
    void refusesAnApiKeyThisBuildDoesNotImplement() {
        for (short apiKey : new short[] {UNTAKEN_API_KEY, 7, -1, 999}) {
            ByteBuffer frame = request(apiKey, ApiKeys.VERSION_0, CORRELATION_ID, new byte[0]);

            RequestDecoding decoding = RequestFrame.decode(frame);

            assertRefused(decoding, ErrorCode.INVALID_REQUEST, "api key " + apiKey);
        }
    }

    @Test
    void refusesAnyApiVersionButZeroAsUnsupported() {
        for (short apiVersion : new short[] {1, 2, -1, Short.MAX_VALUE}) {
            ByteBuffer frame = request(ApiKeys.CREATE_TOPIC, apiVersion, CORRELATION_ID,
                    concat(string("orders"), int32(1)));

            RequestDecoding decoding = RequestFrame.decode(frame);

            assertRefused(decoding, ErrorCode.UNSUPPORTED_VERSION, "version " + apiVersion);
        }
    }

    @Test
    void refusesAFrameTooShortToHoldAnEnvelope() {
        ByteBuffer frame = ByteBuffer.wrap(concat(int16(ApiKeys.CREATE_TOPIC), int16(0), int16(0)));

        RequestDecoding decoding = RequestFrame.decode(frame);

        assertInstanceOf(RequestDecoding.BrokenFrame.class, decoding, decoding.toString());
    }

    @Test
    void refusesABodyThatEndsInsideAField() {
        ByteBuffer frame = request(ApiKeys.FETCH, ApiKeys.VERSION_0, CORRELATION_ID,
                concat(string("orders"), int32(0), int64(0), int32(1024)));

        RequestDecoding decoding = RequestFrame.decode(frame);

        assertRefused(decoding, ErrorCode.INVALID_REQUEST, "ends before");
    }

    @Test
    void refusesTrailingBytesAfterACompleteBody() {
        ByteBuffer frame = request(ApiKeys.CREATE_TOPIC, ApiKeys.VERSION_0, CORRELATION_ID,
                concat(string("orders"), int32(1), new byte[] {0x7f}));

        RequestDecoding decoding = RequestFrame.decode(frame);

        assertRefused(decoding, ErrorCode.INVALID_REQUEST, "left over");
    }

    @Test
    void refusesAStringLengthThatIsNegative() {
        ByteBuffer frame = request(ApiKeys.CREATE_TOPIC, ApiKeys.VERSION_0, CORRELATION_ID,
                concat(int16(-1), int32(1)));

        RequestDecoding decoding = RequestFrame.decode(frame);

        assertRefused(decoding, ErrorCode.INVALID_REQUEST, "negative length");
    }

    @Test
    void refusesAStringLengthThatRunsPastTheEndOfTheFrame() {
        ByteBuffer frame = request(ApiKeys.CREATE_TOPIC, ApiKeys.VERSION_0, CORRELATION_ID,
                concat(stringWithLength(Short.MAX_VALUE, "orders"), int32(1)));

        RequestDecoding decoding = RequestFrame.decode(frame);

        assertRefused(decoding, ErrorCode.INVALID_REQUEST, "32767 bytes");
    }

    @Test
    void refusesATopicNameOutsideTheSafeCharacterSet() {
        for (String topic : new String[] {"../etc", "orders/0", "", ".", "a".repeat(SafeName.MAX_LENGTH_CHARS + 1)}) {
            ByteBuffer frame = request(ApiKeys.PRODUCE, ApiKeys.VERSION_0, CORRELATION_ID,
                    concat(string(topic), int32(0), int32(1), record(null, "v".getBytes(UTF_8))));

            RequestDecoding decoding = RequestFrame.decode(frame);

            assertRefused(decoding, ErrorCode.INVALID_REQUEST, "topic must be");
        }
    }

    @Test
    void refusesAGroupIdOutsideTheSafeCharacterSet() {
        ByteBuffer frame = request(ApiKeys.COMMIT_OFFSET, ApiKeys.VERSION_0, CORRELATION_ID,
                concat(string("../../root"), string("orders"), int32(0), int64(0)));

        RequestDecoding decoding = RequestFrame.decode(frame);

        assertRefused(decoding, ErrorCode.INVALID_REQUEST, "groupId must be");
    }

    @Test
    void refusesARecordCountOverTheCapWithoutBuildingAListForIt() {
        for (int recordCount : new int[] {ProduceRequest.MAX_RECORD_COUNT + 1, 1 << 20, Integer.MAX_VALUE, 0, -1}) {
            ByteBuffer frame = request(ApiKeys.PRODUCE, ApiKeys.VERSION_0, CORRELATION_ID,
                    concat(string("orders"), int32(0), int32(recordCount), record(null, "v".getBytes(UTF_8))));

            RequestDecoding decoding = RequestFrame.decode(frame);

            assertRefused(decoding, ErrorCode.INVALID_REQUEST, "recordCount " + recordCount);
        }
    }

    @Test
    void refusesARecordCountThatClaimsMoreRecordsThanBytesRemain() {
        ByteBuffer frame = request(ApiKeys.PRODUCE, ApiKeys.VERSION_0, CORRELATION_ID,
                concat(string("orders"), int32(0), int32(ProduceRequest.MAX_RECORD_COUNT),
                        record(null, "v".getBytes(UTF_8))));

        RequestDecoding decoding = RequestFrame.decode(frame);

        assertRefused(decoding, ErrorCode.INVALID_REQUEST, "needs at least");
    }

    @Test
    void refusesAKeyLengthThatRunsPastTheEndOfTheFrame() {
        ByteBuffer frame = request(ApiKeys.PRODUCE, ApiKeys.VERSION_0, CORRELATION_ID,
                concat(string("orders"), int32(0), int32(1), int32(Integer.MAX_VALUE), int32(0)));

        RequestDecoding decoding = RequestFrame.decode(frame);

        assertRefused(decoding, ErrorCode.INVALID_REQUEST, "keyLen " + Integer.MAX_VALUE);
    }

    @Test
    void refusesAKeyLengthBelowTheMarkerThatMeansNoKey() {
        ByteBuffer frame = request(ApiKeys.PRODUCE, ApiKeys.VERSION_0, CORRELATION_ID,
                concat(string("orders"), int32(0), int32(1), int32(-2), int32(0)));

        RequestDecoding decoding = RequestFrame.decode(frame);

        assertRefused(decoding, ErrorCode.INVALID_REQUEST, "keyLen -2");
    }

    @Test
    void refusesAValueLengthThatIsNegativeOrRunsPastTheEndOfTheFrame() {
        for (int valueLength : new int[] {-1, Integer.MAX_VALUE, 9}) {
            ByteBuffer frame = request(ApiKeys.PRODUCE, ApiKeys.VERSION_0, CORRELATION_ID,
                    concat(string("orders"), int32(0), int32(1), int32(-1), int32(valueLength), new byte[8]));

            RequestDecoding decoding = RequestFrame.decode(frame);

            assertRefused(decoding, ErrorCode.INVALID_REQUEST, "valueLen " + valueLength);
        }
    }

    @Test
    void refusesAPartitionCountOutsideTheRangeATopicMayHave() {
        for (int partitionCount : new int[] {0, -1, CreateTopicRequest.MAX_PARTITION_COUNT + 1, Integer.MAX_VALUE}) {
            ByteBuffer frame = request(ApiKeys.CREATE_TOPIC, ApiKeys.VERSION_0, CORRELATION_ID,
                    concat(string("orders"), int32(partitionCount)));

            RequestDecoding decoding = RequestFrame.decode(frame);

            assertRefused(decoding, ErrorCode.INVALID_REQUEST, "partitionCount must be");
        }
    }

    @Test
    void refusesANegativeMaxBytesMaxWaitMsOrMinBytes() {
        for (int negativeField = 0; negativeField < 3; negativeField++) {
            ByteBuffer frame = request(ApiKeys.FETCH, ApiKeys.VERSION_0, CORRELATION_ID,
                    concat(string("orders"), int32(0), int64(0),
                            int32(negativeField == 0 ? -1 : 1024),
                            int32(negativeField == 1 ? -1 : 500),
                            int32(negativeField == 2 ? -1 : 1)));

            RequestDecoding decoding = RequestFrame.decode(frame);

            assertRefused(decoding, ErrorCode.INVALID_REQUEST, "must not be negative");
        }
    }

    @Test
    void refusesATopicCountOutsideTheRangeADescribeMayName() {
        for (int topicCount : new int[] {-1, DescribeTopicsRequest.MAX_TOPIC_COUNT + 1, Integer.MAX_VALUE}) {
            ByteBuffer frame = request(ApiKeys.DESCRIBE_TOPICS, ApiKeys.VERSION_0, CORRELATION_ID,
                    concat(int32(topicCount), string("orders")));

            RequestDecoding decoding = RequestFrame.decode(frame);

            assertRefused(decoding, ErrorCode.INVALID_REQUEST, "topicCount " + topicCount);
        }
    }

    @Test
    void refusesATopicCountThatClaimsMoreNamesThanBytesRemain() {
        ByteBuffer frame = request(ApiKeys.DESCRIBE_TOPICS, ApiKeys.VERSION_0, CORRELATION_ID,
                concat(int32(DescribeTopicsRequest.MAX_TOPIC_COUNT), string("orders")));

        RequestDecoding decoding = RequestFrame.decode(frame);

        assertRefused(decoding, ErrorCode.INVALID_REQUEST, "needs at least");
    }

    @Test
    void refusesADescribedTopicNameOutsideTheSafeCharacterSet() {
        ByteBuffer frame = request(ApiKeys.DESCRIBE_TOPICS, ApiKeys.VERSION_0, CORRELATION_ID,
                concat(int32(2), string("orders"), string("../etc")));

        RequestDecoding decoding = RequestFrame.decode(frame);

        assertRefused(decoding, ErrorCode.INVALID_REQUEST, "topic must be");
    }

    @Test
    void refusesADescribedTopicNameWhoseLengthIsNegativeOrRunsPastTheEndOfTheFrame() {
        ByteBuffer negative = request(ApiKeys.DESCRIBE_TOPICS, ApiKeys.VERSION_0, CORRELATION_ID,
                concat(int32(1), int16(-1)));
        ByteBuffer pastTheEnd = request(ApiKeys.DESCRIBE_TOPICS, ApiKeys.VERSION_0, CORRELATION_ID,
                concat(int32(1), stringWithLength(Short.MAX_VALUE, "orders")));

        assertRefused(RequestFrame.decode(negative), ErrorCode.INVALID_REQUEST, "negative length");
        assertRefused(RequestFrame.decode(pastTheEnd), ErrorCode.INVALID_REQUEST, "32767 bytes");
    }

    @Test
    void refusesADescribeWhoseBodyEndsInsideAField() {
        ByteBuffer halfACount = request(ApiKeys.DESCRIBE_TOPICS, ApiKeys.VERSION_0, CORRELATION_ID, int16(0));
        ByteBuffer halfALength = request(ApiKeys.DESCRIBE_GROUP, ApiKeys.VERSION_0, CORRELATION_ID,
                new byte[] {0x00});

        assertRefused(RequestFrame.decode(halfACount), ErrorCode.INVALID_REQUEST, "ends before topicCount");
        assertRefused(RequestFrame.decode(halfALength), ErrorCode.INVALID_REQUEST, "ends before groupId's length");
    }

    @Test
    void refusesTrailingBytesAfterACompleteDescribeBody() {
        ByteBuffer afterTopics = request(ApiKeys.DESCRIBE_TOPICS, ApiKeys.VERSION_0, CORRELATION_ID,
                concat(int32(1), string("orders"), new byte[] {0x7f}));
        ByteBuffer afterAGroup = request(ApiKeys.DESCRIBE_GROUP, ApiKeys.VERSION_0, CORRELATION_ID,
                concat(string("readers"), new byte[] {0x7f}));

        assertRefused(RequestFrame.decode(afterTopics), ErrorCode.INVALID_REQUEST, "left over");
        assertRefused(RequestFrame.decode(afterAGroup), ErrorCode.INVALID_REQUEST, "left over");
    }

    @Test
    void refusesAGroupIdToDescribeOutsideTheSafeCharacterSet() {
        ByteBuffer frame = request(ApiKeys.DESCRIBE_GROUP, ApiKeys.VERSION_0, CORRELATION_ID,
                string("../../root"));

        RequestDecoding decoding = RequestFrame.decode(frame);

        assertRefused(decoding, ErrorCode.INVALID_REQUEST, "groupId must be");
    }

    @Test
    void refusesAGroupIdToDescribeWhoseLengthIsNegativeOrRunsPastTheEndOfTheFrame() {
        ByteBuffer negative = request(ApiKeys.DESCRIBE_GROUP, ApiKeys.VERSION_0, CORRELATION_ID, int16(-1));
        ByteBuffer pastTheEnd = request(ApiKeys.DESCRIBE_GROUP, ApiKeys.VERSION_0, CORRELATION_ID,
                stringWithLength(Short.MAX_VALUE, "readers"));

        assertRefused(RequestFrame.decode(negative), ErrorCode.INVALID_REQUEST, "negative length");
        assertRefused(RequestFrame.decode(pastTheEnd), ErrorCode.INVALID_REQUEST, "32767 bytes");
    }

    @Test
    void readsTheSameBytesFromABufferThatWasHandedOverAsLittleEndian() {
        ByteBuffer frame = request(ApiKeys.CREATE_TOPIC, ApiKeys.VERSION_0, CORRELATION_ID,
                concat(string("orders"), int32(4))).order(ByteOrder.LITTLE_ENDIAN);

        RequestDecoding decoding = RequestFrame.decode(frame);

        RequestDecoding.Accepted accepted = assertInstanceOf(RequestDecoding.Accepted.class, decoding,
                decoding.toString());
        assertEquals(new CreateTopicRequest("orders", 4), accepted.request());
    }

    private static void assertRefused(RequestDecoding decoding, ErrorCode errorCode, String reasonFragment) {
        RequestDecoding.Refused refused = assertInstanceOf(RequestDecoding.Refused.class, decoding,
                decoding.toString());
        assertEquals(CORRELATION_ID, refused.correlationId(), "a refused request is still owed its correlation id");
        assertEquals(errorCode, refused.errorCode());
        assertTrue(refused.reason().contains(reasonFragment),
                "expected a reason naming \"" + reasonFragment + "\" but was: " + refused.reason());
    }
}
