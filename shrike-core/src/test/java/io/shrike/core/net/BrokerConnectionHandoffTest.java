package io.shrike.core.net;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.shrike.core.protocol.CreateTopicRequest;
import io.shrike.core.protocol.ResponseDecoding;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What happens to the acceptor when handing a socket to a thread fails.
 *
 * <p>The failure it is written for is the one that arrives under memory pressure — out of native
 * threads, out of heap — at the moment the place under the connection cap, the socket, and the acceptor
 * thread itself are worth the most. A broker whose acceptor died would keep its port, keep its ready
 * file, and never accept again; a broker that leaked the place under the cap would shrink that cap by
 * one for as long as it ran.
 *
 * <p>The failure is injected through the package-private {@code onConnectionReserved} seam, because
 * running the machine out of native threads is not something a test may do to the build it is part of.
 * The cap here is one connection, so a leaked place under it is the difference between the second
 * client being served and the second client being closed on.
 */
class BrokerConnectionHandoffTest {

    private static final int ONE_CONNECTION_AT_A_TIME = 1;
    private static final String TOPIC = "orders";
    private static final int ONLY_PARTITION_COUNT = 1;
    private static final int FIRST_CORRELATION_ID = 1;

    @TempDir
    Path dataDirectory;

    private ShrikeBroker broker;
    private ExecutorService readerThread;

    @BeforeEach
    void startBroker() {
        broker = ShrikeBroker.start(BrokerHarness.config(dataDirectory, ONE_CONNECTION_AT_A_TIME),
                BrokerHarness.SYSTEM_CLOCK);
        readerThread = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void stopBroker() {
        readerThread.shutdownNow();
        broker.close();
    }

    @Test
    void closesTheSocketAndKeepsAcceptingWhenAConnectionHandoffFails() throws Exception {
        CountDownLatch handoffFailed = new CountDownLatch(1);
        AtomicBoolean failTheNextHandoff = new AtomicBoolean(true);
        broker.onConnectionReserved(() -> {
            if (failTheNextHandoff.compareAndSet(true, false)) {
                handoffFailed.countDown();
                throw new OutOfMemoryError("unable to create native thread (thrown by a test seam)");
            }
        });

        try (WireClient failed = WireClient.connectTo(broker)) {
            Future<Boolean> closed = readerThread.submit(failed::isClosedWithNoReply);

            Await.latch(handoffFailed, "the handoff of the first connection to fail");
            assertTrue(Await.value(closed, "the broker to close the socket it could not hand over"),
                    "a socket whose handoff failed is closed rather than left open with nobody serving it");
        }

        try (WireClient served = WireClient.connectTo(broker)) {
            assertInstanceOf(ResponseDecoding.Answered.class,
                    served.call(FIRST_CORRELATION_ID, new CreateTopicRequest(TOPIC, ONLY_PARTITION_COUNT)),
                    "the acceptor survived the failed handoff, and the place under the cap that handoff took"
                            + " came back with it");
        }
    }

    /**
     * The same failure, with the WARN about it failing too.
     *
     * <p>That is not a contrived pair: the handoff fails because the machine is out of memory, and
     * formatting a message and appending a line are two more allocations under that same condition. So
     * the unwind runs before the line is written rather than after it, and the writing of the line is
     * where a throwable stops. With the old order, an {@code OutOfMemoryError} from the logger left the
     * place under the cap taken, the socket open, and the acceptor dead — the three losses the unwind
     * exists to prevent, arriving from the line that was meant to report them.
     */
    @Test
    void givesTheSlotBackAndKeepsAcceptingWhenTheWarnAboutAFailedHandoffThrowsToo() throws Exception {
        CountDownLatch warnAttempted = new CountDownLatch(1);
        AtomicBoolean failTheNextHandoff = new AtomicBoolean(true);
        broker.onConnectionReserved(() -> {
            if (failTheNextHandoff.compareAndSet(true, false)) {
                throw new OutOfMemoryError("unable to create native thread (thrown by a test seam)");
            }
        });
        broker.logHandoffFailuresThrough((line, failure) -> {
            warnAttempted.countDown();
            throw new OutOfMemoryError("unable to write a log line (thrown by a test seam)");
        });

        try (WireClient failed = WireClient.connectTo(broker)) {
            Future<Boolean> closed = readerThread.submit(failed::isClosedWithNoReply);

            Await.latch(warnAttempted, "the WARN about the failed handoff to be attempted");
            assertTrue(Await.value(closed, "the broker to close the socket it could not hand over"),
                    "the socket was closed before the line about it was written, so a WARN that throws"
                            + " cannot leave it open");
        }

        try (WireClient served = WireClient.connectTo(broker)) {
            assertInstanceOf(ResponseDecoding.Answered.class,
                    served.call(FIRST_CORRELATION_ID, new CreateTopicRequest(TOPIC, ONLY_PARTITION_COUNT)),
                    "the place under the cap came back before the WARN was attempted, and the acceptor"
                            + " survived that WARN throwing");
        }
    }

    /**
     * The same failure once more, with the unwind that answers it failing too.
     *
     * <p>That is the same pair as the one above rather than a new contrivance: removing a map entry and
     * closing a socket are allocations under the very memory pressure that failed the handoff. A
     * throwable from the unwind used to travel out of the handoff and end the accept loop, leaving a
     * broker with its port bound, its ready file where it was, and nothing accepting — and it left the
     * place under the cap taken on the way past, because the throw happened before the count came down.
     *
     * <p>So the two assertions are what the fix owes: the next connection is served, which takes both an
     * acceptor that is alive and a place under the cap that came back; and the one after <em>that</em> is
     * closed rather than served, which is how a place given back twice would show, since a count that had
     * gone one below zero would let two connections through a cap of one.
     */
    @Test
    void givesTheSlotBackOnceAndKeepsAcceptingWhenTheUnwindOfAFailedHandoffThrows() throws Exception {
        CountDownLatch unwindAttempted = new CountDownLatch(1);
        AtomicBoolean failTheNextHandoff = new AtomicBoolean(true);
        AtomicBoolean failTheNextUnwind = new AtomicBoolean(true);
        broker.onConnectionReserved(() -> {
            if (failTheNextHandoff.compareAndSet(true, false)) {
                throw new OutOfMemoryError("unable to create native thread (thrown by a test seam)");
            }
        });
        broker.onConnectionAbandoned(() -> {
            if (failTheNextUnwind.compareAndSet(true, false)) {
                unwindAttempted.countDown();
                throw new OutOfMemoryError("unable to close a socket (thrown by a test seam)");
            }
        });

        try (WireClient failed = WireClient.connectTo(broker)) {
            Await.latch(unwindAttempted, "the unwind of the failed handoff to be attempted");
        }

        try (WireClient served = WireClient.connectTo(broker)) {
            Future<ResponseDecoding> answer = readerThread.submit(
                    () -> served.call(FIRST_CORRELATION_ID, new CreateTopicRequest(TOPIC, ONLY_PARTITION_COUNT)));

            assertInstanceOf(ResponseDecoding.Answered.class,
                    Await.value(answer, "the broker to answer the connection after the one whose unwind threw"),
                    "the acceptor outlived an unwind that threw, and the place under the cap came back with it");

            try (WireClient overTheCap = WireClient.connectTo(broker)) {
                Future<Boolean> closed = readerThread.submit(overTheCap::isClosedWithNoReply);

                assertTrue(Await.value(closed, "the broker to close the connection past its cap"),
                        "and the place came back once and only once: the cap is still one connection, so the"
                                + " connection after the one being served is closed rather than served");
            }
        }
    }
}
