#!/usr/bin/env bash
# v2.1.1 per-repository rollback. Dry-run unless --apply is supplied.
# GLOBAL manifest entries are intentionally never removed here.
set -euo pipefail

TARGET="${1:?usage: uninstall.sh /path/to/repo [--apply]}"
MODE="${2:-}"
case "${MODE}" in
  ""|--dry-run) APPLY=0 ;;
  --apply) APPLY=1 ;;
  *) echo "usage: uninstall.sh /path/to/repo [--apply]" >&2; exit 2 ;;
esac

git -C "${TARGET}" rev-parse --git-dir >/dev/null 2>&1 \
  || { echo "not a git repo/worktree: ${TARGET}" >&2; exit 1; }
TARGET="$(cd "${TARGET}" && pwd -P)"
MANIFEST="${TARGET}/.codex-integration-manifest"
[ -f "${MANIFEST}" ] || { echo "missing manifest: ${MANIFEST}" >&2; exit 1; }

# Validate every repository entry before deleting anything.
while IFS= read -r entry || [ -n "${entry}" ]; do
  [ -z "${entry}" ] && continue
  scope="${entry%%:*}"
  path="${entry#*:}"
  [ "${scope}" = REPO ] || continue
  case "${path}" in
    "${TARGET}/"*) ;;
    *) echo "REFUSING unsafe manifest entry: ${entry}" >&2; exit 2 ;;
  esac
done < "${MANIFEST}"

if [ "${APPLY}" = 0 ]; then
  echo "DRY RUN — the following REPO files would be removed:"
else
  echo "Removing per-repository integration files:"
fi

while IFS= read -r entry || [ -n "${entry}" ]; do
  [ -z "${entry}" ] && continue
  scope="${entry%%:*}"
  path="${entry#*:}"
  [ "${scope}" = REPO ] || continue
  if [ "${APPLY}" = 1 ]; then
    if [ -e "${path}" ] || [ -L "${path}" ]; then
      rm -f -- "${path}"
      echo "REMOVE: ${path}"
    else
      echo "SKIP (already absent): ${path}"
    fi
  else
    echo "  ${path}"
  fi
done < "${MANIFEST}"

if [ "${APPLY}" = 1 ]; then
  rm -f -- "${MANIFEST}"
  echo "Removed manifest: ${MANIFEST}"
  echo "GLOBAL files were left untouched. Empty directories were left in place."
else
  echo "Review the list, then rerun with --apply to remove it."
fi
