package io.shrike.core.protocol;

/**
 * The int16 that names an operation on the wire, and the versions this build speaks for each one.
 *
 * <p>Versioning is per api key rather than per protocol: the request envelope carries an
 * {@code apiVersion} beside its {@code apiKey}, and {@link #isSupportedVersion} is the one place that
 * decides whether this build understands that pair. Today every implemented key speaks version 0 and
 * nothing else, so the method is short; the day produce grows a version 1, fetch does not have to
 * move with it.
 *
 * <p>{@link #DESCRIBE_TOPICS} and {@link #DESCRIBE_GROUP} are numbers held in reserve so that a later
 * slice cannot accidentally spend them on something else. Nothing implements them, so a request
 * naming one is an invalid request today.
 */
public final class ApiKeys {

    /** Append records to one partition. */
    public static final short PRODUCE = 0;

    /** Read records from one partition, starting at an offset. */
    public static final short FETCH = 1;

    /** Store one group's committed offset for one partition. */
    public static final short COMMIT_OFFSET = 2;

    /** Create a topic with a fixed number of partitions. */
    public static final short CREATE_TOPIC = 3;

    /** Reserved for a later slice; not implemented, so a request naming it is refused. */
    public static final short DESCRIBE_TOPICS = 4;

    /** Reserved for a later slice; not implemented, so a request naming it is refused. */
    public static final short DESCRIBE_GROUP = 5;

    /** The only api version that exists: every implemented key speaks this one and no other. */
    public static final short VERSION_0 = 0;

    private ApiKeys() {
    }

    /**
     * @param apiKey the api key an envelope carries
     * @return whether this build implements that key at any version
     */
    public static boolean isImplemented(short apiKey) {
        return switch (apiKey) {
            case PRODUCE, FETCH, COMMIT_OFFSET, CREATE_TOPIC -> true;
            default -> false;
        };
    }

    /**
     * @param apiKey     the api key an envelope carries
     * @param apiVersion the version that envelope asks for
     * @return whether this build speaks that version of that key
     */
    public static boolean isSupportedVersion(short apiKey, short apiVersion) {
        return isImplemented(apiKey) && apiVersion == VERSION_0;
    }
}
