# Phase <ID>: <name>

## Goal
One paragraph. What exists at the end of this phase that doesn't now.

## Non-goals
Explicitly out of scope. The verifier treats work here as UNSTATED-RISK.

## Acceptance criteria
Each criterion has a unique, stable `AC-<number>` ID and must be checkable
from command output or a file:line. IDs are never renumbered after approval.

AC-01: `<command>` exits 0 and its output shows <observable fact>.
AC-02: <behavior> is exercised by <test file> and passes.

## Interface commitments
Signatures, schemas, routes, or events later phases may depend on.
Changing these after phase exit requires a new contract, not a patch.

## Deviation log
| Deviation | Reason | Approved |
|---|---|---|
