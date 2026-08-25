# Phase 03-clarification-and-hardening — Contract

Base SHA: 5803432e5b28579d8800eb0a3302c19a4d4c4fa3
Candidate: HEAD of the committed range under review (claude-verdict.md line 1 binds it).

## Goal
Make the bounded evidence challenge's middle step real, and close the isolation defect that let a test fixture write into the tree under review. Phase 02 adjudicated its AC-01 challenge without ever obtaining a clarification, because there was no sanctioned way to ask for one: the protocol described a reply the tooling could not deliver. This phase adds `claude-skills/verified-phase/scripts/clarify-challenge.sh`, the second sanctioned entry point to the reviewer CLI — fixed prompt, exactly two arguments, the frozen `discrepancy.md` on stdin, the gate's clean-room read-only flag set, one clarification per candidate and criterion, ever. `validate-phase.sh` gains the matching judgement: a sole `none` command is valid, the appended provenance footer is verified against the packet, and neither replaces the verification lead's adjudication. Separately, the suite and the installer stop resolving a symlinked source and refuse it instead, and every fixture that writes runs against an isolated copy — the arrangement that caused the phase-02 incident is reproduced as a regression test.

## Non-goals
- None of the seven deferred findings recorded in phase 01 are addressed here.
- No change to the case-sensitivity of SHA comparison anywhere in the skill; hex case handling stays exactly as phase 02 left it.
- The TM-A residual is not closed. A cooperative drift guard is what this is; prose that reproduces a whole delimited footer byte for byte is still indistinguishable from a real one, and a read-only reviewer can still read the filesystem. This phase documents that limit; it does not remove it.
- No change to any v2.1.1 file: `~/.claude/harness/*`, `.claude/hooks/*`, `.claude/settings.json`, `AGENTS.md`, and `docs/phases/_TEMPLATE/` are untouched.
- No change to `.codex-gate.conf`; its evidence slots are exactly as phase 02 left them.
- No change to any file under `docs/phases/01-verified-phase-skill/` or `docs/phases/02-validator-harness-alignment/`; both records are frozen history and are verified byte-identical.
- Nothing about the state of PR #48 is touched, read as authoritative, or changed.
- No push, no pull-request mutation, no tag, no merge, no release.

## Acceptance criteria
AC-01: clarify-challenge.sh supplies the reviewer only the frozen discrepancy.md on stdin with a fixed in-script prompt, accepts exactly two arguments with no prompt or argument passthrough, and invokes the reviewer CLI solely with the gate's clean-room read-only flag set; no write-capable flag appears anywhere in the diff.
AC-02: A second clarification for the same candidate and criterion is refused with distinct status CLARIFY-EXISTS, and an adjudicated challenge is refused with CLARIFY-ADJUDICATED; the script never overwrites an existing clarification.
AC-03: Before invocation the script re-verifies the packet provenance (contract, both verdicts, candidate, disputed criterion) and refuses mismatches with CLARIFY-PACKET-MISMATCH; the published clarification carries a clarify-provenance footer that validate-phase.sh verifies against the packet.
AC-04: The reviewer reply is published verbatim plus footer; validate-phase.sh judges structure, accepts a sole `none` command, rejects two or more commands, and an invalid or command-free clarification leaves the disagreement UNRESOLVED per the documented protocol.
AC-05: On authentication refusal, reviewer failure, or empty output the script publishes nothing and reports a distinct status; the protocol documents recording clarification absent with a stated reason and no longer presents an absent-clarification challenge as a complete debate loop.
AC-06: Secret checks are defined-only, print variable names never values, and captured login output is never echoed.
AC-07: The test suite refuses to run when its skill-source root resolves through a symlink, all fixtures operate on isolated copies, and install.sh refuses a symlinked source with SKILLS-SRC-SYMLINK.
AC-08: A regression test reproduces the symlinked-source incident shape and asserts both the refusal and that the referenced live SKILL.md is byte-unchanged after the run.
AC-09: The skill documentation in this diff describes the real one-shot clarification loop, the absent-with-reason rule, and the remote-operation boundary in which remote-mutating operations route through the GitHub workflow manager gate with explicit human authorization and the reviewer performs no git or GitHub operation.
AC-10: The suite total strictly exceeds 166 with exit 0 and every pre-existing check preserved; the diff touches only claude-skills/** and docs/phases/03-clarification-and-hardening/**; no v2.1.1 file is modified.

## Evidence requirements
- For AC-01 through AC-09: cite file:line from the DIFF section.
- For every claim backed by a suite case, and for AC-10: cite the TYPECHECK block tail and its exit status; the tail carries the suite total.
- For AC-10's scope claim: cite the DIFF section's file list.
- The LINT block is unchanged from phase 02 and is expected to report `STATUS: RELEASABLE` with exit status 0 against the committed `docs/phases/01-verified-phase-skill` record.

## Interface commitments
- clarify-challenge.sh usage is `clarify-challenge.sh <phase-dir> <AC-ID>`, exactly two arguments. There is no prompt override, no environment override, and no argument passthrough; the prompt is fixed in the script and the packet on stdin is the only caller-controlled input to the reviewer.
- Statuses added this phase: `CLARIFY-OK` (exit 0), `CLARIFY-EXISTS`, `CLARIFY-ADJUDICATED`, `CLARIFY-PACKET-MISMATCH`, `CLARIFY-INCONCLUSIVE` (all exit 1), `CLARIFY-AUTH` (exit 4), `USAGE` (exit 2), plus `CLARIFICATION-TAMPERED` from validate-phase.sh, `TESTS-SRC-SYMLINK` from the suite, and `SKILLS-SRC-SYMLINK` from install.sh. All pre-existing statuses keep their names and meanings.
- `CLARIFY-OK` means a reply was published; it does not mean the disagreement is settled. Only a published `codex-clarification.md` spends the one clarification: every refusal and every inconclusive run publishes nothing and consumes nothing.
- Footer keys published below the verbatim reply, under a line that is exactly `---` with `clarify-provenance:` at column 0 immediately below it: `packet_sha256`, `disputed_criterion`, `candidate_sha`, `base_sha`, `contract_sha256`, `codex`, `model`, `clarifier`. validate-phase.sh verifies `packet_sha256`, `disputed_criterion` and `candidate_sha` against the packet on disk and refuses a mismatch as `CLARIFICATION-TAMPERED`. A clarification with no footer is legacy-acceptable and simply unbound.
- Verification-command convention: at most one command line, or the single word `none`. `none` counts as zero commands, because there is nothing for the verification lead to run. The footer is excluded from that count.
- The clarification call is foreground-only, for the same reason the gate is: a backgrounded run is the empty-output condition of upstream issue codex#19945, and empty output is inconclusive, never a pass.
- Both the suite and install.sh compare the physical and logical resolution of their own source root and refuse, non-zero, when they differ.

## Deviation log
- Two pre-existing suite checks were relabelled rather than left verbatim. Both assertions are preserved unchanged or strengthened; only the human-readable label moved, because in each case the label had become false.
  1. `install: all three scripts executable` now also asserts `clarify-challenge.sh` and reads `install: every shipped script is executable`; the count in the old label was wrong once a fourth script shipped.
  2. `scripts never invoke the reviewer CLI directly` reads `no script uses the bare reviewer-CLI subcommand form`. The grep is unchanged — it forbids `codex exec`, `codex apply` and `codex resume` — but the old label became false the moment a sanctioned entry point existed, and a suite that prints a false claim is worse than one that prints a narrower true one.
- The command-counting reader in `validate_clarification` was changed beyond the minimum described for the `none` convention: it now excludes the delimited `clarify-provenance:` footer from the section it counts. Without that, a genuine published clarification carrying one command counts its own eight footer lines as commands and is refused as `CLARIFICATION-INVALID`, so AC-04 could not hold. Recorded here because it alters how an existing reader treats an existing file shape, not only how it treats `none`.
