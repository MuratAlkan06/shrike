package io.shrike.core.protocol;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The response frame: the one place that knows the byte layout of a response.
 *
 * <p>Every field is big-endian:
 *
 * <pre>
 * length:int32 | correlationId:int32 | errorCode:int16 | body
 * </pre>
 *
 * <ul>
 *   <li>{@code length} counts every byte after itself, so a frame occupies {@code 4 + length} bytes.
 *   <li>{@code correlationId} is the number the request carried, echoed back untouched.
 *   <li>{@code errorCode} of {@link ErrorCode#NONE} means the body that follows is the answer.
 *       Anything else means the code is the answer, and the body is empty — with one exception,
 *       {@link ErrorCode#OFFSET_OUT_OF_RANGE}, whose body is the int64 offset that partition now
 *       starts at. Every other code still carries an empty body and a reader still refuses one that
 *       does not.
 *   <li>A string is {@code len:int16 | UTF-8 bytes} with a length that is never negative, which is the
 *       same string a request carries.
 *   <li>A list is {@code count:int32} and then that many elements, and the count is weighed against the
 *       fewest bytes that many elements could occupy before anything is sized to it.
 *   <li>The envelope does not repeat the api key: a client knows what it asked for, and the
 *       correlation id says which question this answers.
 * </ul>
 *
 * <p>Decoding treats every byte as hostile, exactly as {@link RequestFrame} does. The broker is not a
 * privileged writer: four bytes claiming a list of two billion entries are as cheap to send from a
 * broker's port as from a client's, so nothing here is allocated for a declared count or length until
 * that number has been weighed against the bytes actually in hand.
 */
public final class ResponseFrame {

    /** The width of the length field that precedes every frame. */
    public static final int LENGTH_FIELD_BYTES = Integer.BYTES;

    /** The smallest legal {@code length}: a correlation id and an error code, which is an error. */
    public static final int MINIMUM_LENGTH_BYTES = Integer.BYTES + Short.BYTES;

    /**
     * The body of an {@link ErrorCode#OFFSET_OUT_OF_RANGE} response: one int64, the offset the
     * partition can still be read from. It is the only error body this protocol has.
     */
    public static final int OFFSET_OUT_OF_RANGE_BODY_BYTES = Long.BYTES;

    /**
     * What a fetch response carries before its records: the high-water mark and the size of the
     * records block, in that order.
     */
    public static final int FETCH_RECORDS_PREFIX_BYTES = Long.BYTES + Integer.BYTES;

    /** {@code partition | logStartOffset | highWaterMark | segmentCount | bytes}, every field fixed. */
    private static final int PARTITION_DESCRIPTION_BYTES =
            Integer.BYTES + Long.BYTES + Long.BYTES + Integer.BYTES + Long.BYTES;

    /** A described topic is at least its name's length field and its partition count. */
    private static final int MINIMUM_TOPIC_DESCRIPTION_BYTES = Short.BYTES + Integer.BYTES;

    /** {@code partition | committedOffset} — what one group offset holds beyond its topic name. */
    private static final int GROUP_OFFSET_BYTES_WITHOUT_TOPIC = Integer.BYTES + Long.BYTES;

    /** A group offset on the wire is at least its topic's length field and those two fields. */
    private static final int MINIMUM_GROUP_OFFSET_BYTES = Short.BYTES + GROUP_OFFSET_BYTES_WITHOUT_TOPIC;

    private ResponseFrame() {
    }

    /**
     * Lays out one successful response, length field included, into a buffer positioned at 0 and ready
     * to be written.
     *
     * @param correlationId the number the request carried
     * @param response      the body of the answer
     * @return the encoded frame
     * @throws IllegalArgumentException if the response is too large for a frame to describe
     */
    public static ByteBuffer encode(int correlationId, Response response) {
        Objects.requireNonNull(response, "response");

        long frameBytes = (long) LENGTH_FIELD_BYTES + MINIMUM_LENGTH_BYTES + bodyBytes(response);
        if (frameBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("a response of " + frameBytes
                    + " bytes cannot be framed: the length field is an int32");
        }

        ByteBuffer frame = allocateFrame((int) frameBytes, correlationId, ErrorCode.NONE);
        switch (response) {
            case ProduceResponse produce -> frame.putLong(produce.baseOffset());
            case FetchResponse fetch -> {
                frame.putLong(fetch.highWaterMark());
                frame.putInt(fetch.records().length);
                frame.put(fetch.records());
            }
            case CommitOffsetResponse commitOffset -> {
                // An empty body: a stored commit has nothing to report back.
            }
            case CreateTopicResponse createTopic -> {
                // An empty body: the request already named the partitions.
            }
            case DescribeTopicsResponse describeTopics -> encodeDescribeTopics(frame, describeTopics);
            case DescribeGroupResponse describeGroup -> encodeDescribeGroup(frame, describeGroup);
        }
        return frame.flip();
    }

    /**
     * Lays out everything a fetch response carries <em>before</em> its records: the length field, the
     * correlation id, {@link ErrorCode#NONE}, the high-water mark, and how many bytes of record frames
     * follow. The frames themselves are not here — this is the header a broker writes when it is about
     * to send them straight out of the segment file — and the length field already counts them,
     * because a length is a promise about the whole frame and not about the part of it that is in
     * memory.
     *
     * <p>What this produces is the first {@value #LENGTH_FIELD_BYTES} plus {@value #MINIMUM_LENGTH_BYTES}
     * plus {@value #FETCH_RECORDS_PREFIX_BYTES} bytes of what {@link #encode} produces for the same
     * answer, byte for byte. That is the point of it: which of the two ways a fetch is served must not
     * be something a client can tell from the bytes.
     *
     * @param correlationId    the number the request carried
     * @param highWaterMark    the offset the partition will append next
     * @param recordsSizeBytes how many bytes of record frames will follow this header
     * @return the encoded header
     * @throws IllegalArgumentException if the size is negative, or if the whole response would be too
     *                                  large for a frame to describe
     */
    public static ByteBuffer encodeFetchHeader(int correlationId, long highWaterMark, int recordsSizeBytes) {
        if (recordsSizeBytes < 0) {
            throw new IllegalArgumentException("recordsSizeBytes must not be negative, but was " + recordsSizeBytes);
        }

        long frameBytes = (long) LENGTH_FIELD_BYTES + MINIMUM_LENGTH_BYTES + FETCH_RECORDS_PREFIX_BYTES
                + recordsSizeBytes;
        if (frameBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("a response of " + frameBytes
                    + " bytes cannot be framed: the length field is an int32");
        }

        ByteBuffer header = ByteBuffer.allocate(LENGTH_FIELD_BYTES + MINIMUM_LENGTH_BYTES
                + FETCH_RECORDS_PREFIX_BYTES);
        putEnvelope(header, (int) (frameBytes - LENGTH_FIELD_BYTES), correlationId, ErrorCode.NONE);
        return header.putLong(highWaterMark).putInt(recordsSizeBytes).flip();
    }

    /**
     * Lays out one error response: a correlation id, a code, and nothing else. The body is empty on
     * purpose, so a caller probing the broker with malformed requests learns only which of nine
     * numbers it earned.
     *
     * @param correlationId the number the request carried
     * @param errorCode     what went wrong, which cannot be {@link ErrorCode#NONE} and cannot be
     *                      {@link ErrorCode#OFFSET_OUT_OF_RANGE}
     * @return the encoded frame
     * @throws IllegalArgumentException if the code is {@link ErrorCode#NONE}, which would be an error
     *                                  response saying nothing went wrong, or
     *                                  {@link ErrorCode#OFFSET_OUT_OF_RANGE}, which is the one code
     *                                  that owes the caller a body
     */
    public static ByteBuffer encodeError(int correlationId, ErrorCode errorCode) {
        Objects.requireNonNull(errorCode, "errorCode");
        if (errorCode == ErrorCode.NONE) {
            throw new IllegalArgumentException("an error response cannot carry " + ErrorCode.NONE
                    + ": use encode with a body instead");
        }
        // Refused here rather than trusted to be got right at every call site: a code 2 with no body
        // is a frame every reader of this protocol rejects, so it must not be possible to write one.
        if (errorCode == ErrorCode.OFFSET_OUT_OF_RANGE) {
            throw new IllegalArgumentException(ErrorCode.OFFSET_OUT_OF_RANGE + " carries the log start offset:"
                    + " use encodeOffsetOutOfRange instead");
        }

        return allocateFrame(LENGTH_FIELD_BYTES + MINIMUM_LENGTH_BYTES, correlationId, errorCode).flip();
    }

    /**
     * Lays out the one error response that carries a body: {@link ErrorCode#OFFSET_OUT_OF_RANGE} and
     * the offset the partition can still be read from.
     *
     * <p>It is a deliberate exception to "an error is a code and nothing else", and it buys exactly
     * one thing: a consumer whose committed offset has fallen behind retention learns where to resume
     * in the same answer that refuses it, instead of guessing between the start and the end of a
     * partition it cannot see. The number is not sensitive — it is the oldest offset the broker will
     * serve to anyone who asks for it — and no other code gains a body from this.
     *
     * @param correlationId  the number the request carried
     * @param logStartOffset the lowest offset that partition can still serve
     * @return the encoded frame
     */
    public static ByteBuffer encodeOffsetOutOfRange(int correlationId, long logStartOffset) {
        ByteBuffer frame = allocateFrame(LENGTH_FIELD_BYTES + MINIMUM_LENGTH_BYTES + OFFSET_OUT_OF_RANGE_BODY_BYTES,
                correlationId, ErrorCode.OFFSET_OUT_OF_RANGE);
        return frame.putLong(logStartOffset).flip();
    }

    /**
     * Reads one response out of the bytes that follow the length field. The caller's buffer is neither
     * consumed nor trusted to be big-endian.
     *
     * @param apiKey the api key of the request this answers, which the envelope does not carry
     * @param frame  every byte after the length field, positioned at its first byte
     * @return the answer, the failure it carries instead, or a verdict that these bytes are not a
     *         response at all
     * @throws IllegalArgumentException if the api key is not one this build implements, which is the
     *                                  caller asking about a request it could never have sent
     */
    public static ResponseDecoding decode(short apiKey, ByteBuffer frame) {
        Objects.requireNonNull(frame, "frame");
        if (!ApiKeys.isImplemented(apiKey)) {
            throw new IllegalArgumentException("api key " + apiKey + " is not one this build implements");
        }

        ByteBuffer body = frame.duplicate().order(ByteOrder.BIG_ENDIAN);
        if (body.remaining() < MINIMUM_LENGTH_BYTES) {
            return new ResponseDecoding.BrokenFrame("a response frame of " + body.remaining()
                    + " bytes cannot hold the " + MINIMUM_LENGTH_BYTES + " an envelope needs");
        }

        int correlationId = body.getInt();
        short errorCodeNumber = body.getShort();
        Optional<ErrorCode> errorCode = ErrorCode.fromCode(errorCodeNumber);
        if (errorCode.isEmpty()) {
            return new ResponseDecoding.BrokenFrame("error code " + errorCodeNumber + " is not one this build knows");
        }
        if (errorCode.get() != ErrorCode.NONE) {
            return decodeFailure(correlationId, errorCode.get(), body);
        }

        try {
            Response response = decodeBody(apiKey, body);
            if (body.hasRemaining()) {
                throw new WireFormatException(body.remaining() + " bytes are left over after the body of api key "
                        + apiKey);
            }
            return new ResponseDecoding.Answered(correlationId, response);
        } catch (WireFormatException e) {
            return new ResponseDecoding.BrokenFrame(reasonOf(e));
        } catch (IllegalArgumentException e) {
            // The response records hold the rules about what a legal answer says, so a value that
            // breaks one arrives here rather than being checked a second time in this class.
            return new ResponseDecoding.BrokenFrame(reasonOf(e));
        } catch (BufferUnderflowException e) {
            // Every read below is bounds-checked before it happens; this is the backstop that turns a
            // mistake in one of those checks into a verdict rather than an escaped exception.
            return new ResponseDecoding.BrokenFrame("the body of api key " + apiKey + " ends inside a field");
        }
    }

    /**
     * Reads what an error response carries after its code, which is nothing at all except for
     * {@link ErrorCode#OFFSET_OUT_OF_RANGE}. Both halves are exact: bytes behind any other code are a
     * frame this build refuses, and a code 2 without its eight are too, so neither a body that should
     * not be there nor one that should is silently accepted.
     *
     * @param correlationId the number this answer echoes
     * @param errorCode     the code it carries, never {@link ErrorCode#NONE}
     * @param body          whatever follows the code
     * @return the failure, or a verdict that these bytes are not one
     */
    private static ResponseDecoding decodeFailure(int correlationId, ErrorCode errorCode, ByteBuffer body) {
        if (errorCode != ErrorCode.OFFSET_OUT_OF_RANGE) {
            if (body.hasRemaining()) {
                return new ResponseDecoding.BrokenFrame("an error response carries an empty body, but "
                        + body.remaining() + " bytes follow the code " + errorCode);
            }
            return new ResponseDecoding.Failed(correlationId, errorCode, OptionalLong.empty());
        }

        if (body.remaining() != OFFSET_OUT_OF_RANGE_BODY_BYTES) {
            return new ResponseDecoding.BrokenFrame(errorCode + " carries the log start offset in "
                    + OFFSET_OUT_OF_RANGE_BODY_BYTES + " bytes, but " + body.remaining()
                    + " bytes follow the code");
        }
        return new ResponseDecoding.Failed(correlationId, errorCode, OptionalLong.of(body.getLong()));
    }

    /**
     * @return why a response could not be read, in words: an exception thrown without a message would
     *         leave a verdict with nothing to say, so its type is the fallback
     */
    private static String reasonOf(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static long bodyBytes(Response response) {
        return switch (response) {
            case ProduceResponse produce -> Long.BYTES;
            case FetchResponse fetch -> Long.BYTES + Integer.BYTES + (long) fetch.records().length;
            case CommitOffsetResponse commitOffset -> 0L;
            case CreateTopicResponse createTopic -> 0L;
            case DescribeTopicsResponse describeTopics -> Integer.BYTES + describedTopicsBytes(describeTopics.topics());
            case DescribeGroupResponse describeGroup -> Integer.BYTES
                    + (long) describeGroup.offsets().size() * GROUP_OFFSET_BYTES_WITHOUT_TOPIC
                    + topicNamesBytes(describeGroup.offsets());
        };
    }

    private static long describedTopicsBytes(List<TopicDescription> topics) {
        long totalBytes = 0L;
        for (TopicDescription topic : topics) {
            totalBytes += stringBytes(topic.name()) + Integer.BYTES
                    + (long) topic.partitionCount() * PARTITION_DESCRIPTION_BYTES;
        }
        return totalBytes;
    }

    private static long topicNamesBytes(List<GroupOffset> offsets) {
        long totalBytes = 0L;
        for (GroupOffset offset : offsets) {
            totalBytes += stringBytes(offset.topic());
        }
        return totalBytes;
    }

    private static long stringBytes(String value) {
        return Short.BYTES + (long) value.getBytes(UTF_8).length;
    }

    private static void encodeDescribeTopics(ByteBuffer frame, DescribeTopicsResponse response) {
        frame.putInt(response.topics().size());
        for (TopicDescription topic : response.topics()) {
            putString(frame, topic.name());
            frame.putInt(topic.partitionCount());
            for (PartitionDescription partition : topic.partitions()) {
                frame.putInt(partition.partition());
                frame.putLong(partition.logStartOffset());
                frame.putLong(partition.highWaterMark());
                frame.putInt(partition.segmentCount());
                frame.putLong(partition.bytes());
            }
        }
    }

    private static void encodeDescribeGroup(ByteBuffer frame, DescribeGroupResponse response) {
        frame.putInt(response.offsets().size());
        for (GroupOffset offset : response.offsets()) {
            putString(frame, offset.topic());
            frame.putInt(offset.partition());
            frame.putLong(offset.committedOffset());
        }
    }

    private static void putString(ByteBuffer frame, String value) {
        byte[] utf8 = value.getBytes(UTF_8);
        frame.putShort((short) utf8.length);
        frame.put(utf8);
    }

    private static ByteBuffer allocateFrame(int frameBytes, int correlationId, ErrorCode errorCode) {
        return putEnvelope(ByteBuffer.allocate(frameBytes), frameBytes - LENGTH_FIELD_BYTES, correlationId,
                errorCode);
    }

    /**
     * Writes the three fields every response begins with. It is the only place that layout is written,
     * so a header written ahead of records still on disk and a whole frame written from memory cannot
     * describe themselves differently.
     *
     * @param frame         the buffer to write into, positioned at its first byte
     * @param lengthBytes   what the length field declares, which counts every byte after itself and so
     *                      is not the same as what this buffer holds
     * @param correlationId the number the request carried
     * @param errorCode     the code the envelope carries
     * @return the same buffer, positioned after the envelope
     */
    private static ByteBuffer putEnvelope(ByteBuffer frame, int lengthBytes, int correlationId, ErrorCode errorCode) {
        frame.putInt(lengthBytes);
        frame.putInt(correlationId);
        frame.putShort(errorCode.code());
        return frame;
    }

    private static Response decodeBody(short apiKey, ByteBuffer body) {
        return switch (apiKey) {
            case ApiKeys.PRODUCE -> new ProduceResponse(decodeLong(body, "baseOffset"));
            case ApiKeys.FETCH -> decodeFetch(body);
            case ApiKeys.COMMIT_OFFSET -> new CommitOffsetResponse();
            case ApiKeys.CREATE_TOPIC -> new CreateTopicResponse();
            case ApiKeys.DESCRIBE_TOPICS -> decodeDescribeTopics(body);
            case ApiKeys.DESCRIBE_GROUP -> decodeDescribeGroup(body);
            default -> throw new WireFormatException("api key " + apiKey + " has no body this build can read");
        };
    }

    private static DescribeTopicsResponse decodeDescribeTopics(ByteBuffer body) {
        int topicCount = decodeCount(body, "topicCount", MINIMUM_TOPIC_DESCRIPTION_BYTES);

        List<TopicDescription> topics = new ArrayList<>(topicCount);
        for (int index = 0; index < topicCount; index++) {
            topics.add(decodeTopicDescription(body, index));
        }
        return new DescribeTopicsResponse(topics);
    }

    private static TopicDescription decodeTopicDescription(ByteBuffer body, int index) {
        String name = decodeString(body, "name of topic " + index);
        int partitionCount = decodeCount(body, "partitionCount of topic " + index, PARTITION_DESCRIPTION_BYTES);

        List<PartitionDescription> partitions = new ArrayList<>(partitionCount);
        for (int partition = 0; partition < partitionCount; partition++) {
            partitions.add(new PartitionDescription(decodeInt(body, "partition"),
                    decodeLong(body, "logStartOffset"), decodeLong(body, "highWaterMark"),
                    decodeInt(body, "segmentCount"), decodeLong(body, "bytes")));
        }
        return new TopicDescription(name, partitions);
    }

    private static DescribeGroupResponse decodeDescribeGroup(ByteBuffer body) {
        int offsetCount = decodeCount(body, "offsetCount", MINIMUM_GROUP_OFFSET_BYTES);

        List<GroupOffset> offsets = new ArrayList<>(offsetCount);
        for (int index = 0; index < offsetCount; index++) {
            offsets.add(new GroupOffset(decodeString(body, "topic of offset " + index),
                    decodeInt(body, "partition"), decodeLong(body, "committedOffset")));
        }
        return new DescribeGroupResponse(offsets);
    }

    /**
     * Reads a count and refuses one nothing could be behind.
     *
     * <p>This is the broker's own rule pointed the other way, and for the same reason: four bytes off a
     * socket can ask for a list of two billion entries, and a reader that sizes a list before it has
     * weighed the count against the bytes in hand can be brought down by those four bytes. The smallest
     * an element can be is its fixed fields plus the length field of any string it carries.
     *
     * @param body             the body, positioned at the count
     * @param field            what the count is of, quoted in a refusal
     * @param minimumItemBytes the fewest bytes one element can occupy
     * @return the count, which is safe to size a list to
     */
    private static int decodeCount(ByteBuffer body, String field, int minimumItemBytes) {
        int count = decodeInt(body, field);
        if (count < 0) {
            throw new WireFormatException(field + " " + count + " is negative");
        }
        long smallestBytes = (long) count * minimumItemBytes;
        if (smallestBytes > body.remaining()) {
            throw new WireFormatException(field + " " + count + " needs at least " + smallestBytes
                    + " bytes, but " + body.remaining() + " are left in the frame");
        }
        return count;
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

    private static FetchResponse decodeFetch(ByteBuffer body) {
        long highWaterMark = decodeLong(body, "highWaterMark");
        int recordsSizeBytes = decodeInt(body, "recordsSizeBytes");
        if (recordsSizeBytes < 0 || recordsSizeBytes != body.remaining()) {
            throw new WireFormatException("recordsSizeBytes " + recordsSizeBytes + " does not match the "
                    + body.remaining() + " bytes left in the frame");
        }

        byte[] records = new byte[recordsSizeBytes];
        body.get(records);
        return new FetchResponse(highWaterMark, records);
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
