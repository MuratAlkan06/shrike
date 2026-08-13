package io.shrike.core.net;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.shrike.core.log.ProducedRecord;
import io.shrike.core.protocol.CreateTopicRequest;
import io.shrike.core.protocol.FetchRequest;
import io.shrike.core.protocol.ProduceRequest;
import io.shrike.core.time.MonotonicTimeSource;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The two ways a connection dies with a fetch's segment file open, and that the descriptor goes back
 * on both of them: a client that hangs up while its long poll is still waiting, and a peer the write
 * bound closes part way through the answer it asked for.
 *
 * <p>Neither is a path the fetch itself takes, and that is the point. A range that leaves the partition
 * is closed by the connection that sends it — {@code Connection.stream} holds it in a
 * try-with-resources — so what these tests prove is that a fetch which holds its file open across a
 * wait has not moved that close anywhere: the descriptors are collected through the
 * {@code onSegmentFileOpened} seam and asked, once the connection's own thread has finished, whether
 * they are still open.
 *
 * <p>Nothing here sleeps. The elapsed-time clock is frozen at zero, so every write stamp this broker
 * takes reads zero and the only thing that moves is the reading a reaper pass is given — which the test
 * makes on its own thread, as {@code BrokerWriteTimeoutTest} does. The wall clock is the host's,
 * because the long poll below is ended by a record rather than by its deadline.
 */
class BrokerFetchDescriptorTest {

    private static final String TOPIC = "orders";
    private static final int PARTITION = 0;
    private static final int ONLY_PARTITION_COUNT = 1;

    /** length 4, crc32c 4, magic 1, attributes 1, offset 8, timestamp 8, keyLen 4, valueLen 4. */
    private static final int FRAMING_BYTES_WITHOUT_KEY = 34;

    private static final int VALUE_BYTES = 8 * 1024;
    private static final int RECORD_BYTES = FRAMING_BYTES_WITHOUT_KEY + VALUE_BYTES;

    /** About 3.8 MiB of records: more than a socket buffer holds, so a peer can be caught mid-answer. */
    private static final int RECORDS_LARGER_THAN_A_SOCKET_BUFFER = 480;
    private static final int RECORDS_PER_PRODUCE = 60;

    /** Room for the whole of that answer, since {@code max.request.bytes} bounds what a fetch is served. */
    private static final int MAX_REQUEST_BYTES = 12 * 1024 * 1024;

    /** Both bounds are minutes long, so the reaper's own thread never reaches a pass inside a test. */
    private static final int WRITE_TIMEOUT_MS = 60_000;
    private static final int READ_TIMEOUT_MS = 3_600_000;

    private static final long WRITE_TIMEOUT_NANOS = MILLISECONDS.toNanos(WRITE_TIMEOUT_MS);

    private static final int SMALL_RECEIVE_BUFFER_BYTES = 16 * 1024;
    private static final int A_FEW_BYTES = 64;

    /** Long enough that a fetch which waited it out would fail this test's bounded await instead. */
    private static final int LONG_WAIT_MS = 60_000;

    private static final int ANSWER_IMMEDIATELY_MS = 0;
    private static final int ONE_BYTE_IS_ENOUGH = 1;

    /** More than the answer can hold, so a fetch stops at what the broker will serve rather than here. */
    private static final int PLENTY_OF_BYTES = Integer.MAX_VALUE;

    /** Frozen: every write stamp this broker takes reads zero, and a pass reads what a test hands it. */
    private final MonotonicTimeSource elapsed = () -> 0L;

    /** Every descriptor a fetch on this partition has opened, in the order they were opened. */
    private final List<FileChannel> openedDescriptors = Collections.synchronizedList(new ArrayList<>());

    @TempDir
    Path dataDirectory;

    private ShrikeBroker broker;

    @AfterEach
    void stopBroker() {
        if (broker != null) {
            broker.close();
        }
    }

    @Test
    void closesTheSegmentFileAFetchWasHoldingWhenItsClientWentAwayWhileItWaited() throws IOException {
        startBroker();
        int twoRecordsOfBytes = 2 * RECORD_BYTES;

        try (WireClient producer = WireClient.connectTo(broker)) {
            producer.call(1, new CreateTopicRequest(TOPIC, ONLY_PARTITION_COUNT));
            watchTheSegmentFilesFetchesOpen();
            CountDownLatch waiterRegistered = new CountDownLatch(1);
            broker.partition(TOPIC, PARTITION).orElseThrow().onWaiterRegistered(waiterRegistered::countDown);
            producer.call(2, produceOf(0));

            WireClient poller = WireClient.connectTo(broker);
            poller.send(3, new FetchRequest(TOPIC, PARTITION, 0L, PLENTY_OF_BYTES, LONG_WAIT_MS, twoRecordsOfBytes));
            Await.latch(waiterRegistered, "the fetch to register as a waiter");
            assertEquals(1, openedDescriptors.size(),
                    "a fetch waiting for more bytes is holding the segment file the bytes it has are in");

            CountDownLatch pollerEnded = whenTheNextConnectionEnds();
            poller.close();
            producer.call(4, produceOf(1));

            Await.latch(pollerEnded, "the connection whose client had gone away to end");
            assertClosedEveryDescriptor("the answer nobody was left to read still gave its descriptor back");
        }
    }

    @Test
    void closesTheSegmentFileOfAnAnswerTheWriteBoundStoppedPartWayThrough() throws IOException {
        startBroker();
        produceARangeLargerThanASocketBuffer();
        watchTheSegmentFilesFetchesOpen();

        CountDownLatch deafConnectionEnded = whenTheNextConnectionEnds();
        try (WireClient deaf = WireClient.connectWithASmallReceiveBuffer(broker, SMALL_RECEIVE_BUFFER_BYTES)) {
            deaf.send(1, fetchOfEverything());
            deaf.receiveResponseLength();
            deaf.receiveExactly(A_FEW_BYTES);
            // And nothing after that: the answer is on the wire, unfinished, and its peer has stopped
            // taking it, so this broker is parked inside a transfer out of the file it opened.

            assertEquals(1, openedDescriptors.size(), "which is one file, opened once, for the answer being sent");
            assertTrue(openedDescriptors.get(0).isOpen(), "and still open, because the transfer has not finished");
            assertEquals(1, broker.closeStalledConnections(WRITE_TIMEOUT_NANOS),
                    "a peer that has taken nothing for the whole bound is one this broker stops writing to");
        }

        Await.latch(deafConnectionEnded, "the closed connection's own thread to come out of its transfer");
        assertClosedEveryDescriptor("a transfer the write bound stopped closes the file it was reading");
    }

    private void startBroker() {
        BrokerConfig bounds = BrokerHarness.configWithTimeouts(dataDirectory, BrokerConfig.DEFAULT_CONNECTION_CAP,
                READ_TIMEOUT_MS, WRITE_TIMEOUT_MS);
        BrokerConfig config = new BrokerConfig(bounds.dataDirectory(), bounds.port(), MAX_REQUEST_BYTES,
                bounds.maxFetchWaitMs(), bounds.zeroCopyFetch(), bounds.connectionCap(), bounds.readTimeoutMs(),
                bounds.writeTimeoutMs(), bounds.maxTotalPartitions(), bounds.maxTotalGroups(), bounds.readyFilePath(),
                bounds.logConfig());
        broker = ShrikeBroker.start(config, BrokerHarness.SYSTEM_CLOCK, InetAddress.getLoopbackAddress(), elapsed);
    }

    private void watchTheSegmentFilesFetchesOpen() {
        broker.partition(TOPIC, PARTITION).orElseThrow().onSegmentFileOpened(openedDescriptors::add);
    }

    /**
     * Creates the topic and fills it, on one connection, and returns once that connection is gone from
     * the broker: the test below counts what a pass closes, and a connection nobody is testing would be
     * counted too.
     */
    private void produceARangeLargerThanASocketBuffer() throws IOException {
        CountDownLatch producerEnded = whenTheNextConnectionEnds();
        try (WireClient producer = WireClient.connectTo(broker)) {
            producer.call(1, new CreateTopicRequest(TOPIC, ONLY_PARTITION_COUNT));
            int produced = 0;
            while (produced < RECORDS_LARGER_THAN_A_SOCKET_BUFFER) {
                int batch = Math.min(RECORDS_PER_PRODUCE, RECORDS_LARGER_THAN_A_SOCKET_BUFFER - produced);
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

    /**
     * @return a latch the next connection to end counts down, which is the only instant at which a test
     *         can be sure that connection has finished with everything it was holding
     */
    private CountDownLatch whenTheNextConnectionEnds() {
        CountDownLatch ended = new CountDownLatch(1);
        broker.onConnectionEnded(ended::countDown);
        return ended;
    }

    private void assertClosedEveryDescriptor(String why) {
        assertFalse(openedDescriptors.isEmpty(), "there has to be a descriptor for this to be about");
        for (FileChannel descriptor : openedDescriptors) {
            assertFalse(descriptor.isOpen(), why);
        }
    }

    private static ProduceRequest produceOf(int record) {
        return new ProduceRequest(TOPIC, PARTITION, List.of(new ProducedRecord(null, valueOf(record))));
    }

    private static FetchRequest fetchOfEverything() {
        return new FetchRequest(TOPIC, PARTITION, 0L, PLENTY_OF_BYTES, ANSWER_IMMEDIATELY_MS, ONE_BYTE_IS_ENOUGH);
    }

    private static byte[] valueOf(int record) {
        byte[] value = new byte[VALUE_BYTES];
        byte[] label = "payload-%05d".formatted(record).getBytes(UTF_8);
        System.arraycopy(label, 0, value, 0, label.length);
        return value;
    }
}
