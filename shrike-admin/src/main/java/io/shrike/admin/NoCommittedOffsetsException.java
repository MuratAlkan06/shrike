package io.shrike.admin;

import java.util.Objects;

/**
 * The group asked about has committed nothing, so there is no lag to report.
 *
 * <p><strong>The name says exactly what is known.</strong> A commit is what brings a group into being
 * on this broker: a group that has committed nothing and a group that never existed are one state, all
 * the way down to the absence of one file, and the protocol answers both with no offsets rather than an
 * error. This facade answers 404, because over HTTP "there is nothing here under that name" is what a
 * 404 means — but the exception, and the message it carries, claim only what the broker actually said.
 */
public final class NoCommittedOffsetsException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param group the group that has committed nothing, which has already passed the name rule on its
     *              way to the broker
     */
    public NoCommittedOffsetsException(String group) {
        super("group has no committed offsets: " + Objects.requireNonNull(group, "group"));
    }
}
