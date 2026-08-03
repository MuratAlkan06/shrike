package io.shrike.core.log;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import io.shrike.core.time.TimeSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Startup recovery against real damage: every case here writes records, closes the log cleanly, hurts
 * the files from outside with {@link LogFileDamage}, and opens a fresh log over the same directory to
 * see what it makes of them. Startup is never allowed to fail on a torn tail.
 */
class SegmentedLogRecoveryTest {

    private static final String TOPIC = "orders";
    private static final int PARTITION = 0;
    private static final TimeSource FIXED_CLOCK = () -> 1_700_000_000_000L;

    /** length 4, crc32c 4, magic 1, attributes 1, offset 8, timestamp 8, keyLen 4, valueLen 4. */
    private static final int FRAMING_BYTES_WITHOUT_KEY = 34;

    private static final int VALUE_BYTES = 10;
    private static final int RECORD_BYTES = FRAMING_BYTES_WITHOUT_KEY + VALUE_BYTES;

    /** Two records per segment, so a multi-segment log needs only a handful of appends. */
    private static final int TWO_RECORD_SEGMENT_BYTES = 2 * RECORD_BYTES;

    /** An entry every byte, so every segment that holds two records holds one index entry. */
    private static final int INDEX_INTERVAL_BYTES = 1;

    private static final int THREE_RECORDS = 3;

    @TempDir
    Path dataDirectory;

    /** Damage done to a file by something that is not this broker. */
    private interface Damage {
        void apply(Path file) throws IOException;
    }

    /**
     * One row per way a tail can come back wrong, with the number of records that must survive it.
     * Three records of 44 bytes each start at positions 0, 44, and 88, so the last frame's header
     * starts at 88 and the file is 132 bytes long.
     */
    static Stream<Arguments> tornTails() {
        long lastFramePosition = 2L * RECORD_BYTES;
        long fileBytes = (long) THREE_RECORDS * RECORD_BYTES;
        return Stream.of(
                arguments("an empty 0-byte log file",
                        (Damage) logFile -> LogFileDamage.truncateTo(logFile, 0L), 0),
                arguments("a tail truncated in the middle of a frame header",
                        (Damage) logFile -> LogFileDamage.truncateTo(logFile, lastFramePosition + 6L), 2),
                arguments("a tail truncated with fewer bytes left than a length field",
                        (Damage) logFile -> LogFileDamage.truncateTo(logFile, lastFramePosition + 2L), 2),
                arguments("a tail truncated in the middle of a payload",
                        (Damage) logFile -> LogFileDamage.truncateTo(logFile, fileBytes - 2L), 2),
                arguments("a bit flipped in the last record's payload",
                        (Damage) logFile -> LogFileDamage.flipBitAt(logFile, fileBytes - 1L), 2),
                arguments("a negative length on the last frame",
                        (Damage) logFile -> LogFileDamage.writeIntAt(logFile, lastFramePosition, -1), 2),
                arguments("a length over max.record.bytes on the last frame",
                        (Damage) logFile -> LogFileDamage.writeIntAt(logFile, lastFramePosition,
                                LogConfig.DEFAULT_MAX_RECORD_BYTES), 2),
                arguments("a zero length on the last frame",
                        (Damage) logFile -> LogFileDamage.writeIntAt(logFile, lastFramePosition, 0), 2),
                arguments("trailing zero bytes after the last record",
                        (Damage) logFile -> LogFileDamage.appendZeroBytes(logFile, 16), THREE_RECORDS));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tornTails")
    void truncatesATornTailToTheLastValidRecordOnRestart(String damageName, Damage damage, int survivingRecords)
            throws IOException {
        try (SegmentedLog log = SegmentedLog.open(dataDirectory, TOPIC, PARTITION, FIXED_CLOCK)) {
            for (int record = 0; record < THREE_RECORDS; record++) {
                log.append(recordNumber(record));
            }
        }
        damage.apply(logFileOf(0L));

        try (SegmentedLog recovered = SegmentedLog.open(dataDirectory, TOPIC, PARTITION, FIXED_CLOCK)) {
            assertEquals(survivingRecords, recovered.nextOffset(), damageName + " must cost only the torn records");
            assertEquals((long) survivingRecords * RECORD_BYTES, Files.size(logFileOf(0L)),
                    "the log file ends at the last whole frame");
            for (int record = 0; record < survivingRecords; record++) {
                assertArrayEquals(valueOf(record), recovered.read(record).value());
            }
            assertEquals(survivingRecords, recovered.append(recordNumber(survivingRecords)),
                    "a recovered log takes the offset after the last record that survived");
            assertArrayEquals(valueOf(survivingRecords), recovered.read(survivingRecords).value());
        }
    }

    @Test
    void keepsDamageInsideASealedSegmentAndStillReadsItsNeighbours() throws IOException {
        LogConfig config = new LogConfig(LogConfig.DEFAULT_MAX_RECORD_BYTES, TWO_RECORD_SEGMENT_BYTES,
                LogConfig.DEFAULT_INDEX_INTERVAL_BYTES);
        long positionOfSecondRecord = RECORD_BYTES;
        long positionOfItsValue = positionOfSecondRecord + FRAMING_BYTES_WITHOUT_KEY;

        try (SegmentedLog log = SegmentedLog.open(dataDirectory, TOPIC, PARTITION, FIXED_CLOCK, config)) {
            for (int record = 0; record < 6; record++) {
                log.append(recordNumber(record));
            }
        }
        LogFileDamage.flipBitAt(logFileOf(0L), positionOfItsValue);

        try (SegmentedLog recovered = SegmentedLog.open(dataDirectory, TOPIC, PARTITION, FIXED_CLOCK, config)) {
            CorruptRecordException corruption = assertThrows(CorruptRecordException.class, () -> recovered.read(1L));

            assertEquals(6L, recovered.nextOffset(), "startup neither notices nor repairs a sealed segment");
            assertEquals(TWO_RECORD_SEGMENT_BYTES, Files.size(logFileOf(0L)),
                    "a sealed segment is never truncated: it was forced before it was sealed");
            assertEquals(new RecordLocation(TOPIC, PARTITION, 1L, positionOfSecondRecord, logFileOf(0L)),
                    corruption.location());
            assertTrue(corruption.getMessage().contains("crc32c mismatch"), corruption.getMessage());
            assertArrayEquals(valueOf(0), recovered.read(0L).value(),
                    "the record before the damage, in the same sealed segment, still reads");
            for (int record = 2; record < 6; record++) {
                assertArrayEquals(valueOf(record), recovered.read(record).value(),
                        "the records after the damage still read");
            }
        }
    }

    @Test
    void rebuildsADeletedIndexFromTheLogAlone() throws IOException {
        LogConfig config = new LogConfig(LogConfig.DEFAULT_MAX_RECORD_BYTES, TWO_RECORD_SEGMENT_BYTES,
                INDEX_INTERVAL_BYTES);
        String indexBytesBeforeDeletion = appendSixRecordsAndReadTheFirstIndex(config);

        LogFileDamage.delete(indexFileOf(0L));

        try (SegmentedLog recovered = SegmentedLog.open(dataDirectory, TOPIC, PARTITION, FIXED_CLOCK, config)) {
            assertEquals(indexBytesBeforeDeletion, hexOf(indexFileOf(0L)),
                    "an index is derived data: rebuilding it from the log gives back the same bytes");
            assertEquals(6L, recovered.nextOffset());
            for (int record = 0; record < 6; record++) {
                assertArrayEquals(valueOf(record), recovered.read(record).value());
            }
        }
    }

    @Test
    void rebuildsAnIndexThatPointsPastTheEndOfItsLog() throws IOException {
        LogConfig config = new LogConfig(LogConfig.DEFAULT_MAX_RECORD_BYTES, TWO_RECORD_SEGMENT_BYTES,
                INDEX_INTERVAL_BYTES);
        String indexBytesBeforeDamage = appendSixRecordsAndReadTheFirstIndex(config);
        int positionFieldOfTheFirstEntry = 4;

        LogFileDamage.writeIntAt(indexFileOf(0L), positionFieldOfTheFirstEntry, 10_000);

        try (SegmentedLog recovered = SegmentedLog.open(dataDirectory, TOPIC, PARTITION, FIXED_CLOCK, config)) {
            assertEquals(indexBytesBeforeDamage, hexOf(indexFileOf(0L)),
                    "an entry pointing past the log makes the whole index untrustworthy, so it is rebuilt");
            assertEquals(6L, recovered.nextOffset());
            for (int record = 0; record < 6; record++) {
                assertArrayEquals(valueOf(record), recovered.read(record).value());
            }
        }
    }

    @Test
    void changesNoFileAndNoOffsetOnASecondRestartWithoutWrites() throws IOException {
        LogConfig config = new LogConfig(LogConfig.DEFAULT_MAX_RECORD_BYTES, TWO_RECORD_SEGMENT_BYTES,
                INDEX_INTERVAL_BYTES);
        try (SegmentedLog log = SegmentedLog.open(dataDirectory, TOPIC, PARTITION, FIXED_CLOCK, config)) {
            for (int record = 0; record < 5; record++) {
                log.append(recordNumber(record));
            }
        }
        LogFileDamage.truncateTo(logFileOf(4L), RECORD_BYTES - 2L);

        Map<String, Long> sizesAfterTheFirstRestart;
        String statisticsAfterTheFirstRestart;
        try (SegmentedLog firstRestart = SegmentedLog.open(dataDirectory, TOPIC, PARTITION, FIXED_CLOCK, config)) {
            sizesAfterTheFirstRestart = fileSizes();
            statisticsAfterTheFirstRestart = statisticsOf(firstRestart);
        }

        try (SegmentedLog secondRestart = SegmentedLog.open(dataDirectory, TOPIC, PARTITION, FIXED_CLOCK, config)) {
            assertEquals(sizesAfterTheFirstRestart, fileSizes(),
                    "a restart with no writes in between leaves every file the size it already was");
            assertEquals(statisticsAfterTheFirstRestart, statisticsOf(secondRestart));
            assertEquals(4L, secondRestart.nextOffset());
        }
    }

    @Test
    void reportsTheTruncatedSizeAfterRecoveringATornTail() throws IOException {
        LogConfig config = new LogConfig(LogConfig.DEFAULT_MAX_RECORD_BYTES, TWO_RECORD_SEGMENT_BYTES,
                INDEX_INTERVAL_BYTES);
        try (SegmentedLog log = SegmentedLog.open(dataDirectory, TOPIC, PARTITION, FIXED_CLOCK, config)) {
            for (int record = 0; record < 5; record++) {
                log.append(recordNumber(record));
            }
        }

        LogFileDamage.truncateTo(logFileOf(4L), 20L);

        try (SegmentedLog recovered = SegmentedLog.open(dataDirectory, TOPIC, PARTITION, FIXED_CLOCK, config)) {
            assertEquals(0L, recovered.logStartOffset());
            assertEquals(4L, recovered.highWaterMark(), "the record that was being written is gone with its bytes");
            assertEquals(3, recovered.segmentCount(), "the emptied tail segment is still a segment");
            assertEquals(4L * RECORD_BYTES, recovered.logBytes());
            assertEquals(0L, Files.size(logFileOf(4L)), "a zero-byte tail segment is a valid empty segment");
            assertEquals(2 * OffsetIndex.ENTRY_BYTES, recovered.indexBytes(),
                    "the two full segments kept an entry each; the empty tail has none");
        }
    }

    /**
     * @param config the sizes to open with
     * @return the bytes of the first segment's index after six records were appended and the log was
     *         closed cleanly, which is what a rebuild has to reproduce
     */
    private String appendSixRecordsAndReadTheFirstIndex(LogConfig config) throws IOException {
        try (SegmentedLog log = SegmentedLog.open(dataDirectory, TOPIC, PARTITION, FIXED_CLOCK, config)) {
            for (int record = 0; record < 6; record++) {
                log.append(recordNumber(record));
            }
        }
        assertEquals(OffsetIndex.ENTRY_BYTES, Files.size(indexFileOf(0L)),
                "the first segment must have earned one index entry for this test to prove anything");
        return hexOf(indexFileOf(0L));
    }

    private Map<String, Long> fileSizes() throws IOException {
        Map<String, Long> sizesByName = new TreeMap<>();
        try (Stream<Path> files = Files.list(partitionDirectory())) {
            for (Path file : files.toList()) {
                sizesByName.put(file.getFileName().toString(), Files.size(file));
            }
        }
        return sizesByName;
    }

    private static String statisticsOf(LogStatistics statistics) {
        return "logStartOffset=" + statistics.logStartOffset()
                + " highWaterMark=" + statistics.highWaterMark()
                + " segmentCount=" + statistics.segmentCount()
                + " logBytes=" + statistics.logBytes()
                + " indexBytes=" + statistics.indexBytes();
    }

    private static String hexOf(Path file) throws IOException {
        return HexFormat.of().formatHex(Files.readAllBytes(file));
    }

    private static ProducedRecord recordNumber(int record) {
        return new ProducedRecord(null, valueOf(record));
    }

    private static byte[] valueOf(int record) {
        return "payload-%02d".formatted(record).getBytes(UTF_8);
    }

    private Path partitionDirectory() {
        return dataDirectory.resolve(TOPIC + "-" + PARTITION);
    }

    private Path logFileOf(long baseOffset) {
        return partitionDirectory().resolve("%020d.log".formatted(baseOffset));
    }

    private Path indexFileOf(long baseOffset) {
        return partitionDirectory().resolve("%020d.index".formatted(baseOffset));
    }
}
