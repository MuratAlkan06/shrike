# Design decisions

Each entry states the decision, the strongest alternative that was rejected, and why, in plain language. They are written to be quotable in an interview, so nothing here hides behind jargon.

## Module graph: shrike-core <- shrike-clients <- shrike-admin

**Decision.** Three Maven modules in one reactor. `shrike-core` holds the broker engine and depends on the JDK alone. `shrike-clients` depends on core for its protocol and codec types. `shrike-admin` depends on clients and is the only module allowed to use a framework. Nothing points back up the chain.

**Alternative rejected.** A single module with packages as the only boundary.

**Why.** A package boundary is a convention; a module boundary is compiled and checked. The maven-enforcer bannedDependencies rule on core turns "core stays JDK-only" into a build failure rather than a code-review opinion, and it keeps the engine testable without a framework on the classpath.

## Spring enters through an imported BOM, not a parent POM

**Decision.** `shrike-admin` imports `spring-boot-dependencies` as a `pom`-typed dependency with `import` scope in its own dependencyManagement, and pins `spring-boot-maven-plugin` itself.

**Alternative rejected.** Inheriting from `spring-boot-starter-parent`.

**Why.** The reactor needs its own parent for the toolchain, enforcer, and version pinning that apply to every module, and a POM has only one parent. Importing the BOM keeps Spring's version alignment where it is wanted — inside the admin module — while core and clients carry no Spring coordinate at all. The cost is that the BOM brings no plugin management, so the plugin version is pinned by hand.

## Toolchains plugin, because local Maven runs on a Java 25 JVM

**Decision.** `maven.compiler.release=21` plus `maven-toolchains-plugin` requiring vendor `temurin` version `21`, and a test that asserts `Runtime.version().feature() == 21`.

**Alternative rejected.** Relying on `release=21` alone.

**Why.** `release=21` fixes the language level and the API surface, but the build still compiles and runs tests on whatever JVM launched Maven — here a Java 25 JVM. The toolchain forces javac and the surefire fork onto a real JDK 21, so what CI tests is what a JDK 21 user runs. CI adds a class-file check for major version 65, and the build fails outright when no matching toolchain is declared, which is the loud failure the alternative lacks.

## The clock is an injected TimeSource in its own package

**Decision.** `io.shrike.core.time.TimeSource` is a single-method interface returning epoch milliseconds; `SystemTimeSource` is the only code in the broker that calls `System.currentTimeMillis()`. Components take a `TimeSource` through their constructor.

**Alternative rejected.** Calling `System.currentTimeMillis()` where the timestamp is needed, and letting tests assert a range around "now".

**Why.** A record's timestamp is data that ends up on disk, so tests should assert its exact value, not that it fell inside a window. Injection makes that possible without a single sleep, and it keeps the wall clock — the one thing a test cannot control — behind a seam that is one line long. The interface lives in `io.shrike.core.time` rather than in `io.shrike.core.log` because retention, delivery timeouts, and the network layer will all need the same clock, and none of them should have to depend on the log package to get it.

## The record frame, byte by byte

**Decision.** Every record is stored as one frame, big-endian, with a fixed-width header:

```
length:int32 | crc32c:uint32 | magic:uint8 | attributes:uint8 | offset:int64 | timestamp:int64 | keyLen:int32 | key | valueLen:int32 | value
```

- `length` counts every byte after itself, so a frame occupies `4 + length` bytes on disk.
- `crc32c` is `java.util.zip.CRC32C` over the bytes from `magic` through the last byte of `value`. The `length` field is deliberately outside that cover.
- `magic` is 0 and identifies this layout. `attributes` is 0 and reserved; a reader steps over its bits so a later slice can spend them without invalidating today's files.
- `offset` is written into the frame rather than inferred from the file.
- `keyLen` is -1 for a record appended with no key and 0 for a key of zero bytes, so the two stay distinguishable after a round trip. `valueLen` is never negative: a null value would be a tombstone, tombstones only mean something under compaction, and compaction is a non-goal, so `append` refuses one.

**Alternative rejected.** Covering the whole frame with the checksum, including the length, and skipping the explicit `offset` field because a reader could count records from the start of the file.

**Why.** A reader meets the length field first and must decide how many bytes to allocate for the rest of the frame *before* it has anything to verify: a checksum over the length is a checksum it cannot use yet. So the length is range-checked against `max.record.bytes` instead — the same bound the append side enforces — and a corrupt or hostile length is refused rather than believed and turned into a 2 GiB allocation. Everything after the length is covered, so a flipped bit anywhere in the header or payload fails the read.

Writing the offset into the frame costs eight bytes and buys self-description: a frame carries its own identity, so a reader can assert that the record at a position is the record it asked for, and a later recovery scan can rebuild offsets from the file alone instead of trusting a count. `RecordFrameGoldenBytesTest` freezes the whole layout, checksum included, against bytes derived from this description rather than from the implementation.

## `offset` counts records, `position` counts bytes — and the names never blur

**Refined in Slice 2.** The in-memory `positionsByOffset` list is gone; a segment's sparse index is what maps an offset to a position now, and the mapping is rebuilt from the log rather than held for the life of the process. The law itself is unchanged, and the last sentence of this entry is why the swap cost nothing.

**Decision.** `offset` is always a logical record number and `position` (always spelled `positionBytes` on a variable) is always a byte location in a file. `Log.append` returns an offset; the in-memory `positionsByOffset` maps one to the other; `RecordLocation` carries both plus topic, partition, and file, and every storage error quotes it.

**Alternative rejected.** Letting a reader address records by byte position, which is what the file actually needs.

**Why.** The two numbers are both `long`, so the compiler cannot tell them apart and only names can. Keeping the offset as the public currency also keeps the door open for segments and an index: when a record moves into a different file at a different position, its offset does not change. The mapping is the part that is allowed to be rebuilt.

## A read outside the readable range throws, and reads return a `StoredRecord`

**Decision.** `Log.read(long offset)` returns a `StoredRecord` — a record of `offset`, `timestampMillis`, `key`, and `value` — or throws. A negative offset and an offset at or past the high-water mark (`nextOffset()`) both raise `OffsetOutOfRangeException`, which carries the requested offset and the readable range `[firstOffset, nextOffset)`. An empty log has the empty range `[0, 0)` and refuses every read.

**Alternative rejected.** Returning `Optional<StoredRecord>` or `null` for a read past the end, and clamping a negative offset to 0.

**Why.** Reading past the end and reading before the start are two different mistakes, and a caller that gets `Optional.empty()` cannot tell them apart or learn what the valid range was — it would have to ask a second question to write a useful error. Clamping is worse: it silently answers a question the caller did not ask. A consumer that wants "wait until there is more data" is asking about the network layer's fetch semantics, not about a byte range in a file, so that behavior belongs in a later slice rather than smuggled into this return type. `StoredRecord` returns the stored offset and timestamp alongside the payload so a caller can verify what it got rather than assume.

## The log refuses to reopen a file it did not create

**Superseded by Slice 2.** Recovery arrived, so reopening no longer refuses: see "Reopening a log recovers it, because Slice 2 can read a file it did not write". The entry is kept because the reasoning it records is why recovery had to exist before reopening was allowed.

**Decision.** `SingleFileLog.open` creates the log file with `CREATE_NEW`. An existing file fails the open with a `ShrikeIOException` that says a recovery scan is a later slice. The offset-to-position map is held in memory only.

**Alternative rejected.** Opening an existing file and appending to the end of it.

**Why.** Appending to a file this class has not read means trusting that its last record is intact and that the next offset is whatever the file implies. Deciding that safely is exactly what recovery does — scan for the last valid frame, truncate a torn tail, and say so — and recovery is not in this slice. Refusing loudly beats a broker that quietly appends after a half-written record. It also makes the in-memory map honest: nothing is lost by not persisting it, because there is no path that needs it after a restart yet. Its size is bounded by `Integer.MAX_VALUE` records, which is another reason it is temporary.

## Durability in this slice: appends reach the operating system, `close` forces the file

**Refined in Slice 2.** Sealing a segment forces it, so a log now has two force points rather than one: the roll that seals a segment, and `close()` for the segment still taking records. What an `append` promises has not changed — see "Segments roll on size, and a segment is sealed only after it is forced".

**Decision.** `append` writes the frame through `writeFully` and returns once the operating system has the bytes. Nothing is forced to the device until `close()`, which calls `force(true)` before closing the channel. The README claims ordering and integrity, not survival of a power cut.

**Alternative rejected.** Forcing on every append, which would let the log claim durability today.

**Why.** An fsync per record is a policy decision with a large cost, and the policy — per record, per interval, or never — is a configured flush mode in a later slice. Choosing one now would either pick the slow default for everyone or leave the claim vague. Forcing at `close()` is the one point where the cost is paid once and the promise is exact, so it is the only durability sentence this slice makes.

## Segments roll on size, and a segment is sealed only after it is forced

**Decision.** A partition is a sequence of segment files named after the offset of their first record: `00000000000000000000.log` beside `00000000000000000000.index`, then whatever base offset comes next. Appending to a **non-empty** active segment rolls it when the record would push it *past* `segment.bytes`; a record that fills the segment to the byte does not roll it, and the record that would have overflowed becomes the first record of a new segment whose base offset is that record's own offset. An empty active segment accepts any record `max.record.bytes` allows, even a frame larger than `segment.bytes`, because a record no segment would take could never be stored at all. Rolling seals the segment it leaves behind, in this order: force the log file, force the index file, and only then treat the pair as immutable.

**Alternative rejected.** Rolling when a segment *reaches* `segment.bytes` and marking segments sealed without forcing them, leaving the fsync to the operating system's own schedule.

**Why.** The order of those three steps is the whole point, and it buys the recovery algorithm. Because a sealed segment was on the device before the writer moved on, a crash can only tear the segment that was being written, so startup reads one file frame by frame instead of all of them — a partition with a thousand segments costs the same to recover as a partition with one. Sealing without forcing would make that reasoning false: any segment could then hold a half-written frame, and honest recovery would have to walk every byte the broker had ever stored. Rolling on "would exceed" rather than "would reach" keeps `segment.bytes` a bound the files respect rather than one they cross by a frame, and the empty-segment exception is what keeps the bound from turning into a record the log refuses to store.

## The offset index is derived data, so nothing trusts it

**Decision.** Each segment carries a sparse index of fixed 8-byte big-endian entries, `relativeOffset:int32 | position:int32`, holding one entry per `index.interval.bytes` of appended records. Offsets are stored relative to the segment's base offset and positions as int32, which the 1 GiB cap on `segment.bytes` and `max.record.bytes` keeps safe. A lookup finds the segment whose base offset is the greatest one at or below the target, binary-searches that segment's entries for the greatest relative offset at or below it, and walks frames from there comparing each frame's stored offset with the one asked for — a walk bounded by the index interval. An index that is missing, whose size is not a whole number of entries, or whose entries do not climb or point inside the log is emptied and rebuilt by scanning the log. The rule that decides which record earns an entry is one method, `indexIfDue`, called both by `append` and by the scan that rebuilds, so it reads nothing but the bytes of the log.

**Alternative rejected.** A dense index — one entry per record, which is what Slice 1's in-memory `positionsByOffset` list was — and treating a damaged index as a reason to fail startup.

**Why.** A dense index costs 8 bytes of memory or disk per record forever and buys a lookup that is already cheap: with a 4 KiB interval, the walk after a binary search reads at most a few kilobytes. More importantly, an index restates what the log already says, so making it authoritative would create a second copy of the truth that can disagree with the first. Deriving it means a corrupt index is an inconvenience rather than data loss, and it means the code has one honest answer to "what if the index is wrong": delete it and read the log. Because the entry rule lives in exactly one method, an index grown record by record and an index rebuilt in one pass are the same file, which is what lets recovery rewrite an index without changing anything a reader can see. An index with no entries at all still yields correct reads — a lookup that finds no entry starts at the first byte of the segment — which is the strongest way to say the index is not load-bearing.

## Recovery walks the tail segment, cuts off what is torn, and logs where

**Decision.** Opening a partition directory sorts its segments by base offset. Every segment but the last is sealed, so it gets a cheap check of its index only — the file exists, its size is a whole number of entries, no entry points past the end of the log — and is rebuilt only when that fails; its log file is not read. The last segment is walked frame by frame from its first byte, applying the checks a read applies: the length field must be in range, the CRC32C must match, the magic must be this build's, and the frame's own offset must be the one the walk expects. The first frame that fails, or that the file ends inside, ends the log: the channel is truncated to the byte after the last whole frame, the tail's index is rebuilt from the same walk, a WARN names the topic, partition, byte position, and file, and the next offset becomes the base offset plus the number of frames that survived. A zero-byte tail segment is a valid empty segment; fewer than four trailing bytes cannot even hold a length field, so they are cut off; a run of trailing zeros fails the length range check and is cut off too. Damage inside a sealed segment is not repaired: startup does not look, the damaged record fails with a `CorruptRecordException` when it is read, and the records before and after it still read.

**Alternative rejected.** Walking every segment at startup, and refusing to start when the tail is torn so that a human decides what to do.

**Why.** Walking every segment would make startup cost grow with everything ever stored, and it would be redundant work: the sealing order already proves the earlier segments are whole. Refusing to start is the option that sounds safe and is not — a broker that will not come up after a power cut is down, and the torn tail is by definition made of records no producer was ever told about, so cutting them off loses nothing that was acknowledged. The WARN with the byte position is what keeps that honest: the truncation is a fact in the log file, not a silent repair. Recovery is idempotent because the walk depends only on the bytes on disk: a second restart with no writes in between truncates nothing, writes the same index entries, and leaves every file the size it already was, which is asserted rather than assumed. Leaving sealed damage in place is the same honesty in the other direction: the broker reports the record it cannot vouch for instead of quietly deleting the healthy records that follow it.

## Reopening a log recovers it, because Slice 2 can read a file it did not write

**Decision.** `SegmentedLog.open` creates a partition directory and its first segment when there is nothing there, and recovers what is there when there is. This replaces Slice 1's refusal to reopen an existing log file. The in-memory offset-to-position map is gone: a reopened log finds its records through the segment index instead.

**Alternative rejected.** Keeping the refusal for existing files and adding a separate explicit "recover this directory" entry point that a caller has to remember to call first.

**Why.** Slice 1 refused because appending after a record it had never checked would have meant trusting a file it had not read, and it said so in the exception. That reason is now satisfied rather than argued with: opening reads the tail and proves its last frame before it appends a byte after it. A separate recovery step would leave two ways to open a log, one of them wrong, and the wrong one would be the shorter one to type. Recovery is not an operation on a log — it is what opening one means. Dropping the in-memory map is what makes the size of a partition a disk question rather than a heap question, which is the same reason the index exists.

## The log's sizes live in one record next to the log

**Refined in Slice 3.** `RecordFrame` is public now. A fetch response carries a byte range of a partition's log verbatim, so the consumer that reads those records back parses the same frames the log writes, and one parser for one layout beats two that are free to disagree — `WireRecordsTest` reads the very bytes `RecordFrameGoldenBytesTest` freezes. What this entry says about where validation belongs is unchanged: `max.record.bytes` is still checked in the log package, and what became public is the parse and the three constants a parser needs. The same move settles the boundary PRINCIPLES §9 asks to have written down: `StoredRecord` and `ProducedRecord` are part of the codec surface `shrike-clients` may depend on, because they are value types with no behavior, and nothing else in `io.shrike.core.log` is on that side of the line.

**Decision.** `LogConfig` is a record of `maxRecordBytes`, `segmentBytes`, and `indexIntervalBytes`, validated in its compact constructor, with a `defaults()` factory holding 1 MiB, 128 MiB, and 4 KiB. `segmentBytes` and `maxRecordBytes` are both capped at 1 GiB. It lives in `io.shrike.core.log` beside the log it configures, and it holds no flush or retention setting, because neither behavior exists yet.

**Alternative rejected.** Putting it in `io.shrike.core.config` as the start of a broker-wide configuration type, and keeping `open(...)` overloads that take one `int` per setting.

**Why.** Three `int` parameters in an `open` call are three chances to swap two numbers that the compiler cannot tell apart; a record names them at the call site and validates them once, so a log that holds a config holds one that already makes sense. Validation belongs there rather than in `open` because the smallest legal `maxRecordBytes` is a fact about the frame layout, and keeping the check in the log package is what lets `RecordFrame` stay package-private instead of publishing frame constants to satisfy a config type in another package. The 1 GiB cap is not a taste preference: an index entry stores a byte position in an int32, and the cap is what makes that field safe by construction rather than by hope. Settings for behavior that does not exist are left out, because a knob with no code behind it is a promise the README cannot make.

## The request envelope names an api and its version; the response echoes a correlation id

**Decision.** A request is `length:int32 | apiKey:int16 | apiVersion:int16 | correlationId:int32 | body` and a response is `length:int32 | correlationId:int32 | errorCode:int16 | body`, big-endian throughout, with `length` counting every byte after itself in both. The api key names the operation — 0 produce, 1 fetch, 2 commit offset, 3 create topic, with 4 and 5 reserved and unimplemented — and the version belongs to the key rather than to the connection: `ApiKeys.isSupportedVersion(apiKey, apiVersion)` is the one method that decides whether this build understands a pair, and today it says yes only to version 0. The correlation id is the client's own number, echoed back untouched, and the response repeats neither the api key nor the version.

**Alternative rejected.** One protocol version agreed when the connection opens, and matching each response to its request by the order the two travel in.

**Why.** Order as identity works right up to the first time a client wants two requests in flight, or the broker wants to answer a fetch that is still waiting for records after a produce that arrived later — which is what a fetch held open for `maxWaitMs` will do. A four-byte number the broker never interprets buys that for the price of one field, and it turns a client's bookkeeping into a lookup by key rather than a queue whose invariant nobody can see. A single protocol version is worse than it looks: the day fetch grows a field, produce's version number moves with it, and every client has to be told that its unchanged produce path now speaks version 1. Versioning per key keeps a change local to the api that changed. The response drops the api key because the correlation id already says which question is being answered, and a field the broker restates is a field that can be found disagreeing with the request it answers.

## One error code in the envelope, and an error answer carries no body

**Decision.** Every response carries one int16: 0 none, 1 unknown topic or partition, 2 offset out of range, 3 corrupt record, 4 frame too large, 5 invalid request, 6 unsupported version, 7 topic already exists, 99 internal. `NONE` means the body that follows is the answer; every other code means the body is empty and the code is the whole answer. The codec refuses only what the bytes themselves can be wrong about — a negative string length, a record count over its cap, a name outside the character set a path component may use, an api key or version this build does not implement — and leaves what the broker holds to the broker. An unknown topic, an offset outside a partition's range, and a topic that already exists are answers rather than malformed requests, which is why `FetchRequest` does not judge its own offset and `ProduceRequest` does not judge its partition number. Reserved api keys 4 and 5 earn `INVALID_REQUEST`, the same as an api key of 999. `FRAME_TOO_LARGE` is the one code nothing in this slice can produce: it is held for a produce request whose record exceeds `max.record.bytes`, which is a check the broker makes once this protocol reaches it.

**Alternative rejected.** A per-api error body — a message, or a map of per-partition codes — and a distinct code for an api key that is reserved rather than unknown.

**Why.** An error body is a place to leak from. What the parser can say about the field it choked on is useful to whoever runs the broker and useful in a different way to whoever is probing it, so that sentence lives in `RequestDecoding.Refused` for the broker's own log and is never encoded. What is left is the code, and one code in the envelope means the same six bytes answer every api, which is what lets a client read a failure without knowing which request failed. A separate code for a reserved key would tell a caller which numbers are worth waiting for, and it would be a promise this build cannot keep: 4 and 999 are equally "not a request I implement" today, and that is the whole truth about both. Naming `FRAME_TOO_LARGE` with nothing behind it yet is the honest half of freezing numbers — a wire number can be added but never renumbered, so all nine are spelled out now and this entry says which one has no code behind it.

## A length this reader cannot believe closes the connection and is owed no answer

**Decision.** `RequestReader` reads exactly four bytes, and the length they carry must land in `[8, maxRequestBytes]`: 8 because that is the smallest length that can hold an envelope, and `maxRequestBytes` because a broker decides how much memory one connection may make it hold — 4 MiB by default, passed in rather than compiled into the reader. A length outside that range ends the connection with no reply and with nothing allocated for a body, and so does a stream that ends inside the length field or inside the frame. Only a length that passes gets a buffer allocated and filled. Inside the body every count and every declared length is checked against the bytes actually in hand, and against its own cap, before anything is sized to it: a string length against what remains, a produce record count against both its cap of 10 000 and the fewest bytes that many records could occupy, a key or value length against what remains. A body that breaks a rule is a refusal carrying the correlation id and `INVALID_REQUEST`, and the connection lives through it. Those three outcomes are one sealed type, `RequestDecoding` — `Accepted`, `Refused`, `BrokenFrame` — so a connection loop that forgets one does not compile.

**Alternative rejected.** Answering a bad length with `FRAME_TOO_LARGE` and then skipping that many bytes to find where the next frame starts.

**Why.** The correlation id lives after the length. If the length is nonsense then the bytes claiming to be a correlation id are nonsense too, so the reply would be addressed to nobody, and it would tell a stranger that something is listening and how it parses. Skipping ahead is worse than useless: it resynchronizes the stream by trusting the same number that just proved it cannot be trusted. Closing costs a well-behaved client nothing, because a well-behaved client never sends such a length, and it costs a hostile one the connection it was using. The allocation rule is the other half of the same argument: four bytes can ask for 2 GiB, and a reader that allocates first and validates second can be brought down by four bytes, repeatedly, from one socket. So the guard allocates a fixed four bytes, decides, and only then sizes anything to what it was told — and the test that proves it drives the reader with a channel that fails the run if anything reads past the length field, because a body that was never read is a body that was never allocated for.
