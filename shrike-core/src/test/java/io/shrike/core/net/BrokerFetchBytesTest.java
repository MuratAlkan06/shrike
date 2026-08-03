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
import io.shrike.core.protocol.WireRecords;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a fetch response carries: the bytes of the log, unchanged.
 *
 * <p>The records block is a byte range of the segment file copied verbatim, so a consumer parses the
 * same frames the log wrote. These tests compare the block against the file itself rather than against
 * an expected encoding, because "what the broker sends is what is on disk" is the claim, and a later
 * slice that sends those bytes straight from the file has to keep it true.
 */
class BrokerFetchBytesTest {

    private static final String TOPIC = "orders";
    private static final int PARTITION = 0;
    private static final int ONLY_PARTITION_COUNT = 1;
    private static final int ANSWER_IMMEDIATELY_MS = 0;
    private static final int ANY_BYTES_WILL_DO = 0;
    private static final int PLENTY_OF_BYTES = 64 * 1024;
    private static final String FIRST_SEGMENT_FILE = "00000000000000000000.log";

    @TempDir
    Path dataDirectory;

    private ShrikeBroker broker;
    private WireClient client;

    @BeforeEach
    void startBroker() throws IOException {
        broker = BrokerHarness.start(dataDirectory);
        client = WireClient.connectTo(broker);
        client.call(1, new CreateTopicRequest(TOPIC, ONLY_PARTITION_COUNT));
    }

    @AfterEach
    void stopBroker() throws IOException {
        client.close();
        broker.close();
    }

    @Test
    void servesTheRecordsBlockAsTheVerbatimBytesOfTheSegmentFile() throws Exception {
        produce(new ProducedRecord("k0".getBytes(UTF_8), "first".getBytes(UTF_8)),
                new ProducedRecord(null, "second".getBytes(UTF_8)),
                new ProducedRecord(new byte[0], "third".getBytes(UTF_8)));

        FetchResponse response = fetch(0L, PLENTY_OF_BYTES);

        byte[] segmentFile = Files.readAllBytes(dataDirectory.resolve(TOPIC + "-" + PARTITION)
                .resolve(FIRST_SEGMENT_FILE));
        assertArrayEquals(segmentFile, response.records(),
                "the records block is the segment's bytes, not a re-encoding of its records");
        assertEquals(3L, response.highWaterMark());

        List<StoredRecord> records = WireRecords.decode(ByteBuffer.wrap(response.records()));
        assertEquals(List.of(0L, 1L, 2L), records.stream().map(StoredRecord::offset).toList());
        assertArrayEquals("k0".getBytes(UTF_8), records.get(0).key());
        assertArrayEquals(null, records.get(1).key(), "a record produced without a key still has none");
        assertArrayEquals(new byte[0], records.get(2).key(), "an empty key is not a missing key");
        assertEquals(List.of("first", "second", "third"),
                records.stream().map(record -> new String(record.value(), UTF_8)).toList());
    }

    @Test
    void servesFromTheOffsetThatWasAskedForRatherThanFromTheStartOfTheLog() throws Exception {
        produce(new ProducedRecord(null, "first".getBytes(UTF_8)),
                new ProducedRecord(null, "second".getBytes(UTF_8)),
                new ProducedRecord(null, "third".getBytes(UTF_8)));

        FetchResponse response = fetch(1L, PLENTY_OF_BYTES);

        byte[] segmentFile = Files.readAllBytes(dataDirectory.resolve(TOPIC + "-" + PARTITION)
                .resolve(FIRST_SEGMENT_FILE));
        int firstFrameBytes = segmentFile.length - response.records().length;
        assertArrayEquals(Arrays.copyOfRange(segmentFile, firstFrameBytes, segmentFile.length), response.records(),
                "a fetch from offset 1 starts at the byte the second frame starts at");
        assertEquals(List.of(1L, 2L), WireRecords.decode(ByteBuffer.wrap(response.records())).stream()
                .map(StoredRecord::offset).toList());
    }

    @Test
    void stopsAtTheLastWholeFrameThatFitsMaxBytes() throws Exception {
        // Three values of the same length, so that three frames of the same size make the arithmetic
        // about maxBytes the thing under test rather than the payloads.
        produce(new ProducedRecord(null, "aaa".getBytes(UTF_8)),
                new ProducedRecord(null, "bbb".getBytes(UTF_8)),
                new ProducedRecord(null, "ccc".getBytes(UTF_8)));
        int oneFrameBytes = fetch(0L, ANY_BYTES_WILL_DO).records().length;

        FetchResponse response = fetch(0L, 3 * oneFrameBytes - 1);

        assertEquals(2 * oneFrameBytes, response.records().length,
                "a frame that would cross maxBytes is left for the next fetch");
        assertEquals(List.of(0L, 1L), WireRecords.decode(ByteBuffer.wrap(response.records())).stream()
                .map(StoredRecord::offset).toList());
        assertEquals(3L, response.highWaterMark(), "the high-water mark is the whole partition's, not the answer's");
    }

    @Test
    void servesOneWholeFrameEvenWhenItAloneExceedsMaxBytes() throws Exception {
        produce(new ProducedRecord(null, "first".getBytes(UTF_8)),
                new ProducedRecord(null, "second".getBytes(UTF_8)));

        FetchResponse response = fetch(0L, 1);

        List<StoredRecord> records = WireRecords.decode(ByteBuffer.wrap(response.records()));
        assertEquals(List.of(0L), records.stream().map(StoredRecord::offset).toList(),
                "a consumer that asks for one byte still has to be able to move past the record it is on");
        assertEquals("first", new String(records.get(0).value(), UTF_8));
    }

    private void produce(ProducedRecord... records) throws IOException {
        client.call(2, new ProduceRequest(TOPIC, PARTITION, List.of(records)));
    }

    private FetchResponse fetch(long fetchOffset, int maxBytes) throws IOException {
        ResponseDecoding answer = client.call(3, new FetchRequest(TOPIC, PARTITION, fetchOffset, maxBytes,
                ANSWER_IMMEDIATELY_MS, 0));
        return (FetchResponse) assertInstanceOf(ResponseDecoding.Answered.class, answer).response();
    }
}
