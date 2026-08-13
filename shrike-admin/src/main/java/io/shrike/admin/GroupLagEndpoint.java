package io.shrike.admin;

import io.shrike.clients.ClientConfig;
import io.shrike.clients.ShrikeGroups;
import io.shrike.clients.ShrikeTopics;
import io.shrike.core.protocol.GroupOffset;
import io.shrike.core.protocol.TopicDescription;
import java.util.List;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * How far a consumer group is behind the partitions it has committed against.
 *
 * <p>It is the one endpoint that asks the broker two questions, and the order of the two is the
 * design: <strong>the group is described first and the topics second.</strong> A committed offset read
 * before a high-water mark can only be compared against a high-water mark that is the same or larger,
 * so the subtraction in {@link PartitionLag#between} cannot come out negative. Reading them the other
 * way round would let an append land in between and make a group look like it had read records that do
 * not exist yet.
 *
 * <p>Each question opens its own connection and closes it before the next one is asked, so this
 * endpoint holds two connections in turn and never two at once.
 */
@RestController
@RequestMapping("/api/v1/groups")
public class GroupLagEndpoint {

    private final ClientConfig broker;

    /**
     * @param broker where the broker is, validated at startup
     */
    public GroupLagEndpoint(ClientConfig broker) {
        this.broker = Objects.requireNonNull(broker, "broker");
    }

    /**
     * @param group the group to report on
     * @return one row per partition that group has committed an offset for
     * @throws NoCommittedOffsetsException if the group has committed nothing, which is also what a
     *                                     group this broker has never heard of looks like
     */
    @GetMapping("/{group}/lag")
    public GroupLag describe(@PathVariable("group") String group) {
        // A group id becomes a file name, so it goes through the same one rule a topic name does, and
        // in the same place: in front of the connection, so that a name this facade can already see is
        // unusable costs no socket. TopicsEndpoint.describe says the rest of it.
        UnusableNameException.requireUsable(group, "groupId");

        List<GroupOffset> committed;
        try (ShrikeGroups groups = ShrikeGroups.open(broker)) {
            committed = groups.describe(group);
        }
        if (committed.isEmpty()) {
            throw new NoCommittedOffsetsException(group);
        }

        List<TopicDescription> described;
        try (ShrikeTopics topics = ShrikeTopics.open(broker)) {
            described = topics.describe(topicsCommittedTo(committed));
        }
        return GroupLag.across(group, committed, described);
    }

    /**
     * @param committed every offset a group has committed
     * @return the topics those offsets name, each once, in the order they first appear
     */
    private static List<String> topicsCommittedTo(List<GroupOffset> committed) {
        return committed.stream().map(GroupOffset::topic).distinct().toList();
    }
}
