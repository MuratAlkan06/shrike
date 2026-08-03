package io.shrike.admin;

import java.util.Objects;

/**
 * The topic name or group id in the path is not one the protocol will carry.
 *
 * <p>The rule belongs to the broker — a name becomes a directory or a file there, so it is checked in
 * the one place names turn into paths — and the request records apply it before a byte reaches a
 * socket. This type is what says that a rejection came from that rule and from nothing else, so that
 * an {@link IllegalArgumentException} raised anywhere else is answered as the internal failure it is
 * rather than blamed on the caller.
 *
 * <p>Its message is the rule's own, which is a sentence about the rule and a quoted, cut-down copy of
 * what the caller sent. That is the one piece of detail an error body here carries, and it is the
 * caller's own input coming back.
 */
public final class UnusableNameException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param broken what the name rule threw
     */
    public UnusableNameException(IllegalArgumentException broken) {
        super(Objects.requireNonNull(broken, "broken").getMessage(), broken);
    }
}
