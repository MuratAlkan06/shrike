#!/bin/bash
# Install (or re-sync) the personal skills in this directory into ~/.claude/skills/.
#
# The tracked source tree here is canonical. The installed copy is a mirror of
# it, never the other way round: local edits under ~/.claude/skills are backed
# up, not merged and not silently kept.
#
#   absent        -> copy the tree, chmod +x the scripts   STATUS: INSTALLED
#   identical     -> change nothing                        STATUS: UP-TO-DATE
#   different     -> back it up, then mirror the source    STATUS: REPLACED
#
# Backups land in ~/.claude/skill-backups/, deliberately NOT under
# ~/.claude/skills/, where a stale copy could be discovered as a second skill.
# Nothing else under ~/.claude is read, written, or created.
#
# Usage: claude-skills/install.sh        (honours $HOME; tests override it)
set -euo pipefail

# The source is resolved PHYSICALLY, and a logical path that differs from it
# means this script was reached through a symlink. Refuse: the installer copies
# and replaces trees, and a link in the source path makes "the sandbox copy"
# and "the live tree" the same bytes. That is exactly how a fixture once
# reached back into the working tree it was supposed to be isolated from.
SRC_ROOT="$(cd "$(dirname "$0")" && pwd -P)"
SRC_ROOT_LOGICAL="$(cd "$(dirname "$0")" && pwd)"
if [ "${SRC_ROOT}" != "${SRC_ROOT_LOGICAL}" ]; then
  echo "install: REFUSING — the source path resolves through a symlink." >&2
  echo "install: logical:  ${SRC_ROOT_LOGICAL}" >&2
  echo "install: physical: ${SRC_ROOT}" >&2
  echo "install: run it by its real path; nothing was read or written." >&2
  echo "STATUS: SKILLS-SRC-SYMLINK"
  exit 2
fi
SKILL_NAME="verified-phase"
SRC="${SRC_ROOT}/${SKILL_NAME}"
: "${HOME:?HOME must be set}"
SKILLS_DIR="${HOME}/.claude/skills"
DEST="${SKILLS_DIR}/${SKILL_NAME}"
BACKUP_ROOT="${HOME}/.claude/skill-backups"

[ -d "${SRC}" ] || { echo "install: missing source tree: ${SRC}" >&2; exit 2; }
[ -f "${SRC}/SKILL.md" ] || { echo "install: missing ${SRC}/SKILL.md" >&2; exit 2; }

sha256() { # macOS ships shasum, not sha256sum
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

# Content + executability of every file, path-ordered. scripts/*.sh are
# normalised to executable on both sides so that the chmod below can never
# make an otherwise identical tree look different on the next run.
tree_fingerprint() { # $1 dir
  (
    cd "$1"
    find . -type f -print | LC_ALL=C sort | while IFS= read -r f; do
      bit='-'
      case "${f}" in
        ./scripts/*.sh) bit=x ;;
        *) if [ -x "${f}" ]; then bit=x; fi ;;
      esac
      printf '%s %s %s\n' "$(sha256 "${f}")" "${bit}" "${f}"
    done
  ) | shasum -a 256 | cut -d' ' -f1
}

harden() { # only ever touches files we just placed
  if ls "${DEST}"/scripts/*.sh >/dev/null 2>&1; then
    chmod +x "${DEST}"/scripts/*.sh
  fi
}

mkdir -p "${SKILLS_DIR}"

if [ ! -e "${DEST}" ]; then
  cp -R "${SRC}" "${DEST}"
  harden
  echo "SKILL: ${DEST}"
  echo "STATUS: INSTALLED"
  exit 0
fi

if [ -d "${DEST}" ] && [ "$(tree_fingerprint "${SRC}")" = "$(tree_fingerprint "${DEST}")" ]; then
  echo "SKILL: ${DEST}"
  echo "STATUS: UP-TO-DATE"
  exit 0
fi

stamp="$(date -u +%Y%m%dT%H%M%SZ)"
backup="${BACKUP_ROOT}/${SKILL_NAME}-${stamp}"
suffix=0
while [ -e "${backup}" ]; do
  suffix=$((suffix + 1))
  backup="${BACKUP_ROOT}/${SKILL_NAME}-${stamp}-${suffix}"
done
mkdir -p "${backup}"
if [ -d "${DEST}" ]; then
  cp -R "${DEST}/." "${backup}/"
else
  cp "${DEST}" "${backup}/"
fi
rm -rf "${DEST}"
cp -R "${SRC}" "${DEST}"
harden

echo "SKILL: ${DEST}"
echo "BACKUP: ${backup}"
echo "STATUS: REPLACED"
