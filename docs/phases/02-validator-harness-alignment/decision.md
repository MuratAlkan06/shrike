# Phase 02-validator-harness-alignment — Release Decision

HEAD: b93fd1ab3a1991bca0bc35eea6393893569c15e5
Base: 3d6ea7826a526697a0203c6ddc54931a4d02eb43
Decided by: lead orchestrator (Claude) — Tier-2 reviews, the lead alone decides.
Date: 2026-08-25

## Reconciled matrix (candidate b93fd1ab)

| Criterion | Claude (Tier-1) | Codex (Tier-2) | Reconciled |
|---|---|---|---|
| AC-01 | PASS | PASS | PASS |
| AC-02 | PASS | PASS | PASS |
| AC-03 | PASS | PASS | PASS |
| AC-04 | PASS | PASS | PASS |
| AC-05 | PASS | PASS | PASS |
| AC-06 | PASS | PASS | PASS |
| AC-07 | PASS | PASS | PASS |
| AC-08 | PASS | PASS | PASS |
| AC-09 | PASS | PASS | PASS |
| AC-10 | PASS | PASS | PASS |

No open PASS/FAIL splits, no CANNOT-VERIFY rows, no waivers requested or granted.

## Challenge history (bounded protocol, exercised in earnest)

On the first candidate (edae82142ce52251a6ba17b913b0cc6ae3554230) the two tiers
split PASS/FAIL on AC-01. The bounded protocol ran: neutral packet created and
frozen; the reviewer's clarification round recorded as absent (no channel exists
under the fixed-prompt harness); the reviewer's falsifiable claim was tested with
one deterministic command; the reviewer's FAIL was upheld; the Tier-1 PASS was
withdrawn. Artifacts sealed read-only under challenges/AC-01/ with recorded
hashes, bound to the superseded candidate, never reopened. The defect (binding
from quoted footer-shaped text without the delimiter) was remediated on this
candidate; the adjudicated scenario now refuses with VERDICT-UNBOUND and is a
permanent suite fixture.

## Decision

GO. All ten criteria PASS from both independent verifiers on candidate b93fd1ab
against the frozen contract (contract_sha256
f40518fd51f4c482c51d44a7aaffebf9d184f884e473e1fe288af71b6f6e9c95). Codex
provenance: harness codex-gate.sh v2.1.1, codex-cli 0.149.1, model gpt-5.6-sol,
effort high, sandbox read-only, ephemeral, clean-room, 2026-08-25T02:12:42Z.

## Notes carried forward (future-phase candidates, not defects of this contract)

1. Reviewer UNSTATED-RISK: uppercase-hex SHA spellings are accepted at binding
   but compared case-sensitively between line 1 and the footer; a mixed-case
   spelling of the same commit would be refused as a conflict. Fail-closed only.
2. TM-A residual, documented in the skill docs: prose reproducing a full
   delimited footer byte for byte is indistinguishable by construction; this is
   a cooperative drift guard, not an adversarial boundary.
