package io.shrike.core.log;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.shrike.core.time.TimeSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The flush policy: when a log forces its records to the device, and what that is worth after a crash.
 *
 * <p>An fsync leaves no trace a JVM can observe, so every test here watches the seam
 * {@code SegmentedLog.onForced} instead — the same trick {@link DurableFile.StepObserver} plays for a
 * durable rename, and the only way to say that a caller was answered <em>after</em> a force rather
 * than before it.
 *
 * <p>Nothing here sleeps. The clock is a field a test writes, so "a hundred milliseconds later" is an
 * assignment, and the interval is asked about by hand through {@code flushIfDue} rather than waited
 * for — when {@code shrike-flush} asks is that thread's business, and what a log decides is this
 * one's.
 */
class SegmentedLogFlushTest {

    private static final String TOPIC = "orders";
    private static final int PARTITION = 0;

    /** length 4, crc32c 4, magic 1, attributes 1, offset 8, timestamp 8, keyLen 4, valueLen 4. */
    private static final int FRAMING_BYTES_WITHOUT_KEY = 34;

    private static final int VALUE_BYTES = 10;
    private static final int RECORD_BYTES = FRAMING_BYTES_WITHOUT_KEY + VALUE_BYTES;

    private static final long ONE_HUNDRED_MS = 100L;
    private static final long ONE_HOUR_MS = 3_600_000L;
    private static final long FIRST_TIMESTAMP_MS = 1_700_000_000_000L;

    private static final int THREE_RECORDS = 3;

    /** The clock every log in this test reads, and the only thing that makes time pass here. */
    private final AtomicLong nowMillis = new AtomicLong(FIRST_TIMESTAMP_MS);

    private final TimeSource clock = nowMillis::get;

    /** How many times a log has forced, counted through the seam. */
    private final AtomicInteger forces = new AtomicInteger();

    @TempDir
    Path dataDirectory;

    @Test
    void forcesEveryRecordBeforeTheAppendThatWouldAcknowledgeItReturns() {
        List<String> sequence = new ArrayList<>();
        SegmentedLog log = open(perRecord());
        log.onForced(() -> sequence.add("forced"));

        try (log) {
            log.append(recordNumber(0));
            sequence.add("append returned, so a produce could be answered");
            log.append(recordNumber(1));
            sequence.add("append returned, so a produce could be answered");
        }

        assertEquals(List.of("forced", "append returned, so a produce could be answered",
                        "forced", "append returned, so a produce could be answered"), sequence,
                "per-record mode acknowledges a produce only after force(), so the force comes first every time");
    }

    @Test
    void forcesOnlyOnceTheFlushIntervalHasElapsed() {
        try (SegmentedLog log = open(intervalOf(ONE_HUNDRED_MS, LogConfig.DEFAULT_FLUSH_INTERVAL_BYTES))) {
            log.append(recordNumber(0));
            log.flushIfDue(FIRST_TIMESTAMP_MS);
            log.onForced(forces::incrementAndGet);
            log.append(recordNumber(1));

            boolean oneMillisecondEarly = log.flushIfDue(FIRST_TIMESTAMP_MS + ONE_HUNDRED_MS - 1L);
            int forcesOneMillisecondEarly = forces.get();
            boolean atTheInterval = log.flushIfDue(FIRST_TIMESTAMP_MS + ONE_HUNDRED_MS);
            boolean askedAgainAtTheSameInstant = log.flushIfDue(FIRST_TIMESTAMP_MS + ONE_HUNDRED_MS);

            assertFalse(oneMillisecondEarly, "the interval had not elapsed, so nothing was due");
            assertEquals(0, forcesOneMillisecondEarly, "and nothing was forced either");
            assertTrue(atTheInterval, "the record has now sat unforced for a whole flush.interval.ms");
            assertEquals(1, forces.get(), "which is one force, not one per record it covered");
            assertFalse(askedAgainAtTheSameInstant, "a log with nothing unforced is not due, whatever the clock says");
            assertEquals(1, forces.get());
        }
    }

    @Test
    void forcesAtTheFirstAskWhenItHasNoEarlierFlushToMeasureAnIntervalFrom() {
        try (SegmentedLog log = open(intervalOf(ONE_HOUR_MS, LogConfig.DEFAULT_FLUSH_INTERVAL_BYTES))) {
            log.onForced(forces::incrementAndGet);
            log.append(recordNumber(0));

            boolean askedForTheFirstTime = log.flushIfDue(nowMillis.get());

            assertTrue(askedForTheFirstTime,
                    "a log that has never flushed cannot be measuring an interval it started an hour ago");
            assertEquals(1, forces.get(),
                    "so the first ask forces, which is what holds the window to one interval of asking");
        }
    }

    @Test
    void holdsUnforcedBytesFromTheAppendThatMadeThemUntilTheFlushThatForcesThem() {
        try (SegmentedLog log = open(intervalOf(ONE_HUNDRED_MS, LogConfig.DEFAULT_FLUSH_INTERVAL_BYTES))) {
            boolean beforeAnyRecord = log.hasUnforcedBytes();
            log.append(recordNumber(0));
            log.flushIfDue(FIRST_TIMESTAMP_MS);
            log.onForced(forces::incrementAndGet);

            boolean afterThatFlush = log.hasUnforcedBytes();
            log.append(recordNumber(1));
            boolean afterTheNextAppend = log.hasUnforcedBytes();
            log.flushIfDue(FIRST_TIMESTAMP_MS + ONE_HUNDRED_MS - 1L);
            boolean afterAnAskThatWasNotDue = log.hasUnforcedBytes();
            log.flushIfDue(FIRST_TIMESTAMP_MS + ONE_HUNDRED_MS);

            assertFalse(beforeAnyRecord, "a log that has taken no record holds nothing anybody could force");
            assertFalse(afterThatFlush, "and neither does one whose records are already on the device");
            assertTrue(afterTheNextAppend, "the append made bytes only the operating system has");
            assertTrue(afterAnAskThatWasNotDue, "and an ask before the interval left them exactly where they were");
            assertFalse(log.hasUnforcedBytes(), "the flush that was due is what puts them on the device");
            assertEquals(1, forces.get(), "which is the one force this whole test asked for");
        }
    }

    @Test
    void holdsNothingUnforcedAfterAnAppendThatForcedOnItsOwn() {
        long twoRecordsOfBytes = 2L * RECORD_BYTES;

        try (SegmentedLog log = open(intervalOf(ONE_HOUR_MS, twoRecordsOfBytes))) {
            log.onForced(forces::incrementAndGet);

            log.append(recordNumber(0));
            boolean underTheVolumeBound = log.hasUnforcedBytes();
            log.append(recordNumber(1));

            assertTrue(underTheVolumeBound, "one record is under flush.interval.bytes, so it waits for a flush");
            assertFalse(log.hasUnforcedBytes(),
                    "the append that reached the bound forced before it returned, so there is nothing left to find");
            assertEquals(1, forces.get());
            assertFalse(log.flushIfDue(nowMillis.get() + ONE_HOUR_MS), "which is what the interval then answers");
        }
    }

    @Test
    void forcesOnceFlushIntervalBytesHaveBeenAppendedWithoutTheClockMoving() {
        long threeRecordsOfBytes = (long) THREE_RECORDS * RECORD_BYTES;

        try (SegmentedLog log = open(intervalOf(ONE_HOUR_MS, threeRecordsOfBytes))) {
            log.onForced(forces::incrementAndGet);

            log.append(recordNumber(0));
            log.append(recordNumber(1));
            int forcesUnderTheBound = forces.get();
            log.append(recordNumber(2));

            assertEquals(0, forcesUnderTheBound, "two records are under flush.interval.bytes, so nothing is forced");
            assertEquals(1, forces.get(),
                    "the append that reaches flush.interval.bytes forces there and then, with the clock unmoved");
            assertFalse(log.flushIfDue(nowMillis.get() + ONE_HOUR_MS),
                    "and it leaves the flush interval nothing to find");
        }
    }

    @Test
    void startsTheVolumeBoundAgainAtTheSegmentARollHasSealed() {
        LogConfig threeRecordFlushesInFourRecordSegments =
                intervalOf(ONE_HOUR_MS, (long) THREE_RECORDS * RECORD_BYTES, 4 * RECORD_BYTES);
        List<Integer> forcesAfterEachAppend = new ArrayList<>();

        try (SegmentedLog log = open(threeRecordFlushesInFourRecordSegments)) {
            log.onForced(forces::incrementAndGet);
            for (int record = 0; record < 7; record++) {
                log.append(recordNumber(record));
                forcesAfterEachAppend.add(forces.get());
            }

            assertEquals(List.of(0, 0, 1, 1, 1, 1, 2), forcesAfterEachAppend,
                    "the roll before the fifth record sealed a segment, which forced what was being counted, so"
                            + " the count starts again rather than carrying that debt into the new segment");
        }
    }

    @Test
    void truncatesATornTailWrittenUnderTheIntervalFlushMode() throws IOException {
        LogConfig neitherBoundReached = intervalOf(ONE_HOUR_MS, LogConfig.DEFAULT_FLUSH_INTERVAL_BYTES);
        int forcesWhileAppending;
        try (SegmentedLog log = open(neitherBoundReached)) {
            log.onForced(forces::incrementAndGet);
            for (int record = 0; record < THREE_RECORDS; record++) {
                log.append(recordNumber(record));
            }
            forcesWhileAppending = forces.get();
        }

        LogFileDamage.truncateTo(logFileOf(0L), (long) THREE_RECORDS * RECORD_BYTES - 2L);

        try (SegmentedLog recovered = open(neitherBoundReached)) {
            assertEquals(0, forcesWhileAppending,
                    "the flush policy forced none of these records, which is the case a torn tail is about");
            assertEquals(2L, recovered.nextOffset(), "recovery ends the log at the last whole record");
            assertEquals(2L * RECORD_BYTES, Files.size(logFileOf(0L)), "and the file ends at that record's last byte");
            assertArrayEquals(valueOf(0), recovered.read(0L).value());
            assertArrayEquals(valueOf(1), recovered.read(1L).value());
            assertEquals(2L, recovered.append(recordNumber(2)),
                    "a recovered log takes the offset after the last record that survived");
        }
    }

    private SegmentedLog open(LogConfig config) {
        return SegmentedLog.open(dataDirectory, TOPIC, PARTITION, clock, config);
    }

    private static LogConfig perRecord() {
        return new LogConfig(LogConfig.DEFAULT_MAX_RECORD_BYTES, LogConfig.DEFAULT_SEGMENT_BYTES,
                LogConfig.DEFAULT_INDEX_INTERVAL_BYTES, LogConfig.RETENTION_DISABLED, LogConfig.RETENTION_DISABLED,
                FlushMode.PER_RECORD, LogConfig.DEFAULT_FLUSH_INTERVAL_MS, LogConfig.DEFAULT_FLUSH_INTERVAL_BYTES);
    }

    private static LogConfig intervalOf(long flushIntervalMs, long flushIntervalBytes) {
        return intervalOf(flushIntervalMs, flushIntervalBytes, LogConfig.DEFAULT_SEGMENT_BYTES);
    }

    private static LogConfig intervalOf(long flushIntervalMs, long flushIntervalBytes, int segmentBytes) {
        return new LogConfig(LogConfig.DEFAULT_MAX_RECORD_BYTES, segmentBytes,
                LogConfig.DEFAULT_INDEX_INTERVAL_BYTES, LogConfig.RETENTION_DISABLED, LogConfig.RETENTION_DISABLED,
                FlushMode.INTERVAL, flushIntervalMs, flushIntervalBytes);
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
}
