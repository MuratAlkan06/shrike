package io.shrike.core.log;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.shrike.core.time.TimeSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
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

    private static ProducedRecord recordNumber(int record) {
        return new ProducedRecord(null, "payload-%02d".formatted(record).getBytes(UTF_8));
    }

    private Path logFileOf(long baseOffset) {
        return dataDirectory.resolve(TOPIC + "-" + PARTITION).resolve("%020d.log".formatted(baseOffset));
    }
}
