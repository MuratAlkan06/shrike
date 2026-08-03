package io.shrike.core.log;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.shrike.core.time.TimeSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Freezes the on-disk record frame. The expected bytes below were derived from the format
 * description, not read back out of this implementation, and the crc32c value was computed with an
 * independent Castagnoli implementation. Any change to the layout breaks this test, which is the
 * point: the format may change, but never by accident.
 */
class RecordFrameGoldenBytesTest {

    private static final String TOPIC = "orders";
    private static final int PARTITION = 0;
    private static final long TIMESTAMP_MILLIS = 1_700_000_000_000L;

    /**
     * <pre>
     * 00000020            length     = 32 bytes follow the length field
     * aac8d9b3            crc32c     of the 28 bytes from magic through the end of the value
     * 00                  magic      = 0
     * 00                  attributes = 0, reserved
     * 0000000000000000    offset     = 0
     * 0000018bcfe56800    timestamp  = 1700000000000 epoch millis
     * 00000001            keyLen     = 1
     * 6b                  key        = "k"
     * 00000001            valueLen   = 1
     * 76                  value      = "v"
     * </pre>
     */
    private static final String EXPECTED_FRAME_HEX =
            "00000020aac8d9b3000000000000000000000000018bcfe56800000000016b0000000176";

    @TempDir
    Path dataDirectory;

    @Test
    void freezesTheOnDiskLayoutOfAKnownRecord() throws IOException {
        ProducedRecord record = new ProducedRecord("k".getBytes(UTF_8), "v".getBytes(UTF_8));
        TimeSource fixedClock = () -> TIMESTAMP_MILLIS;

        try (Log log = SingleFileLog.open(dataDirectory, TOPIC, PARTITION, fixedClock)) {
            log.append(record);
        }

        byte[] fileBytes = Files.readAllBytes(
                dataDirectory.resolve(TOPIC + "-" + PARTITION).resolve("00000000000000000000.log"));
        assertEquals(EXPECTED_FRAME_HEX, HexFormat.of().formatHex(fileBytes));
    }
}
