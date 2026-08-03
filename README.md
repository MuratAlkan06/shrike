# Shrike

Shrike is a single-node, log-structured message broker written in Java 21. Producers append records to a segmented commit log on one machine's disk, consumers read them back by offset, and delivery is at-least-once: a record may be redelivered after a failure, and no record is silently dropped.

Status: Slice 3 — a TCP broker with a length-guarded wire protocol, long-polling fetch, durable group offsets, and a blocking client library.

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

## Protocol

One TCP connection carries requests and the answers to them. Every field is big-endian, and each frame begins with a length that counts every byte after itself:

```
request:  length:int32 | apiKey:int16 | apiVersion:int16 | correlationId:int32 | body
response: length:int32 | correlationId:int32 | errorCode:int16 | body
```

The response repeats neither the api key nor the version. The correlation id is the client's own number, echoed back untouched, and it is what says which request an answer belongs to — which is what lets a fetch held open for a while be answered after a produce that arrived later. A version belongs to an api key rather than to the connection, and this build speaks version 0 of four keys:

| Key | Api | Request body | Answer |
|---|---|---|---|
| 0 | produce | topic, partition, records | the offset the first record was appended at |
| 1 | fetch | topic, partition, fetchOffset, maxBytes, maxWaitMs, minBytes | the partition's high-water mark and a block of record frames |
| 2 | commit offset | groupId, topic, partition, offset | an empty body |
| 3 | create topic | name, partitionCount | an empty body |

Keys 4 and 5 are reserved for later slices. Nothing implements them, so a request naming one is refused exactly like an api key that does not exist.

| Code | Name | Means |
|---|---|---|
| 0 | none | the body that follows is the answer |
| 1 | unknown topic or partition | no such topic, or a partition number outside the count it was created with |
| 2 | offset out of range | the offset is outside what that partition can serve |
| 3 | corrupt record | a stored frame no longer matches its checksum |
| 4 | frame too large | a produce record is larger than `max.record.bytes`; none of that request's records were stored |
| 5 | invalid request | the bytes parsed as an envelope, but their contents break a rule of the protocol |
| 6 | unsupported version | the api key exists, but not at the version that was asked for |
| 7 | topic already exists | a topic of that name is already there, whatever partition count was asked for |
| 99 | internal | the broker failed for a reason of its own, which it does not describe to the caller |

**An error response carries an empty body.** The code is the whole answer, so the same six bytes answer every api, and a body behind a non-zero code is a frame a client refuses rather than reads.

**Long-poll fetch.** A fetch that finds fewer than `minBytes` of records readable is held open for up to `maxWaitMs` and answered the moment an append to that partition makes enough available; a wait that runs out is answered with whatever is there — usually nothing — and the code `none`, because "there is nothing new yet" is an answer rather than a failure. A `maxWaitMs` or `minBytes` of 0 is answered on the first pass without waiting at all.

**The frame guard.** A reader takes the four length bytes first and allocates nothing until it believes them. The broker believes a request length inside `[8, max.request.bytes]` — 4 MiB by default — and a length outside it ends the connection with no reply, because the correlation id a reply would be addressed to lives after the length and is worth no more than the length is. The client applies the same shape to responses, bounded by the broker's serving cap plus a kibibyte of envelope headroom.

**Durability, as it stands.** A produce is acknowledged once its records' bytes have been handed to the operating system: ordering and integrity, not the survival of a power cut. A commit is acknowledged only after that group's file has been written, forced, moved into place atomically, and its directory forced. There is no flush-mode setting, because there is no flush mode.

**Names are case-insensitive.** A topic name and a group id are folded before they are used as an identity, because each one becomes a path — `<topic>-<partition>/` for a partition, `groups/<groupId>.offsets` for a group — and a path does not tell two casings apart on APFS, which is what macOS uses by default. So `orders` and `Orders` are one topic, and creating the second is answered `topic already exists`; a data directory that somehow lists both fails the start rather than opening two topics over one directory. A group file already sitting there under an unfolded name is renamed onto the folded one as the broker starts, durably, so that one ordinary commit cannot leave a directory the next start refuses. The fold settles case-insensitivity of the APFS kind and nothing beyond it: Windows path identity folds reserved device names such as `nul` and `com1` too, and this build does not run there anyway, because forcing a directory means opening it for reading and Windows refuses that.

**What the broker will not be asked for.** A fetch's `maxWaitMs` is clamped to `max.fetch.wait.ms` — 30 seconds by default — and its `minBytes` to the most bytes that fetch could ever be served, so neither field can park a connection on a promise that cannot be kept. A create that would push the broker past 1024 open partitions across every topic is refused as an invalid request, because each partition holds a directory and two open files for as long as the broker runs.

**Where it listens, and what it does not check.** The broker binds the loopback interface, and the bind address is not configurable. This build has no authentication, no authorization, and no transport security, so a configurable bind address belongs to the slice that makes exposing the port defensible. There is no server-side read or idle timeout either — `SO_TIMEOUT` bounds reads on a socket's streams and not on the `SocketChannel` the broker reads through — so a local connection that opens and then says nothing, or speaks very slowly, holds its slot until it is closed. What bounds that today is the loopback bind and the connection cap of 64; an idle reaper rides with the bind-address and authentication work. Both are tracked in #9.

## Clients

`shrike-clients` is a blocking client library over that protocol. `ShrikeProducer.send` returns the offset the broker appended a record at, `ShrikeConsumer.fetch` returns the records and the partition's high-water mark, `ShrikeConsumer.commitOffset` stores the offset a group should read next, and `ShrikeTopics.create` creates a topic. There are no background threads, no buffering, and no retries: one connection carries one call at a time, every wait is bounded, and a failure is a typed exception carrying either the broker's error code or the bound that was crossed. It depends on the protocol and codec types of `shrike-core` and on nothing else.

**Which partition a record goes to is decided here, not on the broker.** A produce request has always carried an explicit partition, and a caller that names one is obeyed whatever the record's key is. A caller that would rather not choose hands `ShrikeProducer.send` a `PartitionRouter`, which sends a record with a key to `Math.floorMod((int) crc32c(key), partitionCount)` — the same key to the same partition on every machine and in every JVM, which is what keeps one key's records in one partition and therefore in order — and a record with no key to each partition in turn. CRC32C is reused rather than a second hash added: every record frame already carries one.

## Consumer groups and at-least-once delivery

**Delivery is at-least-once: a record may be delivered more than once, and no acknowledged record is dropped.** A member of a consumer group is handed its records by `ShrikeConsumer.processThenCommit`, which calls the caller's handler first and commits the offset to read next only after that handler has returned. A process that dies between doing the work and committing it does that work again when it is replaced — that is the trade, stated as a cost rather than a footnote: duplicates are possible, records are not lost. An effect that must not repeat has to be made repeatable by whoever writes the handler, and the offsets it is handed are what that can be keyed on. `ConsumerGroupRedeliveryIT#redeliversTheRecordsAKilledMemberProcessedButNeverCommittedToTheMemberThatReplacesIt` is the proof: a member is killed with `destroyForcibly` while it is holding records it has processed and not committed, and every one of those records is delivered again to the process started in its place.

**Assignment is arithmetic, and there is no dynamic rebalancing** — it is a non-goal of this build, listed above and restated here because this is the section where it matters. A member is configured with a group id, its own index, and how many members the group has, and it reads the partitions where `partition % memberCount == memberIndex`. The broker stores committed offsets keyed by group, topic, and partition and nothing else: no members, no sessions, no heartbeats. A member that dies is replaced by an operator starting a process with the same index, which resumes from the last offset that index committed; until then, its partitions are read by nobody. Two live processes sharing one index is the operator's mistake to avoid, and nothing here can detect it — what it costs is redelivery, which at-least-once delivery already allows, rather than a record nobody reads.

## Recovery

The recovery promise is tail-only. Opening a partition walks the last segment frame by frame — checking each frame's length, CRC32C, magic, and the offset it carries — and cuts the file off after the last whole frame, logging a WARN that names the byte position it truncated at. The records lost that way are records no producer was ever told about. Startup does not fail on a torn tail, and running it twice with no writes in between changes no file and no offset.

Earlier segments are not walked, because a segment is forced before it is sealed. Damage inside a sealed segment is therefore left exactly where it is: startup succeeds, reading the damaged record fails with a corrupt-record error naming its topic, partition, offset, byte position, and file, and the records before and after it still read.

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
| A torn tail is truncated to the last valid record at startup, and startup succeeds: an empty file, a truncated header or payload, a flipped bit, a negative, oversized, or zero length field, and trailing zeros all cost only the records that were being written | `SegmentedLogRecoveryTest#truncatesATornTailToTheLastValidRecordOnRestart` | 2 |
| Damage inside a sealed segment is left where it is: startup succeeds, that record's read fails with the corrupt-record error, and the records before and after it still read | `SegmentedLogRecoveryTest#keepsDamageInsideASealedSegmentAndStillReadsItsNeighbours` | 2 |
| A deleted index is rebuilt from the log alone, byte for byte identical to the one the appends had written | `SegmentedLogRecoveryTest#rebuildsADeletedIndexFromTheLogAlone` | 2 |
| An index entry pointing past the end of its log makes the index untrustworthy, so it is emptied and rebuilt | `SegmentedLogRecoveryTest#rebuildsAnIndexThatPointsPastTheEndOfItsLog` | 2 |
| A second restart with no writes in between changes no file size and no offset | `SegmentedLogRecoveryTest#changesNoFileAndNoOffsetOnASecondRestartWithoutWrites` | 2 |
| After a torn tail is truncated, the reported high-water mark and bytes on disk shrink to match what survived | `SegmentedLogRecoveryTest#reportsTheTruncatedSizeAfterRecoveringATornTail` | 2 |
| A produce is answered with the offset its first record was appended at, and each partition counts its own offsets | `BrokerProduceTest#answersEachProduceWithTheOffsetOfItsFirstRecord` | 3 |
| A waiting fetch is served the record that lands in the instant it registered as a waiter, instead of waiting out its maxWaitMs | `BrokerFetchWaitTest#servesAWaitingFetchTheRecordThatLandsTheInstantItRegisteredAsAWaiter` | 3 |
| A fetch response carries the record frames of the segment file byte for byte, rather than a re-encoding of them | `BrokerFetchBytesTest#servesTheRecordsBlockAsTheVerbatimBytesOfTheSegmentFile` | 3 |
| A request length the broker cannot believe closes the connection with no reply at all | `BrokerErrorResponseTest#closesTheConnectionWithNoReplyWhenTheLengthPrefixCannotBeBelieved` | 3 |
| Such a length also costs no memory: the reader is driven by a channel that fails the test if anything reads past the length field | `RequestReaderTest#closesTheConnectionWithoutAllocatingOnALengthOverMaxRequestBytes` | 3 |
| A commit returns to the client only after that group's file is written, forced, moved into place, and its directory forced | `GroupOffsetStoreTest#returnsFromACommitOnlyAfterTheFileIsWrittenForcedMovedAndItsDirectoryForced` | 3 |
| Topics, their partition counts, every record, and every committed offset survive a restart over the same data directory | `BrokerRestartTest#keepsItsTopicsRecordsAndCommittedOffsetsAcrossARestart` | 3 |
| A response length outside the client's own guard closes the connection before a byte of body is read | `BrokerConnectionGuardTest#closesTheConnectionWhenAnAnswerDeclaresALengthOutsideTheGuard` | 3 |
| An answer carrying a correlation id the client never sent closes the connection too | `BrokerConnectionGuardTest#closesTheConnectionWhenAnAnswerCarriesSomebodyElsesCorrelationId` | 3 |
| A broker error code reaches the caller as a typed exception carrying that code, on a connection that stays usable | `ClientRoundTripTest#raisesTheBrokersErrorCodeAsATypedFailureAndKeepsTheConnectionUsable` | 3 |
| A fetch's client-side bound is its maxWaitMs plus a margin, so a long poll longer than that margin is not cut short by the client | `ClientRoundTripTest#holdsAFetchOpenForItsMaxWaitMsEvenWhenThatIsLongerThanTheReadTimeout` | 3 |
| Produce, fetch, commit, and resume work between separate operating-system processes: a consumer process that commits and exits is replaced by one that starts from the offsets the broker stored and reads only the records produced since | `ClientProcessIT#carriesRecordsBetweenSeparateProducerAndConsumerProcessesAndResumesFromTheCommittedOffsets` | 3 |
| A create whose name differs from an existing topic's only in case is refused with topic already exists, whatever partition count it asks for | `BrokerTopicIdentityTest#refusesACreateThatDiffersFromAnExistingTopicOnlyInCase` | 3 |
| A registry file listing two topic names that differ only in case fails the start, with a message naming the file to fix | `BrokerTopicIdentityTest#refusesToStartOverARegistryThatListsTwoTopicsDifferingOnlyInCase` | 3 |
| Two group ids that differ only in case are one group with one file, so a commit under either casing is read back under both | `GroupOffsetStoreTest#keepsOneSetOfCommittedOffsetsForGroupIdsThatDifferOnlyInCase` | 3 |
| A create that would push the broker past its total partition budget is refused as an invalid request, and the connection that asked keeps being served | `BrokerPartitionBudgetTest#refusesACreateThatWouldPassThePartitionBudgetAndKeepsServing` | 3 |
| A fetch asking for more bytes than it could ever be served is answered with the records that fill it as soon as they land, rather than waiting out its maxWaitMs | `BrokerFetchWaitTest#answersAFetchAskingForMoreBytesThanItCanBeServedAsSoonAsItCanBeFilled` | 3 |
| A connection whose handoff to a thread fails is closed, its place under the connection cap comes back, and the acceptor goes on accepting | `BrokerConnectionHandoffTest#closesTheSocketAndKeepsAcceptingWhenAConnectionHandoffFails` | 3 |
| A group offsets file written under an unfolded name is renamed onto the folded one when the store opens, keeping its committed offsets, and the start after the next commit opens rather than refusing | `GroupOffsetStoreTest#renamesAGroupFileWrittenUnderAnUnfoldedNameOntoItsFoldedName` | 3 |
| A key routes to the same partition in every JVM and on every machine, for a table of frozen keys over two partition counts, and a record with no key takes each partition in turn | `PartitionRouterTest#routesKnownKeysToTheFrozenPartitionsOnEveryJvm` | 4 |
| The partition the router picked is the partition the broker appended to, and a caller that names a partition is obeyed whatever its key would have routed to | `ProducerRoutingTest#appendsToThePartitionTheCallerNamedRatherThanTheOneTheKeyRoutesTo` | 4 |
| A group member reads the partitions where partition % memberCount == memberIndex, and a group with more members than the topic has partitions gives the extra ones nothing to read | `GroupAssignmentTest#ownsThePartitionsWhoseNumberFallsToItsMemberIndex` | 4 |
| A member index outside its own member count is refused where the member is configured, with a message naming both numbers | `GroupAssignmentTest#refusesAMemberIndexThatIsNotOneOfTheMembers` | 4 |
| A handler that throws commits nothing, and the very same records are read again | `CommitAfterProcessingTest#commitsNothingWhenTheHandlerThrowsAndReadsTheSameRecordsAgain` | 4 |
| The offset is committed only after the handler has returned: while it runs, the group's file says nothing about those records | `CommitAfterProcessingTest#commitsTheOffsetToReadNextOnlyOnceTheHandlerHasReturned` | 4 |
| Delivery is at-least-once across a kill: a member killed with SIGKILL while holding records it had processed and not committed has every one of them delivered again to the process started in its place, which begins exactly at the offset the broker had stored, and no produced record is missing from any journal | `ConsumerGroupRedeliveryIT#redeliversTheRecordsAKilledMemberProcessedButNeverCommittedToTheMemberThatReplacesIt` | 4 |
