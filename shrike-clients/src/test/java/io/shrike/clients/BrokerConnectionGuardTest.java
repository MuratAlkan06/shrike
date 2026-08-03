package io.shrike.clients;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.shrike.core.protocol.CreateTopicRequest;
import io.shrike.core.protocol.CreateTopicResponse;
import io.shrike.core.protocol.ProduceResponse;
import io.shrike.core.protocol.ResponseFrame;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

/**
 * The client's half of the frame guard, driven by a server that answers with bytes no broker sends.
 *
 * <p>The rule is the broker's rule pointed the other way: read the four length bytes, refuse to
 * believe a length outside the bound, and treat an answer that cannot be matched to a question as a
 * connection that has lost its place rather than as a request that failed. Every one of these cases
 * ends with a closed connection, because a stream that has lied about its framing has no next frame
 * to look for.
 */
class BrokerConnectionGuardTest {

    private static final int ANY_PARTITION_COUNT = 1;
    private static final int A_SHORT_READ_TIMEOUT_MILLIS = 500;

    @Test
    void closesTheConnectionWhenAnAnswerDeclaresALengthOutsideTheGuard() throws IOException {
        byte[] impossibleLength = ByteBuffer.allocate(Integer.BYTES).putInt(Integer.MAX_VALUE).array();

        try (ScriptedServer server = ScriptedServer.start("huge-length", socket -> write(socket, impossibleLength));
             BrokerConnection connection = BrokerConnection.open(server.clientConfig(A_SHORT_READ_TIMEOUT_MILLIS))) {

            MalformedResponseException broken = assertThrows(MalformedResponseException.class,
                    () -> connection.call(new CreateTopicRequest("orders", ANY_PARTITION_COUNT),
                            CreateTopicResponse.class));

            assertTrue(broken.getMessage().contains(String.valueOf(Integer.MAX_VALUE)),
                    "the failure quotes the length it refused: " + broken.getMessage());
            assertFalse(connection.isOpen(), "nothing was allocated for that length and the connection is gone");
        }
    }

    @Test
    void closesTheConnectionWhenAnAnswerDeclaresALengthTooSmallForAnEnvelope() throws IOException {
        byte[] tooShort = ByteBuffer.allocate(Integer.BYTES).putInt(ResponseFrame.MINIMUM_LENGTH_BYTES - 1).array();

        try (ScriptedServer server = ScriptedServer.start("short-length", socket -> write(socket, tooShort));
             BrokerConnection connection = BrokerConnection.open(server.clientConfig(A_SHORT_READ_TIMEOUT_MILLIS))) {

            assertThrows(MalformedResponseException.class,
                    () -> connection.call(new CreateTopicRequest("orders", ANY_PARTITION_COUNT),
                            CreateTopicResponse.class));

            assertFalse(connection.isOpen());
        }
    }

    @Test
    void closesTheConnectionWhenAnAnswerCarriesSomebodyElsesCorrelationId() throws IOException {
        int aNumberThisClientNeverSent = BrokerConnection.FIRST_CORRELATION_ID + 1_000;
        byte[] misaddressed = bytesOf(ResponseFrame.encode(aNumberThisClientNeverSent, new CreateTopicResponse()));

        try (ScriptedServer server = ScriptedServer.start("wrong-correlation", socket -> write(socket, misaddressed));
             BrokerConnection connection = BrokerConnection.open(server.clientConfig(A_SHORT_READ_TIMEOUT_MILLIS))) {

            MalformedResponseException broken = assertThrows(MalformedResponseException.class,
                    () -> connection.call(new CreateTopicRequest("orders", ANY_PARTITION_COUNT),
                            CreateTopicResponse.class));

            assertTrue(broken.getMessage().contains(String.valueOf(aNumberThisClientNeverSent)),
                    "the failure quotes the number that answered nothing: " + broken.getMessage());
            assertFalse(connection.isOpen());
        }
    }

    @Test
    void closesTheConnectionWhenAnAnswerIsTheBodyOfAnotherApi() throws IOException {
        byte[] anAnswerToADifferentQuestion =
                bytesOf(ResponseFrame.encode(BrokerConnection.FIRST_CORRELATION_ID, new ProduceResponse(7L)));

        try (ScriptedServer server = ScriptedServer.start("wrong-body",
                socket -> write(socket, anAnswerToADifferentQuestion));
             BrokerConnection connection = BrokerConnection.open(server.clientConfig(A_SHORT_READ_TIMEOUT_MILLIS))) {

            assertThrows(MalformedResponseException.class,
                    () -> connection.call(new CreateTopicRequest("orders", ANY_PARTITION_COUNT),
                            CreateTopicResponse.class));

            assertFalse(connection.isOpen(), "a create topic answered with eight bytes of body is not an answer");
        }
    }

    @Test
    void closesTheConnectionWhenTheBrokerDoesNotAnswerWithinTheReadTimeout() throws IOException {
        try (ScriptedServer server = ScriptedServer.start("silent", socket -> { });
             BrokerConnection connection = BrokerConnection.open(server.clientConfig(A_SHORT_READ_TIMEOUT_MILLIS))) {

            BrokerTimeoutException timedOut = assertThrows(BrokerTimeoutException.class,
                    () -> connection.call(new CreateTopicRequest("orders", ANY_PARTITION_COUNT),
                            CreateTopicResponse.class));

            assertTrue(timedOut.getMessage().contains(String.valueOf(A_SHORT_READ_TIMEOUT_MILLIS)),
                    "the failure names the bound it crossed: " + timedOut.getMessage());
            assertFalse(connection.isOpen(), "a late answer must not be read as the reply to the next request");
        }
    }

    @Test
    void closesTheConnectionWhenTheBrokerHangsUpWithoutAnswering() throws IOException {
        try (ScriptedServer server = ScriptedServer.start("hangs-up", Socket::close);
             BrokerConnection connection = BrokerConnection.open(server.clientConfig(A_SHORT_READ_TIMEOUT_MILLIS))) {

            assertThrows(BrokerIOException.class,
                    () -> connection.call(new CreateTopicRequest("orders", ANY_PARTITION_COUNT),
                            CreateTopicResponse.class));

            assertFalse(connection.isOpen());
        }
    }

    private static void write(Socket socket, byte[] bytes) throws IOException {
        OutputStream output = socket.getOutputStream();
        output.write(bytes);
        output.flush();
    }

    private static byte[] bytesOf(ByteBuffer frame) {
        byte[] bytes = new byte[frame.remaining()];
        frame.get(bytes);
        return bytes;
    }
}
