package io.shrike.core.log;

/**
 * A read asked for an offset the log does not hold: a negative one, one retention has already
 * deleted, or one at or past the high-water mark. The exception carries the range that was readable
 * when the read was refused, and its lower end — the log start offset — is the number a consumer that
 * fell behind retention needs in order to reset deliberately rather than guess.
 */
public final class OffsetOutOfRangeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String topic;
    private final int partition;
    private final long requestedOffset;
    private final long logStartOffset;
    private final long nextOffset;

    public OffsetOutOfRangeException(String topic, int partition, long requestedOffset, long logStartOffset,
                                     long nextOffset) {
        super("offset " + requestedOffset + " is outside the readable range [" + logStartOffset + ", " + nextOffset
                + ") of topic=" + topic + " partition=" + partition);
        this.topic = topic;
        this.partition = partition;
        this.requestedOffset = requestedOffset;
        this.logStartOffset = logStartOffset;
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
     * @return the lowest offset the log can still serve, inclusive, as of the moment the read was
     *         refused. Retention only ever moves it forward, so a consumer that resets to it is
     *         resetting to the oldest record that still exists rather than to one that was deleted
     */
    public long logStartOffset() {
        return logStartOffset;
    }

    /**
     * @return the offset the next append will take, which is the exclusive end of the readable range
     */
    public long nextOffset() {
        return nextOffset;
    }
}
