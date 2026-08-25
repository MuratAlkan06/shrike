# Discrepancy packet — AC-02

Neutral statement of one factual disagreement about one frozen acceptance
criterion. It argues for neither verifier. Nothing here amends the contract.

## Provenance
Every value below is recorded at packet creation and never edited afterwards.

disputed_criterion: AC-02
base_sha: 5803432e5b28579d8800eb0a3302c19a4d4c4fa3
candidate_sha: 4b3a574c3651c7ef71ff5dd6ee4e4000a49ab5fe
contract_sha256: 9fa99a6ebf0afc74ba42e19a2621beefe59989938d208c11ef7e6f6e5c4a91eb
claude_verdict_sha256: b864a374a8f9a0feafa8554844423fe6914dc4d3f42faee5688fd90724c37536
codex_verdict_sha256: f7751cfe4e20ca8563ab063c5b3e2809efd573f3c24954355820a9d70c0aa262

## Frozen criterion text
Copied verbatim from the approved contract at candidate_sha.

AC-02: A second clarification for the same candidate and criterion is refused with distinct status CLARIFY-EXISTS, and an adjudicated challenge is refused with CLARIFY-ADJUDICATED; the script never overwrites an existing clarification.

## Claude verdict and cited evidence
Verdict: PASS
Evidence cited: "adjudication is rechecked at publish time, so a challenge-result.md appearing during the reviewer call refuses CLARIFY-ADJUDICATED post-call with nothing published (non-vacuity proven)" and "publication is an atomic no-clobber hard link" (claude-verdict.md, AC-02 row).

## Codex verdict and cited evidence
Verdict: FAIL
Evidence cited: "the post-call adjudication check and publication are separate operations at claude-skills/verified-phase/scripts/clarify-challenge.sh:360-390. If challenge-result.md appears after the final [ -e \"${RESULT}\" ] check but before ln, the clarification is published into an adjudicated challenge instead of returning CLARIFY-ADJUDICATED." (codex-verdict.md, AC-02 row).

## The exact factual disagreement
One sentence naming the observable fact the two verdicts describe
differently. Not "who is right" — what is claimed to be true.

Both verdicts agree the recheck and the link are two operations with a nonzero interval between them. The observable fact they describe differently is the consequence: whether the state that interval can produce — a codex-clarification.md present inside a challenge whose challenge-result.md records clarification_sha256: absent — can ever stand as a valid record, or is always refused by validate-phase.sh when the record is next validated.

## Out of scope for this packet
Any criterion other than the disputed one, any design opinion, any remedy.
