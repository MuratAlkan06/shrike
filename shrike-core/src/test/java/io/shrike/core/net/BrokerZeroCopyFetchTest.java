package io.shrike.core.net;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.shrike.core.log.ProducedRecord;
import io.shrike.core.log.StoredRecord;
import io.shrike.core.protocol.CreateTopicRequest;
import io.shrike.core.protocol.FetchRequest;
import io.shrike.core.protocol.FetchResponse;
import io.shrike.core.protocol.ProduceRequest;
import io.shrike.core.protocol.ResponseDecoding;
import io.shrike.core.protocol.ResponseFrame;
import io.shrike.core.protocol.WireRecords;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Serving a fetch out of the segment file instead of out of a buffer, and the one thing that has to
 * stay true when it does: the bytes.
 *
 * <p>The comparison is made over one data directory rather than two, and that is the point of it. The
 * records are produced once, a broker is started over them with {@code fetch.zero.copy} on and asked a
 * list of fetches, and then another is started over the same files with the setting off and asked the
 * same list with the same correlation ids. Two directories would mean two sets of frames — the
 * timestamps and the checksums over them would differ — and the answer would be about the encoder
 * rather than about the two ways of sending what it wrote.
 */
class BrokerZeroCopyFetchTest {

    private static final String TOPIC = "orders";
    private static final int PARTITION = 0;
    private static final int ONLY_PARTITION_COUNT = 1;
    private static final int ANSWER_IMMEDIATELY_MS = 0;
    private static final int ANY_BYTES_WILL_DO = 0;
    private static final int ONE_BYTE_IS_ENOUGH = 1;
    private static final int PLENTY_OF_BYTES = 64 * 1024;
    private static final String FIRST_SEGMENT_FILE = "00000000000000000000.log";

    /** Long enough that a fetch which waited it out would fail this test's bounded await instead. */
    private static final int LONG_WAIT_MS = 60_000;

    /** Five values of the same length, so that five frames of the same size make the arithmetic plain. */
    private static final int RECORD_COUNT = 5;

    /** length 4, correlationId 4, errorCode 2, highWaterMark 8, recordsSizeBytes 4. */
    private static final int FETCH_RESPONSE_HEADER_BYTES = ResponseFrame.LENGTH_FIELD_BYTES
            + ResponseFrame.MINIMUM_LENGTH_BYTES + ResponseFrame.FETCH_RECORDS_PREFIX_BYTES;

    @TempDir
    Path dataDirectory;

    @Test
    void servesByteForByteTheSameResponseWithZeroCopyOnAndOff() throws Exception {
        int oneFrameBytes = storeRecords();
        List<FetchRequest> fetches = List.of(
                fetchOf(0L, PLENTY_OF_BYTES),
                fetchOf(2L, PLENTY_OF_BYTES),
                fetchOf(0L, oneFrameBytes),
                fetchOf(0L, 3 * oneFrameBytes - 1),
                fetchOf(RECORD_COUNT, PLENTY_OF_BYTES),
                fetchOf(0L, 1));

        List<byte[]> transferred = responsesTo(fetches, BrokerHarness.configWithZeroCopyFetch(dataDirectory));
        List<byte[]> copied = responsesTo(fetches, BrokerHarness.configWithBufferedFetch(dataDirectory));

        for (int fetch = 0; fetch < fetches.size(); fetch++) {
            assertArrayEquals(copied.get(fetch), transferred.get(fetch),
                    "the two paths answered " + fetches.get(fetch) + " with different bytes");
        }
        assertEquals(List.of(RECORD_COUNT * oneFrameBytes, 3 * oneFrameBytes, oneFrameBytes, 2 * oneFrameBytes,
                        0, oneFrameBytes),
                transferred.stream().map(BrokerZeroCopyFetchTest::recordBytesIn).toList(),
                "and between them those fetches cover a whole log, a fetch from the middle, one frame, a maxBytes"
                        + " cutting inside a frame, an empty answer at the high-water mark, and one frame larger"
                        + " than the maxBytes that asked for it");
    }

    @Test
    void servesTheRangeThatStartsAtTheFetchOffsetAndEndsOnAWholeFrameBoundary() throws Exception {
        int oneFrameBytes = storeRecords();

        try (ShrikeBroker broker = ShrikeBroker.start(BrokerHarness.configWithZeroCopyFetch(dataDirectory),
                BrokerHarness.SYSTEM_CLOCK);
                WireClient client = WireClient.connectTo(broker)) {
            ResponseDecoding answer = client.call(9, fetchOf(1L, 3 * oneFrameBytes - 1));

            FetchResponse response = answered(answer);
            byte[] segmentFile = Files.readAllBytes(segmentFile());
            assertArrayEquals(Arrays.copyOfRange(segmentFile, oneFrameBytes, 3 * oneFrameBytes), response.records(),
                    "the payload is the file from the byte offset 1's frame starts at to the byte a frame ends on");
            assertEquals(List.of(1L, 2L), WireRecords.decode(ByteBuffer.wrap(response.records())).stream()
                    .map(StoredRecord::offset).toList(), "a frame that would cross maxBytes is left behind whole");
            assertEquals(RECORD_COUNT, response.highWaterMark(),
                    "and the mark beside them is the partition's, not the answer's");
        }
    }

    /**
     * Nothing in this broker forces a segment while it is taking records, so the bytes a fetch is
     * answered with here have been written and not forced. Sending them out of the file is still
     * reading them back through the page cache the append went into, which is why the answer is the
     * file's own bytes rather than a shorter one.
     */
    @Test
    void servesAWaitingFetchTheAppendedBytesItHasNotForcedYet() throws Exception {
        String value = "landed while the fetch was waiting";
        ExecutorService fetchThread = Executors.newSingleThreadExecutor();

        try (ShrikeBroker broker = ShrikeBroker.start(BrokerHarness.configWithZeroCopyFetch(dataDirectory),
                BrokerHarness.SYSTEM_CLOCK)) {
            createTopic(broker);
            CountDownLatch waiterRegistered = new CountDownLatch(1);
            broker.partition(TOPIC, PARTITION).orElseThrow().onWaiterRegistered(waiterRegistered::countDown);

            Future<ResponseDecoding> fetch = fetchThread.submit(() -> {
                try (WireClient client = WireClient.connectTo(broker)) {
                    return client.call(21, new FetchRequest(TOPIC, PARTITION, 0L, PLENTY_OF_BYTES, LONG_WAIT_MS,
                            ONE_BYTE_IS_ENOUGH));
                }
            });
            Await.latch(waiterRegistered, "the fetch to register as a waiter");
            produce(broker, List.of(new ProducedRecord(null, value.getBytes(UTF_8))));

            FetchResponse response = answered(Await.value(fetch, "the fetch that was waiting for a record"));
            assertEquals(1L, response.highWaterMark());
            assertArrayEquals(Files.readAllBytes(segmentFile()), response.records(),
                    "what was sent is what is in the file, forced or not");
            assertEquals(List.of(value), WireRecords.decode(ByteBuffer.wrap(response.records())).stream()
                    .map(record -> new String(record.value(), UTF_8)).toList());
        } finally {
            fetchThread.shutdownNow();
        }
    }

    /**
     * Produces {@value #RECORD_COUNT} records of one size into a partition of its own broker, which is
     * then stopped so that the brokers under test open the files it left.
     *
     * @return how many bytes one of those records occupies on disk
     */
    private int storeRecords() throws IOException {
        try (ShrikeBroker broker = BrokerHarness.start(dataDirectory)) {
            createTopic(broker);
            List<ProducedRecord> records = new ArrayList<>(RECORD_COUNT);
            for (int record = 0; record < RECORD_COUNT; record++) {
                records.add(new ProducedRecord(null, "payload-%02d".formatted(record).getBytes(UTF_8)));
            }
            produce(broker, records);
        }
        return (int) (Files.size(segmentFile()) / RECORD_COUNT);
    }

    /**
     * @param fetches what to ask, in order
     * @param config  the broker to ask, which differs between calls only in {@code fetch.zero.copy}
     * @return every response frame as it came off the socket
     */
    private List<byte[]> responsesTo(List<FetchRequest> fetches, BrokerConfig config) throws IOException {
        List<byte[]> responses = new ArrayList<>(fetches.size());
        try (ShrikeBroker broker = ShrikeBroker.start(config, BrokerHarness.SYSTEM_CLOCK);
                WireClient client = WireClient.connectTo(broker)) {
            int correlationId = 100;
            for (FetchRequest fetch : fetches) {
                responses.add(client.callForBytes(correlationId++, fetch));
            }
        }
        return responses;
    }

    private void createTopic(ShrikeBroker broker) throws IOException {
        try (WireClient client = WireClient.connectTo(broker)) {
            client.call(1, new CreateTopicRequest(TOPIC, ONLY_PARTITION_COUNT));
        }
    }

    private void produce(ShrikeBroker broker, List<ProducedRecord> records) throws IOException {
        try (WireClient client = WireClient.connectTo(broker)) {
            client.call(2, new ProduceRequest(TOPIC, PARTITION, records));
        }
    }

    private static FetchRequest fetchOf(long fetchOffset, int maxBytes) {
        return new FetchRequest(TOPIC, PARTITION, fetchOffset, maxBytes, ANSWER_IMMEDIATELY_MS, ANY_BYTES_WILL_DO);
    }

    private static FetchResponse answered(ResponseDecoding answer) {
        return (FetchResponse) assertInstanceOf(ResponseDecoding.Answered.class, answer, answer.toString())
                .response();
    }

    /**
     * @param responseFrame a whole fetch response frame
     * @return how many bytes of records it carried, taken from the frame's own size so that a header
     *         which lied about it would show up as a mismatch rather than be believed
     */
    private static int recordBytesIn(byte[] responseFrame) {
        return responseFrame.length - FETCH_RESPONSE_HEADER_BYTES;
    }

    private Path segmentFile() {
        return dataDirectory.resolve(TOPIC + "-" + PARTITION).resolve(FIRST_SEGMENT_FILE);
    }
}
