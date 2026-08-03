package io.shrike.core.net;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.shrike.core.log.ProducedRecord;
import io.shrike.core.log.ShrikeIOException;
import io.shrike.core.log.StoredRecord;
import io.shrike.core.protocol.CreateTopicRequest;
import io.shrike.core.protocol.ErrorCode;
import io.shrike.core.protocol.FetchRequest;
import io.shrike.core.protocol.FetchResponse;
import io.shrike.core.protocol.ProduceRequest;
import io.shrike.core.protocol.ResponseDecoding;
import io.shrike.core.protocol.WireRecords;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A topic's identity is at most as fine-grained as the filesystem's: {@code orders} and {@code Orders}
 * are one topic, because {@code orders-0} and {@code Orders-0} are one directory on APFS and on
 * Windows. Two topics sharing one directory would be two locks and two open channels over the same
 * segment file, appending over each other's acknowledged records.
 *
 * <p>Every assertion here is about what the broker refuses or serves, never about what the filesystem
 * underneath it does, so the outcome is the same on a filesystem that folds case and on one that does
 * not.
 */
class BrokerTopicIdentityTest {

    private static final String TOPIC = "orders";
    private static final String SAME_TOPIC_SHOUTED = "ORDERS";
    private static final String SAME_TOPIC_CAPITALISED = "Orders";
    private static final int PARTITION = 0;
    private static final int ONLY_PARTITION_COUNT = 1;
    private static final int ANSWER_IMMEDIATELY_MS = 0;
    private static final int PLENTY_OF_BYTES = 64 * 1024;

    @TempDir
    Path dataDirectory;

    @Test
    void refusesACreateThatDiffersFromAnExistingTopicOnlyInCase() throws IOException {
        try (ShrikeBroker broker = BrokerHarness.start(dataDirectory);
                WireClient client = WireClient.connectTo(broker)) {
            assertInstanceOf(ResponseDecoding.Answered.class,
                    client.call(1, new CreateTopicRequest(TOPIC, ONLY_PARTITION_COUNT)));

            ResponseDecoding repeat = client.call(2, new CreateTopicRequest(SAME_TOPIC_CAPITALISED, 4));

            assertEquals(ErrorCode.TOPIC_ALREADY_EXISTS,
                    assertInstanceOf(ResponseDecoding.Failed.class, repeat).errorCode(),
                    "a name that folds onto a topic that is already here names that topic, whatever count it asks for");
        }
    }

    @Test
    void servesTheOneTopicUnderEveryCasingOfItsName() throws IOException {
        try (ShrikeBroker broker = BrokerHarness.start(dataDirectory);
                WireClient client = WireClient.connectTo(broker)) {
            client.call(1, new CreateTopicRequest(TOPIC, ONLY_PARTITION_COUNT));

            client.call(2, new ProduceRequest(SAME_TOPIC_SHOUTED, PARTITION,
                    List.of(new ProducedRecord(null, "produced under another casing".getBytes(UTF_8)))));

            ResponseDecoding answer = client.call(3, new FetchRequest(SAME_TOPIC_CAPITALISED, PARTITION, 0L,
                    PLENTY_OF_BYTES, ANSWER_IMMEDIATELY_MS, 0));
            FetchResponse response = (FetchResponse) assertInstanceOf(ResponseDecoding.Answered.class,
                    answer).response();
            assertEquals(1L, response.highWaterMark(), "one topic, so one high-water mark");
            assertEquals(List.of("produced under another casing"), valuesIn(response));
        }
    }

    @Test
    void refusesToStartOverARegistryThatListsTwoTopicsDifferingOnlyInCase() throws IOException {
        Files.writeString(dataDirectory.resolve(TopicRegistry.FILE_NAME),
                TopicRegistry.VERSION_HEADER + "\n" + TOPIC + " 1\n" + SAME_TOPIC_CAPITALISED + " 1\n", UTF_8);

        ShrikeIOException refused = assertThrows(ShrikeIOException.class, () -> BrokerHarness.start(dataDirectory));

        assertTrue(refused.getMessage().contains("differ only in case"), refused.getMessage());
        assertTrue(refused.getMessage().contains(TopicRegistry.FILE_NAME),
                "the failure names the file a human has to fix: " + refused.getMessage());
    }

    private static List<String> valuesIn(FetchResponse response) {
        return WireRecords.decode(ByteBuffer.wrap(response.records())).stream()
                .map(StoredRecord::value)
                .map(value -> new String(value, UTF_8))
                .toList();
    }
}
