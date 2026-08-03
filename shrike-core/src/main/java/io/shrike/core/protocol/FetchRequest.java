package io.shrike.core.protocol;

import io.shrike.core.log.SafeName;

/**
 * Read records from one partition, starting at an offset.
 *
 * <p>The offset is not checked here: an offset outside what a partition can serve is a question about
 * what the broker holds, and the broker answers it with {@link ErrorCode#OFFSET_OUT_OF_RANGE}. The
 * three sizes are checked, because a negative one is a statement about the request itself and can
 * only be a mistake or an attempt at one.
 *
 * @param topic       the topic to read from, which must be a {@link SafeName}
 * @param partition   the partition of that topic
 * @param fetchOffset the logical record number to start at
 * @param maxBytes    the most bytes of records the response may carry
 * @param maxWaitMs   how long the broker may hold the request open waiting for records
 * @param minBytes    how many bytes of records are worth answering before that wait is up
 */
public record FetchRequest(String topic, int partition, long fetchOffset, int maxBytes, int maxWaitMs, int minBytes)
        implements Request {

    public FetchRequest {
        SafeName.require(topic, "topic");
        requireNotNegative(maxBytes, "maxBytes");
        requireNotNegative(maxWaitMs, "maxWaitMs");
        requireNotNegative(minBytes, "minBytes");
    }

    @Override
    public short apiKey() {
        return ApiKeys.FETCH;
    }

    private static void requireNotNegative(int value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " must not be negative, but was " + value);
        }
    }
}
