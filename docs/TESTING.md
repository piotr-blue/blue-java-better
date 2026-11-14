# Test strategy

Tests are grouped by the invariant they own. Normal `test` runs the complete library suite;
`build` also checks formatting, compiler warnings, Javadoc and benchmark compilation. Tests use
checked-in data and do not execute Python or read a sibling source checkout.

| Boundary | Tests | What a failure means |
| --- | --- | --- |
| Published semantic oracle | `LanguageConformanceTest`, `CanonicalBexConformanceTest`, `CanonicalContractsConformanceTest` | Canonical values, exact gas, observations or receipts changed |
| Public configuration | `CanonicalConfigurationTest`, `LanguageConfigurationTest`, `blue.contracts.ContractsConfigurationTest`, `ContractProgramsTest` | API selection, immutable configuration or invocation isolation changed |
| Language | `blue.language.*`, `Language*Test`, `NodeTest` | Parsing, identity, lists, inheritance, evidence or immutable source values changed |
| Public value types | `blue.language.PointerTest`, `BlueIdTest`, `RationalTest` | Pointer parsing/escaping/prefix rules, identity syntax or exact schema arithmetic changed |
| BEX configuration | `BexConfigurationTest`, `CanonicalBexApiTest` | Builder validation, compilation entry points, intrinsics, host gas charges or input channels changed |
| Packages | `Package*Test` | Publication identity, dependency resolution or namespace mapping changed |
| Contracts lifecycle | `ContractInitializationTest`, `ContractTopologyTest`, `ContractManagedBoundaryTest` | Initialization, attachment, termination or cyclic topology changed |
| Type generalization | `blue.contracts.TypeGeneralizationTest`, `GeneralizationPolicyTest` | Repair by nearest valid ancestor, floors, rule precedence or policy validation changed |
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

`DiagnosticSwitchesTest` pins the name and default of every `blue.physical.*` and
`blue.canonical.*` system property. Suites that prove an optimization is neutral flip those
switches under `@ResourceLock(Resources.SYSTEM_PROPERTIES)`; they are documented in
`DiagnosticSwitches`.

Performance probes report physical time separately from gas. Use identical inputs and starting
snapshots, state whether user-document state is resident, separate initialization from processing,
and measure restoration/publication/end-to-end latency when making claims about a durable host.

`./gradlew languageInputAllocationProbe` runs helper, strict Language and generic node JSON readers
in a dedicated 128 MiB JVM. After 4,000 unrelated field names, each set of 64 small reads must stay
within 32 KiB per read. The parser consumes Java strings; field-name canonicalization is disabled
so a request does not inherit a vocabulary grown by unrelated documents. Deserializer and buffer
reuse remain enabled. This checks allocated bytes for a fixed input, not latency or total process
heap. Ordinary language/conformance tests retain responsibility for syntax, identity and gas.

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

## Inspect physical counters for one payment

Run `./gradlew contractCounterProbe` to initialize a sample Agreement, attach one Order and Payment, and settle the Payment. The probe uses a fresh JVM and prints the native receipt and gas, followed by immutable physical-counter snapshots per phase and for the process. Compare fixed-size phases across branches. Wall-clock spans include work on the executing thread. Counters and timing observations never participate in the receipt or semantic gas schedule.

## Inspect unexercised code

Run `./gradlew jacocoTestCoverageVerification` to instrument the ordinary JUnit suite in its own JVM. Open `build/reports/jacoco/test/html/index.html` to inspect missed branches. The module-wide regression floors are 90% line and 76% branch coverage. A branch exercised by a test still needs an assertion that distinguishes correct behavior. Ordinary tests and JMH run without this instrumentation.

## Check whether tests detect changes

Run `./gradlew mutationTest` to run the focused PIT suite. Open `build/reports/mutation/index.html` and inspect each surviving mutation in context. A surviving change can expose a missing assertion or behaviorally equivalent code. Investigate the affected invariant before changing a test.

`./gradlew numericCacheRetentionProbe` projects 64 distinct definitions, each containing a 3 MiB
integer, through the actual program-definition cache in a fresh 96 MiB JVM. Every value must remain
exact. Keeping those oversized constants would exceed the heap; declining retention must permit
the workload to complete. This gate checks one cache boundary, not all application memory use.

Select another production boundary with `-PmutationClasses=CLASS_GLOB` and its tests with `-PmutationTests=TEST_GLOB`. Quote wildcard arguments in the shell. PIT reports the selected classes only. It runs in forked JVMs and stays outside the release artifact. [PIT command-line options](https://pitest.org/quickstart/commandline/)

### Closed execution retention

Run `./gradlew executionRetentionProbe` to check the host lifetime boundary in a dedicated 128 MiB JVM. The program keeps the returned document state reachable, closes its execution and verifies collection of the host behind each reader. An unregistered host is the control. The exported state must keep its BlueId while releasing completion storage and calculation storage. The second half retains discarded and committed transaction handles and checks that their original sessions and previously returned transitions can be collected independently. This collector-dependent check is separate from the deterministic unit suite.

The same probe retains escaped authoring editors after successful and failed callbacks. Neither
the original root nor its provider closure may remain reachable through the editor. A successful
edit's returned immutable value remains usable. `NodeTest` separately checks that both mutation
methods reject use after the callback; those ordinary tests do not depend on garbage collection.

## Native read projection budget

Run `./gradlew nativeReadBudgetProbe` to exercise a native step on a 192 MiB heap. Nineteen binary references give the same leaf over half a million paths. Reading the entire subtree must return `PROJECTION_LIMIT_EXCEEDED` and preserve the checkpoint. Selecting one leaf must still succeed. This checks that expansion stops while the output is being built. [Bounded-heap probe](../src/demo/java/blue/NativeReadBudgetProbe.java)
