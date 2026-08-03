#!/usr/bin/env bash
# Fails when an overclaiming phrase appears in a tracked file or in any commit
# message. PRINCIPLES.md section 7 states the rule; this script is the gate.
# Shell scripts are outside the scanned globs so the patterns can live here.
set -euo pipefail

readonly PATTERN='\bdistributed\b|exactly[ -]once|kafka[ -]compatible'

cd "$(git rev-parse --show-toplevel)"

status=0

# grep exits 1 when it matches nothing, which is the clean case, so every call
# is guarded and judged by its output instead of its exit code.
while IFS= read -r file; do
  [ -n "$file" ] || continue
  file_hits=$(grep -H -n -i -E -- "$PATTERN" "$file" || true)
  if [ -n "$file_hits" ]; then
    printf '%s\n' "$file_hits"
    status=1
  fi
done < <(git ls-files -- \
  '*.md' '*.java' '*.xml' '*.yml' '*.yaml' '*.properties' 'Dockerfile*' \
  ':(exclude)LICENSE*' ':(exclude)NOTICE*' ':(exclude)mvnw*' ':(exclude).mvn/**')

while IFS= read -r commit_sha; do
  [ -n "$commit_sha" ] || continue
  message_hits=$(git log -1 --format=%B "$commit_sha" | grep -n -i -E -- "$PATTERN" || true)
  if [ -n "$message_hits" ]; then
    printf 'commit %s (%s):\n' "$commit_sha" "$(git log -1 --format=%s "$commit_sha")"
    printf '%s\n' "$message_hits" | sed 's/^/  /'
    status=1
  fi
done < <(git log --format=%H)

if [ "$status" -ne 0 ]; then
  echo "Forbidden phrases found. Say what the broker actually does instead." >&2
  exit 1
fi

echo "No forbidden phrases in tracked files or commit messages."
