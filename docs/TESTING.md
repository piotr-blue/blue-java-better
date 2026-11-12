# Test strategy

Tests are grouped by the invariant they own. Normal `test` runs the complete library suite;
`build` also checks formatting, compiler warnings, Javadoc and benchmark compilation. Tests use
checked-in data and do not execute Python or read a sibling source checkout.

| Boundary | Tests | What a failure means |
| --- | --- | --- |
| Published semantic oracle | `LanguageConformanceTest`, `CanonicalBexConformanceTest`, `CanonicalContractsConformanceTest` | Canonical values, exact gas, observations or receipts changed |
| Public configuration | `CanonicalConfigurationTest`, `LanguageConfigurationTest`, `ContractProgramsTest` | API selection, immutable configuration or invocation isolation changed |
| Language | `blue.language.*`, `Language*Test`, `NodeTest` | Parsing, identity, lists, inheritance, evidence or immutable source values changed |
| Packages | `Package*Test` | Publication identity, dependency resolution or namespace mapping changed |
| Contracts lifecycle | `ContractInitializationTest`, `ContractTopologyTest`, `ContractManagedBoundaryTest` | Initialization, attachment, termination or cyclic topology changed |
| Atomic execution | `CanonicalTransactionTest`, `CanonicalPortableTest`, `CanonicalReuseTest` | Commit/discard, replay, gas limits or cache neutrality changed |
| Semantic gas | `CompositionalGasTest`, `ContractRetainedCompositionTest` | Representation neutrality, sibling independence, additive child/parent gas, overflow or bounded cyclic work changed; `GasSchedule.STANDARD` is the sole policy, documented in [SEMANTIC-GAS.md](SEMANTIC-GAS.md) |
| Durable records | `ContractExecutionTest`, `ContractPreparationTest`, `ContractPreparationDeltaTest`, `ContractPreparationTopologyTest`, `ContractCalculationReuseTest`, `ContractSnapshot*Test` | Stored evidence or physical reuse changed semantic outcomes |
| Native capabilities | `blue.contracts.*`, `NativeAdmissionReplayTest` | Authority, registration, callback lifetime, limits or native admission changed |
| Timelines | `blue.coordination.*`, `TimelineValidationReuseTest` | Signature, historical completeness, input identity or scope checks changed |
| Real clauses | `CanonicalClausesTest`, `IncrementalDocumentsTest`, `CanonicalAgreementTest` | Published installation or Agreement/Order/Payment behavior changed |
| Tutorials | `blue.examples.*` | A documented canonical example stopped working |

Useful selectors: `languageTest`, `packageTest`, `canonicalBexTest`, `contractsTest`, `coordinationTest`,
`conformanceTest`, `scenarioTest`, `concurrencyTest`. A selector is not a substitute for the full gate.

The removed Local-1 and detached-runtime suites asserted different semantics. Canonical portions of
mixed suites were retained and migrated, including language ingress, native steps/channels/admission,
projection, timeline verification/reads, and language tutorial examples. The old Local-1 BEX vector
set is superseded by the canonical BEX corpus. Do not count retired tests as passing tests.

Performance probes report physical time separately from gas. Use identical inputs and starting
snapshots, state whether user-document state is resident, separate initialization from processing,
and measure restoration/publication/end-to-end latency when making claims about a durable host.

Protected recovery has two independent gates: `blue.language.CheckpointTest` verifies lazy provider
retention across builders and processors, and strict ordinary reads; `ContractPreparationTopologyTest`
compares birth/detachment deltas with a complete export and cold native replay. Neither cache nor
private preparation establishes Timeline authorization. Structural edits still use full graph
execution even when their preparation publication is incremental.

`LocalOwnerExecutionTest` also owns structural recovery from a frozen collection of protected owner
checkpoints: exact ordinary-execution observations, gas, identities and rejection of corrupted or
incomplete attachments. That API reuses completed admission within a revocable attempt, checks the
actual attachment edges, and never advertises a missing full-graph preparation as a delta base.
`ContractCacheTest` owns physical retention bounds, replacement identity, LRU recency and overflow;
these choices have no effect on gas or authorization.
