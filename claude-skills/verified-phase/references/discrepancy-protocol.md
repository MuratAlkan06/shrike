# Discrepancy protocol — the bounded evidence challenge

Two independent verifiers will sometimes contradict each other on the same
criterion. Left unstructured, that becomes a debate, and the more fluent
arguer wins. This protocol bounds it: one packet, one reply, one adjudication,
then it is over.

## Invariants

1. The initial verdicts are produced independently and are preserved
   unchanged, forever. Nothing in this protocol edits `claude-verdict.md` or
   `codex-verdict.md`. A challenge adds files; it never rewrites evidence.
2. One challenge per (candidate commit, criterion). Ever. There is no second
   round, no appeal, no "one more look".
3. The reviewer stays read-only throughout. A clarification is delivered
   through the same harness path as the original verdict.
4. Nothing here converts CANNOT-VERIFY or UNRESOLVED into a pass.

## What is NOT a trigger

- a Codex CANNOT-VERIFY row — that is an evidence gap; use the single
  evidence-augmentation rerun in `phase-workflow.md`, which is a different
  mechanism with a different purpose
- a stylistic objection, a naming preference, a suggested refactor
- an UNSTATED-RISK note that no criterion covers
- both verdicts agreeing but one being unhappy about it

## The only trigger

A material PASS/FAIL disagreement on the same AC-ID, or on the overall
release outcome. Both sides claim something about the same observable fact
and cannot both be right.

## Step 1 — the packet

    ~/.claude/skills/verified-phase/scripts/validate-phase.sh \
        docs/phases/<phase> --new-challenge <AC-ID> <candidate-sha>

Creates `docs/phases/<phase>/challenges/<AC-ID>/discrepancy.md` from
`assets/discrepancy-template.md`, with a frozen provenance block already
filled in:

    disputed_criterion, base_sha, candidate_sha, contract_sha256,
    claude_verdict_sha256, codex_verdict_sha256

The command refuses with `STATUS: CHALLENGE-EXISTS` (nonzero) if a challenge
directory already exists for that criterion. If the candidate commit changed,
a human moves the old packet to `challenges/archive/<old-sha>/<AC-ID>/`
before a new one is raised — the script will not do it for you.

Claude then fills in the prose sections and nothing else:

- the frozen criterion text, verbatim from the contract
- both verdicts and both cited pieces of evidence, verbatim
- the exact factual disagreement, in one sentence, naming the observable
  fact — not who is right

The packet is neutral. It argues for neither side and proposes no remedy. If
you cannot state the disagreement without arguing, you do not yet understand
it.

## Step 2 — one clarification

The reviewer gets exactly one opportunity to respond, delivered through the
harness with the packet as its evidence, still sandboxed read-only. The reply
is written to `challenges/<AC-ID>/codex-clarification.md` and must contain
four sections and no more:

    ## Claim
    ## Supporting evidence
    ## What would falsify this claim
    ## Verification command (at most one)

The command section contains the command and nothing else — no prose, no
alternatives, no fallback. `validate-phase.sh` counts the non-empty lines in
that section and rejects the file with `STATUS: CLARIFICATION-INVALID` when
there is more than one. A reviewer who needs three commands to make a point
does not have evidence yet.

There is no second reply. Not a follow-up question, not a "can you also
check". If the clarification does not settle it, the protocol is finished and
the row is UNRESOLVED.

## Step 3 — adjudication

Claude's verification lead runs the named command, and only that command,
and only if it is:

- deterministic and reproducible
- inside the phase scope
- safe: no writes outside the work tree, no network mutation, no credential
  use, nothing that changes the candidate

If the command fails any of those, it is not run; record why, and adjudicate
on the packet alone.

Write `challenges/<AC-ID>/challenge-result.md` containing the command that
was run, its verbatim output, the adjudication and its reasoning, and:

    discrepancy_sha256: <sha256 of discrepancy.md>
    clarification_sha256: <sha256 of codex-clarification.md, or 'absent'>

Those two hashes are the tamper-evidence. On every later validation the
script recomputes them and refuses with `STATUS: CHALLENGE-TAMPERED` if
either artifact changed after adjudication. It also chmods the three files
`a-w` — belt and braces; the hashes are the real check, since a permission
bit is trivially reversible and a hash mismatch is not.

Outcomes:

- the evidence sustains Claude's verdict -> that verdict stands
- the evidence sustains the reviewer's verdict -> Claude's verdict is
  corrected in `decision.md`, with the challenge cited as provenance. The
  original `claude-verdict.md` still stands unedited; being wrong on the
  record is the point of keeping it.
- inconclusive -> UNRESOLVED

## Step 4 — UNRESOLVED

An UNRESOLVED row blocks the release. Two ways out, both human:

1. a human decides, and the work continues on that decision
2. a human waives it in `decision.md`:

       WAIVED-BY: <name> <date> — <reason>

No agent writes that line. `--release-check` rejects a waiver whose signer
reads as claude, codex, or agent, and it never converts a non-pass row into a
pass to unblock itself.

## Why bounded

An unbounded exchange between two agents converges on whichever one writes
more confidently, not on what is true. One packet, one reply, one command,
one adjudication — that is enough to resolve a genuine factual dispute, and
too little to sustain a rhetorical one.
