# AGENTS.md

Your role in this repo is assigned per invocation. If no role was given in
your instructions, stop and ask. Do not assume you are implementing.

## Source of truth
Phase contracts live in `docs/phases/<phase>/contract.md`: goals, non-goals,
acceptance criteria, interface commitments. The contract is authoritative and
fixed. You do not amend it, extend it, or infer additional scope from it.
Where contract and code disagree, that is a finding — not something to fix.

## Invariants
- Never work outside the current phase's stated scope.
- Never touch files outside the working directory you were given.
- Acceptance criteria are executable. To claim a pass, cite the command
  output or file:line that proves it.
- Never commit, branch, push, or open a PR.

## Verification commands
Defined in `.codex-gate.conf` at repo root (hash-recorded per gate run).
