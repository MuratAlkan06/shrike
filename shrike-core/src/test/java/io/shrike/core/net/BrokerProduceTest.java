package io.shrike.core.net;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.shrike.core.log.ProducedRecord;
import io.shrike.core.log.StoredRecord;
import io.shrike.core.protocol.CreateTopicRequest;
import io.shrike.core.protocol.FetchRequest;
import io.shrike.core.protocol.FetchResponse;
import io.shrike.core.protocol.ProduceRequest;
import io.shrike.core.protocol.ProduceResponse;
import io.shrike.core.protocol.ResponseDecoding;
import io.shrike.core.protocol.WireRecords;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Producing: the broker assigns the offsets, answers with the first of them, and keeps the partitions
 * of one topic apart.
 */
class BrokerProduceTest {

    private static final String TOPIC = "orders";
    private static final int PARTITION_COUNT = 3;
    private static final int ANSWER_IMMEDIATELY_MS = 0;
    private static final int PLENTY_OF_BYTES = 64 * 1024;

    @TempDir
    Path dataDirectory;

    private ShrikeBroker broker;
    private WireClient client;

    @BeforeEach
    void startBroker() throws IOException {
        broker = BrokerHarness.start(dataDirectory);
        client = WireClient.connectTo(broker);
        client.call(1, new CreateTopicRequest(TOPIC, PARTITION_COUNT));
    }

    @AfterEach
    void stopBroker() throws IOException {
        client.close();
        broker.close();
    }

    @Test
    void answersEachProduceWithTheOffsetOfItsFirstRecord() throws Exception {
        long firstBaseOffset = produce(0, "a", "b", "c");
        long secondBaseOffset = produce(0, "d");
        long thirdBaseOffset = produce(0, "e", "f");

        assertEquals(0L, firstBaseOffset, "the first record of an empty partition is offset 0");
        assertEquals(3L, secondBaseOffset, "the rest of a batch follow its base offset in order");
        assertEquals(4L, thirdBaseOffset);
        assertEquals(List.of("a", "b", "c", "d", "e", "f"), valuesOf(0));
    }

    @Test
    void keepsTheOffsetsAndRecordsOfOnePartitionOutOfAnother() throws Exception {
        long firstOnPartitionTwo = produce(2, "written to partition two");
        produce(2, "and another");

        assertEquals(0L, firstOnPartitionTwo, "every partition counts its own offsets from zero");
        assertEquals(List.of("written to partition two", "and another"), valuesOf(2));
        assertEquals(List.of(), valuesOf(0), "a partition nobody produced to holds nothing");
        assertEquals(0L, highWaterMarkOf(1));
    }

    private long produce(int partition, String... values) throws IOException {
        List<ProducedRecord> records = List.of(values).stream()
                .map(value -> new ProducedRecord(null, value.getBytes(UTF_8)))
                .toList();
        ResponseDecoding answer = client.call(2, new ProduceRequest(TOPIC, partition, records));
        return ((ProduceResponse) assertInstanceOf(ResponseDecoding.Answered.class, answer).response()).baseOffset();
    }

    private List<String> valuesOf(int partition) throws IOException {
        return WireRecords.decode(ByteBuffer.wrap(fetch(partition).records())).stream()
                .map(StoredRecord::value)
                .map(value -> new String(value, UTF_8))
                .toList();
    }

    private long highWaterMarkOf(int partition) throws IOException {
        return fetch(partition).highWaterMark();
    }

    private FetchResponse fetch(int partition) throws IOException {
        ResponseDecoding answer = client.call(3, new FetchRequest(TOPIC, partition, 0L, PLENTY_OF_BYTES,
                ANSWER_IMMEDIATELY_MS, 0));
        return (FetchResponse) assertInstanceOf(ResponseDecoding.Answered.class, answer).response();
    }
}
