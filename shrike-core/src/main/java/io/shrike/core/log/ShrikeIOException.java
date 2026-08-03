package io.shrike.core.log;

import java.io.IOException;
import java.util.Objects;

/**
 * The filesystem refused an operation the log needed. The cause is always the {@link IOException}
 * that was thrown, and the message names the file, so an unchecked type never loses the detail a
 * checked one would have carried.
 */
public final class ShrikeIOException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ShrikeIOException(String message, IOException cause) {
        super(Objects.requireNonNull(message, "message"), Objects.requireNonNull(cause, "cause"));
    }
}
