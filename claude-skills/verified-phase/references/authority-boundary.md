# Authority boundary

One lead, one reviewer. The split is not negotiable mid-phase, and neither
side may widen its own half.

## Claude is the sole lead

Claude owns, start to finish:

- planning and slicing the work
- writing the phase contract and getting the user's approval for it
- delegating to Claude subagents (implementation-engineer, verification-lead,
  release-gate-overseer, and the rest) — delegation is a Claude-only act
- implementing the change
- Tier-1 verification: running the repo's own tests, typecheck, and lint,
  and writing `claude-verdict.md` first, before anything Tier-2 happens
- every git operation, subject to the remote-operation boundary below
- reconciling the two evidence matrices
- the final release decision

## The remote-operation boundary

Local and remote are not the same authority, and treating them as one is how
an agent ends up publishing something nobody agreed to publish.

Claude owns the local half outright: planning, branch creation, staging,
committing, running the repo's own tests, code review, and gate runs. All of
it is local, all of it is reversible, and none of it is visible to anyone
outside this machine until someone publishes it.

Every remote-mutating operation is the other half. Pushing a branch; creating,
updating, marking ready, or merging a pull request; mutating an issue; pushing
a tag; cutting a release — each one routes through the GitHub workflow manager
agent, which is the gate for exactly these operations, and each one requires
explicit human authorization before it happens. Not inferred consent, not "the
phase is green so this was obviously wanted", not an earlier approval reused
for a later operation. A green gate is evidence. It is not permission.

Codex performs no git operation and no GitHub operation of any kind. It does
not stage, commit, branch, push, tag, open, comment on, review, or merge
anything. It is handed evidence on stdin and it emits text. That is the whole
of its reach.

## Codex is an independent read-only reviewer, and nothing else

Codex is invoked only through `~/.claude/harness/codex-gate.sh` and
`~/.claude/skills/verified-phase/scripts/clarify-challenge.sh`. Inside either
one it runs sandboxed read-only, judges what it was piped, and emits one file.
That is its entire role.

Codex never:

- delegates to anything, or is asked to
- modifies, creates, or deletes a file in the repository
- expands scope, proposes refactors, or fixes what it finds
- amends, reinterprets, or "clarifies" the contract — where the contract and
  the code disagree, that is a finding, not a repair job
- commits, branches, pushes, or opens a PR
- decides whether the phase ships

A Codex PASS is one input to reconciliation. It never overwrites a Claude
FAIL, and it is never the release decision.

## Two sanctioned entry points, both of them scripts

The skill wraps existing machinery; it does not reimplement any of it. The
reviewer CLI is reached from exactly two places, and from no Bash call an
agent writes:

1. `~/.claude/harness/codex-gate.sh` — Tier-2 verification of a phase.
2. `~/.claude/skills/verified-phase/scripts/clarify-challenge.sh` — the one
   clarification of a bounded evidence challenge, added by this skill. It
   mirrors the harness call flag for flag: foreground, ephemeral, read-only
   sandbox, user config and rules ignored, strict config. Its prompt is fixed
   in the script and its only input is the frozen discrepancy packet on
   stdin. It takes two arguments and offers no passthrough for either.

Two consequences, both hard rules:

1. Never call the `codex` CLI directly from Bash. A PreToolUse drift guard
   denies it by default, and the deny is correct — the harness carries the
   auth fail-closed check, the clean-tree check, the ordering guard, the
   verdict validator, and the provenance footer. A direct call carries none
   of those.
2. Never rewrite the harness, the verdict schema, or the AC-ID grammar from
   inside this skill. If the harness refuses, fix the stated cause.

## AGENTS.md is the reviewer's file, not a Claude agent definition

`AGENTS.md` at the repo root is what Codex reads to learn its role. It is not
a Claude subagent definition and it is not a place to put phase content.

`bootstrap-repo.sh` places it only when absent. If a user-authored `AGENTS.md`
already exists in any form, it is left byte-identical and reported as
untouched. Overwriting someone's reviewer instructions to make a gate pass is
the exact drift this whole apparatus exists to catch.

## Threat model: cooperative drift, not adversary

This is a drift gate over cooperative agents that make mistakes, anchor on
their own earlier reasoning, and quietly widen scope. It is not an
adversarial security boundary.

Honest limits, stated once so nobody over-trusts the result:

- read-only Codex can still read the filesystem and reach committed history;
  the harness removes the incidental channels (it moves both verdict files
  out of the tree during the run) and instructs against exploration, but it
  does not claim cryptographic isolation
- the same limit applies to the clarification call, and more sharply. It
  SUPPLIES only the packet; it does not make the packet the only thing the
  reviewer can SEE. "Do not read files, do not explore" is an instruction,
  not isolation, and a packet-only property is a drift guard over what the
  call hands over. A real boundary needs a different mechanism, not a longer
  prompt
- shell indirection can bypass the drift guard; real isolation for a
  write-capable reviewer means a separate terminal, not a hook
- two agreeing verdicts mean two independent readings agreed, not that the
  code is correct

Treat the gate's output as evidence with provenance, weighted the way
verification-lead weights it: reproduced version-matched observation first,
version-matched docs and confirmed issue reports second, model memory last.
