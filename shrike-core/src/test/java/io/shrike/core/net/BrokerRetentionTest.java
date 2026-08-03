package io.shrike.core.net;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.shrike.core.log.LogConfig;
import io.shrike.core.log.ProducedRecord;
import io.shrike.core.protocol.CommitOffsetRequest;
import io.shrike.core.protocol.CreateTopicRequest;
import io.shrike.core.protocol.ErrorCode;
import io.shrike.core.protocol.FetchRequest;
import io.shrike.core.protocol.FetchResponse;
import io.shrike.core.protocol.ProduceRequest;
import io.shrike.core.protocol.ResponseDecoding;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a client on the wire sees once retention has deleted the records it was going to ask for: the
 * refusal, and the number it needs to carry on.
 *
 * <p>Retention is asked for by hand here, through the same call {@code shrike-retention} makes on its
 * own thread, because when a sweep happens is not what these tests are about.
 */
class BrokerRetentionTest {

    private static final String TOPIC = "orders";
    private static final int ONLY_PARTITION_COUNT = 1;
    private static final int PARTITION = 0;
    private static final int ANSWER_IMMEDIATELY_MS = 0;
    private static final int PLENTY_OF_BYTES = 64 * 1024;
    private static final String GROUP = "readers";

    /** length 4, crc32c 4, magic 1, attributes 1, offset 8, timestamp 8, keyLen 4, valueLen 4. */
    private static final int FRAMING_BYTES_WITHOUT_KEY = 34;

    private static final int VALUE_BYTES = 10;
    private static final int RECORD_BYTES = FRAMING_BYTES_WITHOUT_KEY + VALUE_BYTES;
    private static final int TWO_RECORD_SEGMENT_BYTES = 2 * RECORD_BYTES;

    /** Everything sealed is retired the moment it is sealed, so one sweep trims to the active segment. */
    private static final long DELETE_EVERY_SEALED_SEGMENT_MS = 0L;

    @TempDir
    Path dataDirectory;

    private ShrikeBroker broker;
    private WireClient client;

    @BeforeEach
    void startBroker() throws IOException {
        LogConfig twoRecordSegmentsThatRetireAtOnce = new LogConfig(LogConfig.DEFAULT_MAX_RECORD_BYTES,
                TWO_RECORD_SEGMENT_BYTES, LogConfig.DEFAULT_INDEX_INTERVAL_BYTES, DELETE_EVERY_SEALED_SEGMENT_MS,
                LogConfig.RETENTION_DISABLED);

        broker = ShrikeBroker.start(BrokerHarness.configWithLogConfig(dataDirectory,
                twoRecordSegmentsThatRetireAtOnce), BrokerHarness.SYSTEM_CLOCK);
        client = WireClient.connectTo(broker);
        client.call(1, new CreateTopicRequest(TOPIC, ONLY_PARTITION_COUNT));
    }

    @AfterEach
    void stopBroker() throws IOException {
        client.close();
        broker.close();
    }

    @Test
    void answersAFetchBelowTheOffsetRetentionMovedToWithThatOffset() throws Exception {
        produceRecords(5);

        int deleted = sweepRetention();

        assertEquals(2, deleted, "two sealed segments were retired; the one taking records was not");
        ResponseDecoding refused = client.call(3, new FetchRequest(TOPIC, PARTITION, 0L, PLENTY_OF_BYTES,
                ANSWER_IMMEDIATELY_MS, 0));
        ResponseDecoding.Failed failed = assertInstanceOf(ResponseDecoding.Failed.class, refused,
                refused.toString());
        assertEquals(ErrorCode.OFFSET_OUT_OF_RANGE, failed.errorCode());
        assertEquals(OptionalLong.of(4L), failed.logStartOffset(),
                "the refusal carries where the partition starts now, so a consumer can reset on purpose");
    }

    @Test
    void servesTheRecordsFromTheOffsetItsRefusalNamed() throws Exception {
        produceRecords(5);
        sweepRetention();

        ResponseDecoding refused = client.call(4, new FetchRequest(TOPIC, PARTITION, 1L, PLENTY_OF_BYTES,
                ANSWER_IMMEDIATELY_MS, 0));
        long resumeOffset = assertInstanceOf(ResponseDecoding.Failed.class, refused, refused.toString())
                .logStartOffset().orElseThrow();
        ResponseDecoding served = client.call(5, new FetchRequest(TOPIC, PARTITION, resumeOffset, PLENTY_OF_BYTES,
                ANSWER_IMMEDIATELY_MS, 0));

        FetchResponse answer = (FetchResponse) assertInstanceOf(ResponseDecoding.Answered.class, served,
                served.toString()).response();
        assertEquals(5L, answer.highWaterMark());
        assertEquals(RECORD_BYTES, answer.records().length, "the one record retention left is served whole");
    }

    @Test
    void answersACommitPastTheHighWaterMarkWithTheOffsetThePartitionStartsAtToo() throws Exception {
        produceRecords(5);
        sweepRetention();

        ResponseDecoding refused = client.call(6, new CommitOffsetRequest(GROUP, TOPIC, PARTITION, 99L));

        ResponseDecoding.Failed failed = assertInstanceOf(ResponseDecoding.Failed.class, refused,
                refused.toString());
        assertEquals(ErrorCode.OFFSET_OUT_OF_RANGE, failed.errorCode());
        assertEquals(OptionalLong.of(4L), failed.logStartOffset(),
                "one code carries one body, whichever api earned it");
    }

    private void produceRecords(int recordCount) throws IOException {
        for (int record = 0; record < recordCount; record++) {
            client.call(2, new ProduceRequest(TOPIC, PARTITION,
                    List.of(new ProducedRecord(null, "payload-%02d".formatted(record).getBytes(UTF_8)))));
        }
    }

    /**
     * @return how many segments one sweep of this broker's retention deleted, taken through the
     *         partition the way {@code shrike-retention} takes it: under the partition's own lock
     */
    private int sweepRetention() {
        return broker.partition(TOPIC, PARTITION).orElseThrow()
                .deleteRetiredSegments(System.currentTimeMillis());
    }
}
