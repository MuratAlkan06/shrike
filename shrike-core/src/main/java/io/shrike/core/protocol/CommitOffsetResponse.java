package io.shrike.core.protocol;

/**
 * The commit was stored. There is nothing to say beyond that, so the body is empty and the error code
 * in the envelope carries the whole answer.
 */
public record CommitOffsetResponse() implements Response {

    @Override
    public short apiKey() {
        return ApiKeys.COMMIT_OFFSET;
    }
}
