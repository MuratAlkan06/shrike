package io.shrike.core.log;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.shrike.core.time.TimeSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Retention: which sealed segments a log stops keeping, in what order it lets go of them, and what a
 * reader sees while it does.
 *
 * <p>Nothing here sleeps. The clock is a field a test writes, so "an hour later" is an assignment,
 * and every deletion is asked for by hand through {@code deleteRetiredSegments} rather than waited
 * for — when a sweep happens is the retention thread's business, and what a sweep decides is this
 * one's.
 */
class SegmentedLogRetentionTest {

    private static final String TOPIC = "orders";
    private static final int PARTITION = 0;

    /** length 4, crc32c 4, magic 1, attributes 1, offset 8, timestamp 8, keyLen 4, valueLen 4. */
    private static final int FRAMING_BYTES_WITHOUT_KEY = 34;

    private static final int VALUE_BYTES = 10;
    private static final int RECORD_BYTES = FRAMING_BYTES_WITHOUT_KEY + VALUE_BYTES;

    /** Room for two records and not a byte more, so every pair of records is a segment. */
    private static final int TWO_RECORD_SEGMENT_BYTES = 2 * RECORD_BYTES;

    private static final long ONE_HOUR_MS = 3_600_000L;
    private static final long FIRST_TIMESTAMP_MS = 1_700_000_000_000L;

    /** The clock every log in this test reads, and the only thing that makes time pass here. */
    private final AtomicLong nowMillis = new AtomicLong(FIRST_TIMESTAMP_MS);

    private final TimeSource clock = nowMillis::get;

    @TempDir
    Path dataDirectory;

    @Test
    void deletesTheSealedSegmentsWhoseNewestRecordIsOlderThanRetentionMs() {
        try (SegmentedLog log = open(retentionOf(ONE_HOUR_MS, LogConfig.RETENTION_DISABLED))) {
            appendRecords(log, 0, 4);
            nowMillis.addAndGet(ONE_HOUR_MS);
            appendRecords(log, 4, 6);

            int deleted = log.deleteRetiredSegments(nowMillis.get() + ONE_HOUR_MS);

            assertEquals(2, deleted, "both segments whose newest record is an hour behind the bound go");
            assertEquals(4L, log.logStartOffset());
            assertEquals(1, log.segmentCount());
            assertFalse(Files.exists(logFileOf(0L)));
            assertFalse(Files.exists(indexFileOf(0L)));
            assertFalse(Files.exists(logFileOf(2L)));
        }
    }

    @Test
    void keepsASegmentHoldingOneRecordInsideRetentionMs() {
        try (SegmentedLog log = open(retentionOf(ONE_HOUR_MS, LogConfig.RETENTION_DISABLED))) {
            appendRecords(log, 0, 1);
            nowMillis.addAndGet(ONE_HOUR_MS);
            // Offsets 0 and 1 share a segment, and this one is young, so the whole segment stays.
            appendRecords(log, 1, 4);

            int deleted = log.deleteRetiredSegments(nowMillis.get());

            assertEquals(0, deleted, "a segment is judged by its newest record, not its oldest");
            assertEquals(0L, log.logStartOffset());
            assertArrayEquals(valueOf(0), log.read(0L).value());
        }
    }

    @Test
    void datesAReopenedSegmentFromItsLastRecordRatherThanFromTheFilesOnDisk() throws IOException {
        try (SegmentedLog log = open(retentionOf(ONE_HOUR_MS, LogConfig.RETENTION_DISABLED))) {
            appendRecords(log, 0, 4);
            nowMillis.addAndGet(ONE_HOUR_MS);
            appendRecords(log, 4, 6);
        }

        // Every file now looks as though it were written this instant, which is what a restore from a
        // backup does. Retention must not believe it: the timestamps are in the records.
        FileTime restoredJustNow = FileTime.from(Instant.now());
        for (long baseOffset : new long[] {0L, 2L, 4L}) {
            Files.setLastModifiedTime(logFileOf(baseOffset), restoredJustNow);
            Files.setLastModifiedTime(indexFileOf(baseOffset), restoredJustNow);
        }

        try (SegmentedLog reopened = open(retentionOf(ONE_HOUR_MS, LogConfig.RETENTION_DISABLED))) {
            int deleted = reopened.deleteRetiredSegments(FIRST_TIMESTAMP_MS + ONE_HOUR_MS);

            assertEquals(2, deleted, "the reopened log read each sealed segment's last frame for its age");
            assertEquals(4L, reopened.logStartOffset());
            assertArrayEquals(valueOf(4), reopened.read(4L).value());
        }
    }

    @Test
    void deletesTheOldestSegmentsFirstUntilThePartitionIsUnderRetentionBytes() {
        // Seven records fill three whole segments and start a fourth: 308 bytes against a bound of 176.
        long twoSegmentsOfBytes = 2L * TWO_RECORD_SEGMENT_BYTES;

        try (SegmentedLog log = open(retentionOf(LogConfig.RETENTION_DISABLED, twoSegmentsOfBytes))) {
            appendRecords(log, 0, 7);
            assertEquals(4, log.segmentCount());
            assertEquals(7L * RECORD_BYTES, log.logBytes());

            int deleted = log.deleteRetiredSegments(nowMillis.get());

            assertEquals(2, deleted, "segments come off the front until what is left is under the bound");
            assertEquals(4L, log.logStartOffset(), "and the oldest is always the one that goes");
            assertEquals(TWO_RECORD_SEGMENT_BYTES + RECORD_BYTES, log.logBytes());
            assertArrayEquals(valueOf(4), log.read(4L).value(), "everything above the new start still reads");
            assertArrayEquals(valueOf(6), log.read(6L).value());
        }
    }

    @Test
    void keepsTheSegmentStillTakingRecordsHoweverOldOrHoweverLargeItIs() {
        try (SegmentedLog log = open(retentionOf(0L, 0L))) {
            appendRecords(log, 0, 3);

            int deleted = log.deleteRetiredSegments(nowMillis.get() + ONE_HOUR_MS);

            assertEquals(1, deleted, "the sealed segment goes and the active one stays, whatever the bounds say");
            assertEquals(2L, log.logStartOffset());
            assertEquals(1, log.segmentCount());
            assertArrayEquals(valueOf(2), log.read(2L).value());
            assertEquals(3L, log.append(recordNumber(3)), "and the partition goes on taking records");
        }
    }

    @Test
    void refusesReadsBelowTheStartOffsetRetentionMovedAndServesEverythingAboveIt() {
        try (SegmentedLog log = open(retentionOf(0L, LogConfig.RETENTION_DISABLED))) {
            appendRecords(log, 0, 5);

            log.deleteRetiredSegments(nowMillis.get() + ONE_HOUR_MS);

            assertEquals(4L, log.logStartOffset());
            OffsetOutOfRangeException refusal = assertThrows(OffsetOutOfRangeException.class, () -> log.read(3L));
            assertEquals(4L, refusal.logStartOffset(), "the refusal names the offset a reader should reset to");
            assertEquals(5L, refusal.nextOffset());
            assertArrayEquals(valueOf(4), log.read(4L).value());
            assertEquals(RECORD_BYTES, log.readRange(4L, 5L, Integer.MAX_VALUE).length);
        }
    }

    @Test
    void deletesNothingWhenBothRetentionBoundsAreOff() {
        try (SegmentedLog log = open(LogConfig.defaults())) {
            appendRecords(log, 0, 3);
            nowMillis.addAndGet(1_000L * ONE_HOUR_MS);

            int deleted = log.deleteRetiredSegments(nowMillis.get());

            assertEquals(0, deleted, "the defaults keep every record, which is what they did before retention");
            assertEquals(0L, log.logStartOffset());
        }
    }

    /**
     * The one thing that makes deleting a file under a reader safe, proven rather than argued:
     * unlinking removes a name, and a channel opened before the unlink goes on reading the bytes it
     * opened. The reader here is a channel this test holds part way through a segment, which is the
     * shape of a fetch that is mid-file when a sweep decides that segment is retired.
     */
    @Test
    void readsASegmentWholeThroughAChannelOpenedBeforeRetentionDeletedIt() throws IOException {
        try (SegmentedLog log = open(retentionOf(0L, LogConfig.RETENTION_DISABLED))) {
            appendRecords(log, 0, 6);
            Path oldestSegment = logFileOf(0L);
            byte[] beforeDeletion = Files.readAllBytes(oldestSegment);

            try (FileChannel reader = FileChannel.open(oldestSegment, StandardOpenOption.READ)) {
                ByteBuffer firstFrame = ByteBuffer.allocate(RECORD_BYTES);
                ByteChannels.readFully(reader, firstFrame, 0L);

                int deleted = log.deleteRetiredSegments(nowMillis.get() + ONE_HOUR_MS);

                ByteBuffer secondFrame = ByteBuffer.allocate(RECORD_BYTES);
                ByteChannels.readFully(reader, secondFrame, RECORD_BYTES);

                assertEquals(2, deleted);
                assertFalse(Files.exists(oldestSegment), "the name is gone from the directory");
                assertEquals(beforeDeletion.length, reader.size(), "and the bytes behind it are not");
                assertFalse(secondFrame.hasRemaining(), "the read that spanned the deletion was not cut short");
                assertArrayEquals(Arrays.copyOfRange(beforeDeletion, 0, RECORD_BYTES),
                        firstFrame.array());
                assertArrayEquals(Arrays.copyOfRange(beforeDeletion, RECORD_BYTES, 2 * RECORD_BYTES),
                        secondFrame.array());
                assertRecordDecodes(firstFrame.array(), 0L);
                assertRecordDecodes(secondFrame.array(), 1L);
            }
        }
    }

    /**
     * The same property, from the side a fetch uses it from. A range opened for sending holds a
     * channel of its own on the segment file, so the sweep that closes the segment's channel and
     * unlinks its two names cannot cut the transfer short — and this asks for the whole range
     * <em>after</em> the deletion, which is the worst version of the timing a connection thread can
     * meet.
     */
    @Test
    void transfersARangeOpenedBeforeRetentionDeletedTheSegmentItIsIn() throws IOException {
        try (SegmentedLog log = open(retentionOf(0L, LogConfig.RETENTION_DISABLED))) {
            appendRecords(log, 0, 6);
            byte[] beforeDeletion = Files.readAllBytes(logFileOf(0L));

            ByteArrayOutputStream sent = new ByteArrayOutputStream();
            try (RecordRange range = log.openRange(0L, 2L, Integer.MAX_VALUE);
                    WritableByteChannel destination = Channels.newChannel(sent)) {
                int deleted = log.deleteRetiredSegments(nowMillis.get() + ONE_HOUR_MS);

                range.transferTo(destination);

                assertEquals(2, deleted);
                assertFalse(Files.exists(logFileOf(0L)), "the name is gone from the directory");
                assertEquals(beforeDeletion.length, range.lengthBytes(),
                        "and the range still promises every byte the segment held");
            }

            assertArrayEquals(beforeDeletion, sent.toByteArray(),
                    "a transfer across a deletion sends the bytes it was opened over, whole");
            assertRecordDecodes(Arrays.copyOfRange(sent.toByteArray(), 0, RECORD_BYTES), 0L);
            assertRecordDecodes(Arrays.copyOfRange(sent.toByteArray(), RECORD_BYTES, 2 * RECORD_BYTES), 1L);
        }
    }

    /**
     * @param frameBytes one whole record frame, length field included
     * @param offset     the offset that frame must carry
     */
    private static void assertRecordDecodes(byte[] frameBytes, long offset) {
        StoredRecord record = RecordFrame.decodeBody(ByteBuffer.wrap(frameBytes, RecordFrame.LENGTH_FIELD_BYTES,
                frameBytes.length - RecordFrame.LENGTH_FIELD_BYTES).slice());

        assertEquals(offset, record.offset(), "a frame read across a deletion still checksums and decodes");
        assertArrayEquals(valueOf((int) offset), record.value());
    }

    private SegmentedLog open(LogConfig config) {
        return SegmentedLog.open(dataDirectory, TOPIC, PARTITION, clock, config);
    }

    private static LogConfig retentionOf(long retentionMs, long retentionBytes) {
        return new LogConfig(LogConfig.DEFAULT_MAX_RECORD_BYTES, TWO_RECORD_SEGMENT_BYTES,
                LogConfig.DEFAULT_INDEX_INTERVAL_BYTES, retentionMs, retentionBytes);
    }

    private static void appendRecords(SegmentedLog log, int fromRecord, int toRecord) {
        for (int record = fromRecord; record < toRecord; record++) {
            log.append(recordNumber(record));
        }
    }

    private static ProducedRecord recordNumber(int record) {
        return new ProducedRecord(null, valueOf(record));
    }

    private static byte[] valueOf(int record) {
        return "payload-%02d".formatted(record).getBytes(UTF_8);
    }

    private Path logFileOf(long baseOffset) {
        return dataDirectory.resolve(TOPIC + "-" + PARTITION).resolve("%020d.log".formatted(baseOffset));
    }

    private Path indexFileOf(long baseOffset) {
        return dataDirectory.resolve(TOPIC + "-" + PARTITION).resolve("%020d.index".formatted(baseOffset));
    }
}
