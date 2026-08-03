package io.shrike.core.protocol;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.shrike.core.log.GoldenRecordFrame;
import io.shrike.core.log.Log;
import io.shrike.core.log.MalformedFrameException;
import io.shrike.core.log.ProducedRecord;
import io.shrike.core.log.SegmentedLog;
import io.shrike.core.log.StoredRecord;
import io.shrike.core.time.TimeSource;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The records block of a fetch response, read back. The first test is the one that matters most: the
 * bytes it parses are the same frozen bytes {@code RecordFrameGoldenBytesTest} holds the log to, so a
 * consumer and a log file cannot drift apart without one of the two tests failing.
 */
class WireRecordsTest {

    private static final String TOPIC = "orders";
    private static final int PARTITION = 0;
    private static final long TIMESTAMP_MILLIS = 1_700_000_000_000L;

    /** Where the magic byte sits in a frame: past the length and the checksum. */
    private static final int MAGIC_POSITION_BYTES = Integer.BYTES + Integer.BYTES;

    @TempDir
    Path dataDirectory;

    @Test
    void readsBackTheFrameTheLogFreezesByteForByte() {
        ByteBuffer block = ByteBuffer.wrap(GoldenRecordFrame.bytes());

        List<StoredRecord> records = WireRecords.decode(block);

        assertEquals(1, records.size());
        assertEquals(GoldenRecordFrame.OFFSET, records.get(0).offset());
        assertEquals(GoldenRecordFrame.TIMESTAMP_MILLIS, records.get(0).timestampMillis());
        assertArrayEquals(GoldenRecordFrame.KEY.getBytes(UTF_8), records.get(0).key());
        assertArrayEquals(GoldenRecordFrame.VALUE.getBytes(UTF_8), records.get(0).value());
    }

    @Test
    void readsEveryRecordASegmentedLogWroteInOnePass() throws IOException {
        byte[] block = logFileBytesOf(
                new ProducedRecord(null, "first".getBytes(UTF_8)),
                new ProducedRecord(new byte[0], new byte[0]),
                new ProducedRecord("k".getBytes(UTF_8), "third".getBytes(UTF_8)));

        List<StoredRecord> records = WireRecords.decode(ByteBuffer.wrap(block));

        assertEquals(List.of(0L, 1L, 2L), records.stream().map(StoredRecord::offset).toList());
        assertEquals(List.of(TIMESTAMP_MILLIS, TIMESTAMP_MILLIS, TIMESTAMP_MILLIS),
                records.stream().map(StoredRecord::timestampMillis).toList());
        assertNull(records.get(0).key(), "a null key must not come back as an empty one");
        assertArrayEquals("first".getBytes(UTF_8), records.get(0).value());
        assertArrayEquals(new byte[0], records.get(1).key());
        assertArrayEquals(new byte[0], records.get(1).value());
        assertArrayEquals("k".getBytes(UTF_8), records.get(2).key());
        assertArrayEquals("third".getBytes(UTF_8), records.get(2).value());
    }

    @Test
    void readsNothingFromABlockWithNoRecordsInIt() {
        List<StoredRecord> records = WireRecords.decode(ByteBuffer.allocate(0));

        assertTrue(records.isEmpty());
    }

    @Test
    void leavesTheCallersBufferWhereItFoundIt() {
        ByteBuffer block = ByteBuffer.wrap(GoldenRecordFrame.bytes());

        WireRecords.decode(block);

        assertEquals(0, block.position());
        assertEquals(GoldenRecordFrame.bytes().length, block.remaining());
    }

    @Test
    void refusesAFrameWhoseChecksumNoLongerMatchesItsBytes() {
        byte[] block = GoldenRecordFrame.bytes();
        block[block.length - 1] ^= 0x01;

        MalformedFrameException refusal = assertThrows(MalformedFrameException.class,
                () -> WireRecords.decode(ByteBuffer.wrap(block)));

        assertTrue(refusal.getMessage().contains("crc32c mismatch"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("positionBytes=0"), refusal.getMessage());
    }

    @Test
    void refusesAFrameWhoseMagicIsNotTheOneThisBuildWrites() {
        byte[] block = GoldenRecordFrame.bytes();
        block[MAGIC_POSITION_BYTES] = 1;
        rewriteChecksum(block);

        MalformedFrameException refusal = assertThrows(MalformedFrameException.class,
                () -> WireRecords.decode(ByteBuffer.wrap(block)));

        assertTrue(refusal.getMessage().contains("unknown frame magic 1"), refusal.getMessage());
    }

    @Test
    void refusesABlockThatEndsInsideAFrame() {
        byte[] wholeFrame = GoldenRecordFrame.bytes();
        byte[] cutShort = Arrays.copyOf(wholeFrame, wholeFrame.length - 1);

        MalformedFrameException refusal = assertThrows(MalformedFrameException.class,
                () -> WireRecords.decode(ByteBuffer.wrap(cutShort)));

        assertTrue(refusal.getMessage().contains("declares 32 bytes"), refusal.getMessage());
    }

    @Test
    void refusesABlockThatEndsInsideALengthField() {
        MalformedFrameException refusal = assertThrows(MalformedFrameException.class,
                () -> WireRecords.decode(ByteBuffer.wrap(new byte[] {0, 0, 0})));

        assertTrue(refusal.getMessage().contains("inside a length field"), refusal.getMessage());
    }

    @Test
    void refusesALengthNoFrameCouldBeAsShortAs() {
        for (int lengthBytes : new int[] {0, -1, 29, Integer.MAX_VALUE}) {
            ByteBuffer block = ByteBuffer.allocate(Integer.BYTES + 64).putInt(lengthBytes).rewind();

            MalformedFrameException refusal = assertThrows(MalformedFrameException.class,
                    () -> WireRecords.decode(block));

            assertTrue(refusal.getMessage().contains("declares " + lengthBytes + " bytes"), refusal.getMessage());
        }
    }

    @Test
    void refusesTheSecondFrameOfABlockAndSaysWhereItIs() {
        byte[] first = GoldenRecordFrame.bytes();
        byte[] second = GoldenRecordFrame.bytes();
        second[second.length - 1] ^= 0x01;
        byte[] block = WireFrames.concat(first, second);

        MalformedFrameException refusal = assertThrows(MalformedFrameException.class,
                () -> WireRecords.decode(ByteBuffer.wrap(block)));

        assertTrue(refusal.getMessage().contains("positionBytes=" + first.length), refusal.getMessage());
    }

    /**
     * Writes records through a real log and hands back the bytes of its segment file, so what is
     * parsed is what a broker would copy into a fetch response.
     */
    private byte[] logFileBytesOf(ProducedRecord... records) throws IOException {
        TimeSource fixedClock = () -> TIMESTAMP_MILLIS;
        try (Log log = SegmentedLog.open(dataDirectory, TOPIC, PARTITION, fixedClock)) {
            for (ProducedRecord record : records) {
                log.append(record);
            }
        }
        return Files.readAllBytes(
                dataDirectory.resolve(TOPIC + "-" + PARTITION).resolve("00000000000000000000.log"));
    }

    /**
     * Recomputes the frame's checksum over its own bytes, which is what a corrupted frame does not do
     * and a forged one does: it is the only way to test a check that sits behind the checksum.
     */
    private static void rewriteChecksum(byte[] frame) {
        CRC32C checksum = new CRC32C();
        checksum.update(frame, MAGIC_POSITION_BYTES, frame.length - MAGIC_POSITION_BYTES);
        ByteBuffer.wrap(frame).putInt(Integer.BYTES, (int) checksum.getValue());
    }
}
