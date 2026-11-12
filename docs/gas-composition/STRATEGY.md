# Compositional gas and incremental document execution

Status: implementation strategy, 9 November 2026. This is a proposed, explicitly selected accounting revision, not a claim that the published Contracts gas-6 specification already says this. Existing histories must continue under their selected rules. No migration may silently reinterpret an existing receipt.

## 1. The contract we want

For a causally ordered input, let W(d) be the deterministic semantic work actually performed by document d. The logical cost of the rooted transition is the sum of W(d) over the distinct executed document turns in its causal closure. An inactive sibling contributes zero. A child computation reused from a protected historical completion contributes its original cost, never zero and never twice.

For an acyclic A embedding B, with one selected B completion and one A reaction:

    G(A with B processed inline) = G(B) + G(A's own reaction)

The right-hand A cost excludes B. Adding the inclusive costs of every ancestor would double-count descendants. For a shared diamond, union the distinct completed work, then sum it; do not sum inclusive child totals. For a cycle, the accounting unit is the selected strongly connected component and all rounds actually executed. Each actual turn costs gas; an implementation must not split a cycle into independently committed guesses.

Identical semantic values expressed inline, by verified BlueId, or through inherited content have the same gas. Expansion, collapsing, fetching, decoding, cache lookup, storage, proof serialization, hashing of engine snapshots, and discovery of quiet siblings contribute no semantic gas. A business program that explicitly iterates over a million values still pays for a million iterations.

## 2. Gas is not a price for every implementation instruction

Three budgets serve different purposes:

1. **Semantic gas**, deterministic and part of the result. Meter BEX evaluation, authored control flow, actual handler calls, local turns, authored patches, emitted/delivered events and native operations under exact tariffs. Apply limits before the charged work.
2. **Deterministic admission limits**, covering accepted input size, depth, integer/text/output limits, graph growth and permissible native operation shapes. These must be explicit and versioned if they affect the semantic result.
3. **Host resource limits**, covering provider bytes, physical resolver work, memory, CPU deadlines, stack and transport. Exhaustion is incomplete execution, with no committed semantic outcome and no consumed input. It may be retried on a better provisioned host. It is not a deterministic business failure or evidence that the document is invalid.

Zero gas for expansion does not mean unlimited untrusted expansion. Immutable bodies and type graphs are verified once and retained by exact identity. A host stops before exceeding bounded work/resources and leaves the input pending. It must not change the successful outcome or its gas based on cache warmth.

The existing BEX counters for expression/statement execution, loop items, integer limbs, text work and produced value members remain. Engine-only counters for manifest opening, type traversal, object rebuilding, identity construction, topology partition, preflight and snapshot/proof bookkeeping are zero in the new schedule. Handler routing candidates are not charged merely because an engine scans them; actual delivery and actual execution are charged. Free index maintenance must not hide an authored global query.

## 3. An explicit revision, not changed gas under an old identity

Keep published gas-6 as the default compatibility mode. Add one explicitly selected compositional schedule to the existing Contracts configuration and interpreter. Bind its identifier into persisted snapshots, invocation policy, completion accounting, retained preparation configuration and host history mode. A restored snapshot under a different schedule is rejected. An old host must reject a new snapshot instead of interpreting it as gas-6.

This is an experimental accounting override of the pinned Contracts rules, pending a new package publication. Do not advertise its receipts as original-Java or Python gas-6 parity. The same interpreter must continue passing legacy vectors unchanged. The core document types and workflow language remain published Contracts/BEX; no alternate Blue/Coordinator or host-only authored document type is introduced.

## 4. Composition and limits

A protected completion must bind its exact before/after state, cause (event and timestamp), selected accounting schedule, ordered events, actual semantic gas, owned members, and attachment/channel lifetimes. The host must select the historical predecessor required by the parent's current view. A child being further ahead is not permission to consume its latest state. Foreign results require verification; a map supplied by a caller is not a trusted gas certificate.

A parent adopts only an eligible child transition. Removing an attachment first cancels later deliveries through that attachment. The independently processed child history remains valid and is not rolled back. Parent failure rolls back the parent's tentative adoption/reaction, not an already committed child history.

Accounting must retain distinct local versus inclusive work. Reused child cost is included once, but importing proof bytes is free. Multiple occurrences of the same child completion share the child cost; distinct parent handlers and deliveries each incur their own cost. Repeated delivery of the same input is rejected or recognized as already observed by coordination, not billed again as a new accepted input.

A shared cap must have the same success/failure threshold whether work is executed inline or reused. A single cached total is not sufficient to preserve gas-6's first-rejected microcharge. The new revision must either retain ordered debit fragments or explicitly make completed-unit admission atomic. This implementation must not claim exact failure-prefix equivalence until tested. Initially reuse may be enabled only where its accounting/failure contract is established; otherwise use the ordinary interpreter with the new schedule. This fallback is correct but is not the final locality architecture.

Use nonnegative, bounded integer arithmetic. Check multiplication and additions before overflow. Compare a debit to remaining allowance before adding. Never wrap a positive cost into a negative value. Invalid tariffs are configuration errors. An actual resource charge greater than the representable allowance must fail as gas exhaustion before its work starts, not escape as ArithmeticException after execution. Limit cyclic rounds through charged local turns; retain host limits for structural work which gas no longer bounds.

## 5. The physical execution boundary must also change

Changing gas alone cannot make a flat snapshot incremental. The production target is:

    process changed child's historical transition
    validate/adopt its exact change at the selected parent occurrence
    execute parent's real reaction
    publish changed state/index paths plus retained immutable references

An indexed source frontier establishes ordering and completeness. Internal sources share the local authority's completeness bound; external sources retain independent verified coverage. Provider acquisition runs independently and ahead of demand. An unchanged million-document graph should not require a million provider calls at every step. Missing external coverage still blocks progress.

For every graph-wide pass, classify it as: authored semantic work; required representation/commitment work; or avoidable implementation reconstruction. The existing flat native snapshot, dependencies, observations and canonical commitment can still require O(n) work even after gas becomes O(changed work). Storage paging after constructing those objects does not fix that. Replacing the required commitment format is a separate versioned decision, not an unreported implementation shortcut.

The production follow-up is a persistent native member/edge index, exact source-completion adoption, sparse per-attempt observations and structural sharing through publication. Materialize a complete ordinary Blue document when explicitly exporting it. Its content must match whole-root execution. If its current BlueId format needs a broad hash, record that cost honestly; do not substitute a storage-page digest for a BlueId.

## 6. Verification and rollout gates

Organize tests by accounting laws, resource safety, composition semantics, and durable integration.

- Legacy mode: existing vectors, receipts and rollback stay unchanged.
- Representation: inline versus referenced content; inherited declaration versus expanded declaration; warm versus cold/protected restore.
- Locality: 10 and 1000 quiet siblings, same selected Order/Payment update and same parent reaction. Check semantic gas and relevant values/events; full documents naturally differ because they contain different numbers of siblings.
- Decomposition: whole-root versus separately executed child plus parent. Compare final root BlueId and ordered public event identities/payloads, inclusive gas and local accounting. Test shared descendants and multiple source completions.
- Limits: exact sufficient cap, one less, zero, per-member caps, oversized arithmetic, infinite BEX loop and cyclic feedback. Validate rollback and that failed parent adoption leaves independent child history intact.
- Ordering: equal timestamps across timelines, removal before a pending child result, historical catch-up, missing external completeness, and restart between child and parent publication.
- Cycles: self-cycle, two-member cycle, A-B-C-D-A and a diamond feeding a cycle. Dynamic SCC changes retain existing explicit restrictions until proven.
- Persistence: bind schedule and provenance; reject mixed-schedule restores and reused results; no silent rewrite of old histories.

Measure initialization separately from steady-state changes. Restart the owner execution between measured inputs, retain only immutable package data, and exclude oracle replay from the timed interval. Record restore, execute, prepare, storage and end-to-end time; member/edge visits and bytes as well as milliseconds. Keep all samples and distinguish JFR/counter diagnostics from unprofiled medians.

Apply to local integration first. Then configure the AWS host to support the same explicitly selected schedule for fresh histories, run isolated DynamoDB qualification and a live clause journey, and deploy only after gates pass. Existing accounts/histories retain their original mode. AWS latency is not inferred from local timings.

## 7. Completion criteria and honest reporting

The first gate is representation-independent, sibling-independent semantic gas with preserved business behavior and bounded failure handling. The next gate is safe completed-work adoption. The final gate is physical locality through restoration and publication. These are separate results and must be reported separately.

A lower gas number is not a performance result. Equal business totals are not complete semantic parity. An observed speedup is not proof of constant complexity. Every accepted implementation change must identify which complete pass it removes, what remains, and which tests establish the new contract.

## 8. Implemented draft and deliberate differences

The opt-in identifier is `contracts-compositional-gas-1` (`GasSchedule.COMPOSITIONAL_V1`). The default remains `contracts-deterministic-gas-6` (`GasSchedule.PUBLISHED`). This is a draft semantics revision; selecting the published base Contracts rules together with this override does not claim published gas-6 conformance. No new authored `Core` document, interpreter or execution model is introduced.

The semantic Contracts counters are handler calls, scope initialization, pointer segments, patch boundary checks, patch add/replace/remove, document-update delivery, internal-event enqueue/dequeue, triggered/embedded/lifecycle event delivery, termination, closure queue work, local turn opening/sealing and completion-batch installation. Existing prices apply to these counters. BEX execution charges remain. Loading or expanding a definition and reconstructing a Blue representation do not charge semantic gas. Explicit identity operations retain their BEX request charge, but do not additionally charge representation-size traversal.

Native timeline admission outside a local contract turn uses a separate bounded host-work meter (currently 10 million units). Exhaustion returns `HOST_INCOMPLETE`, leaving the attempt retryable instead of consuming contract gas or committing a business failure. Arithmetic multiplication and accumulation reject overflow before work. A trusted native extension that loops without consulting its meter cannot be preempted by this Java mechanism: process memory, time and scheduling limits are still necessary. The gas schedule is not a claim that arbitrary native Java code is sandboxed.

New-schedule snapshots, retained completions and portable execution policy bind the schedule. Persisted outcomes contain a `gasByMember` ledger. For a Payment → Order → Agreement path, measured inclusive gas was 51, 104 and 157. This means own work was 51 + 53 + 53 = 157; adding the inclusive totals would count child work repeatedly. Reusing the same member result through a diamond or cycle does not charge it twice. A selected retained source must include its execution root; selecting only a subset of an inclusive source is rejected.

For LIVE adoption, if an imported result cannot fit the shared or per-member gas caps, execution falls back to the original input path. This preserves the first rejected microcharge and rollback behavior rather than fabricating an aggregate failed charge. HISTORICAL adoption retains its separate seedless replay contract; identical failure prefixes are not claimed for that mode.

Zero-work member proofs are sparse in this schedule. This reduces retained per-attempt provenance, but the current scheduler still enumerates graph members. Reused-result receipts describe their own provenance and therefore need not equal receipts from a whole-root invocation. Verified equivalence here means the full resulting root BlueId, exact gas and ordered public emitted events. Published-mode exact receipt parity remains required by its existing tests.

A further semantics change was necessary for dynamic creation: a new embedded member's identity must not depend on which ancestor happened to invoke the execution. In the new schedule, dynamic identities derive from the creating member, attachment path, retained edge generation and exact child value. Whole-root execution and independent child execution now create the same member/event identities. Published allocation is unchanged. This change must be reviewed as part of the proposed semantics edition, not hidden as a price-only adjustment.

Physical locality is unfinished. Native rooted restore, some admission/dependency-vector work, final validation and publication still depend on the graph. The measurements below belong in a separate qualification report; equal gas must never be presented as constant elapsed time.

The additive equality above describes successful execution under sufficient shared and local allowances. When a parent has less allowance than an already-completed child's work, the independent child's history remains valid, but the parent must fail at its own budget boundary. It cannot import the complete child's gas past that cap. Failed child attempts are not successful source completions: the hosts replay the original cause for the parent. A successful source whose predecessor differs from the parent's retained view also falls back to ordinary processing. These fallback paths preserve correctness and currently sacrifice locality for exceptional cases.

Language already has independent host limits: default 10 million abstract steps, depth 256, one million values, 100,000 provider fetches, 16 MiB input text, and bounded numeric token/decimal sizes. `OperationLimitExceeded` is translated to `HOST_INCOMPLETE` by the Contracts bridge. These limits complement semantic gas and the native-admission budget; neither in-process limit replaces the host's memory/process boundary.
