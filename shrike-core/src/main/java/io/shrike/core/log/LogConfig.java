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
 * @param retentionMs        {@code retention.ms}: how long a sealed segment is kept after the largest
 *                           record timestamp it holds, or {@link #RETENTION_DISABLED} to keep every
 *                           segment however old it is. The age is measured from the timestamps inside
 *                           the records and never from a file's modification time, which a copy or a
 *                           restore resets while the records do not change.
 * @param retentionBytes     {@code retention.bytes}: how many bytes of log files one partition may
 *                           hold before its oldest sealed segments are deleted, or
 *                           {@link #RETENTION_DISABLED} to keep every segment however large the
 *                           partition grows. Only whole sealed segments are deleted, and the segment
 *                           still taking appends is never one of them, so a partition can sit above
 *                           this number by the size of its active segment.
 */
public record LogConfig(int maxRecordBytes, int segmentBytes, int indexIntervalBytes, long retentionMs,
                        long retentionBytes) {

    /** One mebibyte: the default {@code max.record.bytes}. */
    public static final int DEFAULT_MAX_RECORD_BYTES = 1024 * 1024;

    /** 128 mebibytes: the default {@code segment.bytes}. */
    public static final int DEFAULT_SEGMENT_BYTES = 128 * 1024 * 1024;

    /** Four kibibytes: the default {@code index.interval.bytes}. */
    public static final int DEFAULT_INDEX_INTERVAL_BYTES = 4096;

    /**
     * What either retention bound is set to when it deletes nothing. A bound of 0 is a bound: it
     * deletes every sealed segment there is, which is why "off" needs a number outside the range
     * rather than the smallest number in it.
     */
    public static final long RETENTION_DISABLED = -1L;

    /** Off: a log deletes nothing on age unless it is asked to. */
    public static final long DEFAULT_RETENTION_MS = RETENTION_DISABLED;

    /** Off: a log deletes nothing on size unless it is asked to. */
    public static final long DEFAULT_RETENTION_BYTES = RETENTION_DISABLED;

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
        if (retentionMs < RETENTION_DISABLED) {
            throw new IllegalArgumentException("retentionMs must be " + RETENTION_DISABLED + " to keep every segment,"
                    + " or a number of milliseconds that is not negative, but was " + retentionMs);
        }
        if (retentionBytes < RETENTION_DISABLED) {
            throw new IllegalArgumentException("retentionBytes must be " + RETENTION_DISABLED + " to keep every"
                    + " segment, or a number of bytes that is not negative, but was " + retentionBytes);
        }
    }

    /**
     * The same sizes with both retention bounds off. Retention is the one setting here that deletes
     * stored records, so the shorter constructor is the one that deletes none of them: a caller that
     * says nothing about retention gets the behaviour every log had before retention existed.
     *
     * @param maxRecordBytes     {@code max.record.bytes}
     * @param segmentBytes       {@code segment.bytes}
     * @param indexIntervalBytes {@code index.interval.bytes}
     */
    public LogConfig(int maxRecordBytes, int segmentBytes, int indexIntervalBytes) {
        this(maxRecordBytes, segmentBytes, indexIntervalBytes, DEFAULT_RETENTION_MS, DEFAULT_RETENTION_BYTES);
    }

    /**
     * @return whether this configuration deletes sealed segments once they are old enough
     */
    public boolean deletesOnAge() {
        return retentionMs != RETENTION_DISABLED;
    }

    /**
     * @return whether this configuration deletes sealed segments once the partition is large enough
     */
    public boolean deletesOnSize() {
        return retentionBytes != RETENTION_DISABLED;
    }

    /**
     * @return the configuration a log gets when the caller names no numbers: a 1 MiB record bound,
     *         128 MiB segments, an index entry every 4 KiB, and retention off in both directions
     */
    public static LogConfig defaults() {
        return new LogConfig(DEFAULT_MAX_RECORD_BYTES, DEFAULT_SEGMENT_BYTES, DEFAULT_INDEX_INTERVAL_BYTES,
                DEFAULT_RETENTION_MS, DEFAULT_RETENTION_BYTES);
    }
}
