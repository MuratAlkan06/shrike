package io.shrike.core.log;

import java.util.Locale;

/**
 * The rule for a name that arrives over the network and becomes part of a path on disk. A topic name
 * and a group id both go through it, and there is exactly one of it, because a second copy is a
 * second chance to be more generous than the first. It lives in this package because this is where a
 * name turns into a path — {@code SegmentedLog} resolves a partition directory out of one — and a rule
 * about paths belongs beside the code that makes them.
 *
 * <p>A name is 1 to {@link #MAX_LENGTH_CHARS} characters of {@code [A-Za-z0-9._-]}, and is neither
 * {@code "."} nor {@code ".."}. That leaves no separator, no {@code NUL}, no drive letter, and no
 * parent directory, so a name that passes cannot name a file outside the directory it was resolved
 * against. The check runs on the decoded string rather than on the bytes: every legal character is
 * one ASCII byte, and any byte sequence that is not legal UTF-8 decodes to a replacement character
 * that the character set refuses.
 *
 * <p>Two names that differ only in case are one name here: see {@link #fold(String)}.
 */
public final class SafeName {

    /** The longest a name may be. Every legal character is one ASCII byte, so this bounds bytes too. */
    public static final int MAX_LENGTH_CHARS = 200;

    private SafeName() {
    }

    /**
     * @param name the decoded name, as it arrived
     * @return whether the name is one this broker will accept as a path component
     */
    public static boolean isValid(String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_LENGTH_CHARS) {
            return false;
        }
        if (name.equals(".") || name.equals("..")) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            if (!isAllowed(name.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Refuses a name that breaks the rule, naming the field it came from.
     *
     * @param name  the decoded name, as it arrived
     * @param field what the name was meant to be, quoted in the failure
     * @throws IllegalArgumentException if the name is not a valid one
     */
    public static void require(String name, String field) {
        if (!isValid(name)) {
            throw new IllegalArgumentException(field + " must be 1 to " + MAX_LENGTH_CHARS
                    + " characters of [A-Za-z0-9._-] and neither \".\" nor \"..\", but was " + quote(name));
        }
    }

    /**
     * Folds a name to the identity it is stored and looked up under.
     *
     * <p>A name becomes a path — {@code <topic>-<partition>/} for a partition, {@code <groupId>.offsets}
     * for a group — and a path on APFS or on Windows does not tell two casings apart. So identity here
     * is at most as fine-grained as the filesystem's: {@code orders} and {@code Orders} are one topic
     * everywhere, on every filesystem, rather than two topics that share one directory on some of them.
     * Every legal character is ASCII, and {@link Locale#ROOT} is what keeps the fold from depending on
     * the machine's locale.
     *
     * @param name a name that has already passed {@link #isValid(String)}
     * @return the identity two names that differ only in case share
     */
    public static String fold(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static boolean isAllowed(char character) {
        return (character >= 'a' && character <= 'z')
                || (character >= 'A' && character <= 'Z')
                || (character >= '0' && character <= '9')
                || character == '.' || character == '_' || character == '-';
    }

    /**
     * Quotes a rejected name for a message. A rejected name is hostile input on its way into a log
     * line, so it is cut to the length the rule allows and anything that could forge a line of its
     * own is replaced rather than printed.
     */
    private static String quote(String name) {
        if (name == null) {
            return "null";
        }
        int shownChars = Math.min(name.length(), MAX_LENGTH_CHARS);
        StringBuilder quoted = new StringBuilder(shownChars + 2).append('"');
        for (int i = 0; i < shownChars; i++) {
            char character = name.charAt(i);
            quoted.append(character >= 0x20 && character < 0x7f ? character : '?');
        }
        quoted.append('"');
        if (name.length() > shownChars) {
            quoted.append(" cut from ").append(name.length()).append(" characters");
        }
        return quoted.toString();
    }
}
