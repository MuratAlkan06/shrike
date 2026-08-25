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
3. The reviewer stays read-only throughout. A clarification is delivered by
   `clarify-challenge.sh`, which runs the reviewer in the same clean room the
   gate harness uses: foreground, ephemeral, read-only, strict config, user
   config and rules ignored.
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

## Step 2 — one clarification, one call

    ~/.claude/skills/verified-phase/scripts/clarify-challenge.sh \
        docs/phases/<phase> <AC-ID>

Two arguments, and no others. There is no prompt override and no argument
passthrough: the prompt is fixed inside the script, and the only
caller-controlled bytes that reach the reviewer are the packet's own, handed
to it on stdin. Run it in FOREGROUND Bash — a backgrounded run is the
empty-output condition of upstream issue codex#19945, and empty output is
inconclusive here as well.

Before it calls anything, the script re-verifies the packet against the
artifacts the packet names: contract, both verdicts, candidate, disputed
criterion. A packet whose recorded hashes no longer match the files on disk is
not a frozen record of anything, and nothing in this protocol repairs one.

| STATUS | exit | meaning |
|---|---|---|
| `CLARIFY-OK` | 0 | a reply was published — not "the disagreement is settled" |
| `CLARIFY-EXISTS` | 1 | a clarification is already on record; it is never overwritten |
| `CLARIFY-ADJUDICATED` | 1 | `challenge-result.md` exists; the challenge is closed |
| `CLARIFY-PACKET-MISMATCH` | 1 | the packet no longer matches what it records |
| `CHALLENGE-INCOMPLETE` | 1 | no packet, or a provenance field still unfilled |
| `CLARIFY-INCONCLUSIVE` | 1 | the reviewer failed, timed out, or replied with nothing |
| `CLARIFY-AUTH` | 4 | an API-key variable is defined, or the CLI is off the subscription |
| `USAGE` | 2 | anything other than exactly two arguments |

Only a PUBLISHED `codex-clarification.md` spends the one opportunity. An
authentication refusal, a reviewer failure and an empty reply publish nothing
and consume nothing — fix the cause and rerun. What is never rerun is a call
that already published.

The reply is published verbatim: nothing edits, trims, reformats or
re-sections it. A `clarify-provenance:` footer is appended below it, binding
the file to the packet it answered — `packet_sha256`, `disputed_criterion`,
`candidate_sha`, `base_sha`, `contract_sha256`, the reviewer version, the
model and flags, and the clarifier version and time. The script does not judge
what it published. `validate-phase.sh` does, and it recomputes that footer
against the packet on disk; a reply sitting beside a packet it did not answer
is refused as `CLARIFICATION-TAMPERED`.

The reply must contain exactly these four sections, in this order, and nothing
else at that heading level:

    ## Claim
    ## Supporting evidence
    ## What would falsify this
    ## Verification command

`validate-phase.sh` reads the headings of the published body — everything above
the provenance footer — and refuses anything else with
`STATUS: CLARIFICATION-INVALID`: a fifth section, a missing one, the same four
shuffled. The wording of each heading is the reviewer's; what the section is
about is not, so the match is on the keyword and is case-insensitive.

The command section contains one command and nothing else — no prose, no
alternatives, no fallback — or the single word `none`. `validate-phase.sh`
counts the non-empty lines in that section, ignoring the provenance footer
below it, and rejects the file when there is more than one. A reviewer who
needs three commands to make a point does not have evidence yet.

`none` is a valid and expected answer. It means no single command settles the
question, and it counts as zero commands, because there is nothing for the
verification lead to run. It has to be the bare word, alone on its line — any
case, but nothing around it. A `none` wrapped in backticks is markdown about
the word, and `none.` is a sentence; both are refused as
`CLARIFICATION-INVALID` rather than quietly rescored, because otherwise
punctuation decides whether the verification lead runs something.

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

If the clarification names `none`, if the command fails any of those tests, or
if no clarification could be obtained at all, then no command is run. Record
why — in words, in `challenge-result.md` — and adjudicate on what is already
on record.

Write `challenges/<AC-ID>/challenge-result.md` containing the command that
was run and its verbatim output (or the stated reason none was run), the
adjudication and its reasoning, and:

    discrepancy_sha256: <sha256 of discrepancy.md>
    clarification_sha256: <sha256 of codex-clarification.md, or 'absent'>

`clarification_sha256: absent` is only ever written together with the reason
it is absent. Absent without a reason is an unexplained hole in the record: a
later reader cannot tell a reviewer that declined to answer from a call that
was never made, and the two mean very different things about how much the
adjudication is worth.

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

The disagreement stays UNRESOLVED unless evidence already on record settles
the disputed fact deterministically. "The reviewer did not reply, so my
verdict stands" is not an adjudication; it is the absence of one.

### What an absent clarification is not

A challenge with no clarification is not a complete debate loop. It is one
side's packet and no reply, and it should never be described as though the
reviewer had been heard and answered.

It can still be adjudicated — but only from evidence already recorded before
the challenge was raised, and only when that evidence settles the disputed
fact deterministically. If it does not, the row is UNRESOLVED, and UNRESOLVED
is a real outcome rather than a failure to try hard enough.

The AC-01 challenge under `docs/phases/02-validator-harness-alignment/` is
that shape, and should be read as exactly that: an
adjudication-from-initial-evidence in which the reviewer never replied. The
two verdicts and the committed diff settled the disputed fact between them.
Citing it as a worked example of the clarification loop would misdescribe
what happened, because the loop's middle step never ran.

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
