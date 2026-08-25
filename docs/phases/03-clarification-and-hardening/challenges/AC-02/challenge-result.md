# Challenge result — AC-02

candidate_sha: 4b3a574c3651c7ef71ff5dd6ee4e4000a49ab5fe
adjudicated_by: lead orchestrator (Claude); deterministic experiment run by the lead
date: 2026-08-25

## Clarification round
EXERCISED — the first production use of clarify-challenge.sh. The reviewer received
only the frozen packet and replied once (verbatim, provenance-bound). Its verification
command was the word none, but its falsification section defined a deterministic
experiment, which the verification lead ran as permitted by the protocol.

## The experiment (from the reviewer's own falsifier)
Falsifier text: "Evidence showing that validate-phase.sh deterministically refuses
every record containing codex-clarification.md when challenge-result.md carries a
recorded absent clarification hash would falsify the claim."
Construction: sandbox challenge adjudicated with the clarification hash recorded as
absent, then a clarification file introduced (the exact state the disputed interval
can produce). Control (no clarification file): ADJUDICATED, STATUS: OK, exit 0.
Experiment: DETAIL "codex-clarification.md changed after adjudication (or
appeared/disappeared)" with recorded value absent versus a now-present file hash →
STATUS: CHALLENGE-TAMPERED, exit 1.
The refusal is a recorded-hash comparison — deterministic, not timing-dependent.
The alternate interleaving (clarification landing before the adjudicator records
hashes) yields a legitimately clarified record with a real recorded hash; every
ordering therefore ends consistent or refused.

## Adjudication
The reviewer's clarified claim — that no evidence establishes validation always
refuses the raced record — is falsified per its own falsifier. The frozen disputed
fact resolves in favor of the Tier-1 verdict: the state the residual interval can
produce can never stand as a valid record. Reconciled AC-02 = PASS for candidate
4b3a574c3651c7ef71ff5dd6ee4e4000a49ab5fe. Carried forward, not disputed: the
script-level check-then-link interval is real and closable only by a locking
protocol shared with the adjudication writer (TM-A note); the reviewer's related
observation that the packet is re-read for stdin and footer hashing without a
snapshot equality recheck (same family, same posture); and, found while writing
this record, the result-file parser reads the first line matching its reserved
keys rather than the last, so narrative text must not begin a line with them.

discrepancy_sha256: d4a7195a004101eaab5d150c6af54c95565c9b2c7c13395791a148872bacfb7d
clarification_sha256: b411096152da0beff70d24a34f334bcaf91fe04a312533f260f3dad7bf494356
