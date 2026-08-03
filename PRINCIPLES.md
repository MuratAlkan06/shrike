# Shrike Coding Principles

These rules bind every contributor to this repository — human or agent. They exist because this repo will be audited read-only against every claim in its README: boring, explicit, provable code is the goal. When a rule conflicts with cleverness, the rule wins. When a rule must be broken, the exception is documented in DESIGN.md in the same commit.

## 1. Naming

Names are the first line of documentation. Write for the reader at 2 a.m. holding a stack trace.

- **Packages** — by domain, never by layer cliché: `io.shrike.core.log`, `io.shrike.core.protocol`, `io.shrike.core.net`, `io.shrike.core.group`, `io.shrike.core.retention`, `io.shrike.core.config`. No `util`, `common`, or `misc` packages, ever.
- **Types** — nouns that name the thing: `LogSegment`, `OffsetIndex`, `SegmentedLog`, `RecordFrame`, `FetchWaiter`, `GroupOffsetStore`. Interfaces take the plain name (`Log`); implementations take the descriptive one (`SegmentedLog`). Value types are Java `record`s. Banned suffixes: `Manager`, `Helper`, `Util`, `Utils`, `Impl`, and `Processor` used as a grab-bag.
- **Methods** — verb first, one verb, honest about what happens: `append`, `readFrom`, `rollIfNeeded`, `recover`, `seal`, `lookupPosition`. Predicates read as booleans: `isSealed()`, `hasCapacity(long)`. Conversions are `toX()`. No `get` prefix on anything that does work. No filler verbs (`doProcess`, `handleStuff`, `performWrite`).
- **Variables** — every scalar carries its unit: `maxWaitMs`, `segmentBytes`, `indexIntervalBytes`, `flushIntervalMs`. A bare `timeout`, `size`, or `len` for a united quantity is a defect. Constants are UPPER_SNAKE with unit: `DEFAULT_SEGMENT_BYTES`. Single letters only as tight-loop indices.
- **The offset/position law** — the most important rule in this codebase: `offset` is always a logical record number (long); `position` is always a byte location within a file (long). Never interchange them, never name one as the other, never abbreviate either into ambiguity. A variable named `offset` holding bytes is a bug even when the code happens to work.

## 2. Methods and classes

- One reason to change per class: `LogSegment` knows nothing of sockets; `RequestDecoder` knows nothing of files.
- Methods do one thing. Forty lines is a smell threshold, not dogma — but an extraction must earn a real name.
- No boolean flag parameters. Split the method instead: not `flush(true)` but `flushSync()` / `flushAsync()`.
- Dependencies enter through constructors: `TimeSource`, config objects, the data directory. No static mutable state, no singletons. `System.currentTimeMillis()` / `Instant.now()` appear only inside the `TimeSource` implementation.
- Fields are `final` by default. Mutability is declared, justified, and guarded (§4).
- Nulls die at boundaries: `Objects.requireNonNull(x, "x")` at every public entry point; internals then assume non-null.

## 3. Error handling

- Specific exceptions carrying data: `CorruptRecordException`, `OffsetOutOfRangeException`, `ShrikeIOException`. Every message locates the byte: topic, partition, offset, position, file.
- Fail fast on corruption. Never "repair" silently; recovery truncates the torn tail and says so in a WARN log with the position.
- Never swallow: every catch handles meaningfully, or adds context and rethrows, or does not exist. Empty catch blocks, `catch (Exception)` in core paths, and `e.printStackTrace()` are banned.
- Network input is hostile: range-check every length before allocating; bounds-check every count; a malformed frame yields a defined error code or a closed connection — never an OOM, never an escaped exception.
- The broker refuses bad requests; it does not crash on them. Startup fails only on genuinely unusable state (an unreadable data dir), never on a torn tail.

## 4. Concurrency

- Every mutable field documents its guard: `// guarded by: partitionLock` or `// confined to: shrike-retention`. If the guard cannot be named, the design is not finished.
- Single writer per partition. Readers never mutate.
- Every wait is bounded and loops on its predicate under the same lock the signaler holds. That sentence is the lost-wakeup vaccine; memorize it.
- Every thread is named `shrike-*` (`shrike-acceptor`, `shrike-conn-7`, `shrike-retention`, `shrike-flush`) so a thread dump reads like documentation.
- `volatile` requires a one-line justification comment at the declaration. `synchronized(this)` on objects visible outside their package is banned.
- No sleeps for coordination — production code or tests, no exceptions beyond §6's single sanctioned real-time test.

## 5. I/O and durability

- Every write path can answer: "when is this durable, and who was told?" If the answer is not evident in code plus DESIGN.md, the change is incomplete.
- All full-frame writes go through the single `writeFully` utility, which loops until `!hasRemaining()`. A naked `channel.write(buffer)` for a whole frame is a bug: short writes are legal in NIO.
- ByteBuffer discipline: allocate → fill → flip → drain. Never share a mutable buffer across threads; use `duplicate()`/`slice()` for views; every `flip()`/`clear()`/`compact()` is deliberate, not cargo-culted.
- Channels close via try-with-resources; no manual close chains.
- Every path derives from the injected data directory. Nothing touches the working directory or implicit temp locations.

## 6. Testing

- Test names state behavior, not method names: `truncatesTornTailToLastValidRecordOnRestart`, `redeliversUncommittedRecordsToReplacementMember`.
- Arrange-Act-Assert, separated by blank lines.
- Storage tests run against real files in JUnit-managed temp dirs. No mocked filesystems — the filesystem is the thing under test.
- Process tests (broker + clients) use ProcessBuilder, a ready-file handshake ({port, pid}), bounded awaits, and always destroy the full process tree in cleanup — including on failure.
- Time is injected. Tests assert causality and ordering, never durations. Per timing feature, exactly one real-time test is allowed, and only as an upper bound.
- Sleeps for correctness are banned; use the shared bounded-await helper or the test is wrong.
- Every fixed bug gets a regression test in the same commit, written to fail before the fix.
- Coverage follows risk — framing, recovery, concurrency edges — not a percentage vanity number.

## 7. Honesty and documentation

- Three overclaiming phrases never appear in any tracked file or commit message; `scripts/forbidden_phrases.sh` holds the exact patterns and CI runs it over both. Do not test the gate.
- Scope words are exact: this is a single-node broker with at-least-once delivery. Durability claims are stated per flush mode and nothing stronger.
- A README claim exists only with its evidence: a Claims-table row naming the proving test, merged in the same commit as that test. No evidence, no claim.
- DESIGN.md records every non-obvious decision as: the decision, the strongest alternative rejected, and why — in plain sentences an interviewer can quote.
- Docs ride in the slice: behavior and the docs describing it change in the same diff.

## 8. Change discipline

- Smallest correct diff. No building ahead of the current slice; only contract-listed seams may anticipate the future.
- No drive-by refactors. Adjacent-code improvements are their own commit or they do not happen.
- Conventional commits (`feat(core): ...`, `test(recovery): ...`); every commit compiles and passes tests on its own.
- Git operations go through the sanctioned workflow only: issue → branch → PR → green CI → merge.

## 9. Dependencies

- `shrike-core` depends at runtime on the JDK alone — build-enforced.
- Spring exists only in `shrike-admin`. `shrike-clients` may depend only on core's protocol/codec surface, and that boundary is documented.
- Any new dependency in any module requires a DESIGN.md justification entry in the same commit.

## 10. Enforcement map

| Rule | Enforced by |
|---|---|
| Forbidden phrases (§7) | CI docs-gate grep (files + commit messages) |
| Claims need evidence (§7) | CI claims-table checker |
| Core is JDK-only (§9) | maven-enforcer bannedDependencies |
| Built and tested on Java 21 | toolchains + release=21 + runtime assert + class-file major check |
| writeFully / no naked frame writes (§5) | code review + short-write stub test |
| Everything else | implementation-engineer self-check → verification-lead spot-check → release gate |
