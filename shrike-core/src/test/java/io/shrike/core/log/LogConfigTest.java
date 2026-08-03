package io.shrike.core.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LogConfigTest {

    @Test
    void defaultsToOneMebibyteRecordsAndOneHundredAndTwentyEightMebibyteSegments() {
        LogConfig config = LogConfig.defaults();

        assertEquals(1024 * 1024, config.maxRecordBytes());
        assertEquals(128 * 1024 * 1024, config.segmentBytes());
        assertEquals(4096, config.indexIntervalBytes());
    }

    @Test
    void keepsEveryRecordUntilRetentionIsAskedForByName() {
        LogConfig defaults = LogConfig.defaults();
        LogConfig sizesOnly = new LogConfig(LogConfig.DEFAULT_MAX_RECORD_BYTES, LogConfig.DEFAULT_SEGMENT_BYTES,
                LogConfig.DEFAULT_INDEX_INTERVAL_BYTES);

        assertFalse(defaults.deletesOnAge(), "the default deletes nothing on age");
        assertFalse(defaults.deletesOnSize(), "and nothing on size");
        assertEquals(defaults, sizesOnly, "so a caller that names only sizes gets the same policy");
    }

    @Test
    void deletesOnABoundOfZeroBecauseZeroIsABoundAndMinusOneIsNoBound() {
        LogConfig everythingSealedGoes = new LogConfig(LogConfig.DEFAULT_MAX_RECORD_BYTES,
                LogConfig.DEFAULT_SEGMENT_BYTES, LogConfig.DEFAULT_INDEX_INTERVAL_BYTES, 0L, 0L);

        assertTrue(everythingSealedGoes.deletesOnAge());
        assertTrue(everythingSealedGoes.deletesOnSize());
    }

    @Test
    void refusesARetentionBoundBelowTheOneThatMeansNoBound() {
        IllegalArgumentException byAge = assertThrows(IllegalArgumentException.class,
                () -> new LogConfig(LogConfig.DEFAULT_MAX_RECORD_BYTES, LogConfig.DEFAULT_SEGMENT_BYTES,
                        LogConfig.DEFAULT_INDEX_INTERVAL_BYTES, -2L, LogConfig.RETENTION_DISABLED));
        IllegalArgumentException bySize = assertThrows(IllegalArgumentException.class,
                () -> new LogConfig(LogConfig.DEFAULT_MAX_RECORD_BYTES, LogConfig.DEFAULT_SEGMENT_BYTES,
                        LogConfig.DEFAULT_INDEX_INTERVAL_BYTES, LogConfig.RETENTION_DISABLED, -2L));

        assertTrue(byAge.getMessage().contains("retentionMs"), byAge.getMessage());
        assertTrue(bySize.getMessage().contains("retentionBytes"), bySize.getMessage());
    }

    @Test
    void refusesASegmentBytesOverTheOneGibibyteCap() {
        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> new LogConfig(LogConfig.DEFAULT_MAX_RECORD_BYTES, LogConfig.MAX_SEGMENT_BYTES + 1,
                        LogConfig.DEFAULT_INDEX_INTERVAL_BYTES));

        assertEquals(1024 * 1024 * 1024, LogConfig.MAX_SEGMENT_BYTES);
        assertTrue(refusal.getMessage().contains("int32"), refusal.getMessage());
    }

    @Test
    void refusesAMaxRecordBytesOverTheOneGibibyteCap() {
        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> new LogConfig(LogConfig.MAX_SEGMENT_BYTES + 1, LogConfig.DEFAULT_SEGMENT_BYTES,
                        LogConfig.DEFAULT_INDEX_INTERVAL_BYTES));

        assertTrue(refusal.getMessage().contains("int32"), refusal.getMessage());
    }

    @Test
    void refusesSizesTooSmallToHoldOneEmptyRecord() {
        int oneByteUnderAnEmptyRecordsFrame = 33;

        assertThrows(IllegalArgumentException.class, () -> new LogConfig(oneByteUnderAnEmptyRecordsFrame,
                LogConfig.DEFAULT_SEGMENT_BYTES, LogConfig.DEFAULT_INDEX_INTERVAL_BYTES));
        assertThrows(IllegalArgumentException.class, () -> new LogConfig(LogConfig.DEFAULT_MAX_RECORD_BYTES,
                oneByteUnderAnEmptyRecordsFrame, LogConfig.DEFAULT_INDEX_INTERVAL_BYTES));
    }

    @Test
    void refusesAnIndexIntervalOfZeroBytes() {
        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> new LogConfig(LogConfig.DEFAULT_MAX_RECORD_BYTES, LogConfig.DEFAULT_SEGMENT_BYTES, 0));

        assertTrue(refusal.getMessage().contains("indexIntervalBytes"), refusal.getMessage());
    }
}
