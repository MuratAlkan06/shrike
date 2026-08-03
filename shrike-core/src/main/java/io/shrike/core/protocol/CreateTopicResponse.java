package io.shrike.core.protocol;

/**
 * The topic was created. The request named its partitions, so the response repeats nothing back: the
 * body is empty and the error code in the envelope carries the whole answer.
 */
public record CreateTopicResponse() implements Response {

    @Override
    public short apiKey() {
        return ApiKeys.CREATE_TOPIC;
    }
}
