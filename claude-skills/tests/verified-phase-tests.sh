#!/bin/bash
# Regression suite for the verified-phase skill and its installer.
#
# Every case runs against a throwaway git repository and a fake $HOME under a
# mktemp -d sandbox. The real $HOME and the host repository are never read
# from or written to. Script invocations happen from a NESTED subdirectory so
# that repo-root resolution is actually exercised rather than assumed.
set -euo pipefail

SKILLS_SRC="$(cd "$(dirname "$0")/.." && pwd -P)"
# pwd -P so the sandbox path matches what git rev-parse reports; on macOS
# /var is a symlink to /private/var and mktemp -d hands back the symlinked form.
TEST_ROOT="$(cd "$(mktemp -d)" && pwd -P)"
trap 'chmod -R u+w "${TEST_ROOT}" >/dev/null 2>&1 || true; rm -rf "${TEST_ROOT}"' EXIT

pass=0
fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { pass=$((pass + 1)); echo "ok ${pass} - $*"; }

RUN_STATUS=0
run_in() { # $1 dir, rest: command
  local dir="$1"
  shift
  set +e
  ( cd "${dir}" && "$@" ) > "${TEST_ROOT}/last.out" 2> "${TEST_ROOT}/last.err"
  RUN_STATUS=$?
  set -e
  cat "${TEST_ROOT}/last.out" "${TEST_ROOT}/last.err" > "${TEST_ROOT}/last.all"
}
dump_last() { sed -n '1,60p' "${TEST_ROOT}/last.all" >&2; }
expect_status() { # expected, label
  [ "${RUN_STATUS}" -eq "$1" ] || { dump_last; fail "$2: expected exit $1, got ${RUN_STATUS}"; }
  ok "$2"
}
expect_out() { # pattern, label
  grep -qE -- "$1" "${TEST_ROOT}/last.all" || { dump_last; fail "$2: no match for /$1/"; }
  ok "$2"
}
refute_out() { # pattern, label
  if grep -qE -- "$1" "${TEST_ROOT}/last.all"; then dump_last; fail "$2: unexpected /$1/"; fi
  ok "$2"
}
last_status() { sed -n 's/^STATUS: //p' "${TEST_ROOT}/last.all" | tail -n1; }
file_hash() { shasum -a 256 "$1" | cut -d' ' -f1; }
tree_hash() { # $1 dir — content + executability, path-ordered
  (
    cd "$1"
    find . -type f -print | LC_ALL=C sort | while IFS= read -r f; do
      if [ -x "${f}" ]; then bit=x; else bit=-; fi
      printf '%s %s %s\n' "$(file_hash "${f}")" "${bit}" "${f}"
    done
  ) | shasum -a 256 | cut -d' ' -f1
}
home_hash_except_skill() { # $1 fake home — everything under .claude the installer must not touch
  (
    cd "$1/.claude"
    find . -type f -not -path './skills/verified-phase/*' -not -path './skill-backups/*' \
      -print | LC_ALL=C sort | while IFS= read -r f; do
      printf '%s %s\n' "$(file_hash "${f}")" "${f}"
    done
  ) | shasum -a 256 | cut -d' ' -f1
}
new_repo() { # $1 path
  mkdir -p "$1/src/deep"
  git -C "$1" init -q -b main
  git -C "$1" config user.email test@example.invalid
  git -C "$1" config user.name 'Phase Test'
  printf 'seed\n' > "$1/README.md"
  git -C "$1" add README.md
  git -C "$1" commit -q -m init
}

# The sandbox must not itself sit inside a git work tree, or the
# "outside a repository" case would silently test nothing.
if git -C "${TEST_ROOT}" rev-parse --show-toplevel >/dev/null 2>&1; then
  fail "sandbox ${TEST_ROOT} is inside a git work tree; cannot test the refusal path"
fi
ok "sandbox is outside any git work tree"

# ---- 1. install: first run ---------------------------------------------------
H1="${TEST_ROOT}/home-a"
mkdir -p "${H1}/.claude/agents" "${H1}/.claude/skills/other-skill"
printf '{"keep":true}\n' > "${H1}/.claude/settings.json"
printf 'agent definition\n' > "${H1}/.claude/agents/keep.md"
printf 'other skill\n' > "${H1}/.claude/skills/other-skill/SKILL.md"

run_in "${SKILLS_SRC}" env HOME="${H1}" ./install.sh
expect_status 0 "install: first run exits 0"
expect_out 'STATUS: INSTALLED' "install: reports INSTALLED"
[ -f "${H1}/.claude/skills/verified-phase/SKILL.md" ] \
  || fail "install: SKILL.md not present at the installed path"
ok "install: SKILL.md present at \$HOME/.claude/skills/verified-phase/SKILL.md"
for s in bootstrap-repo.sh preflight.sh validate-phase.sh; do
  [ -x "${H1}/.claude/skills/verified-phase/scripts/${s}" ] \
    || fail "install: ${s} is not executable"
done
ok "install: all three scripts executable"
[ -f "${H1}/.claude/skills/verified-phase/references/discrepancy-protocol.md" ] \
  || fail "install: references/ not copied"
[ -f "${H1}/.claude/skills/verified-phase/assets/discrepancy-template.md" ] \
  || fail "install: assets/ not copied"
ok "install: references/ and assets/ copied"

# ---- 2. install: idempotent --------------------------------------------------
installed_before="$(tree_hash "${H1}/.claude/skills/verified-phase")"
run_in "${SKILLS_SRC}" env HOME="${H1}" ./install.sh
expect_status 0 "install: second run exits 0"
expect_out 'STATUS: UP-TO-DATE' "install: second run reports UP-TO-DATE"
installed_after="$(tree_hash "${H1}/.claude/skills/verified-phase")"
[ "${installed_before}" = "${installed_after}" ] \
  || fail "install: idempotent run changed the installed tree"
ok "install: idempotent run is byte-stable"
[ "${installed_after}" = "$(tree_hash "${SKILLS_SRC}/verified-phase")" ] \
  || fail "install: installed tree does not mirror the canonical source"
ok "install: installed tree mirrors the canonical source"

# ---- 3. install: replace a drifted copy --------------------------------------
untouched_before="$(home_hash_except_skill "${H1}")"
printf '\nLOCAL DRIFT\n' >> "${H1}/.claude/skills/verified-phase/SKILL.md"
run_in "${SKILLS_SRC}" env HOME="${H1}" ./install.sh
expect_status 0 "install: drifted copy exits 0"
expect_out 'STATUS: REPLACED' "install: drifted copy reports REPLACED"
expect_out "BACKUP: ${H1}/.claude/skill-backups/verified-phase-" "install: reports backup path"
backup_dir="$(ls -d "${H1}/.claude/skill-backups"/verified-phase-* | head -n1)"
[ -f "${backup_dir}/SKILL.md" ] || fail "install: backup does not contain SKILL.md"
grep -q 'LOCAL DRIFT' "${backup_dir}/SKILL.md" \
  || fail "install: backup does not preserve the drifted copy"
ok "install: drifted copy preserved under skill-backups/"
refute_out '\.claude/skills/verified-phase/skill-backups' "install: backup is not nested under skills/"
[ "$(tree_hash "${H1}/.claude/skills/verified-phase")" = "$(tree_hash "${SKILLS_SRC}/verified-phase")" ] \
  || fail "install: replaced tree does not match the canonical source"
ok "install: replaced tree matches the canonical source"
[ "${untouched_before}" = "$(home_hash_except_skill "${H1}")" ] \
  || fail "install: something else under ~/.claude changed"
ok "install: nothing else under \$HOME/.claude was touched"

BOOTSTRAP="${H1}/.claude/skills/verified-phase/scripts/bootstrap-repo.sh"
PREFLIGHT="${H1}/.claude/skills/verified-phase/scripts/preflight.sh"
VALIDATE="${H1}/.claude/skills/verified-phase/scripts/validate-phase.sh"

# A stand-in harness, so presence checks exercise both branches. Created by
# the test, never by the installer.
mkdir -p "${H1}/.claude/harness"
printf '#!/bin/bash\necho stand-in harness\n' > "${H1}/.claude/harness/codex-gate.sh"
chmod +x "${H1}/.claude/harness/codex-gate.sh"

# ---- 4. bootstrap writes to the repo root, from a nested directory -----------
R_BOOT="${TEST_ROOT}/repo-bootstrap"
new_repo "${R_BOOT}"
run_in "${R_BOOT}/src/deep" env HOME="${H1}" "${BOOTSTRAP}"
expect_status 0 "bootstrap: nested invocation exits 0"
expect_out 'STATUS: OK' "bootstrap: reports OK"
expect_out "REPO-ROOT: ${R_BOOT}" "bootstrap: resolved the real repo root"
for f in docs/phases/_TEMPLATE/contract.md docs/phases/_TEMPLATE/claude-verdict.TEMPLATE.md \
         .codex-gate.conf AGENTS.md; do
  [ -f "${R_BOOT}/${f}" ] || fail "bootstrap: did not place ${f} at the repo root"
done
ok "bootstrap: scaffolding placed at the repo root"
[ ! -e "${R_BOOT}/src/deep/docs" ] || fail "bootstrap: wrote into the current directory"
[ ! -e "${R_BOOT}/src/deep/AGENTS.md" ] || fail "bootstrap: wrote into the current directory"
ok "bootstrap: nothing written into the nested working directory"
[ ! -e "${H1}/docs" ] && [ ! -e "${H1}/AGENTS.md" ] \
  || fail "bootstrap: wrote into \$HOME"
ok "bootstrap: nothing written into \$HOME"
expect_out 'NOTE: add docs/phases/\*\*/evidence-full\.log to \.gitignore' \
  "bootstrap: reports the missing .gitignore entry instead of editing it"
grep -q '^AC-01:' "${R_BOOT}/docs/phases/_TEMPLATE/contract.md" \
  || fail "bootstrap: placed contract template lacks a stable AC-ID example"
ok "bootstrap: contract template carries harness-parseable AC-IDs"

# ---- 5. bootstrap preserves everything that already exists -------------------
R_KEEP="${TEST_ROOT}/repo-preserve"
new_repo "${R_KEEP}"
printf 'TEST_CMD="sentinel do not touch"\n' > "${R_KEEP}/.codex-gate.conf"
printf '# AGENTS.md\n\nsentinel reviewer instructions\n' > "${R_KEEP}/AGENTS.md"
printf 'work in progress\n' > "${R_KEEP}/src/deep/dirty.txt"
conf_hash="$(file_hash "${R_KEEP}/.codex-gate.conf")"
agents_hash="$(file_hash "${R_KEEP}/AGENTS.md")"
dirty_hash="$(file_hash "${R_KEEP}/src/deep/dirty.txt")"

run_in "${R_KEEP}/src/deep" env HOME="${H1}" "${BOOTSTRAP}"
expect_status 0 "bootstrap: first run over an existing setup exits 0"
expect_out 'SKIP \(exists, untouched\): AGENTS\.md' "bootstrap: leaves AGENTS.md untouched"
expect_out 'SKIP \(exists, untouched\): \.codex-gate\.conf' "bootstrap: leaves .codex-gate.conf untouched"

run_in "${R_KEEP}/src/deep" env HOME="${H1}" "${BOOTSTRAP}"
expect_status 0 "bootstrap: second run exits 0"
added="$(grep -c '^ADD:' "${TEST_ROOT}/last.all" || true)"
[ "${added}" = 0 ] || fail "bootstrap: second run added ${added} file(s); expected all SKIP"
ok "bootstrap: second run is all SKIP"
[ "${conf_hash}" = "$(file_hash "${R_KEEP}/.codex-gate.conf")" ] \
  || fail "bootstrap: rewrote an existing .codex-gate.conf"
[ "${agents_hash}" = "$(file_hash "${R_KEEP}/AGENTS.md")" ] \
  || fail "bootstrap: rewrote an existing AGENTS.md"
ok "bootstrap: pre-existing conf and AGENTS.md are byte-identical"
[ "${dirty_hash}" = "$(file_hash "${R_KEEP}/src/deep/dirty.txt")" ] \
  || fail "bootstrap: touched an unrelated dirty file"
ok "bootstrap: unrelated dirty file untouched"

# ---- 6. bootstrap outside a git work tree ------------------------------------
H_EMPTY="${TEST_ROOT}/home-empty"
mkdir -p "${H_EMPTY}/.claude"
NOT_REPO="${TEST_ROOT}/not-a-repo"
mkdir -p "${NOT_REPO}"
run_in "${NOT_REPO}" env HOME="${H_EMPTY}" "${BOOTSTRAP}"
expect_status 2 "bootstrap: outside a git work tree exits nonzero"
expect_out 'STATUS: NOT-A-GIT-REPO' "bootstrap: reports NOT-A-GIT-REPO"
[ -z "$(ls -A "${NOT_REPO}")" ] || fail "bootstrap: wrote into a non-repo directory"
ok "bootstrap: wrote nothing into the non-repo directory"
[ -z "$(ls -A "${H_EMPTY}/.claude")" ] || fail "bootstrap: wrote into \$HOME"
ok "bootstrap: wrote nothing into \$HOME"

# ---- 7. preflight: verification commands -------------------------------------
run_in "${R_BOOT}/src/deep" env HOME="${H1}" "${PREFLIGHT}"
expect_status 1 "preflight: bare repo needs attention"
expect_out 'COMMANDS-STATUS: MISSING-COMMANDS' "preflight: empty TEST_CMD is MISSING-COMMANDS"

R_AMBI="${TEST_ROOT}/repo-ambiguous"
new_repo "${R_AMBI}"
printf '<project/>\n' > "${R_AMBI}/pom.xml"
printf '{"scripts": {"test": "vitest run"}}\n' > "${R_AMBI}/package.json"
git -C "${R_AMBI}" add pom.xml package.json
git -C "${R_AMBI}" commit -q -m 'two build signals'
run_in "${R_AMBI}/src/deep" env HOME="${H1}" "${PREFLIGHT}"
expect_status 1 "preflight: ambiguous repo needs attention"
expect_out 'COMMANDS-STATUS: AMBIGUOUS-COMMANDS' "preflight: two signals is AMBIGUOUS-COMMANDS"
expect_out 'mvn verify' "preflight: lists the Maven candidate"
expect_out 'npm test' "preflight: lists the npm candidate"

R_MAVEN="${TEST_ROOT}/repo-maven"
new_repo "${R_MAVEN}"
printf '<project/>\n' > "${R_MAVEN}/pom.xml"
git -C "${R_MAVEN}" add pom.xml
git -C "${R_MAVEN}" commit -q -m 'one build signal'
run_in "${R_MAVEN}/src/deep" env HOME="${H1}" "${PREFLIGHT}"
expect_status 1 "preflight: proposal still needs confirmation"
expect_out 'COMMANDS-STATUS: PROPOSED-COMMANDS' "preflight: one signal proposes"
expect_out 'PROPOSAL: TEST_CMD="mvn verify"' "preflight: proposes mvn verify"
[ ! -f "${R_MAVEN}/.codex-gate.conf" ] || fail "preflight: wrote .codex-gate.conf"
ok "preflight: inference never writes .codex-gate.conf"

R_CONF="${TEST_ROOT}/repo-configured"
new_repo "${R_CONF}"
printf 'TEST_CMD="printf ok"\nTYPECHECK_CMD=""\nLINT_CMD=""\n' > "${R_CONF}/.codex-gate.conf"
printf '# AGENTS.md\nreviewer instructions\n' > "${R_CONF}/AGENTS.md"
git -C "${R_CONF}" add .codex-gate.conf AGENTS.md
git -C "${R_CONF}" commit -q -m scaffolding
run_in "${R_CONF}/src/deep" env HOME="${H1}" "${PREFLIGHT}"
expect_status 0 "preflight: configured clean repo is ready"
expect_out 'COMMANDS-STATUS: OK' "preflight: uses the configured TEST_CMD"
expect_out 'TEST_CMD: printf ok' "preflight: echoes the configured command"
expect_out 'STATUS: OK' "preflight: overall status OK"

# ---- 8. preflight: dirty base ------------------------------------------------
printf 'uncommitted edit\n' >> "${R_CONF}/README.md"
run_in "${R_CONF}/src/deep" env HOME="${H1}" "${PREFLIGHT}"
expect_status 1 "preflight: dirty base needs attention"
expect_out 'BASE-STATUS: DIRTY-BASE' "preflight: reports DIRTY-BASE"
expect_out 'STATUS: DIRTY-BASE' "preflight: DIRTY-BASE blocks overall"
expect_out 'M README\.md' "preflight: names the offending entry"
git -C "${R_CONF}" checkout -q -- README.md

# ---- 9. preflight: secret hygiene, names only --------------------------------
run_in "${R_CONF}/src/deep" env HOME="${H1}" OPENAI_API_KEY='sk-super-secret-sentinel' "${PREFLIGHT}"
expect_status 1 "preflight: a defined API key blocks"
expect_out 'SECRETS-STATUS: SECRET-ENV-SET' "preflight: flags the defined key"
expect_out 'OPENAI_API_KEY' "preflight: names the variable"
leaked="$(grep -c 'sk-super-secret-sentinel' "${TEST_ROOT}/last.all" || true)"
[ "${leaked}" = 0 ] || fail "preflight: leaked a secret value ${leaked} time(s)"
ok "preflight: the value never appears on stdout or stderr"
run_in "${R_CONF}/src/deep" env HOME="${H1}" CODEX_API_KEY= "${PREFLIGHT}"
expect_status 1 "preflight: defined-but-empty key still blocks"
expect_out 'CODEX_API_KEY is defined' "preflight: flags a defined-but-empty key by name"

# ---- 10. read-only invariants ------------------------------------------------
if grep -RInE '(^|[^a-zA-Z])codex[[:space:]]+(exec|apply|resume)' \
     "${SKILLS_SRC}/verified-phase/scripts" "${SKILLS_SRC}/install.sh" \
     > "${TEST_ROOT}/hits" 2>&1; then
  sed -n '1,20p' "${TEST_ROOT}/hits" >&2
  fail "scripts invoke the reviewer CLI directly"
fi
ok "scripts never invoke the reviewer CLI directly"
if grep -RIn -e '--sandbox workspace-write' -e '--dangerously' \
     "${SKILLS_SRC}/verified-phase" "${SKILLS_SRC}/install.sh" \
     > "${TEST_ROOT}/hits" 2>&1; then
  sed -n '1,20p' "${TEST_ROOT}/hits" >&2
  fail "skill mentions a write-capable or approval-bypassing sandbox flag"
fi
ok "no write-capable or approval-bypassing sandbox flags anywhere in the skill"
if grep -RInE '(^|[^a-zA-Z])codex[[:space:]]+(exec|apply|resume)' \
     "${SKILLS_SRC}/verified-phase/SKILL.md" "${SKILLS_SRC}/verified-phase/references" \
     > "${TEST_ROOT}/hits" 2>&1; then
  sed -n '1,20p' "${TEST_ROOT}/hits" >&2
  fail "SKILL.md or references instruct a direct reviewer CLI call"
fi
ok "SKILL.md and references never instruct a direct reviewer CLI call"
grep -q 'harness/codex-gate\.sh' "${SKILLS_SRC}/verified-phase/SKILL.md" \
  || fail "SKILL.md does not route Tier-2 through the harness"
grep -q 'harness/codex-gate\.sh' "${SKILLS_SRC}/verified-phase/references/phase-workflow.md" \
  || fail "phase-workflow.md does not route Tier-2 through the harness"
ok "Tier-2 is routed only through the gate harness"

# ---- 11. the bounded evidence challenge --------------------------------------
R_CH="${TEST_ROOT}/repo-challenge"
new_repo "${R_CH}"
CH_BASE="$(git -C "${R_CH}" rev-parse HEAD)"
mkdir -p "${R_CH}/docs/phases/07"
cat > "${R_CH}/docs/phases/07/contract.md" <<CONTRACT
# Phase 07: challenge fixture

## Base
Base SHA: ${CH_BASE}
Base ref: main

## Acceptance criteria
AC-01: the reaper observes the deadline and the log names it.
AC-02: the second behavior is exercised by a named test.
CONTRACT
git -C "${R_CH}" add docs/phases/07/contract.md
git -C "${R_CH}" commit -q -m 'phase 07 contract'
CH_CAND="$(git -C "${R_CH}" rev-parse HEAD)"
PD="${R_CH}/docs/phases/07"
cat > "${PD}/claude-verdict.md" <<VERDICT
HEAD: ${CH_CAND}

| Criterion | Verdict | Evidence |
|---|---|---|
| AC-01 | PASS | src/deep/reaper.txt:12 |
| AC-02 | PASS | test output line 40 |

UNSTATED-RISK: none
VERDICT
cat > "${PD}/codex-verdict.md" <<VERDICT
HEAD: ${CH_CAND}

| Criterion | Verdict | Evidence |
|---|---|---|
| AC-01 | FAIL | src/deep/reaper.txt:12 shows no deadline check |
| AC-02 | PASS | test output line 40 |

UNSTATED-RISK: none
VERDICT

run_in "${R_CH}/src/deep" env HOME="${H1}" "${VALIDATE}" docs/phases/07
expect_status 0 "validate: well-formed phase passes"
expect_out 'STATUS: OK' "validate: reports OK"
expect_out "BASE-SHA: ${CH_BASE}" "validate: reads the recorded base SHA"

run_in "${R_CH}/src/deep" env HOME="${H1}" "${VALIDATE}" docs/phases/07 \
  --new-challenge AC-99 "${CH_CAND}"
expect_status 1 "validate: challenge on an undeclared criterion refuses"
expect_out 'STATUS: UNKNOWN-CRITERION' "validate: reports UNKNOWN-CRITERION"

run_in "${R_CH}/src/deep" env HOME="${H1}" "${VALIDATE}" docs/phases/07 \
  --new-challenge AC-01 "${CH_CAND}"
expect_status 0 "validate: first challenge is created"
expect_out 'STATUS: OK' "validate: new-challenge reports OK"
CH_DIR="${PD}/challenges/AC-01"
[ -f "${CH_DIR}/discrepancy.md" ] || fail "validate: discrepancy.md not created"
grep -q "candidate_sha: ${CH_CAND}" "${CH_DIR}/discrepancy.md" \
  || fail "validate: packet does not record the candidate SHA"
grep -q "base_sha: ${CH_BASE}" "${CH_DIR}/discrepancy.md" \
  || fail "validate: packet does not record the base SHA"
grep -q "contract_sha256: $(file_hash "${PD}/contract.md")" "${CH_DIR}/discrepancy.md" \
  || fail "validate: packet does not record the contract hash"
grep -q "claude_verdict_sha256: $(file_hash "${PD}/claude-verdict.md")" "${CH_DIR}/discrepancy.md" \
  || fail "validate: packet does not record the Claude verdict hash"
grep -q "codex_verdict_sha256: $(file_hash "${PD}/codex-verdict.md")" "${CH_DIR}/discrepancy.md" \
  || fail "validate: packet does not record the reviewer verdict hash"
grep -q 'AC-01: the reaper observes the deadline' "${CH_DIR}/discrepancy.md" \
  || fail "validate: packet does not carry the frozen criterion text"
ok "validate: packet provenance is complete and frozen"

run_in "${R_CH}/src/deep" env HOME="${H1}" "${VALIDATE}" docs/phases/07 \
  --new-challenge AC-01 "${CH_CAND}"
expect_status 1 "validate: a second challenge for the same criterion refuses"
expect_out 'STATUS: CHALLENGE-EXISTS' "validate: reports CHALLENGE-EXISTS"

cat > "${CH_DIR}/codex-clarification.md" <<'CLAR'
## Claim
The deadline check is absent from the committed range.

## Supporting evidence
src/deep/reaper.txt:12 in the supplied diff shows no comparison.

## What would falsify this claim
Any line in the committed range comparing a deadline to a clock reading.

## Verification command (at most one)
grep -n deadline src/deep/reaper.txt
grep -n clock src/deep/reaper.txt
CLAR
run_in "${R_CH}/src/deep" env HOME="${H1}" "${VALIDATE}" docs/phases/07
expect_status 1 "validate: a clarification with two commands refuses"
expect_out 'STATUS: CLARIFICATION-INVALID' "validate: reports CLARIFICATION-INVALID"

cat > "${CH_DIR}/codex-clarification.md" <<'CLAR'
## Claim
The deadline check is absent from the committed range.

## Supporting evidence
src/deep/reaper.txt:12 in the supplied diff shows no comparison.

## What would falsify this claim
Any line in the committed range comparing a deadline to a clock reading.

## Verification command (at most one)
grep -n deadline src/deep/reaper.txt
CLAR
run_in "${R_CH}/src/deep" env HOME="${H1}" "${VALIDATE}" docs/phases/07
expect_status 0 "validate: a single-command clarification is accepted"
expect_out 'CLARIFICATION-OK' "validate: reports CLARIFICATION-OK"

cat > "${CH_DIR}/challenge-result.md" <<RESULT
# Adjudication — AC-01

Command run: grep -n deadline src/deep/reaper.txt
Output: (no matches)
Adjudication: the reviewer's FAIL is sustained; AC-01 is corrected to FAIL.

discrepancy_sha256: $(file_hash "${CH_DIR}/discrepancy.md")
clarification_sha256: $(file_hash "${CH_DIR}/codex-clarification.md")
RESULT
run_in "${R_CH}/src/deep" env HOME="${H1}" "${VALIDATE}" docs/phases/07
expect_status 0 "validate: adjudicated challenge with matching hashes passes"
expect_out 'ADJUDICATED: AC-01' "validate: reports the adjudication"
if [ -w "${CH_DIR}/discrepancy.md" ]; then fail "validate: did not seal discrepancy.md read-only"; fi
if [ -w "${CH_DIR}/challenge-result.md" ]; then fail "validate: did not seal challenge-result.md read-only"; fi
ok "validate: adjudicated artifacts are sealed read-only"

chmod u+w "${CH_DIR}/discrepancy.md"
printf '\ntampered after adjudication\n' >> "${CH_DIR}/discrepancy.md"
run_in "${R_CH}/src/deep" env HOME="${H1}" "${VALIDATE}" docs/phases/07
expect_status 1 "validate: a tampered packet refuses"
expect_out 'STATUS: CHALLENGE-TAMPERED' "validate: reports CHALLENGE-TAMPERED"

# ---- 12. release check -------------------------------------------------------
R_REL="${TEST_ROOT}/repo-release"
new_repo "${R_REL}"
REL_BASE="$(git -C "${R_REL}" rev-parse HEAD)"
mkdir -p "${R_REL}/docs/phases/08"
RD="${R_REL}/docs/phases/08"
cat > "${RD}/contract.md" <<CONTRACT
# Phase 08: release fixture

## Base
Base SHA: ${REL_BASE}
Base ref: main

## Acceptance criteria
AC-01: the disputed behavior holds.
AC-02: the undisputed behavior holds.
CONTRACT
git -C "${R_REL}" add docs/phases/08/contract.md
git -C "${R_REL}" commit -q -m 'phase 08 contract'
REL_CAND="$(git -C "${R_REL}" rev-parse HEAD)"
cat > "${RD}/claude-verdict.md" <<VERDICT
HEAD: ${REL_CAND}

| Criterion | Verdict | Evidence |
|---|---|---|
| AC-01 | FAIL | tests line 8 |
| AC-02 | PASS | tests line 9 |
VERDICT
cat > "${RD}/codex-verdict.md" <<VERDICT
HEAD: ${REL_CAND}

| Criterion | Verdict | Evidence |
|---|---|---|
| AC-01 | PASS | diff line 4 |
| AC-02 | PASS | tests line 9 |
VERDICT

write_decision() { # $1... extra lines appended after the table
  {
    printf '# Phase 08 decision\n\n'
    printf '| Criterion | Verdict | Evidence |\n|---|---|---|\n'
    printf '| %s |\n' "AC-01 | ${1} | reconciled from both matrices"
    printf '| %s |\n' "AC-02 | PASS | both matrices agree"
    printf '\n'
    shift
    for line in "$@"; do printf '%s\n' "${line}"; done
  } > "${RD}/decision.md"
}

write_decision UNRESOLVED
run_in "${R_REL}/src/deep" env HOME="${H1}" "${VALIDATE}" --release-check docs/phases/08
expect_status 1 "release: an UNRESOLVED row without a waiver blocks"
expect_out 'STATUS: NOT-RELEASABLE' "release: reports NOT-RELEASABLE"

write_decision UNRESOLVED 'WAIVED-BY: claude 2026-08-24 — signed off by the agent'
run_in "${R_REL}/src/deep" env HOME="${H1}" "${VALIDATE}" --release-check docs/phases/08
expect_status 1 "release: an agent-signed waiver is invalid"
expect_out 'STATUS: NOT-RELEASABLE' "release: agent waiver still NOT-RELEASABLE"
expect_out 'INVALID waiver' "release: names the invalid waiver"

write_decision UNRESOLVED 'WAIVED-BY: Murat Alkan 2026-08-24 — accepted after manual review'
run_in "${R_REL}/src/deep" env HOME="${H1}" "${VALIDATE}" --release-check docs/phases/08
expect_status 0 "release: a human waiver clears the row"
expect_out 'STATUS: RELEASABLE' "release: reports RELEASABLE"
refute_out 'STATUS: NOT-RELEASABLE' "release: does not also report NOT-RELEASABLE"

write_decision CANNOT-VERIFY
run_in "${R_REL}/src/deep" env HOME="${H1}" "${VALIDATE}" --release-check docs/phases/08
expect_status 1 "release: an unwaived CANNOT-VERIFY row blocks"
expect_out 'STATUS: NOT-RELEASABLE' "release: CANNOT-VERIFY is not auto-converted"

write_decision FAIL 'WAIVED-BY: Murat Alkan 2026-08-24 — please ship it anyway'
run_in "${R_REL}/src/deep" env HOME="${H1}" "${VALIDATE}" --release-check docs/phases/08
expect_status 1 "release: a FAIL row is never waivable"
expect_out 'never waivable' "release: says why a FAIL cannot be waived"

write_decision PASS
run_in "${R_REL}/src/deep" env HOME="${H1}" "${VALIDATE}" --release-check docs/phases/08
expect_status 0 "release: an all-PASS matrix is releasable"
expect_out 'never issues GO' "release: leaves the GO decision to Claude"

run_in "${R_REL}/src/deep" env HOME="${H1}" "${VALIDATE}" docs/phases/08 --release-check
expect_status 0 "release: the phase-dir-first argument form works too"
expect_out 'STATUS: RELEASABLE' "release: both argument orders agree"
run_in "${R_REL}/src/deep" env HOME="${H1}" "${VALIDATE}"
expect_status 2 "validate: no arguments is a usage error"
expect_out 'STATUS: USAGE' "validate: reports USAGE"
run_in "${R_REL}/src/deep" env HOME="${H1}" "${VALIDATE}" docs/phases/99
expect_status 1 "validate: an absent phase directory refuses"
expect_out 'STATUS: MISSING-PHASE-DIR' "validate: reports MISSING-PHASE-DIR"

# ---- 12b. the layout the gate harness actually publishes ---------------------
# codex-gate.sh writes the reviewer's table starting at line 1 and binds the
# candidate only in the provenance footer it appends. A validator that demands
# `HEAD:` on line 1 rejects every genuine gate verdict, so both bindings are
# accepted — and a file carrying both must name one commit, not two.
R_HARN="${TEST_ROOT}/repo-harness-layout"
new_repo "${R_HARN}"
HARN_BASE="$(git -C "${R_HARN}" rev-parse HEAD)"
mkdir -p "${R_HARN}/docs/phases/09"
HD="${R_HARN}/docs/phases/09"
cat > "${HD}/contract.md" <<CONTRACT
# Phase 09: harness-layout fixture

## Base
Base SHA: ${HARN_BASE}
Base ref: main

## Acceptance criteria
AC-01: the first behavior holds.
AC-02: the second behavior holds.
CONTRACT
git -C "${R_HARN}" add docs/phases/09/contract.md
git -C "${R_HARN}" commit -q -m 'phase 09 contract'
HARN_CAND="$(git -C "${R_HARN}" rev-parse HEAD)"
cat > "${HD}/claude-verdict.md" <<VERDICT
HEAD: ${HARN_CAND}

| Criterion | Verdict | Evidence |
|---|---|---|
| AC-01 | PASS | tests line 8 |
| AC-02 | PASS | tests line 9 |
VERDICT

write_harness_verdict() { # $1 footer head sha, $2 line-1 HEAD sha ("" for none)
  {
    if [ -n "${2:-}" ]; then printf 'HEAD: %s\n\n' "$2"; fi
    printf '| exact AC-ID | PASS or FAIL or CANNOT-VERIFY | Evidence |\n'
    printf '|---|---|---|\n'
    printf '| AC-01 | PASS | `src/deep/a.txt:3` in the DIFF |\n'
    printf '| AC-02 | PASS | TYPECHECK output line 12 |\n'
    printf '\n## UNSTATED-RISK\n\n- none\n'
    printf '%s\n' '---'
    printf 'provenance:\n'
    printf '  head: %s\n' "$1"
    printf '  base: %s (merge-base of main)\n' "${HARN_BASE}"
    printf '  contract_sha256: %s\n' "$(file_hash "${HD}/contract.md")"
    printf '  gate_conf_sha256: %s\n' \
      '0000000000000000000000000000000000000000000000000000000000000000'
    printf '  model: gpt-5.6-sol / effort=high / sandbox=read-only / ephemeral / clean-room\n'
    printf '  harness: codex-gate.sh v2.1.1 (2026-08-24T21:08:19Z)\n'
  } > "${HD}/codex-verdict.md"
}
write_harness_decision() { # $... row bodies, e.g. 'AC-01 | PASS | PASS | PASS'
  {
    printf '# Phase 09 decision\n\n'
    printf '| Criterion | Claude (Tier-1) | Codex (Tier-2) | Reconciled |\n'
    printf '|---|---|---|---|\n'
    for line in "$@"; do printf '| %s |\n' "${line}"; done
  } > "${HD}/decision.md"
}

write_harness_verdict "${HARN_CAND}" ""
run_in "${R_HARN}/src/deep" env HOME="${H1}" "${VALIDATE}" docs/phases/09
expect_status 0 "validate: the harness verdict layout is accepted"
expect_out "VERDICT-OK: codex-verdict\.md \(HEAD ${HARN_CAND}\)" \
  "validate: binds the candidate from the provenance footer"
expect_out 'STATUS: OK' "validate: harness layout reports OK"

write_harness_decision 'AC-01 | PASS | PASS | PASS' 'AC-02 | PASS | PASS | PASS'
run_in "${R_HARN}/src/deep" env HOME="${H1}" "${VALIDATE}" --release-check docs/phases/09
expect_status 0 "release: a harness-published verdict passes the release check"
expect_out 'STATUS: RELEASABLE' "release: harness layout reports RELEASABLE"

cat > "${HD}/codex-verdict.md" <<VERDICT
HEAD: ${HARN_CAND}

| Criterion | Verdict | Evidence |
|---|---|---|
| AC-01 | PASS | diff line 4 |
| AC-02 | PASS | tests line 9 |
VERDICT
run_in "${R_HARN}/src/deep" env HOME="${H1}" "${VALIDATE}" docs/phases/09
expect_status 0 "validate: a legacy line-1 HEAD verdict is still accepted"
expect_out "VERDICT-OK: codex-verdict\.md \(HEAD ${HARN_CAND}\)" \
  "validate: line 1 still binds when there is no footer"

write_harness_verdict "${HARN_CAND}" "${HARN_CAND}"
run_in "${R_HARN}/src/deep" env HOME="${H1}" "${VALIDATE}" docs/phases/09
expect_status 0 "validate: line 1 and footer in agreement are accepted"
expect_out 'STATUS: OK' "validate: agreeing bindings report OK"

write_harness_verdict "${HARN_CAND}" "${HARN_BASE}"
run_in "${R_HARN}/src/deep" env HOME="${H1}" "${VALIDATE}" docs/phases/09
expect_status 1 "validate: line 1 and footer naming different commits refuses"
expect_out 'STATUS: VERDICT-UNBOUND' "validate: conflicting bindings report VERDICT-UNBOUND"
expect_out "line 1: ${HARN_BASE}" "validate: names the line-1 sha in the conflict"
expect_out "footer: ${HARN_CAND}" "validate: names the footer sha in the conflict"

{
  printf '| exact AC-ID | PASS or FAIL or CANNOT-VERIFY | Evidence |\n'
  printf '|---|---|---|\n'
  printf '| AC-01 | PASS | diff line 4 |\n'
  printf '| AC-02 | PASS | tests line 9 |\n'
} > "${HD}/codex-verdict.md"
run_in "${R_HARN}/src/deep" env HOME="${H1}" "${VALIDATE}" docs/phases/09
expect_status 1 "validate: a verdict bound to nothing refuses"
expect_out 'STATUS: VERDICT-UNBOUND' "validate: an unbound verdict reports VERDICT-UNBOUND"

write_harness_verdict "not-a-sha" ""
run_in "${R_HARN}/src/deep" env HOME="${H1}" "${VALIDATE}" docs/phases/09
expect_status 1 "validate: a footer head that is not a full sha binds nothing"
expect_out 'STATUS: VERDICT-UNBOUND' "validate: a malformed footer head reports VERDICT-UNBOUND"

write_harness_verdict "${HARN_BASE}" ""
run_in "${R_HARN}/src/deep" env HOME="${H1}" "${VALIDATE}" docs/phases/09
expect_status 1 "validate: a footer bound to another commit refuses"
expect_out 'STATUS: VERDICT-SHA-MISMATCH' "validate: reports VERDICT-SHA-MISMATCH across verdicts"

# A reviewer judging this machinery quotes `provenance:` and `head:` in its
# prose. Only the footer the harness appends last may bind the file, or the
# reviewer's own text could name the candidate.
{
  printf '| exact AC-ID | PASS or FAIL or CANNOT-VERIFY | Evidence |\n'
  printf '|---|---|---|\n'
  printf '| AC-01 | PASS | `src/deep/a.txt:3` in the DIFF |\n'
  printf '| AC-02 | PASS | TYPECHECK output line 12 |\n'
  printf '\n## UNSTATED-RISK\n\n- the footer format under review reads:\n\n'
  printf '```\nprovenance:\n  head: %s\n```\n\n' "${HARN_BASE}"
  printf '%s\n' '---'
  printf 'provenance:\n'
  printf '  head: %s\n' "${HARN_CAND}"
  printf '  harness: codex-gate.sh v2.1.1 (2026-08-24T21:08:19Z)\n'
} > "${HD}/codex-verdict.md"
run_in "${R_HARN}/src/deep" env HOME="${H1}" "${VALIDATE}" docs/phases/09
expect_status 0 "validate: quoted provenance text in the verdict body binds nothing"
expect_out "VERDICT-OK: codex-verdict\.md \(HEAD ${HARN_CAND}\)" \
  "validate: the appended footer binds, not the quotation above it"

{
  printf '| exact AC-ID | PASS or FAIL or CANNOT-VERIFY | Evidence |\n'
  printf '|---|---|---|\n'
  printf '| AC-01 | PASS | `src/deep/a.txt:3` in the DIFF |\n'
  printf '| AC-02 | PASS | TYPECHECK output line 12 |\n'
  printf '\n## UNSTATED-RISK\n\n```\nprovenance:\n  head: %s\n```\n\n' "${HARN_CAND}"
  printf '%s\n' '---'
  printf 'provenance:\n'
  printf '  base: %s (merge-base of main)\n' "${HARN_BASE}"
} > "${HD}/codex-verdict.md"
run_in "${R_HARN}/src/deep" env HOME="${H1}" "${VALIDATE}" docs/phases/09
expect_status 1 "validate: a final footer with no head leaves the verdict unbound"
expect_out 'STATUS: VERDICT-UNBOUND' \
  "validate: an earlier quotation cannot stand in for the missing footer head"

# git accepts an abbreviated sha as naming a commit, and so does the
# line-1 binding; the full sha the harness writes must simply start with it.
write_harness_verdict "${HARN_CAND}" "$(printf '%s' "${HARN_CAND}" | cut -c1-8)"
run_in "${R_HARN}/src/deep" env HOME="${H1}" "${VALIDATE}" docs/phases/09
expect_status 0 "validate: an abbreviated line 1 agrees with the full footer sha"
expect_out "VERDICT-OK: codex-verdict\.md \(HEAD ${HARN_CAND}\)" \
  "validate: the full footer sha is the one carried forward"

write_harness_verdict "${HARN_CAND}" "$(printf '%s' "${HARN_BASE}" | cut -c1-8)"
run_in "${R_HARN}/src/deep" env HOME="${H1}" "${VALIDATE}" docs/phases/09
expect_status 1 "validate: an abbreviation of another commit is still a conflict"
expect_out 'STATUS: VERDICT-UNBOUND' "validate: abbreviated conflicts report VERDICT-UNBOUND"

# The harness delimits its footer: a line that is exactly `---`, and
# `provenance:` at column 0 on the very next line. Footer-shaped text without
# that delimiter is prose. The first case below is the scenario adjudicated in
# the AC-01 challenge of phase 02: no line-1 HEAD, no delimiter, a quoted
# footer at the end of the file. It must leave the verdict unbound.
{
  printf '| exact AC-ID | PASS or FAIL or CANNOT-VERIFY | Evidence |\n'
  printf '|---|---|---|\n'
  printf '| AC-01 | PASS | `src/deep/a.txt:3` in the DIFF |\n'
  printf '| AC-02 | PASS | TYPECHECK output line 12 |\n'
  printf '\n## UNSTATED-RISK\n\n- the footer format under review reads:\n\n'
  printf '```\nprovenance:\n  head: %s\n```\n' "${HARN_CAND}"
} > "${HD}/codex-verdict.md"
run_in "${R_HARN}/src/deep" env HOME="${H1}" "${VALIDATE}" docs/phases/09
expect_status 1 "validate: a quoted footer with no delimiter and no line 1 binds nothing"
expect_out 'STATUS: VERDICT-UNBOUND' \
  "validate: the adjudicated AC-01 scenario reports VERDICT-UNBOUND"

# The same shape without the fence: trailing prose is still prose.
{
  printf '| exact AC-ID | PASS or FAIL or CANNOT-VERIFY | Evidence |\n'
  printf '|---|---|---|\n'
  printf '| AC-01 | PASS | `src/deep/a.txt:3` in the DIFF |\n'
  printf '| AC-02 | PASS | TYPECHECK output line 12 |\n'
  printf '\n## UNSTATED-RISK\n\n- the footer under review ends:\n\n'
  printf 'provenance:\n  head: %s\n' "${HARN_CAND}"
} > "${HD}/codex-verdict.md"
run_in "${R_HARN}/src/deep" env HOME="${H1}" "${VALIDATE}" docs/phases/09
expect_status 1 "validate: undelimited footer-shaped prose binds nothing"
expect_out 'STATUS: VERDICT-UNBOUND' "validate: unfenced footer-shaped prose reports VERDICT-UNBOUND"

# The harness writes `provenance:` on the line immediately after `---`;
# anything between them means the block was not written by the harness.
{
  printf '| exact AC-ID | PASS or FAIL or CANNOT-VERIFY | Evidence |\n'
  printf '|---|---|---|\n'
  printf '| AC-01 | PASS | `src/deep/a.txt:3` in the DIFF |\n'
  printf '| AC-02 | PASS | TYPECHECK output line 12 |\n'
  printf '\n## UNSTATED-RISK\n\n- none\n'
  printf '%s\n' '---'
  printf '\nprovenance:\n'
  printf '  head: %s\n' "${HARN_CAND}"
} > "${HD}/codex-verdict.md"
run_in "${R_HARN}/src/deep" env HOME="${H1}" "${VALIDATE}" docs/phases/09
expect_status 1 "validate: a blank line between --- and provenance: binds nothing"
expect_out 'STATUS: VERDICT-UNBOUND' "validate: a detached provenance block reports VERDICT-UNBOUND"

# ...and an indented `provenance:` under a delimiter is quotation, not a footer.
{
  printf '| exact AC-ID | PASS or FAIL or CANNOT-VERIFY | Evidence |\n'
  printf '|---|---|---|\n'
  printf '| AC-01 | PASS | `src/deep/a.txt:3` in the DIFF |\n'
  printf '| AC-02 | PASS | TYPECHECK output line 12 |\n'
  printf '\n## UNSTATED-RISK\n\n- none\n'
  printf '%s\n' '---'
  printf '  provenance:\n'
  printf '    head: %s\n' "${HARN_CAND}"
} > "${HD}/codex-verdict.md"
run_in "${R_HARN}/src/deep" env HOME="${H1}" "${VALIDATE}" docs/phases/09
expect_status 1 "validate: an indented provenance: under a delimiter binds nothing"
expect_out 'STATUS: VERDICT-UNBOUND' "validate: an indented provenance block reports VERDICT-UNBOUND"

# The genuine layout the harness publishes — delimiter, `provenance:`, an
# indented `head:` — still binds, with or without a blank line above it.
{
  printf '| exact AC-ID | PASS or FAIL or CANNOT-VERIFY | Evidence |\n'
  printf '|---|---|---|\n'
  printf '| AC-01 | PASS | `src/deep/a.txt:3` in the DIFF |\n'
  printf '| AC-02 | PASS | TYPECHECK output line 12 |\n'
  printf '\n## UNSTATED-RISK\n\n- none\n\n'
  printf '%s\n' '---'
  printf 'provenance:\n'
  printf '  head: %s\n' "${HARN_CAND}"
  printf '  base: %s (merge-base of main)\n' "${HARN_BASE}"
  printf '  harness: codex-gate.sh v2.1.1 (2026-08-24T21:08:19Z)\n'
} > "${HD}/codex-verdict.md"
run_in "${R_HARN}/src/deep" env HOME="${H1}" "${VALIDATE}" docs/phases/09
expect_status 0 "validate: a delimited harness footer still binds"
expect_out "VERDICT-OK: codex-verdict\.md \(HEAD ${HARN_CAND}\)" \
  "validate: the delimited footer names the candidate"

# Two delimited footers: the last one is the one the harness appended.
{
  printf '| exact AC-ID | PASS or FAIL or CANNOT-VERIFY | Evidence |\n'
  printf '|---|---|---|\n'
  printf '| AC-01 | PASS | `src/deep/a.txt:3` in the DIFF |\n'
  printf '| AC-02 | PASS | TYPECHECK output line 12 |\n'
  printf '\n## UNSTATED-RISK\n\n- a footer from an earlier run reads:\n\n'
  printf '%s\n' '---'
  printf 'provenance:\n'
  printf '  head: %s\n' "${HARN_BASE}"
  printf '\n'
  printf '%s\n' '---'
  printf 'provenance:\n'
  printf '  head: %s\n' "${HARN_CAND}"
  printf '  harness: codex-gate.sh v2.1.1 (2026-08-24T21:08:19Z)\n'
} > "${HD}/codex-verdict.md"
run_in "${R_HARN}/src/deep" env HOME="${H1}" "${VALIDATE}" docs/phases/09
expect_status 0 "validate: with two delimited footers the last one binds"
expect_out "VERDICT-OK: codex-verdict\.md \(HEAD ${HARN_CAND}\)" \
  "validate: an earlier delimited footer does not bind"

# ---- 12c. the merged matrix must cover the contract, and only it -------------
write_harness_verdict "${HARN_CAND}" ""

write_harness_decision
run_in "${R_HARN}/src/deep" env HOME="${H1}" "${VALIDATE}" --release-check docs/phases/09
expect_status 1 "release: a decision with a header but no rows blocks"
expect_out 'STATUS: DECISION-MISSING-ROW' \
  "release: an empty matrix is reported as rows left out, not as a bare refusal"
expect_out 'no row for contracted criterion/criteria: AC-01 AC-02' \
  "release: names every criterion left without a row"

write_harness_decision 'AC-01 | PASS | PASS | PASS'
run_in "${R_HARN}/src/deep" env HOME="${H1}" "${VALIDATE}" --release-check docs/phases/09
expect_status 1 "release: a decision omitting a contracted criterion blocks"
expect_out 'STATUS: DECISION-MISSING-ROW' "release: reports DECISION-MISSING-ROW"
expect_out 'no row for contracted criterion/criteria: AC-02' "release: names the absent criterion"
S_MISSING="$(last_status)"

write_harness_decision 'AC-01 | PASS | PASS | PASS' 'AC-02 | PASS | PASS | PASS' \
                       'AC-99 | PASS | PASS | PASS'
run_in "${R_HARN}/src/deep" env HOME="${H1}" "${VALIDATE}" --release-check docs/phases/09
expect_status 1 "release: a row for an undeclared criterion blocks"
expect_out 'STATUS: DECISION-UNKNOWN-ROW' "release: reports DECISION-UNKNOWN-ROW"
expect_out 'does not declare: AC-99' "release: names the undeclared criterion"
S_UNKNOWN="$(last_status)"

write_harness_decision 'AC-01 | PASS | PASS | PASS' 'AC-01 | PASS | PASS | PASS' \
                       'AC-02 | PASS | PASS | PASS'
run_in "${R_HARN}/src/deep" env HOME="${H1}" "${VALIDATE}" --release-check docs/phases/09
expect_status 1 "release: a repeated row for one criterion blocks"
expect_out 'STATUS: DECISION-DUPLICATE-ROW' "release: reports DECISION-DUPLICATE-ROW"
expect_out 'repeated row\(s\) for: AC-01' "release: names the repeated criterion"
S_DUP="$(last_status)"

[ "${S_MISSING}" != "${S_UNKNOWN}" ] && [ "${S_UNKNOWN}" != "${S_DUP}" ] \
  && [ "${S_MISSING}" != "${S_DUP}" ] \
  || fail "release: the three decision-matrix statuses are not distinct"
ok "release: missing, unknown, and repeated rows report three distinct statuses"

write_harness_decision 'AC-01 | PASS | PASS | PASS' 'AC-01 | PASS | PASS | PASS' \
                       'AC-02 | PASS | PASS | PASS' 'AC-99 | PASS | PASS | PASS'
run_in "${R_HARN}/src/deep" env HOME="${H1}" "${VALIDATE}" --release-check docs/phases/09
expect_status 1 "release: two matrix problems at once still block"
expect_out 'STATUS: DECISION-DUPLICATE-ROW' "release: the reported status is the first failing class"
expect_out 'does not declare: AC-99' "release: the other problem is still detailed"

write_harness_decision 'AC-01 | PASS | PASS | PASS' 'AC-02 | PASS | PASS | PASS'
run_in "${R_HARN}/src/deep" env HOME="${H1}" "${VALIDATE}" --release-check docs/phases/09
expect_status 0 "release: a matrix covering the contract exactly is releasable"
expect_out 'STATUS: RELEASABLE' "release: the complete matrix reports RELEASABLE"

# ---- 13. shell portability ---------------------------------------------------
> "${TEST_ROOT}/bash4-pattern" printf '%s\n' 'mapfile' 'readarray' 'declare -A' \
  '\$\{[A-Za-z_]+,,\}' '&>>'
for script in "${SKILLS_SRC}/verified-phase/scripts/bootstrap-repo.sh" \
              "${SKILLS_SRC}/verified-phase/scripts/preflight.sh" \
              "${SKILLS_SRC}/verified-phase/scripts/validate-phase.sh" \
              "${SKILLS_SRC}/install.sh"; do
  /bin/bash -n "${script}" || fail "bash -n failed: ${script}"
  head -n1 "${script}" | grep -q '^#!/bin/bash$' || fail "unexpected shebang: ${script}"
  grep -q '^set -euo pipefail$' "${script}" || fail "missing strict mode: ${script}"
  if grep -nE -f "${TEST_ROOT}/bash4-pattern" "${script}"; then
    fail "bash 4+ construct in ${script}; /bin/bash on macOS is 3.2"
  fi
done
/bin/bash -n "$0" || fail "bash -n failed: $0"
ok "all scripts: bash -n clean, /bin/bash shebang, strict mode, no bash 4+ constructs"
if command -v shellcheck >/dev/null 2>&1; then
  shellcheck -s bash -S error \
    "${SKILLS_SRC}/verified-phase/scripts/"*.sh "${SKILLS_SRC}/install.sh" \
    || fail "shellcheck reported an error-level finding"
  ok "shellcheck: no error-level findings"
else
  ok "shellcheck: not installed, skipped"
fi

# ---- 14. SKILL.md frontmatter ------------------------------------------------
SKILL_MD="${SKILLS_SRC}/verified-phase/SKILL.md"
grep -q '^name: verified-phase$' "${SKILL_MD}" || fail "SKILL.md: missing name"
grep -q '^disable-model-invocation: true$' "${SKILL_MD}" \
  || fail "SKILL.md: missing disable-model-invocation: true"
ok "SKILL.md: name and disable-model-invocation present"
if grep -q 'context:' "${SKILL_MD}"; then
  fail "SKILL.md: declares a fork; the lead must keep orchestration in the main thread"
fi
if grep -q 'allowed-tools:' "${SKILL_MD}"; then
  fail "SKILL.md: declares allowed-tools; a narrow list would misdescribe the workflow"
fi
ok "SKILL.md: no fork declaration and no allowed-tools declaration"
grep -q '\$ARGUMENTS' "${SKILL_MD}" || fail "SKILL.md: does not take the task from \$ARGUMENTS"
grep -q 'is empty, stop here and ask the user' "${SKILL_MD}" \
  || fail "SKILL.md: missing the ask-if-empty instruction"
grep -q 'stay active for the entire phase' "${SKILL_MD}" \
  || fail "SKILL.md: missing the instructions-remain-active statement"
ok "SKILL.md: \$ARGUMENTS, ask-if-empty, and instructions-remain-active all present"
grep -q 'skills/verified-phase/references/' "${SKILL_MD}" \
  || fail "SKILL.md: does not point at its references by installed path"
grep -q 'skills/verified-phase/scripts/' "${SKILL_MD}" \
  || fail "SKILL.md: does not point at its scripts by installed path"
ok "SKILL.md: references and scripts cited by installed path"

echo "PASS: ${pass} verified-phase checks"
