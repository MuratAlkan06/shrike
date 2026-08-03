package io.shrike.clients;

import io.shrike.core.net.BrokerConfig;
import io.shrike.core.net.ShrikeBroker;
import io.shrike.core.time.SystemTimeSource;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/**
 * A broker in a process of its own: start it on a port the operating system chooses, write the ready
 * file that says which port that was, and stay up until whoever started this process destroys it.
 *
 * <p>It is the only class in this module's tests that touches the broker itself, and it exists so
 * that everything else can talk to a broker the way anything on the network would — through a socket,
 * with no shared objects and no shared heap.
 */
public final class BrokerProcessMain {

    private static final int USAGE_EXIT_CODE = 2;

    private BrokerProcessMain() {
    }

    /**
     * @param args the data directory and the path to write the ready file to
     * @throws InterruptedException if this process is interrupted while it waits to be destroyed
     */
    public static void main(String[] args) throws InterruptedException {
        if (args.length != 2) {
            System.err.println("usage: " + BrokerProcessMain.class.getName() + " <dataDirectory> <readyFilePath>");
            System.exit(USAGE_EXIT_CODE);
            return;
        }

        Path dataDirectory = Path.of(args[0]);
        Path readyFilePath = Path.of(args[1]);
        ShrikeBroker broker = ShrikeBroker.start(
                BrokerConfig.defaults(dataDirectory, BrokerConfig.EPHEMERAL_PORT, readyFilePath), new SystemTimeSource());
        Runtime.getRuntime().addShutdownHook(new Thread(broker::close, "shrike-broker-stop"));

        System.out.println("listening port=" + broker.port() + " pid=" + ProcessHandle.current().pid());

        // There is nothing else for this thread to do: the acceptor is running, and this process ends
        // when the test that started it destroys it. Waiting on a latch nobody counts down is how that
        // is said without a sleep.
        new CountDownLatch(1).await();
    }
}
