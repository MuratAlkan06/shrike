package io.shrike.core.log;

/**
 * An append was refused because the record's frame would occupy more bytes on disk than
 * {@code max.record.bytes} allows. The same bound sizes the largest buffer a read will ever
 * allocate, so accepting the record would make the log unreadable.
 */
public final class RecordTooLargeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String topic;
    private final int partition;
    private final long recordBytes;
    private final int maxRecordBytes;

    public RecordTooLargeException(String topic, int partition, long recordBytes, int maxRecordBytes) {
        super("record framing to " + recordBytes + " bytes exceeds max.record.bytes=" + maxRecordBytes
                + " for topic=" + topic + " partition=" + partition);
        this.topic = topic;
        this.partition = partition;
        this.recordBytes = recordBytes;
        this.maxRecordBytes = maxRecordBytes;
    }

    public String topic() {
        return topic;
    }

    public int partition() {
        return partition;
    }

    /**
     * @return the bytes the rejected record would have occupied on disk, framing included
     */
    public long recordBytes() {
        return recordBytes;
    }

    public int maxRecordBytes() {
        return maxRecordBytes;
    }
}
