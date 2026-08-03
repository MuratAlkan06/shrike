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
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class SingleFileLogTest {

    private static final String TOPIC = "orders";
    private static final int PARTITION = 0;
    private static final long FIXED_TIMESTAMP_MILLIS = 1_700_000_000_000L;
    private static final TimeSource FIXED_CLOCK = () -> FIXED_TIMESTAMP_MILLIS;

    /**
     * The bytes a frame spends on framing when the record has no key: length 4, crc32c 4, magic 1,
     * attributes 1, offset 8, timestamp 8, keyLen 4, valueLen 4. Spelled out here rather than taken
     * from the production constants, so a change to the layout has to be made twice on purpose.
     */
    private static final int FRAMING_BYTES_WITHOUT_KEY = 34;

    @TempDir
    Path dataDirectory;

    static Stream<Arguments> recordShapes() {
        return Stream.of(
                arguments("no key at all", null, "payload".getBytes(UTF_8)),
                arguments("a key of zero bytes", new byte[0], "payload".getBytes(UTF_8)),
                arguments("a value of zero bytes", "k".getBytes(UTF_8), new byte[0]),
                arguments("a key and a value", "k".getBytes(UTF_8), "payload".getBytes(UTF_8)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("recordShapes")
    void roundTripsARecordWithNullKeyEmptyKeyOrEmptyValue(String shape, byte[] key, byte[] value) {
        ProducedRecord record = new ProducedRecord(key, value);

        try (Log log = SingleFileLog.open(dataDirectory, TOPIC, PARTITION, FIXED_CLOCK)) {
            long offset = log.append(record);
            StoredRecord stored = log.read(offset);

            assertEquals(0L, offset, shape);
            assertEquals(0L, stored.offset());
            assertEquals(FIXED_TIMESTAMP_MILLIS, stored.timestampMillis());
            assertArrayEquals(key, stored.key(), "a null key and an empty key must stay distinguishable");
            assertArrayEquals(value, stored.value());
        }
    }

    @Test
    void writesTheLogFileUnderTopicAndPartitionInsideTheInjectedDataDirectory() {
        int otherThanPartitionZero = 3;

        try (Log log = SingleFileLog.open(dataDirectory, TOPIC, otherThanPartitionZero, FIXED_CLOCK)) {
            log.append(new ProducedRecord(null, "payload".getBytes(UTF_8)));

            assertEquals(TOPIC, log.topic());
            assertEquals(otherThanPartitionZero, log.partition());
            assertTrue(Files.isRegularFile(logFileOf(TOPIC, otherThanPartitionZero)),
                    "the log file must be <dataDir>/<topic>-<partition>/00000000000000000000.log");
        }
    }

    @Test
    void assignsSequentialOffsetsStartingAtZero() {
        try (Log log = SingleFileLog.open(dataDirectory, TOPIC, PARTITION, FIXED_CLOCK)) {
            long first = log.append(new ProducedRecord(null, "first".getBytes(UTF_8)));
            long second = log.append(new ProducedRecord(null, "second".getBytes(UTF_8)));
            long third = log.append(new ProducedRecord(null, "third".getBytes(UTF_8)));

            assertEquals(0L, first);
            assertEquals(1L, second);
            assertEquals(2L, third);
            assertEquals(3L, log.nextOffset(), "the high-water mark is the offset the next append takes");
        }
    }

    @Test
    void readsBackTheFirstAndTheLastRecordOfTheLog() {
        try (Log log = SingleFileLog.open(dataDirectory, TOPIC, PARTITION, FIXED_CLOCK)) {
            log.append(new ProducedRecord(null, "first".getBytes(UTF_8)));
            log.append(new ProducedRecord(null, "middle".getBytes(UTF_8)));
            long lastOffset = log.append(new ProducedRecord(null, "last".getBytes(UTF_8)));

            assertArrayEquals("first".getBytes(UTF_8), log.read(0L).value());
            assertArrayEquals("last".getBytes(UTF_8), log.read(lastOffset).value());
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {Long.MIN_VALUE, -1L, 3L, 4L, Long.MAX_VALUE})
    void refusesReadsOutsideTheReadableOffsetRange(long unreadableOffset) {
        try (Log log = SingleFileLog.open(dataDirectory, TOPIC, PARTITION, FIXED_CLOCK)) {
            log.append(new ProducedRecord(null, "first".getBytes(UTF_8)));
            log.append(new ProducedRecord(null, "second".getBytes(UTF_8)));
            log.append(new ProducedRecord(null, "third".getBytes(UTF_8)));

            OffsetOutOfRangeException refusal =
                    assertThrows(OffsetOutOfRangeException.class, () -> log.read(unreadableOffset));

            assertEquals(unreadableOffset, refusal.requestedOffset());
            assertEquals(0L, refusal.firstOffset());
            assertEquals(3L, refusal.nextOffset());
            assertTrue(refusal.getMessage().contains("[0, 3)"), refusal.getMessage());
        }
    }

    @Test
    void refusesEveryReadFromAnEmptyLog() {
        try (Log log = SingleFileLog.open(dataDirectory, TOPIC, PARTITION, FIXED_CLOCK)) {
            OffsetOutOfRangeException refusal = assertThrows(OffsetOutOfRangeException.class, () -> log.read(0L));

            assertEquals(0L, log.nextOffset());
            assertEquals(0L, refusal.firstOffset());
            assertEquals(0L, refusal.nextOffset(), "an empty log has an empty readable range");
        }
    }

    @Test
    void stampsRecordsWithTheInjectedTimeSourceRatherThanTheWallClock() {
        AtomicLong tickMillis = new AtomicLong();
        TimeSource tickingClock = () -> tickMillis.addAndGet(1_000L);

        try (Log log = SingleFileLog.open(dataDirectory, TOPIC, PARTITION, tickingClock)) {
            log.append(new ProducedRecord(null, "first".getBytes(UTF_8)));
            log.append(new ProducedRecord(null, "second".getBytes(UTF_8)));

            assertEquals(1_000L, log.read(0L).timestampMillis());
            assertEquals(2_000L, log.read(1L).timestampMillis());
        }
    }

    @Test
    void storesARecordThatFillsMaxRecordBytesExactly() throws IOException {
        byte[] value = new byte[SingleFileLog.DEFAULT_MAX_RECORD_BYTES - FRAMING_BYTES_WITHOUT_KEY];
        value[value.length - 1] = 'z';

        try (Log log = SingleFileLog.open(dataDirectory, TOPIC, PARTITION, FIXED_CLOCK)) {
            long offset = log.append(new ProducedRecord(null, value));

            assertArrayEquals(value, log.read(offset).value());
            assertEquals(SingleFileLog.DEFAULT_MAX_RECORD_BYTES, Files.size(logFileOf(TOPIC, PARTITION)),
                    "a record of exactly max.record.bytes fills the file to exactly that many bytes");
        }
    }

    @Test
    void refusesARecordOneByteOverMaxRecordBytes() {
        byte[] value = new byte[SingleFileLog.DEFAULT_MAX_RECORD_BYTES - FRAMING_BYTES_WITHOUT_KEY + 1];

        try (Log log = SingleFileLog.open(dataDirectory, TOPIC, PARTITION, FIXED_CLOCK)) {
            RecordTooLargeException refusal =
                    assertThrows(RecordTooLargeException.class, () -> log.append(new ProducedRecord(null, value)));

            assertEquals(SingleFileLog.DEFAULT_MAX_RECORD_BYTES + 1L, refusal.recordBytes());
            assertEquals(SingleFileLog.DEFAULT_MAX_RECORD_BYTES, refusal.maxRecordBytes());
            assertEquals(0L, log.nextOffset(), "a refused append must not consume an offset");
        }
    }

    @Test
    void enforcesTheConfiguredMaxRecordBytesRatherThanTheDefault() {
        int maxRecordBytes = 64;
        byte[] value = new byte[maxRecordBytes - FRAMING_BYTES_WITHOUT_KEY + 1];

        try (Log log = SingleFileLog.open(dataDirectory, TOPIC, PARTITION, FIXED_CLOCK, maxRecordBytes)) {
            RecordTooLargeException refusal =
                    assertThrows(RecordTooLargeException.class, () -> log.append(new ProducedRecord(null, value)));

            assertEquals(maxRecordBytes, refusal.maxRecordBytes());
        }
    }

    @Test
    void refusesToAppendANullValueBecauseCompactionIsANonGoal() {
        try (Log log = SingleFileLog.open(dataDirectory, TOPIC, PARTITION, FIXED_CLOCK)) {
            NullPointerException refusal =
                    assertThrows(NullPointerException.class, () -> log.append(new ProducedRecord(null, null)));

            assertTrue(refusal.getMessage().contains("tombstone"), refusal.getMessage());
            assertEquals(0L, log.nextOffset());
        }
    }

    @Test
    void detectsASingleFlippedBitOnDiskWhenTheRecordIsRead() throws IOException {
        Path logFile = logFileOf(TOPIC, PARTITION);
        long positionOfSecondRecord = FRAMING_BYTES_WITHOUT_KEY + "first".length();

        try (Log log = SingleFileLog.open(dataDirectory, TOPIC, PARTITION, FIXED_CLOCK)) {
            log.append(new ProducedRecord(null, "first".getBytes(UTF_8)));
            log.append(new ProducedRecord(null, "second".getBytes(UTF_8)));
            flipLastBitOnDisk(logFile);

            CorruptRecordException corruption = assertThrows(CorruptRecordException.class, () -> log.read(1L));

            assertEquals(new RecordLocation(TOPIC, PARTITION, 1L, positionOfSecondRecord, logFile),
                    corruption.location());
            assertTrue(corruption.getMessage().contains("topic=" + TOPIC), corruption.getMessage());
            assertTrue(corruption.getMessage().contains("partition=" + PARTITION), corruption.getMessage());
            assertTrue(corruption.getMessage().contains("offset=1"), corruption.getMessage());
            assertTrue(corruption.getMessage().contains("positionBytes=" + positionOfSecondRecord),
                    corruption.getMessage());
            assertTrue(corruption.getMessage().contains("file=" + logFile), corruption.getMessage());
            assertArrayEquals("first".getBytes(UTF_8), log.read(0L).value(),
                    "the intact record before the damage still reads");
        }
    }

    @Test
    void refusesToOpenALogFileThatAlreadyExists() {
        SingleFileLog.open(dataDirectory, TOPIC, PARTITION, FIXED_CLOCK).close();

        ShrikeIOException refusal = assertThrows(ShrikeIOException.class,
                () -> SingleFileLog.open(dataDirectory, TOPIC, PARTITION, FIXED_CLOCK));

        assertTrue(refusal.getMessage().contains("recovery scan"), refusal.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ".", "..", "../escapes", "nested/topic", "with space"})
    void refusesTopicNamesThatWouldNameSomethingOtherThanOneDirectory(String topic) {
        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> SingleFileLog.open(dataDirectory, topic, PARTITION, FIXED_CLOCK));

        assertTrue(refusal.getMessage().contains("topic must match"), refusal.getMessage());
    }

    private Path logFileOf(String topic, int partition) {
        return dataDirectory.resolve(topic + "-" + partition).resolve("00000000000000000000.log");
    }

    private static void flipLastBitOnDisk(Path logFile) throws IOException {
        byte[] bytes = Files.readAllBytes(logFile);
        bytes[bytes.length - 1] ^= 0x01;
        Files.write(logFile, bytes);
    }
}
