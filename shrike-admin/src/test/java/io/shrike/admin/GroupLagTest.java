package io.shrike.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.shrike.core.protocol.GroupOffset;
import io.shrike.core.protocol.PartitionDescription;
import io.shrike.core.protocol.TopicDescription;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The one number this facade computes rather than reports, tested without a broker or a socket,
 * because the computation needs neither: it is the two answers the broker gave and a subtraction.
 */
class GroupLagTest {

    private static final String GROUP = "watchers";

    @Test
    void subtractsWhatAGroupCommittedFromEachPartitionsHighWaterMark() {
        List<GroupOffset> committed = List.of(new GroupOffset("orders", 0, 2), new GroupOffset("orders", 1, 7));
        List<TopicDescription> described = List.of(new TopicDescription("orders",
                List.of(partitionEndingAt(0, 5), partitionEndingAt(1, 9))));

        GroupLag lag = GroupLag.across(GROUP, committed, described);

        assertEquals(List.of(new PartitionLag("orders", 0, 2, 5, 3), new PartitionLag("orders", 1, 7, 9, 2)),
                lag.partitions());
        assertEquals(GROUP, lag.group());
    }

    @Test
    void reportsNoLagForAPartitionAGroupHasReadToItsEnd() {
        List<GroupOffset> committed = List.of(new GroupOffset("orders", 0, 5));
        List<TopicDescription> described = List.of(new TopicDescription("orders",
                List.of(partitionEndingAt(0, 5))));

        GroupLag lag = GroupLag.across(GROUP, committed, described);

        // A committed offset is the next offset to read, so a group that has read everything committed
        // the high-water mark itself. Its lag is zero rather than one.
        assertEquals(List.of(new PartitionLag("orders", 0, 5, 5, 0)), lag.partitions());
    }

    @Test
    void keepsTheOrderTheBrokerListedTheCommittedOffsetsIn() {
        List<GroupOffset> committed = List.of(new GroupOffset("orders", 1, 1), new GroupOffset("shipments", 0, 0));
        List<TopicDescription> described = List.of(
                new TopicDescription("shipments", List.of(partitionEndingAt(0, 4))),
                new TopicDescription("orders", List.of(partitionEndingAt(0, 9), partitionEndingAt(1, 3))));

        GroupLag lag = GroupLag.across(GROUP, committed, described);

        assertEquals(List.of("orders", "shipments"), lag.partitions().stream().map(PartitionLag::topic).toList(),
                "the rows follow the offsets the group committed, not the order the topics were described in");
        assertEquals(List.of(2L, 4L), lag.partitions().stream().map(PartitionLag::lag).toList());
    }

    @Test
    void countsTheRecordsRetentionDeletedInTheLagOfAGroupThatFellBehindIt() {
        List<GroupOffset> committed = List.of(new GroupOffset("orders", 0, 2));
        // Retention has moved this partition's start offset past what the group committed, so offsets 2
        // to 5 are gone and the group can never read them: its next fetch is refused with 6.
        List<TopicDescription> described = List.of(new TopicDescription("orders",
                List.of(new PartitionDescription(0, 6L, 9L, 2, 0L))));

        GroupLag lag = GroupLag.across(GROUP, committed, described);

        assertEquals(List.of(new PartitionLag("orders", 0, 2, 9, 7)), lag.partitions(),
                "lag is the high-water mark minus the commit whether or not the records between them are"
                        + " still stored: it is how far behind the group is, not how much it can still read");
    }

    @Test
    void refusesToReportOnAPartitionThatWasNotDescribed() {
        List<GroupOffset> committed = List.of(new GroupOffset("orders", 3, 1));
        List<TopicDescription> described = List.of(new TopicDescription("orders",
                List.of(partitionEndingAt(0, 9))));

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> GroupLag.across(GROUP, committed, described));

        assertTrue(refused.getMessage().contains("partition=3"), refused.getMessage());
    }

    @Test
    void refusesToReportALagBelowZero() {
        List<GroupOffset> committed = List.of(new GroupOffset("orders", 0, 7));
        List<TopicDescription> described = List.of(new TopicDescription("orders",
                List.of(partitionEndingAt(0, 5))));

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> GroupLag.across(GROUP, committed, described));

        // Reading the group first is what makes a negative lag impossible, so a negative one means the
        // two answers arrived the other way round. That is a bug to be shouted about, not a number to
        // be published.
        assertTrue(refused.getMessage().contains("highWaterMark=5"), refused.getMessage());
    }

    /**
     * @param partition      the partition number
     * @param highWaterMark  the offset its next append would take
     * @return that partition as a describe would report it, with the numbers this test does not care
     *         about held at their smallest legal values
     */
    private static PartitionDescription partitionEndingAt(int partition, long highWaterMark) {
        return new PartitionDescription(partition, 0L, highWaterMark, PartitionDescription.MIN_SEGMENT_COUNT, 0L);
    }
}
