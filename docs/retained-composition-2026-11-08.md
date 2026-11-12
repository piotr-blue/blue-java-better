# Retained composition corrections — 8 November 2026

Two real `myos-simplified` scenarios exposed defects in retained graph composition.
`ContractRetainedCompositionTest` reproduces them through the public `Contracts.Execution` API.
Both tests failed against the previous implementation and pass with these corrections.

## Shared descendants across independently initialized parents

When a retained parent is imported, its descendant can already exist in the in-progress
snapshot while not yet being reachable through an installed parent edge. The reachable
alias index then cannot find it. Importing a second parent previously allocated a fresh
key for that same descendant, leading to `SOURCE_PREDECESSOR_MISMATCH` on receiving.

`ContractGraph.importTarget` now retains an existing key when its complete document
metadata exactly equals the imported metadata. Distinct metadata still follows the existing
conflict/fresh-key path. This does not merge arbitrary independently owned equal-content
documents. Live alias ambiguity and imported edge conflicts remain checked.

## Multi-round source event occurrences

`ContractCompletions.install` previously flattened all turns of each cyclic member into
one output batch with serial zero. Whole execution retains each batch's turn serial and
output boundary. Those are inputs to the receiving turn's event occurrence identity.

Receiving now routes each recorded turn independently, preserving its serial, completion
identity and output. The test compares complete final root identity and exact root event
occurrence identities against whole-document processing, including a four-member ring.
The local integration campaign additionally repeats eight independent rings feeding one
ledger and mixed-provider shared Product-clause Offers.

These are implementation corrections. There are no native tariff, package identity or
execution profile changes. New receiving receipts commit the corrected batch structure;
old stored receipts remain immutable. Existing affected histories need explicit replay or
migration rather than silent rewriting. This does not address whole-root versus independent
invocation gas/failure atomicity or the remaining cost of large cyclic/evidence representations.
