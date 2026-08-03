package io.shrike.clients;

/**
 * Where the broker put a record it accepted: the partition it went to and the offset it was appended
 * at.
 *
 * <p>It answers a routed send, because a caller that let a {@link PartitionRouter} pick the partition
 * does not otherwise know which one that was — and a partition and an offset together are what names a
 * record. A caller that named the partition itself is answered with the offset alone, since that is
 * the only half it did not already know.
 *
 * @param partition the partition the record was appended to
 * @param offset    the offset it was appended at within that partition
 */
public record SentRecord(int partition, long offset) {

    public SentRecord {
        if (partition < 0) {
            throw new IllegalArgumentException("partition must not be negative, but was " + partition);
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative, but was " + offset);
        }
    }
}
