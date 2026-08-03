package io.shrike.core.net;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.shrike.core.log.ProducedRecord;
import io.shrike.core.protocol.CommitOffsetRequest;
import io.shrike.core.protocol.CreateTopicRequest;
import io.shrike.core.protocol.ErrorCode;
import io.shrike.core.protocol.FetchRequest;
import io.shrike.core.protocol.FetchResponse;
import io.shrike.core.protocol.ProduceRequest;
import io.shrike.core.protocol.ProduceResponse;
import io.shrike.core.protocol.ResponseDecoding;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What survives a restart: the topics, their partition counts, every record, and every committed
 * offset. The second broker is a new object over the same directory, which is what a process restart
 * looks like from the inside.
 */
class BrokerRestartTest {

    private static final String TOPIC = "orders";
    private static final int PARTITION_COUNT = 2;
    private static final String GROUP = "readers";
    private static final int ANSWER_IMMEDIATELY_MS = 0;
    private static final int PLENTY_OF_BYTES = 64 * 1024;
    private static final long COMMITTED_OFFSET = 2L;

    @TempDir
    Path dataDirectory;

    @Test
    void keepsItsTopicsRecordsAndCommittedOffsetsAcrossARestart() throws Exception {
        byte[] recordsBeforeRestart;
        try (ShrikeBroker broker = BrokerHarness.start(dataDirectory);
             WireClient client = WireClient.connectTo(broker)) {
            client.call(1, new CreateTopicRequest(TOPIC, PARTITION_COUNT));
            produce(client, 0, "a", "b", "c");
            produce(client, 1, "on the second partition");
            assertInstanceOf(ResponseDecoding.Answered.class,
                    client.call(4, new CommitOffsetRequest(GROUP, TOPIC, 0, COMMITTED_OFFSET)));
            recordsBeforeRestart = fetch(client, 0).records();
        }

        try (ShrikeBroker restarted = BrokerHarness.start(dataDirectory);
             WireClient client = WireClient.connectTo(restarted)) {
            ResponseDecoding repeatCreate = client.call(5, new CreateTopicRequest(TOPIC, PARTITION_COUNT));
            ResponseDecoding pastTheLastPartition = client.call(6, new FetchRequest(TOPIC, PARTITION_COUNT, 0L,
                    PLENTY_OF_BYTES, ANSWER_IMMEDIATELY_MS, 0));
            FetchResponse records = fetch(client, 0);
            FetchResponse secondPartition = fetch(client, 1);
            OptionalLong committed = restarted.groupOffsets().committedOffset(GROUP, TOPIC, 0);
            long nextBaseOffset = produce(client, 0, "d");

            assertEquals(ErrorCode.TOPIC_ALREADY_EXISTS,
                    assertInstanceOf(ResponseDecoding.Failed.class, repeatCreate).errorCode(),
                    "the topic came back with the registry, not with a client asking for it again");
            assertEquals(ErrorCode.UNKNOWN_TOPIC_OR_PARTITION,
                    assertInstanceOf(ResponseDecoding.Failed.class, pastTheLastPartition).errorCode(),
                    "the partition count came back with it, and it is still " + PARTITION_COUNT);
            assertArrayEquals(recordsBeforeRestart, records.records(), "every record reads back byte for byte");
            assertEquals(3L, records.highWaterMark());
            assertEquals(1L, secondPartition.highWaterMark(), "each partition kept its own offsets");
            assertEquals(OptionalLong.of(COMMITTED_OFFSET), committed,
                    "the committed offset is the next offset that group should read, and it survived");
            assertEquals(3L, nextBaseOffset, "the log appends after the last record it recovered");
        }
    }

    private static long produce(WireClient client, int partition, String... values) throws IOException {
        List<ProducedRecord> records = List.of(values).stream()
                .map(value -> new ProducedRecord(null, value.getBytes(UTF_8)))
                .toList();
        ResponseDecoding answer = client.call(2, new ProduceRequest(TOPIC, partition, records));
        return ((ProduceResponse) assertInstanceOf(ResponseDecoding.Answered.class, answer).response()).baseOffset();
    }

    private static FetchResponse fetch(WireClient client, int partition) throws IOException {
        ResponseDecoding answer = client.call(3, new FetchRequest(TOPIC, partition, 0L, PLENTY_OF_BYTES,
                ANSWER_IMMEDIATELY_MS, 0));
        return (FetchResponse) assertInstanceOf(ResponseDecoding.Answered.class, answer).response();
    }
}
