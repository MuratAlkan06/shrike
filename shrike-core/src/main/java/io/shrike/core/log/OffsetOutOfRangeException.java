package io.shrike.core.log;

/**
 * A read asked for an offset the log does not hold: a negative one, or one at or past the high-water
 * mark. The exception carries the range that was readable when the read was refused.
 */
public final class OffsetOutOfRangeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String topic;
    private final int partition;
    private final long requestedOffset;
    private final long firstOffset;
    private final long nextOffset;

    public OffsetOutOfRangeException(String topic, int partition, long requestedOffset, long firstOffset, long nextOffset) {
        super("offset " + requestedOffset + " is outside the readable range [" + firstOffset + ", " + nextOffset
                + ") of topic=" + topic + " partition=" + partition);
        this.topic = topic;
        this.partition = partition;
        this.requestedOffset = requestedOffset;
        this.firstOffset = firstOffset;
        this.nextOffset = nextOffset;
    }

    public String topic() {
        return topic;
    }

    public int partition() {
        return partition;
    }

    public long requestedOffset() {
        return requestedOffset;
    }

    /**
     * @return the lowest offset the log can still serve, inclusive
     */
    public long firstOffset() {
        return firstOffset;
    }

    /**
     * @return the offset the next append will take, which is the exclusive end of the readable range
     */
    public long nextOffset() {
        return nextOffset;
    }
}
