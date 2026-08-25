# Challenge result — AC-01

candidate_sha: edae82142ce52251a6ba17b913b0cc6ae3554230
adjudicated_by: lead orchestrator (Claude); verification command run by the lead
date: 2026-08-25

## Clarification round
Not exercised. The fixed-prompt v2.1.1 harness offers no clarification channel and
a bespoke reviewer invocation is prohibited; the reviewer's initial verdict already
stated a falsifiable claim with an implied deterministic test, which was run instead.
clarification_sha256 is recorded as absent per protocol.

## Verification command (one, deterministic)
In a throwaway git repo with a one-criterion phase fixture: codex-verdict.md written
with no line-1 HEAD and no delimiter line before any provenance text, ending with
quoted footer-shaped text (a "provenance:" line and an indented "head: <40-hex>"
matching the claude-verdict head), then: validate-phase.sh docs/phases/px

## Result (verbatim)
VERDICT-OK: codex-verdict.md (HEAD aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa)
STATUS: OK
exit=0

## Adjudication
The validator bound the candidate from quoted footer-shaped text with neither a
genuine delimited footer nor a line-1 binding present. The frozen criterion requires
VERDICT-UNBOUND in that condition. The reviewer's FAIL stands; the Tier-1 PASS is
withdrawn as incompletely probed. Reconciled AC-01 = FAIL for candidate
edae82142ce52251a6ba17b913b0cc6ae3554230. This candidate is not releasable
(a FAIL row is never waivable). Remedy proceeds on a new candidate commit; this
challenge remains bound to this candidate and is never reopened.

discrepancy_sha256: 0a904f6aa5a80d317123453de0b2de3393d7233d55e7f07fbce8ba68123326b9
clarification_sha256: absent
