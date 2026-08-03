package io.shrike.clients;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The routing rule, pinned.
 *
 * <p>The vectors below are literals rather than something this test computes, and that is the point: a
 * test that hashed the keys the same way the code does would agree with any rule at all, including a
 * rule that changed between two releases. These numbers were computed once and frozen, so the mapping
 * they describe is the same on every machine and in every JVM this ever runs on — which is what a key
 * staying in one partition, and therefore in order, depends on.
 */
class PartitionRouterTest {

    /**
     * Keys and the partitions they belong to, for two different partition counts, frozen.
     *
     * <p>Two counts because one would not tell a mapping from a coincidence: 4 is a power of two, where
     * a modulo keeps only the low bits, and 7 is not, where it does not.
     */
    private static final Map<String, Integer> OVER_FOUR_PARTITIONS = Map.of(
            "", 0,
            "a", 0,
            "orders", 2,
            "key-0", 2,
            "key-1", 1,
            "customer-42", 0,
            "é", 0,
            "the quick brown fox", 3);

    private static final Map<String, Integer> OVER_SEVEN_PARTITIONS = Map.of(
            "", 0,
            "a", 4,
            "orders", 3,
            "key-0", 1,
            "key-1", 1,
            "customer-42", 6,
            "é", 5,
            "the quick brown fox", 2);

    /** Keys that are bytes rather than text, so the rule is pinned below the encoding too. */
    private static final byte[][] RAW_KEYS = {
            {},
            {0},
            {1, 2, 3},
            {(byte) 0xff, (byte) 0xff}};

    private static final int[] RAW_KEYS_OVER_FOUR_PARTITIONS = {0, 1, 2, 0};
    private static final int[] RAW_KEYS_OVER_SEVEN_PARTITIONS = {0, 3, 1, 5};

    @Test
    void routesKnownKeysToTheFrozenPartitionsOnEveryJvm() {
        Map<String, Integer> routedOverFour = routeAll(OVER_FOUR_PARTITIONS.keySet(), 4);
        Map<String, Integer> routedOverSeven = routeAll(OVER_SEVEN_PARTITIONS.keySet(), 7);

        assertEquals(OVER_FOUR_PARTITIONS, routedOverFour,
                "the mapping from a key to a partition is frozen: changing it splits one key's records"
                        + " across two partitions and their order with them");
        assertEquals(OVER_SEVEN_PARTITIONS, routedOverSeven,
                "and it is frozen for a partition count that is not a power of two as well");
    }

    @Test
    void routesKnownKeyBytesToTheFrozenPartitionsWhateverTheyDecodeTo() {
        List<Integer> routedOverFour = new ArrayList<>();
        List<Integer> routedOverSeven = new ArrayList<>();
        for (byte[] key : RAW_KEYS) {
            routedOverFour.add(PartitionRouter.partitionOfKey(key, 4));
            routedOverSeven.add(PartitionRouter.partitionOfKey(key, 7));
        }

        assertEquals(partitions(RAW_KEYS_OVER_FOUR_PARTITIONS), routedOverFour);
        assertEquals(partitions(RAW_KEYS_OVER_SEVEN_PARTITIONS), routedOverSeven,
                "a key is bytes, so a key of 0xffff routes by those bytes and not by any text they might be");
    }

    @Test
    void routesEveryRecordWithTheSameKeyToTheSamePartition() {
        PartitionRouter partitions = new PartitionRouter(4);
        PartitionRouter another = new PartitionRouter(4);

        int first = partitions.partitionFor("orders-99".getBytes(UTF_8));
        int again = partitions.partitionFor("orders-99".getBytes(UTF_8));
        int fromAnotherRouter = another.partitionFor("orders-99".getBytes(UTF_8));

        assertEquals(first, again, "a keyed record does not depend on what the router has done before");
        assertEquals(first, fromAnotherRouter, "nor on which router asks");
    }

    @Test
    void sendsRecordsWithNoKeyToEveryPartitionInTurn() {
        PartitionRouter partitions = new PartitionRouter(3);

        List<Integer> routed = new ArrayList<>();
        for (int record = 0; record < 7; record++) {
            routed.add(partitions.partitionFor(null));
        }

        assertEquals(List.of(0, 1, 2, 0, 1, 2, 0), routed,
                "a record with no key says nothing about where it belongs, so it takes the next partition");
    }

    @Test
    void keepsAKeyOfZeroBytesDistinctFromNoKeyAtAll() {
        PartitionRouter partitions = new PartitionRouter(4);

        int emptyKey = partitions.partitionFor(new byte[0]);
        int noKeyAtAll = partitions.partitionFor(null);
        int emptyKeyAgain = partitions.partitionFor(new byte[0]);

        assertEquals(emptyKey, emptyKeyAgain, "an empty key is a key, so it hashes like one");
        assertEquals(0, noKeyAtAll, "while a record with no key takes its turn");
        assertEquals(1, partitions.partitionFor(null), "and the turn moved on, which the empty key did not touch");
    }

    @Test
    void spreadsManyKeysOverEveryPartitionRatherThanPilingThemOnOne() {
        int partitionCount = 4;
        int[] recordsPerPartition = new int[partitionCount];

        for (int record = 0; record < 400; record++) {
            recordsPerPartition[PartitionRouter.partitionOfKey(("key-" + record).getBytes(UTF_8), partitionCount)]++;
        }

        for (int partition = 0; partition < partitionCount; partition++) {
            assertTrue(recordsPerPartition[partition] > 0, "partition " + partition + " was given no records at all"
                    + " out of 400 keys, which is a hash that does not spread: "
                    + Arrays.toString(recordsPerPartition));
        }
    }

    @Test
    void refusesAPartitionCountBelowOne() {
        IllegalArgumentException noPartitions = assertThrows(IllegalArgumentException.class,
                () -> new PartitionRouter(0));
        IllegalArgumentException negative = assertThrows(IllegalArgumentException.class,
                () -> PartitionRouter.partitionOfKey("a".getBytes(UTF_8), -1));

        assertTrue(noPartitions.getMessage().contains("partitionCount"), noPartitions.getMessage());
        assertTrue(negative.getMessage().contains("partitionCount"), negative.getMessage());
    }

    @Test
    void refusesToMapAKeyThatIsNotThere() {
        assertThrows(NullPointerException.class, () -> PartitionRouter.partitionOfKey(null, 4),
                "a record with no key takes a turn, which is a router's business rather than the mapping's");
    }

    private static Map<String, Integer> routeAll(Iterable<String> keys, int partitionCount) {
        PartitionRouter partitions = new PartitionRouter(partitionCount);

        Map<String, Integer> routed = new LinkedHashMap<>();
        for (String key : keys) {
            routed.put(key, partitions.partitionFor(key.getBytes(UTF_8)));
        }
        return routed;
    }

    private static List<Integer> partitions(int[] partitions) {
        List<Integer> boxed = new ArrayList<>(partitions.length);
        for (int partition : partitions) {
            boxed.add(partition);
        }
        return boxed;
    }
}
