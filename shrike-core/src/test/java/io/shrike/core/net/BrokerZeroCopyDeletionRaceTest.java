package io.shrike.core.net;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.shrike.core.log.LogConfig;
import io.shrike.core.log.ProducedRecord;
import io.shrike.core.protocol.ApiKeys;
import io.shrike.core.protocol.CreateTopicRequest;
import io.shrike.core.protocol.FetchRequest;
import io.shrike.core.protocol.FetchResponse;
import io.shrike.core.protocol.ProduceRequest;
import io.shrike.core.protocol.ResponseDecoding;
import io.shrike.core.protocol.ResponseFrame;
import io.shrike.core.protocol.WireRecords;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Retention deleting a segment out from under a fetch that is still being written to a socket.
 *
 * <p>This is the hazard a transfer straight out of the log file brings with it, so it is proven rather
 * than argued. The sequence is causal and nothing here sleeps: the client takes a few bytes of the
 * answer off the socket, which is what makes "the broker has started sending and has not finished"
 * something the test knows rather than something it hopes; retention is then asked to sweep, by hand,
 * through the same call {@code shrike-retention} makes; and only then does the client read the rest of
 * the length the broker promised it.
 *
 * <p>The answer is nearly four mebibytes and the client's receive buffer is sixteen kibibytes, so the
 * bytes cannot all be sitting in a kernel buffer with the broker already gone from the request. What
 * has to hold is that the response arrives whole, that it is the bytes the deleted segment held, and
 * that the connection is a connection afterwards rather than one stopped half way through a frame.
 */
class BrokerZeroCopyDeletionRaceTest {

    private static final String TOPIC = "orders";
    private static final int PARTITION = 0;
    private static final int ONLY_PARTITION_COUNT = 1;
    private static final int ANSWER_IMMEDIATELY_MS = 0;
    private static final int ANY_BYTES_WILL_DO = 0;

    /** length 4, crc32c 4, magic 1, attributes 1, offset 8, timestamp 8, keyLen 4, valueLen 4. */
    private static final int FRAMING_BYTES_WITHOUT_KEY = 34;

    private static final int VALUE_BYTES = 8 * 1024;
    private static final int RECORD_BYTES = FRAMING_BYTES_WITHOUT_KEY + VALUE_BYTES;

    /** Records to a segment: about 3.8 MiB of them, which is more than a socket buffer holds. */
    private static final int RECORDS_PER_SEGMENT = 480;
    private static final int SEGMENT_BYTES = RECORDS_PER_SEGMENT * RECORD_BYTES;
    private static final int RECORDS_PER_PRODUCE = 60;

    /** Everything sealed is retired the moment it is sealed, so one sweep takes the first segment. */
    private static final long DELETE_EVERY_SEALED_SEGMENT_MS = 0L;

    private static final int SMALL_RECEIVE_BUFFER_BYTES = 16 * 1024;
    private static final int A_FEW_BYTES = 64;

    /** More than one segment holds, so the answer stops at the segment boundary rather than at a cap. */
    private static final int PLENTY_OF_BYTES = Integer.MAX_VALUE;

    private static final String FIRST_SEGMENT_FILE = "00000000000000000000.log";

    @TempDir
    Path dataDirectory;

    private ShrikeBroker broker;

    @BeforeEach
    void startBroker() throws IOException {
        LogConfig segmentsThatRetireAtOnce = new LogConfig(LogConfig.DEFAULT_MAX_RECORD_BYTES, SEGMENT_BYTES,
                LogConfig.DEFAULT_INDEX_INTERVAL_BYTES, DELETE_EVERY_SEALED_SEGMENT_MS, LogConfig.RETENTION_DISABLED);

        broker = ShrikeBroker.start(BrokerHarness.configWithLogConfig(dataDirectory, segmentsThatRetireAtOnce),
                BrokerHarness.SYSTEM_CLOCK);
        try (WireClient client = WireClient.connectTo(broker)) {
            client.call(1, new CreateTopicRequest(TOPIC, ONLY_PARTITION_COUNT));
        }
    }

    @AfterEach
    void stopBroker() {
        broker.close();
    }

    @Test
    void finishesAFetchAlreadyOnTheWireWhenRetentionDeletesTheSegmentItIsBeingSentFrom() throws Exception {
        fillTheFirstSegmentAndRollPastIt();
        Path oldestSegment = dataDirectory.resolve(TOPIC + "-" + PARTITION).resolve(FIRST_SEGMENT_FILE);
        byte[] beforeDeletion = Files.readAllBytes(oldestSegment);

        try (WireClient client = WireClient.connectWithASmallReceiveBuffer(broker, SMALL_RECEIVE_BUFFER_BYTES)) {
            client.send(31, fetchOf(0L));
            int promisedBytes = client.receiveResponseLength();
            byte[] started = client.receiveExactly(A_FEW_BYTES);

            int deleted = broker.partition(TOPIC, PARTITION).orElseThrow()
                    .deleteRetiredSegments(System.currentTimeMillis());

            byte[] rest = client.receiveExactly(promisedBytes - A_FEW_BYTES);

            assertEquals(1, deleted, "the segment being sent is the one retention retired");
            assertFalse(Files.exists(oldestSegment), "and its name is gone from the directory");
            FetchResponse response = decode(started, rest);
            assertEquals(RECORDS_PER_SEGMENT + 1, response.highWaterMark());
            assertArrayEquals(beforeDeletion, response.records(),
                    "every byte the header promised arrived, and they are the deleted segment's own");
            assertEquals(RECORDS_PER_SEGMENT, WireRecords.decode(ByteBuffer.wrap(response.records())).size(),
                    "and they still parse as whole frames that checksum");

            ResponseDecoding next = client.call(32, fetchOf(RECORDS_PER_SEGMENT));
            assertEquals(RECORDS_PER_SEGMENT + 1, fetchResponse(next).highWaterMark(),
                    "the connection was left in the middle of nothing, so it answers the next request");
        }
    }

    /**
     * Appends one segment's worth of records and then one more, which rolls the log and seals the
     * first segment. Only a sealed segment is one retention may delete.
     */
    private void fillTheFirstSegmentAndRollPastIt() throws IOException {
        try (WireClient client = WireClient.connectTo(broker)) {
            int produced = 0;
            while (produced < RECORDS_PER_SEGMENT + 1) {
                int batch = Math.min(RECORDS_PER_PRODUCE, RECORDS_PER_SEGMENT + 1 - produced);
                List<ProducedRecord> records = new ArrayList<>(batch);
                for (int record = 0; record < batch; record++) {
                    records.add(new ProducedRecord(null, valueOf(produced + record)));
                }
                client.call(2, new ProduceRequest(TOPIC, PARTITION, records));
                produced += batch;
            }
        }
    }

    /**
     * @param started the first bytes of the response body, taken before the deletion
     * @param rest    the bytes taken after it
     * @return what the two halves decode to as one answer
     */
    private static FetchResponse decode(byte[] started, byte[] rest) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream(started.length + rest.length);
        body.write(started);
        body.write(rest);
        return fetchResponse(ResponseFrame.decode(ApiKeys.FETCH, ByteBuffer.wrap(body.toByteArray())));
    }

    private static FetchResponse fetchResponse(ResponseDecoding answer) {
        return (FetchResponse) assertInstanceOf(ResponseDecoding.Answered.class, answer, answer.toString())
                .response();
    }

    private static FetchRequest fetchOf(long fetchOffset) {
        return new FetchRequest(TOPIC, PARTITION, fetchOffset, PLENTY_OF_BYTES, ANSWER_IMMEDIATELY_MS,
                ANY_BYTES_WILL_DO);
    }

    private static byte[] valueOf(int record) {
        byte[] value = new byte[VALUE_BYTES];
        byte[] label = "payload-%05d".formatted(record).getBytes(UTF_8);
        System.arraycopy(label, 0, value, 0, label.length);
        return value;
    }
}
