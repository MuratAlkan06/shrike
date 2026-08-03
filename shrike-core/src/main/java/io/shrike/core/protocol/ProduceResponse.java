package io.shrike.core.protocol;

/**
 * The offset the first record of a produce request was appended at. The rest followed it in order, so
 * a producer that sent {@code n} records knows every offset it was given from this one number.
 *
 * @param baseOffset the logical record number of the first record appended
 */
public record ProduceResponse(long baseOffset) implements Response {

    public ProduceResponse {
        if (baseOffset < 0) {
            throw new IllegalArgumentException("baseOffset must not be negative, but was " + baseOffset);
        }
    }

    @Override
    public short apiKey() {
        return ApiKeys.PRODUCE;
    }
}
