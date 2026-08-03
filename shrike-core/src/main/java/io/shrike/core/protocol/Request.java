package io.shrike.core.protocol;

/**
 * A request body this build understands. The envelope around it — length, api key, api version, and
 * correlation id — is {@link RequestFrame}'s business; a {@code Request} is what the body says.
 *
 * <p>Each of these records validates its own components, so a {@code Request} that exists is one the
 * protocol allows. That is the single place those rules live: the decoder builds a record out of what
 * came off the wire and turns a refusal into {@link ErrorCode#INVALID_REQUEST}, and a client that
 * builds one by hand meets the same rules before a byte is sent.
 */
public sealed interface Request permits ProduceRequest, FetchRequest, CommitOffsetRequest, CreateTopicRequest {

    /**
     * @return the api key that names this request on the wire
     */
    short apiKey();
}
