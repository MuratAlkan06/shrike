package io.shrike.core.protocol;

import io.shrike.core.log.ProducedRecord;
import java.util.List;
import java.util.Objects;

/**
 * Append records to the end of one partition, in the order they appear here.
 *
 * <p>The partition number is not checked: a partition that does not exist is a question about what
 * the broker holds, and the broker answers it with {@link ErrorCode#UNKNOWN_TOPIC_OR_PARTITION}. What
 * is checked here is what the bytes themselves can be wrong about.
 *
 * <p>Like {@link ProducedRecord} this keeps the array-identity {@code equals} that
 * {@code java.lang.Record} generates; compare the components, not the record.
 *
 * @param topic     the topic to append to, which must be a {@link SafeName}
 * @param partition the partition of that topic
 * @param records   the records to append, {@value #MIN_RECORD_COUNT} to {@value #MAX_RECORD_COUNT} of
 *                  them, each with a value and with or without a key
 */
public record ProduceRequest(String topic, int partition, List<ProducedRecord> records) implements Request {

    /** A request that appends nothing has nothing to be answered with, so it is not a request. */
    public static final int MIN_RECORD_COUNT = 1;

    /**
     * The most records one request may carry. It is a bound on what a single frame can make the
     * broker build before it has appended anything, and it is checked against the bytes in hand
     * before a list is sized to it.
     */
    public static final int MAX_RECORD_COUNT = 10_000;

    public ProduceRequest {
        SafeName.require(topic, "topic");
        Objects.requireNonNull(records, "records");
        if (records.size() < MIN_RECORD_COUNT || records.size() > MAX_RECORD_COUNT) {
            throw new IllegalArgumentException("a produce request carries " + MIN_RECORD_COUNT + " to "
                    + MAX_RECORD_COUNT + " records, but this one carries " + records.size());
        }
        records = List.copyOf(records);
        for (ProducedRecord record : records) {
            Objects.requireNonNull(record.value(), "a produced record's value");
        }
    }

    @Override
    public short apiKey() {
        return ApiKeys.PRODUCE;
    }
}
