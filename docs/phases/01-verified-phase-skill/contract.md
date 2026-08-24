# Phase 01-verified-phase-skill — Contract

Base SHA: ff290ad70d15a32ca530f96db02ba7fd620a9693
Candidate: HEAD of the committed range under review (claude-verdict.md line 1 binds it).

## Goal
Add the tracked `verified-phase` Claude Code skill package (canonical source for the personal skill installed at ~/.claude/skills/verified-phase), an idempotent installer, and its automated shell test suite, wired into this repository's gate evidence commands.

## Non-goals
- No change to any Java module, the Maven build, CI workflows, or README claims.
- No change to the v2.1.1 gate machinery (global harness, hook, .claude/settings.json, AGENTS.md, docs/phases/_TEMPLATE).
- No push, tag, or release.

## Acceptance criteria
AC-01: claude-skills/verified-phase/SKILL.md exists with frontmatter `name: verified-phase` and `disable-model-invocation: true`, and the file contains no `context:` key and no `allowed-tools:` key.
AC-02: SKILL.md takes the task from `$ARGUMENTS`, instructs asking the user for the task when it is empty, and states the skill instructions remain active for the entire phase.
AC-03: No file in the diff invokes the codex CLI (exec, apply, resume, or any write-capable flag); Tier-2 review is routed exclusively through ~/.claude/harness/codex-gate.sh.
AC-04: claude-skills/install.sh syncs the tracked source to ~/.claude/skills/verified-phase, is idempotent (second run reports UP-TO-DATE and changes nothing), and backs up a differing pre-existing install under ~/.claude/skill-backups/ before replacing it.
AC-05: claude-skills/verified-phase/scripts/bootstrap-repo.sh resolves the repository root from a nested working directory, never overwrites an existing file (including AGENTS.md and .codex-gate.conf), and exits non-zero without creating files when run outside a git work tree.
AC-06: claude-skills/verified-phase/scripts/preflight.sh reports MISSING-COMMANDS, AMBIGUOUS-COMMANDS, and DIRTY-BASE conditions as distinct statuses, and its secret checks test only whether OPENAI_API_KEY or CODEX_API_KEY are defined, printing variable names and never values.
AC-07: claude-skills/verified-phase/scripts/validate-phase.sh refuses a second challenge for an already-challenged criterion (CHALLENGE-EXISTS), detects post-adjudication tampering via recorded sha256 hashes (CHALLENGE-TAMPERED), rejects a clarification containing more than one verification command, treats agent-signed waivers as invalid, never treats a FAIL row as waivable, never converts UNRESOLVED or CANNOT-VERIFY into a pass, and never itself issues GO.
AC-08: The package test suite passes: the TYPECHECK evidence block shows `PASS: 109 verified-phase checks` with exit status 0.
AC-09: The Java build is unaffected: the TESTS evidence block shows `mvn verify` with exit status 0, and the diff touches only claude-skills/**, docs/phases/**, and .codex-gate.conf.
AC-10: Every shell script in the diff declares a bash shebang with `set -euo pipefail`, and the suite output confirms its syntax and bash-3.2 compatibility checks passed.

## Evidence requirements
- For AC-01 through AC-07, AC-09, AC-10: cite file:line from the DIFF section; the installer, bootstrap, preflight, and validate criteria may additionally cite named checks in the TYPECHECK block output.
- For AC-08: cite the TYPECHECK block tail and its exit status.
- For AC-09: cite the TESTS block exit status plus the DIFF scope line.

## Interface commitments
- Installed skill path: ~/.claude/skills/verified-phase/ (SKILL.md + references/ + assets/ + scripts/), byte-synced from claude-skills/verified-phase/.
- Gate evidence commands: TEST_CMD `mvn verify` (unchanged), TYPECHECK_CMD `/bin/bash claude-skills/tests/verified-phase-tests.sh` (added this phase).
- validate-phase.sh statuses: OK, CHALLENGE-EXISTS, CHALLENGE-TAMPERED, CLARIFICATION-INVALID, NOT-RELEASABLE, RELEASABLE, MISSING-PHASE-DIR.

## Deviation log
- Ten implementer deviations from the session brief, all conservative tightenings, accepted pre-candidate: phase-dir-first argument order; challenge refusal whenever a challenge directory for the criterion exists; FAIL rows never waivable; decision file at docs/phases/<phase>/decision.md; reviewer-instruction file and gate-conf example embedded in bootstrap-repo.sh; seal-on-validate; shellcheck at error severity (skips cleanly when absent); an unscoped human waiver applies to all non-pass rows in its file; preflight emits per-section statuses with a first-blocking overall STATUS; dirty-base test fixture uses a tracked file.
