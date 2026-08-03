# Shrike

Shrike is a single-node, log-structured message broker written in Java 21. Producers append records to a segmented commit log on one machine's disk, consumers read them back by offset, and delivery is at-least-once: a record may be redelivered after a failure, and no record is silently dropped.

Version 1.0.0 — a TCP broker with a length-guarded wire protocol, long-polling fetch, and durable group offsets; retention that deletes whole sealed segments by age or by size, a fetch whose records go from the segment file to the socket without being read into memory, and a flush policy that is either per-record or interval, with JMH benchmarks of what the last two cost on one machine; a blocking client library that routes keys to partitions, splits a topic between the members of a consumer group, and commits only after the records have been processed; a read-only HTTP admin facade that reads a live broker over that same protocol; and a container image holding the broker. What a running broker can be told is nineteen environment variables and no more, one for each setting this README names, which [Configuration](#configuration) sets out; the [Non-goals](#non-goals) below are what this build does not do at all.

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
- any admin api that writes: the facade reads a broker and nothing else

## Quickstart

**Build it.** Maven, and a Temurin 21 toolchain registered in `~/.m2/toolchains.xml`: the reactor selects that JDK for `javac` and for every test fork, so what the suite runs on is what a Java 21 user runs.

```
mvn -B clean verify
```

**Run the broker.** `SHRIKE_DATA_DIRECTORY` is the one variable with no default, because a default would be a path this build picked rather than one somebody chose:

```
SHRIKE_DATA_DIRECTORY=/var/lib/shrike java -cp shrike-core/target/classes io.shrike.core.net.BrokerMain
```

It listens on port 9750, binds the loopback interface, and writes `shrike.ready` — the port it bound and its pid — into the data directory. `SHRIKE_PORT`, `SHRIKE_READY_FILE`, and `SHRIKE_BIND_ADDRESS` are the other three variables that name where a broker lives, each of which has a default, and the last of them is the only way this broker comes to listen anywhere but loopback. Every other setting this README names is read from one variable of its own — retention, the flush policy, the sizes, the caps — and a variable nobody sets is a setting left at the default this build has always run. [Configuration](#configuration) is the list of them, with the rule that names them and the default each one runs at.

**Or run it in a container.** The image is the same entry point reading the same variables, with `SHRIKE_BIND_ADDRESS` set to `0.0.0.0` because a published port never lands on a container's loopback:

```
docker build -t shrike:1.0.0 .
docker run -d --name shrike -p 127.0.0.1:9750:9750 -v shrike-data:/var/lib/shrike shrike:1.0.0
```

**The address in front of that port is not decoration.** The unqualified `-p 9750:9750` publishes on every interface the host has, and this broker has no authentication, so that form hands an unauthenticated broker to whatever can reach the machine. It is also not something a host firewall necessarily catches: on Linux, Docker publishes a port by writing DNAT rules that are traversed before the `INPUT` chain, so a firewall written as `INPUT` rules is a firewall the published port goes around. `127.0.0.1:9750:9750` publishes to the host's loopback and nowhere else, which is what the rest of this quickstart connects to.

**Produce and consume.** A published port is an ordinary broker port, so this is the same whether the broker is in a container or not. With `shrike-core` and `shrike-clients` on the classpath — both of which depend on the JDK alone:

```java
ClientConfig config = ClientConfig.defaults("127.0.0.1", 9750);
try (ShrikeTopics topics = ShrikeTopics.open(config)) {
    topics.create("orders", 1);
}
try (ShrikeProducer producer = ShrikeProducer.open(config)) {
    producer.send("orders", 0, "order-1".getBytes(UTF_8), "a record".getBytes(UTF_8));
}
try (ShrikeConsumer consumer = ShrikeConsumer.open(config)) {
    FetchedRecords fetched = consumer.fetch("orders", 0, 0L, 64 * 1024, 0, 0);
    consumer.commitOffset("billing", "orders", 0, fetched.nextOffset(0L));
}
```

**Read it back over HTTP.** The admin facade is not in the image. It runs beside the broker and already points at `127.0.0.1:9750`:

```
java -jar shrike-admin/target/shrike-admin-1.0.0.jar
curl -s localhost:8080/api/v1/topics
curl -s localhost:8080/api/v1/topics/orders
curl -s localhost:8080/api/v1/groups/billing/lag
```

It binds `127.0.0.1:8080`, and what those three answer with is under [Admin endpoints](#admin-endpoints).

## Configuration

**The broker's shipped entry point reads nineteen environment variables and nothing else.** `BrokerMain` builds a `BrokerLaunch` out of the environment — no configuration file, no command line, and an argument is refused rather than silently ignored — and hands the broker the `BrokerConfig` those variables describe. The container image runs that same entry point, so it reads the same nineteen. **One rule names all of them: a setting's variable is `SHRIKE_` followed by the setting's own name in capitals, with each dot an underscore** — `retention.ms` is `SHRIKE_RETENTION_MS`, `flush.interval.bytes` is `SHRIKE_FLUSH_INTERVAL_BYTES`, and the three settings that have no dotted name are spelled the same way from their field names.

| Variable | Default | What it names |
|---|---|---|
| `SHRIKE_DATA_DIRECTORY` | none: it is required | the directory every path this broker writes derives from |
| `SHRIKE_PORT` | `9750` | the TCP port to listen on |
| `SHRIKE_READY_FILE` | `<data dir>/shrike.ready` | the file written once the broker is listening, holding the port it bound and its pid |
| `SHRIKE_BIND_ADDRESS` | the loopback address | the interface to listen on |
| `SHRIKE_RETENTION_MS` | `-1`, which is off | how long a sealed segment is kept after the newest record in it, or `-1` to keep every segment |
| `SHRIKE_RETENTION_BYTES` | `-1`, which is off | how many bytes of log files one partition may hold before its oldest sealed segments are deleted, or `-1` to keep every segment |
| `SHRIKE_FLUSH_MODE` | `interval` | when records reach the device: `per-record` or `interval`, in whatever letters |
| `SHRIKE_FLUSH_INTERVAL_MS` | `100` | how long records may sit unforced in `interval` mode |
| `SHRIKE_FLUSH_INTERVAL_BYTES` | `1048576` (1 MiB) | how many bytes of records may be appended without a force in `interval` mode |
| `SHRIKE_SEGMENT_BYTES` | `134217728` (128 MiB) | the size a segment may reach before the next record starts a new one |
| `SHRIKE_MAX_RECORD_BYTES` | `1048576` (1 MiB) | the most bytes one record may occupy on disk, framing included |
| `SHRIKE_INDEX_INTERVAL_BYTES` | `4096` | how many bytes of appended records go by between two entries of a segment's sparse index |
| `SHRIKE_MAX_FETCH_WAIT_MS` | `30000` | the longest a fetch may be held open, whatever wait it asks for |
| `SHRIKE_MAX_REQUEST_BYTES` | `4194304` (4 MiB) | the largest request frame this broker will read, and so the most one connection can make it hold |
| `SHRIKE_READ_TIMEOUT_MS` | `30000` | the longest one connection may spend idle between requests, or part way through a request frame, before this broker closes it; it never bounds serving one |
| `SHRIKE_FETCH_ZERO_COPY` | `true` | `true` to send a fetch's records out of the segment file, `false` to read them into memory first |
| `SHRIKE_CONNECTION_CAP` | `64` | the most connections served at once |
| `SHRIKE_MAX_TOTAL_PARTITIONS` | `1024` | the most partitions this broker will hold open across every topic |
| `SHRIKE_MAX_TOTAL_GROUPS` | `1024` | the most consumer groups this broker will hold committed offsets for; a commit that would create one past it is refused |

**A value this broker cannot use stops it before it opens a socket, and nothing here ever falls back to a default.** A variable that is unset, or set to nothing at all — which is what `docker run -e SHRIKE_PORT=` passes — is one the default answers for; a variable that says something is either used or refused. The refusal is one sentence naming the variable, quoting what it was given, and saying what was expected, on standard error with exit code 2: `SHRIKE_SEGMENT_BYTES was set to a value this broker cannot use: segmentBytes must not exceed 1073741824, so that every byte position inside a segment fits an index entry's int32 field, but was 2147483647`. That sentence has two halves because the work does: text becomes a number, a spelling, or a `true`/`false` where the environment is read, while the bounds stay on the records below, checked once for every caller they have.

**Every setting above is a field of `LogConfig` or `BrokerConfig`, and building those records in Java is still the other way to set one.** They are a library surface as well as an operator surface: an application that puts `shrike-core` on its classpath and calls `ShrikeBroker.start(config, timeSource)` with a configuration it assembled gets whatever it named, and the quickstart's entry point and the image get what their environment says.

| Setting | Variable | Field | Lives on |
|---|---|---|---|
| `retention.ms` | `SHRIKE_RETENTION_MS` | `retentionMs` | `LogConfig` |
| `retention.bytes` | `SHRIKE_RETENTION_BYTES` | `retentionBytes` | `LogConfig` |
| `flush.mode` | `SHRIKE_FLUSH_MODE` | `flushMode` | `LogConfig` |
| `flush.interval.ms` | `SHRIKE_FLUSH_INTERVAL_MS` | `flushIntervalMs` | `LogConfig` |
| `flush.interval.bytes` | `SHRIKE_FLUSH_INTERVAL_BYTES` | `flushIntervalBytes` | `LogConfig` |
| `segment.bytes` | `SHRIKE_SEGMENT_BYTES` | `segmentBytes` | `LogConfig` |
| `max.record.bytes` | `SHRIKE_MAX_RECORD_BYTES` | `maxRecordBytes` | `LogConfig` |
| `index.interval.bytes` | `SHRIKE_INDEX_INTERVAL_BYTES` | `indexIntervalBytes` | `LogConfig` |
| `max.fetch.wait.ms` | `SHRIKE_MAX_FETCH_WAIT_MS` | `maxFetchWaitMs` | `BrokerConfig` |
| `max.request.bytes` | `SHRIKE_MAX_REQUEST_BYTES` | `maxRequestBytes` | `BrokerConfig` |
| `read.timeout.ms` | `SHRIKE_READ_TIMEOUT_MS` | `readTimeoutMs` | `BrokerConfig` |
| `fetch.zero.copy` | `SHRIKE_FETCH_ZERO_COPY` | `zeroCopyFetch` | `BrokerConfig` |

The dotted names in the left-hand column are what the javadoc on those two records calls each field, and what the prose below calls them; the middle column is each of those names under the rule above. Three more numbers sit on `BrokerConfig` beside them with no dotted name of their own, and the same rule reaches them from their fields: `connectionCap`, 64, from `SHRIKE_CONNECTION_CAP`, `maxTotalPartitions`, 1024, from `SHRIKE_MAX_TOTAL_PARTITIONS`, and `maxTotalGroups`, 1024, from `SHRIKE_MAX_TOTAL_GROUPS`.

`shrike-admin` has a settable surface of its own, because it is a Spring application: `server.address`, `server.port`, `shrike.broker.host`, and `shrike.broker.port` are set the way any Spring Boot property is, and they are described under [Admin endpoints](#admin-endpoints).

## Architecture

**Three modules, and nothing points back up the chain.** `shrike-core` is the broker itself — the log, the protocol, the group offset store, and the sockets — and it depends on the JDK alone, which `maven-enforcer` fails the build over rather than leaving to a reviewer's memory. `shrike-clients` is the blocking client library; it declares one dependency, `shrike-core`, for the protocol and codec types it names, so it too runs on the JDK alone, and it reaches a broker over TCP and no other way. `shrike-admin` is the read-only HTTP facade and the only module in this build that names Spring: it is a client of a broker that is up, configured with a host and a port and no path at all, so it opens none of the broker's files either.

**Storage is one append-only log per partition.** A partition's records live under `<data dir>/<topic>-<partition>/`, split into segments named for the offset they start at, each with a sparse `.index` beside it holding one entry per `index.interval.bytes` of appended records. A segment rolls when the next record would push it past `segment.bytes`, and it is forced before it is sealed, which is why recovery only ever walks the last one. Appends to a partition go through that partition's own lock — one writer at a time — and readers mutate nothing.

**The protocol is framed, and every length is checked before a byte is allocated.** A request names an api key and a version; an answer carries the correlation id that says which request it belongs to, and an error code. There are six keys, and [Protocol](#protocol) is the table of them.

**Consumer groups are arithmetic, and the arithmetic runs on the client.** A key picks its partition in `shrike-clients`, a member reads the partitions its index owns, and the broker stores nothing about a group but the offsets it committed — for as many groups as its budget allows, since a commit is what creates one and nothing removes it. Delivery is at-least-once: duplicates are possible after a failure, and no acknowledged record is dropped.

**Lag is computed in the admin facade, not in the broker.** The broker answers what a group committed and what a partition holds; the subtraction happens in `shrike-admin`, which is the only number it publishes that the broker did not say.

Every decision behind those sentences — with the strongest alternative that was rejected, and why — is in DESIGN.md.

## Protocol

One TCP connection carries requests and the answers to them. Every field is big-endian, and each frame begins with a length that counts every byte after itself:

```
request:  length:int32 | apiKey:int16 | apiVersion:int16 | correlationId:int32 | body
response: length:int32 | correlationId:int32 | errorCode:int16 | body
```

The response repeats neither the api key nor the version. The correlation id is the client's own number, echoed back untouched, and it is what says which request an answer belongs to — which is what lets a fetch held open for a while be answered after a produce that arrived later. A version belongs to an api key rather than to the connection, and this build speaks version 0 of six keys:

| Key | Api | Request body | Answer |
|---|---|---|---|
| 0 | produce | topic, partition, records | the offset the first record was appended at |
| 1 | fetch | topic, partition, fetchOffset, maxBytes, maxWaitMs, minBytes | the partition's high-water mark and a block of record frames |
| 2 | commit offset | groupId, topic, partition, offset | an empty body |
| 3 | create topic | name, partitionCount | an empty body |
| 4 | describe topics | the topics to describe, or none of them to mean all of them | one entry per topic: its name, and per partition its log start offset, high-water mark, segment count, and bytes on disk |
| 5 | describe group | groupId | one entry per partition that group has committed an offset for: topic, partition, and the next offset to read |

No key past 5 exists. A request naming one is refused as an invalid request, exactly like an api key that never will.

**Describing reads and changes nothing, and the two describes disagree about what "not here" means.** A describe that names a topic this broker does not hold is refused with `unknown topic or partition`, because a caller that spelled a name out is owed the news that it is not here. A describe of a group this broker has never heard of is answered with `none` and no entries: there is no create-group api and no group registry, so a commit is what brings a group into being — which is why how many groups there may be is budgeted on the commit path, below — and a group that has committed nothing and a group that does not exist are one state that no error code could honestly tell apart. A describe that names no topic at all is asking about every topic there is, and a broker holding none answers that with no topics rather than a failure. Topic names come back folded — `orders` and `Orders` are one topic, as below — so the topic in a describe of a group and the topic in a describe of topics are the same string.

| Code | Name | Means |
|---|---|---|
| 0 | none | the body that follows is the answer |
| 1 | unknown topic or partition | no such topic, or a partition number outside the count it was created with |
| 2 | offset out of range | the offset is outside what that partition can serve; the body is the int64 offset it can still be read from |
| 3 | corrupt record | a stored frame no longer matches its checksum |
| 4 | frame too large | a produce record is larger than `max.record.bytes`; none of that request's records were stored |
| 5 | invalid request | the bytes parsed as an envelope, but their contents break a rule of the protocol |
| 6 | unsupported version | the api key exists, but not at the version that was asked for |
| 7 | topic already exists | a topic of that name is already there, whatever partition count was asked for |
| 99 | internal | the broker failed for a reason of its own, which it does not describe to the caller |

**An error response carries an empty body, with one exception.** For eight of the nine codes the code is the whole answer, so the same six bytes answer every api, and a body behind one of them is a frame a client refuses rather than reads. The exception is `offset out of range`, which carries eight bytes: the offset that partition can still be read from. It exists because a consumer whose committed offset has been deleted by retention would otherwise have to choose between re-reading everything that survived and skipping records that are still there, and neither is a choice it can make from a code alone. The rule is exact in both directions — a code 2 without those eight bytes is refused as surely as a body behind any other code — and the number is nothing a legal fetch would not have been told anyway. No api key was added for it.

**Long-poll fetch.** A fetch that finds fewer than `minBytes` of records readable is held open for up to `maxWaitMs` and answered the moment an append to that partition makes enough available; a wait that runs out is answered with whatever is there — usually nothing — and the code `none`, because "there is nothing new yet" is an answer rather than a failure. A `maxWaitMs` or `minBytes` of 0 is answered on the first pass without waiting at all.

**How a fetch's records reach the socket.** The records block of a fetch response is a byte range of a segment file copied out verbatim, so by default the broker sends it without reading it into memory at all: it writes the response header — whose length field already counts every record byte to come — and then transfers the range straight from the file to the connection with `FileChannel.transferTo`. A `BrokerConfig` whose `fetch.zero.copy` is off selects the other path, which reads the range into a buffer and writes the response as one frame; that is what this broker did before the setting existed, and it is both the way back if the transfer ever misbehaves somewhere and the A/B the fetch benchmark below measures across. The two are the same bytes on the wire, envelope included, and a client cannot tell which answered it. Records that have been appended and not yet written out to the device are served the same way, because the transfer reads the same file the append wrote; what bounds a range is the high-water mark, not what has been flushed.

**The frame guard.** A reader takes the four length bytes first and allocates nothing until it believes them. The broker believes a request length inside `[8, max.request.bytes]` — 4 MiB by default — and a length outside it ends the connection with no reply, because the correlation id a reply would be addressed to lives after the length and is worth no more than the length is. The client applies the same shape to responses, bounded by the broker's serving cap plus a kibibyte of envelope headroom.

**Durability, per flush mode.** `flush.mode` decides when a record reaches the device, and it is the only thing that decides it. It is a `LogConfig` field, per [Configuration](#configuration), and it defaults to `interval` — which is therefore what the entry point and the image run.

- `per-record` — a produce is acknowledged only after `force()`, so an acknowledged record survives an operating-system or power failure. The cost is one fsync per record, paid on the thread the producer is waiting on and under the partition's own lock, so a partition answers one produce at a time at the speed of its device.
- `interval` — acknowledged records survive a process crash (they are in the page cache), but up to one flush interval of acknowledged records can be lost on an operating-system or power failure, after which recovery truncates the torn tail. Whichever of `flush.interval.ms` (100 by default) and `flush.interval.bytes` (1 MiB by default) comes first is what forces: the append that crosses the byte bound forces where it stands, because that is the only moment the bound can be seen being crossed, and a thread named `shrike-flush` asks each partition once per interval about the other. Every force is `force(true)`, so the file's new length is on the device beside the bytes it reaches.

**Durability and delivery semantics are separate axes, and neither implies the other.** A flush mode says what a failure of the machine may cost. At-least-once delivery, below, says what a failure of a consumer may cost. Delivery is at-least-once in both modes and nothing about `flush.mode` changes it, exactly as nothing about at-least-once makes a record survive a power cut.

A commit of a group offset is neither of the two and is stronger than both: it is acknowledged only after that group's file has been written, forced, moved into place atomically, and its directory forced.

**Names are case-insensitive.** A topic name and a group id are folded before they are used as an identity, because each one becomes a path — `<topic>-<partition>/` for a partition, `groups/<groupId>.offsets` for a group — and a path does not tell two casings apart on APFS, which is what macOS uses by default. So `orders` and `Orders` are one topic, and creating the second is answered `topic already exists`; a data directory that somehow lists both fails the start rather than opening two topics over one directory. A group file already sitting there under an unfolded name is renamed onto the folded one as the broker starts, durably, so that one ordinary commit cannot leave a directory the next start refuses. The fold settles case-insensitivity of the APFS kind and nothing beyond it: Windows path identity folds reserved device names such as `nul` and `com1` too, and this build does not run there anyway, because forcing a directory means opening it for reading and Windows refuses that.

**What the broker will not be asked for.** A fetch's `maxWaitMs` is clamped to `max.fetch.wait.ms` — 30 seconds by default — and its `minBytes` to the most bytes that fetch could ever be served, so neither field can park a connection on a promise that cannot be kept. A create that would push the broker past 1024 open partitions across every topic is refused as an invalid request, because each partition holds a directory and two open files for as long as the broker runs. A commit that would create a consumer group past 1024 of them is refused the same way and for the same shape of reason: a commit is what creates a group, and the group it creates is a file and a resident entry that nothing removes. Both numbers are settings — `SHRIKE_MAX_TOTAL_PARTITIONS` and `SHRIKE_MAX_TOTAL_GROUPS` — and both refuse only what is new.

**Where it listens, and what it does not check.** The broker binds the loopback interface unless the process that starts it names another address. `ShrikeBroker.start(config, clock)` binds loopback; a second `start` taking a bind address binds the one it is handed, and that overload is the only way this broker comes to listen anywhere else. `BrokerMain`, the entry point, hands it whatever `SHRIKE_BIND_ADDRESS` holds, and the default there is the loopback address as well — so listening past loopback takes an environment variable somebody set on purpose. The container image in this repository is what sets it, to `0.0.0.0` inside a network namespace of its own, because a published port is the only way into a container. This build has no authentication, no authorization, and no transport security, so a broker listening past loopback is a broker anything that can reach that port may append to — and read back, and enumerate. Keys 4 and 5 are unauthenticated too: a describe naming no topic at all lists every topic this broker holds, and per partition its log start offset, its high-water mark, its segment count, and its bytes on disk, while a describe of a group names the offset that group has reached in each partition it has committed. So the exposure is not only writes. Anyone who can reach the port learns what topics exist, how much is in them, how much of it each consumer group has got through, and — with a fetch — every record. That decision is the operator's to make in so many words, and nothing here makes it for them.

**How long a connection may spend reading, and how long it may sit idle, is now a number.** A connection may spend at most `read.timeout.ms` — 30 seconds by default, from `SHRIKE_READ_TIMEOUT_MS` — in one *read phase*, and a read phase is exactly two things: the time it sits idle between requests, and the time it spends part way through a request frame. A thread named `shrike-conn-reaper` asks once per bound and closes the connections that have crossed it, which is what brings their threads out of their blocking reads and gives their places under the connection cap back. `SO_TIMEOUT` is not what does it: that bounds reads on a socket's streams and not on the `SocketChannel` the broker reads through. **The bound never covers serving a request.** A long-poll fetch held open for `max.fetch.wait.ms` is the broker waiting to write rather than a client failing to speak, and it is not closed for it — a fetch waiting the full 30 seconds under a read timeout of one second is served its records, which is what `BrokerReadTimeoutTest#keepsALongPollFetchThatWaitsFarPastTheReadTimeoutAndServesItItsRecords` is there to keep true. **And it does not cover the write side either, which nothing here bounds.** A peer that asks for a large fetch and then stops reading the answer leaves this broker blocked in its own write to that socket, and a connection blocked writing is serving rather than reading, so the reaper leaves it alone: it holds its place under the connection cap until the socket breaks, the peer reads, or the broker stops. That is the same slot a stalled reader would have held, taken by the other half of the conversation, and closing it is a different mechanism from this one — a bound on a blocking write rather than on a read phase — which this build does not have. Bounding it is tracked in #33. So what the number covers is reading and idling, and the sentence stops there. There is no value that turns the bound off, and the largest one it takes is a ceiling rather than an off switch: `read.timeout.ms` is a whole number of milliseconds that must be at least 1, and about 24.8 days — `Integer.MAX_VALUE` of them — is as long as one read phase can be allowed to run. What is still open under #9 is the other half of what the paragraph before this one leaves unchecked: this build has no authentication.

## Clients

`shrike-clients` is a blocking client library over that protocol. `ShrikeProducer.send` returns the offset the broker appended a record at, `ShrikeConsumer.fetch` returns the records and the partition's high-water mark, `ShrikeConsumer.commitOffset` stores the offset a group should read next, and `ShrikeTopics.create` creates a topic. Reading back what the broker holds is `ShrikeTopics.describe` and `ShrikeTopics.describeAll` for topics and `ShrikeGroups.describe` for one consumer group — `ShrikeGroups` is a client of its own for the same reason `ShrikeTopics` is, and neither of the three changes anything. There are no background threads, no buffering, and no retries: one connection carries one call at a time, every wait is bounded, and a failure is a typed exception carrying either the broker's error code or the bound that was crossed. It depends on the protocol and codec types of `shrike-core` and on nothing else. Because there are no retries, a caller that leaves a connection idle for longer than the broker's `read.timeout.ms` finds it closed and gets a `BrokerIOException` on its next call: a connection this library holds is one the caller is expected to use or to close. A `ShrikeConsumer` looping on `fetch` is never idle for long — its wait is the broker serving it, not the broker waiting on it.

**Which partition a record goes to is decided here, not on the broker.** A produce request has always carried an explicit partition, and a caller that names one is obeyed whatever the record's key is. A caller that would rather not choose hands `ShrikeProducer.send` a `PartitionRouter`, which sends a record with a key to `Math.floorMod((int) crc32c(key), partitionCount)` — the same key to the same partition on every machine and in every JVM, which is what keeps one key's records in one partition and therefore in order — and a record with no key to each partition in turn. CRC32C is reused rather than a second hash added: every record frame already carries one.

## Consumer groups and at-least-once delivery

**Delivery is at-least-once: a record may be delivered more than once, and no acknowledged record is dropped.** A member of a consumer group is handed its records by `ShrikeConsumer.processThenCommit`, which calls the caller's handler first and commits the offset to read next only after that handler has returned. A process that dies between doing the work and committing it does that work again when it is replaced — that is the trade, stated as a cost rather than a footnote: duplicates are possible, records are not lost. An effect that must not repeat has to be made repeatable by whoever writes the handler, and the offsets it is handed are what that can be keyed on. `ConsumerGroupRedeliveryIT#redeliversTheRecordsAKilledMemberProcessedButNeverCommittedToTheMemberThatReplacesIt` is the proof: a member is killed with `destroyForcibly` while it is holding records it has processed and not committed, and every one of those records is delivered again to the process started in its place.

**Assignment is arithmetic, and there is no dynamic rebalancing** — it is a non-goal of this build, listed above and restated here because this is the section where it matters. A member is configured with a group id, its own index, and how many members the group has, and it reads the partitions where `partition % memberCount == memberIndex`. The broker stores committed offsets keyed by group, topic, and partition and nothing else: no members, no sessions, no heartbeats. A member that dies is replaced by an operator starting a process with the same index, which resumes from the last offset that index committed; until then, its partitions are read by nobody. Two live processes sharing one index is the operator's mistake to avoid, and nothing here can detect it — what it costs is redelivery, which at-least-once delivery already allows, rather than a record nobody reads.

**How many groups a broker will hold is budgeted, and the budget stops new groups only.** A commit naming a group id this broker does not hold creates that group: a `groups/<id>.offsets` file, and an entry in memory that is read back at every start from then on. Nothing deletes a group, so without a number the cost of the whole directory is decided by whoever can open a socket. `maxTotalGroups` — `SHRIKE_MAX_TOTAL_GROUPS`, 1024 by default — is that number, and a commit that would create a group past it is refused as an invalid request naming the group, the count held, and the budget, with nothing written for it. A commit by a group the broker already holds is never refused for the budget, however full it is: what is bounded is how many groups may come into being, not how often they may commit. A data directory that already holds more group files than the budget — which is what lowering the setting looks like — is opened whole: every group in it is loaded and goes on committing, a WARN says how many were found and what the budget is, and it is the next new group that is refused until the number is raised. Committed offsets are acknowledged data, and a setting somebody lowered is not a reason to refuse to start over them.

## Recovery

The recovery promise is tail-only. Opening a partition walks the last segment frame by frame — checking each frame's length, CRC32C, magic, and the offset it carries — and cuts the file off after the last whole frame, logging a WARN that names the byte position it truncated at. The records lost that way are records no producer was ever told about. Startup does not fail on a torn tail, and running it twice with no writes in between changes no file and no offset.

Earlier segments are not walked, because a segment is forced before it is sealed. Damage inside a sealed segment is therefore left exactly where it is: startup succeeds, reading the damaged record fails with a corrupt-record error naming its topic, partition, offset, byte position, and file, and the records before and after it still read.

## Retention

**Retention deletes acknowledged records, on purpose, whenever a log is opened under a policy that asks for it.** That is what it is for, and it is worth saying in those words rather than in softer ones. Two `LogConfig` fields decide it, per partition log: `retention.ms` deletes a sealed segment once every record in it is that many milliseconds old, and `retention.bytes` deletes the oldest sealed segments until the partition's log files add up to no more than that many bytes. **Both default to −1, which is off, and off is what the entry point and the image run unless somebody says otherwise: a broker started with neither of them set keeps every record it has ever stored.** Asking for retention is `SHRIKE_RETENTION_MS` or `SHRIKE_RETENTION_BYTES` on the process, per [Configuration](#configuration), or an application that builds its own `LogConfig`.

A segment's age is the largest record timestamp inside it — the timestamp the broker stamped when it appended the newest record it holds — and never a file's modification time, which a copy or a restore resets while the records do not change. Because the age comes from the newest record, no record is ever deleted inside its own window. Whole sealed segments only: the segment still taking appends is never deleted, however old it is and however large the partition has grown, so a partition can sit above `retention.bytes` by the size of that one segment.

A deletion is announced rather than silent. It happens only where a `LogConfig` asked for it, on a thread named `shrike-retention` that sweeps once a minute, and each segment that goes is an INFO line naming the topic, the partition, the base offset, the bytes, the reason, and the offset the partition starts at afterwards. Deleting the oldest segments moves that start offset forward, and a fetch below it is answered `offset out of range` **carrying the new start offset**, so a consumer that was down long enough to fall behind is told where it may resume instead of guessing.

This is a different thing from the delivery semantics above, and neither weakens the other. At-least-once is about what a failure may do to a record — redeliver it, never silently drop it. Retention is about what a log's configuration has asked the broker to stop storing after a stated age or size, which is neither a failure nor silent. With retention off, which is the default, the two never meet at all.

## Benchmarks

Two things this slice added are worth a number rather than an adjective: what `flush.mode` costs an append, and what sending a fetch's records out of the segment file costs against reading them into memory first. Both are JMH benchmarks under `shrike-core/src/test/java/io/shrike/core/bench/`, they are compiled by every build and run by none of it, and one command runs them:

```
mvn -pl shrike-core -P bench test-compile exec:exec
```

**Everything below is a measurement of one machine at one commit.** The harness is commit `9261b81`, the machine is an Apple M4 Pro with 14 cores and 48 GiB of memory running macOS 15.6.1, and the JVM is the one the toolchain selects:

```
openjdk version "21.0.7" 2025-04-15 LTS
OpenJDK Runtime Environment Temurin-21.0.7+6 (build 21.0.7+6-LTS)
OpenJDK 64-Bit Server VM Temurin-21.0.7+6 (build 21.0.7+6-LTS, mixed mode, sharing)
```

Every benchmark ran on one thread, in 2 forks of 3 one-second warmup iterations and 5 one-second measurement iterations, with no JVM arguments of its own. The suite takes about two minutes. The raw JMH output is committed exactly as it was written, and it is what the rows below are read from: [`docs/bench/slice-5-flush-and-fetch.json`](docs/bench/slice-5-flush-and-fetch.json).

**Why that commit and not the one the run was started from.** Slice branches are squash-merged into `main` in this repository, so the branch commit the benchmark process was actually launched at does not exist in this history under its own identity, and a fresh clone cannot resolve it. `9261b81` is the squash that carries the same harness and the same committed JSON into `main`, and it is the commit named above for that reason. Its benchmark sources differ from the ones the run used by one javadoc paragraph and by nothing a measurement passes through.

**What a flush mode costs an append.** One `SegmentedLog.append` of a 162-byte frame — a 128-byte value and no key — on a log opened with the defaults apart from the mode. The ± is JMH's own 99.9% confidence interval over the ten measured iterations.

| `flush.mode` | Appends per second | p50, closed-loop service time | p99, closed-loop service time | Samples timed |
|---|---|---|---|---|
| `per-record` | 222.1 ± 41.5 | 4.00 ms | 5.97 ms | 2 492 |
| `interval`, 100 ms / 1 MiB | 485 739 ± 76 685 | 0.96 µs | 3.79 µs | 333 677 |

**Both percentile columns are closed-loop service time, and that phrase is load-bearing.** The harness issues the next append only once the previous one has returned, so nothing ever queues behind anything: these are the times the log took, not the times a client would have waited under an arrival rate the broker does not control. A percentile measured this way understates latency under open load, which is why it is written into the column heading rather than left to be assumed.

The confidence interval on the second row is wide, and that is the measurement rather than noise around it: in that mode the append that crosses `flush.interval.bytes` forces where it stands, and a roll forces and seals a whole 128 MiB segment, so a run that is mostly page-cache writes has rare long appends in it. They are in the same run's tail: p99.9 is 26 µs, p99.99 is 4.3 ms, and the slowest of the 333 677 timed samples was 8.3 ms.

The interval on the first row is wide for a different reason, and it is named here rather than smoothed away. The two forks of that trial did not agree: the five measured iterations of the first sustained between 245 and 249 appends a second, and the five of the second sustained between 181 and 213. What a force costs is the device's answer rather than the JVM's, and it changed between the two forks, so 222.1 is a mean across a machine that moved underneath the run and not a rate the run held throughout. The sample arm of the same benchmark is a separate trial and it did not see it: every one of its ten iterations, across both of its forks, averaged between 244.5 and 249.4 appends a second. So the slow fork was something the machine did for a few seconds and not something the mode does, and the honest reading of the first row is a rate that sat near 247 with an excursion in it. The number is published as it was measured, spread and all: a tighter one would have had to come from choosing between runs.

**A sample is not an append.** JMH's `SampleTime` mode times some invocations rather than all of them, and it thins that subset as a run goes on, so the `Samples timed` column counts the appends that were timed and not the appends that were made. The 333 677 on the `interval` row were drawn from several million appends; the 2 492 on the `per-record` row are very nearly every append that run made. Each percentile stands on the count printed beside it.

**The two rows do not write the same amount of data, and only one of them rolls a segment.** At the rates in the first column, a `per-record` trial appends about 1 800 records over its three warmup and five measured seconds — some 280 KiB of frames — and never comes near the 128 MiB `segment.bytes`, so it rolls no segment at all. An `interval` trial appends about 3.9 million records in the same eight seconds, some 600 MiB, and rolls and seals a segment about every 128 MiB while it does. The roll, and the force that seals a segment, are charged to the `interval` row alone: the `per-record` row contains no roll at all.

That tail is a property of a trial of this length rather than a constant of the mode. The percentiles above come from a run that grew one log by hundreds of mebibytes across its warmup and five measured iterations, which is four or five seals and that much writeback for the operating system to do underneath. A shorter trial meets fewer of both and a longer one meets more, so 8.3 ms is what this trial found rather than a number to plan against.

**What serving a fetch out of the file costs.** One fetch of the same 1 MiB range — 991 frames, 1 048 478 bytes — of the same pre-built log, sent into a connected pair of loopback `SocketChannel`s with a thread reading the other end and discarding it. The two rows are the two calls `fetch.zero.copy` selects between and nothing else.

| Path | Fetches per second | Bytes per second (derived) |
|---|---|---|
| `FileChannel.transferTo` out of the segment file | 2 567.3 ± 62.2 | 2 567 MiB/s |
| read into a buffer, then one `writeFully` | 2 253.5 ± 36.9 | 2 253 MiB/s |

The `Bytes per second` column is derived rather than measured: it is the row beside it multiplied by the 1 048 478-byte range, and it carries that row's ± with it.

Both rows include loopback TCP and the thread draining it. That is deliberate: a transfer differs from a read-then-write only when the destination is a socket, so a sink that was not one would have compared the buffered path with itself. Neither row is a measurement of a disk, of a network, or of any machine other than this one.

The transfer row also opens a file descriptor on the segment file for every fetch and closes it once the range has been sent, because that is what `SegmentedLog.openRange` does inside the broker; the buffered row reads through the channel the segment already holds. That open and close is what lets a range still be sent after retention has deleted the segment it is in, and it is a cost the transfer path pays per fetch, so it sits inside the transfer number rather than beside it.

**What the shared sink costs is a third benchmark rather than a sentence.** `FetchPathBenchmark.writeRangeToTheSinkAlone` writes the same 1 048 478 bytes into the same loopback pair with no log behind it — no range located, no file read, no descriptor opened — and it ran at 14 637.6 ± 213.2 writes a second. It is in the same committed JSON as the two rows above and it is deliberately not in the table beside them: it is not a path a fetch can take, it is the floor under both of the paths that are.

Read as times rather than rates, that is 68.3 µs to put the mebibyte on the socket against 389.5 µs and 443.8 µs to serve it, so neither row is bound by the socket — which is the question the sink was measured to answer. It is not free either: it is 17.5% of the transfer row's time and 15.4% of the buffered row's, and both rows pay it. The transfer row served 13.9% more fetches a second than the buffered row on this machine, and taking the same 68.3 µs off both leaves 16.9%, so the difference in the table understates the difference between the two paths rather than flattering it. Every figure in this paragraph is a reciprocal or a ratio of three scores in that one file and nothing else.

**A footnote about what `per-record` was measured under, and it is not a small one.** On macOS, `FileChannel.force()` issues `fsync(2)` rather than `fcntl(F_FULLFSYNC)` (JDK-8080589), so it does not push the drive's cache out to the media. The `per-record` numbers above are therefore weaker-durability numbers than the same benchmark on Linux would produce, and they are measurements of this machine rather than claims about what any other machine does. That cuts one way and the direction is the point: a force that stops at the drive's cache is cheaper than one that reaches the media, so `per-record` here is the optimistic arm, and the distance between the two flush modes above is a lower bound on what a media-durable force would open rather than a flattering one.

And `per-record` is per record, not per produce request. The row above is one `append` and therefore one force; a produce carrying a batch of N records under this mode pays N of them, so a client batching to amortize the network amortizes nothing here.

## Admin endpoints

`shrike-admin` answers three GETs in JSON by asking a running broker the same questions any other client may ask it. It reads and never writes, it opens no file the broker owns, and it keeps nothing between requests: each call opens a connection, gets its answer, and closes it. It serves `127.0.0.1:8080` and reads a broker at `127.0.0.1:9750` unless `server.address`, `server.port`, `shrike.broker.host`, and `shrike.broker.port` say otherwise.

**What it does not check.** This facade has no authentication and no transport security — TLS or auth is as much a non-goal here as it is on the broker's own port — so anything that can reach its port can list every topic the broker holds, read each partition's offsets, segment count, and bytes, and ask how far any group it names has got through the partitions that group has committed. The `127.0.0.1` above is the default and the whole of what keeps that on one machine: `server.address` is the only way this facade comes to listen anywhere else, widening it is the same deliberate act as widening the broker's own bind address, and the host it is widened onto is one trusted with the broker it reads.

The bodies below came off a broker holding two topics — `orders` with three partitions and `shipments` with two — carrying five records in `orders-0`, two in `orders-1`, three in `shipments-0`, and a group `watchers` that had committed 2, 2, and 1 against them. `AdminFacadeIT` pins the same three bodies whole, against a broker in a process of its own.

`GET /api/v1/topics` — every topic the broker holds, in the order the broker lists them. A broker holding none answers `{"topics":[]}` with status 200, because an empty broker is a truth rather than a failure.

```json
{"topics":[{"name":"orders","partitionCount":3},{"name":"shipments","partitionCount":2}]}
```

`GET /api/v1/topics/{topic}` — one topic, a line per partition. `logStartOffset` and `highWaterMark` count records and `bytes` counts bytes: what the partition can serve is the half-open range `[logStartOffset, highWaterMark)`, so a partition holding nothing reports the same number twice, and `bytes` is that partition's log files and index files added together. The name that comes back is the folded one that is a topic's identity, whatever casing the path was spelled in.

```json
{"name":"orders","partitions":[{"partition":0,"logStartOffset":0,"highWaterMark":5,"segmentCount":1,"bytes":220},{"partition":1,"logStartOffset":0,"highWaterMark":2,"segmentCount":1,"bytes":88},{"partition":2,"logStartOffset":0,"highWaterMark":0,"segmentCount":1,"bytes":0}]}
```

`GET /api/v1/groups/{group}/lag` — one row per partition that group has committed an offset for, topic and then partition. `lag` is `highWaterMark - committedOffset`, and it is subtracted here rather than by the broker. `committedOffset` is the next offset the group should read, so a group that has read a partition to its end has a lag of 0 rather than 1. The group is asked about before the topics are, which is what keeps a lag from coming out negative; one that did anyway would be refused rather than published.

```json
{"group":"watchers","partitions":[{"topic":"orders","partition":0,"committedOffset":2,"highWaterMark":5,"lag":3},{"topic":"orders","partition":1,"committedOffset":2,"highWaterMark":2,"lag":0},{"topic":"shipments","partition":0,"committedOffset":1,"highWaterMark":3,"lag":2}]}
```

**Every failure this facade answers is one shape:** `{"error":"<one sentence>"}`, and nothing else — `/error` included, and so are the `/WEB-INF` and `/META-INF` prefixes Tomcat refuses inside the container, both of which the framework would otherwise answer in a shape of its own carrying a timestamp and the caller's path. No stack trace, no exception class name, and no path on anybody's disk reaches a caller; what actually failed is logged by the facade, where an operator can read it — with its cause when the failure belongs to the facade or to the broker, and as one line without one when it belongs to the caller, because a log line a stranger can ask for is a log line a stranger can ask for a million of.

A request Tomcat refuses **before** the servlet stack sees it — a broken percent-escape in the path, a request line longer than the header buffer — never reaches any of that. Those are answered with the status code and an empty body, never a page.

| Status | When | The sentence |
|---|---|---|
| 400 | a topic name or group id the protocol will not carry, refused before anything is asked of the broker | `topic must be 1 to 200 characters of [A-Za-z0-9._-] and neither "." nor "..", but was "orders!"` |
| 404 | a topic the broker does not hold | `no such topic` |
| 404 | a group that has committed nothing, which is also what a group this broker has never heard of looks like | `group has no committed offsets: nobody` |
| 404 | a path this facade does not serve | `no such endpoint` |
| 405 | a method it does not answer, every endpoint here being a read | `this facade answers GET only` |
| 502 | something answered on the broker's port with bytes this client will not believe | `broker answer could not be read` |
| 503 | a broker that cannot be reached, or does not answer in time | `broker unreachable` |
| 4xx | any other refusal the container settled before a handler was asked | `request refused` |
| 500 | anything else, which is a bug in the facade | `internal error` |

The 400 sentence names the field the name arrived in — `topic` on a topic path, `groupId` on a group path — and quotes the caller's own input back, cut down when it is long. That quotation is the only detail any error body here carries, and it is the caller's, not the broker's.

## The container image

The image holds a Temurin 21 JRE and one jar, runs as a user that is not root, and keeps everything under `/var/lib/shrike`, which is where its volume goes. The two commands that build it and start it are in the [quickstart](#quickstart) above.

The image sets `SHRIKE_BIND_ADDRESS=0.0.0.0`, because publishing a port maps a host port onto the container's own interface and never onto its loopback. The bare broker's default is the loopback address and stays that way: the image opts in, inside a network namespace of its own, and the port it can be reached on is the one its operator published. `SHRIKE_DATA_DIRECTORY` and `SHRIKE_PORT` are the other two the image sets, `SHRIKE_READY_FILE` is left at its default inside the volume, and `docker run -e` is how any of them — or any other variable in [Configuration](#configuration), none of which this image sets — is given a value. `docker stop` is a `SIGTERM`, which the broker answers by closing its logs. Its `HEALTHCHECK` opens a TCP connection to `SHRIKE_PORT` and closes it — no request, no api key, nothing appended — so `docker ps` says healthy when something is listening and says nothing about what it would answer.

That published port is an ordinary broker port, which the client code in the quickstart talks to unchanged. The admin facade is not in the image: it is an HTTP server of its own, and it runs beside the container.

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
| A key routes to the same partition in every JVM and on every machine, for a table of frozen keys over two partition counts | `PartitionRouterTest#routesKnownKeysToTheFrozenPartitionsOnEveryJvm` | 4 |
| A record with no key takes each partition in turn, coming back round to the first once it has been through them all | `PartitionRouterTest#sendsRecordsWithNoKeyToEveryPartitionInTurn` | 4 |
| The partition the router picked is the partition the broker appended to, and the send answers with the partition its record went to | `ProducerRoutingTest#appendsAKeyedRecordToThePartitionItsKeyRoutesTo` | 4 |
| A caller that names a partition is obeyed whatever its key would have routed to | `ProducerRoutingTest#appendsToThePartitionTheCallerNamedRatherThanTheOneTheKeyRoutesTo` | 4 |
| A group member reads the partitions where partition % memberCount == memberIndex | `GroupAssignmentTest#ownsThePartitionsWhoseNumberFallsToItsMemberIndex` | 4 |
| A group with more members than the topic has partitions gives the extra ones nothing to read | `GroupAssignmentTest#givesAMemberBeyondTheLastPartitionNothingToRead` | 4 |
| A member index outside its own member count is refused where the member is configured, with a message naming both numbers | `GroupAssignmentTest#refusesAMemberIndexThatIsNotOneOfTheMembers` | 4 |
| A handler that throws commits nothing, and the very same records are read again | `CommitAfterProcessingTest#commitsNothingWhenTheHandlerThrowsAndReadsTheSameRecordsAgain` | 4 |
| The offset is committed only after the handler has returned: while it runs, the group's file says nothing about those records | `CommitAfterProcessingTest#commitsTheOffsetToReadNextOnlyOnceTheHandlerHasReturned` | 4 |
| Delivery is at-least-once across a kill: a member killed with SIGKILL while holding records it had processed and not committed has every one of them delivered again to the process started in its place, which begins exactly at the offset the broker had stored, and no produced record is missing from any journal | `ConsumerGroupRedeliveryIT#redeliversTheRecordsAKilledMemberProcessedButNeverCommittedToTheMemberThatReplacesIt` | 4 |
| A broker started without naming retention keeps every record it has ever stored, exactly as it did before retention existed | `SegmentedLogRetentionTest#deletesNothingWhenBothRetentionBoundsAreOff` | 5 |
| A sealed segment whose newest record is older than retention.ms is deleted, and the segment still taking records is not | `SegmentedLogRetentionTest#deletesTheSealedSegmentsWhoseNewestRecordIsOlderThanRetentionMs` | 5 |
| A segment is aged by its newest record, so no record is deleted inside its own retention window | `SegmentedLogRetentionTest#keepsASegmentHoldingOneRecordInsideRetentionMs` | 5 |
| A segment's age survives a restart and comes from the timestamps in its records: setting every file's modification time to now changes nothing about what retention deletes | `SegmentedLogRetentionTest#datesAReopenedSegmentFromItsLastRecordRatherThanFromTheFilesOnDisk` | 5 |
| Segments come off the oldest end until the partition holds no more than retention.bytes, and everything above the new start still reads | `SegmentedLogRetentionTest#deletesTheOldestSegmentsFirstUntilThePartitionIsUnderRetentionBytes` | 5 |
| The segment taking appends is never deleted, whatever the bounds say, and the partition goes on taking records | `SegmentedLogRetentionTest#keepsTheSegmentStillTakingRecordsHoweverOldOrHoweverLargeItIs` | 5 |
| A read below the offset retention moved to is refused with that offset, and reads from it onwards are served | `SegmentedLogRetentionTest#refusesReadsBelowTheStartOffsetRetentionMovedAndServesEverythingAboveIt` | 5 |
| A reader holding a channel open on a segment retention deletes reads it whole: the bytes match what was on disk and still checksum, because unlinking removes a name and not the file behind it | `SegmentedLogRetentionTest#readsASegmentWholeThroughAChannelOpenedBeforeRetentionDeletedIt` | 5 |
| A fetch below the offset retention moved to is answered offset out of range carrying that offset, over the wire | `BrokerRetentionTest#answersAFetchBelowTheOffsetRetentionMovedToWithThatOffset` | 5 |
| Fetching from the offset that refusal named is served the records that survived | `BrokerRetentionTest#servesTheRecordsFromTheOffsetItsRefusalNamed` | 5 |
| An offset-out-of-range response round-trips its log start offset, and one without those eight bytes is refused as a broken frame | `ResponseFrameTest#refusesAnOffsetOutOfRangeResponseWithoutItsLogStartOffset` | 5 |
| The offset reaches a caller of the client library on the typed failure, while every other error code carries nothing | `ClientRoundTripTest#raisesOffsetOutOfRangeCarryingTheOffsetThePartitionCanStillBeReadFrom` | 5 |
| Retention sweeps on a thread named shrike-retention, repeatedly, and closing it ends that thread | `RetentionSweepTest#sweepsOnItsOwnNamedThreadUntilItIsClosed` | 5 |
| What counts as old is measured against the injected clock, so a test advances time instead of waiting | `RetentionSweepTest#sweepsWithTheTimeItReadsFromTheInjectedClock` | 5 |
| A fetch served out of the segment file and the same fetch served out of a buffer are the same bytes on the wire, envelope included, over an empty answer, a whole log, a maxBytes cutting inside a frame, and a frame larger than the maxBytes that asked for it | `BrokerZeroCopyFetchTest#servesByteForByteTheSameResponseWithZeroCopyOnAndOff` | 5 |
| The records sent start at the frame the fetch offset names and end on a whole-frame boundary, with the partition's own high-water mark beside them | `BrokerZeroCopyFetchTest#servesTheRangeThatStartsAtTheFetchOffsetAndEndsOnAWholeFrameBoundary` | 5 |
| A waiting fetch is sent the bytes an append has just landed and nothing has forced, straight out of the file they were appended to | `BrokerZeroCopyFetchTest#servesAWaitingFetchTheAppendedBytesItHasNotForcedYet` | 5 |
| A range opened for sending covers the same bytes the buffered read would have copied, across a segment boundary, a maxBytes cut, and the empty answer at the high-water mark | `SegmentedLogRangeTest#opensTheSameRangeItWouldHaveReadIntoMemory` | 5 |
| A range opened before retention deleted its segment still sends every byte it promised, and those bytes still decode to the records they were | `SegmentedLogRetentionTest#transfersARangeOpenedBeforeRetentionDeletedTheSegmentItIsIn` | 5 |
| A fetch response already part way down a socket is delivered to exactly the length its header promised when retention deletes the segment it is being sent from, and the connection answers the next request afterwards | `BrokerZeroCopyDeletionRaceTest#finishesAFetchAlreadyOnTheWireWhenRetentionDeletesTheSegmentItIsBeingSentFrom` | 5 |
| In per-record mode the force finishes before the append that would acknowledge the record returns, proved by a seam that records the two in the order they happened rather than by timing them | `SegmentedLogFlushTest#forcesEveryRecordBeforeTheAppendThatWouldAcknowledgeItReturns` | 5 |
| In interval mode a log forces once flush.interval.ms has elapsed and not a millisecond before, against a clock a test advances rather than a wait | `SegmentedLogFlushTest#forcesOnlyOnceTheFlushIntervalHasElapsed` | 5 |
| Appending flush.interval.bytes of records forces them at the append that crosses the bound, with the clock never moving | `SegmentedLogFlushTest#forcesOnceFlushIntervalBytesHaveBeenAppendedWithoutTheClockMoving` | 5 |
| Sealing a segment resets what counts as unforced, so the byte bound starts again at the segment a roll began instead of inheriting bytes the seal already put on the device | `SegmentedLogFlushTest#startsTheVolumeBoundAgainAtTheSegmentARollHasSealed` | 5 |
| A torn tail written under interval mode, with nothing forced, is truncated to the last whole record at startup, and the recovered log takes the offset after it | `SegmentedLogFlushTest#truncatesATornTailWrittenUnderTheIntervalFlushMode` | 5 |
| The flush interval runs on a thread named shrike-flush, repeatedly, and closing it ends that thread | `FlushSweepTest#flushesOnItsOwnNamedThreadUntilItIsClosed` | 5 |
| A log opened without naming a flush policy forces on whichever of 100 milliseconds and 1 MiB comes first | `LogConfigTest#defaultsToFlushingOnWhicheverOfOneHundredMillisecondsAndOneMebibyteComesFirst` | 5 |
| On the machine, the JVM, and the harness commit named under Benchmarks, appending a 162-byte frame measured 222.1 ± 41.5 appends a second in `per-record` flush mode, under macOS `fsync(2)` and not `F_FULLFSYNC`, and 485 739 ± 76 685 under `interval` | `docs/bench/slice-5-flush-and-fetch.json` | 5 |
| On that same machine, commit, and JVM, the closed-loop p99 service time of one append measured 5.97 ms under `per-record`, under macOS `fsync(2)` and not `F_FULLFSYNC`, over 2 492 timed samples of about as many appends, and 3.79 µs under `interval` over 333 677 timed samples drawn from several million appends | `docs/bench/slice-5-flush-and-fetch.json` | 5 |
| On that same machine, commit, and JVM, serving one 1 MiB range into a loopback socket measured 2 567.3 ± 62.2 fetches a second through `FileChannel.transferTo` and 2 253.5 ± 36.9 through a buffered read | `docs/bench/slice-5-flush-and-fetch.json` | 5 |
| On that same machine, commit, and JVM, writing the same 1 048 478 bytes into the same loopback socket with no log behind it measured 14 637.6 ± 213.2 writes a second, which is 17.5% of the transfer path's time per fetch and 15.4% of the buffered path's | `docs/bench/slice-5-flush-and-fetch.json` | 5 |
| A describe that names a topic this broker does not hold is refused with unknown topic or partition | `BrokerDescribeTest#refusesADescribeThatNamesATopicThisBrokerDoesNotHold` | 6 |
| A describe that names no topic asks about every topic there is, and a broker holding none answers with no topics rather than a failure | `BrokerDescribeTest#describesEveryTopicOfABrokerHoldingNoneAsNoTopicsRatherThanAFailure` | 6 |
| A group this broker has never heard of is described with no committed offsets rather than refused, because a commit is what creates a group | `BrokerDescribeTest#describesAGroupThatHasNeverCommittedAsNoOffsetsRatherThanAFailure` | 6 |
| A describe reports a partition's log start offset, high-water mark, segment count, and a byte count equal to what its log and index files actually occupy, across a segment roll | `BrokerDescribeTest#reportsThePartitionsOffsetsSegmentCountAndBytesOnDiskAcrossASegmentRoll` | 6 |
| A describe of a group returns exactly the offsets that group committed and nobody else's, topic and then partition, whatever order the commits arrived in | `BrokerDescribeTest#describesExactlyTheOffsetsAGroupCommittedInTopicThenPartitionOrder` | 6 |
| A topic is described under the folded name that is its identity, whatever casing created it or asks about it | `BrokerDescribeTest#describesATopicUnderTheFoldedNameThatIsItsIdentityWhateverCasingAsksForIt` | 6 |
| A describe request whose topic count claims more names than the frame could hold is refused before a list is sized to it | `RequestFrameHostileBytesTest#refusesATopicCountThatClaimsMoreNamesThanBytesRemain` | 6 |
| A describe answer whose count is negative, or claims more entries than the frame could hold, is refused by the reader that would have to allocate for it | `ResponseFrameTest#refusesACountThatIsNegativeOrClaimsMoreEntriesThanBytesRemain` | 6 |
| A client describes the topic it has been producing to and is told each partition's offsets, segment count, and bytes on disk | `ClientRoundTripTest#describesTheTopicItHasBeenProducingTo` | 6 |
| A client describes a group and is told the offsets that group committed, while a group that has committed nothing comes back empty rather than failing | `ClientRoundTripTest#describesTheOffsetsAGroupHasCommitted` | 6 |
| A describe naming a topic the broker does not hold reaches the caller as a typed unknown-topic failure | `ClientRoundTripTest#raisesUnknownTopicWhenADescribeNamesATopicThisBrokerDoesNotHold` | 6 |
| A describe answer whose entry count cannot be believed closes the client's connection with a malformed-response failure instead of being sized to, and without waiting for bytes that are not coming | `BrokerConnectionGuardTest#closesTheConnectionWhenADescribeAnswerCountsMoreEntriesThanItCarries` | 6 |
| The admin facade reports the topics, partition offsets, segment counts, bytes on disk, and group lag of a broker running in a separate process, over the wire protocol and without opening one of its files | `AdminFacadeIT#reportsTheTopicsPartitionsAndGroupLagOfTheBrokerItIsPointedAt` | 6 |
| Lag is a partition's high-water mark less the offset the group committed, so a group that has read a partition to its end has none | `GroupLagTest#reportsNoLagForAPartitionAGroupHasReadToItsEnd` | 6 |
| A lag below zero is refused rather than published, because the group is read before the topics precisely so that one cannot happen | `GroupLagTest#refusesToReportALagBelowZero` | 6 |
| Asking the facade about a topic the broker does not hold is answered 404, with one plain sentence and no stack trace, exception name, or path | `AdminFacadeIT#answersNotFoundForATopicTheBrokerDoesNotHold` | 6 |
| Asking the facade for the lag of a group that has committed nothing is answered 404, saying only what the broker can actually tell | `AdminFacadeIT#answersNotFoundForAGroupThatHasCommittedNothing` | 6 |
| A facade pointed at a broker that is not listening answers 503 rather than an empty report | `AdminFacadeIT#answersServiceUnavailableWhenTheBrokerIsNotListening` | 6 |
| A topic name the protocol will not carry is refused by the facade with 400 and the rule it broke, before anything is asked of the broker | `AdminFacadeIT#answersBadRequestForANameTheProtocolWillNotCarry` | 6 |
| A path the facade does not serve is answered 404 in the same one-field JSON shape as every other failure, never the framework's default error body | `AdminFacadeIT#answersNotFoundWithoutDetailForAPathItDoesNotServe` | 6 |
| `/error`, where the container forwards, is answered in the facade's own shape whether the caller will accept JSON or HTML, rather than the framework's error body or its HTML page | `AdminFacadeIT#answersTheContainersErrorPathInItsOwnShapeWhateverTheCallerWillAccept` | 6 |
| A path Tomcat refuses inside the container, `/WEB-INF` and `/META-INF`, comes back in that same one-field shape, without the timestamp and path the framework's default body carries | `AdminFacadeIT#answersAPathTheContainerRefusesInTheSameShapeAsEveryOtherFailure` | 6 |
| A request Tomcat refuses before the servlet stack sees it is answered with a status code and an empty body, never an HTML page naming the server | `AdminFacadeIT#answersARequestTomcatRefusesWithAStatusAndAnEmptyBodyRatherThanAPage` | 6 |
| A broker read out of its environment binds the loopback interface when nothing in that environment asks for a wider one | `BrokerLaunchTest#bindsTheLoopbackAddressWhenTheEnvironmentDoesNotAskForAWiderOne` | 6 |
| Every interface is bound only when the environment says so in so many words | `BrokerLaunchTest#bindsEveryInterfaceOnlyWhenTheEnvironmentSaysSoInSoManyWords` | 6 |
| A broker process started without a data directory refuses to start rather than choosing a path of its own | `BrokerLaunchTest#refusesToStartWithoutADataDirectory` | 6 |
| A describe answered while retention is deleting segments from the same partition never reports a range running backwards or a partition with no segments, because the snapshot and the sweep take one lock | `PartitionDescribeRetentionRaceTest#describesOneInstantOfAPartitionRetentionIsDeletingSegmentsFrom` | 6 |
| A describe taken after retention has moved a partition past a group's commit reports the offset it moved to, the high-water mark retention never touched, and only the bytes the segments that are left occupy | `BrokerDescribeTest#reportsTheOffsetRetentionMovedToBesideACommitThatFellBehindIt` | 6 |
| A group whose unread records retention deleted still lags by the high-water mark minus its commit, because lag says how far behind a group is and not how much of that it can still read | `GroupLagTest#countsTheRecordsRetentionDeletedInTheLagOfAGroupThatFellBehindIt` | 6 |
| A broker started with nothing named but its data directory holds exactly the configuration it held before any setting could come from the environment | `BrokerLaunchTest#runsTheDefaultsItAlwaysRanWhenNothingButTheDataDirectoryIsSet` | 1.1 |
| Every setting this README documents is read from one environment variable of its own, and each value lands in the field that variable names | `BrokerLaunchTest#readsEverySettingFromTheVariableThatNamesIt` | 1.1 |
| A setting that is not a whole number stops the start with a message naming the variable and quoting what it was given | `BrokerLaunchTest#refusesASettingThatIsNotAWholeNumberNamingTheVariableAndQuotingWhatItWasGiven` | 1.1 |
| A setting outside the bounds its record holds stops the start naming the variable that set it, with the record's own sentence behind it, rather than falling back to a default | `BrokerLaunchTest#refusesASettingOutsideItsRangeNamingTheVariableThatSetItAndKeepingTheRecordsSentence` | 1.1 |
| Either flush-mode spelling is taken in whatever letters it arrives in | `BrokerLaunchTest#takesEitherFlushModeSpellingInWhateverLettersItArrivesIn` | 1.1 |
| A flush-mode spelling this build does not know stops the start and names the two spellings it takes | `BrokerLaunchTest#refusesAFlushModeSpellingThisBuildDoesNotKnowAndSaysWhichTwoItTakes` | 1.1 |
| `fetch.zero.copy` set to a word that is neither true nor false stops the start rather than being read as false | `BrokerLaunchTest#refusesAZeroCopySettingThatIsNeitherTrueNorFalseRatherThanReadingItAsFalse` | 1.1 |
| A connection that sends nothing for the whole of `read.timeout.ms` is closed, and the place it held under the connection cap comes back on its own thread, so the next connection is served | `BrokerReadTimeoutTest#closesAConnectionThatSaysNothingPastTheBoundAndGivesItsPlaceUnderTheCapBack` | 1.1 |
| A connection that sent a length this broker believed and then stalled part way through the frame behind it is closed too, so the bound covers finishing a request and not only starting one | `BrokerReadTimeoutTest#closesAConnectionThatStalledPartWayThroughARequestFrame` | 1.1 |
| A long-poll fetch waiting out a `max.fetch.wait.ms` many times longer than `read.timeout.ms` is never closed for it and is served its records, because waiting to write is serving rather than reading | `BrokerReadTimeoutTest#keepsALongPollFetchThatWaitsFarPastTheReadTimeoutAndServesItItsRecords` | 1.1 |
| A connection whose requests are spaced under the bound is still answering twice the bound after it was opened | `BrokerReadTimeoutTest#keepsAConnectionWhoseRequestsAreSpacedUnderTheBound` | 1.1 |
| The read bound is swept on a thread named shrike-conn-reaper, repeatedly, and closing it ends that thread | `ConnectionReaperTest#reapsOnItsOwnNamedThreadUntilItIsClosed` | 1.1 |
| How long a connection has been reading is measured against the injected clock, so a test advances time instead of waiting | `ConnectionReaperTest#looksForStalledConnectionsWithTheTimeItReadsFromTheInjectedClock` | 1.1 |
| A `read.timeout.ms` of zero stops the start naming the variable that set it, rather than starting a broker that closes every connection it accepts | `BrokerLaunchTest#refusesAReadTimeoutOfZeroNamingTheVariableThatWouldHaveClosedEveryConnection` | 1.1 |
| A commit that would create a consumer group past the group budget is refused as an invalid request, and the connection that asked keeps being served | `BrokerGroupBudgetTest#refusesACommitThatWouldCreateAGroupPastTheGroupBudgetAndKeepsServing` | 1.1 |
| A commit by a group the broker already holds is served with the budget full, because what is bounded is how many groups come into being rather than how often they commit | `BrokerGroupBudgetTest#servesACommitToAGroupItAlreadyHoldsWhenTheGroupBudgetIsFull` | 1.1 |
| A broker started over a data directory that already holds more groups than its budget loads every one of them, serves their commits, and refuses only the next new group | `BrokerGroupBudgetTest#opensAGroupCountAlreadyPastTheBudgetAndRefusesTheNextNewGroup` | 1.1 |
| The refusal names the group, how many groups are held, and the budget, and the group it refused has no file and no offsets afterwards | `GroupOffsetStoreTest#refusesTheCommitThatWouldCreateAGroupPastTheBudgetAndWritesNothingForIt` | 1.1 |
| A group whose first commit fails to reach the device is forgotten along with the place it took under the budget, so the groups after it still commit | `GroupOffsetStoreTest#forgetsAGroupWhoseCreatingCommitNeverBecameDurableAndKeepsItsBudgetSlot` | 1.1 |
