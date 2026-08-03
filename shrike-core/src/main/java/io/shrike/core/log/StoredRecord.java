package io.shrike.core.log;

/**
 * A record read back out of the log, carrying the offset and timestamp the log assigned when it was
 * appended. The arrays are freshly allocated by the read, so the caller owns them.
 *
 * <p>Like {@link ProducedRecord} this keeps the array-identity {@code equals} that
 * {@code java.lang.Record} generates; compare the components, not the record.
 *
 * @param offset          the logical record number this record was appended at
 * @param timestampMillis epoch milliseconds read from the broker's time source at append time
 * @param key             the record key, or {@code null} when the record was appended without one
 * @param value           the payload, never {@code null}
 */
public record StoredRecord(long offset, long timestampMillis, byte[] key, byte[] value) {
}
