package io.shrike.core.net;

import io.shrike.core.log.ShrikeIOException;
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
 * <p>A broker that does not come up is one line on standard error and exit code
 * {@value #STARTUP_FAILURE_EXIT_CODE} — no stack trace, whether what stopped it was a misspelled
 * environment variable, a port already bound, or a data directory this process may not write to. All
 * three are the operator's to fix rather than this build's to report, and the sentence each of them
 * arrives with is what says which one it was.
 *
 * <p>Stopping is a {@code SIGTERM}, which is what {@code docker stop} and a service manager send. The
 * hook named {@code shrike-broker-stop} runs then, and closing the broker is what forces the segment
 * every partition was still writing.
 */
public final class BrokerMain {

    /** What this process exits with when it cannot get a broker up, whatever stopped it. */
    public static final int STARTUP_FAILURE_EXIT_CODE = 2;

    private BrokerMain() {
    }

    /**
     * @param args nothing: this broker is configured by the environment variables {@link BrokerLaunch}
     *             lists, and an argument is refused rather than ignored
     * @throws InterruptedException if this process is interrupted while it waits to be stopped
     */
    public static void main(String[] args) throws InterruptedException {
        ShrikeBroker broker;
        try {
            BrokerLaunch launch = BrokerLaunch.from(List.of(args), System.getenv());
            broker = ShrikeBroker.start(launch.config(), new SystemTimeSource(), launch.bindAddress());
        } catch (IllegalArgumentException | ShrikeIOException refusal) {
            // Both halves of a start that did not happen, answered the same way. A misspelled variable
            // or an address that is not one is refused by BrokerLaunch; a port already bound, a data
            // directory nothing may write to, and a registry file this broker will not believe are
            // refused by ShrikeBroker. To whoever ran this process those are one event — it did not
            // come up — and the two types above are the whole of what either of them documents. Each
            // carries its sentence in its message, which a stack trace would bury, and exiting 1 would
            // make an unbindable port look like a different kind of failure from a misspelled one.
            System.err.println("shrike cannot start: " + refusal.getMessage());
            System.exit(STARTUP_FAILURE_EXIT_CODE);
            return;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(broker::close, "shrike-broker-stop"));

        System.out.println("listening port=" + broker.port() + " pid=" + ProcessHandle.current().pid());

        // There is nothing else for this thread to do: the acceptor is running, and this process ends
        // when it is signalled. Waiting on a latch nobody counts down is how that is said without a
        // sleep.
        new CountDownLatch(1).await();
    }
}
