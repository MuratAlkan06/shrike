package io.shrike.core.protocol;

/**
 * A response body this build sends. The envelope around it — length, correlation id, and error code —
 * is {@link ResponseFrame}'s business.
 *
 * <p>Only a response whose error code is {@link ErrorCode#NONE} has a body at all: an error is the
 * whole answer, so its body is empty and there is no record here for it.
 */
public sealed interface Response permits ProduceResponse, FetchResponse, CommitOffsetResponse, CreateTopicResponse {

    /**
     * @return the api key of the request this answers, which the envelope does not repeat
     */
    short apiKey();
}
