package io.shrike.clients;

import java.util.Objects;
import java.util.zip.CRC32C;

/**
 * Which partition of a topic a record belongs to, decided on this side of the wire.
 *
 * <p><strong>The rule.</strong> A record with a key goes to
 * {@code Math.floorMod((int) crc32c(key), partitionCount)}, so the same key lands on the same
 * partition every time, on every machine, in every JVM — which is what keeps one key's records in one
 * partition and therefore in order. A record with no key goes to each partition in turn, because
 * nothing about it says where it belongs and spreading it evenly is the only honest answer. A caller
 * that already knows the partition does not come here at all: it names the partition, and
 * {@link ShrikeProducer#send(String, int, byte[], byte[])} sends it there.
 *
 * <p><strong>The broker does not route.</strong> A produce request has always carried an explicit
 * partition and still does; nothing about this class is on the wire. That is why the mapping has to be
 * stable rather than merely deterministic-per-run, and why {@code PartitionRouterTest} pins it to a
 * table of frozen vectors: a routing rule that changed between releases would silently split one key's
 * records across two partitions, and their order with it.
 *
 * <p><strong>CRC32C is reused deliberately.</strong> It is already what every record frame is
 * checksummed with, so it is a dependency this repository has taken and tested rather than a second
 * hash function to justify. DESIGN.md records that decision and what was rejected.
 *
 * <p>A router is not safe to share between threads, for the same reason a {@link BrokerConnection} is
 * not: the turn a keyless record takes is a field. Two threads that produce use two routers.
 */
public final class PartitionRouter {

    private final int partitionCount;

    /**
     * The partition the next record without a key goes to, counted from 0 so that a run of keyless
     * records spreads the same way every time it is replayed.
     */
    // confined to: the one thread that owns this router, like the connection it routes for
    private int nextPartitionWithoutKey;

    /**
     * @param partitionCount how many partitions the topic has, which is fixed when the topic is
     *                       created
     * @throws IllegalArgumentException if the count is below 1
     */
    public PartitionRouter(int partitionCount) {
        requireAtLeastOnePartition(partitionCount);
        this.partitionCount = partitionCount;
    }

    /**
     * @return how many partitions this router spreads records over
     */
    public int partitionCount() {
        return partitionCount;
    }

    /**
     * The partition one record belongs to.
     *
     * @param key the record key, or {@code null} for a record with no key, which takes the next
     *            partition in turn. An empty array is a key of zero bytes and is hashed like any other
     * @return the partition to send it to
     */
    public int partitionFor(byte[] key) {
        if (key == null) {
            int partition = nextPartitionWithoutKey;
            nextPartitionWithoutKey = partition + 1 == partitionCount ? 0 : partition + 1;
            return partition;
        }
        return partitionOfKey(key, partitionCount);
    }

    /**
     * The frozen mapping from a key to a partition: the whole of the rule for a record that has a key,
     * with no state behind it, so a caller can ask where a key would go without holding a router.
     *
     * <p>{@link Math#floorMod(int, int)} rather than {@code %} because a CRC32C read as an {@code int}
     * is negative for about half of all keys, and {@code %} would answer those with a negative
     * partition.
     *
     * @param key            the record key, never {@code null}
     * @param partitionCount how many partitions the topic has
     * @return the partition that key belongs to
     * @throws IllegalArgumentException if the count is below 1
     */
    public static int partitionOfKey(byte[] key, int partitionCount) {
        Objects.requireNonNull(key, "key");
        requireAtLeastOnePartition(partitionCount);

        CRC32C checksum = new CRC32C();
        checksum.update(key, 0, key.length);
        // Narrowed to an int, which is how this codebase already stores a CRC32C in a record frame.
        return Math.floorMod((int) checksum.getValue(), partitionCount);
    }

    private static void requireAtLeastOnePartition(int partitionCount) {
        if (partitionCount < 1) {
            throw new IllegalArgumentException("partitionCount must be at least 1, because every topic has at least"
                    + " one partition, but was " + partitionCount);
        }
    }
}
