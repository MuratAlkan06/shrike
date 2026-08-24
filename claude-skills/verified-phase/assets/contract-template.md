# Phase <ID>: <name>

## Base
Recorded before implementation starts, on a clean tree.

Base SHA: <40-hex output of `git rev-parse HEAD` at phase start>
Base ref: <branch or tag the gate uses as base-ref, usually main>

## Goal
One paragraph. What exists at the end of this phase that does not now.

## Non-goals
Explicitly out of scope. The verifier treats work here as UNSTATED-RISK.

## Acceptance criteria
Each criterion has a unique, stable `AC-<number>` ID and must be checkable
from command output or a file:line. IDs are never renumbered after approval.
Every line that begins with those two letters must use the `AC-<digits>:`
form, or the gate harness refuses the contract.

AC-01: `<command>` exits 0 and its output shows <observable fact>.
AC-02: <behavior> is exercised by <test file> and passes.

## Evidence requirements
One row per criterion. The evidence a verifier may cite is limited to what
this table names; anything else is CANNOT-VERIFY, never a guess.

| Criterion | Required evidence | Command or artifact |
|---|---|---|
| AC-01 | command exit status and named output line | `<command>` |
| AC-02 | test name and result line | `<test command>` |

## Interface commitments
Signatures, schemas, routes, or events later phases may depend on.
Changing these after phase exit requires a new contract, not a patch.

## Deviation log
| Deviation | Reason | Approved |
|---|---|---|
