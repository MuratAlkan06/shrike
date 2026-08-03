package io.shrike.core.net;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What one broker process was told to do: the {@link BrokerConfig} to start, and the address to bind.
 *
 * <p>{@link BrokerConfig} is what a broker is built from; this is how a process comes to hold one. It
 * is read from environment variables and nothing else — no configuration file, no working-directory
 * lookup, no path this process guesses at. Four variables, of which one is required:
 *
 * <pre>
 * SHRIKE_DATA_DIRECTORY  required, no default: everything this broker stores lives under it
 * SHRIKE_PORT            the TCP port to listen on, {@value #DEFAULT_PORT} by default
 * SHRIKE_READY_FILE      the file written once the broker is listening,
 *                        &lt;dataDirectory&gt;/{@value #DEFAULT_READY_FILE_NAME} by default
 * SHRIKE_BIND_ADDRESS    the interface to listen on, the loopback address by default
 * </pre>
 *
 * <p>A variable that is not set, or set to nothing at all, is one the default answers for — which is
 * what {@code docker run -e SHRIKE_PORT=} passes and what an operator means by it. The data directory
 * has no default because a default would be a path this process picked rather than one somebody chose,
 * and every other path here derives from it.
 *
 * <p>Every value is checked here, before a socket is opened or a log is recovered, and a value that
 * does not make sense raises an {@link IllegalArgumentException} whose message is one sentence naming
 * the variable to fix.
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
            throw new IllegalArgumentException("this broker takes no arguments and is configured by "
                    + DATA_DIRECTORY_VARIABLE + ", " + PORT_VARIABLE + ", " + READY_FILE_VARIABLE + ", and "
                    + BIND_ADDRESS_VARIABLE + ", but it was given " + arguments.size());
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

        return new BrokerLaunch(BrokerConfig.defaults(dataDirectory, port, readyFilePath), bindAddress);
    }

    /**
     * @return the variable's value with its surrounding blanks removed, or empty when it was not set or
     *         was set to nothing at all
     */
    private static Optional<String> value(Map<String, String> environment, String variable) {
        return Optional.ofNullable(environment.get(variable)).map(String::trim).filter(set -> !set.isEmpty());
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
