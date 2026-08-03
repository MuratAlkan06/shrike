package io.shrike.core.net;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.shrike.core.log.ProducedRecord;
import io.shrike.core.log.StoredRecord;
import io.shrike.core.protocol.CreateTopicRequest;
import io.shrike.core.protocol.FetchRequest;
import io.shrike.core.protocol.FetchResponse;
import io.shrike.core.protocol.ProduceRequest;
import io.shrike.core.protocol.ResponseDecoding;
import io.shrike.core.protocol.WireRecords;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The long poll: a fetch that finds too few records waits for more, under the same lock the append
 * that would satisfy it has to take.
 *
 * <p>Two of these tests are sequenced by the {@code onWaiterRegistered} seam, which fires under the
 * partition lock at the instant a fetch has decided to wait and has not let go of the lock yet. That
 * is the only window a lost wakeup could live in, and landing an append in it by hand is the only way
 * to prove the window is closed. Nothing here sleeps, and nothing here asserts how long something
 * took — except {@link #answersAQuietPartitionWithinTheMaxWaitMsItWasGiven}, which is this slice's one
 * sanctioned real-time test and asserts an upper bound only.
 */
class BrokerFetchWaitTest {

    private static final String TOPIC = "orders";
    private static final int PARTITION = 0;
    private static final int ONLY_PARTITION_COUNT = 1;

    /** Long enough that a fetch which waited it out would fail this test's bounded await instead. */
    private static final int LONG_WAIT_MS = 60_000;

    private static final int ONE_BYTE_IS_ENOUGH = 1;
    private static final int PLENTY_OF_BYTES = 64 * 1024;
    private static final int ANSWER_IMMEDIATELY_MS = 0;

    /** Fewer bytes than one record's frame occupies, so it is also the most this fetch can be served. */
    private static final int FEWER_BYTES_THAN_ONE_RECORD = 32;

    /** What the wire format lets a client ask for, and what this broker will not do. */
    private static final int MORE_THAN_THIS_BROKER_WILL_DO = Integer.MAX_VALUE;

    @TempDir
    Path dataDirectory;

    private ShrikeBroker broker;
    private ExecutorService fetchThread;

    @BeforeEach
    void startBroker() throws IOException {
        broker = BrokerHarness.start(dataDirectory);
        fetchThread = Executors.newSingleThreadExecutor();
        try (WireClient client = WireClient.connectTo(broker)) {
            client.call(1, new CreateTopicRequest(TOPIC, ONLY_PARTITION_COUNT));
        }
    }

    @AfterEach
    void stopBroker() {
        fetchThread.shutdownNow();
        broker.close();
    }

    @Test
    void servesAWaitingFetchTheRecordThatLandsTheInstantItRegisteredAsAWaiter() throws Exception {
        CountDownLatch waiterRegistered = new CountDownLatch(1);
        broker.partition(TOPIC, PARTITION).orElseThrow().onWaiterRegistered(waiterRegistered::countDown);

        Future<ResponseDecoding> fetch = fetchThread.submit(() -> {
            try (WireClient client = WireClient.connectTo(broker)) {
                return client.call(7, new FetchRequest(TOPIC, PARTITION, 0L, PLENTY_OF_BYTES, LONG_WAIT_MS,
                        ONE_BYTE_IS_ENOUGH));
            }
        });
        Await.latch(waiterRegistered, "the fetch to register as a waiter");
        produce("landed while the fetch was registering");

        ResponseDecoding answer = Await.value(fetch, "the fetch that was waiting for a record");
        ResponseDecoding.Answered answered = assertInstanceOf(ResponseDecoding.Answered.class, answer);
        assertEquals(7, answered.correlationId());
        FetchResponse response = (FetchResponse) answered.response();
        assertEquals(1L, response.highWaterMark());
        assertEquals(List.of("landed while the fetch was registering"), valuesIn(response));
    }

    @Test
    void keepsAFetchWaitingWhileTheBytesItCanSeeAreFewerThanItsMinBytes() throws Exception {
        produce("first");
        int oneRecordBytes = fetchedBytes();

        CountDownLatch waiterRegistered = new CountDownLatch(1);
        CountDownLatch fetchAnswered = new CountDownLatch(1);
        broker.partition(TOPIC, PARTITION).orElseThrow().onWaiterRegistered(waiterRegistered::countDown);
        int moreThanOneRecordBytes = oneRecordBytes + 1;

        Future<ResponseDecoding> fetch = fetchThread.submit(() -> {
            try (WireClient client = WireClient.connectTo(broker)) {
                ResponseDecoding answer = client.call(11, new FetchRequest(TOPIC, PARTITION, 0L, PLENTY_OF_BYTES,
                        LONG_WAIT_MS, moreThanOneRecordBytes));
                fetchAnswered.countDown();
                return answer;
            }
        });
        Await.latch(waiterRegistered, "the fetch to register as a waiter");

        int visibleBytes = fetchedBytes();
        assertEquals(oneRecordBytes, visibleBytes, "a fetch that does not wait sees the record that is already there");
        assertEquals(1L, fetchAnswered.getCount(), "the waiting fetch has fewer bytes than its minBytes, so it waits");

        produce("second");

        ResponseDecoding answer = Await.value(fetch, "the fetch that was waiting for its minBytes");
        FetchResponse response = (FetchResponse) assertInstanceOf(ResponseDecoding.Answered.class, answer).response();
        assertTrue(response.records().length >= moreThanOneRecordBytes,
                "the answer carries at least the minBytes it waited for");
        assertEquals(List.of("first", "second"), valuesIn(response));
        assertEquals(2L, response.highWaterMark());
    }

    /**
     * Causality, not duration: a fetch asking for more bytes than it could ever be served, for longer
     * than this broker will ever hold one open, is answered the moment enough records to fill it land.
     * Without the clamps its predicate is unsatisfiable and its deadline is about twenty-five days, so
     * the record below arrives and the fetch keeps waiting until this test's bounded await gives up.
     */
    @Test
    void answersAFetchAskingForMoreBytesThanItCanBeServedAsSoonAsItCanBeFilled() throws Exception {
        String value = "landed for a fetch that asked for more than it could be given";
        CountDownLatch waiterRegistered = new CountDownLatch(1);
        broker.partition(TOPIC, PARTITION).orElseThrow().onWaiterRegistered(waiterRegistered::countDown);

        Future<ResponseDecoding> fetch = fetchThread.submit(() -> {
            try (WireClient client = WireClient.connectTo(broker)) {
                return client.call(13, new FetchRequest(TOPIC, PARTITION, 0L, FEWER_BYTES_THAN_ONE_RECORD,
                        MORE_THAN_THIS_BROKER_WILL_DO, MORE_THAN_THIS_BROKER_WILL_DO));
            }
        });
        Await.latch(waiterRegistered, "the fetch to register as a waiter");
        produce(value);

        ResponseDecoding answer = Await.value(fetch, "the fetch whose minBytes was more than it could be served");
        FetchResponse response = (FetchResponse) assertInstanceOf(ResponseDecoding.Answered.class, answer).response();
        assertEquals(1L, response.highWaterMark());
        assertEquals(List.of(value), valuesIn(response), "and it is answered with the record, not with nothing");
    }

    /**
     * The one real-time test this slice is allowed. It asserts an upper bound and nothing else: the
     * margin is many times the wait, so only a fetch that ignores its deadline altogether can fail it.
     */
    @Test
    void answersAQuietPartitionWithinTheMaxWaitMsItWasGiven() throws Exception {
        int shortWaitMs = 250;
        long generousMarginMs = 10_000L;

        long startNanos = System.nanoTime();
        try (WireClient client = WireClient.connectTo(broker)) {
            ResponseDecoding answer = client.call(3,
                    new FetchRequest(TOPIC, PARTITION, 0L, PLENTY_OF_BYTES, shortWaitMs, ONE_BYTE_IS_ENOUGH));
            long elapsedMs = NANOSECONDS.toMillis(System.nanoTime() - startNanos);

            FetchResponse response = (FetchResponse) assertInstanceOf(ResponseDecoding.Answered.class,
                    answer).response();
            assertEquals(0, response.records().length, "a quiet partition has nothing to answer with");
            assertEquals(0L, response.highWaterMark());
            assertTrue(elapsedMs < shortWaitMs + generousMarginMs,
                    "a fetch on a quiet partition must return within its maxWaitMs, but took " + elapsedMs + "ms");
        }
    }

    @Test
    void answersAFetchAtTheHighWaterMarkImmediatelyWithNoRecords() throws Exception {
        produce("first");
        produce("second");

        try (WireClient client = WireClient.connectTo(broker)) {
            ResponseDecoding answer = client.call(5, new FetchRequest(TOPIC, PARTITION, 2L, PLENTY_OF_BYTES,
                    ANSWER_IMMEDIATELY_MS, ONE_BYTE_IS_ENOUGH));

            ResponseDecoding.Answered answered = assertInstanceOf(ResponseDecoding.Answered.class, answer,
                    "the high-water mark is in range: it is where a caught-up consumer sits");
            FetchResponse response = (FetchResponse) answered.response();
            assertEquals(0, response.records().length);
            assertEquals(2L, response.highWaterMark());
        }
    }

    private void produce(String value) throws IOException {
        try (WireClient client = WireClient.connectTo(broker)) {
            client.call(2, new ProduceRequest(TOPIC, PARTITION,
                    List.of(new ProducedRecord(null, value.getBytes(UTF_8)))));
        }
    }

    /**
     * @return how many bytes of records a fetch that refuses to wait can see right now
     */
    private int fetchedBytes() throws IOException {
        try (WireClient client = WireClient.connectTo(broker)) {
            ResponseDecoding answer = client.call(4, new FetchRequest(TOPIC, PARTITION, 0L, PLENTY_OF_BYTES,
                    ANSWER_IMMEDIATELY_MS, 0));
            return ((FetchResponse) assertInstanceOf(ResponseDecoding.Answered.class, answer).response())
                    .records().length;
        }
    }

    private static List<String> valuesIn(FetchResponse response) {
        return WireRecords.decode(ByteBuffer.wrap(response.records())).stream()
                .map(StoredRecord::value)
                .map(value -> new String(value, UTF_8))
                .toList();
    }
}
