#!/bin/bash
# verified-phase: pre-phase readiness check for the CURRENT repository.
#
# Answers four questions before a phase starts, and decides nothing:
#   a) which commands prove this repo works        -> COMMANDS-STATUS
#   b) is the base clean and eligible              -> BASE-STATUS
#   c) is the environment safe for the gate        -> SECRETS-STATUS
#   d) is the gate machinery present               -> HARNESS-STATUS / AGENTS-STATUS
#
# It never writes to the repository, never edits .codex-gate.conf, and never
# invokes the reviewer CLI. Command inference only ever PROPOSES.
#
# Usage: preflight.sh              (run from anywhere inside the repo)
# Exit:  0 = ready, 1 = attention required (see STATUS), 2 = not a git work tree.
set -euo pipefail

if ! REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || [ -z "${REPO_ROOT}" ]; then
  echo "STATUS: NOT-A-GIT-REPO"
  echo "preflight: refusing — $(pwd) is not inside a git work tree." >&2
  echo "preflight: nothing was written. cd into the target repository and rerun." >&2
  exit 2
fi
cd "${REPO_ROOT}"
echo "REPO-ROOT: ${REPO_ROOT}"

blocking=""
block() { # first blocking condition wins the overall STATUS line
  if [ -z "${blocking}" ]; then blocking="$1"; fi
}

# ---- (a) verification commands ----------------------------------------------
# The conf is shell, sourced exactly as the harness sources it. Same trust
# boundary: a repo that can define its own test command can already run code.
commands_status=""
if [ -f .codex-gate.conf ]; then
  TEST_CMD=""; TYPECHECK_CMD=""; LINT_CMD=""
  conf_error=0
  # shellcheck source=/dev/null
  . ./.codex-gate.conf || conf_error=1
  if [ "${conf_error}" = 1 ]; then
    commands_status="MISSING-COMMANDS"
    echo "DETAIL: .codex-gate.conf exists but failed to source cleanly."
  elif [ -n "${TEST_CMD}" ]; then
    commands_status="OK"
    echo "TEST_CMD: ${TEST_CMD}"
    echo "TYPECHECK_CMD: ${TYPECHECK_CMD}"
    echo "LINT_CMD: ${LINT_CMD}"
  else
    echo "DETAIL: .codex-gate.conf exists but TEST_CMD is empty."
  fi
fi

if [ -z "${commands_status}" ]; then
  sig_count=0
  sig_list=""
  proposal=""
  note_signal() { # $1 human label, $2 proposed TEST_CMD
    sig_count=$((sig_count + 1))
    sig_list="${sig_list}  - $1 -> TEST_CMD=\"$2\"
"
    proposal="$2"
  }
  if [ -f pom.xml ]; then note_signal "pom.xml (Maven)" "mvn verify"; fi
  if [ -f package.json ] && grep -q '"scripts"' package.json \
     && grep -Eq '"test"[[:space:]]*:' package.json; then
    note_signal "package.json with a test script (npm)" "npm test"
  fi
  if [ -f Cargo.toml ]; then note_signal "Cargo.toml (Cargo)" "cargo test"; fi
  if [ -f go.mod ]; then note_signal "go.mod (Go)" "go test ./..."; fi

  if [ "${sig_count}" -eq 0 ]; then
    commands_status="MISSING-COMMANDS"
    echo "DETAIL: no unambiguous build signal found at the repo root."
    echo "DETAIL: ask the user which command proves this repo works, then write"
    echo "DETAIL: it to .codex-gate.conf as TEST_CMD before starting the phase."
  elif [ "${sig_count}" -eq 1 ]; then
    commands_status="PROPOSED-COMMANDS"
    echo "PROPOSAL: TEST_CMD=\"${proposal}\""
    echo "DETAIL: inferred from a single build signal. Confirm with the user;"
    echo "DETAIL: this script never writes .codex-gate.conf."
  else
    commands_status="AMBIGUOUS-COMMANDS"
    echo "DETAIL: ${sig_count} build signals found; inference refuses to guess."
    printf '%s' "${sig_list}"
    echo "DETAIL: ask the user which one is authoritative for this repo."
  fi
fi
echo "COMMANDS-STATUS: ${commands_status}"
[ "${commands_status}" = OK ] || block "${commands_status}"

# ---- (b) clean, eligible base ------------------------------------------------
if ! git rev-parse --verify -q HEAD >/dev/null 2>&1; then
  echo "BASE-STATUS: NO-COMMITS"
  echo "DETAIL: HEAD does not resolve; commit the initial state first."
  block "NO-COMMITS"
else
  dirty="$(git status --porcelain)"
  if [ -n "${dirty}" ]; then
    echo "BASE-STATUS: DIRTY-BASE"
    echo "DETAIL: the base commit must describe the tree exactly. Offending entries:"
    printf '%s\n' "${dirty}" | sed 's/^/  /'
    block "DIRTY-BASE"
  else
    echo "BASE-STATUS: OK"
    echo "BASE-SHA: $(git rev-parse HEAD)"
  fi
fi

# ---- (c) secret hygiene ------------------------------------------------------
# Defined-only tests. Variable NAMES are printed; values never are, not even
# to say they are empty-but-set. Mirrors the harness fail-closed behaviour.
secrets_status="OK"
if [ "${OPENAI_API_KEY+x}" = x ]; then
  echo "DETAIL: OPENAI_API_KEY is defined in this environment (value not shown)."
  secrets_status="SECRET-ENV-SET"
fi
if [ "${CODEX_API_KEY+x}" = x ]; then
  echo "DETAIL: CODEX_API_KEY is defined in this environment (value not shown)."
  secrets_status="SECRET-ENV-SET"
fi
echo "SECRETS-STATUS: ${secrets_status}"
if [ "${secrets_status}" != OK ]; then
  echo "DETAIL: the gate harness refuses (exit 4) while either name is defined,"
  echo "DETAIL: because a stored key could bill the API instead of the plan."
  echo "DETAIL: unset the named variable — do not print or edit its value."
  block "SECRET-ENV-SET"
fi

# ---- (d) machinery presence --------------------------------------------------
HARNESS="${HOME}/.claude/harness/codex-gate.sh"
if [ -x "${HARNESS}" ]; then
  echo "HARNESS-STATUS: OK"
  echo "HARNESS: ${HARNESS}"
else
  echo "HARNESS-STATUS: MISSING-HARNESS"
  echo "DETAIL: expected an executable gate harness at ${HARNESS}."
  echo "DETAIL: Tier-2 cannot run without it; install the gate integration first."
  block "MISSING-HARNESS"
fi

if [ -f AGENTS.md ]; then
  echo "AGENTS-STATUS: OK"
else
  echo "AGENTS-STATUS: MISSING-AGENTS-MD"
  echo "DETAIL: AGENTS.md is the reviewer's instruction file; run bootstrap-repo.sh."
  block "MISSING-AGENTS-MD"
fi

if [ -z "${blocking}" ]; then
  echo "STATUS: OK"
else
  echo "STATUS: ${blocking}"
  exit 1
fi
