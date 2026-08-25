# Phase 02-validator-harness-alignment — Contract

Base SHA: 3d6ea7826a526697a0203c6ddc54931a4d02eb43
Candidate: HEAD of the committed range under review (claude-verdict.md line 1 binds it).

## Goal
Align `claude-skills/verified-phase/scripts/validate-phase.sh` with the output the v2.1.1 gate harness actually publishes, so that a genuine `codex-verdict.md` validates instead of being refused as unbound, and make `--release-check` compare the merged decision matrix against the contract's complete criterion set instead of reading rows one at a time. Both defects were found in new code during phase 01 and recorded in that phase's decision as future-phase candidates.

## Non-goals
- No change to the v2.1.1 gate machinery: the global harness, the hooks, `.claude/settings.json`, `AGENTS.md`, and `docs/phases/_TEMPLATE/` are untouched. The only file of that set edited here is the repo-controlled `.codex-gate.conf`, whose evidence slots the repository owns.
- No change to any file under `docs/phases/01-verified-phase-skill/`; that record is frozen history and is verified byte-identical.
- None of the seven deferred findings recorded in phase 01 are addressed here.
- No change to any Java module, the Maven build, CI workflows, or README claims outside `claude-skills/`.
- No push, tag, or release.

## Acceptance criteria
AC-01: validate-phase.sh binds the codex-verdict.md candidate SHA from the v2.1.1 harness provenance footer (`head:` line), still accepts a legacy line-1 `HEAD:` binding, requires agreement when both are present, and reports VERDICT-UNBOUND when neither is present.
AC-02: --release-check rejects a decision file missing a row for any contracted criterion with distinct status DECISION-MISSING-ROW and non-zero exit.
AC-03: --release-check rejects a decision row naming a criterion absent from the contract with distinct status DECISION-UNKNOWN-ROW and non-zero exit.
AC-04: --release-check rejects duplicate rows for the same criterion with distinct status DECISION-DUPLICATE-ROW and non-zero exit.
AC-05: claude-verdict.md binding is unchanged: line 1 must be `HEAD: <sha>` matching the candidate, enforced as before.
AC-06: Every pre-existing suite check is preserved and passing; the suite total strictly exceeds 109 and the suite exits 0.
AC-07: The diff touches only claude-skills/**, docs/phases/02-validator-harness-alignment/**, and .codex-gate.conf.
AC-08: All modified scripts remain bash-3.2 compatible and pass the suite's syntax and compatibility checks.
AC-09: Skill documentation describing verdict binding and release-check statuses is updated in this same diff.
AC-10: The LINT evidence block shows validate-phase.sh --release-check succeeding against the committed docs/phases/01-verified-phase-skill record with STATUS: RELEASABLE and exit status 0.

## Evidence requirements
- For AC-01 through AC-05 and AC-07 through AC-09: cite file:line from the DIFF section.
- For AC-06: cite the TYPECHECK block tail and its exit status; the tail carries the suite total.
- For AC-10: cite the LINT block, its status line, and its exit status.

## Interface commitments
- validate-phase.sh statuses gained this phase: `DECISION-DUPLICATE-ROW`, `DECISION-UNKNOWN-ROW`, `DECISION-MISSING-ROW`. All pre-existing statuses keep their names and meanings.
- Verdict binding rule: `codex-verdict.md` is bound by the provenance footer's `head:` line or by a line-1 `HEAD: <sha>`; when both are present they must name the same commit, and when neither is present the status is `VERDICT-UNBOUND`. `claude-verdict.md` is bound by line 1 only. Two refinements of that rule: only the last provenance footer in the file binds, so verdict prose quoting the footer format names no candidate; and a line-1 sha that abbreviates the full sha in the footer names the same commit, as it does everywhere else in this script.
- Decision-matrix rule: the merged matrix carries one row for each contracted criterion and no others. Every detected problem is printed; the reported status is the first present of duplicate, unknown, missing, in that order.
- Gate evidence commands: TEST_CMD `mvn verify` (unchanged), TYPECHECK_CMD `/bin/bash claude-skills/tests/verified-phase-tests.sh` (unchanged), LINT_CMD `claude-skills/verified-phase/scripts/validate-phase.sh docs/phases/01-verified-phase-skill --release-check` (added this phase).

## Deviation log
- One pre-existing status was changed rather than preserved: a `decision.md` whose matrix has no criterion rows at all previously refused with `NOT-RELEASABLE` ("no merged criterion rows found"), and now refuses with `DECISION-MISSING-ROW`, naming every criterion left without a row. AC-02 asks for that status whenever a contracted row is absent, and an empty matrix is the extreme case; both paths refuse with a non-zero exit, and the original wording is kept as a detail line. No test or reference document depended on the old status.
