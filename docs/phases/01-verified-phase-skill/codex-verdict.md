| exact AC-ID | PASS or FAIL or CANNOT-VERIFY | Evidence |
|---|---|---|
| AC-01 | PASS | `claude-skills/verified-phase/SKILL.md:1-5` contains the required frontmatter. `TYPECHECK: ok 106` confirms both required fields; `ok 107` confirms no `context:` or `allowed-tools:` declaration. |
| AC-02 | PASS | `claude-skills/verified-phase/SKILL.md:9-18` uses `$ARGUMENTS`, requires asking the user when empty, and says the instructions stay active for the entire phase. Confirmed by `TYPECHECK: ok 108`. |
| AC-03 | PASS | `claude-skills/tests/verified-phase-tests.sh:309-337` scans for direct `codex exec/apply/resume`, prohibited write-capable flags, and harness routing. `TYPECHECK: ok 61` through `ok 64` report all checks passing. Harness-only routing is stated at `claude-skills/verified-phase/SKILL.md:22-25`. |
| AC-04 | PASS | `claude-skills/install.sh:19-26` defines the canonical source, destination, and backup root; `install.sh:60-72` implements unchanged `UP-TO-DATE`; `install.sh:74-93` backs up a differing install before replacement. The suite’s installer checks are included in the successful `PASS: 109 verified-phase checks` result. |
| AC-05 | PASS | `claude-skills/verified-phase/scripts/bootstrap-repo.sh:19-25` resolves the Git root and refuses outside a work tree; `bootstrap-repo.sh:41-69` skips every existing destination; `bootstrap-repo.sh:86-112` preserves an existing `AGENTS.md`. `TYPECHECK: ok 31-36` confirms preservation and the no-write refusal path. |
| AC-06 | PASS | `claude-skills/verified-phase/scripts/preflight.sh:73-93` emits distinct `MISSING-COMMANDS` and `AMBIGUOUS-COMMANDS` statuses; `preflight.sh:103-111` emits `DIRTY-BASE`; `preflight.sh:117-131` uses defined-only checks and prints names, not values. Confirmed by `TYPECHECK: ok 38`, `ok 40`, `ok 52-53`, and `ok 55-60`. |
| AC-07 | PASS | `claude-skills/verified-phase/scripts/validate-phase.sh:343-349` returns `CHALLENGE-EXISTS`; `validate-phase.sh:281-294` verifies adjudication hashes and returns `CHALLENGE-TAMPERED`; `validate-phase.sh:215-220` rejects multiple command lines; `validate-phase.sh:423-455` rejects agent waivers, makes FAIL non-waivable, and leaves UNRESOLVED/CANNOT-VERIFY as non-pass rows; `validate-phase.sh:461-463` says the script never issues GO. Confirmed by `TYPECHECK: ok 73-97`. |
| AC-08 | PASS | `TYPECHECK` reports command `/bin/bash claude-skills/tests/verified-phase-tests.sh` with exit status `0`; its tail is `PASS: 109 verified-phase checks`. |
| AC-09 | PASS | `TESTS` identifies `mvn verify` with exit status `0` and ends with `[INFO] BUILD SUCCESS`. The DIFF header identifies the reviewed range and every displayed changed path is under `.codex-gate.conf`, `claude-skills/**`, or `docs/phases/**`. |
| AC-10 | PASS | Bash shebang and strict mode appear at `claude-skills/install.sh:1,17`, `claude-skills/tests/verified-phase-tests.sh:1,8`, `bootstrap-repo.sh:1,14`, `preflight.sh:1,15`, and `validate-phase.sh:1,18`. `TYPECHECK: ok 104` reports bash syntax, shebang, strict-mode, and Bash-3.2 compatibility checks passing. |

## UNSTATED-RISK

- `claude-skills/verified-phase/scripts/validate-phase.sh:400-420` parses decision rows but does not compare them with the contract’s complete criterion set or reject duplicates. Consequently, `validate-phase.sh:422-463` can report `RELEASABLE` when `decision.md` omits a contracted criterion or supplies duplicate rows.
---
provenance:
  head: 58023c4630f7a22afdbaafaf69770f202abb0c98
  base: ff290ad70d15a32ca530f96db02ba7fd620a9693 (merge-base of ff290ad)
  contract_sha256: dd69b6ef01d5f390fca24bb9dbea90d2b341ee0ca6ddb9d4f9fb782f798f1ebc
  gate_conf_sha256: ea08b19085a5ad3452a6d5dae4ba51cfe6d973e7b0f893e1910883b9d9326a5c
  evidence_sha256: 3a8395388d76f07c7beb3e988c1fe945e7af50105a499b40e7ac5dc0718cab5f
  full_log_sha256: 1daa0ebb071a107a803cdde4b16ad4759e3b3c120b4a88525ab36e03b96f5503
  codex: codex-cli 0.149.1
  model: gpt-5.6-sol / effort=high / sandbox=read-only / ephemeral / clean-room
  harness: codex-gate.sh v2.1.1 (2026-08-24T21:08:19Z)
