package io.shrike.core.protocol;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.shrike.core.log.ProducedRecord;
import io.shrike.core.log.RecordFrame;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The request frame: the one place that knows the byte layout of a request.
 *
 * <p>Every field is big-endian:
 *
 * <pre>
 * length:int32 | apiKey:int16 | apiVersion:int16 | correlationId:int32 | body
 * </pre>
 *
 * <ul>
 *   <li>{@code length} counts every byte after itself, so a frame occupies {@code 4 + length} bytes.
 *   <li>{@code correlationId} is the client's number and means nothing to the broker but "put this in
 *       the response", which is what lets a client keep more than one request in flight.
 *   <li>A string is {@code len:int16 | UTF-8 bytes} with a length that is never negative.
 *   <li>A list is {@code count:int32} and then that many elements, and the count is weighed against
 *       both its own cap and the fewest bytes that many elements could occupy before anything is sized
 *       to it.
 *   <li>A produced record is {@code keyLen:int32 | key | valueLen:int32 | value}, where a
 *       {@code keyLen} of {@value io.shrike.core.log.RecordFrame#NULL_KEY_LENGTH} means no key at
 *       all — the same convention the record on disk uses, so the two never drift.
 * </ul>
 *
 * <p>Decoding treats every byte as hostile. Nothing is allocated for a declared length until that
 * length has been checked against the bytes actually in hand and against the cap the protocol puts on
 * it, and a body that breaks a rule produces a refusal rather than an exception the caller has to
 * catch.
 */
public final class RequestFrame {

    /** The width of the length field that precedes every frame. */
    public static final int LENGTH_FIELD_BYTES = Integer.BYTES;

    /** The smallest legal {@code length}: an api key, an api version, a correlation id, no body. */
    public static final int MINIMUM_LENGTH_BYTES = Short.BYTES + Short.BYTES + Integer.BYTES;

    /** A produced record on the wire is at least its two length fields. */
    private static final int MINIMUM_RECORD_BYTES = Integer.BYTES + Integer.BYTES;

    /** A named topic on the wire is at least the length field of its name. */
    private static final int MINIMUM_TOPIC_BYTES = Short.BYTES;

    private RequestFrame() {
    }

    /**
     * Lays out one request, length field included, into a buffer positioned at 0 and ready to be
     * written.
     *
     * @param correlationId the number the response will echo back
     * @param request       the request to encode
     * @return the encoded frame
     * @throws IllegalArgumentException if the request is too large for a frame to describe
     */
    public static ByteBuffer encode(int correlationId, Request request) {
        Objects.requireNonNull(request, "request");

        long frameBytes = (long) LENGTH_FIELD_BYTES + MINIMUM_LENGTH_BYTES + bodyBytes(request);
        if (frameBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("a request of " + frameBytes
                    + " bytes cannot be framed: the length field is an int32");
        }

        ByteBuffer frame = ByteBuffer.allocate((int) frameBytes);
        frame.putInt((int) frameBytes - LENGTH_FIELD_BYTES);
        frame.putShort(request.apiKey());
        frame.putShort(ApiKeys.VERSION_0);
        frame.putInt(correlationId);
        switch (request) {
            case ProduceRequest produce -> encodeProduce(frame, produce);
            case FetchRequest fetch -> encodeFetch(frame, fetch);
            case CommitOffsetRequest commit -> encodeCommitOffset(frame, commit);
            case CreateTopicRequest create -> encodeCreateTopic(frame, create);
            case DescribeTopicsRequest describeTopics -> encodeDescribeTopics(frame, describeTopics);
            case DescribeGroupRequest describeGroup -> putString(frame, describeGroup.groupId());
        }
        return frame.flip();
    }

    /**
     * Reads one request out of the bytes that follow the length field. The caller's buffer is neither
     * consumed nor trusted to be big-endian.
     *
     * @param frame every byte after the length field, positioned at its first byte
     * @return what the caller is owed: a request, a refusal to answer with, or a broken frame to close
     *         the connection over
     */
    public static RequestDecoding decode(ByteBuffer frame) {
        Objects.requireNonNull(frame, "frame");

        ByteBuffer body = frame.duplicate().order(ByteOrder.BIG_ENDIAN);
        if (body.remaining() < MINIMUM_LENGTH_BYTES) {
            return new RequestDecoding.BrokenFrame("a request frame of " + body.remaining()
                    + " bytes cannot hold the " + MINIMUM_LENGTH_BYTES + " an envelope needs");
        }

        short apiKey = body.getShort();
        short apiVersion = body.getShort();
        int correlationId = body.getInt();
        if (!ApiKeys.isImplemented(apiKey)) {
            return new RequestDecoding.Refused(correlationId, ErrorCode.INVALID_REQUEST,
                    "api key " + apiKey + " is not one this build implements");
        }
        if (!ApiKeys.isSupportedVersion(apiKey, apiVersion)) {
            return new RequestDecoding.Refused(correlationId, ErrorCode.UNSUPPORTED_VERSION,
                    "api key " + apiKey + " speaks version " + ApiKeys.VERSION_0 + ", not version " + apiVersion);
        }

        try {
            Request request = decodeBody(apiKey, body);
            if (body.hasRemaining()) {
                throw new WireFormatException(body.remaining() + " bytes are left over after the body of api key "
                        + apiKey);
            }
            return new RequestDecoding.Accepted(correlationId, request);
        } catch (WireFormatException e) {
            return new RequestDecoding.Refused(correlationId, ErrorCode.INVALID_REQUEST, reasonOf(e));
        } catch (IllegalArgumentException e) {
            // The request records hold the rules about what a legal request says, so a value that
            // breaks one arrives here rather than being checked a second time in this class.
            return new RequestDecoding.Refused(correlationId, ErrorCode.INVALID_REQUEST, reasonOf(e));
        } catch (BufferUnderflowException e) {
            // Every read below is bounds-checked before it happens, so this cannot fire; it is here so
            // that a mistake in one of those checks costs a refused request rather than an exception
            // thrown at the connection loop.
            return new RequestDecoding.Refused(correlationId, ErrorCode.INVALID_REQUEST,
                    "the body of api key " + apiKey + " ends inside a field");
        }
    }

    /**
     * @return why a request was refused, in words: an exception thrown without a message would leave a
     *         refusal with nothing to say, so its type is the fallback
     */
    private static String reasonOf(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static long bodyBytes(Request request) {
        return switch (request) {
            case ProduceRequest produce -> stringBytes(produce.topic()) + Integer.BYTES + Integer.BYTES
                    + recordsBytes(produce.records());
            case FetchRequest fetch -> stringBytes(fetch.topic()) + Integer.BYTES + Long.BYTES + Integer.BYTES
                    + Integer.BYTES + Integer.BYTES;
            case CommitOffsetRequest commit -> stringBytes(commit.groupId()) + stringBytes(commit.topic())
                    + Integer.BYTES + Long.BYTES;
            case CreateTopicRequest create -> stringBytes(create.name()) + Integer.BYTES;
            case DescribeTopicsRequest describeTopics -> Integer.BYTES + namesBytes(describeTopics.topics());
            case DescribeGroupRequest describeGroup -> stringBytes(describeGroup.groupId());
        };
    }

    private static long namesBytes(List<String> names) {
        long totalBytes = 0L;
        for (String name : names) {
            totalBytes += stringBytes(name);
        }
        return totalBytes;
    }

    private static long recordsBytes(List<ProducedRecord> records) {
        long totalBytes = 0L;
        for (ProducedRecord record : records) {
            totalBytes += MINIMUM_RECORD_BYTES + (record.key() == null ? 0L : record.key().length)
                    + record.value().length;
        }
        return totalBytes;
    }

    private static long stringBytes(String value) {
        return Short.BYTES + (long) value.getBytes(UTF_8).length;
    }

    private static void encodeProduce(ByteBuffer frame, ProduceRequest request) {
        putString(frame, request.topic());
        frame.putInt(request.partition());
        frame.putInt(request.records().size());
        for (ProducedRecord record : request.records()) {
            byte[] key = record.key();
            frame.putInt(key == null ? RecordFrame.NULL_KEY_LENGTH : key.length);
            if (key != null) {
                frame.put(key);
            }
            frame.putInt(record.value().length);
            frame.put(record.value());
        }
    }

    private static void encodeFetch(ByteBuffer frame, FetchRequest request) {
        putString(frame, request.topic());
        frame.putInt(request.partition());
        frame.putLong(request.fetchOffset());
        frame.putInt(request.maxBytes());
        frame.putInt(request.maxWaitMs());
        frame.putInt(request.minBytes());
    }

    private static void encodeCommitOffset(ByteBuffer frame, CommitOffsetRequest request) {
        putString(frame, request.groupId());
        putString(frame, request.topic());
        frame.putInt(request.partition());
        frame.putLong(request.offset());
    }

    private static void encodeCreateTopic(ByteBuffer frame, CreateTopicRequest request) {
        putString(frame, request.name());
        frame.putInt(request.partitionCount());
    }

    private static void encodeDescribeTopics(ByteBuffer frame, DescribeTopicsRequest request) {
        frame.putInt(request.topics().size());
        for (String topic : request.topics()) {
            putString(frame, topic);
        }
    }

    private static void putString(ByteBuffer frame, String value) {
        byte[] utf8 = value.getBytes(UTF_8);
        frame.putShort((short) utf8.length);
        frame.put(utf8);
    }

    private static Request decodeBody(short apiKey, ByteBuffer body) {
        return switch (apiKey) {
            case ApiKeys.PRODUCE -> decodeProduce(body);
            case ApiKeys.FETCH -> decodeFetch(body);
            case ApiKeys.COMMIT_OFFSET -> decodeCommitOffset(body);
            case ApiKeys.CREATE_TOPIC -> decodeCreateTopic(body);
            case ApiKeys.DESCRIBE_TOPICS -> decodeDescribeTopics(body);
            case ApiKeys.DESCRIBE_GROUP -> new DescribeGroupRequest(decodeString(body, "groupId"));
            default -> throw new WireFormatException("api key " + apiKey + " has no body this build can read");
        };
    }

    private static ProduceRequest decodeProduce(ByteBuffer body) {
        String topic = decodeString(body, "topic");
        int partition = decodeInt(body, "partition");
        int recordCount = decodeInt(body, "recordCount");
        if (recordCount < ProduceRequest.MIN_RECORD_COUNT || recordCount > ProduceRequest.MAX_RECORD_COUNT) {
            throw new WireFormatException("recordCount " + recordCount + " is outside ["
                    + ProduceRequest.MIN_RECORD_COUNT + ", " + ProduceRequest.MAX_RECORD_COUNT + "]");
        }

        // Nothing is sized to the count until the bytes to back it are known to be there: the smallest
        // a record can be is its two length fields, so a count claiming more records than that many
        // pairs of them is refused before a list is allocated for it.
        long smallestRecordsBytes = (long) recordCount * MINIMUM_RECORD_BYTES;
        if (smallestRecordsBytes > body.remaining()) {
            throw new WireFormatException("recordCount " + recordCount + " needs at least " + smallestRecordsBytes
                    + " bytes of records, but " + body.remaining() + " are left in the frame");
        }

        List<ProducedRecord> records = new ArrayList<>(recordCount);
        for (int index = 0; index < recordCount; index++) {
            records.add(decodeProducedRecord(body, index));
        }
        return new ProduceRequest(topic, partition, records);
    }

    private static ProducedRecord decodeProducedRecord(ByteBuffer body, int index) {
        int keyLength = decodeInt(body, "keyLen of record " + index);
        if (keyLength < RecordFrame.NULL_KEY_LENGTH || keyLength > body.remaining() - Integer.BYTES) {
            throw new WireFormatException("keyLen " + keyLength + " of record " + index + " does not fit the "
                    + body.remaining() + " bytes left in the frame");
        }
        byte[] key = null;
        if (keyLength != RecordFrame.NULL_KEY_LENGTH) {
            key = new byte[keyLength];
            body.get(key);
        }

        int valueLength = decodeInt(body, "valueLen of record " + index);
        if (valueLength < 0 || valueLength > body.remaining()) {
            throw new WireFormatException("valueLen " + valueLength + " of record " + index + " does not fit the "
                    + body.remaining() + " bytes left in the frame");
        }
        byte[] value = new byte[valueLength];
        body.get(value);

        return new ProducedRecord(key, value);
    }

    private static FetchRequest decodeFetch(ByteBuffer body) {
        String topic = decodeString(body, "topic");
        int partition = decodeInt(body, "partition");
        long fetchOffset = decodeLong(body, "fetchOffset");
        int maxBytes = decodeInt(body, "maxBytes");
        int maxWaitMs = decodeInt(body, "maxWaitMs");
        int minBytes = decodeInt(body, "minBytes");

        return new FetchRequest(topic, partition, fetchOffset, maxBytes, maxWaitMs, minBytes);
    }

    private static CommitOffsetRequest decodeCommitOffset(ByteBuffer body) {
        String groupId = decodeString(body, "groupId");
        String topic = decodeString(body, "topic");
        int partition = decodeInt(body, "partition");
        long offset = decodeLong(body, "offset");

        return new CommitOffsetRequest(groupId, topic, partition, offset);
    }

    private static CreateTopicRequest decodeCreateTopic(ByteBuffer body) {
        String name = decodeString(body, "name");
        int partitionCount = decodeInt(body, "partitionCount");

        return new CreateTopicRequest(name, partitionCount);
    }

    private static DescribeTopicsRequest decodeDescribeTopics(ByteBuffer body) {
        int topicCount = decodeInt(body, "topicCount");
        if (topicCount < 0 || topicCount > DescribeTopicsRequest.MAX_TOPIC_COUNT) {
            throw new WireFormatException("topicCount " + topicCount + " is outside [0, "
                    + DescribeTopicsRequest.MAX_TOPIC_COUNT + "]");
        }

        // The same rule the produce record count follows: nothing is sized to a count until the bytes
        // to back it are known to be there. The smallest a named topic can be is the length field of
        // its name, so a count claiming more names than that many length fields is refused before a
        // list is allocated for it.
        long smallestTopicsBytes = (long) topicCount * MINIMUM_TOPIC_BYTES;
        if (smallestTopicsBytes > body.remaining()) {
            throw new WireFormatException("topicCount " + topicCount + " needs at least " + smallestTopicsBytes
                    + " bytes of names, but " + body.remaining() + " are left in the frame");
        }

        List<String> topics = new ArrayList<>(topicCount);
        for (int index = 0; index < topicCount; index++) {
            topics.add(decodeString(body, "topic " + index));
        }
        return new DescribeTopicsRequest(topics);
    }

    private static String decodeString(ByteBuffer body, String field) {
        requireBytes(body, Short.BYTES, field + "'s length");
        short lengthBytes = body.getShort();
        if (lengthBytes < 0) {
            throw new WireFormatException(field + " declares a negative length of " + lengthBytes);
        }
        if (lengthBytes > body.remaining()) {
            throw new WireFormatException(field + " declares " + lengthBytes + " bytes, but " + body.remaining()
                    + " are left in the frame");
        }

        byte[] utf8 = new byte[lengthBytes];
        body.get(utf8);
        return new String(utf8, UTF_8);
    }

    private static int decodeInt(ByteBuffer body, String field) {
        requireBytes(body, Integer.BYTES, field);
        return body.getInt();
    }

    private static long decodeLong(ByteBuffer body, String field) {
        requireBytes(body, Long.BYTES, field);
        return body.getLong();
    }

    private static void requireBytes(ByteBuffer body, int fieldBytes, String field) {
        if (body.remaining() < fieldBytes) {
            throw new WireFormatException("the frame ends before " + field + ": " + fieldBytes + " bytes needed, "
                    + body.remaining() + " left");
        }
    }
}
