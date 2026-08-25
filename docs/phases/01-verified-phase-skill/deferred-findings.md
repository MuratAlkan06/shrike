# Phase 01-verified-phase-skill — Deferred findings

Pre-existing limitations observed during verification, explicitly out of this
phase's scope (the phase changes no gate machinery, CI, or host tooling).
Recorded here as phase-history findings to be evaluated later from actual
phase history.

1. STATUS: DEFERRED — The v2.1.1 hook block-unsafe-codex.sh python3-exception
   path allows the call on malformed hook input, i.e. it fails open despite
   its fail-closed comment.
2. STATUS: DEFERRED — Neither timeout nor gtimeout exists on this machine, so
   the harness EVIDENCE_TIMEOUT/CODEX_TIMEOUT settings are no-ops; runaway
   evidence or reviewer runs are bounded only by the caller's Bash timeout.
3. STATUS: DEFERRED — shellcheck is not installed, so the suite's shellcheck
   check skips cleanly (suite line: ok 105); static-analysis coverage for the
   shell scripts is reduced on this host.
4. STATUS: DEFERRED — .codex-integration-manifest is ignored only via
   .git/info/exclude, which does not travel with the repository; a fresh
   clone would show it as untracked dirt.
5. STATUS: DEFERRED — The installed Codex CLI is 0.149.1 behind a wrapper
   shim, while the harness flags were verified against 0.146.0; flag drift
   between the two versions is unverified.
6. STATUS: DEFERRED — No CI job yet runs
   claude-skills/tests/verified-phase-tests.sh; the suite is enforced only
   through the local gate's TYPECHECK evidence block.
7. STATUS: DEFERRED — An unscoped human WAIVED-BY line clears all non-pass
   rows in its file. This is deliberate in v2.1.1, but AC-scoped waivers are
   recommended to avoid unintentionally waiving unrelated rows.
