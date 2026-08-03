package io.shrike.core.protocol;

import java.util.Objects;

/**
 * The bytes inside a frame do not follow the wire format: a field runs past the end of the frame, a
 * declared length is negative, or a count is outside what the protocol allows.
 *
 * <p>It never leaves this package. It is how a check deep inside a body reaches the one place that
 * decides what a caller is owed for it — a refusal carrying {@link ErrorCode#INVALID_REQUEST} on the
 * request side, a broken-frame verdict on the response side — without every field check having to
 * carry that decision with it.
 */
final class WireFormatException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    WireFormatException(String detail) {
        super(Objects.requireNonNull(detail, "detail"));
    }
}
