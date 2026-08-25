#!/bin/bash
# verified-phase: structural validation of one phase directory.
#
# Checks the things a human should never have to re-check by eye: stable
# criterion IDs, HEAD-bound verdicts describing the same candidate, and the
# tamper-evidence of the bounded evidence challenge. It reads verdicts; it
# never writes, merges, or upgrades one. Nothing here can turn a non-pass
# into a pass.
#
# usage:
#   validate-phase.sh <phase-dir>
#   validate-phase.sh <phase-dir> --new-challenge <AC-ID> <candidate-sha>
#   validate-phase.sh <phase-dir> --release-check
#   validate-phase.sh --release-check <phase-dir>
#
# exit: 0 = OK/RELEASABLE, 1 = validation failure (see STATUS), 2 = usage or
#       not inside a git work tree.
set -euo pipefail

SKILL_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ASSETS="${SKILL_ROOT}/assets"

WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

sha256() { # macOS ships shasum, not sha256sum
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  else shasum -a 256 "$1" | cut -d' ' -f1; fi
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
    echo "usage: validate-phase.sh <phase-dir>"
    echo "       validate-phase.sh <phase-dir> --new-challenge <AC-ID> <candidate-sha>"
    echo "       validate-phase.sh <phase-dir> --release-check"
    echo "       validate-phase.sh --release-check <phase-dir>"
    if [ "$#" -gt 0 ]; then echo "error: $1"; fi
  } >&2
  echo "STATUS: USAGE"
  exit 2
}

# ---- arguments ---------------------------------------------------------------
MODE="validate"
PHASE_ARG=""
AC_ID=""
CANDIDATE=""

[ "$#" -ge 1 ] || usage "no phase directory given"
case "$1" in
  --release-check)
    MODE="release-check"; shift
    [ "$#" -ge 1 ] || usage "--release-check needs a phase directory"
    PHASE_ARG="$1"; shift
    ;;
  --new-challenge)
    MODE="new-challenge"; shift
    [ "$#" -ge 3 ] || usage "--new-challenge needs <AC-ID> <candidate-sha> <phase-dir>"
    AC_ID="$1"; CANDIDATE="$2"; PHASE_ARG="$3"; shift 3
    ;;
  -*)
    usage "unknown option: $1"
    ;;
  *)
    PHASE_ARG="$1"; shift
    if [ "$#" -gt 0 ]; then
      case "$1" in
        --release-check)
          MODE="release-check"; shift
          ;;
        --new-challenge)
          MODE="new-challenge"; shift
          [ "$#" -ge 2 ] || usage "--new-challenge needs <AC-ID> <candidate-sha>"
          AC_ID="$1"; CANDIDATE="$2"; shift 2
          ;;
        *)
          usage "unexpected argument: $1"
          ;;
      esac
    fi
    ;;
esac
[ "$#" -eq 0 ] || usage "unexpected trailing argument: $1"

# ---- repository + phase directory -------------------------------------------
if ! REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || [ -z "${REPO_ROOT}" ]; then
  echo "STATUS: NOT-A-GIT-REPO"
  echo "validate-phase: refusing — $(pwd) is not inside a git work tree." >&2
  echo "validate-phase: nothing was written." >&2
  exit 2
fi

PHASE_DIR=""
if [ -d "${PHASE_ARG}" ]; then
  PHASE_DIR="$(cd "${PHASE_ARG}" && pwd)"
elif [ -d "${REPO_ROOT}/${PHASE_ARG}" ]; then
  PHASE_DIR="$(cd "${REPO_ROOT}/${PHASE_ARG}" && pwd)"
else
  die MISSING-PHASE-DIR "no such phase directory: ${PHASE_ARG}" \
      "paths are resolved against the current directory, then the repo root."
fi
echo "REPO-ROOT: ${REPO_ROOT}"
echo "PHASE-DIR: ${PHASE_DIR}"

CONTRACT="${PHASE_DIR}/contract.md"
CLAUDE_VERDICT="${PHASE_DIR}/claude-verdict.md"
CODEX_VERDICT="${PHASE_DIR}/codex-verdict.md"
DECISION="${PHASE_DIR}/decision.md"

BASE_SHA=""
CANDIDATE_SHA=""

# ---- contract ----------------------------------------------------------------
validate_contract() {
  [ -f "${CONTRACT}" ] || die MISSING-CONTRACT "expected ${CONTRACT}"

  awk '
    /^[[:space:]]*AC-/ && $0 !~ /^[[:space:]]*AC-[0-9]+:/ { print NR ":" $0 }
  ' "${CONTRACT}" > "${WORK}/bad-ids"
  if [ -s "${WORK}/bad-ids" ]; then
    echo "DETAIL: malformed criterion line(s); the gate harness expects AC-01: ..."
    sed 's/^/  /' "${WORK}/bad-ids"
    die MALFORMED-CRITERION-ID
  fi

  awk '
    /^[[:space:]]*AC-[0-9]+:/ {
      line = $0
      sub(/^[[:space:]]*/, "", line)
      sub(/:.*/, "", line)
      print line
    }
  ' "${CONTRACT}" > "${WORK}/contract-ids"
  [ -s "${WORK}/contract-ids" ] || \
    die NO-CRITERIA "the contract declares no criteria (expected AC-01: ...)"
  if [ -n "$(LC_ALL=C sort "${WORK}/contract-ids" | uniq -d)" ]; then
    die DUPLICATE-CRITERION-ID "criterion IDs must be unique and never renumbered"
  fi

  BASE_SHA="$(sed -n \
    's/^[[:space:]]*[-*]*[[:space:]]*[Bb]ase SHA:[[:space:]]*\([0-9a-fA-F]\{7,40\}\)[[:space:]]*$/\1/p' \
    "${CONTRACT}" | head -n1)"
  [ -n "${BASE_SHA}" ] || die MISSING-BASE-SHA \
    "the contract must record the phase base as a line:  Base SHA: <sha>"

  echo "CRITERIA: $(wc -l < "${WORK}/contract-ids" | tr -d ' ')"
  echo "BASE-SHA: ${BASE_SHA}"
}

# ---- verdict files -----------------------------------------------------------
# The gate harness publishes codex-verdict.md with the reviewer's criterion
# table on line 1 and binds the candidate in the provenance footer it appends:
#
#     ---
#     provenance:
#       head: <40-hex>
#       base: <40-hex> (merge-base of <ref>)
#
# so a validator that insists on line 1 rejects every genuine gate verdict.
# The footer binding is read for the reviewer verdict; a hand-written
# line-1 `HEAD: <sha>` still binds, and when a file carries both they must
# name the same commit. claude-verdict.md is unchanged — the evidence-matrix
# template mandates line 1, so line 1 is what is read.
#
# Only a footer the harness itself delimited binds anything. The harness
# writes a line that is exactly `---` and, on the very next line, `provenance:`
# at column 0. Both are required here: a reviewer writing about this very
# machinery quotes `provenance:` and `head:` in its prose, indented or fenced,
# and footer-shaped prose must never name the candidate. If several delimited
# footers exist the LAST one wins; if that one carries no readable head, the
# file is unbound. Residual, stated plainly: prose that reproduces a whole
# delimited footer byte for byte is indistinguishable from the real thing by
# construction. This is a drift guard for a cooperating harness, not an
# adversarial boundary.
footer_head_sha() { # $1 file — head sha of the last delimited footer, or empty
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

validate_verdict() { # $1 file, $2 label, $3 "footer" when a provenance footer may bind
  local file="$1" label="$2" footer_ok="${3:-}" head_sha line1_sha footer_sha
  [ -s "${file}" ] || return 0

  line1_sha="$(head -n1 "${file}" | sed -n 's/^HEAD:[[:space:]]*\([0-9a-fA-F]\{7,40\}\)[[:space:]]*$/\1/p')"
  footer_sha=""
  if [ "${footer_ok}" = footer ]; then footer_sha="$(footer_head_sha "${file}")"; fi

  # Both present: they must name one commit. A hand-written line 1 may
  # abbreviate the sha the footer spells out in full — git's own rule, and the
  # rule --new-challenge already applies to a candidate argument — so the
  # footer, which is always the full 40, has to start with what line 1 claims.
  if [ -n "${line1_sha}" ] && [ -n "${footer_sha}" ]; then
    case "${footer_sha}" in
      "${line1_sha}"*) ;;
      *) die VERDICT-UNBOUND \
           "${label}: line 1 and the provenance footer bind different commits" \
           "line 1: ${line1_sha}" \
           "footer: ${footer_sha}" \
           "one file may not claim to judge two candidates; rebind or republish it" ;;
    esac
  fi

  head_sha=""
  if [ -n "${footer_sha}" ]; then head_sha="${footer_sha}"
  elif [ -n "${line1_sha}" ]; then head_sha="${line1_sha}"; fi
  if [ -z "${head_sha}" ]; then
    if [ "${footer_ok}" = footer ]; then
      die VERDICT-UNBOUND \
        "${label}: nothing binds this verdict to a commit — expected line 1" \
        "  HEAD: <sha>  or a footer delimited by a line that is exactly  ---" \
        "immediately above  provenance:  and carrying  head: <40-hex>" \
        "(line 1 is: $(head -n1 "${file}"))"
    fi
    die VERDICT-UNBOUND \
      "${label}: line 1 must be exactly  HEAD: <sha>  (found: $(head -n1 "${file}"))"
  fi

  if [ -z "${CANDIDATE_SHA}" ]; then
    CANDIDATE_SHA="${head_sha}"
  elif [ "${CANDIDATE_SHA}" != "${head_sha}" ]; then
    die VERDICT-SHA-MISMATCH \
      "the two verdicts describe different commits; they must judge one candidate" \
      "first: ${CANDIDATE_SHA}" \
      "${label}: ${head_sha}"
  fi

  if ! awk -F'|' '
    function trim(s) { gsub(/^[[:space:]]+|[[:space:]]+$/, "", s); return s }
    /^[[:space:]]*\|/ {
      id = trim($2)
      if (id ~ /^AC-/) {
        verdict = trim($3)
        if (id !~ /^AC-[0-9]+$/ || verdict !~ /^(PASS|FAIL|CANNOT-VERIFY)$/) { bad = 1 }
        else { print id }
      }
    }
    END { if (bad) exit 2 }
  ' "${file}" > "${WORK}/verdict-ids"; then
    die MALFORMED-VERDICT \
      "${label}: a criterion row has a bad ID or a verdict outside PASS|FAIL|CANNOT-VERIFY"
  fi
  if [ -n "$(LC_ALL=C sort "${WORK}/verdict-ids" | uniq -d)" ]; then
    die MALFORMED-VERDICT "${label}: duplicate criterion rows"
  fi
  if [ -n "$(LC_ALL=C comm -23 <(LC_ALL=C sort -u "${WORK}/verdict-ids") \
                               <(LC_ALL=C sort -u "${WORK}/contract-ids"))" ]; then
    die MALFORMED-VERDICT "${label}: cites a criterion the contract does not declare"
  fi
  if [ -n "$(LC_ALL=C comm -13 <(LC_ALL=C sort -u "${WORK}/verdict-ids") \
                               <(LC_ALL=C sort -u "${WORK}/contract-ids"))" ]; then
    echo "NOTE: ${label} does not yet cover every criterion in the contract."
  fi
  echo "VERDICT-OK: ${label} (HEAD ${head_sha})"
}

# ---- bounded evidence challenge ---------------------------------------------
validate_clarification() { # $1 file, $2 label
  local file="$1" label="$2" commands
  grep -qiE '^##[[:space:]].*claim'    "${file}" || die CLARIFICATION-INVALID \
    "${label}: missing the '## Claim' section"
  grep -qiE '^##[[:space:]].*evidence' "${file}" || die CLARIFICATION-INVALID \
    "${label}: missing the supporting-evidence section"
  grep -qiE '^##[[:space:]].*falsif'   "${file}" || die CLARIFICATION-INVALID \
    "${label}: missing the falsification section"
  grep -qiE '^##[[:space:]].*command'  "${file}" || die CLARIFICATION-INVALID \
    "${label}: missing the verification-command section"

  commands="$(awk '
    /^##/ { incmd = (tolower($0) ~ /command/) ? 1 : 0; next }
    incmd {
      line = $0
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", line)
      if (line == "") next
      if (line ~ /^```/) next
      if (line ~ /^</ && line ~ />$/) next
      n++
    }
    END { print n + 0 }
  ' "${file}")"
  if [ "${commands}" -gt 1 ]; then
    die CLARIFICATION-INVALID \
      "${label}: ${commands} command lines in the verification-command section" \
      "the reviewer may name at most one deterministic command, and nothing else"
  fi
  echo "CLARIFICATION-OK: ${label} (commands: ${commands})"
}

validate_challenge() { # $1 challenge dir
  local dir="$1" name disc clar res key value
  local rec_disc rec_clar now_disc now_clar
  name="$(basename "${dir}")"
  case "${name}" in
    AC-[0-9]*) ;;
    *) die CHALLENGE-INCOMPLETE \
         "challenge directory must be named for the disputed criterion: ${dir}" ;;
  esac

  disc="${dir}/discrepancy.md"
  clar="${dir}/codex-clarification.md"
  res="${dir}/challenge-result.md"

  [ -f "${disc}" ] || die CHALLENGE-INCOMPLETE "missing ${disc}"

  for key in disputed_criterion base_sha candidate_sha contract_sha256 \
             claude_verdict_sha256 codex_verdict_sha256; do
    value="$(sed -n "s/^${key}:[[:space:]]*//p" "${disc}" | head -n1)"
    case "${value}" in
      ""|"<"*) die CHALLENGE-INCOMPLETE \
                 "${disc}: provenance field '${key}' is missing or unfilled" ;;
    esac
    if [ "${key}" = disputed_criterion ] && [ "${value}" != "${name}" ]; then
      die CHALLENGE-INCOMPLETE \
        "${disc}: disputed_criterion (${value}) does not match the directory (${name})"
    fi
  done
  echo "CHALLENGE-OK: ${name} (packet provenance complete)"

  if [ -f "${clar}" ]; then validate_clarification "${clar}" "${name}/codex-clarification.md"; fi

  if [ -f "${res}" ]; then
    rec_disc="$(sed -n 's/^discrepancy_sha256:[[:space:]]*//p' "${res}" | head -n1)"
    rec_clar="$(sed -n 's/^clarification_sha256:[[:space:]]*//p' "${res}" | head -n1)"
    if [ -z "${rec_disc}" ] || [ -z "${rec_clar}" ]; then
      die CHALLENGE-INCOMPLETE \
        "${res}: must record discrepancy_sha256 and clarification_sha256" \
        "use 'clarification_sha256: absent' when the reviewer never replied"
    fi
    now_disc="$(sha256 "${disc}")"
    if [ -f "${clar}" ]; then now_clar="$(sha256 "${clar}")"; else now_clar="absent"; fi
    if [ "${rec_disc}" != "${now_disc}" ]; then
      die CHALLENGE-TAMPERED \
        "${disc} changed after adjudication" \
        "recorded: ${rec_disc}" "now:      ${now_disc}"
    fi
    if [ "${rec_clar}" != "${now_clar}" ]; then
      die CHALLENGE-TAMPERED \
        "${clar} changed after adjudication (or appeared/disappeared)" \
        "recorded: ${rec_clar}" "now:      ${now_clar}"
    fi
    chmod a-w "${disc}" "${res}" 2>/dev/null || true
    if [ -f "${clar}" ]; then chmod a-w "${clar}" 2>/dev/null || true; fi
    echo "ADJUDICATED: ${name} (hashes verified, artifacts sealed read-only)"
  fi
}

validate_challenges() {
  local dir
  [ -d "${PHASE_DIR}/challenges" ] || return 0
  for dir in "${PHASE_DIR}"/challenges/*; do
    [ -d "${dir}" ] || continue
    case "$(basename "${dir}")" in
      archive) echo "NOTE: skipping archived challenges in $(basename "${dir}")/"; continue ;;
    esac
    validate_challenge "${dir}"
  done
}

validate_core() {
  validate_contract
  validate_verdict "${CLAUDE_VERDICT}" "claude-verdict.md"
  validate_verdict "${CODEX_VERDICT}" "codex-verdict.md" footer
  validate_challenges
}

# ---- mode: new-challenge -----------------------------------------------------
new_challenge() {
  local ch_dir disc criterion tmp1 line
  case "${AC_ID}" in
    AC-[0-9]*) ;;
    *) die MALFORMED-CRITERION-ID "criterion ID must look like AC-01, got: ${AC_ID}" ;;
  esac
  case "${CANDIDATE}" in
    *[!0-9a-fA-F]*|"") usage "candidate sha must be hexadecimal: ${CANDIDATE}" ;;
  esac

  validate_core

  grep -qxF "${AC_ID}" "${WORK}/contract-ids" || \
    die UNKNOWN-CRITERION "${AC_ID} is not declared in ${CONTRACT}"

  [ -s "${CLAUDE_VERDICT}" ] && [ -s "${CODEX_VERDICT}" ] || \
    die CHALLENGE-INCOMPLETE \
      "both verdicts must exist and be preserved unchanged before a challenge" \
      "a challenge disputes two recorded verdicts; it never replaces one"

  case "${CANDIDATE_SHA}" in
    "${CANDIDATE}"*) ;;
    *) die VERDICT-SHA-MISMATCH \
         "the verdicts judge ${CANDIDATE_SHA}, not ${CANDIDATE}" ;;
  esac

  ch_dir="${PHASE_DIR}/challenges/${AC_ID}"
  if [ -e "${ch_dir}" ]; then
    die CHALLENGE-EXISTS \
      "a challenge already exists for ${AC_ID}: ${ch_dir}" \
      "one challenge per (candidate, criterion), ever — there is no second round" \
      "if the candidate commit changed, a human moves the old packet to" \
      "challenges/archive/<old-sha>/${AC_ID}/ before a new one is raised"
  fi

  criterion="$(sed -n "s/^[[:space:]]*\(${AC_ID}:.*\)$/\1/p" "${CONTRACT}" | head -n1)"
  [ -n "${criterion}" ] || die UNKNOWN-CRITERION "cannot read the frozen text of ${AC_ID}"

  [ -f "${ASSETS}/discrepancy-template.md" ] || \
    die MISSING-ASSETS "template not found: ${ASSETS}/discrepancy-template.md"

  mkdir -p "${ch_dir}"
  disc="${ch_dir}/discrepancy.md"
  tmp1="${WORK}/discrepancy.stage"

  sed -e "s|<AC-ID>|${AC_ID}|g" \
      -e "s|<base-sha>|${BASE_SHA}|g" \
      -e "s|<candidate-sha>|${CANDIDATE_SHA}|g" \
      -e "s|<contract-sha256>|$(sha256 "${CONTRACT}")|g" \
      -e "s|<claude-verdict-sha256>|$(sha256 "${CLAUDE_VERDICT}")|g" \
      -e "s|<codex-verdict-sha256>|$(sha256 "${CODEX_VERDICT}")|g" \
      "${ASSETS}/discrepancy-template.md" > "${tmp1}"

  # The criterion text is inserted literally, not through sed, so that
  # ampersands, slashes and pipes in the contract survive verbatim.
  while IFS= read -r line; do
    if [ "${line}" = "<frozen-criterion-text>" ]; then
      printf '%s\n' "${criterion}"
    else
      printf '%s\n' "${line}"
    fi
  done < "${tmp1}" > "${disc}"

  echo "CHALLENGE: ${disc}"
  echo "DETAIL: fill in both verdict sections and the factual disagreement."
  echo "DETAIL: the provenance block above them is already frozen — do not edit it."
  echo "STATUS: OK"
  exit 0
}

# ---- mode: release-check -----------------------------------------------------
release_check() {
  local blockers=0 id verdict waiver name_part
  local set_status="" dupes unknown missing
  validate_core

  [ -s "${DECISION}" ] || die NOT-RELEASABLE \
    "missing ${DECISION}: the merged matrix and the release decision live there"

  if ! awk -F'|' '
    function trim(s) { gsub(/^[[:space:]]+|[[:space:]]+$/, "", s); return s }
    /^[[:space:]]*\|/ {
      id = trim($2)
      if (id ~ /^AC-/) {
        verdict = trim($3)
        if (id !~ /^AC-[0-9]+$/ || verdict !~ /^(PASS|FAIL|CANNOT-VERIFY|UNRESOLVED)$/) { bad = 1 }
        else { print id " " verdict }
      }
    }
    END { if (bad) exit 2 }
  ' "${DECISION}" > "${WORK}/decision-rows"; then
    die NOT-RELEASABLE \
      "${DECISION}: a row has a bad ID or a verdict outside PASS|FAIL|CANNOT-VERIFY|UNRESOLVED"
  fi
  # A matrix with no rows at all is the extreme case of a row left out, and it
  # is reported as one below, naming every criterion that has no row.
  #
  # The merged matrix must cover the contract and nothing else. Reading rows
  # one at a time cannot see an absent row, and a repeated row hides which of
  # its verdicts counted, so the row set is compared with the contract the way
  # the gate harness compares a reviewer verdict with it: LC_ALL=C sort with
  # uniq -d for repeats, then cmp for set equality. All three problems are
  # reported; the status names the first class found, so it is deterministic.
  awk '{ print $1 }' "${WORK}/decision-rows" > "${WORK}/decision-ids"
  LC_ALL=C sort -u "${WORK}/decision-ids"  > "${WORK}/decision-ids.sorted"
  LC_ALL=C sort -u "${WORK}/contract-ids"  > "${WORK}/contract-ids.sorted"

  dupes="$(LC_ALL=C sort "${WORK}/decision-ids" | uniq -d | tr '\n' ' ')"
  unknown=""
  missing=""
  if ! cmp -s "${WORK}/decision-ids.sorted" "${WORK}/contract-ids.sorted"; then
    unknown="$(LC_ALL=C comm -23 "${WORK}/decision-ids.sorted" \
                                 "${WORK}/contract-ids.sorted" | tr '\n' ' ')"
    missing="$(LC_ALL=C comm -13 "${WORK}/decision-ids.sorted" \
                                 "${WORK}/contract-ids.sorted" | tr '\n' ' ')"
  fi
  if [ -n "${dupes% }" ]; then
    echo "DETAIL: ${DECISION}: repeated row(s) for: ${dupes% }"
    echo "DETAIL: one merged row per criterion; a repeat hides which verdict counted."
    set_status="DECISION-DUPLICATE-ROW"
  fi
  if [ -n "${unknown% }" ]; then
    echo "DETAIL: ${DECISION}: row(s) naming a criterion the contract does not declare: ${unknown% }"
    [ -n "${set_status}" ] || set_status="DECISION-UNKNOWN-ROW"
  fi
  if [ -n "${missing% }" ]; then
    if [ ! -s "${WORK}/decision-ids" ]; then
      echo "DETAIL: ${DECISION}: no merged criterion rows found at all."
    fi
    echo "DETAIL: ${DECISION}: no row for contracted criterion/criteria: ${missing% }"
    echo "DETAIL: every criterion in ${CONTRACT} is reconciled before a release."
    [ -n "${set_status}" ] || set_status="DECISION-MISSING-ROW"
  fi
  if [ -n "${set_status}" ]; then
    die "${set_status}" \
      "the merged matrix must carry one row for each contracted criterion, and no others"
  fi

  grep -n 'WAIVED-BY:' "${DECISION}" > "${WORK}/waivers" || true
  : > "${WORK}/valid-waivers"
  while IFS= read -r waiver; do
    [ -n "${waiver}" ] || continue
    name_part="${waiver#*WAIVED-BY:}"
    name_part="${name_part%%—*}"
    if printf '%s' "${name_part}" | grep -qiE '(^|[^a-z])(claude|codex|agent)([^a-z]|$)'; then
      echo "DETAIL: INVALID waiver — signed by an agent, not a human:"
      echo "  ${waiver}"
      blockers=$((blockers + 1))
    else
      printf '%s\n' "${waiver}" >> "${WORK}/valid-waivers"
    fi
  done < "${WORK}/waivers"

  while read -r id verdict; do
    [ -n "${id}" ] || continue
    case "${verdict}" in
      PASS)
        continue
        ;;
      FAIL)
        echo "DETAIL: ${id} is FAIL — a failing criterion is never waivable."
        blockers=$((blockers + 1))
        continue
        ;;
    esac
    # UNRESOLVED or CANNOT-VERIFY: needs a human waiver naming it, or a
    # human waiver that names no criterion at all (whole-file scope).
    if grep -qF "${id}" "${WORK}/valid-waivers" 2>/dev/null; then
      echo "WAIVED: ${id} (${verdict}) — human waiver on record"
    elif grep -qvE 'AC-[0-9]+' "${WORK}/valid-waivers" 2>/dev/null; then
      echo "WAIVED: ${id} (${verdict}) — unscoped human waiver on record"
    else
      echo "DETAIL: ${id} is ${verdict} with no human waiver."
      echo "DETAIL: add  WAIVED-BY: <name> <date> — <reason>  to ${DECISION},"
      echo "DETAIL: or resolve the row. No agent may write that line."
      blockers=$((blockers + 1))
    fi
  done < "${WORK}/decision-rows"

  if [ "${blockers}" -gt 0 ]; then
    echo "DETAIL: ${blockers} blocking row(s) or invalid waiver(s)."
    die NOT-RELEASABLE "nothing here was converted to a pass"
  fi
  echo "STATUS: RELEASABLE"
  echo "NOTE: structural checks passed. The release decision itself is Claude's,"
  echo "NOTE: made in the conversation — this script never issues GO."
  exit 0
}

case "${MODE}" in
  new-challenge) new_challenge ;;
  release-check) release_check ;;
  validate)
    validate_core
    echo "STATUS: OK"
    ;;
esac
