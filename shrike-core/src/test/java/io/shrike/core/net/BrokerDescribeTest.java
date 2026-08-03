package io.shrike.core.net;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.shrike.core.log.LogConfig;
import io.shrike.core.log.ProducedRecord;
import io.shrike.core.protocol.CommitOffsetRequest;
import io.shrike.core.protocol.CreateTopicRequest;
import io.shrike.core.protocol.DescribeGroupRequest;
import io.shrike.core.protocol.DescribeGroupResponse;
import io.shrike.core.protocol.DescribeTopicsRequest;
import io.shrike.core.protocol.DescribeTopicsResponse;
import io.shrike.core.protocol.ErrorCode;
import io.shrike.core.protocol.GroupOffset;
import io.shrike.core.protocol.PartitionDescription;
import io.shrike.core.protocol.ProduceRequest;
import io.shrike.core.protocol.Request;
import io.shrike.core.protocol.Response;
import io.shrike.core.protocol.ResponseDecoding;
import io.shrike.core.protocol.TopicDescription;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The two read-only apis over the wire: what this broker holds, and where one group has got to.
 *
 * <p>The pair is asymmetric on purpose, and both halves of that are proved here. A describe that names
 * a topic this broker does not hold is refused, because a caller that spelled a name out is owed the
 * news that it is not here. A describe of a group this broker has never heard of is answered with no
 * entries, because a commit is what creates a group: "no such group" and "a group that has committed
 * nothing" are one state, and there is no code that could honestly tell them apart.
 */
class BrokerDescribeTest {

    private static final String TOPIC = "orders";
    private static final String GROUP = "readers";

    /** length 4, crc32c 4, magic 1, attributes 1, offset 8, timestamp 8, keyLen 4, valueLen 4. */
    private static final int FRAMING_BYTES_WITHOUT_KEY = 34;

    private static final int VALUE_BYTES = 10;
    private static final int RECORD_BYTES = FRAMING_BYTES_WITHOUT_KEY + VALUE_BYTES;
    private static final int TWO_RECORD_SEGMENT_BYTES = 2 * RECORD_BYTES;

    /** An entry every byte, so a segment past its first record has an index worth counting. */
    private static final int INDEX_INTERVAL_BYTES = 1;

    @TempDir
    Path dataDirectory;

    @Test
    void refusesADescribeThatNamesATopicThisBrokerDoesNotHold() throws IOException {
        try (ShrikeBroker broker = BrokerHarness.start(dataDirectory);
                WireClient client = WireClient.connectTo(broker)) {
            client.call(1, new CreateTopicRequest(TOPIC, 1));

            ResponseDecoding unknown = client.call(2, new DescribeTopicsRequest(List.of(TOPIC, "no-such-topic")));

            assertEquals(ErrorCode.UNKNOWN_TOPIC_OR_PARTITION,
                    assertInstanceOf(ResponseDecoding.Failed.class, unknown, unknown.toString()).errorCode(),
                    "one name this broker does not hold refuses the whole describe");
        }
    }

    @Test
    void describesEveryTopicOfABrokerHoldingNoneAsNoTopicsRatherThanAFailure() throws IOException {
        try (ShrikeBroker broker = BrokerHarness.start(dataDirectory);
                WireClient client = WireClient.connectTo(broker)) {
            DescribeTopicsResponse described = describeTopics(client, DescribeTopicsRequest.everyTopic());

            assertEquals(List.of(), described.topics(),
                    "a describe that names no topic asks about all of them, and none is an answer");
        }
    }

    @Test
    void describesEveryTopicItHoldsWhenTheRequestNamesNone() throws IOException {
        try (ShrikeBroker broker = BrokerHarness.start(dataDirectory);
                WireClient client = WireClient.connectTo(broker)) {
            client.call(1, new CreateTopicRequest("events", 1));
            client.call(2, new CreateTopicRequest(TOPIC, 2));

            DescribeTopicsResponse described = describeTopics(client, DescribeTopicsRequest.everyTopic());

            assertEquals(List.of("events", TOPIC), described.topics().stream().map(TopicDescription::name).toList());
            assertEquals(List.of(1, 2), described.topics().stream().map(TopicDescription::partitionCount).toList(),
                    "a topic is described with the partitions it was created with");
        }
    }

    @Test
    void describesATopicUnderTheFoldedNameThatIsItsIdentityWhateverCasingAsksForIt() throws IOException {
        try (ShrikeBroker broker = BrokerHarness.start(dataDirectory);
                WireClient client = WireClient.connectTo(broker)) {
            client.call(1, new CreateTopicRequest("Orders", 1));

            DescribeTopicsResponse described = describeTopics(client, new DescribeTopicsRequest(List.of("ORDERS")));

            assertEquals(List.of(TOPIC), described.topics().stream().map(TopicDescription::name).toList(),
                    "one topic has one identity, and it is the name a committed offset is keyed by too");
        }
    }

    @Test
    void reportsThePartitionsOffsetsSegmentCountAndBytesOnDiskAcrossASegmentRoll() throws IOException {
        LogConfig smallSegments = new LogConfig(LogConfig.DEFAULT_MAX_RECORD_BYTES, TWO_RECORD_SEGMENT_BYTES,
                INDEX_INTERVAL_BYTES);

        try (ShrikeBroker broker = ShrikeBroker.start(BrokerHarness.configWithLogConfig(dataDirectory, smallSegments),
                BrokerHarness.SYSTEM_CLOCK);
                WireClient client = WireClient.connectTo(broker)) {
            client.call(1, new CreateTopicRequest(TOPIC, 1));
            for (int record = 0; record < 5; record++) {
                client.call(2 + record, produce(record));
            }

            DescribeTopicsResponse described = describeTopics(client, new DescribeTopicsRequest(List.of(TOPIC)));

            PartitionDescription partition = described.topics().get(0).partitions().get(0);
            assertEquals(0, partition.partition());
            assertEquals(0L, partition.logStartOffset(), "nothing has been deleted, so the log still starts at 0");
            assertEquals(5L, partition.highWaterMark(), "the high-water mark is the offset the next append takes");
            assertEquals(3, partition.segmentCount(), "five records into two-record segments rolled twice");
            assertEquals(bytesOnDisk(dataDirectory.resolve(TOPIC + "-0")), partition.bytes(),
                    "the bytes reported are the log files and the index files of every segment, added up");
        }
    }

    @Test
    void describesAGroupThatHasNeverCommittedAsNoOffsetsRatherThanAFailure() throws IOException {
        try (ShrikeBroker broker = BrokerHarness.start(dataDirectory);
                WireClient client = WireClient.connectTo(broker)) {
            DescribeGroupResponse described = describeGroup(client, new DescribeGroupRequest("nobody"));

            assertEquals(List.of(), described.offsets(),
                    "a commit is what creates a group, so a group with none is not a group that is missing");
        }
    }

    @Test
    void describesExactlyTheOffsetsAGroupCommittedInTopicThenPartitionOrder() throws IOException {
        try (ShrikeBroker broker = BrokerHarness.start(dataDirectory);
                WireClient client = WireClient.connectTo(broker)) {
            client.call(1, new CreateTopicRequest(TOPIC, 2));
            client.call(2, new CreateTopicRequest("events", 1));
            client.call(3, produce(0));
            client.call(4, new CommitOffsetRequest(GROUP, TOPIC, 1, 0L));
            client.call(5, new CommitOffsetRequest("other-group", TOPIC, 0, 0L));
            client.call(6, new CommitOffsetRequest(GROUP, "events", 0, 0L));
            client.call(7, new CommitOffsetRequest(GROUP, TOPIC, 0, 1L));

            DescribeGroupResponse described = describeGroup(client, new DescribeGroupRequest(GROUP));

            assertEquals(List.of(new GroupOffset("events", 0, 0L), new GroupOffset(TOPIC, 0, 1L),
                            new GroupOffset(TOPIC, 1, 0L)), described.offsets(),
                    "one group's offsets and nobody else's, topic and then partition whatever order they arrived in");
        }
    }

    private static ProduceRequest produce(int record) {
        return new ProduceRequest(TOPIC, 0, List.of(
                new ProducedRecord(null, "payload-%02d".formatted(record).getBytes(UTF_8))));
    }

    private static DescribeTopicsResponse describeTopics(WireClient client, DescribeTopicsRequest request)
            throws IOException {
        return assertInstanceOf(DescribeTopicsResponse.class, answerTo(client, request));
    }

    private static DescribeGroupResponse describeGroup(WireClient client, DescribeGroupRequest request)
            throws IOException {
        return assertInstanceOf(DescribeGroupResponse.class, answerTo(client, request));
    }

    private static Response answerTo(WireClient client, Request request) throws IOException {
        ResponseDecoding decoding = client.call(99, request);

        return assertInstanceOf(ResponseDecoding.Answered.class, decoding, decoding.toString()).response();
    }

    /**
     * @param partitionDirectory a partition's own directory
     * @return every byte its segment files occupy, read from the filesystem rather than from the broker
     */
    private static long bytesOnDisk(Path partitionDirectory) throws IOException {
        try (Stream<Path> files = Files.list(partitionDirectory)) {
            long totalBytes = 0L;
            for (Path file : files.toList()) {
                totalBytes += Files.size(file);
            }
            return totalBytes;
        }
    }
}
