package io.shrike.core.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.shrike.core.protocol.CommitOffsetRequest;
import io.shrike.core.protocol.CreateTopicRequest;
import io.shrike.core.protocol.ErrorCode;
import io.shrike.core.protocol.ResponseDecoding;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The group budget: how many consumer groups this broker will hold committed offsets for.
 *
 * <p>A commit is what creates a group — there is no create-group api — and the group it creates costs a
 * file and a resident entry that nothing ever removes, so anything that can send commits can otherwise
 * make this broker keep as many of them as it likes. The budget here is small so a test can fill it with
 * three commits; what it proves is the shape of the rule — the commit that would create a group past the
 * budget is refused, every group that is already here goes on committing, and the broker keeps answering.
 */
class BrokerGroupBudgetTest {

    private static final int GROUP_BUDGET = 2;

    private static final String TOPIC = "orders";
    private static final int PARTITION = 0;
    private static final int ONLY_PARTITION_COUNT = 1;

    /**
     * The offset every commit here stores. A partition nothing has been produced to has a high-water
     * mark of 0, and committing that mark is a group saying it has read everything there is, so these
     * commits are refused for the budget or for nothing at all.
     */
    private static final long CAUGHT_UP = 0L;

    @TempDir
    Path dataDirectory;

    @Test
    void refusesACommitThatWouldCreateAGroupPastTheGroupBudgetAndKeepsServing() throws IOException {
        try (ShrikeBroker broker = ShrikeBroker.start(
                BrokerHarness.configWithGroupBudget(dataDirectory, GROUP_BUDGET), BrokerHarness.SYSTEM_CLOCK);
                WireClient client = WireClient.connectTo(broker)) {
            client.call(1, new CreateTopicRequest(TOPIC, ONLY_PARTITION_COUNT));
            assertInstanceOf(ResponseDecoding.Answered.class,
                    client.call(2, new CommitOffsetRequest("readers", TOPIC, PARTITION, CAUGHT_UP)),
                    "the first of the two groups this broker may hold");
            assertInstanceOf(ResponseDecoding.Answered.class,
                    client.call(3, new CommitOffsetRequest("auditors", TOPIC, PARTITION, CAUGHT_UP)),
                    "and the second");

            ResponseDecoding pastTheBudget = client.call(4,
                    new CommitOffsetRequest("watchers", TOPIC, PARTITION, CAUGHT_UP));

            assertEquals(ErrorCode.INVALID_REQUEST,
                    assertInstanceOf(ResponseDecoding.Failed.class, pastTheBudget).errorCode(),
                    "a commit naming a group this broker does not hold is a commit that would create one");
            assertInstanceOf(ResponseDecoding.Answered.class,
                    client.call(5, new CommitOffsetRequest("readers", TOPIC, PARTITION, CAUGHT_UP)),
                    "the connection that was refused is still being served");
            assertEquals(ErrorCode.INVALID_REQUEST,
                    assertInstanceOf(ResponseDecoding.Failed.class,
                            client.call(6, new CommitOffsetRequest("newcomers", TOPIC, PARTITION, CAUGHT_UP)))
                            .errorCode(),
                    "and the budget is exhausted rather than approximate");
        }
    }

    @Test
    void servesACommitToAGroupItAlreadyHoldsWhenTheGroupBudgetIsFull() throws IOException {
        try (ShrikeBroker broker = ShrikeBroker.start(
                BrokerHarness.configWithGroupBudget(dataDirectory, GROUP_BUDGET), BrokerHarness.SYSTEM_CLOCK);
                WireClient client = WireClient.connectTo(broker)) {
            client.call(1, new CreateTopicRequest(TOPIC, ONLY_PARTITION_COUNT));
            client.call(2, new CommitOffsetRequest("readers", TOPIC, PARTITION, CAUGHT_UP));
            client.call(3, new CommitOffsetRequest("auditors", TOPIC, PARTITION, CAUGHT_UP));

            ResponseDecoding committedAgain = client.call(4,
                    new CommitOffsetRequest("readers", TOPIC, PARTITION, CAUGHT_UP));

            assertInstanceOf(ResponseDecoding.Answered.class, committedAgain,
                    "the budget counts groups, so a group that is already one of them is never refused for it");
        }
    }

    /**
     * A budget somebody lowered is not a reason to strand committed offsets: every group file was written
     * under the budget of the day, so all of them load and go on committing, and the next new group is
     * what pays. The same call the partition budget makes about a registry it opens over.
     *
     * <p>The three group files are written by a broker rather than by hand, which is what makes this a
     * budget somebody lowered rather than a directory somebody forged.
     */
    @Test
    void opensAGroupCountAlreadyPastTheBudgetAndRefusesTheNextNewGroup() throws IOException {
        try (ShrikeBroker roomier = ShrikeBroker.start(
                BrokerHarness.configWithGroupBudget(dataDirectory, GROUP_BUDGET + 1), BrokerHarness.SYSTEM_CLOCK);
                WireClient client = WireClient.connectTo(roomier)) {
            client.call(1, new CreateTopicRequest(TOPIC, ONLY_PARTITION_COUNT));
            client.call(2, new CommitOffsetRequest("readers", TOPIC, PARTITION, CAUGHT_UP));
            client.call(3, new CommitOffsetRequest("auditors", TOPIC, PARTITION, CAUGHT_UP));
            client.call(4, new CommitOffsetRequest("watchers", TOPIC, PARTITION, CAUGHT_UP));
        }

        try (ShrikeBroker broker = ShrikeBroker.start(
                BrokerHarness.configWithGroupBudget(dataDirectory, GROUP_BUDGET), BrokerHarness.SYSTEM_CLOCK);
                WireClient client = WireClient.connectTo(broker)) {
            assertInstanceOf(ResponseDecoding.Answered.class,
                    client.call(2, new CommitOffsetRequest("readers", TOPIC, PARTITION, CAUGHT_UP)),
                    "a broker that would not start over these would be one a lowered number had made an outage");
            assertInstanceOf(ResponseDecoding.Answered.class,
                    client.call(3, new CommitOffsetRequest("watchers", TOPIC, PARTITION, CAUGHT_UP)),
                    "including the group that is past the budget by being the third of three");
            assertEquals(ErrorCode.INVALID_REQUEST,
                    assertInstanceOf(ResponseDecoding.Failed.class,
                            client.call(4, new CommitOffsetRequest("newcomers", TOPIC, PARTITION, CAUGHT_UP)))
                            .errorCode(),
                    "and a broker already past its budget creates no group beyond the ones it loaded");
        }
    }
}
