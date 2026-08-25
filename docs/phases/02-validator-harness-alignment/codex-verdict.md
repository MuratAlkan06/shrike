| exact AC-ID | PASS or FAIL or CANNOT-VERIFY | Evidence |
|---|---|---|
| AC-01 | PASS | `claude-skills/verified-phase/scripts/validate-phase.sh:178-240` parses the last delimited footer, accepts line-1 binding, checks agreement, and emits `VERDICT-UNBOUND`; `:379-382` enables footer binding only for `codex-verdict.md`. |
| AC-02 | PASS | `claude-skills/verified-phase/scripts/validate-phase.sh:500-525` detects missing contract IDs, selects `DECISION-MISSING-ROW`, and calls `die`; TYPECHECK lines 143-148 confirm non-zero refusal and the distinct status. |
| AC-03 | PASS | `claude-skills/verified-phase/scripts/validate-phase.sh:496-525` computes unknown IDs and selects `DECISION-UNKNOWN-ROW`; TYPECHECK lines 149-151 confirm non-zero refusal and the status. |
| AC-04 | PASS | `claude-skills/verified-phase/scripts/validate-phase.sh:490-525` detects duplicate IDs and prioritizes `DECISION-DUPLICATE-ROW`; TYPECHECK lines 152-157 confirm refusal, status, and precedence. |
| AC-05 | PASS | `claude-skills/verified-phase/scripts/validate-phase.sh:205-240` requires line-1 `HEAD:` whenever footer binding is not enabled, while `:379-382` passes the footer option only for `codex-verdict.md`, leaving `claude-verdict.md` line-1-bound. |
| AC-06 | PASS | TYPECHECK tail: `PASS: 166 verified-phase checks`; command exit status is `0`. The total 166 strictly exceeds 109. |
| AC-07 | PASS | Every shown changed path is within the permitted sets: `.codex-gate.conf:8`, `claude-skills/README.md:50-64`, `claude-skills/tests/verified-phase-tests.sh:40,525-849`, `claude-skills/verified-phase/references/phase-workflow.md:159-212`, `claude-skills/verified-phase/scripts/validate-phase.sh:159-525`, and `docs/phases/02-validator-harness-alignment/**`. |
| AC-08 | PASS | TYPECHECK line `ok 161 - all scripts: bash -n clean, /bin/bash shebang, strict mode, no bash 4+ constructs`; command exit status is `0`. |
| AC-09 | PASS | `claude-skills/README.md:50-64` documents both bindings and all three decision statuses; `claude-skills/verified-phase/references/phase-workflow.md:159-212` documents footer boundaries, binding behavior, matrix completeness, and status precedence. |
| AC-10 | PASS | LINT output identifies `docs/phases/01-verified-phase-skill`, reports `STATUS: RELEASABLE`, and has exit status `0`. |

## UNSTATED-RISK

- `claude-skills/verified-phase/scripts/validate-phase.sh:211` accepts uppercase hexadecimal bindings, but `:220-224` compares line-1 and footer SHAs case-sensitively. Equivalent SHA spellings with different letter case would be reported as conflicting.
---
provenance:
  head: b93fd1ab3a1991bca0bc35eea6393893569c15e5
  base: 3d6ea7826a526697a0203c6ddc54931a4d02eb43 (merge-base of 3d6ea78)
  contract_sha256: f40518fd51f4c482c51d44a7aaffebf9d184f884e473e1fe288af71b6f6e9c95
  gate_conf_sha256: f838d9ced520be1ce604de772a0d51bf00cb7ec9fe8bf4042ed27df49287d9ca
  evidence_sha256: 52e7e20d03be7898c371b136539f5bc435a496bc7d0d91d1442dacbeadf686ac
  full_log_sha256: d24977d2c9131cda8ac0fa83243bf98f81d52408ec0cdadf82ba10753ae15791
  codex: codex-cli 0.149.1
  model: gpt-5.6-sol / effort=high / sandbox=read-only / ephemeral / clean-room
  harness: codex-gate.sh v2.1.1 (2026-08-25T02:12:42Z)
