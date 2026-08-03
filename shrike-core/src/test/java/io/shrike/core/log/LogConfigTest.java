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
    void defaultsToFlushingOnWhicheverOfOneHundredMillisecondsAndOneMebibyteComesFirst() {
        LogConfig defaults = LogConfig.defaults();
        LogConfig sizesAndRetentionOnly = new LogConfig(LogConfig.DEFAULT_MAX_RECORD_BYTES,
                LogConfig.DEFAULT_SEGMENT_BYTES, LogConfig.DEFAULT_INDEX_INTERVAL_BYTES,
                LogConfig.RETENTION_DISABLED, LogConfig.RETENTION_DISABLED);

        assertEquals(FlushMode.INTERVAL, defaults.flushMode(), "an fsync per record is a cost a deployment chooses");
        assertFalse(defaults.forcesEveryRecord());
        assertEquals(100L, defaults.flushIntervalMs());
        assertEquals(1024L * 1024L, defaults.flushIntervalBytes());
        assertEquals(defaults, sizesAndRetentionOnly, "so a caller that names no flush policy gets this one");
    }

    @Test
    void refusesAFlushIntervalOfZeroBecausePerRecordIsWhatThatWouldMean() {
        IllegalArgumentException byTime = assertThrows(IllegalArgumentException.class,
                () -> flushingEvery(0L, LogConfig.DEFAULT_FLUSH_INTERVAL_BYTES));
        IllegalArgumentException byVolume = assertThrows(IllegalArgumentException.class,
                () -> flushingEvery(LogConfig.DEFAULT_FLUSH_INTERVAL_MS, 0L));

        assertTrue(byTime.getMessage().contains("PER_RECORD"), byTime.getMessage());
        assertTrue(byVolume.getMessage().contains("PER_RECORD"), byVolume.getMessage());
    }

    @Test
    void forcesEveryRecordOnlyWhenTheModeSaysSo() {
        LogConfig perRecord = new LogConfig(LogConfig.DEFAULT_MAX_RECORD_BYTES, LogConfig.DEFAULT_SEGMENT_BYTES,
                LogConfig.DEFAULT_INDEX_INTERVAL_BYTES, LogConfig.RETENTION_DISABLED, LogConfig.RETENTION_DISABLED,
                FlushMode.PER_RECORD, LogConfig.DEFAULT_FLUSH_INTERVAL_MS, LogConfig.DEFAULT_FLUSH_INTERVAL_BYTES);

        assertTrue(perRecord.forcesEveryRecord());
    }

    @Test
    void refusesAnIndexIntervalOfZeroBytes() {
        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> new LogConfig(LogConfig.DEFAULT_MAX_RECORD_BYTES, LogConfig.DEFAULT_SEGMENT_BYTES, 0));

        assertTrue(refusal.getMessage().contains("indexIntervalBytes"), refusal.getMessage());
    }

    private static LogConfig flushingEvery(long flushIntervalMs, long flushIntervalBytes) {
        return new LogConfig(LogConfig.DEFAULT_MAX_RECORD_BYTES, LogConfig.DEFAULT_SEGMENT_BYTES,
                LogConfig.DEFAULT_INDEX_INTERVAL_BYTES, LogConfig.RETENTION_DISABLED, LogConfig.RETENTION_DISABLED,
                FlushMode.INTERVAL, flushIntervalMs, flushIntervalBytes);
    }
}
