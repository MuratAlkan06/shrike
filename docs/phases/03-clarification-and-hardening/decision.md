# Phase 03-clarification-and-hardening — Release Decision

HEAD: 4b3a574c3651c7ef71ff5dd6ee4e4000a49ab5fe
Base: 5803432e5b28579d8800eb0a3302c19a4d4c4fa3
Decided by: lead orchestrator (Claude) — Tier-2 reviews, the lead alone decides.
Date: 2026-08-25

## Reconciled matrix (candidate 4b3a574c)

| Criterion | Claude (Tier-1) | Codex (Tier-2) | Reconciled |
|---|---|---|---|
| AC-01 | PASS | PASS | PASS |
| AC-02 | PASS | FAIL | PASS |
| AC-03 | PASS | PASS | PASS |
| AC-04 | PASS | PASS | PASS |
| AC-05 | PASS | PASS | PASS |
| AC-06 | PASS | PASS | PASS |
| AC-07 | PASS | PASS | PASS |
| AC-08 | PASS | PASS | PASS |
| AC-09 | PASS | PASS | PASS |
| AC-10 | PASS | PASS | PASS |

AC-02's split was resolved through the bounded evidence challenge (sealed record at
challenges/AC-02/): the reviewer exercised its one clarification via the real
clarify-challenge.sh path built this phase, named a deterministic falsifier, the
lead ran that experiment, and the reviewer's claim was falsified per its own
falsifier. Both initial verdicts are preserved unchanged. No waivers requested or
granted; zero UNRESOLVED rows.

## Remediation history (four gate rounds, frozen contract unamended)

Round 1 (9b848e31): four reviewer findings upheld by lead concurrence without
challenge — write-flag literals in the diff, publish-time overwrite primitive,
missing candidate cross-check, lenient none-variant acceptance — remediated with
non-vacuity-proven tests, plus both reviewer risks (loose criterion-ID matching,
phase-directory containment). Round 2 (e007e0d1): refusal-status ordering for
adjudicated-and-clarified challenges, upheld and remediated. Round 3 (7d8a0a3e):
mid-call adjudication recheck and empty-command-section refusal, upheld and
remediated. Round 4 (4b3a574c): the residual check-then-link interval — challenged
and adjudicated as above. Suite growth across the phase: 166 to 317, nothing
removed (two labels renamed, recorded).

## Decision

GO. All ten criteria reconcile PASS for candidate 4b3a574c against the frozen
contract (contract_sha256 9fa99a6ebf0afc74ba42e19a2621beefe59989938d208c11ef7e6f6e5c4a91eb).
Tier-2 provenance: harness codex-gate.sh v2.1.1, codex-cli 0.149.1, gpt-5.6-sol,
effort high, sandbox read-only, ephemeral, clean-room, final run 2026-08-25T17:29:29Z.
The clarification path is validated by mock coverage (suite) and a real end-to-end
run on a synthetic dispute plus this phase's own live dispute.

## Carried forward (recorded, not fixed — future-phase candidates)

1. The check-then-link interval in clarify-challenge.sh is real; closing it fully
   needs a locking protocol shared with the adjudication writer. TM-A posture.
2. Reviewer risk: the packet is re-read for stdin and footer hashing without a
   snapshot equality recheck (same family as 1).
3. The result-file parser binds the first line matching its reserved keys rather
   than the last; narrative text must not begin a line with them (bit the lead
   while writing the AC-02 record).
4. CLARIFY-AUTH is reported when the reviewer CLI is absent entirely (fail-closed,
   imprecise cause); a hard-link failure for reasons other than an existing
   destination names CLARIFY-EXISTS imprecisely while publishing nothing.
5. The suite's symlink guard must remain the first executable section of the test
   file (comment-level warning present).
6. Protocol-doc description of the command counter is now incomplete (it also
   rejects zero); a section containing only a fence or placeholder counts as empty.
7. The seven phase-01 deferred findings remain untouched per the contract.
