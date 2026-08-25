# claude-skills

Personal [Claude Code skills](https://code.claude.com/docs/en/skills) kept
under version control here, where they can be reviewed and tested, and
installed from here into `~/.claude/skills/`. This directory is the canonical
copy; the installed one is a mirror.

## What is here

| Skill | Purpose |
|---|---|
| `verified-phase/` | Runs one contract-bound development phase with two-tier verification: Claude leads and verifies (Tier 1), the read-only Codex gate harness reviews independently (Tier 2), and the two evidence matrices are reconciled before any release decision. Explicit invocation only. |

`verified-phase` wraps the existing v2.1.1 gate machinery
(`~/.claude/harness/codex-gate.sh`, `AGENTS.md`, `.codex-gate.conf`,
`docs/phases/`). It does not reimplement any of it, and it never calls the
reviewer CLI directly.

## Install

    claude-skills/install.sh

Copies `verified-phase/` to `~/.claude/skills/verified-phase/` and makes its
scripts executable. Reports one of:

- `STATUS: INSTALLED` — no copy was there
- `STATUS: UP-TO-DATE` — the installed copy already matches; nothing changed
- `STATUS: REPLACED` — the installed copy had drifted; it was backed up to
  `~/.claude/skill-backups/verified-phase-<UTC timestamp>/` and then mirrored

Nothing else under `~/.claude` is created, read, or modified. The installer
honours `$HOME`, which is how the tests run it against a sandbox.

Then invoke the skill explicitly in Claude Code:

    /verified-phase <what the phase should deliver>

## Test

    /bin/bash claude-skills/tests/verified-phase-tests.sh

Every case runs against a throwaway git repository and a fake `$HOME` under
`mktemp -d`; the real `$HOME` and this repository are never written to. The
suite covers installer semantics (install, idempotency, backup-and-replace),
repo-root resolution from a nested working directory, scaffolding
idempotency and preservation, preflight command inference and secret hygiene,
the read-only invariants, the bounded evidence challenge, and the release
check. It prints `PASS: <n> verified-phase checks` when it is green.

Two of those cases are worth naming, because they are what a validator gets
wrong quietly. A phase's reviewer verdict is bound to its commit by the
provenance footer the gate harness appends last (`head:`), not by line 1, so
the suite validates a verdict built in that layout — including one whose prose
quotes the footer format, which must not bind anything. A hand-written line-1
`HEAD: <sha>` still binds, and a file carrying both bindings must name one
commit. And the merged matrix in `decision.md` must carry one row for each
contracted criterion and no others — a repeated, invented, or absent row is
refused as `DECISION-DUPLICATE-ROW`, `DECISION-UNKNOWN-ROW`, or
`DECISION-MISSING-ROW`.

## Portability

Scripts target `/bin/bash` as shipped on macOS — bash 3.2. No `mapfile`, no
associative arrays, no `${var,,}`, no `&>>`. `shasum -a 256` is used where
`sha256sum` is unavailable, matching the harness.
