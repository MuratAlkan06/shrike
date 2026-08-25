#!/bin/bash
# verified-phase: the one clarification call of the bounded evidence challenge.
#
# This is the second sanctioned entry point to the reviewer CLI, and it is
# deliberately the narrowest one that can exist: a fixed in-script prompt, the
# frozen discrepancy packet on stdin, and nothing else. It takes exactly two
# arguments — a phase directory and the disputed criterion ID — offers no
# prompt override, no passthrough arguments and no write-capable flag, and
# runs the reviewer in the clean room the gate harness uses: foreground,
# ephemeral, read-only, strict-config, user config and rules ignored. The only
# caller-controlled bytes that reach the reviewer are the packet's own.
#
# Threat model, stated plainly so nobody over-trusts it: TM-A. This is a
# cooperative DRIFT guard, not an adversarial security boundary. A read-only
# reviewer can still read the filesystem and reach committed history; the
# packet-only property is about what this script SUPPLIES, and "do not
# explore" is an instruction, not isolation. A boundary needs a different
# mechanism, not a longer prompt.
#
# One clarification per (candidate, criterion), ever. A PUBLISHED
# codex-clarification.md is what consumes that one shot: an authentication
# refusal, a reviewer failure or an empty reply publishes nothing and consumes
# nothing, and no path here overwrites an existing reply.
#
# usage: clarify-challenge.sh <phase-dir> <AC-ID>
#
# exit: 0 = CLARIFY-OK — a reply was published. That is NOT "the disagreement
#           is settled": structural validity is judged by validate-phase.sh,
#           and adjudication is the verification lead's, in challenge-result.md.
#       1 = a refusal or an inconclusive run (see STATUS)
#       2 = usage, or not inside a git work tree
#       4 = CLARIFY-AUTH — subscription authentication is not active
set -euo pipefail

# ---- portability (mirrors ~/.claude/harness/codex-gate.sh) -------------------
sha256() { # macOS ships shasum, not sha256sum
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  else shasum -a 256 "$1" | cut -d' ' -f1; fi
}
with_timeout() { # $1 secs, rest cmd — degrade gracefully if no timeout binary
  local secs="$1"; shift
  if command -v timeout  >/dev/null 2>&1; then timeout  "${secs}" "$@"
  elif command -v gtimeout >/dev/null 2>&1; then gtimeout "${secs}" "$@"
  else "$@"; fi
}

die() { # $1 status, rest = detail lines
  local status="$1"; shift
  local line
  for line in "$@"; do echo "DETAIL: ${line}"; done
  echo "STATUS: ${status}"
  exit 1
}

usage() {
  {
    echo "usage: clarify-challenge.sh <phase-dir> <AC-ID>"
    echo "       exactly two arguments; there is no prompt or argument passthrough."
    if [ "$#" -gt 0 ]; then echo "error: $1"; fi
  } >&2
  echo "STATUS: USAGE"
  exit 2
}

# ---- arguments: exactly two, and nothing that reaches the reviewer -----------
[ "$#" -eq 2 ] || usage "expected 2 arguments, got $#"
PHASE_ARG="$1"
AC_ID="$2"
# `AC-[0-9]*` reads like an anchored pattern and is not one: as a shell glob it
# means AC-, one digit, then anything at all, so AC-1xyz passes it. The ID names
# a directory this script reads and publishes into, so it is checked exactly —
# AC- followed by digits and nothing else, the same shape the verdict reader in
# validate-phase.sh requires of a criterion row.
case "${AC_ID}" in
  AC-*) AC_DIGITS="${AC_ID#AC-}" ;;
  *)    AC_DIGITS="" ;;
esac
case "${AC_DIGITS}" in
  ""|*[!0-9]*) usage "criterion ID must be AC- followed by digits only, got: ${AC_ID}" ;;
esac

# Everything below is fixed in this script. None of it is reachable from the
# command line, the environment, or the packet.
CLARIFY_TIMEOUT=900
CLARIFY_MODEL="gpt-5.6-sol"
CLARIFY_VERSION="clarify-challenge.sh v1"
CLARIFY_PROMPT="You are the reviewer whose verdict on the disputed criterion \
is challenged. You receive ONLY the frozen discrepancy packet on stdin; it is \
the entire record of the disagreement. Judge only that packet: do not read \
files, do not explore the repository, do not run commands. Evidence that is \
not in the packet is not available to you, and saying so is a valid answer. \
Reply with exactly four sections, in this order, and nothing else. \
'## Claim' — one sentence naming what you assert is true about the disputed \
criterion. '## Supporting evidence' — cite only content quoted in the packet; \
'the code looks correct' is not evidence. '## What would falsify this' — the \
observation that would show your claim is wrong. '## Verification command' — \
at most ONE exact deterministic command a human can run to settle it, on a \
line by itself and nothing else in that section, or the single word none when \
no single command settles it. No preamble, no praise, no refactors, no \
remedies, no second command."

# ---- repository + challenge directory ---------------------------------------
if ! REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || [ -z "${REPO_ROOT}" ]; then
  echo "STATUS: NOT-A-GIT-REPO"
  echo "clarify-challenge: refusing — $(pwd) is not inside a git work tree." >&2
  echo "clarify-challenge: nothing was written." >&2
  exit 2
fi

# Resolved physically, then required to be inside the repository. This script
# publishes into the directory it is handed, and "it exists" is not a licence to
# write there: an absolute path, or a symlink pointing out of the tree, would
# otherwise put the reply somewhere no diff, no validator and no reviewer will
# ever look at again.
REPO_ROOT_PHYS="$(cd "${REPO_ROOT}" && pwd -P)"
PHASE_DIR=""
if [ -d "${PHASE_ARG}" ]; then
  PHASE_DIR="$(cd "${PHASE_ARG}" && pwd -P)"
elif [ -d "${REPO_ROOT}/${PHASE_ARG}" ]; then
  PHASE_DIR="$(cd "${REPO_ROOT}/${PHASE_ARG}" && pwd -P)"
else
  die MISSING-PHASE-DIR "no such phase directory: ${PHASE_ARG}" \
      "paths are resolved against the current directory, then the repo root."
fi
case "${PHASE_DIR}" in
  "${REPO_ROOT_PHYS}"|"${REPO_ROOT_PHYS}"/*) ;;
  *) die MISSING-PHASE-DIR \
       "the phase directory is outside the repository: ${PHASE_DIR}" \
       "repository root: ${REPO_ROOT_PHYS}" \
       "a phase record belongs to the repository under review, and a clarification" \
       "is published beside its packet; nothing here writes outside the work tree" ;;
esac
cd "${REPO_ROOT}"

CONTRACT="${PHASE_DIR}/contract.md"
CLAUDE_VERDICT="${PHASE_DIR}/claude-verdict.md"
CODEX_VERDICT="${PHASE_DIR}/codex-verdict.md"
CH_DIR="${PHASE_DIR}/challenges/${AC_ID}"
DISC="${CH_DIR}/discrepancy.md"
CLAR="${CH_DIR}/codex-clarification.md"
RESULT="${CH_DIR}/challenge-result.md"

echo "REPO-ROOT: ${REPO_ROOT}"
echo "CHALLENGE: ${CH_DIR}"

WORK="$(mktemp -d)"
STAGE="${CH_DIR}/.codex-clarification.$$.tmp"
cleanup() { rm -f "${STAGE}"; rm -rf "${WORK}"; }
trap cleanup EXIT

# ---- precondition: a complete, unedited packet -------------------------------
# Same reading and same status as validate-phase.sh: a provenance field that is
# absent or still a <placeholder> means the packet was never finished.
[ -d "${CH_DIR}" ] || die CHALLENGE-INCOMPLETE \
  "no challenge directory: ${CH_DIR}" \
  "raise the packet first:  validate-phase.sh <phase-dir> --new-challenge ${AC_ID} <candidate-sha>"
[ -f "${DISC}" ] || die CHALLENGE-INCOMPLETE "missing ${DISC}"

packet_field() { # $1 key — first value recorded in the packet
  sed -n "s/^$1:[[:space:]]*//p" "${DISC}" | head -n1
}

for key in disputed_criterion base_sha candidate_sha contract_sha256 \
           claude_verdict_sha256 codex_verdict_sha256; do
  value="$(packet_field "${key}")"
  case "${value}" in
    ""|"<"*) die CHALLENGE-INCOMPLETE \
               "${DISC}: provenance field '${key}' is missing or unfilled" \
               "the reviewer is sent the packet verbatim; an unfilled packet asks nothing" ;;
  esac
done

PACKET_CRITERION="$(packet_field disputed_criterion)"
PACKET_BASE="$(packet_field base_sha)"
PACKET_CANDIDATE="$(packet_field candidate_sha)"
PACKET_CONTRACT_SHA="$(packet_field contract_sha256)"
PACKET_CLAUDE_SHA="$(packet_field claude_verdict_sha256)"
PACKET_CODEX_SHA="$(packet_field codex_verdict_sha256)"

# ---- precondition: the packet still describes what it was cut from -----------
# Recomputed here, not trusted. A packet whose recorded hashes no longer match
# the artifacts is not a frozen record of anything, and the reviewer must not
# be asked about it. (validate-phase.sh reports a disputed_criterion that
# disagrees with its directory as CHALLENGE-INCOMPLETE; here the same
# disagreement is a packet mismatch, because the criterion also came in as an
# argument and the two must name one criterion.)
mismatch() { # $1 field, $2 recorded, $3 recomputed
  die CLARIFY-PACKET-MISMATCH \
    "${DISC}: ${1} no longer matches what it records" \
    "recorded:   ${2}" \
    "recomputed: ${3}" \
    "a packet is frozen at creation; nothing here repairs one" \
    "if the candidate changed, a human archives this challenge and raises a new one"
}
[ "${PACKET_CRITERION}" = "${AC_ID}" ] || \
  mismatch disputed_criterion "${PACKET_CRITERION}" "${AC_ID}"
[ -f "${CONTRACT}" ] || mismatch contract_sha256 "${PACKET_CONTRACT_SHA}" "<no contract.md>"
[ -f "${CLAUDE_VERDICT}" ] || mismatch claude_verdict_sha256 "${PACKET_CLAUDE_SHA}" "<no claude-verdict.md>"
[ -f "${CODEX_VERDICT}" ] || mismatch codex_verdict_sha256 "${PACKET_CODEX_SHA}" "<no codex-verdict.md>"

NOW_CONTRACT_SHA="$(sha256 "${CONTRACT}")"
NOW_CLAUDE_SHA="$(sha256 "${CLAUDE_VERDICT}")"
NOW_CODEX_SHA="$(sha256 "${CODEX_VERDICT}")"
[ "${PACKET_CONTRACT_SHA}" = "${NOW_CONTRACT_SHA}" ] || \
  mismatch contract_sha256 "${PACKET_CONTRACT_SHA}" "${NOW_CONTRACT_SHA}"
[ "${PACKET_CLAUDE_SHA}" = "${NOW_CLAUDE_SHA}" ] || \
  mismatch claude_verdict_sha256 "${PACKET_CLAUDE_SHA}" "${NOW_CLAUDE_SHA}"
[ "${PACKET_CODEX_SHA}" = "${NOW_CODEX_SHA}" ] || \
  mismatch codex_verdict_sha256 "${PACKET_CODEX_SHA}" "${NOW_CODEX_SHA}"

# ---- precondition: the packet names the candidate both verdicts judge --------
# The artifact hashes above prove the packet still describes the same FILES.
# They say nothing about the commit it claims those files judged: a packet can
# carry three correct hashes and a candidate_sha nobody reviewed, and then the
# reviewer is asked about a commit that was never in front of either verifier.
#
# The reading rule is validate-phase.sh's, unchanged. claude-verdict.md binds on
# line 1, because the evidence-matrix template mandates line 1. codex-verdict.md
# binds in the last `---`-delimited provenance footer the gate harness appends,
# and falls back to line 1 when it has none. A line-1 sha may abbreviate what a
# footer spells out in full, so agreement is prefix agreement — git's own rule,
# and the one --new-challenge already applies to a candidate argument.
verdict_line1_sha() { # $1 file — the sha a line-1 `HEAD:` binds, or empty
  head -n1 "$1" | sed -n 's/^HEAD:[[:space:]]*\([0-9a-fA-F]\{7,40\}\)[[:space:]]*$/\1/p'
}
verdict_footer_sha() { # $1 file — head of the LAST delimited provenance footer
  awk '
    prev == "---" && $0 == "provenance:" {
      inprov = 1; found = 0; val = ""; prev = $0; next
    }
    inprov && $0 !~ /^[[:space:]]/ { inprov = 0 }
    inprov && !found && /^[[:space:]]+head:[[:space:]]*/ {
      line = $0
      sub(/^[[:space:]]*head:[[:space:]]*/, "", line)
      sub(/[[:space:]]*$/, "", line)
      val = line
      found = 1
    }
    { prev = $0 }
    END { if (found) print val }
  ' "$1" | sed -n 's/^\([0-9a-fA-F]\{40\}\)$/\1/p'
}
sha_agrees() { # $1 $2 — equal, or one is an abbreviation of the other
  [ -n "$1" ] || return 1
  [ -n "$2" ] || return 1
  case "$1" in "$2"*) return 0 ;; esac
  case "$2" in "$1"*) return 0 ;; esac
  return 1
}

CLAUDE_HEAD="$(verdict_line1_sha "${CLAUDE_VERDICT}")"
CODEX_HEAD="$(verdict_footer_sha "${CODEX_VERDICT}")"
if [ -z "${CODEX_HEAD}" ]; then CODEX_HEAD="$(verdict_line1_sha "${CODEX_VERDICT}")"; fi

candidate_mismatch() { # $1 which verdict disagreed
  die CLARIFY-PACKET-MISMATCH \
    "${DISC}: candidate_sha is not the commit ${1} judges" \
    "packet candidate_sha: ${PACKET_CANDIDATE}" \
    "claude-verdict.md binds: ${CLAUDE_HEAD:-<nothing binds it to a commit>}" \
    "codex-verdict.md binds: ${CODEX_HEAD:-<nothing binds it to a commit>}" \
    "a packet must name the commit both verdicts judged; nothing here repairs one" \
    "if the candidate changed, a human archives this challenge and raises a new one"
}
sha_agrees "${PACKET_CANDIDATE}" "${CLAUDE_HEAD}" || candidate_mismatch claude-verdict.md
sha_agrees "${PACKET_CANDIDATE}" "${CODEX_HEAD}"  || candidate_mismatch codex-verdict.md

# ---- precondition: the one shot is still unspent -----------------------------
# Adjudication is checked FIRST, and the order is the whole point. A challenge
# adjudicated on a clarification has BOTH files on disk, which is the ordinary
# end state, not a rare one. Asking about the clarification first would answer
# every such rerun with CLARIFY-EXISTS — true, but the lesser truth: the shot is
# spent AND the record is closed, and closed is what the caller must be told.
if [ -e "${RESULT}" ]; then
  die CLARIFY-ADJUDICATED \
    "${AC_ID} is already adjudicated: ${RESULT}" \
    "the challenge is closed; a reply now could only reopen a settled record"
fi
if [ -e "${CLAR}" ]; then
  die CLARIFY-EXISTS \
    "a clarification already exists for ${AC_ID}: ${CLAR}" \
    "one clarification per (candidate, criterion), ever — there is no second round" \
    "nothing here overwrites it; adjudicate on what is already recorded"
fi

# ---- precondition: subscription authentication, fail closed ------------------
# Variable NAMES are printed; values never are, and the captured login output is
# never echoed — it names the credential in use.
refuse_api_env() {
  echo "clarify-challenge: REFUSING — $1 is defined; the reviewer call could bill" >&2
  echo "clarify-challenge: the API, not the Pro plan. Unset it (value not shown)." >&2
  echo "STATUS: CLARIFY-AUTH"
  exit 4
}
[ "${OPENAI_API_KEY+x}" = x ] && refuse_api_env OPENAI_API_KEY
[ "${CODEX_API_KEY+x}" = x ] && refuse_api_env CODEX_API_KEY

LOGIN_STATUS=""
if ! LOGIN_STATUS="$(codex login status 2>&1)" || \
   ! printf '%s\n' "${LOGIN_STATUS}" | grep -qxF 'Logged in using ChatGPT'; then
  echo "clarify-challenge: REFUSING — the reviewer CLI is not authenticated with ChatGPT." >&2
  echo "clarify-challenge: run 'codex login', verify 'codex login status', then rerun." >&2
  echo "clarify-challenge: the captured status output is not echoed; it names a credential." >&2
  echo "STATUS: CLARIFY-AUTH"
  exit 4
fi

# ---- the clarification call --------------------------------------------------
# Global flags precede exec (verified on 0.146.0: exit 2 otherwise). Clean room:
# --ignore-user-config --ignore-rules --strict-config. Read-only sandbox,
# ephemeral session, foreground only — a backgrounded run is the empty-output
# condition of upstream issue codex#19945, and empty is INCONCLUSIVE here too.
REPLY_TMP="${WORK}/clarification.tmp.md"
ERR="${WORK}/stderr"
CODEX_STATUS=0
with_timeout "${CLARIFY_TIMEOUT}" \
codex --ask-for-approval never exec \
    --sandbox read-only \
    --ephemeral \
    --ignore-user-config \
    --ignore-rules \
    --strict-config \
    -m "${CLARIFY_MODEL}" \
    -c model_reasoning_effort=high \
    -o "${REPLY_TMP}" \
    "${CLARIFY_PROMPT}" \
    < "${DISC}" >/dev/null 2>"${ERR}" || CODEX_STATUS=$?

if [ "${CODEX_STATUS}" -ne 0 ]; then
  echo "clarify-challenge: the reviewer call exited ${CODEX_STATUS} (or timed out). stderr:" >&2
  tail -n 20 "${ERR}" >&2
  die CLARIFY-INCONCLUSIVE \
    "nothing was published, so the one clarification is NOT spent — rerun it" \
    "if it cannot be run at all, record  clarification_sha256: absent  with the" \
    "reason in challenge-result.md; the disagreement stays UNRESOLVED unless" \
    "already-recorded evidence adjudicates it"
fi
if [ ! -s "${REPLY_TMP}" ]; then
  tail -n 20 "${ERR}" >&2
  die CLARIFY-INCONCLUSIVE \
    "empty reply (#19945 class) — rerun in FOREGROUND Bash, never backgrounded" \
    "nothing was published, so the one clarification is NOT spent"
fi

# ---- provenance, then atomic publish -----------------------------------------
# The reply is published VERBATIM. Nothing here edits, trims, reformats or
# re-sections it: a clarification the tooling rewrote is not the reviewer's
# answer. The footer is appended below it and binds the file to this packet;
# validate-phase.sh recomputes it.
cat "${REPLY_TMP}" > "${STAGE}"
{
  echo ""
  echo "---"
  echo "clarify-provenance:"
  echo "  packet_sha256: $(sha256 "${DISC}")"
  echo "  disputed_criterion: ${AC_ID}"
  echo "  candidate_sha: ${PACKET_CANDIDATE}"
  echo "  base_sha: ${PACKET_BASE}"
  echo "  contract_sha256: ${PACKET_CONTRACT_SHA}"
  echo "  codex: $(codex --version 2>/dev/null | head -n1)"
  echo "  model: ${CLARIFY_MODEL} / effort=high / sandbox=read-only / ephemeral / clean-room"
  echo "  clarifier: ${CLARIFY_VERSION} ($(date -u +%Y-%m-%dT%H:%M:%SZ))"
} >> "${STAGE}"
# The staging file is a sibling of its destination, so publication is atomic:
# a reader sees either no clarification or the whole one, never a partial file.
# It is also NO-CLOBBER, and that is the part that matters. `mv -f` would
# happily replace a clarification published while this call was waiting for the
# reviewer, and that window is the whole length of the reviewer call — minutes,
# not microseconds. `ln` fails when the destination exists, so the file already
# on disk wins and this reply is discarded. The existence check before the call
# is a courtesy that saves a pointless invocation; this is the check that holds.
if ! ln "${STAGE}" "${CLAR}" 2>/dev/null; then
  rm -f "${STAGE}"
  die CLARIFY-EXISTS \
    "a clarification was published for ${AC_ID} while the reviewer was answering" \
    "on disk: ${CLAR}" \
    "one clarification per (candidate, criterion), ever — there is no second round" \
    "publication is no-clobber: the recorded reply is untouched and this one is" \
    "discarded, unpublished. Adjudicate on what is already recorded."
fi
rm -f "${STAGE}"

echo "CLARIFICATION: ${CLAR}"
echo "STATUS: CLARIFY-OK"
echo "NOTE: published verbatim. Structural validity is judged by validate-phase.sh,"
echo "NOTE: not here — run it before you rely on this file."
echo "NOTE: an invalid clarification, or one whose verification command is 'none',"
echo "NOTE: leaves the disagreement UNRESOLVED unless evidence already on record"
echo "NOTE: adjudicates it deterministically. The adjudication is yours to write."
