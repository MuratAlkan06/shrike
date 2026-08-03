package io.shrike.core.net;

import io.shrike.core.log.FlushMode;
import io.shrike.core.log.LogConfig;
import io.shrike.core.protocol.RequestReader;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What one broker process was told to do: the {@link BrokerConfig} to start, and the address to bind.
 *
 * <p>{@link BrokerConfig} is what a broker is built from; this is how a process comes to hold one. It
 * is read from environment variables and nothing else — no configuration file, no working-directory
 * lookup, no path this process guesses at. Nineteen variables, of which one is required, and one rule
 * names them: a setting's variable is {@code SHRIKE_} followed by the name {@link LogConfig} and
 * {@link BrokerConfig} give the field in their javadoc, upper-cased with each dot an underscore, so
 * {@code retention.ms} is read from {@code SHRIKE_RETENTION_MS}.
 *
 * <pre>
 * SHRIKE_DATA_DIRECTORY       required, no default: everything this broker stores lives under it
 * SHRIKE_PORT                 the TCP port to listen on, {@value #DEFAULT_PORT} by default
 * SHRIKE_READY_FILE           the file written once the broker is listening,
 *                             &lt;dataDirectory&gt;/{@value #DEFAULT_READY_FILE_NAME} by default
 * SHRIKE_BIND_ADDRESS         the interface to listen on, the loopback address by default
 * SHRIKE_RETENTION_MS         retention.ms
 * SHRIKE_RETENTION_BYTES      retention.bytes
 * SHRIKE_FLUSH_MODE           flush.mode, written per-record or interval in whatever letters
 * SHRIKE_FLUSH_INTERVAL_MS    flush.interval.ms
 * SHRIKE_FLUSH_INTERVAL_BYTES flush.interval.bytes
 * SHRIKE_SEGMENT_BYTES        segment.bytes
 * SHRIKE_MAX_RECORD_BYTES     max.record.bytes
 * SHRIKE_INDEX_INTERVAL_BYTES index.interval.bytes
 * SHRIKE_MAX_FETCH_WAIT_MS    max.fetch.wait.ms
 * SHRIKE_MAX_REQUEST_BYTES    max.request.bytes
 * SHRIKE_READ_TIMEOUT_MS      read.timeout.ms
 * SHRIKE_FETCH_ZERO_COPY      fetch.zero.copy, written true or false in whatever letters
 * SHRIKE_CONNECTION_CAP       connectionCap, which has no dotted name of its own
 * SHRIKE_MAX_TOTAL_PARTITIONS maxTotalPartitions, which has no dotted name of its own
 * SHRIKE_MAX_TOTAL_GROUPS     maxTotalGroups, which has no dotted name of its own
 * </pre>
 *
 * <p>A variable that is not set, or set to nothing at all, is one the default answers for — which is
 * what {@code docker run -e SHRIKE_PORT=} passes and what an operator means by it. Each of the
 * fifteen settings below the first four defaults to what {@link LogConfig#defaults()} and
 * {@link BrokerConfig#defaults(Path, int, Path)} give the field it sets, so a process that names none
 * of them starts the broker every deployment before them started. The data directory has no default
 * because a default would be a path this process picked rather than one somebody chose, and every
 * other path here derives from it.
 *
 * <p>Every value is checked here, before a socket is opened or a log is recovered, and a value that
 * does not make sense raises an {@link IllegalArgumentException} whose message is one sentence naming
 * the variable to fix. A value is never quietly traded for the default: what a variable says either
 * starts this broker or stops it. What happens here is the conversion from text — a whole number, one
 * of two spellings — while the bounds stay in the records that own them, where they run for every
 * caller those records have. A refusal from one of them is caught here and rethrown with the variable
 * that set the field in front of it, which is possible because one variable sets one field.
 *
 * @param config      the configuration the broker starts with
 * @param bindAddress the interface to listen on, which is the loopback address unless the environment
 *                    named another one
 */
public record BrokerLaunch(BrokerConfig config, InetAddress bindAddress) {

    /** Required: the directory every path this broker writes derives from. */
    public static final String DATA_DIRECTORY_VARIABLE = "SHRIKE_DATA_DIRECTORY";

    /** The TCP port to listen on. */
    public static final String PORT_VARIABLE = "SHRIKE_PORT";

    /** The file written once the broker is listening, holding the port it bound and its pid. */
    public static final String READY_FILE_VARIABLE = "SHRIKE_READY_FILE";

    /** The interface to listen on. Setting it past loopback is the whole of the opt-in. */
    public static final String BIND_ADDRESS_VARIABLE = "SHRIKE_BIND_ADDRESS";

    /** {@code retention.ms}, or {@link LogConfig#RETENTION_DISABLED} to keep every segment. */
    public static final String RETENTION_MS_VARIABLE = "SHRIKE_RETENTION_MS";

    /** {@code retention.bytes}, or {@link LogConfig#RETENTION_DISABLED} to keep every segment. */
    public static final String RETENTION_BYTES_VARIABLE = "SHRIKE_RETENTION_BYTES";

    /** {@code flush.mode}: {@code per-record} or {@code interval}, in whatever letters. */
    public static final String FLUSH_MODE_VARIABLE = "SHRIKE_FLUSH_MODE";

    /** {@code flush.interval.ms}. */
    public static final String FLUSH_INTERVAL_MS_VARIABLE = "SHRIKE_FLUSH_INTERVAL_MS";

    /** {@code flush.interval.bytes}. */
    public static final String FLUSH_INTERVAL_BYTES_VARIABLE = "SHRIKE_FLUSH_INTERVAL_BYTES";

    /** {@code segment.bytes}. */
    public static final String SEGMENT_BYTES_VARIABLE = "SHRIKE_SEGMENT_BYTES";

    /** {@code max.record.bytes}. */
    public static final String MAX_RECORD_BYTES_VARIABLE = "SHRIKE_MAX_RECORD_BYTES";

    /** {@code index.interval.bytes}. */
    public static final String INDEX_INTERVAL_BYTES_VARIABLE = "SHRIKE_INDEX_INTERVAL_BYTES";

    /** {@code max.fetch.wait.ms}. */
    public static final String MAX_FETCH_WAIT_MS_VARIABLE = "SHRIKE_MAX_FETCH_WAIT_MS";

    /** {@code max.request.bytes}. */
    public static final String MAX_REQUEST_BYTES_VARIABLE = "SHRIKE_MAX_REQUEST_BYTES";

    /** {@code read.timeout.ms}, which bounds reading and idling and never bounds serving. */
    public static final String READ_TIMEOUT_MS_VARIABLE = "SHRIKE_READ_TIMEOUT_MS";

    /** {@code fetch.zero.copy}: {@code true} or {@code false}, in whatever letters. */
    public static final String FETCH_ZERO_COPY_VARIABLE = "SHRIKE_FETCH_ZERO_COPY";

    /** The most connections served at once. */
    public static final String CONNECTION_CAP_VARIABLE = "SHRIKE_CONNECTION_CAP";

    /** The most partitions this broker will hold open across every topic. */
    public static final String MAX_TOTAL_PARTITIONS_VARIABLE = "SHRIKE_MAX_TOTAL_PARTITIONS";

    /** The most consumer groups this broker will hold committed offsets for. */
    public static final String MAX_TOTAL_GROUPS_VARIABLE = "SHRIKE_MAX_TOTAL_GROUPS";

    /**
     * Which variable set the field a record refused, keyed by the name that record's own sentence
     * begins with. Bounds are checked once, in {@link LogConfig} and {@link BrokerConfig}, for every
     * caller they have; this table is what lets a refusal from there arrive here as a sentence naming
     * the variable an operator edits. One variable sets one field, so which one that is never has to
     * be guessed at, and the fields no bound can refuse are not in the table.
     */
    private static final Map<String, String> VARIABLE_BY_FIELD = Map.ofEntries(
            Map.entry("port", PORT_VARIABLE),
            Map.entry("retentionMs", RETENTION_MS_VARIABLE),
            Map.entry("retentionBytes", RETENTION_BYTES_VARIABLE),
            Map.entry("flushIntervalMs", FLUSH_INTERVAL_MS_VARIABLE),
            Map.entry("flushIntervalBytes", FLUSH_INTERVAL_BYTES_VARIABLE),
            Map.entry("segmentBytes", SEGMENT_BYTES_VARIABLE),
            Map.entry("maxRecordBytes", MAX_RECORD_BYTES_VARIABLE),
            Map.entry("indexIntervalBytes", INDEX_INTERVAL_BYTES_VARIABLE),
            Map.entry("maxFetchWaitMs", MAX_FETCH_WAIT_MS_VARIABLE),
            Map.entry("maxRequestBytes", MAX_REQUEST_BYTES_VARIABLE),
            Map.entry("readTimeoutMs", READ_TIMEOUT_MS_VARIABLE),
            Map.entry("connectionCap", CONNECTION_CAP_VARIABLE),
            Map.entry("maxTotalPartitions", MAX_TOTAL_PARTITIONS_VARIABLE),
            Map.entry("maxTotalGroups", MAX_TOTAL_GROUPS_VARIABLE));

    /**
     * The port a broker listens on when nothing names another: 9750. It is not a registered port and
     * not one this build shares with anything else it ships, and it is the port {@code shrike-admin}
     * looks for a broker on.
     */
    public static final int DEFAULT_PORT = 9750;

    /** The ready file's name inside the data directory, when the environment names no other path. */
    public static final String DEFAULT_READY_FILE_NAME = "shrike.ready";

    public BrokerLaunch {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(bindAddress, "bindAddress");
    }

    /**
     * Reads one launch out of what a process was started with.
     *
     * @param arguments   the command line, which must be empty: this broker is configured by the
     *                    environment, and an argument it silently ignored would be a lie about that
     * @param environment the environment variables, ordinarily {@code System.getenv()}
     * @return the launch, already validated
     * @throws IllegalArgumentException if an argument was given, if the data directory is missing, or
     *                                  if any value is not one this broker can use
     */
    public static BrokerLaunch from(List<String> arguments, Map<String, String> environment) {
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(environment, "environment");

        if (!arguments.isEmpty()) {
            throw new IllegalArgumentException("this broker takes no arguments and is configured by the "
                    + "environment, beginning with " + DATA_DIRECTORY_VARIABLE + ", but it was given "
                    + arguments.size());
        }

        String namedDataDirectory = value(environment, DATA_DIRECTORY_VARIABLE)
                .orElseThrow(() -> new IllegalArgumentException(DATA_DIRECTORY_VARIABLE
                        + " names the directory this broker stores everything under, and it has no default"));

        Path dataDirectory = Path.of(namedDataDirectory);
        int port = value(environment, PORT_VARIABLE).map(BrokerLaunch::port).orElse(DEFAULT_PORT);
        Path readyFilePath = value(environment, READY_FILE_VARIABLE).map(Path::of)
                .orElseGet(() -> dataDirectory.resolve(DEFAULT_READY_FILE_NAME));
        InetAddress bindAddress = value(environment, BIND_ADDRESS_VARIABLE)
                .map(BrokerLaunch::bindAddress)
                .orElseGet(InetAddress::getLoopbackAddress);

        return new BrokerLaunch(brokerConfig(environment, dataDirectory, port, readyFilePath), bindAddress);
    }

    /**
     * The numbers this broker serves under, each from the variable that names it and otherwise from
     * {@link BrokerConfig#defaults(Path, int, Path)}.
     */
    private static BrokerConfig brokerConfig(Map<String, String> environment, Path dataDirectory, int port,
                                             Path readyFilePath) {
        int maxRequestBytes = wholeNumber(environment, MAX_REQUEST_BYTES_VARIABLE,
                RequestReader.DEFAULT_MAX_REQUEST_BYTES);
        int maxFetchWaitMs = wholeNumber(environment, MAX_FETCH_WAIT_MS_VARIABLE,
                BrokerConfig.DEFAULT_MAX_FETCH_WAIT_MILLIS);
        boolean zeroCopyFetch = value(environment, FETCH_ZERO_COPY_VARIABLE)
                .map(BrokerLaunch::zeroCopyFetch)
                .orElse(BrokerConfig.DEFAULT_ZERO_COPY_FETCH);
        int connectionCap = wholeNumber(environment, CONNECTION_CAP_VARIABLE, BrokerConfig.DEFAULT_CONNECTION_CAP);
        int readTimeoutMs = wholeNumber(environment, READ_TIMEOUT_MS_VARIABLE,
                BrokerConfig.DEFAULT_READ_TIMEOUT_MILLIS);
        int maxTotalPartitions = wholeNumber(environment, MAX_TOTAL_PARTITIONS_VARIABLE,
                BrokerConfig.DEFAULT_MAX_TOTAL_PARTITIONS);
        int maxTotalGroups = wholeNumber(environment, MAX_TOTAL_GROUPS_VARIABLE,
                BrokerConfig.DEFAULT_MAX_TOTAL_GROUPS);
        LogConfig logConfig = logConfig(environment);

        try {
            return new BrokerConfig(dataDirectory, port, maxRequestBytes, maxFetchWaitMs, zeroCopyFetch,
                    connectionCap, readTimeoutMs, maxTotalPartitions, maxTotalGroups, readyFilePath, logConfig);
        } catch (IllegalArgumentException refusal) {
            throw refusalNamingTheVariable(refusal);
        }
    }

    /**
     * The sizes and policy every partition log opens with, each from the variable that names it and
     * otherwise from {@link LogConfig#defaults()}.
     */
    private static LogConfig logConfig(Map<String, String> environment) {
        int maxRecordBytes = wholeNumber(environment, MAX_RECORD_BYTES_VARIABLE, LogConfig.DEFAULT_MAX_RECORD_BYTES);
        int segmentBytes = wholeNumber(environment, SEGMENT_BYTES_VARIABLE, LogConfig.DEFAULT_SEGMENT_BYTES);
        int indexIntervalBytes = wholeNumber(environment, INDEX_INTERVAL_BYTES_VARIABLE,
                LogConfig.DEFAULT_INDEX_INTERVAL_BYTES);
        long retentionMs = wholeNumber(environment, RETENTION_MS_VARIABLE, LogConfig.DEFAULT_RETENTION_MS);
        long retentionBytes = wholeNumber(environment, RETENTION_BYTES_VARIABLE, LogConfig.DEFAULT_RETENTION_BYTES);
        FlushMode flushMode = value(environment, FLUSH_MODE_VARIABLE)
                .map(BrokerLaunch::flushMode)
                .orElse(LogConfig.DEFAULT_FLUSH_MODE);
        long flushIntervalMs = wholeNumber(environment, FLUSH_INTERVAL_MS_VARIABLE,
                LogConfig.DEFAULT_FLUSH_INTERVAL_MS);
        long flushIntervalBytes = wholeNumber(environment, FLUSH_INTERVAL_BYTES_VARIABLE,
                LogConfig.DEFAULT_FLUSH_INTERVAL_BYTES);

        try {
            return new LogConfig(maxRecordBytes, segmentBytes, indexIntervalBytes, retentionMs, retentionBytes,
                    flushMode, flushIntervalMs, flushIntervalBytes);
        } catch (IllegalArgumentException refusal) {
            throw refusalNamingTheVariable(refusal);
        }
    }

    /**
     * @return the variable's value with its surrounding blanks removed, or empty when it was not set or
     *         was set to nothing at all
     */
    private static Optional<String> value(Map<String, String> environment, String variable) {
        return Optional.ofNullable(environment.get(variable)).map(String::trim).filter(set -> !set.isEmpty());
    }

    /**
     * @return the whole number the variable was set to, or {@code byDefault} when it was not set. Text
     *         that is not a whole number the field can hold is refused in the words
     *         {@link #PORT_VARIABLE} has always been refused in: the variable, and what it was given
     */
    private static int wholeNumber(Map<String, String> environment, String variable, int byDefault) {
        Optional<String> named = value(environment, variable);
        if (named.isEmpty()) {
            return byDefault;
        }
        try {
            return Integer.parseInt(named.get());
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException(variable + " must be a whole number from " + Integer.MIN_VALUE
                    + " to " + Integer.MAX_VALUE + ", but was \"" + named.get() + "\"", notANumber);
        }
    }

    /**
     * @return the whole number the variable was set to, or {@code byDefault} when it was not set. The
     *         same refusal as the smaller fields, in the range this one holds
     */
    private static long wholeNumber(Map<String, String> environment, String variable, long byDefault) {
        Optional<String> named = value(environment, variable);
        if (named.isEmpty()) {
            return byDefault;
        }
        try {
            return Long.parseLong(named.get());
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException(variable + " must be a whole number from " + Long.MIN_VALUE
                    + " to " + Long.MAX_VALUE + ", but was \"" + named.get() + "\"", notANumber);
        }
    }

    /**
     * The two spellings {@link FlushMode} documents, read in whatever letters they were written in
     * because an environment is typed by hand. A third spelling is a durability promise this build
     * cannot keep, so it stops the broker rather than being rounded to one of these.
     */
    private static FlushMode flushMode(String named) {
        return switch (named.toLowerCase(Locale.ROOT)) {
            case "per-record" -> FlushMode.PER_RECORD;
            case "interval" -> FlushMode.INTERVAL;
            default -> throw new IllegalArgumentException(FLUSH_MODE_VARIABLE
                    + " must be \"per-record\" or \"interval\", but was \"" + named + "\"");
        };
    }

    /**
     * {@code true} and {@code false} and nothing else. {@code Boolean.parseBoolean} is what is not
     * called here: it answers {@code false} to every word that is not {@code true}, so a typo would
     * turn a setting nobody meant to change into the path this broker does not take by default, and it
     * would do it in silence.
     */
    private static boolean zeroCopyFetch(String named) {
        return switch (named.toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException(FETCH_ZERO_COPY_VARIABLE
                    + " must be \"true\" or \"false\", but was \"" + named + "\"");
        };
    }

    /**
     * @return the record's own refusal with the variable that set the field in front of it, or that
     *         refusal unchanged when it is about a field no variable here sets. The record's sentence
     *         is kept whole behind the variable's name, because it is the half that says what was
     *         expected while the variable is the half that says where to fix it
     */
    private static IllegalArgumentException refusalNamingTheVariable(IllegalArgumentException refusal) {
        String sentence = String.valueOf(refusal.getMessage());
        String variable = VARIABLE_BY_FIELD.get(sentence.split(" ", 2)[0]);
        if (variable == null) {
            return refusal;
        }
        return new IllegalArgumentException(variable + " was set to a value this broker cannot use: " + sentence,
                refusal);
    }

    private static int port(String named) {
        try {
            return Integer.parseInt(named);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(PORT_VARIABLE + " must be a whole number from "
                    + BrokerConfig.EPHEMERAL_PORT + " to " + BrokerConfig.MAX_PORT + ", but was \"" + named + "\"", e);
        }
    }

    /**
     * A literal address is taken as it is written; a name is resolved, which is the one thing here that
     * can reach the network, and it happens once, before anything is opened.
     */
    private static InetAddress bindAddress(String named) {
        try {
            return InetAddress.getByName(named);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(BIND_ADDRESS_VARIABLE
                    + " must be an address or a name this machine can resolve, but \"" + named + "\" is neither", e);
        }
    }
}
