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
 * Freezes the on-disk record frame against {@link GoldenRecordFrame}, whose bytes were derived from
 * the format description rather than read back out of this implementation. Any change to the layout
 * breaks this test, which is the point: the format may change, but never by accident.
 */
class RecordFrameGoldenBytesTest {

    private static final String TOPIC = "orders";
    private static final int PARTITION = 0;

    @TempDir
    Path dataDirectory;

    @Test
    void freezesTheOnDiskLayoutOfAKnownRecord() throws IOException {
        ProducedRecord record = new ProducedRecord(GoldenRecordFrame.KEY.getBytes(UTF_8),
                GoldenRecordFrame.VALUE.getBytes(UTF_8));
        TimeSource fixedClock = () -> GoldenRecordFrame.TIMESTAMP_MILLIS;

        try (Log log = SegmentedLog.open(dataDirectory, TOPIC, PARTITION, fixedClock)) {
            log.append(record);
        }

        byte[] fileBytes = Files.readAllBytes(
                dataDirectory.resolve(TOPIC + "-" + PARTITION).resolve("00000000000000000000.log"));
        assertEquals(GoldenRecordFrame.HEX, HexFormat.of().formatHex(fileBytes));
    }
}
