| exact AC-ID | PASS or FAIL or CANNOT-VERIFY | Evidence |
|---|---|---|
| AC-01 | PASS | Exact arity, fixed prompt, and input constraints are implemented at `claude-skills/verified-phase/scripts/clarify-challenge.sh:36-89`; the read-only invocation is at `claude-skills/verified-phase/scripts/clarify-challenge.sh:315-334`. TYPECHECK exit 0 includes `ok 243`–`ok 245`, confirming byte-identical packet input, no argument passthrough, and the clean-room read-only invocation. |
| AC-02 | FAIL | Existing files are checked before invocation and publication uses no-clobber `ln`, but the post-call adjudication check and publication are separate operations at `claude-skills/verified-phase/scripts/clarify-challenge.sh:360-390`. If `challenge-result.md` appears after the final `[ -e "${RESULT}" ]` check but before `ln`, the clarification is published into an adjudicated challenge instead of returning `CLARIFY-ADJUDICATED`. TYPECHECK `ok 300`–`ok 306` tests adjudication earlier during the reviewer call, not this remaining interval. |
| AC-03 | PASS | Artifact hashes, criterion, and candidate bindings are rechecked at `claude-skills/verified-phase/scripts/clarify-challenge.sh:145-275`; mismatches use `CLARIFY-PACKET-MISMATCH`. The footer is emitted at `claude-skills/verified-phase/scripts/clarify-challenge.sh:340-358` and verified at `claude-skills/verified-phase/scripts/validate-phase.sh:411-452`. TYPECHECK exit 0 includes `ok 256`, `ok 263`–`ok 274`, and `ok 286`–`ok 289`. |
| AC-04 | PASS | Verbatim publication plus footer is at `claude-skills/verified-phase/scripts/clarify-challenge.sh:340-358`; structure and command counting are enforced at `claude-skills/verified-phase/scripts/validate-phase.sh:316-410`. UNRESOLVED handling is documented at `claude-skills/verified-phase/references/discrepancy-protocol.md:145-186`. TYPECHECK exit 0 includes `ok 246`, `ok 254`–`ok 262`, and `ok 290`–`ok 294`. |
| AC-05 | PASS | Authentication refusal, reviewer failure, and empty-output paths publish nothing at `claude-skills/verified-phase/scripts/clarify-challenge.sh:293-352`. Absent-with-reason and incomplete-loop rules are documented at `claude-skills/verified-phase/references/discrepancy-protocol.md:145-186`. The complete suite exited 0 with `PASS: 317 verified-phase checks`. |
| AC-06 | PASS | Defined-only checks use `${OPENAI_API_KEY+x}` and `${CODEX_API_KEY+x}`, print only variable names, and suppress captured login output at `claude-skills/verified-phase/scripts/clarify-challenge.sh:293-313`. The complete suite exited 0 with `PASS: 317 verified-phase checks`. |
| AC-07 | PASS | Installer symlink refusal is at `claude-skills/install.sh:19-32`; suite refusal is at `claude-skills/tests/verified-phase-tests.sh:10-33`; the isolated source copy is established at `claude-skills/tests/verified-phase-tests.sh:95-111`. TYPECHECK exit 0 includes `ok 307`–`ok 317`. |
| AC-08 | PASS | The regression fixture creates a link to the live source, invokes both entry points, and checks the live `SKILL.md` hash afterward at `claude-skills/tests/verified-phase-tests.sh:1137-1657`. TYPECHECK exit 0 reports `ok 307`–`ok 316`, including both refusal statuses and both byte-unchanged assertions. |
| AC-09 | PASS | The one-shot loop and absent-with-reason rule are documented at `claude-skills/verified-phase/SKILL.md:80-100` and `claude-skills/verified-phase/references/discrepancy-protocol.md:61-186`. Remote mutation authorization and the reviewer’s prohibition from git/GitHub operations are documented at `claude-skills/verified-phase/references/authority-boundary.md:17-49`. |
| AC-10 | PASS | TYPECHECK exited 0 with `PASS: 317 verified-phase checks`, strictly exceeding 166. The DIFF file list contains only `claude-skills/**` and `docs/phases/03-clarification-and-hardening/contract.md`; it shows no v2.1.1 file. The complete diff shows only two pre-existing assertion-label replacements, with their checks retained or strengthened. LINT exited 0 with `STATUS: RELEASABLE`. |

## UNSTATED-RISK

- The packet is validated before the call, but the live `discrepancy.md` is later reopened for stdin and hashed again for the footer without a snapshot or equality recheck (`claude-skills/verified-phase/scripts/clarify-challenge.sh:145-275`, `claude-skills/verified-phase/scripts/clarify-challenge.sh:315-358`). A concurrent packet replacement can therefore make the reviewed bytes and the footer-bound bytes differ.
---
provenance:
  head: 4b3a574c3651c7ef71ff5dd6ee4e4000a49ab5fe
  base: 5803432e5b28579d8800eb0a3302c19a4d4c4fa3 (merge-base of 5803432)
  contract_sha256: 9fa99a6ebf0afc74ba42e19a2621beefe59989938d208c11ef7e6f6e5c4a91eb
  gate_conf_sha256: f838d9ced520be1ce604de772a0d51bf00cb7ec9fe8bf4042ed27df49287d9ca
  evidence_sha256: 0c2fcfc3e0ea26fcc5bc20a12089102e613ddf03ead6527a22c198157734fb3e
  full_log_sha256: 57aaad8f44dba2c00715fc3063bb0cffc1fe94fd957af7f060984fee20b38d88
  codex: codex-cli 0.149.1
  model: gpt-5.6-sol / effort=high / sandbox=read-only / ephemeral / clean-room
  harness: codex-gate.sh v2.1.1 (2026-08-25T17:29:29Z)
