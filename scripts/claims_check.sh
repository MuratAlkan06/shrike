#!/usr/bin/env bash
# Fails when a README Claims row names evidence that does not exist.
# PRINCIPLES.md section 7: no evidence, no claim. Evidence is either a
# Class#method reference to a test, or a path in the repository.
set -euo pipefail

readonly README='README.md'

cd "$(git rev-parse --show-toplevel)"

if [ ! -f "$README" ]; then
  echo "$README is missing, so its claims cannot be checked." >&2
  exit 1
fi

status=0
checked_rows=0

claim_rows=$(awk '
  /^##[[:space:]]+Claims[[:space:]]*$/ { in_claims = 1; next }
  /^##[[:space:]]/ { in_claims = 0 }
  in_claims && /^\|/ { print }
' "$README")

while IFS= read -r row; do
  [ -n "$row" ] || continue

  claim=$(printf '%s' "$row" | awk -F '|' '{print $2}' | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')
  evidence=$(printf '%s' "$row" | awk -F '|' '{print $3}' | sed 's/^[[:space:]]*//; s/[[:space:]]*$//; s/`//g')

  # Skip the header row and the dashes-and-colons separator row.
  if [ "$claim" = 'Claim' ] && [ "$evidence" = 'Evidence' ]; then
    continue
  fi
  if printf '%s' "$claim" | grep -qE '^[-: ]+$'; then
    continue
  fi

  checked_rows=$((checked_rows + 1))

  if [ -z "$evidence" ]; then
    echo "Claim \"$claim\" names no evidence." >&2
    status=1
    continue
  fi

  if printf '%s' "$evidence" | grep -qE '^[A-Za-z_][A-Za-z0-9_]*#[A-Za-z_][A-Za-z0-9_]*$'; then
    class_name=${evidence%%#*}
    method_name=${evidence#*#}
    test_file=$(git ls-files -- "*/src/test/java/*/${class_name}.java" "*/src/test/java/${class_name}.java" | head -n 1)

    if [ -z "$test_file" ]; then
      echo "Claim \"$claim\" cites ${evidence}, but no ${class_name}.java exists under */src/test/java." >&2
      status=1
      continue
    fi

    if ! grep -qE "\b${method_name}[[:space:]]*\(" "$test_file"; then
      echo "Claim \"$claim\" cites ${evidence}, but ${test_file} has no ${method_name} method." >&2
      status=1
    fi
  elif [ ! -e "$evidence" ]; then
    echo "Claim \"$claim\" cites ${evidence}, which does not exist." >&2
    status=1
  fi
done <<EOF
$claim_rows
EOF

if [ "$status" -ne 0 ]; then
  echo "Claims without evidence found. Remove the claim or merge its proof." >&2
  exit 1
fi

echo "Claims table checked: ${checked_rows} claim(s), every one backed by evidence."
