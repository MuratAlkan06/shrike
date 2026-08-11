package io.shrike.core.net;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.shrike.core.log.ProducedRecord;
import io.shrike.core.log.StoredRecord;
import io.shrike.core.protocol.ApiKeys;
import io.shrike.core.protocol.CreateTopicRequest;
import io.shrike.core.protocol.FetchRequest;
import io.shrike.core.protocol.FetchResponse;
import io.shrike.core.protocol.ProduceRequest;
import io.shrike.core.protocol.ResponseDecoding;
import io.shrike.core.protocol.ResponseFrame;
import io.shrike.core.protocol.WireRecords;
import io.shrike.core.time.MonotonicTimeSource;
import io.shrike.core.time.TimeSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * {@code write.timeout.ms}: how long an answer this broker is sending may go with not one byte of it
 * leaving, and the three things it deliberately does not cover.
 *
 * <p>The bound is on making no progress, not on how long an answer takes, and that difference is what
 * most of this class is about. A peer that takes a fetch of nearly four mebibytes sixteen kibibytes at
 * a time, pausing just under the bound between every one of them, holds this broker's write for
 * hundreds of bounds' worth of elapsed time and is never closed; a peer that asks for the same answer
 * and stops reading is closed one bound after it stops. A cap on total serve time could not tell those
 * two apart, which is why there is not one.
 *
 * <p>Nothing here waits for a bound to pass, and nothing sleeps. {@link #nowNanos} is the elapsed-time
 * clock the broker stamps its write progress from, {@link #broker}'s
 * {@link ShrikeBroker#closeStalledConnections(long)} is one pass of the reaper taken on the test's own
 * thread, and the reading a pass is given is the test's to choose. Both bounds are minutes long in real
 * time on purpose: the reaper's own thread asks once per the shorter of them, so a bound this long is
 * one its thread cannot reach inside a test, and every pass these tests see is one they asked for.
 *
 * <p>The answer is nearly eight mebibytes and the client's receive buffer is sixteen kibibytes, so the
 * bytes cannot all be sitting in a kernel buffer with the broker already gone from the request — the
 * same arrangement, and the same reason for it, as {@link BrokerZeroCopyDeletionRaceTest}, with a
 * larger answer because this class needs the broker to be blocked in its write over and over rather
 * than once. A host that buffered eight mebibytes of it would fail the first test below rather than
 * pass it quietly: what that test asserts at the end is that the transfer cost several bounds of
 * elapsed time, which is only true if the broker was made to wait for its peer many times.
 */
class BrokerWriteTimeoutTest {

    private static final String TOPIC = "orders";
    private static final String ANOTHER_TOPIC = "shipments";
    private static final int PARTITION = 0;
    private static final int ONLY_PARTITION_COUNT = 1;

    /** So that the connection under test is the only one there can be, and its place is countable. */
    private static final int ONE_CONNECTION_AT_A_TIME = 1;

    /**
     * A minute of a write getting nowhere, and an hour of reading. Both are far longer in real time
     * than a test takes, so the reaper's own thread — which asks once per the shorter of the two —
     * never reaches a pass of its own; and the hour is long enough that a pass many write bounds into
     * the future is still nowhere near the read bound, so what these tests see closed was closed by
     * the bound they are about.
     */
    private static final int WRITE_TIMEOUT_MS = 60_000;
    private static final int READ_TIMEOUT_MS = 3_600_000;

    private static final long WRITE_TIMEOUT_NANOS = MILLISECONDS.toNanos(WRITE_TIMEOUT_MS);

    /** One nanosecond short of the bound, which is the largest gap that must not close anything. */
    private static final long JUST_UNDER_THE_BOUND_NANOS = WRITE_TIMEOUT_NANOS - 1L;

    /** Bounds' worth of elapsed time, used where the point is that the total is not what is bounded. */
    private static final int MANY_BOUNDS = 5;

    /** One short of the fetch wait, so the long poll below is still inside its deadline at the end. */
    private static final int SWEEPS_WHILE_THE_FETCH_WAITS = 29;

    private static final long FIRST_TIMESTAMP_MS = 1_700_000_000_000L;

    /** A wall-clock step far larger than either bound, applied in both directions. */
    private static final long AN_HOUR_MS = 3_600_000L;

    /** Length 4, crc32c 4, magic 1, attributes 1, offset 8, timestamp 8, keyLen 4, valueLen 4. */
    private static final int FRAMING_BYTES_WITHOUT_KEY = 34;

    private static final int VALUE_BYTES = 8 * 1024;

    /** About 7.9 MiB of records: more than any socket buffer holds, and less than a fetch may carry. */
    private static final int RECORDS = 960;
    private static final int RECORDS_PER_PRODUCE = 60;

    /**
     * Room for the whole of that answer, since {@code max.request.bytes} is also what bounds the
     * records a fetch is served. It is larger than the default only so that the answer can be larger
     * than a socket buffer; nothing here is about the request bound itself.
     */
    private static final int MAX_REQUEST_BYTES = 12 * 1024 * 1024;

    private static final int SMALL_RECEIVE_BUFFER_BYTES = 16 * 1024;
    private static final int DRAIN_CHUNK_BYTES = 16 * 1024;
    private static final int A_FEW_BYTES = 64;

    /** More than the answer can hold, so the fetch stops at what the broker will serve rather than here. */
    private static final int PLENTY_OF_BYTES = Integer.MAX_VALUE;

    private static final int ANSWER_IMMEDIATELY_MS = 0;
    private static final int ONE_BYTE_IS_ENOUGH = 1;

    /** The wall clock: what a record is stamped with and what a fetch's wait expires against. */
    private final AtomicLong nowMillis = new AtomicLong(FIRST_TIMESTAMP_MS);

    /** Elapsed time, which is what every write of this broker's is measured against. */
    private final AtomicLong nowNanos = new AtomicLong();

    /**
     * The largest reading the elapsed clock has handed out. While an answer is being written it is the
     * broker's last write progress and nothing else: no other thread in these tests reads this clock
     * between the first byte of a response and its last.
     */
    private final AtomicLong lastReadingNanos = new AtomicLong();

    private final TimeSource clock = nowMillis::get;

    private final MonotonicTimeSource elapsed = () -> {
        long reading = nowNanos.get();
        lastReadingNanos.accumulateAndGet(reading, Math::max);
        return reading;
    };

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

    /**
     * The claim the whole bound is shaped around: a peer that keeps taking bytes is never closed,
     * however long the answer takes it. Time here is moved to just under the bound past the broker's
     * last write progress before every pass, so the connection spends the entire transfer one
     * nanosecond short of being closed and is never closed — and the answer costs hundreds of bounds
     * of elapsed time in total, which is precisely what a cap on total serve time would have killed it
     * for.
     */
    @Test
    void servesAnAnswerLargerThanTheSocketBuffersToAPeerThatDrainsItSlowly() throws Exception {
        startBroker(BrokerConfig.DEFAULT_CONNECTION_CAP);
        produceARangeLargerThanASocketBuffer();

        try (WireClient slow = WireClient.connectWithASmallReceiveBuffer(broker, SMALL_RECEIVE_BUFFER_BYTES)) {
            slow.send(1, fetchOfEverything());
            int promisedBytes = slow.receiveResponseLength();
            ByteArrayOutputStream answer = new ByteArrayOutputStream(promisedBytes);

            int takenBytes = 0;
            while (takenBytes < promisedBytes) {
                int chunkBytes = Math.min(DRAIN_CHUNK_BYTES, promisedBytes - takenBytes);
                answer.write(slow.receiveExactly(chunkBytes));
                takenBytes += chunkBytes;

                nowNanos.set(lastReadingNanos.get() + JUST_UNDER_THE_BOUND_NANOS);
                assertEquals(0, broker.closeStalledConnections(nowNanos.get()),
                        "a peer that is still taking bytes is making progress, whatever the whole answer has cost");
            }

            assertTrue(nowNanos.get() > MANY_BOUNDS * WRITE_TIMEOUT_NANOS,
                    "the transfer has to outlast the bound many times over for this to prove anything, and it took "
                            + nowNanos.get() + "ns");
            assertEquals(RECORDS, recordsIn(decode(answer.toByteArray())).size(),
                    "and every record the header promised arrived, in one whole frame");
            assertInstanceOf(ResponseDecoding.Answered.class,
                    slow.call(2, new CreateTopicRequest(ANOTHER_TOPIC, ONLY_PARTITION_COUNT)),
                    "on the same connection, which was never closed under it");
        }
    }

    /**
     * The other side of the same bound, and the denial #33 is about: a peer that asks for an answer
     * larger than the socket buffers and stops reading parks this broker's connection thread inside a
     * write that will never return. The place it holds under the connection cap comes back through the
     * one path that gives one back — its own thread, as the closed socket ends the write it was parked
     * in — which is what the last two assertions are for.
     */
    @Test
    void closesAPeerThatStoppedReadingItsAnswerAndGivesItsPlaceUnderTheCapBack() throws Exception {
        startBroker(ONE_CONNECTION_AT_A_TIME);
        produceARangeLargerThanASocketBuffer();

        CountDownLatch placeGivenBack = whenTheNextConnectionEnds();
        try (WireClient deaf = WireClient.connectWithASmallReceiveBuffer(broker, SMALL_RECEIVE_BUFFER_BYTES)) {
            deaf.send(1, fetchOfEverything());
            int promisedBytes = deaf.receiveResponseLength();
            deaf.receiveExactly(A_FEW_BYTES);
            // And nothing after that: the answer is on the wire, unfinished, and its peer has stopped
            // taking it. The clock is not moved either, so every stamp the write took reads zero and
            // the only thing that changes below is when a pass happens.

            assertEquals(0, broker.closeStalledConnections(JUST_UNDER_THE_BOUND_NANOS),
                    "one nanosecond short of the bound is a bound that has not been crossed");
            assertEquals(1, broker.closeStalledConnections(WRITE_TIMEOUT_NANOS),
                    "a peer that has taken nothing for the whole bound is one this broker stops writing to");
            assertTrue(Await.value(clientThread.submit(() -> socketEndsBefore(deaf, promisedBytes - A_FEW_BYTES)),
                            "the closed connection to reach its client as a socket that ends"),
                    "what the client is owed it does not get: the answer stops part way through");
        }

        Await.latch(placeGivenBack, "the closed connection's own thread to give back its place under the cap");
        try (WireClient served = WireClient.connectTo(broker)) {
            assertInstanceOf(ResponseDecoding.Answered.class,
                    served.call(2, new CreateTopicRequest(ANOTHER_TOPIC, ONLY_PARTITION_COUNT)),
                    "the place the stalled write held came back with it, so the cap did not shrink by one");
        }
    }

    /**
     * An answer that has gone is not a write making no progress. Nothing about the write bound may
     * survive the last byte of a response, or every connection between two requests would be one this
     * broker is about to close.
     *
     * <p>The second request is what makes this exact rather than nearly exact. A connection clears its
     * write stamp on its own thread, one instant after its client already has the last byte, and this
     * test moves the clock by bounds inside instants — so it asks for something the broker can only
     * answer by having gone back to reading, and only then does it look. What is left uncertain
     * afterwards is that second answer's own stamp, which is why the pass below lands one nanosecond
     * short of a bound past it: whichever of the two states the connection is in, this pass must close
     * nothing, and it would close something if the fetch's write bound had outlived the fetch.
     */
    @Test
    void neverClosesAConnectionWhoseAnswerHasAlreadyGone() throws Exception {
        startBroker(BrokerConfig.DEFAULT_CONNECTION_CAP);
        String value = "small enough to be written in one go";
        produce(value);

        try (WireClient brisk = WireClient.connectTo(broker)) {
            FetchResponse answer = fetchResponse(brisk.call(1, fetchOfEverything()));
            assertEquals(List.of(value), valuesIn(answer), "the whole answer went while the clock stood still");

            nowNanos.set(MANY_BOUNDS * WRITE_TIMEOUT_NANOS);
            assertInstanceOf(ResponseDecoding.Answered.class,
                    brisk.call(2, new CreateTopicRequest(ANOTHER_TOPIC, ONLY_PARTITION_COUNT)),
                    "the connection answers again five bounds later, so it went back to reading after the fetch");

            assertEquals(0, broker.closeStalledConnections(nowNanos.get() + JUST_UNDER_THE_BOUND_NANOS),
                    "and six bounds after the fetch was written there is no write here for a bound to be about");
            assertInstanceOf(ResponseDecoding.Answered.class,
                    brisk.call(3, new FetchRequest(TOPIC, PARTITION, 0L, PLENTY_OF_BYTES, ANSWER_IMMEDIATELY_MS,
                            ONE_BYTE_IS_ENOUGH)),
                    "on a connection nothing closed under it");
        }
    }

    /**
     * A long-poll fetch is waiting to write rather than writing, so the write bound must not reach it
     * either — the same regression the read bound has, from the other end. The shape is what a bound
     * on serving rather than on writing could not survive: the bound is a minute of no progress, the
     * fetch's wait is thirty seconds of wall-clock time it never spends, and the reaper is asked
     * {@value #SWEEPS_WHILE_THE_FETCH_WAITS} times at elapsed readings that are each further past the
     * bound than the last.
     */
    @Test
    void keepsALongPollThatHasNotBegunWritingHoweverManyWriteBoundsGoBy() throws Exception {
        startBroker(BrokerConfig.DEFAULT_CONNECTION_CAP);
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
                assertEquals(0, broker.closeStalledConnections(sweep * WRITE_TIMEOUT_NANOS),
                        "a fetch that has not begun writing is not a write that is getting nowhere");
            }
            produce(value);

            FetchResponse answer = fetchResponse(Await.value(fetch,
                    "the long poll that outlasted the write bound many times over"));
            assertEquals(List.of(value), valuesIn(answer), "and it is answered with its records, not with a close");
        }
    }

    /**
     * The write bound is an amount of time that has to pass with nothing moving, not a date that has
     * to be reached, and a stepped wall clock must not move it in either direction — the same claim
     * {@code BrokerReadTimeoutTest} makes for the read bound, on the half of a connection the reaper
     * could not see before this bound existed.
     */
    @Test
    void measuresTheWriteBoundOnElapsedTimeSoAWallClockStepDoesNotMoveIt() throws Exception {
        startBroker(BrokerConfig.DEFAULT_CONNECTION_CAP);
        produceARangeLargerThanASocketBuffer();

        try (WireClient deaf = WireClient.connectWithASmallReceiveBuffer(broker, SMALL_RECEIVE_BUFFER_BYTES)) {
            deaf.send(1, fetchOfEverything());
            int promisedBytes = deaf.receiveResponseLength();
            deaf.receiveExactly(A_FEW_BYTES);

            nowMillis.set(FIRST_TIMESTAMP_MS + AN_HOUR_MS);
            assertEquals(0, broker.closeStalledConnections(JUST_UNDER_THE_BOUND_NANOS),
                    "an hour added to the date is not an hour of an answer going nowhere");
            nowMillis.set(FIRST_TIMESTAMP_MS - AN_HOUR_MS);
            assertEquals(0, broker.closeStalledConnections(JUST_UNDER_THE_BOUND_NANOS),
                    "and an hour taken off it is not a bound that has been reset either");

            assertEquals(1, broker.closeStalledConnections(WRITE_TIMEOUT_NANOS),
                    "what closes the connection is the bound's worth of elapsed time, and only that");
            assertTrue(Await.value(clientThread.submit(() -> socketEndsBefore(deaf, promisedBytes - A_FEW_BYTES)),
                            "the closed connection to reach its client as a socket that ends"),
                    "with the wall clock still an hour behind where the fetch was asked for");
        }
    }

    private void startBroker(int connectionCap) {
        BrokerConfig bounds = BrokerHarness.configWithTimeouts(dataDirectory, connectionCap, READ_TIMEOUT_MS,
                WRITE_TIMEOUT_MS);
        BrokerConfig config = new BrokerConfig(bounds.dataDirectory(), bounds.port(), MAX_REQUEST_BYTES,
                bounds.maxFetchWaitMs(), bounds.zeroCopyFetch(), bounds.connectionCap(), bounds.readTimeoutMs(),
                bounds.writeTimeoutMs(), bounds.maxTotalPartitions(), bounds.maxTotalGroups(), bounds.readyFilePath(),
                bounds.logConfig());
        broker = ShrikeBroker.start(config, clock, InetAddress.getLoopbackAddress(), elapsed);
        clientThread = Executors.newSingleThreadExecutor();
    }

    /**
     * Creates the topic and fills it, on one connection, and returns once that connection is gone
     * from the broker: the tests below count what a pass closes, and a connection nobody is testing
     * would be counted too.
     */
    private void produceARangeLargerThanASocketBuffer() throws IOException {
        CountDownLatch producerEnded = whenTheNextConnectionEnds();
        try (WireClient producer = WireClient.connectTo(broker)) {
            producer.call(1, new CreateTopicRequest(TOPIC, ONLY_PARTITION_COUNT));
            int produced = 0;
            while (produced < RECORDS) {
                int batch = Math.min(RECORDS_PER_PRODUCE, RECORDS - produced);
                List<ProducedRecord> records = new ArrayList<>(batch);
                for (int record = 0; record < batch; record++) {
                    records.add(new ProducedRecord(null, valueOf(produced + record)));
                }
                producer.call(2, new ProduceRequest(TOPIC, PARTITION, records));
                produced += batch;
            }
        }
        Await.latch(producerEnded, "the producing connection to end, leaving only the connection under test");
    }

    private void produce(String value) throws IOException {
        CountDownLatch producerEnded = whenTheNextConnectionEnds();
        try (WireClient producer = WireClient.connectTo(broker)) {
            producer.call(3, new CreateTopicRequest(TOPIC, ONLY_PARTITION_COUNT));
            producer.call(4, new ProduceRequest(TOPIC, PARTITION,
                    List.of(new ProducedRecord(null, value.getBytes(UTF_8)))));
        }
        Await.latch(producerEnded, "the producing connection to end, leaving only the connection under test");
    }

    /**
     * @return a latch the next connection to end counts down, which is the only instant at which a
     *         test can be sure that connection is no longer one a pass will look at
     */
    private CountDownLatch whenTheNextConnectionEnds() {
        CountDownLatch ended = new CountDownLatch(1);
        broker.onConnectionEnded(ended::countDown);
        return ended;
    }

    /**
     * @param client    a client that has been promised more of an answer than it has taken
     * @param owedBytes how much of it is still owed
     * @return whether the socket ended before those bytes arrived, which is what a connection closed
     *         part way through an answer looks like from the other end. The failure is the answer
     *         here, so it is caught rather than thrown
     */
    private static boolean socketEndsBefore(WireClient client, int owedBytes) {
        try {
            client.receiveExactly(owedBytes);
            return false;
        } catch (IOException socketEnded) {
            return true;
        }
    }

    private static FetchRequest fetchOfEverything() {
        return new FetchRequest(TOPIC, PARTITION, 0L, PLENTY_OF_BYTES, ANSWER_IMMEDIATELY_MS, ONE_BYTE_IS_ENOUGH);
    }

    private static FetchResponse decode(byte[] responseBody) throws IOException {
        return fetchResponse(ResponseFrame.decode(ApiKeys.FETCH, ByteBuffer.wrap(responseBody)));
    }

    private static FetchResponse fetchResponse(ResponseDecoding answer) {
        return (FetchResponse) assertInstanceOf(ResponseDecoding.Answered.class, answer, answer.toString())
                .response();
    }

    private static List<StoredRecord> recordsIn(FetchResponse response) {
        return WireRecords.decode(ByteBuffer.wrap(response.records()));
    }

    private static List<String> valuesIn(FetchResponse response) {
        return recordsIn(response).stream()
                .map(StoredRecord::value)
                .map(value -> new String(value, UTF_8))
                .toList();
    }

    private static byte[] valueOf(int record) {
        byte[] value = new byte[VALUE_BYTES];
        byte[] label = "payload-%05d".formatted(record).getBytes(UTF_8);
        System.arraycopy(label, 0, value, 0, label.length);
        return value;
    }
}
