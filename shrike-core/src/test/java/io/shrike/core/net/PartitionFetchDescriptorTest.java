package io.shrike.core.net;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.shrike.core.log.LogConfig;
import io.shrike.core.log.OffsetOutOfRangeException;
import io.shrike.core.log.ProducedRecord;
import io.shrike.core.time.TimeSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The segment file a fetch served out of the log holds open: that it is opened once however many
 * appends wake that fetch, that it is the file the records are actually in, and that it is closed once
 * and only once whichever way the fetch ends.
 *
 * <p>A descriptor that leaked leaves no trace anywhere else in this process, so every test here
 * collects the descriptors the partition opened through the {@code onSegmentFileOpened} seam and asks
 * each one afterwards whether it is still open. The count is the other half: the seam fires for an
 * {@code open(2)} and never for a range served through a descriptor its fetch already had, so
 * "opened once" is something these tests count rather than infer.
 *
 * <p>Nothing here sleeps. A fetch is driven on a thread of its own and every step of it is sequenced by
 * the {@code onWaiterRegistered} seam, which fires under the partition lock at the instant that fetch
 * has decided to wait — so an append that follows one cannot be lost and cannot be early. The clock is
 * a field this test writes, which is how the one fetch that has to reach its deadline reaches it
 * without any time passing.
 */
class PartitionFetchDescriptorTest {

    private static final String TOPIC = "orders";
    private static final int PARTITION = 0;

    /** length 4, crc32c 4, magic 1, attributes 1, offset 8, timestamp 8, keyLen 4, valueLen 4. */
    private static final int FRAMING_BYTES_WITHOUT_KEY = 34;

    private static final int VALUE_BYTES = 16;
    private static final int RECORD_BYTES = FRAMING_BYTES_WITHOUT_KEY + VALUE_BYTES;

    /** Long enough that a fetch which waited it out would fail this test's bounded await instead. */
    private static final int LONG_WAIT_MS = 60_000;

    private static final int ANSWER_IMMEDIATELY_MS = 0;
    private static final int ONE_BYTE_IS_ENOUGH = 1;
    private static final int PLENTY_OF_BYTES = 64 * 1024;

    private static final long FIRST_TIMESTAMP_MS = 1_700_000_000_000L;

    /** Everything sealed is retired the moment it is sealed, so one sweep takes the first segment. */
    private static final long DELETE_EVERY_SEALED_SEGMENT_MS = 0L;

    /** The clock every record here is stamped from, and the only thing that makes time pass. */
    private final AtomicLong nowMillis = new AtomicLong(FIRST_TIMESTAMP_MS);

    private final TimeSource clock = nowMillis::get;

    /** Every descriptor a fetch on this partition has opened, in the order they were opened. */
    private final List<FileChannel> openedDescriptors = Collections.synchronizedList(new ArrayList<>());

    /** One permit per time a fetch has registered as a waiter, which is once per wakeup that waits. */
    private final Semaphore waiterRegistrations = new Semaphore(0);

    @TempDir
    Path dataDirectory;

    private Partition partition;
    private ExecutorService fetchThread;

    @BeforeEach
    void startFetchThread() {
        fetchThread = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void closePartition() {
        fetchThread.shutdownNow();
        if (partition != null) {
            partition.close();
        }
    }

    @Test
    void opensItsSegmentFileOnceHoweverManyAppendsWakeAFetchBeforeItCanBeAnswered() {
        openPartition(oneSegmentHoldsEverything());
        int threeRecordsOfBytes = 3 * RECORD_BYTES;

        Future<FetchedRecords> fetch = fetching(0L, threeRecordsOfBytes, LONG_WAIT_MS);
        for (int record = 0; record < 3; record++) {
            awaitWaiterRegistered();
            produce(record);
        }

        try (FetchedRecords answer = Await.value(fetch, "the fetch that waited for three records")) {
            assertEquals(1, openedDescriptors.size(),
                    "two of those appends woke a fetch that already had the file open, and a wakeup is not an open");
            assertTrue(openedDescriptors.get(0).isOpen(),
                    "the descriptor left with the answer, so it is still open for the bytes to be sent through");
            assertEquals(threeRecordsOfBytes, answer.recordBytes());
            assertEquals(3L, answer.highWaterMark());
        }

        assertClosedEveryDescriptor("closing the answer is what closes it, and that is the connection's job");
    }

    @Test
    void opensNoSegmentFileForAFetchThatFindsNothingToServe() {
        openPartition(oneSegmentHoldsEverything());

        try (FetchedRecords answer = partition.fetch(0L, PLENTY_OF_BYTES, ANSWER_IMMEDIATELY_MS, ONE_BYTE_IS_ENOUGH)) {
            assertEquals(0, answer.recordBytes(), "a partition with no records answers with none");
        }

        assertEquals(List.of(), openedDescriptors, "and opens no file to say so");
    }

    @Test
    void servesTheSegmentARollStartedToAFetchWaitingAtTheOffsetThatSegmentBeginsAt() throws IOException {
        openPartition(segmentsOf(2));
        produce(0);
        produce(1);

        Future<FetchedRecords> fetch = fetching(2L, ONE_BYTE_IS_ENOUGH, LONG_WAIT_MS);
        awaitWaiterRegistered();
        produce(2);

        try (FetchedRecords answer = Await.value(fetch, "the fetch waiting where the next segment would begin")) {
            assertEquals(3L, answer.highWaterMark());
            assertArrayEquals(Files.readAllBytes(segmentFileOf(2L)), sentBytesOf(answer),
                    "the record it waited for landed in the segment the roll started, and that is the file the"
                            + " answer was sent from");
            assertEquals(1, openedDescriptors.size(), "which it opened when it knew which file that was");
        }

        assertClosedEveryDescriptor("the answer carried the only descriptor there was");
    }

    @Test
    void servesTheSegmentItAlreadyHoldsWhenTheRecordThatWokeItLandedInANewOne() throws IOException {
        openPartition(segmentsOf(2));
        produce(0);
        produce(1);
        int twoRecordsOfBytes = 2 * RECORD_BYTES;

        Future<FetchedRecords> fetch = fetching(1L, twoRecordsOfBytes, LONG_WAIT_MS);
        awaitWaiterRegistered();
        produce(2);
        awaitWaiterRegistered();
        nowMillis.addAndGet(LONG_WAIT_MS + 1L);
        produce(3);

        try (FetchedRecords answer = Await.value(fetch, "the fetch that reached its deadline one record short")) {
            assertEquals(4L, answer.highWaterMark());
            assertArrayEquals(lastRecordOf(segmentFileOf(0L)), sentBytesOf(answer),
                    "a range stops at the end of the segment its offset is in, and the roll did not move that"
                            + " offset into another file");
            assertEquals(1, openedDescriptors.size(),
                    "so the file it had open across the roll is the file it answered from");
        }

        assertClosedEveryDescriptor("and it is closed once, by the caller the answer went to");
    }

    @Test
    void closesTheSegmentFileItHeldWhenRetentionDeletesTheOffsetItIsWaitingOn() {
        openPartition(segmentsThatRetireAsSoonAsTheyAreSealed(2));
        produce(0);
        produce(1);
        produce(2);
        int fiveRecordsOfBytes = 5 * RECORD_BYTES;

        Future<OffsetOutOfRangeException> refused = fetchThread.submit(() -> assertThrows(
                OffsetOutOfRangeException.class,
                () -> partition.fetch(0L, PLENTY_OF_BYTES, LONG_WAIT_MS, fiveRecordsOfBytes)));
        awaitWaiterRegistered();
        int deleted = partition.deleteRetiredSegments(nowMillis.get());
        produce(3);

        OffsetOutOfRangeException answer = Await.value(refused, "the fetch whose offset retention deleted");
        assertEquals(1, deleted, "the segment the waiting fetch had open is the one retention retired");
        assertEquals(2L, answer.logStartOffset(), "and the refusal names the offset this partition now starts at");
        assertEquals(1, openedDescriptors.size());
        assertClosedEveryDescriptor("a refusal leaves with no range, so the fetch closes what it was holding");
    }

    /**
     * @param fetchOffset the offset to read from
     * @param minBytes    how many bytes are worth answering with before the deadline
     * @param maxWaitMs   how long the fetch may be held open
     * @return the fetch, running on a thread of its own so that this one can append to the partition it
     *         is waiting on
     */
    private Future<FetchedRecords> fetching(long fetchOffset, int minBytes, int maxWaitMs) {
        return fetchThread.submit(() -> partition.fetch(fetchOffset, PLENTY_OF_BYTES, maxWaitMs, minBytes));
    }

    private void openPartition(LogConfig logConfig) {
        partition = Partition.open(BrokerHarness.configWithLogConfig(dataDirectory, logConfig), TOPIC, PARTITION,
                clock);
        partition.onSegmentFileOpened(openedDescriptors::add);
        partition.onWaiterRegistered(waiterRegistrations::release);
    }

    /**
     * Waits for the fetch under test to register as a waiter, which it does under the partition lock and
     * one statement before it lets go of that lock. Returning from here is therefore the moment at which
     * an append is guaranteed to reach a fetch that is provably waiting for it.
     */
    private void awaitWaiterRegistered() {
        try {
            assertTrue(waiterRegistrations.tryAcquire(Await.TIMEOUT_SECONDS, SECONDS),
                    "the fetch to register as a waiter");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("interrupted while waiting for the fetch to register as a waiter", e);
        }
    }

    private void produce(int record) {
        partition.produce(List.of(new ProducedRecord(null, valueOf(record))));
    }

    private void assertClosedEveryDescriptor(String why) {
        for (FileChannel descriptor : openedDescriptors) {
            assertFalse(descriptor.isOpen(), why);
        }
    }

    /**
     * @param answer an answer served out of the segment file
     * @return the bytes it would put on a socket, which is the only place a range's bytes can be seen
     */
    private static byte[] sentBytesOf(FetchedRecords answer) throws IOException {
        ByteArrayOutputStream sent = new ByteArrayOutputStream(answer.recordBytes());
        ((FetchedRecords.Pinned) answer).records().transferTo(Channels.newChannel(sent));
        return sent.toByteArray();
    }

    private Path segmentFileOf(long baseOffset) {
        return dataDirectory.resolve(TOPIC + "-" + PARTITION).resolve("%020d.log".formatted(baseOffset));
    }

    /**
     * @param segmentFile a segment file holding whole frames of this test's one record size
     * @return the bytes of its last record, which is what a fetch at that record's offset is owed
     */
    private static byte[] lastRecordOf(Path segmentFile) throws IOException {
        byte[] segment = Files.readAllBytes(segmentFile);
        byte[] lastRecord = new byte[RECORD_BYTES];
        System.arraycopy(segment, segment.length - RECORD_BYTES, lastRecord, 0, RECORD_BYTES);
        return lastRecord;
    }

    private static LogConfig oneSegmentHoldsEverything() {
        return new LogConfig(LogConfig.DEFAULT_MAX_RECORD_BYTES, LogConfig.DEFAULT_SEGMENT_BYTES,
                LogConfig.DEFAULT_INDEX_INTERVAL_BYTES, LogConfig.RETENTION_DISABLED, LogConfig.RETENTION_DISABLED);
    }

    private static LogConfig segmentsOf(int records) {
        return new LogConfig(LogConfig.DEFAULT_MAX_RECORD_BYTES, records * RECORD_BYTES,
                LogConfig.DEFAULT_INDEX_INTERVAL_BYTES, LogConfig.RETENTION_DISABLED, LogConfig.RETENTION_DISABLED);
    }

    private static LogConfig segmentsThatRetireAsSoonAsTheyAreSealed(int records) {
        return new LogConfig(LogConfig.DEFAULT_MAX_RECORD_BYTES, records * RECORD_BYTES,
                LogConfig.DEFAULT_INDEX_INTERVAL_BYTES, DELETE_EVERY_SEALED_SEGMENT_MS,
                LogConfig.RETENTION_DISABLED);
    }

    private static byte[] valueOf(int record) {
        byte[] value = new byte[VALUE_BYTES];
        byte[] label = "payload-%03d".formatted(record).getBytes(UTF_8);
        System.arraycopy(label, 0, value, 0, label.length);
        return value;
    }
}
