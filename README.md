# Shrike

Shrike is a single-node, log-structured message broker written in Java 21. Producers append records to a segmented commit log on one machine's disk, consumers read them back by offset, and delivery is at-least-once: a record may be redelivered after a failure, and no record is silently dropped.

Status: Slice 1 — storage core: append-only log, record framing, offset math.

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
| A record reads back byte for byte, with a null key, an empty key, or an empty value all surviving the round trip | `SingleFileLogTest#roundTripsARecordWithNullKeyEmptyKeyOrEmptyValue` | 1 |
| Appends assign offsets sequentially from 0 | `SingleFileLogTest#assignsSequentialOffsetsStartingAtZero` | 1 |
| A read of a negative offset, or of one at or past the high-water mark, is refused with the range that was readable | `SingleFileLogTest#refusesReadsOutsideTheReadableOffsetRange` | 1 |
| A single flipped bit on disk is caught when the record is read, and the error names topic, partition, offset, byte position, and file | `SingleFileLogTest#detectsASingleFlippedBitOnDiskWhenTheRecordIsRead` | 1 |
| A record framing to exactly max.record.bytes (1 MiB by default) is stored | `SingleFileLogTest#storesARecordThatFillsMaxRecordBytesExactly` | 1 |
| A record one byte over max.record.bytes is refused at append and consumes no offset | `SingleFileLogTest#refusesARecordOneByteOverMaxRecordBytes` | 1 |
| Record timestamps come from the injected time source, so no broker logic reads the wall clock | `SingleFileLogTest#stampsRecordsWithTheInjectedTimeSourceRatherThanTheWallClock` | 1 |
| Each partition's records live in `<data dir>/<topic>-<partition>/00000000000000000000.log` | `SingleFileLogTest#writesTheLogFileUnderTopicAndPartitionInsideTheInjectedDataDirectory` | 1 |
| The on-disk frame layout is frozen byte for byte, checksum included | `RecordFrameGoldenBytesTest#freezesTheOnDiskLayoutOfAKnownRecord` | 1 |
