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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Rolling: when a segment fills, where the next record goes, and what happens to the segment left
 * behind. Every size here is spelled out from the frame layout rather than read back out of the
 * implementation, so a change to either has to be made on purpose.
 */
class SegmentedLogRollingTest {

    private static final String TOPIC = "orders";
    private static final int PARTITION = 0;
    private static final TimeSource FIXED_CLOCK = () -> 1_700_000_000_000L;

    /**
     * The bytes a frame spends on framing when the record has no key: length 4, crc32c 4, magic 1,
     * attributes 1, offset 8, timestamp 8, keyLen 4, valueLen 4.
     */
    private static final int FRAMING_BYTES_WITHOUT_KEY = 34;

    /** Every test record's value is ten bytes, so every frame on disk is 44 bytes. */
    private static final int VALUE_BYTES = 10;
    private static final int RECORD_BYTES = FRAMING_BYTES_WITHOUT_KEY + VALUE_BYTES;

    /** Room for two records and not a byte more. */
    private static final int TWO_RECORD_SEGMENT_BYTES = 2 * RECORD_BYTES;

    @TempDir
    Path dataDirectory;

    @Test
    void fitsAsManyRecordsIntoASegmentAsSegmentBytesExactlyAllows() {
        LogConfig config = configWithSegmentBytes(TWO_RECORD_SEGMENT_BYTES);

        try (SegmentedLog log = SegmentedLog.open(dataDirectory, TOPIC, PARTITION, FIXED_CLOCK, config)) {
            log.append(recordNumber(0));
            log.append(recordNumber(1));

            assertEquals(1, log.segmentCount(), "a record that fills a segment exactly does not roll it");
            assertEquals(TWO_RECORD_SEGMENT_BYTES, log.logBytes());
        }
    }

    @Test
    void startsANewSegmentWhenTheNextRecordWouldPushThePreviousPastSegmentBytes() throws IOException {
        LogConfig config = configWithSegmentBytes(TWO_RECORD_SEGMENT_BYTES);

        try (SegmentedLog log = SegmentedLog.open(dataDirectory, TOPIC, PARTITION, FIXED_CLOCK, config)) {
            for (int record = 0; record < 5; record++) {
                assertEquals(record, log.append(recordNumber(record)));
            }

            assertEquals(3, log.segmentCount());
            assertEquals(TWO_RECORD_SEGMENT_BYTES, Files.size(logFileOf(0L)));
            assertEquals(TWO_RECORD_SEGMENT_BYTES, Files.size(logFileOf(2L)),
                    "the record that would not fit starts the next segment, so segment 2 holds offsets 2 and 3");
            assertEquals(RECORD_BYTES, Files.size(logFileOf(4L)),
                    "the new segment's base offset is the offset of the record that overflowed the last one");
            assertFalse(Files.exists(logFileOf(1L)), "segments are named after base offsets, not sequence numbers");
        }
    }

    @Test
    void storesARecordLargerThanSegmentBytesInASegmentOfItsOwn() throws IOException {
        LogConfig config = configWithSegmentBytes(TWO_RECORD_SEGMENT_BYTES);
        byte[] oversizedValue = new byte[TWO_RECORD_SEGMENT_BYTES + 1];
        oversizedValue[oversizedValue.length - 1] = 'z';

        try (SegmentedLog log = SegmentedLog.open(dataDirectory, TOPIC, PARTITION, FIXED_CLOCK, config)) {
            long oversizedOffset = log.append(new ProducedRecord(null, oversizedValue));
            long nextOffset = log.append(recordNumber(1));

            assertArrayEquals(oversizedValue, log.read(oversizedOffset).value(),
                    "an empty segment takes any record max.record.bytes allows, even one over segment.bytes");
            assertEquals(1L, nextOffset);
            assertEquals(2, log.segmentCount());
            assertEquals(FRAMING_BYTES_WITHOUT_KEY + oversizedValue.length, Files.size(logFileOf(0L)));
            assertEquals(RECORD_BYTES, Files.size(logFileOf(1L)));
        }
    }

    @Test
    void sealsTheFilledSegmentBeforeTheNextOneTakesARecord() throws IOException {
        LogConfig config = new LogConfig(LogConfig.DEFAULT_MAX_RECORD_BYTES, TWO_RECORD_SEGMENT_BYTES, 1);

        try (SegmentedLog log = SegmentedLog.open(dataDirectory, TOPIC, PARTITION, FIXED_CLOCK, config)) {
            log.append(recordNumber(0));
            log.append(recordNumber(1));
            long sealedLogBytes = Files.size(logFileOf(0L));
            long sealedIndexBytes = Files.size(indexFileOf(0L));

            log.append(recordNumber(2));
            log.append(recordNumber(3));
            log.append(recordNumber(4));

            assertEquals(sealedLogBytes, Files.size(logFileOf(0L)),
                    "a sealed segment's log is complete when the next segment starts and never grows again");
            assertEquals(sealedIndexBytes, Files.size(indexFileOf(0L)),
                    "a sealed segment's index is finalised with it");
            assertTrue(sealedIndexBytes > 0, "the index of a filled segment holds the entries it earned");
            assertArrayEquals(valueOf(0), log.read(0L).value());
            assertArrayEquals(valueOf(1), log.read(1L).value());
        }
    }

    @Test
    void readsEveryRecordBackAcrossManySegments() {
        LogConfig config = configWithSegmentBytes(TWO_RECORD_SEGMENT_BYTES);
        int recordCount = 21;

        try (SegmentedLog log = SegmentedLog.open(dataDirectory, TOPIC, PARTITION, FIXED_CLOCK, config)) {
            for (int record = 0; record < recordCount; record++) {
                log.append(recordNumber(record));
            }

            List<byte[]> readBackwards = new ArrayList<>();
            for (long offset = recordCount - 1; offset >= 0; offset--) {
                readBackwards.add(log.read(offset).value());
            }

            assertEquals(11, log.segmentCount(), "two records per segment plus the odd one out");
            for (int record = 0; record < recordCount; record++) {
                assertArrayEquals(valueOf(record), readBackwards.get(recordCount - 1 - record),
                        "offset " + record + " must read back whichever segment it landed in");
            }
        }
    }

    @Test
    void keepsRollingAfterARestartWithoutReusingASegmentFile() throws IOException {
        LogConfig config = configWithSegmentBytes(TWO_RECORD_SEGMENT_BYTES);

        try (SegmentedLog log = SegmentedLog.open(dataDirectory, TOPIC, PARTITION, FIXED_CLOCK, config)) {
            log.append(recordNumber(0));
            log.append(recordNumber(1));
            log.append(recordNumber(2));
        }

        try (SegmentedLog reopened = SegmentedLog.open(dataDirectory, TOPIC, PARTITION, FIXED_CLOCK, config)) {
            assertEquals(3L, reopened.append(recordNumber(3)));
            assertEquals(4L, reopened.append(recordNumber(4)));

            assertEquals(3, reopened.segmentCount(), "the recovered tail keeps taking records until it fills");
            assertEquals(TWO_RECORD_SEGMENT_BYTES, Files.size(logFileOf(2L)));
            assertEquals(RECORD_BYTES, Files.size(logFileOf(4L)));
            assertArrayEquals(valueOf(2), reopened.read(2L).value());
            assertArrayEquals(valueOf(4), reopened.read(4L).value());
        }
    }

    private static LogConfig configWithSegmentBytes(int segmentBytes) {
        return new LogConfig(LogConfig.DEFAULT_MAX_RECORD_BYTES, segmentBytes, LogConfig.DEFAULT_INDEX_INTERVAL_BYTES);
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
