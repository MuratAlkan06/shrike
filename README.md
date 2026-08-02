# Shrike

Shrike is a single-node, log-structured message broker written in Java 21. Producers append records to a segmented commit log on one machine's disk, consumers read them back by offset, and delivery is at-least-once: a record may be redelivered after a failure, and no record is silently dropped.

Status: Slice 0 — scaffold.

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
