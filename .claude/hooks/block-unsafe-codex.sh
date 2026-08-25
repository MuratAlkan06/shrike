#!/usr/bin/env bash
# PreToolUse DRIFT GUARD, v2.1.1 — default-deny for `codex` from Bash.
# Policy: the only sanctioned codex entry points inside Claude Code are the
# gate harness and a short read-only diagnostics list. Everything else is
# denied (exit 2) — including read-only invocations, since profile/config
# indirection made flag-matching unsound (verified: plain `codex exec` and
# `codex -p <profile> exec` bypassed the v2 keyword filter).
# Still a DRIFT guard: shell indirection (bash -c, scripts, npm run) can
# bypass it. Real isolation for write-capable codex = separate terminal.
INPUT="$(cat)"

# Extract .tool_input.command structurally when python3 exists (jq is not
# guaranteed on macOS); otherwise fall back to the raw JSON, which can only
# over-block (fail closed).
CMD="$(printf '%s' "${INPUT}" | python3 -c '
import json,sys
try:
    d = json.load(sys.stdin)
    if d.get("tool_name") == "Bash":
        print(d.get("tool_input", {}).get("command", ""))
except Exception:
    sys.stdout.write(sys.stdin.read() if False else "")
' 2>/dev/null || printf '%s' "${INPUT}")"

[ -z "${CMD}" ] && exit 0   # not Bash, or no command

# Is `codex` invoked at a command position (start, a control operator,
# grouping delimiter, command substitution, or a new line)?
if ! printf '%s' "${CMD}" | grep -Eq '(^|[;&|(`{]|\$\(|[[:cntrl:]])[[:space:]]*codex([[:space:]]|$)'; then
  exit 0   # e.g. git commit -m "codex verdict" — prose, allowed
fi

# Allow only complete, exact diagnostic commands. The harness contains
# `codex-gate.sh`, not a `codex` command token, so an ordinary harness call
# never reaches this branch. Prefix matching is deliberately forbidden:
# `codex --version; codex exec ...` must not inherit the diagnostic exception.
TRIMMED="$(printf '%s' "${CMD}" | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')"
case "${TRIMMED}" in
  "codex --version"|"codex login status"|"codex doctor") exit 0 ;;
esac

echo "Blocked (drift guard): direct codex invocation from Claude Code is not sanctioned. Tier 1 = /codex:review plugin; Tier 2 = ~/.claude/harness/codex-gate.sh." >&2
exit 2
