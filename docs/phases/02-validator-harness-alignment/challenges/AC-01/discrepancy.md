# Discrepancy packet — AC-01

Neutral statement of one factual disagreement about one frozen acceptance
criterion. It argues for neither verifier. Nothing here amends the contract.

## Provenance
Every value below is recorded at packet creation and never edited afterwards.

disputed_criterion: AC-01
base_sha: 3d6ea7826a526697a0203c6ddc54931a4d02eb43
candidate_sha: edae82142ce52251a6ba17b913b0cc6ae3554230
contract_sha256: f40518fd51f4c482c51d44a7aaffebf9d184f884e473e1fe288af71b6f6e9c95
claude_verdict_sha256: 9690770264882440bbf76bc5a22087e57ad7eab8e055a5a0724e8260f419b780
codex_verdict_sha256: a6359f2755149cd8e1c91bbb5f6d2d24d779176d6eea351c62f6342570b69114

## Frozen criterion text
Copied verbatim from the approved contract at candidate_sha.

AC-01: validate-phase.sh binds the codex-verdict.md candidate SHA from the v2.1.1 harness provenance footer (`head:` line), still accepts a legacy line-1 `HEAD:` binding, requires agreement when both are present, and reports VERDICT-UNBOUND when neither is present.

## Claude verdict and cited evidence
Verdict: PASS
Evidence cited: "Fixture probes: harness-layout footer binds (STATUS: OK); spoofed early provenance block ignored, LAST footer bound; line-1/footer conflict refused naming both SHAs; neither form and headless footer fail closed; abbreviated line-1 with matching full footer accepted. Suite checks in sections 12b." (claude-verdict.md, AC-01 row)

## Codex verdict and cited evidence
Verdict: FAIL
Evidence cited: "claude-skills/verified-phase/scripts/validate-phase.sh:178-189 treats any final `provenance:` block followed by `head:` as a footer, without requiring the harness's `---` delimiter or excluding Markdown quotations. Therefore a verdict ending with quoted footer text can bind despite having neither a genuine footer nor line-1 binding, contrary to the required `VERDICT-UNBOUND` behavior." (codex-verdict.md, AC-01 row)

## The exact factual disagreement
One sentence naming the observable fact the two verdicts describe
differently. Not "who is right" — what is claimed to be true.

Whether validate-phase.sh, given a codex-verdict.md whose last provenance-shaped text is a quotation (no line beginning `---` before it, no line-1 `HEAD:`), reports VERDICT-UNBOUND (Claude's probes imply yes) or binds the quoted `head:` value as the candidate (Codex's reading of lines 178-189 implies it binds).

## Out of scope for this packet
Any criterion other than the disputed one, any design opinion, any remedy.
