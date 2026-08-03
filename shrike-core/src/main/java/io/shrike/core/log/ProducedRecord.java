package io.shrike.core.log;

/**
 * A record handed to the log for appending. The producer supplies the bytes; the log assigns the
 * offset and stamps the timestamp, so neither appears here.
 *
 * <p>The arrays are held, not copied: appending is the hot path and copying every payload twice
 * buys nothing while the caller still owns the array. Do not mutate an array after passing it to
 * {@link Log#append}. For the same reason this record keeps the array-identity {@code equals} that
 * {@code java.lang.Record} generates; compare the components, not the record.
 *
 * @param key   the record key, or {@code null} when there is none; an empty array is a key of zero
 *              bytes and stays distinct from {@code null} on disk
 * @param value the payload; {@code null} is refused by {@link Log#append} because a null value
 *              would be a tombstone and compaction is a non-goal
 */
public record ProducedRecord(byte[] key, byte[] value) {
}
