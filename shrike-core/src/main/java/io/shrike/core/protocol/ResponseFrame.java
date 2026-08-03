package io.shrike.core.protocol;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.Optional;

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
 *       Anything else means the body is empty and the code is the whole answer.
 *   <li>The envelope does not repeat the api key: a client knows what it asked for, and the
 *       correlation id says which question this answers.
 * </ul>
 */
public final class ResponseFrame {

    /** The width of the length field that precedes every frame. */
    public static final int LENGTH_FIELD_BYTES = Integer.BYTES;

    /** The smallest legal {@code length}: a correlation id and an error code, which is an error. */
    public static final int MINIMUM_LENGTH_BYTES = Integer.BYTES + Short.BYTES;

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
        }
        return frame.flip();
    }

    /**
     * Lays out one error response: a correlation id, a code, and nothing else. The body is empty on
     * purpose, so a caller probing the broker with malformed requests learns only which of nine
     * numbers it earned.
     *
     * @param correlationId the number the request carried
     * @param errorCode     what went wrong, which cannot be {@link ErrorCode#NONE}
     * @return the encoded frame
     * @throws IllegalArgumentException if the code is {@link ErrorCode#NONE}, which would be an error
     *                                  response saying nothing went wrong
     */
    public static ByteBuffer encodeError(int correlationId, ErrorCode errorCode) {
        Objects.requireNonNull(errorCode, "errorCode");
        if (errorCode == ErrorCode.NONE) {
            throw new IllegalArgumentException("an error response cannot carry " + ErrorCode.NONE
                    + ": use encode with a body instead");
        }

        return allocateFrame(LENGTH_FIELD_BYTES + MINIMUM_LENGTH_BYTES, correlationId, errorCode).flip();
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
            if (body.hasRemaining()) {
                return new ResponseDecoding.BrokenFrame("an error response carries an empty body, but "
                        + body.remaining() + " bytes follow the code " + errorCode.get());
            }
            return new ResponseDecoding.Failed(correlationId, errorCode.get());
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
        };
    }

    private static ByteBuffer allocateFrame(int frameBytes, int correlationId, ErrorCode errorCode) {
        ByteBuffer frame = ByteBuffer.allocate(frameBytes);
        frame.putInt(frameBytes - LENGTH_FIELD_BYTES);
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
            default -> throw new WireFormatException("api key " + apiKey + " has no body this build can read");
        };
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
