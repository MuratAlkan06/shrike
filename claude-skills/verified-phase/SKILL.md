---
name: verified-phase
description: Runs one contract-bound development phase with two-tier verification — Claude leads and verifies (Tier 1), the read-only Codex gate harness reviews independently (Tier 2), and the two evidence matrices are reconciled before any release decision. Explicit invocation only; never triggered automatically.
disable-model-invocation: true
---

# Verified phase

Run one development phase for: **$ARGUMENTS**

If that task description is empty, stop here and ask the user what the phase
should deliver. Do not inspect the repository, bootstrap anything, or start
planning until you have their answer.

**These instructions stay active for the entire phase — every step below,
through the final release decision. They are not a one-shot prompt.** If the
conversation wanders, come back to this workflow; if the user changes the
goal, the phase ends and a new contract begins.

Read `~/.claude/skills/verified-phase/references/authority-boundary.md` now.
The short version: you are the sole lead — planning, delegation,
implementation, Tier-1 verification, commits, reconciliation, and the release
decision are all yours. Codex is an independent read-only reviewer, invoked
only through `~/.claude/harness/codex-gate.sh`. Never call the `codex` CLI
directly; never rewrite the harness.

## Workflow

Full detail for every step:
`~/.claude/skills/verified-phase/references/phase-workflow.md`.

1. **Find the repository.** `git rev-parse --show-toplevel`. You may have
   started in a nested directory. Every phase artifact lives in that
   repository — never under `$HOME`, never in the current directory.
2. **Inspect what exists.** `docs/phases/`, `.codex-gate.conf`, `AGENTS.md`,
   the CI config. Match existing conventions rather than inventing new ones.
3. **Bootstrap what is missing.**
   `~/.claude/skills/verified-phase/scripts/bootstrap-repo.sh` — idempotent,
   places only absent files, never overwrites, never edits `AGENTS.md`.
4. **Preflight.** `~/.claude/skills/verified-phase/scripts/preflight.sh`
   reports the verification commands, base cleanliness, secret hygiene, and
   harness presence. On `MISSING-COMMANDS` or `AMBIGUOUS-COMMANDS`, ask the
   user one time which command proves this repo works. Inference proposes;
   only you write `.codex-gate.conf`.
5. **Commit the scaffolding first.** Complete it, verify the test command
   actually runs, commit it, confirm `git status --porcelain` is empty — and
   only then record that commit as the phase base. A base recorded on a dirty
   tree describes a state no commit ever had.
6. **Write the contract and get approval.** Copy
   `~/.claude/skills/verified-phase/assets/contract-template.md` to
   `docs/phases/<phase>/contract.md`: goal, non-goals, stable `AC-<n>:` IDs
   (never renumbered), the evidence that settles each one, interface
   commitments, base SHA. Stop and get the user's explicit approval before
   implementing. After approval the contract is frozen.
7. **Implement and verify (Tier 1).** Delegate slices, review, run the repo's
   own tests. Commit everything, then write
   `docs/phases/<phase>/claude-verdict.md` with `HEAD: <sha>` as line 1 —
   before anything Tier-2 happens. That commit is the candidate; it is now
   fixed. Self-check with
   `~/.claude/skills/verified-phase/scripts/validate-phase.sh docs/phases/<phase>`.
8. **Gate (Tier 2).** From the repo root, in FOREGROUND Bash only — never
   backgrounded, because of the empty-output condition of upstream issue
   codex#19945:

       ~/.claude/harness/codex-gate.sh <phase> <base-ref> [paths]

   Exit 0 means a verdict was produced, **not** that verification passed.
   Any other exit, or an empty verdict, is INCONCLUSIVE — never a pass.
9. **Reconcile.** Both PASS -> PASS. Both FAIL -> FAIL. Disagreement ->
   UNRESOLVED, never averaged, and a Codex PASS never overwrites your FAIL.
   A Codex CANNOT-VERIFY gets one evidence-augmentation rerun (step 9 of
   `phase-workflow.md`). A material PASS/FAIL disagreement is the only thing
   that may open a bounded challenge:
   `~/.claude/skills/verified-phase/references/discrepancy-protocol.md`.
   Write the merged matrix to `docs/phases/<phase>/decision.md`, then run
   `validate-phase.sh --release-check docs/phases/<phase>`.
10. **Decide.** The release decision is yours alone, stated in the
    conversation. UNRESOLVED and unwaived CANNOT-VERIFY rows never auto-pass.
    Waivers are human-only: `WAIVED-BY: <name> <date> — <reason>`. You do not
    write that line, and neither does any other agent.

## Non-negotiables

- Never invoke the `codex` CLI from Bash. The harness is the only sanctioned
  entry point, and a drift guard denies the rest by default.
- Never run the gate backgrounded.
- Never overwrite a user-authored `AGENTS.md`; it is the reviewer's
  instruction file, not a Claude agent definition.
- Never amend an approved contract to match what got built. Log the
  deviation, or end the phase.
- Never let a script, a green test run, or a reviewer issue the release
  decision for you.
