#!/bin/bash
# verified-phase: place missing gate scaffolding in the CURRENT repository.
#
# Non-destructive by construction: it only ever creates files that do not
# exist (v2.1.1 place() semantics — "SKIP (exists, untouched)"). It never
# edits, appends to, or overwrites an existing file, including AGENTS.md and
# .gitignore. A second run is all SKIP and exits 0.
#
# Everything it writes lives inside the git work tree. If the current
# directory is not inside one, it writes nothing and fails.
#
# Usage: bootstrap-repo.sh          (run from anywhere inside the repo)
# Exit:  0 = completed, 2 = not inside a git work tree / missing assets.
set -euo pipefail

SKILL_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ASSETS="${SKILL_ROOT}/assets"

if ! REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || [ -z "${REPO_ROOT}" ]; then
  echo "STATUS: NOT-A-GIT-REPO"
  echo "bootstrap-repo: refusing — $(pwd) is not inside a git work tree." >&2
  echo "bootstrap-repo: nothing was written. cd into the target repository and rerun." >&2
  exit 2
fi

for asset in contract-template.md evidence-matrix-template.md discrepancy-template.md; do
  if [ ! -f "${ASSETS}/${asset}" ]; then
    echo "STATUS: MISSING-ASSETS"
    echo "bootstrap-repo: refusing — asset not found: ${ASSETS}/${asset}" >&2
    exit 2
  fi
done

cd "${REPO_ROOT}"
echo "REPO-ROOT: ${REPO_ROOT}"

added=0
skipped=0

place() { # $1 src, $2 dst-relative-to-repo-root, $3 exec(0|1)
  local src="$1" dst="$2" want_exec="$3"
  if [ -e "${dst}" ]; then
    echo "SKIP (exists, untouched): ${dst}"
    skipped=$((skipped + 1))
    return 0
  fi
  mkdir -p "$(dirname "${dst}")"
  cp "${src}" "${dst}"
  if [ "${want_exec}" = 1 ]; then chmod +x "${dst}"; fi
  echo "ADD: ${dst}"
  added=$((added + 1))
}

place_text() { # $1 dst-relative-to-repo-root; content on stdin
  local dst="$1"
  if [ -e "${dst}" ]; then
    cat > /dev/null
    echo "SKIP (exists, untouched): ${dst}"
    skipped=$((skipped + 1))
    return 0
  fi
  mkdir -p "$(dirname "${dst}")"
  cat > "${dst}"
  echo "ADD: ${dst}"
  added=$((added + 1))
}

place "${ASSETS}/contract-template.md" \
      docs/phases/_TEMPLATE/contract.md 0
place "${ASSETS}/evidence-matrix-template.md" \
      docs/phases/_TEMPLATE/claude-verdict.TEMPLATE.md 0

place_text .codex-gate.conf <<'CONF'
# .codex-gate.conf — evidence commands for the external gate harness.
# Shell syntax. Edit these to the commands that actually prove this repo
# works; the harness runs them and pipes their output to the reviewer.
# The harness records this file's sha256 in every verdict's provenance
# footer, so changes to it are visible in gate history.
#
# TEST_CMD is required. Leave the other two empty if the repo has no
# separate typecheck or lint step.
TEST_CMD=""
TYPECHECK_CMD=""
LINT_CMD=""
CONF

if [ -e AGENTS.md ]; then
  echo "SKIP (exists, untouched): AGENTS.md"
  echo "  AGENTS.md is the reviewer's instruction file and may be user-authored;"
  echo "  it is never overwritten, merged, or reformatted by this script."
  skipped=$((skipped + 1))
else
  place_text AGENTS.md <<'AGENTSMD'
# AGENTS.md

Your role in this repo is assigned per invocation. If no role was given in
your instructions, stop and ask. Do not assume you are implementing.

## Source of truth
Phase contracts live in `docs/phases/<phase>/contract.md`: goals, non-goals,
acceptance criteria, interface commitments. The contract is authoritative and
fixed. You do not amend it, extend it, or infer additional scope from it.
Where contract and code disagree, that is a finding — not something to fix.

## Invariants
- Never work outside the current phase's stated scope.
- Never touch files outside the working directory you were given.
- Acceptance criteria are executable. To claim a pass, cite the command
  output or file:line that proves it.
- Never commit, branch, push, or open a PR.

## Verification commands
Defined in `.codex-gate.conf` at repo root (hash-recorded per gate run).
AGENTSMD
fi

IGNORE_LINE='docs/phases/**/evidence-full.log'
if [ -f .gitignore ] && grep -qxF "${IGNORE_LINE}" .gitignore; then
  echo "OK: .gitignore already excludes ${IGNORE_LINE}"
else
  echo "NOTE: add ${IGNORE_LINE} to .gitignore — the full evidence log is a"
  echo "NOTE: local artifact; only its sha256 belongs in a verdict. This script"
  echo "NOTE: never edits existing files, so apply it yourself."
fi

echo "SUMMARY: added=${added} skipped=${skipped}"
echo "STATUS: OK"
