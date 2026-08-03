package io.shrike.clients;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Who reads what, when the answer is arithmetic rather than a coordinator.
 */
class GroupAssignmentTest {

    private static final String GROUP = "readers";

    @Test
    void ownsThePartitionsWhoseNumberFallsToItsMemberIndex() {
        GroupAssignment onlyMember = new GroupAssignment(GROUP, 0, 1);
        GroupAssignment firstOfTwo = new GroupAssignment(GROUP, 0, 2);
        GroupAssignment secondOfTwo = new GroupAssignment(GROUP, 1, 2);
        GroupAssignment thirdOfThree = new GroupAssignment(GROUP, 2, 3);

        assertEquals(List.of(0, 1, 2, 3), onlyMember.partitionsOf(4), "one member reads the whole topic");
        assertEquals(List.of(0, 2), firstOfTwo.partitionsOf(4));
        assertEquals(List.of(1, 3), secondOfTwo.partitionsOf(4));
        assertEquals(List.of(2, 5), thirdOfThree.partitionsOf(7), "a topic that does not divide evenly still divides");
    }

    @Test
    void givesAMemberBeyondTheLastPartitionNothingToRead() {
        GroupAssignment fourthOfSix = new GroupAssignment(GROUP, 3, 6);
        GroupAssignment secondOfSix = new GroupAssignment(GROUP, 1, 6);

        assertEquals(List.of(), fourthOfSix.partitionsOf(3),
                "six members over three partitions is a configuration that works: three of them read nothing");
        assertEquals(List.of(1), secondOfSix.partitionsOf(3));
        assertFalse(fourthOfSix.ownsPartition(0));
        assertFalse(fourthOfSix.ownsPartition(2));
    }

    @Test
    void dividesEveryPartitionAmongTheMembersWithNoneSharedAndNoneMissed() {
        int partitionCount = 12;
        int memberCount = 5;

        List<Integer> readByTheWholeGroup = new ArrayList<>();
        for (int memberIndex = 0; memberIndex < memberCount; memberIndex++) {
            readByTheWholeGroup.addAll(new GroupAssignment(GROUP, memberIndex, memberCount).partitionsOf(
                    partitionCount));
        }
        readByTheWholeGroup.sort(Integer::compareTo);

        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11), readByTheWholeGroup,
                "every partition is read by one member of the group, and no partition by two");
    }

    @Test
    void answersWhetherOnePartitionIsThisMembersToRead() {
        GroupAssignment secondOfThree = new GroupAssignment(GROUP, 1, 3);

        assertTrue(secondOfThree.ownsPartition(1));
        assertTrue(secondOfThree.ownsPartition(4));
        assertFalse(secondOfThree.ownsPartition(0));
        assertFalse(secondOfThree.ownsPartition(5));
    }

    @Test
    void refusesAMemberIndexThatIsNotOneOfTheMembers() {
        IllegalArgumentException pastTheLast = assertThrows(IllegalArgumentException.class,
                () -> new GroupAssignment(GROUP, 2, 2));
        IllegalArgumentException negative = assertThrows(IllegalArgumentException.class,
                () -> new GroupAssignment(GROUP, -1, 2));

        assertTrue(pastTheLast.getMessage().contains("memberIndex must be 0 to 1"), pastTheLast.getMessage());
        assertTrue(negative.getMessage().contains("memberIndex"), negative.getMessage());
    }

    @Test
    void refusesAGroupWithNoMembersOrNoName() {
        IllegalArgumentException noMembers = assertThrows(IllegalArgumentException.class,
                () -> new GroupAssignment(GROUP, 0, 0));
        IllegalArgumentException blankGroup = assertThrows(IllegalArgumentException.class,
                () -> new GroupAssignment("  ", 0, 1));

        assertTrue(noMembers.getMessage().contains("memberCount must be at least 1"), noMembers.getMessage());
        assertTrue(blankGroup.getMessage().contains("groupId"), blankGroup.getMessage());
        assertThrows(NullPointerException.class, () -> new GroupAssignment(null, 0, 1));
    }

    @Test
    void refusesToBeAskedAboutAPartitionThatCannotExist() {
        GroupAssignment onlyMember = new GroupAssignment(GROUP, 0, 1);

        assertThrows(IllegalArgumentException.class, () -> onlyMember.ownsPartition(-1));
        assertThrows(IllegalArgumentException.class, () -> onlyMember.partitionsOf(0));
    }
}
