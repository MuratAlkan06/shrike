package io.shrike.core.log;

/**
 * The numbers one partition's log is opened with. Every field is validated here, so a log that holds
 * a {@code LogConfig} holds one that already makes sense.
 *
 * @param maxRecordBytes     {@code max.record.bytes}: the most bytes one record may occupy on disk,
 *                           framing included. A read allocates at most this much for a frame, so the
 *                           append side and the read side agree on one number.
 * @param segmentBytes       {@code segment.bytes}: the size a segment may reach before the next
 *                           record starts a new one. A record that would push a non-empty segment
 *                           past this bound goes to the next segment instead; an empty segment
 *                           accepts any record {@code maxRecordBytes} allows, because a record that
 *                           fits no segment could never be stored at all.
 * @param indexIntervalBytes {@code index.interval.bytes}: how many bytes of appended data go by
 *                           between two entries of a segment's sparse offset index.
 */
public record LogConfig(int maxRecordBytes, int segmentBytes, int indexIntervalBytes) {

    /** One mebibyte: the default {@code max.record.bytes}. */
    public static final int DEFAULT_MAX_RECORD_BYTES = 1024 * 1024;

    /** 128 mebibytes: the default {@code segment.bytes}. */
    public static final int DEFAULT_SEGMENT_BYTES = 128 * 1024 * 1024;

    /** Four kibibytes: the default {@code index.interval.bytes}. */
    public static final int DEFAULT_INDEX_INTERVAL_BYTES = 4096;

    /**
     * One gibibyte: the hard cap on both {@code segment.bytes} and {@code max.record.bytes}, and so
     * on the size any one segment can reach. An index entry stores a byte position in an int32, and
     * this cap is what keeps every position inside a segment far below that field's limit.
     */
    public static final int MAX_SEGMENT_BYTES = 1024 * 1024 * 1024;

    public LogConfig {
        long smallestPossibleRecordBytes = RecordFrame.frameBytes(RecordFrame.NULL_KEY_LENGTH, 0);
        if (maxRecordBytes < smallestPossibleRecordBytes) {
            throw new IllegalArgumentException("maxRecordBytes must be at least " + smallestPossibleRecordBytes
                    + ", the size of an empty record's frame, but was " + maxRecordBytes);
        }
        if (maxRecordBytes > MAX_SEGMENT_BYTES) {
            throw new IllegalArgumentException("maxRecordBytes must not exceed " + MAX_SEGMENT_BYTES
                    + ", so that every byte position inside a segment fits an index entry's int32 field, but was "
                    + maxRecordBytes);
        }
        if (segmentBytes < smallestPossibleRecordBytes) {
            throw new IllegalArgumentException("segmentBytes must be at least " + smallestPossibleRecordBytes
                    + ", the size of an empty record's frame, but was " + segmentBytes);
        }
        if (segmentBytes > MAX_SEGMENT_BYTES) {
            throw new IllegalArgumentException("segmentBytes must not exceed " + MAX_SEGMENT_BYTES
                    + ", so that every byte position inside a segment fits an index entry's int32 field, but was "
                    + segmentBytes);
        }
        if (indexIntervalBytes < 1) {
            throw new IllegalArgumentException("indexIntervalBytes must be at least 1, but was " + indexIntervalBytes);
        }
    }

    /**
     * @return the configuration a log gets when the caller names no numbers: a 1 MiB record bound,
     *         128 MiB segments, and an index entry every 4 KiB
     */
    public static LogConfig defaults() {
        return new LogConfig(DEFAULT_MAX_RECORD_BYTES, DEFAULT_SEGMENT_BYTES, DEFAULT_INDEX_INTERVAL_BYTES);
    }
}
