package io.shrike.core.net;

import io.shrike.core.log.LogConfig;
import io.shrike.core.time.SystemTimeSource;
import io.shrike.core.time.TimeSource;
import java.nio.file.Path;

/**
 * Starts brokers for tests: a real one, on a real ephemeral port, over a real data directory.
 *
 * <p>The clock is the system clock rather than a fixed one on purpose. A fetch's deadline is measured
 * on the injected clock, so a frozen clock is a broker whose long polls never expire — which is a
 * legitimate thing to inject and exactly the wrong thing for a test that wants a fetch to give up. The
 * tests here assert causality anyway, so the only thing the real clock buys them is that a wait
 * eventually ends.
 */
final class BrokerHarness {

    /** The ready file every test broker writes, inside its own temporary data directory. */
    static final String READY_FILE_NAME = "shrike.ready";

    static final TimeSource SYSTEM_CLOCK = new SystemTimeSource();

    private BrokerHarness() {
    }

    /**
     * @param dataDirectory the test's temporary directory
     * @return the configuration a test broker starts with: an ephemeral port and every default
     */
    static BrokerConfig config(Path dataDirectory) {
        return BrokerConfig.defaults(dataDirectory, BrokerConfig.EPHEMERAL_PORT,
                dataDirectory.resolve(READY_FILE_NAME));
    }

    /**
     * @param dataDirectory the test's temporary directory
     * @param connectionCap the cap this broker enforces
     * @return the same configuration with a cap a test can reach without opening sixty-four sockets
     */
    static BrokerConfig config(Path dataDirectory, int connectionCap) {
        BrokerConfig defaults = config(dataDirectory);
        return new BrokerConfig(defaults.dataDirectory(), defaults.port(), defaults.maxRequestBytes(), connectionCap,
                defaults.readyFilePath(), LogConfig.defaults());
    }

    /**
     * @param dataDirectory the test's temporary directory
     * @return a started broker, which the test closes
     */
    static ShrikeBroker start(Path dataDirectory) {
        return ShrikeBroker.start(config(dataDirectory), SYSTEM_CLOCK);
    }
}
