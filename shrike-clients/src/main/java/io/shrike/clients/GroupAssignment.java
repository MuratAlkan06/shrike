package io.shrike.clients;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Which partitions one member of a consumer group reads: the ones whose number falls to it,
 * {@code partition % memberCount == memberIndex}.
 *
 * <p><strong>The assignment is arithmetic, not a negotiation.</strong> A member is configured with the
 * group it commits under, its own index, and how many members the group has, and those three numbers
 * are the whole of it. Nothing is asked of the broker, nothing is stored there, and nothing changes
 * while a member runs: the broker keeps committed offsets keyed by group, topic, and partition, and
 * knows nothing about members, sessions, or heartbeats. Two members of two see one partition each out
 * of every two; four members of four see one out of every four; a member whose index is past the last
 * partition owns nothing at all, which is a configuration that works rather than one that fails.
 *
 * <p><strong>What replaces a member is a member with the same index.</strong> There is no rebalancing
 * in this build — it is a stated non-goal — so a process that dies is replaced by an operator starting
 * another one with the same {@code memberIndex} and {@code memberCount}, and it resumes from the last
 * offset the dead one committed. The partitions of a member that is not running are not read by anyone
 * until it comes back.
 *
 * <p><strong>Two live processes sharing an index is the operator's mistake to avoid.</strong> Nothing
 * here can detect it, because nothing here talks to the other process. What it costs is redelivery:
 * both read the same partitions and both commit, so records are processed more than once, which is
 * what at-least-once delivery already allows. It cannot lose a record.
 *
 * @param groupId     the group this member commits offsets under
 * @param memberIndex this member's place in the group, from 0 up
 * @param memberCount how many members the group has
 */
public record GroupAssignment(String groupId, int memberIndex, int memberCount) {

    public GroupAssignment {
        Objects.requireNonNull(groupId, "groupId");
        if (groupId.isBlank()) {
            throw new IllegalArgumentException("groupId must name a consumer group, but was blank");
        }
        if (memberCount < 1) {
            throw new IllegalArgumentException("memberCount must be at least 1, because a group has at least the"
                    + " member being configured, but was " + memberCount);
        }
        if (memberIndex < 0 || memberIndex >= memberCount) {
            throw new IllegalArgumentException("memberIndex must be 0 to " + (memberCount - 1) + " for a group of "
                    + memberCount + " member(s), but was " + memberIndex);
        }
    }

    /**
     * @param partition the partition to ask about
     * @return whether this member is the one that reads it
     * @throws IllegalArgumentException if the partition is negative
     */
    public boolean ownsPartition(int partition) {
        if (partition < 0) {
            throw new IllegalArgumentException("partition must not be negative, but was " + partition);
        }
        return partition % memberCount == memberIndex;
    }

    /**
     * @param partitionCount how many partitions the topic has
     * @return the partitions of that topic this member reads, in ascending order, which is empty when
     *         the group has more members than the topic has partitions
     * @throws IllegalArgumentException if the count is below 1
     */
    public List<Integer> partitionsOf(int partitionCount) {
        if (partitionCount < 1) {
            throw new IllegalArgumentException("partitionCount must be at least 1, because every topic has at least"
                    + " one partition, but was " + partitionCount);
        }

        List<Integer> partitions = new ArrayList<>();
        for (int partition = memberIndex; partition < partitionCount; partition += memberCount) {
            partitions.add(partition);
        }
        return List.copyOf(partitions);
    }
}
