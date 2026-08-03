package io.shrike.admin;

import java.util.Objects;

/**
 * The body of every answer this facade gives that is not a 200: one field, holding one plain sentence.
 *
 * <p>One shape for every failure is the point. A caller parses the same thing whatever went wrong, and
 * there is nowhere in the shape for a stack trace, an exception class, or a path on the broker's disk
 * to arrive — the detail behind a failure goes to this process's log, where an operator can read it,
 * and never onto the wire.
 *
 * @param error what went wrong, in one sentence a caller can show a person
 */
public record ErrorBody(String error) {

    public ErrorBody {
        Objects.requireNonNull(error, "error");
    }
}
