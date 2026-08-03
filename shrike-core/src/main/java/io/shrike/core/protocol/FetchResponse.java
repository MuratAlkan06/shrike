package io.shrike.core.protocol;

import java.util.Objects;

/**
 * Records read from one partition, with the offset the partition would append next.
 *
 * <p>The records are a byte range of the partition's log, copied verbatim: the codec does not look
 * inside them on the way out and does not re-serialize them, so what a consumer receives is the frame
 * that is on disk. {@link WireRecords} is what reads them back.
 *
 * <p>The array is held, not copied, and this record keeps the array-identity {@code equals} that
 * {@code java.lang.Record} generates; compare the components, not the record.
 *
 * @param highWaterMark the offset the partition will append next, so a consumer can tell how far
 *                      behind it is
 * @param records       whole record frames, back to back, and never a partial one
 */
public record FetchResponse(long highWaterMark, byte[] records) implements Response {

    public FetchResponse {
        if (highWaterMark < 0) {
            throw new IllegalArgumentException("highWaterMark must not be negative, but was " + highWaterMark);
        }
        Objects.requireNonNull(records, "records");
    }

    @Override
    public short apiKey() {
        return ApiKeys.FETCH;
    }
}
