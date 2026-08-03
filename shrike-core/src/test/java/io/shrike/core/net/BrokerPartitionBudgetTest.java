package io.shrike.core.net;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.shrike.core.protocol.CreateTopicRequest;
import io.shrike.core.protocol.ErrorCode;
import io.shrike.core.protocol.ResponseDecoding;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The partition budget: how many partitions this broker will hold open across every topic.
 *
 * <p>A partition costs a directory and two open file handles for as long as the broker runs, so one
 * create asking for a thousand of them spends a thousand descriptors that no answer gives back. The
 * budget here is small so a test can reach it with a handful of topics; what it proves is the shape of
 * the rule — a create past the budget is refused, and the broker keeps answering.
 */
class BrokerPartitionBudgetTest {

    private static final int PARTITION_BUDGET = 4;

    @TempDir
    Path dataDirectory;

    @Test
    void refusesACreateThatWouldPassThePartitionBudgetAndKeepsServing() throws IOException {
        try (ShrikeBroker broker = ShrikeBroker.start(
                BrokerHarness.configWithPartitionBudget(dataDirectory, PARTITION_BUDGET), BrokerHarness.SYSTEM_CLOCK);
                WireClient client = WireClient.connectTo(broker)) {
            assertInstanceOf(ResponseDecoding.Answered.class, client.call(1, new CreateTopicRequest("orders", 3)),
                    "three of the four partitions this broker may open");

            ResponseDecoding pastTheBudget = client.call(2, new CreateTopicRequest("events", 2));

            assertEquals(ErrorCode.INVALID_REQUEST,
                    assertInstanceOf(ResponseDecoding.Failed.class, pastTheBudget).errorCode(),
                    "a create is counted against every topic's partitions, not against its own");
            assertInstanceOf(ResponseDecoding.Answered.class, client.call(3, new CreateTopicRequest("events", 1)),
                    "the connection is still usable and the last partition in the budget is still free");
            assertEquals(ErrorCode.INVALID_REQUEST,
                    assertInstanceOf(ResponseDecoding.Failed.class,
                            client.call(4, new CreateTopicRequest("audit", 1))).errorCode(),
                    "and the budget is exhausted rather than approximate");
        }
    }

    /**
     * A budget somebody lowered is not a reason to strand stored records: everything the registry lists
     * was created under the budget of the day, so it all opens, and the next create is what pays.
     */
    @Test
    void opensAPartitionCountAlreadyPastTheBudgetAndRefusesTheNextCreate() throws IOException {
        Files.writeString(dataDirectory.resolve(TopicRegistry.FILE_NAME),
                TopicRegistry.VERSION_HEADER + "\norders 8\n", UTF_8);

        try (ShrikeBroker broker = ShrikeBroker.start(
                BrokerHarness.configWithPartitionBudget(dataDirectory, PARTITION_BUDGET), BrokerHarness.SYSTEM_CLOCK);
                WireClient client = WireClient.connectTo(broker)) {
            assertEquals(ErrorCode.TOPIC_ALREADY_EXISTS,
                    assertInstanceOf(ResponseDecoding.Failed.class,
                            client.call(1, new CreateTopicRequest("orders", 8))).errorCode(),
                    "every partition the registry listed was opened, so the topic is here");
            assertEquals(ErrorCode.INVALID_REQUEST,
                    assertInstanceOf(ResponseDecoding.Failed.class,
                            client.call(2, new CreateTopicRequest("events", 1))).errorCode(),
                    "and a broker already past its budget creates nothing more");
        }
    }
}
