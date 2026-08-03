package io.shrike.clients;

import io.shrike.core.protocol.CommitOffsetRequest;
import io.shrike.core.protocol.CreateTopicRequest;
import io.shrike.core.protocol.DescribeGroupRequest;
import io.shrike.core.protocol.DescribeTopicsRequest;
import io.shrike.core.protocol.FetchRequest;
import io.shrike.core.protocol.ProduceRequest;
import io.shrike.core.protocol.Request;
import io.shrike.core.protocol.RequestFrame;
import io.shrike.core.protocol.Response;
import io.shrike.core.protocol.ResponseDecoding;
import io.shrike.core.protocol.ResponseFrame;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * One blocking connection to one broker: the socket, the correlation ids, and the guard that decides
 * whether the bytes coming back are a response at all.
 *
 * <p><strong>Blocking, and one call at a time.</strong> A call writes its request and reads its answer
 * before returning; there is no background thread, no queue, and nothing in flight between calls. That
 * makes a connection cheap to reason about and unsafe to share: one thread owns a connection, and two
 * threads that want to talk to a broker open two of them.
 *
 * <p><strong>The response guard</strong> is the mirror of the broker's
 * {@link io.shrike.core.protocol.RequestReader}. Four bytes are read, and the length they carry must
 * land in {@code [ResponseFrame.MINIMUM_LENGTH_BYTES, maxResponseBytes]} before a byte is allocated
 * for the body. A length outside that range, a correlation id that answers a different question, or a
 * body that does not parse means this stream cannot be resynchronized: the connection is closed and a
 * {@link MalformedResponseException} says why. That covers the bodies an error response is not
 * allowed to carry as well: every code but {@code OFFSET_OUT_OF_RANGE} is a code and nothing else,
 * and that one carries exactly eight bytes of log start offset — anything else under either rule is a
 * frame this client refuses rather than reads.
 *
 * <p><strong>Timeouts.</strong> Connecting is bounded by {@code connectTimeoutMillis}. Reading is
 * bounded by {@code readTimeoutMillis}, except for a fetch, whose bound is its own {@code maxWaitMs}
 * plus that margin: the broker is allowed to hold a fetch open for exactly as long as the caller
 * asked, and the margin is what is left for it to answer once it stops waiting. A read that times out
 * closes the connection, because the answer it gave up on may still arrive.
 */
public final class BrokerConnection implements AutoCloseable {

    private static final System.Logger LOGGER = System.getLogger(BrokerConnection.class.getName());

    /** The number the first request on a connection travels under; each one after it climbs by one. */
    public static final int FIRST_CORRELATION_ID = 1;

    private final ClientConfig config;
    private final Socket socket;
    private final InputStream input;
    private final OutputStream output;

    /**
     * The number the next request travels under. It only has to differ from the one before it, so
     * wrapping past {@link Integer#MAX_VALUE} is harmless: the broker echoes whatever it is given.
     */
    // confined to: the one thread that owns this connection, which is also the only one that may call it
    private int nextCorrelationId = FIRST_CORRELATION_ID;

    /** What the socket's read timeout was last set to, so a timeout can say which bound it crossed. */
    // confined to: as above
    private int readTimeoutMillisInUse;

    // confined to: as above
    private boolean closed;

    private BrokerConnection(ClientConfig config, Socket socket, InputStream input, OutputStream output) {
        this.config = config;
        this.socket = socket;
        this.input = input;
        this.output = output;
        this.readTimeoutMillisInUse = config.readTimeoutMillis();
    }

    /**
     * Opens a connection to the broker the configuration names.
     *
     * @param config where the broker is and how long to wait for it
     * @return the connected connection, which the caller closes
     * @throws BrokerTimeoutException if the connection was not established within
     *                                {@code connectTimeoutMillis}
     * @throws BrokerIOException      if the connection was refused or failed
     */
    public static BrokerConnection open(ClientConfig config) {
        Objects.requireNonNull(config, "config");

        Socket socket = new Socket();
        try {
            // Small requests and small answers, one at a time: waiting to see whether another write is
            // coming would add a round trip's delay to every request, which is what the broker's own
            // side of this socket says too.
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(config.host(), config.port()), config.connectTimeoutMillis());
            socket.setSoTimeout(config.readTimeoutMillis());
            return new BrokerConnection(config, socket, socket.getInputStream(), socket.getOutputStream());
        } catch (SocketTimeoutException e) {
            closeQuietly(socket, config.address());
            throw new BrokerTimeoutException("connecting to " + config.address() + " took longer than"
                    + " connectTimeoutMillis=" + config.connectTimeoutMillis(), e);
        } catch (IOException e) {
            closeQuietly(socket, config.address());
            throw new BrokerIOException("cannot connect to " + config.address(), e);
        }
    }

    /**
     * Sends one request and reads its answer.
     *
     * @param request      what to ask
     * @param responseType the response record this api answers with
     * @param <R>          that type
     * @return the answer
     * @throws BrokerErrorException       if the broker answered with an error code; the connection
     *                                    survives it
     * @throws BrokerTimeoutException     if the broker did not answer in time
     * @throws BrokerIOException          if the connection failed or ended
     * @throws MalformedResponseException if what came back is not a response this client can believe
     * @throws IllegalStateException      if this connection is closed
     */
    public <R extends Response> R call(Request request, Class<R> responseType) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(responseType, "responseType");
        if (closed) {
            throw new IllegalStateException("this connection to " + config.address() + " is closed, so it cannot"
                    + " carry " + describe(request));
        }

        int correlationId = nextCorrelationId++;
        applyReadTimeout(request);
        send(correlationId, request);

        Response response = receive(request, correlationId);
        // The codec decodes a body by the api key of the request it answers, so the type is decided by
        // what was asked rather than by what came back; this is the backstop that turns a mistake in
        // that pairing into a closed connection rather than a ClassCastException at the call site.
        if (!responseType.isInstance(response)) {
            throw closeOnBrokenResponse("the broker answered " + describe(request) + " with a "
                    + response.getClass().getSimpleName() + " rather than a " + responseType.getSimpleName());
        }
        return responseType.cast(response);
    }

    /**
     * @return whether this connection can still carry a call. A connection closes itself on every
     *         failure except an error code, so this answers {@code false} after one
     */
    public boolean isOpen() {
        return !closed;
    }

    /**
     * Closes the socket. Calling it twice does nothing the second time.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        closeQuietly(socket, config.address());
    }

    /**
     * A fetch is allowed to be held open for its own {@code maxWaitMs}, so the socket's bound is that
     * wait plus the configured margin. Every other api is bounded by the margin alone.
     */
    private void applyReadTimeout(Request request) {
        int timeoutMillis = request instanceof FetchRequest fetch
                ? saturatedSumMillis(fetch.maxWaitMs(), config.readTimeoutMillis())
                : config.readTimeoutMillis();
        if (timeoutMillis == readTimeoutMillisInUse) {
            return;
        }
        try {
            socket.setSoTimeout(timeoutMillis);
            readTimeoutMillisInUse = timeoutMillis;
        } catch (SocketException e) {
            throw closeOnIoFailure("cannot bound reads from " + config.address() + " at " + timeoutMillis
                    + "ms", e);
        }
    }

    private void send(int correlationId, Request request) {
        ByteBuffer frame = RequestFrame.encode(correlationId, request);
        try {
            // The codec allocates this frame on the heap and hands it back positioned at its first
            // byte, so its backing array holds exactly the bytes to write. There is no short-write
            // loop here because there is no short write to loop over: OutputStream.write writes every
            // byte it is given or throws. DESIGN.md says why this side of the wire is a stream rather
            // than a channel.
            output.write(frame.array(), frame.arrayOffset() + frame.position(), frame.remaining());
        } catch (IOException e) {
            throw closeOnIoFailure("cannot send " + describe(request) + " to " + config.address(), e);
        }
    }

    private Response receive(Request request, int correlationId) {
        int lengthBytes = readFully(ResponseFrame.LENGTH_FIELD_BYTES, "a response length").getInt();
        if (lengthBytes < ResponseFrame.MINIMUM_LENGTH_BYTES || lengthBytes > config.maxResponseBytes()) {
            throw closeOnBrokenResponse("response length " + lengthBytes + " is outside ["
                    + ResponseFrame.MINIMUM_LENGTH_BYTES + ", " + config.maxResponseBytes()
                    + "], so no body was read");
        }

        ByteBuffer frame = readFully(lengthBytes, "a " + lengthBytes + "-byte response");
        return switch (ResponseFrame.decode(request.apiKey(), frame)) {
            case ResponseDecoding.Answered answered -> {
                requireAnswerTo(answered.correlationId(), correlationId, request);
                yield answered.response();
            }
            case ResponseDecoding.Failed failed -> {
                requireAnswerTo(failed.correlationId(), correlationId, request);
                throw new BrokerErrorException(failed.errorCode(), correlationId, describe(request),
                        failed.logStartOffset());
            }
            case ResponseDecoding.BrokenFrame broken -> throw closeOnBrokenResponse("the answer to "
                    + describe(request) + " is not a response: " + broken.reason());
        };
    }

    /**
     * The correlation id is what says which question is being answered, so an answer carrying somebody
     * else's number means this client has lost track of the stream — which is not a request that
     * failed but a connection that cannot be believed.
     */
    private void requireAnswerTo(int answeredCorrelationId, int correlationId, Request request) {
        if (answeredCorrelationId != correlationId) {
            throw closeOnBrokenResponse("the answer to " + describe(request) + " carries correlationId="
                    + answeredCorrelationId + " rather than the " + correlationId + " it was sent under");
        }
    }

    /**
     * @param lengthBytes how many bytes to read
     * @param what        what is being read, quoted in a failure
     * @return exactly that many bytes, positioned at the first of them
     */
    private ByteBuffer readFully(int lengthBytes, String what) {
        byte[] bytes = new byte[lengthBytes];
        int filledBytes = 0;
        while (filledBytes < lengthBytes) {
            int readBytes;
            try {
                readBytes = input.read(bytes, filledBytes, lengthBytes - filledBytes);
            } catch (SocketTimeoutException e) {
                throw closeOnTimeout(config.address() + " did not finish answering within "
                        + readTimeoutMillisInUse + "ms: " + filledBytes + " of " + lengthBytes + " bytes of "
                        + what + " arrived", e);
            } catch (IOException e) {
                throw closeOnIoFailure("the connection to " + config.address() + " failed " + filledBytes
                        + " bytes into " + what, e);
            }
            if (readBytes < 0) {
                throw closeOnIoFailure(config.address() + " closed the connection " + filledBytes + " bytes into "
                        + what);
            }
            filledBytes += readBytes;
        }
        return ByteBuffer.wrap(bytes);
    }

    private MalformedResponseException closeOnBrokenResponse(String reason) {
        close();
        return new MalformedResponseException(reason);
    }

    private BrokerIOException closeOnIoFailure(String message) {
        close();
        return new BrokerIOException(message);
    }

    private BrokerIOException closeOnIoFailure(String message, IOException cause) {
        close();
        return new BrokerIOException(message, cause);
    }

    private BrokerTimeoutException closeOnTimeout(String message, SocketTimeoutException cause) {
        close();
        return new BrokerTimeoutException(message, cause);
    }

    /**
     * @return what a request asks for, in words, so a failure names the call rather than a frame
     */
    private static String describe(Request request) {
        return switch (request) {
            case ProduceRequest produce -> "a produce of " + produce.records().size() + " record(s) to topic="
                    + produce.topic() + " partition=" + produce.partition();
            case FetchRequest fetch -> "a fetch of topic=" + fetch.topic() + " partition=" + fetch.partition()
                    + " from fetchOffset=" + fetch.fetchOffset();
            case CommitOffsetRequest commit -> "a commit of groupId=" + commit.groupId() + " topic=" + commit.topic()
                    + " partition=" + commit.partition() + " offset=" + commit.offset();
            case CreateTopicRequest create -> "a create of topic=" + create.name() + " with "
                    + create.partitionCount() + " partition(s)";
            case DescribeTopicsRequest describeTopics -> describeTopics.describesEveryTopic()
                    ? "a describe of every topic"
                    : "a describe of topic(s) " + String.join(", ", describeTopics.topics());
            case DescribeGroupRequest describeGroup -> "a describe of groupId=" + describeGroup.groupId();
        };
    }

    /**
     * @return the sum, held at {@link Integer#MAX_VALUE} rather than wrapped into a negative timeout
     */
    private static int saturatedSumMillis(int leftMillis, int rightMillis) {
        long sumMillis = (long) leftMillis + rightMillis;
        return sumMillis > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sumMillis;
    }

    private static void closeQuietly(Socket socket, String address) {
        try {
            socket.close();
        } catch (IOException e) {
            LOGGER.log(System.Logger.Level.WARNING, "cannot close the connection to " + address, e);
        }
    }
}
