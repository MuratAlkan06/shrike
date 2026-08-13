package io.shrike.admin;

import io.shrike.core.log.SafeName;
import java.util.Objects;

/**
 * The topic name or group id in the path is not one the protocol will carry.
 *
 * <p>The rule belongs to the broker — a name becomes a directory or a file there, so it is checked in
 * the one place names turn into paths — and this facade applies that same one rule, from the same
 * class, to the name in the path. This type is what says that a rejection came from that rule and from
 * nothing else, so that an {@link IllegalArgumentException} raised anywhere else is answered as the
 * internal failure it is rather than blamed on the caller.
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

    /**
     * Judges a name out of a request path, before anything is asked of the broker.
     *
     * <p>The rule is {@link SafeName}'s and is not restated here: a second copy of it is a second
     * chance to be more generous than the first, which is the reason that class says there is one of
     * it. What this adds is <em>when</em> it runs — in front of the connection rather than inside the
     * request record that would have travelled over it — so that a name this facade can already see is
     * unusable costs no socket, and is answered 400 rather than 503 when the broker happens to be down.
     *
     * @param name  the name as it arrived in the path
     * @param field what that name was meant to be, quoted in the refusal: {@code topic} or
     *              {@code groupId}, the same words the broker's own request records use
     * @throws UnusableNameException if the name is not one the protocol will carry
     */
    static void requireUsable(String name, String field) {
        try {
            SafeName.require(name, field);
        } catch (IllegalArgumentException unusable) {
            throw new UnusableNameException(unusable);
        }
    }
}
