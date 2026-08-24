# Phase workflow — full detail

The numbered steps in SKILL.md are the contract. This file is the detail
behind each one. Paths below assume the skill is installed at
`~/.claude/skills/verified-phase/`.

## 1. Resolve the real repository root

Claude is often started in a nested directory. Every phase artifact belongs to
the repository, never to `$HOME` and never to whatever directory happened to
be current.

    git rev-parse --show-toplevel

All three scripts do this themselves and refuse, writing nothing, when the
current directory is not inside a git work tree (`STATUS: NOT-A-GIT-REPO`,
exit 2). If that happens, the fix is to move into the repository — never to
create one, and never to fall back to `$HOME`.

## 2. Inspect what already exists

Read before writing:

- `docs/phases/` — prior phases, their contracts, their verdicts. Reuse the
  existing numbering and naming.
- `.codex-gate.conf` — the repo's evidence commands, if already chosen.
- `AGENTS.md` — the reviewer's instruction file. Read it; never rewrite it.
- the repo's CI config — the Tier-1 commands should be the CI commands.

If prior phases exist, match their conventions instead of inventing new ones.

## 3. Bootstrap missing scaffolding

    ~/.claude/skills/verified-phase/scripts/bootstrap-repo.sh

Places only what is absent: `docs/phases/_TEMPLATE/contract.md`,
`docs/phases/_TEMPLATE/claude-verdict.TEMPLATE.md`, a commented
`.codex-gate.conf`, and — only when there is none — `AGENTS.md`. Existing
files are reported as `SKIP (exists, untouched)` and are not read into,
appended to, or reformatted. A second run is all SKIP and exits 0.

It never edits `.gitignore`. When `docs/phases/**/evidence-full.log` is not
ignored it prints a `NOTE:` line; apply that yourself, because the full
evidence log is a local artifact and only its sha256 belongs in a verdict.

## 4. Preflight

    ~/.claude/skills/verified-phase/scripts/preflight.sh

Exit 0 means ready; exit 1 means something needs a decision; exit 2 means not
a git work tree. Branch on the status lines:

| Line | Meaning | What to do |
|---|---|---|
| `COMMANDS-STATUS: OK` | `.codex-gate.conf` has a `TEST_CMD` | use it as-is |
| `COMMANDS-STATUS: PROPOSED-COMMANDS` | exactly one build signal found | show the `PROPOSAL:` line and confirm with the user once |
| `COMMANDS-STATUS: MISSING-COMMANDS` | no signal | ask the user once which command proves the repo works |
| `COMMANDS-STATUS: AMBIGUOUS-COMMANDS` | two or more signals | show the candidates and ask the user once which is authoritative |
| `BASE-STATUS: DIRTY-BASE` | uncommitted work | commit or stash it; the base must describe the tree |
| `SECRETS-STATUS: SECRET-ENV-SET` | an API-key variable is defined | unset the named variable; never print or edit its value |
| `HARNESS-STATUS: MISSING-HARNESS` | no gate harness | Tier-2 cannot run; install the gate integration before promising two tiers |
| `AGENTS-STATUS: MISSING-AGENTS-MD` | no reviewer instructions | run bootstrap first |

Inference only ever proposes. Preflight never writes `.codex-gate.conf`;
Claude writes it after the user confirms, and commits it.

Ask the user at most one time per unknown. Do not re-ask what preflight
already answered.

## 5. Scaffolding is complete, verified, and committed before the base

The base commit must describe the tree exactly. So, in this order:

1. finish and verify the scaffolding (`.codex-gate.conf` with a real
   `TEST_CMD`, `AGENTS.md`, templates, the `.gitignore` line)
2. run the chosen `TEST_CMD` once and confirm it works in this repo
3. commit the scaffolding
4. confirm `git status --porcelain` is empty
5. record that commit's sha as the phase base

Recording a base while the tree is dirty produces a contract that describes a
state no commit ever had. Do not do it.

## 6. Draft the contract, then get approval

Copy `~/.claude/skills/verified-phase/assets/contract-template.md` to
`docs/phases/<phase>/contract.md` and fill in:

- Base SHA and base ref (from step 5)
- goal, in one paragraph
- non-goals, explicitly — the reviewer treats out-of-scope work as
  UNSTATED-RISK
- acceptance criteria with stable IDs: every line starting with those two
  letters must be `AC-<digits>:`, or the harness refuses the contract with
  exit 2. IDs are never renumbered after approval; a dropped criterion stays
  in the file, struck through in prose, rather than shifting the numbering.
- per-criterion evidence requirements: for each ID, the command or artifact
  whose output settles it. A criterion nothing can settle is not a criterion.
- interface commitments later phases may depend on

Then stop and get the user's explicit approval. After approval the contract
is frozen: it is not amended to match what got built. Deviations go in the
deviation log and, if material, end the phase and start a new contract.

## 7. Implement and verify Tier-1

Claude orchestrates: delegate slices to implementation-engineer, review, and
run verification-lead for Tier-1. Then:

1. commit everything — the harness refuses a tree with any change outside the
   three generated gate artifacts
2. write `docs/phases/<phase>/claude-verdict.md` from
   `~/.claude/skills/verified-phase/assets/evidence-matrix-template.md`.
   Line 1 must be exactly `HEAD: <output of git rev-parse HEAD>`. One row per
   criterion; verdicts are `PASS`, `FAIL`, or `CANNOT-VERIFY`; evidence is a
   file:line or a command output line. "The code looks correct" is not
   evidence.
3. write your verdict BEFORE Tier-2 runs. The harness enforces the ordering
   and there is no bypass.

The commit whose sha is on line 1 is the candidate. It is fixed from here.

Structural self-check before the gate:

    ~/.claude/skills/verified-phase/scripts/validate-phase.sh docs/phases/<phase>

## 8. Tier-2: run the gate

From the repository root, in FOREGROUND Bash only:

    ~/.claude/harness/codex-gate.sh <phase> <base-ref> [paths]

Never run it backgrounded. The empty-output failure of upstream issue
codex#19945 reproduces under the setsid-equivalent condition that
backgrounding creates; the harness warns when it sees no controlling TTY, and
an empty verdict is INCONCLUSIVE, never a pass.

Exit codes:

| Exit | Meaning | Response |
|---|---|---|
| 0 | a verdict was produced | parse it — this is NOT "verification passed" |
| 1 | INCONCLUSIVE (crash, timeout, empty, or a verdict that failed structural validation) | rerun one time in the foreground; if it repeats, report INCONCLUSIVE — the gate cannot pass |
| 2 | usage or contract problem (missing contract, malformed AC-ID, no `TEST_CMD`) | fix the stated cause and rerun |
| 3 | ordering or cleanliness refusal (dirty tree, missing/stale `claude-verdict.md`) | commit the tree or rebind your verdict to the current HEAD |
| 4 | authentication refusal (an API-key variable is defined, or not logged in with ChatGPT) | unset the named variable, or log in; values are never printed |

Verdict files, both committed with the phase:

    docs/phases/<phase>/claude-verdict.md    Tier-1, written first
    docs/phases/<phase>/codex-verdict.md     Tier-2, published by the harness
    docs/phases/<phase>/evidence-full.log    local only, gitignored

The published Codex verdict carries a provenance footer: head and base shas,
sha256 of the contract, of `.codex-gate.conf`, of the evidence, and of the
full log. If a later dispute needs to know what was judged, those hashes are
the answer.

## 9. Reconcile

Per criterion, using both matrices:

- both PASS -> PASS
- both FAIL -> FAIL
- disagreement -> UNRESOLVED. Never average. Never let a Codex PASS overwrite
  a Claude FAIL. A material PASS/FAIL disagreement is the only thing that may
  open a challenge — see `discrepancy-protocol.md`.
- Codex CANNOT-VERIFY -> the evidence-augmentation rerun below

Write the merged matrix to `docs/phases/<phase>/decision.md`, one row per
criterion with per-row provenance, then run:

    ~/.claude/skills/verified-phase/scripts/validate-phase.sh --release-check docs/phases/<phase>

### The CANNOT-VERIFY evidence-augmentation rerun

This is a gap in the evidence, not a disagreement. It is handled entirely
separately from the challenge protocol, and it happens one time per criterion:

1. Read the exact command Codex named as the one that would settle it.
2. If that command is safe and inside the phase scope, add it to
   `.codex-gate.conf` (or widen the gate's pathspec) so the harness runs it
   and pipes its output as evidence.
3. Commit that configuration change.
4. Refresh your complete verdict and rebind line 1 to the new HEAD.
5. Rerun the gate one time.

If the row is still CANNOT-VERIFY, it is SINGLE-SOURCED. It counts toward a
release only with an explicit human waiver recorded in `decision.md`:

    WAIVED-BY: <name> <date> — <reason>

No agent grants that waiver, including Claude. `validate-phase.sh
--release-check` rejects a waiver whose signer reads as claude, codex, or
agent, and rejects any UNRESOLVED or CANNOT-VERIFY row that has none.

## 10. Decide

Claude alone issues the release decision, in the conversation, after
`--release-check` exits 0. The script reports `STATUS: RELEASABLE`; it never
says GO.

Blocking conditions, none of which are formalities:

- any UNRESOLVED row
- any unwaived CANNOT-VERIFY row
- any INCONCLUSIVE gate result
- a verdict whose `HEAD:` sha is not the commit under review
- a waiver signed by an agent

State the decision, the evidence behind it, and what remains unverified. An
honest "verified except AC-04, which is single-sourced and unwaived" is worth
more than a GO nobody can defend.
