package io.shrike.core.net;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.shrike.core.log.ProducedRecord;
import io.shrike.core.log.StoredRecord;
import io.shrike.core.protocol.CreateTopicRequest;
import io.shrike.core.protocol.FetchRequest;
import io.shrike.core.protocol.FetchResponse;
import io.shrike.core.protocol.ProduceRequest;
import io.shrike.core.protocol.RequestFrame;
import io.shrike.core.protocol.ResponseDecoding;
import io.shrike.core.protocol.WireRecords;
import io.shrike.core.time.MonotonicTimeSource;
import io.shrike.core.time.TimeSource;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code read.timeout.ms}: how long one connection may hold a place under the connection cap without
 * finishing a request, and the two things it deliberately does not cover.
 *
 * <p>Nothing here waits for a bound to pass. The clock every read phase is stamped from is
 * {@link #nowNanos} — elapsed time, which is what a deadline is made of — the test sets it, and one
 * pass of the reaper is
 * {@link ShrikeBroker#closeStalledConnections(long)} — the same call {@code shrike-conn-reaper} makes on
 * its own thread. So these tests advance a clock and ask, exactly as the retention and flush tests do,
 * and there is no real-time test in this class at all: how often production asks is measured in
 * {@link ConnectionReaperTest}, and how often it asks is not what the bound means.
 *
 * <p>{@link #nowMillis} is the other clock, and it is here to be moved rather than to be measured
 * against: it is the wall clock a record's timestamp and a fetch's deadline come from, and the bound
 * this class is about must not move when it does.
 *
 * <p>Two of these tests know a connection is one the reaper can see by opening a second one and watching
 * it be refused. {@code shrike-acceptor} is a single thread that hands a socket over before it accepts
 * the next, so a refusal is proof that the connection before it was already built, registered, and given
 * a thread — which is the only thing this test needs to be sure of, and nothing on the wire says it.
 */
class BrokerReadTimeoutTest {

    private static final String TOPIC = "orders";
    private static final int PARTITION = 0;
    private static final int ONLY_PARTITION_COUNT = 1;

    /** So that the connection under test is the only one there can be, and the next one is refused. */
    private static final int ONE_CONNECTION_AT_A_TIME = 1;

    /**
     * A minute. It is long in real time on purpose: the reaper's own thread asks once per bound, so a
     * bound this long is one its thread cannot reach inside a test, and every pass these tests see is
     * one they asked for against a clock they set.
     */
    private static final int READ_TIMEOUT_MS = 60_000;

    /**
     * One second, against the default {@code max.fetch.wait.ms} of thirty — the shape the regression
     * test below needs, and the shape a whole-socket timeout could not survive.
     */
    private static final int SHORT_READ_TIMEOUT_MS = 1_000;

    /** One short of the fetch wait, so the long poll below is still inside its deadline at the end. */
    private static final int SWEEPS_WHILE_THE_FETCH_WAITS = 29;

    private static final long FIRST_TIMESTAMP_MS = 1_700_000_000_000L;

    /** A wall-clock step far larger than either bound, applied in both directions. */
    private static final long AN_HOUR_MS = 3_600_000L;

    /** A length this broker will believe, so the reader takes it and allocates a body for it. */
    private static final int A_LENGTH_THIS_BROKER_WILL_BELIEVE = 64;

    private static final int SOME_OF_THE_BODY_BYTES = 3;

    private static final int FOUR_REQUESTS = 4;
    private static final int PLENTY_OF_BYTES = 64 * 1024;
    private static final int ANSWER_IMMEDIATELY_MS = 0;
    private static final int ONE_BYTE_IS_ENOUGH = 1;
    private static final int NOTHING_IS_ENOUGH = 0;

    /** The wall clock: what a record is stamped with and what a fetch's wait expires against. */
    private final AtomicLong nowMillis = new AtomicLong(FIRST_TIMESTAMP_MS);

    /** Elapsed time, which is what every read phase in this test is measured against. */
    private final AtomicLong nowNanos = new AtomicLong();

    private final TimeSource clock = nowMillis::get;

    private final MonotonicTimeSource elapsed = nowNanos::get;

    @TempDir
    Path dataDirectory;

    private ShrikeBroker broker;
    private ExecutorService clientThread;

    @AfterEach
    void stopBroker() {
        if (clientThread != null) {
            clientThread.shutdownNow();
        }
        if (broker != null) {
            broker.close();
        }
    }

    @Test
    void closesAConnectionThatSaysNothingPastTheBoundAndGivesItsPlaceUnderTheCapBack() throws Exception {
        startBroker(ONE_CONNECTION_AT_A_TIME, READ_TIMEOUT_MS);
        CountDownLatch placeGivenBack = new CountDownLatch(1);
        broker.onConnectionEnded(placeGivenBack::countDown);

        try (WireClient silent = WireClient.connectTo(broker);
             WireClient overTheCap = WireClient.connectTo(broker)) {
            assertTrue(Await.value(clientThread.submit(overTheCap::isClosedWithNoReply),
                            "the broker to close the connection past its cap"),
                    "the silent connection holds the only place there is, so it is one the reaper can see");
            Future<Boolean> silentClosed = clientThread.submit(silent::isClosedWithNoReply);

            nowNanos.set(elapsedNanosAfter(READ_TIMEOUT_MS));

            assertEquals(1, broker.closeStalledConnections(nowNanos.get()),
                    "a connection that has not sent a byte for the whole bound is one this broker closes");
            assertTrue(Await.value(silentClosed, "the reaped connection to reach its client as a closed socket"),
                    "and what the client is told is the socket closing, with no reply of any kind");
        }

        Await.latch(placeGivenBack, "the reaped connection's own thread to give back its place under the cap");

        try (WireClient served = WireClient.connectTo(broker)) {
            assertInstanceOf(ResponseDecoding.Answered.class,
                    served.call(1, new CreateTopicRequest(TOPIC, ONLY_PARTITION_COUNT)),
                    "the place the reaped connection held came back with it, so the cap did not shrink by one");
        }
    }

    @Test
    void closesAConnectionThatStalledPartWayThroughARequestFrame() throws Exception {
        startBroker(ONE_CONNECTION_AT_A_TIME, READ_TIMEOUT_MS);

        try (WireClient stalled = WireClient.connectTo(broker);
             WireClient overTheCap = WireClient.connectTo(broker)) {
            // A length this broker believes and three of the bytes it was promised behind it: the reader
            // has taken the length, sized a buffer to it, and is inside a read that will never finish.
            stalled.sendRaw(ByteBuffer.allocate(RequestFrame.LENGTH_FIELD_BYTES + SOME_OF_THE_BODY_BYTES)
                    .putInt(A_LENGTH_THIS_BROKER_WILL_BELIEVE).array());
            assertTrue(Await.value(clientThread.submit(overTheCap::isClosedWithNoReply),
                            "the broker to close the connection past its cap"),
                    "the stalled connection holds the only place there is, so it is one the reaper can see");
            Future<Boolean> stalledClosed = clientThread.submit(stalled::isClosedWithNoReply);

            nowNanos.set(elapsedNanosAfter(READ_TIMEOUT_MS));

            assertEquals(1, broker.closeStalledConnections(nowNanos.get()),
                    "the bound covers finishing a frame that has begun, not only saying nothing at all");
            assertTrue(Await.value(stalledClosed, "the reaped connection to reach its client as a closed socket"),
                    "so the bytes that were promised and never sent cost this broker one bound of heap");
        }
    }

    /**
     * The read bound is an amount of time that has to pass, not a date that has to be reached. A host
     * whose wall clock is stepped — by a time-synchronization daemon, by an operator, by a virtual
     * machine resuming from a snapshot — must keep the bound it was configured with, in both
     * directions: a step forwards must not close a connection that has just spoken, and a step
     * backwards must not stretch the bound by the size of the step.
     */
    @Test
    void measuresTheBoundOnElapsedTimeSoAWallClockStepDoesNotMoveIt() throws Exception {
        startBroker(ONE_CONNECTION_AT_A_TIME, READ_TIMEOUT_MS);

        try (WireClient silent = WireClient.connectTo(broker);
             WireClient overTheCap = WireClient.connectTo(broker)) {
            assertTrue(Await.value(clientThread.submit(overTheCap::isClosedWithNoReply),
                            "the broker to close the connection past its cap"),
                    "the silent connection holds the only place there is, so it is one the reaper can see");

            nowMillis.set(FIRST_TIMESTAMP_MS + AN_HOUR_MS);
            assertEquals(0, broker.closeStalledConnections(nowNanos.get()),
                    "an hour added to the date is not an hour of a connection saying nothing");
            nowMillis.set(FIRST_TIMESTAMP_MS - AN_HOUR_MS);
            assertEquals(0, broker.closeStalledConnections(nowNanos.get()),
                    "and an hour taken off it is not a bound that has been reset either");

            Future<Boolean> silentClosed = clientThread.submit(silent::isClosedWithNoReply);
            nowNanos.set(elapsedNanosAfter(READ_TIMEOUT_MS));

            assertEquals(1, broker.closeStalledConnections(nowNanos.get()),
                    "what closes the connection is the bound's worth of elapsed time, and only that");
            assertTrue(Await.value(silentClosed, "the reaped connection to reach its client as a closed socket"),
                    "with the wall clock still an hour behind where the connection was opened");
        }
    }

    /**
     * The regression test. A long-poll fetch is the one api this broker holds a connection open for on
     * purpose: it waits under the partition's lock, on its own connection's thread, between two whole
     * request frames — after the request was read and before the next one is. So it is <em>serving</em>,
     * not reading, and the read bound must not touch it.
     *
     * <p>The shape here is what a whole-connection or whole-socket timeout could not survive: the bound
     * is one second, the fetch's wait is the default thirty, and the reaper is asked
     * {@value #SWEEPS_WHILE_THE_FETCH_WAITS} times while the fetch waits — every one of them at a clock
     * that is further past the bound than the last. Every pass must close nothing, the record produced
     * at the end must reach the fetch, and the connection must go on answering afterwards.
     *
     * <p>Whoever changes the bookkeeping in {@link Connection} changes what this test proves. If it ever
     * starts failing, the change has made the read bound cover serving, and the cost of that is every
     * consumer on a quiet partition losing its connection once a second.
     */
    @Test
    void keepsALongPollFetchThatWaitsFarPastTheReadTimeoutAndServesItItsRecords() throws Exception {
        startBroker(BrokerConfig.DEFAULT_CONNECTION_CAP, SHORT_READ_TIMEOUT_MS);
        String value = "landed for a fetch that had been waiting many bounds";

        try (WireClient polling = WireClient.connectTo(broker)) {
            polling.call(1, new CreateTopicRequest(TOPIC, ONLY_PARTITION_COUNT));
            CountDownLatch waiterRegistered = new CountDownLatch(1);
            broker.partition(TOPIC, PARTITION).orElseThrow().onWaiterRegistered(waiterRegistered::countDown);
            Future<ResponseDecoding> fetch = clientThread.submit(() -> polling.call(2,
                    new FetchRequest(TOPIC, PARTITION, 0L, PLENTY_OF_BYTES, BrokerConfig.DEFAULT_MAX_FETCH_WAIT_MILLIS,
                            ONE_BYTE_IS_ENOUGH)));
            Await.latch(waiterRegistered, "the fetch to register as a waiter");

            for (int sweep = 1; sweep <= SWEEPS_WHILE_THE_FETCH_WAITS; sweep++) {
                nowMillis.set(FIRST_TIMESTAMP_MS + (long) sweep * SHORT_READ_TIMEOUT_MS);
                nowNanos.set(elapsedNanosAfter((long) sweep * SHORT_READ_TIMEOUT_MS));
                assertEquals(0, broker.closeStalledConnections(nowNanos.get()),
                        "a fetch waiting to be written to is not a connection failing to be read from");
            }
            produce(value);

            FetchResponse answer = (FetchResponse) assertInstanceOf(ResponseDecoding.Answered.class,
                    Await.value(fetch, "the long poll that outlasted the read bound many times over")).response();
            assertEquals(List.of(value), valuesIn(answer), "and it is answered with its records, not with a close");
            assertInstanceOf(ResponseDecoding.Answered.class,
                    polling.call(3, new CreateTopicRequest("shipments", ONLY_PARTITION_COUNT)),
                    "on the same connection, which was never closed under it");
        }
    }

    @Test
    void keepsAConnectionWhoseRequestsAreSpacedUnderTheBound() throws Exception {
        startBroker(ONE_CONNECTION_AT_A_TIME, READ_TIMEOUT_MS);
        long halfTheBoundMs = READ_TIMEOUT_MS / 2;

        try (WireClient busy = WireClient.connectTo(broker)) {
            assertInstanceOf(ResponseDecoding.Answered.class,
                    busy.call(1, new CreateTopicRequest(TOPIC, ONLY_PARTITION_COUNT)));

            for (int request = 1; request <= FOUR_REQUESTS; request++) {
                nowNanos.set(elapsedNanosAfter(request * halfTheBoundMs));

                assertEquals(0, broker.closeStalledConnections(nowNanos.get()),
                        "half a bound has gone by since this connection's last answer, so it is not stalled");
                assertInstanceOf(ResponseDecoding.Answered.class,
                        busy.call(1 + request, new FetchRequest(TOPIC, PARTITION, 0L, PLENTY_OF_BYTES,
                                ANSWER_IMMEDIATELY_MS, NOTHING_IS_ENOUGH)),
                        "and it is still there to answer, twice the bound after it was opened");
            }
        }
    }

    private void startBroker(int connectionCap, int readTimeoutMs) {
        broker = ShrikeBroker.start(BrokerHarness.configWithReadTimeout(dataDirectory, connectionCap, readTimeoutMs),
                clock, InetAddress.getLoopbackAddress(), elapsed);
        clientThread = Executors.newSingleThreadExecutor();
    }

    /** @param elapsedMs how far into this test's elapsed time to move, from where it started */
    private long elapsedNanosAfter(long elapsedMs) {
        return MILLISECONDS.toNanos(elapsedMs);
    }

    private void produce(String value) throws IOException {
        try (WireClient producer = WireClient.connectTo(broker)) {
            producer.call(4, new ProduceRequest(TOPIC, PARTITION,
                    List.of(new ProducedRecord(null, value.getBytes(UTF_8)))));
        }
    }

    private static List<String> valuesIn(FetchResponse response) {
        return WireRecords.decode(ByteBuffer.wrap(response.records())).stream()
                .map(StoredRecord::value)
                .map(value -> new String(value, UTF_8))
                .toList();
    }
}
