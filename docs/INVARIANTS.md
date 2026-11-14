# Invariants for maintainers and host integrators

Use this map when reviewing a change. The architecture explains how a component works;
this page identifies what its replacement must preserve and where that promise is exercised.
A passing selected test is evidence for that boundary, not certification of the whole runtime.

| Boundary | Required invariant | Implementation and regression evidence |
| --- | --- | --- |
| Content identity | A BlueId commits to exact content. A matching hash does not establish permission, current membership or trustworthy host preparation. | [Language](../src/main/java/blue/Language.java), [identity tests](../src/test/java/blue/LanguageIdentityTest.java), [security boundary](../SECURITY.md) |
| Provider lookup | Missing evidence remains incomplete. A failed or incorrect answer cannot become a proof of absence. Shared providers own thread safety and I/O deadlines. | [Provider contract](../src/main/java/blue/LanguageProvider.java), [provider tests](../src/test/java/blue/language/ProviderTest.java) |
| Literal selection | Slash and tilde inside a field name remain data when using `field`; `item` selects Blue's `/items/index` path. Malformed pointer escapes are rejected even next to line terminators. | [View API](../src/main/java/blue/LanguageView.java), [pointer grammar](../src/test/java/blue/PointerGrammarTest.java) |
| Mutable authoring | An editor belongs to its callback and creating thread. Retaining it cannot mutate a returned immutable value later. | [Node API](../src/main/java/blue/Node.java), [Node tests](../src/test/java/blue/NodeTest.java) |
| Snapshot ownership | Caller-owned nested maps and lists, including runtime tariffs, cannot change a restored state's identity after validation. | [Snapshot restore](../src/main/java/blue/ContractStorage.java), [restored binding tests](../src/test/java/blue/RestoredLocalExecutionTest.java) |
| Restored owner processing | A restored-state handle belongs to one open execution and its validated predecessor. It cannot be replaced by an ordinary graph, foreign attempt or unpublished outcome. | [Execution API](../src/main/java/blue/Contracts.java), [restored execution tests](../src/test/java/blue/RestoredLocalExecutionTest.java) |
| Source completion | A parent consumes the historical child result and embedding generation selected by the host. A newer child head is not interchangeable. | [Owner processing](../src/main/java/blue/ContractLocal.java), [local execution tests](../src/test/java/blue/LocalOwnerExecutionTest.java) |
| Speculation | A failed or stale candidate cannot publish a partial result or warm another invocation's logical evidence. | [Transaction rules](CANONICAL-TRANSACTIONS.md), [concurrent publication tests](../src/test/java/blue/ContractsConcurrencyTest.java) |
| Cache reuse | Retained facts are immutable and bounded. Cache warmth cannot change gas, validation, ordered events or authorization. Oversized facts remain usable without retention. | [Cache](../src/main/java/blue/ContractCache.java), [weight and replacement tests](../src/test/java/blue/ContractCacheTest.java), [evidence reuse tests](../src/test/java/blue/ContractEvidenceCacheTest.java) |
| Inspection | A projection's work/output allowance is independent of semantic gas and resets per call. Exceeding it cannot mutate the document. | [Projection tests](../src/test/java/blue/contracts/ProjectionLimitsTest.java), [native projection tests](../src/test/java/blue/NativeProjectionTest.java) |
| Content scope | Closing an execution revokes borrowed readers without erasing independently retained immutable evidence. | [Content-scope tests](../src/test/java/blue/ContractContentScopeTest.java), [snapshot scope tests](../src/test/java/blue/ContractLanguageSnapshotTest.java) |
| Compatibility | Additions preserve existing declarations and meanings. A renamed type, ambiguous overload or changed gas policy needs explicit compatibility review. | [API baseline](../api/README.md), [gas policy](SEMANTIC-GAS.md), [readiness decisions](PRODUCTION-READINESS.md) |

## Choose the smallest suitable public API

Use `LanguageDocument` and `LanguageView` for immutable content and contextual reads. Use
`Contracts.Session` when one process owns a whole graph. A durable host uses
`Contracts.Execution` for one attempt, publishes its result conditionally and closes the attempt.
Do not introduce a cache of live mutable executions merely to avoid repeated preparation.

For an already admitted owner, restore the protected checkpoint once, inspect admission channels
on that state, and pass the same state to `processRestoredLocal`. The host still verifies source
ordering and authority, retains required content, and conditionally publishes the checkpoint and
result. The handle saves duplicate restoration; it grants no new authority. Keep ordinary
`restore` for portable input and the existing Map-based `processLocal` entry points for callers
that do not need a separate admission inspection.

## Apply the design references deliberately

Caffeine's controlled ticker and executor make time and maintenance behavior testable. The
corresponding discipline here is explicit execution ownership, latch-driven overlap tests and
separate physical retention accounting. Its buffered replacement policy is a reference for
contention work, not a reason to make business events or gas lossy. Our small preparation caches
use synchronized metadata and have no asynchronous maintenance queue. A change to that tradeoff
needs a measured contention problem and preservation of the invariants above.
[Caffeine testing](https://github.com/ben-manes/caffeine/wiki/Testing) ·
[Caffeine design](https://github.com/ben-manes/caffeine/wiki/Design)

When an optimization touches one of these boundaries, retain a failing counterexample or an
explicit before/after oracle, then measure the same workload and completion boundary. Record
initialization separately. Constant semantic gas or a constant number of active owners does not
prove constant physical hashing, allocation, checkpoint work or wall-clock latency.
