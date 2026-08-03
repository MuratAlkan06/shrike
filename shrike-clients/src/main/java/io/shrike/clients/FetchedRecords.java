package io.shrike.clients;

import io.shrike.core.log.StoredRecord;
import java.util.List;
import java.util.Objects;

/**
 * What one fetch came back with: the records it read and how far the partition had got by then.
 *
 * <p>The high-water mark is the offset the partition will append next, so a consumer that has read up
 * to it is caught up, and {@code fetchOffset + records.size()} against it is the answer to "is there
 * more?" without a second question.
 *
 * <p>Every record here was parsed from the frame the broker copied out of its log, so the offset and
 * timestamp are the ones the log stored rather than numbers this client counted.
 *
 * @param highWaterMark the offset the partition will append next
 * @param records       the records read, in ascending offset order, possibly none
 */
public record FetchedRecords(long highWaterMark, List<StoredRecord> records) {

    public FetchedRecords {
        if (highWaterMark < 0) {
            throw new IllegalArgumentException("highWaterMark must not be negative, but was " + highWaterMark);
        }
        Objects.requireNonNull(records, "records");
        records = List.copyOf(records);
    }

    /**
     * @param fetchOffset the offset this fetch asked for
     * @return the offset to ask for next: one past the last record read, or the offset that was asked
     *         for when there were none. A consumer commits this number, because a committed offset is
     *         the next offset to read
     */
    public long nextOffset(long fetchOffset) {
        if (fetchOffset < 0) {
            throw new IllegalArgumentException("fetchOffset must not be negative, but was " + fetchOffset);
        }
        return records.isEmpty() ? fetchOffset : records.get(records.size() - 1).offset() + 1;
    }
}
