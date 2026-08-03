# Shrike

Shrike is a single-node, log-structured message broker written in Java 21. Producers append records to a segmented commit log on one machine's disk, consumers read them back by offset, and delivery is at-least-once: a record may be redelivered after a failure, and no record is silently dropped.

Status: Slice 2 — segmented log: size-based rolling, a sparse offset index, and startup recovery.

## Non-goals

- replication
- leader election
- dynamic consumer rebalancing
- transactions
- log compaction
- compression
- TLS or auth
- Kafka wire-protocol compatibility
- any web UI

## Claims

A claim may only be added in the same commit as the test that proves it. CI checks that every Evidence cell below points at something that exists.

| Claim | Evidence | Slice |
|---|---|---|
| A short write cannot truncate a write: the log's write path loops until the buffer is drained, even against a channel that accepts one byte per call | `ByteChannelsTest#writesEveryByteWhenTheChannelAcceptsOnlyOneBytePerCall` | 1 |
| A record reads back byte for byte, with a null key, an empty key, or an empty value all surviving the round trip | `SegmentedLogTest#roundTripsARecordWithNullKeyEmptyKeyOrEmptyValue` | 1 |
| Appends assign offsets sequentially from 0 | `SegmentedLogTest#assignsSequentialOffsetsStartingAtZero` | 1 |
| A read of a negative offset, or of one at or past the high-water mark, is refused with the range that was readable | `SegmentedLogTest#refusesReadsOutsideTheReadableOffsetRange` | 1 |
| A single flipped bit on disk is caught when the record is read, and the error names topic, partition, offset, byte position, and file | `SegmentedLogTest#detectsASingleFlippedBitOnDiskWhenTheRecordIsRead` | 1 |
| A record framing to exactly max.record.bytes (1 MiB by default) is stored | `SegmentedLogTest#storesARecordThatFillsMaxRecordBytesExactly` | 1 |
| A record one byte over max.record.bytes is refused at append and consumes no offset | `SegmentedLogTest#refusesARecordOneByteOverMaxRecordBytes` | 1 |
| Record timestamps come from the injected time source, so no broker logic reads the wall clock | `SegmentedLogTest#stampsRecordsWithTheInjectedTimeSourceRatherThanTheWallClock` | 1 |
| Each partition's records live in `<data dir>/<topic>-<partition>/<20-digit base offset>.log`, with that segment's `.index` beside it | `SegmentedLogTest#writesTheLogFileUnderTopicAndPartitionInsideTheInjectedDataDirectory` | 1 |
| The on-disk frame layout is frozen byte for byte, checksum included | `RecordFrameGoldenBytesTest#freezesTheOnDiskLayoutOfAKnownRecord` | 1 |
| A record that would push a non-empty segment past segment.bytes starts a new segment instead, whose base offset is that record's own offset | `SegmentedLogRollingTest#startsANewSegmentWhenTheNextRecordWouldPushThePreviousPastSegmentBytes` | 2 |
| A record that fills a segment to the byte does not roll it | `SegmentedLogRollingTest#fitsAsManyRecordsIntoASegmentAsSegmentBytesExactlyAllows` | 2 |
| A record larger than segment.bytes is still stored, because an empty segment takes any record max.record.bytes allows | `SegmentedLogRollingTest#storesARecordLargerThanSegmentBytesInASegmentOfItsOwn` | 2 |
| A filled segment's log and index are complete on disk before the next segment takes a record, and neither grows again | `SegmentedLogRollingTest#sealsTheFilledSegmentBeforeTheNextOneTakesARecord` | 2 |
| Every record reads back by offset across many segments | `SegmentedLogRollingTest#readsEveryRecordBackAcrossManySegments` | 2 |
| A segment's index holds one entry per index.interval.bytes of appended records, with each offset stored relative to that segment's base | `SegmentedLogOffsetIndexTest#indexesOneRecordEveryIndexIntervalBytesOfAppendedData` | 2 |
| An offset that falls between two index entries, or before the first one, still reads back the record it names | `SegmentedLogOffsetIndexTest#readsOffsetsThatFallBetweenTwoIndexEntries` | 2 |
| Reopening a log recovers it and appends after its last stored record | `SegmentedLogTest#reopensAnExistingLogAndAppendsAfterItsLastRecord` | 2 |
| A partition reports its log start offset, high-water mark, segment count, and bytes on disk | `SegmentedLogStatisticsTest#reportsOffsetsSegmentCountAndBytesAcrossARoll` | 2 |
| segment.bytes is capped at 1 GiB, so every byte position inside a segment fits an index entry's int32 field | `LogConfigTest#refusesASegmentBytesOverTheOneGibibyteCap` | 2 |
