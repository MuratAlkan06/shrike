# Discrepancy packet — <AC-ID>

Neutral statement of one factual disagreement about one frozen acceptance
criterion. It argues for neither verifier. Nothing here amends the contract.

## Provenance
Every value below is recorded at packet creation and never edited afterwards.

disputed_criterion: <AC-ID>
base_sha: <base-sha>
candidate_sha: <candidate-sha>
contract_sha256: <contract-sha256>
claude_verdict_sha256: <claude-verdict-sha256>
codex_verdict_sha256: <codex-verdict-sha256>

## Frozen criterion text
Copied verbatim from the approved contract at candidate_sha.

<frozen-criterion-text>

## Claude verdict and cited evidence
Verdict: <PASS|FAIL|CANNOT-VERIFY>
Evidence cited: <file:line or command output line, verbatim>

## Codex verdict and cited evidence
Verdict: <PASS|FAIL|CANNOT-VERIFY>
Evidence cited: <file:line or command output line, verbatim>

## The exact factual disagreement
One sentence naming the observable fact the two verdicts describe
differently. Not "who is right" — what is claimed to be true.

<statement>

## Out of scope for this packet
Any criterion other than the disputed one, any design opinion, any remedy.
