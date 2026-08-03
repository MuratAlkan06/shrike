package io.shrike.core.net;

import io.shrike.core.time.SystemTimeSource;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Starts a broker and keeps it up: the entry point a container, a service manager, or a person runs.
 *
 * <p>It reads {@link BrokerLaunch} from the environment, starts the broker, installs a shutdown hook
 * that closes it, says which port it bound and which process it is, and then does nothing at all until
 * this process is asked to stop. Everything it decides is decided in {@link BrokerLaunch}, so what
 * happens here is only what cannot be tested: a real broker, a real hook, and a thread that waits.
 *
 * <p>A launch this broker cannot use is one line on standard error and exit code
 * {@value #STARTUP_FAILURE_EXIT_CODE} — no stack trace, because a misspelled environment variable is
 * the operator's mistake to fix rather than this build's to report.
 *
 * <p>Stopping is a {@code SIGTERM}, which is what {@code docker stop} and a service manager send. The
 * hook named {@code shrike-broker-stop} runs then, and closing the broker is what forces the segment
 * every partition was still writing.
 */
public final class BrokerMain {

    /** What this process exits with when the environment does not describe a broker it can start. */
    public static final int STARTUP_FAILURE_EXIT_CODE = 2;

    private BrokerMain() {
    }

    /**
     * @param args nothing: this broker is configured by the environment variables {@link BrokerLaunch}
     *             lists, and an argument is refused rather than ignored
     * @throws InterruptedException if this process is interrupted while it waits to be stopped
     */
    public static void main(String[] args) throws InterruptedException {
        BrokerLaunch launch;
        try {
            launch = BrokerLaunch.from(List.of(args), System.getenv());
        } catch (IllegalArgumentException refusal) {
            System.err.println("shrike cannot start: " + refusal.getMessage());
            System.exit(STARTUP_FAILURE_EXIT_CODE);
            return;
        }

        ShrikeBroker broker = ShrikeBroker.start(launch.config(), new SystemTimeSource(), launch.bindAddress());
        Runtime.getRuntime().addShutdownHook(new Thread(broker::close, "shrike-broker-stop"));

        System.out.println("listening port=" + broker.port() + " pid=" + ProcessHandle.current().pid());

        // There is nothing else for this thread to do: the acceptor is running, and this process ends
        // when it is signalled. Waiting on a latch nobody counts down is how that is said without a
        // sleep.
        new CountDownLatch(1).await();
    }
}
