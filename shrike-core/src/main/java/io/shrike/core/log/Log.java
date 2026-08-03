package io.shrike.core.log;

import java.io.Closeable;

/**
 * The append-only record log of one topic partition. Records go in at the end and come back out by
 * offset, where an offset is a logical record number and never a byte count.
 *
 * <p>A log has a single writer. Nothing here is safe to call from two threads at once.
 */
public interface Log extends Closeable {

    /**
     * @return the topic this log stores
     */
    String topic();

    /**
     * @return the partition of that topic this log stores; 0 is a partition like any other
     */
    int partition();

    /**
     * Appends one record to the end of the log.
     *
     * @param record the record to store; its value must not be {@code null}
     * @return the offset assigned to the record, one higher than the previous append and 0 for the
     *         first record in the log
     * @throws NullPointerException   if the record or its value is {@code null}, because a null value
     *                                would be a tombstone and compaction is a non-goal
     * @throws RecordTooLargeException if the framed record would exceed {@code max.record.bytes}
     * @throws ShrikeIOException       if the write fails
     */
    long append(ProducedRecord record);

    /**
     * Reads the record stored at {@code offset}.
     *
     * @param offset the logical record number to read
     * @return the stored record, with the offset and timestamp it was appended with
     * @throws OffsetOutOfRangeException if the offset is negative or is at or past {@link #nextOffset()}
     * @throws CorruptRecordException    if the bytes on disk no longer match their checksum
     * @throws ShrikeIOException         if the read fails
     */
    StoredRecord read(long offset);

    /**
     * @return the offset the next append will take, which is also the exclusive upper bound of the
     *         readable offsets: the high-water mark
     */
    long nextOffset();

    /**
     * Closes the log's file. Implementations state in their own documentation what closing means for
     * durability.
     *
     * @throws ShrikeIOException if the file cannot be closed
     */
    @Override
    void close();
}
