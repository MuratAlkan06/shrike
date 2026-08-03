package io.shrike.core.group;

/**
 * This commit would push the broker past the consumer groups it may hold.
 *
 * <p>A commit is what creates a group — there is no create-group api — and the group it creates is a
 * file under {@code groups/} and an entry in memory that nothing deletes, so the cost of one commit
 * outlives the connection that sent it and every restart after it. The budget is
 * {@code BrokerConfig.maxTotalGroups()} and it stops the creation of new groups only: a group this
 * broker already holds commits whatever the budget says.
 *
 * <p>Public where {@code TooManyPartitionsException} is package-private, and for the one reason a
 * package boundary gives: this is thrown in {@code io.shrike.core.group} and caught in
 * {@code io.shrike.core.net}, where it becomes an {@code INVALID_REQUEST}. Only this package can raise
 * it, because the constructor is package-private.
 */
public final class TooManyGroupsException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    TooManyGroupsException(String group, int groupCount, int maxTotalGroups) {
        super("committing for group " + group + " would create it, but this broker already holds " + groupCount
                + " of the " + maxTotalGroups + " groups it may hold");
    }
}
