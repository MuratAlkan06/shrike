package io.shrike.core.log;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.shrike.core.time.TimeSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reading a range of the log back as bytes rather than as records — what a fetch response carries.
 *
 * <p>Every expectation here is measured against the segment files themselves, because the claim is
 * that the range is the file's own bytes and not a copy that happens to decode the same way.
 */
class SegmentedLogRangeTest {

    private static final String TOPIC = "orders";
    private static final int PARTITION = 0;
    private static final TimeSource FIXED_CLOCK = () -> 1_700_000_000_000L;

    /** Length 4, crc32c 4, magic 1, attributes 1, offset 8, timestamp 8, keyLen 4, valueLen 4. */
    private static final int FRAMING_BYTES_WITHOUT_KEY = 34;
    private static final int VALUE_BYTES = 10;
    private static final int RECORD_BYTES = FRAMING_BYTES_WITHOUT_KEY + VALUE_BYTES;
    private static final int TWO_RECORD_SEGMENT_BYTES = 2 * RECORD_BYTES;

    private static final int PLENTY_OF_BYTES = 64 * 1024;

    @TempDir
    Path dataDirectory;

    @Test
    void readsBackTheVerbatimBytesOfEveryFrameInTheRange() throws IOException {
        try (SegmentedLog log = SegmentedLog.open(dataDirectory, TOPIC, PARTITION, FIXED_CLOCK)) {
            log.append(recordNumber(0));
            log.append(recordNumber(1));
            log.append(recordNumber(2));

            byte[] wholeLog = log.readRange(0L, log.nextOffset(), PLENTY_OF_BYTES);
            byte[] fromTheSecond = log.readRange(1L, log.nextOffset(), PLENTY_OF_BYTES);

            byte[] segmentFile = Files.readAllBytes(logFileOf(0L));
            assertArrayEquals(segmentFile, wholeLog);
            assertEquals(2 * RECORD_BYTES, fromTheSecond.length);
            assertArrayEquals(Arrays.copyOfRange(segmentFile, RECORD_BYTES, segmentFile.length),
                    fromTheSecond, "a range starting at offset 1 starts at the byte its frame starts at");
        }
    }

    @Test
    void stopsAtTheEndOfTheSegmentTheRangeStartsIn() {
        LogConfig twoRecordSegments = new LogConfig(LogConfig.DEFAULT_MAX_RECORD_BYTES, TWO_RECORD_SEGMENT_BYTES,
                LogConfig.DEFAULT_INDEX_INTERVAL_BYTES);

        try (SegmentedLog log = SegmentedLog.open(dataDirectory, TOPIC, PARTITION, FIXED_CLOCK, twoRecordSegments)) {
            for (int record = 0; record < 5; record++) {
                log.append(recordNumber(record));
            }

            byte[] fromTheStart = log.readRange(0L, log.nextOffset(), PLENTY_OF_BYTES);
            byte[] fromTheThird = log.readRange(2L, log.nextOffset(), PLENTY_OF_BYTES);

            assertEquals(TWO_RECORD_SEGMENT_BYTES, fromTheStart.length,
                    "a range stops at the segment boundary; the caller asks again from where it got to");
            assertEquals(TWO_RECORD_SEGMENT_BYTES, fromTheThird.length);
            assertEquals(3, log.segmentCount());
        }
    }

    @Test
    void neverReadsPastTheHighWaterMarkHoweverHighTheLimitIs() {
        try (SegmentedLog log = SegmentedLog.open(dataDirectory, TOPIC, PARTITION, FIXED_CLOCK)) {
            log.append(recordNumber(0));

            byte[] beyondTheEnd = log.readRange(0L, Long.MAX_VALUE, PLENTY_OF_BYTES);
            byte[] atTheHighWaterMark = log.readRange(1L, Long.MAX_VALUE, PLENTY_OF_BYTES);

            assertEquals(RECORD_BYTES, beyondTheEnd.length, "the limit is clamped to the high-water mark");
            assertEquals(0, atTheHighWaterMark.length,
                    "the high-water mark is where a caught-up reader sits, and it reads nothing");
        }
    }

    @Test
    void refusesARangeThatStartsOutsideTheReadableOffsets() {
        try (SegmentedLog log = SegmentedLog.open(dataDirectory, TOPIC, PARTITION, FIXED_CLOCK)) {
            log.append(recordNumber(0));

            OffsetOutOfRangeException pastTheEnd = assertThrows(OffsetOutOfRangeException.class,
                    () -> log.readRange(2L, 3L, PLENTY_OF_BYTES));
            assertThrows(OffsetOutOfRangeException.class, () -> log.readRange(-1L, 1L, PLENTY_OF_BYTES));
            assertThrows(IllegalArgumentException.class, () -> log.readRange(0L, 1L, -1));

            assertEquals(2L, pastTheEnd.requestedOffset());
            assertEquals(1L, pastTheEnd.nextOffset());
        }
    }

    @Test
    void servesTheFirstFrameWholeEvenWhenItAloneExceedsMaxBytes() {
        try (SegmentedLog log = SegmentedLog.open(dataDirectory, TOPIC, PARTITION, FIXED_CLOCK)) {
            log.append(recordNumber(0));
            log.append(recordNumber(1));

            byte[] oneByteAsked = log.readRange(0L, log.nextOffset(), 1);
            byte[] twoFramesFit = log.readRange(0L, log.nextOffset(), 2 * RECORD_BYTES);
            byte[] oneFrameFits = log.readRange(0L, log.nextOffset(), 2 * RECORD_BYTES - 1);

            assertEquals(RECORD_BYTES, oneByteAsked.length, "a whole frame comes back or a reader could never move");
            assertEquals(2 * RECORD_BYTES, twoFramesFit.length);
            assertEquals(RECORD_BYTES, oneFrameFits.length, "a frame that would cross the cap waits for the next read");
        }
    }

    /**
     * The range a fetch is sent out of the file must be the range a fetch would have been sent out of
     * a buffer, because a client is not told which of the two answered it. Both are asked here, over
     * the same log and for the same offsets, including the cases where the two could most easily
     * disagree: a segment boundary, a {@code maxBytes} landing inside a frame, the whole-frame
     * exception for a first frame larger than {@code maxBytes}, and the empty answer at the high-water
     * mark.
     */
    @Test
    void opensTheSameRangeItWouldHaveReadIntoMemory() throws IOException {
        LogConfig twoRecordSegments = new LogConfig(LogConfig.DEFAULT_MAX_RECORD_BYTES, TWO_RECORD_SEGMENT_BYTES,
                LogConfig.DEFAULT_INDEX_INTERVAL_BYTES);

        try (SegmentedLog log = SegmentedLog.open(dataDirectory, TOPIC, PARTITION, FIXED_CLOCK, twoRecordSegments)) {
            for (int record = 0; record < 5; record++) {
                log.append(recordNumber(record));
            }
            long highWaterMark = log.nextOffset();

            assertRangesAgree(log, 0L, highWaterMark, PLENTY_OF_BYTES);
            assertRangesAgree(log, 1L, highWaterMark, PLENTY_OF_BYTES);
            assertRangesAgree(log, 2L, highWaterMark, PLENTY_OF_BYTES);
            assertRangesAgree(log, 0L, highWaterMark, 2 * RECORD_BYTES - 1);
            assertRangesAgree(log, 0L, highWaterMark, 1);
            assertRangesAgree(log, 0L, 1L, PLENTY_OF_BYTES);
            assertRangesAgree(log, highWaterMark, highWaterMark, PLENTY_OF_BYTES);
        }
    }

    @Test
    void tellsWriteProgressWhatOfTheRangeHasGoneAsItGoes() throws IOException {
        try (SegmentedLog log = SegmentedLog.open(dataDirectory, TOPIC, PARTITION, FIXED_CLOCK)) {
            log.append(recordNumber(0));
            log.append(recordNumber(1));

            List<Long> reported = new ArrayList<>();
            OneBytePerCallChannel destination = new OneBytePerCallChannel();
            try (RecordRange range = log.openRange(0L, log.nextOffset(), PLENTY_OF_BYTES)) {
                range.transferTo(destination, reported::add);

                assertEquals(range.lengthBytes(), reported.size(),
                        "a transfer that moved a byte at a time is a transfer that got somewhere that many times");
                assertEquals(range.lengthBytes(), reported.stream().mapToLong(Long::longValue).sum(),
                        "and what a bounded write is told adds up to the range it was promised");
            }
            assertArrayEquals(Files.readAllBytes(logFileOf(0L)), destination.received(),
                    "the bytes are the segment's own, whoever was watching them go");
        }
    }

    /**
     * Asserts that the two ways of serving one range cover the same bytes of the same file.
     *
     * @param log         the log to ask
     * @param fetchOffset where the range starts
     * @param limitOffset the exclusive offset it stops before
     * @param maxBytes    the most bytes it may cover
     */
    private static void assertRangesAgree(SegmentedLog log, long fetchOffset, long limitOffset, int maxBytes)
            throws IOException {
        byte[] copied = log.readRange(fetchOffset, limitOffset, maxBytes);

        ByteArrayOutputStream sent = new ByteArrayOutputStream();
        try (RecordRange range = log.openRange(fetchOffset, limitOffset, maxBytes);
                WritableByteChannel destination = Channels.newChannel(sent)) {
            assertEquals(copied.length, range.lengthBytes(),
                    "the opened range promised a different size from offset " + fetchOffset);
            range.transferTo(destination);
        }

        assertArrayEquals(copied, sent.toByteArray(), "the two paths differ from offset " + fetchOffset
                + " with maxBytes=" + maxBytes);
    }

    private static ProducedRecord recordNumber(int record) {
        return new ProducedRecord(null, "payload-%02d".formatted(record).getBytes(UTF_8));
    }

    /**
     * Takes one byte per call, which a channel is allowed to do and which turns a transfer into as
     * many reports as there are bytes — the shape a caller bounding a slow write has to survive.
     */
    private static final class OneBytePerCallChannel implements WritableByteChannel {

        private final ByteArrayOutputStream received = new ByteArrayOutputStream();

        // confined to: the test thread that created this stub
        private boolean open = true;

        @Override
        public int write(ByteBuffer source) {
            if (!source.hasRemaining()) {
                return 0;
            }
            received.write(source.get());
            return 1;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }

        byte[] received() {
            return received.toByteArray();
        }
    }

    private Path logFileOf(long baseOffset) {
        return dataDirectory.resolve(TOPIC + "-" + PARTITION).resolve("%020d.log".formatted(baseOffset));
    }
}
