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

**Decision.** `offset` is always a logical record number and `position` (always spelled `positionBytes` on a variable) is always a byte location in a file. `Log.append` returns an offset; the in-memory `positionsByOffset` maps one to the other; `RecordLocation` carries both plus topic, partition, and file, and every storage error quotes it.

**Alternative rejected.** Letting a reader address records by byte position, which is what the file actually needs.

**Why.** The two numbers are both `long`, so the compiler cannot tell them apart and only names can. Keeping the offset as the public currency also keeps the door open for segments and an index: when a record moves into a different file at a different position, its offset does not change. The mapping is the part that is allowed to be rebuilt.

## A read outside the readable range throws, and reads return a `StoredRecord`

**Decision.** `Log.read(long offset)` returns a `StoredRecord` — a record of `offset`, `timestampMillis`, `key`, and `value` — or throws. A negative offset and an offset at or past the high-water mark (`nextOffset()`) both raise `OffsetOutOfRangeException`, which carries the requested offset and the readable range `[firstOffset, nextOffset)`. An empty log has the empty range `[0, 0)` and refuses every read.

**Alternative rejected.** Returning `Optional<StoredRecord>` or `null` for a read past the end, and clamping a negative offset to 0.

**Why.** Reading past the end and reading before the start are two different mistakes, and a caller that gets `Optional.empty()` cannot tell them apart or learn what the valid range was — it would have to ask a second question to write a useful error. Clamping is worse: it silently answers a question the caller did not ask. A consumer that wants "wait until there is more data" is asking about the network layer's fetch semantics, not about a byte range in a file, so that behavior belongs in a later slice rather than smuggled into this return type. `StoredRecord` returns the stored offset and timestamp alongside the payload so a caller can verify what it got rather than assume.

## The log refuses to reopen a file it did not create

**Decision.** `SingleFileLog.open` creates the log file with `CREATE_NEW`. An existing file fails the open with a `ShrikeIOException` that says a recovery scan is a later slice. The offset-to-position map is held in memory only.

**Alternative rejected.** Opening an existing file and appending to the end of it.

**Why.** Appending to a file this class has not read means trusting that its last record is intact and that the next offset is whatever the file implies. Deciding that safely is exactly what recovery does — scan for the last valid frame, truncate a torn tail, and say so — and recovery is not in this slice. Refusing loudly beats a broker that quietly appends after a half-written record. It also makes the in-memory map honest: nothing is lost by not persisting it, because there is no path that needs it after a restart yet. Its size is bounded by `Integer.MAX_VALUE` records, which is another reason it is temporary.

## Durability in this slice: appends reach the operating system, `close` forces the file

**Decision.** `append` writes the frame through `writeFully` and returns once the operating system has the bytes. Nothing is forced to the device until `close()`, which calls `force(true)` before closing the channel. The README claims ordering and integrity, not survival of a power cut.

**Alternative rejected.** Forcing on every append, which would let the log claim durability today.

**Why.** An fsync per record is a policy decision with a large cost, and the policy — per record, per interval, or never — is a configured flush mode in a later slice. Choosing one now would either pick the slow default for everyone or leave the claim vague. Forcing at `close()` is the one point where the cost is paid once and the promise is exact, so it is the only durability sentence this slice makes.
