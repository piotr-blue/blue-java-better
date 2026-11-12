# Code-quality review against Caffeine

For the later historical scores, see [the November 4 review](QUALITY-REVIEW.md).
Current implementation evidence is in [the iteration log](ITERATIONS-2026-11.md) and
[test strategy](TESTING.md).

Historical review before the execution-profile iteration; see [current profile design](EXECUTION-PROFILES.md).

Review date: 2026-11-03. Scope: the experimental `blue-java-better` library.

The public API and internal boundaries are materially clearer after this pass. They follow the
same useful principles seen in Caffeine: explicit contracts, a small configuration surface,
well-defined ownership, and implementation details kept out of the public API. This does not
establish equal maturity or correctness. That claim needs external review and substantially
broader qualification than this experiment has received.

## Reference and comparison

The review used Caffeine 3.2.2's
[builder](https://github.com/ben-manes/caffeine/blob/v3.2.2/caffeine/src/main/java/com/github/benmanes/caffeine/cache/Caffeine.java)
and [Cache contract](https://github.com/ben-manes/caffeine/blob/v3.2.2/caffeine/src/main/java/com/github/benmanes/caffeine/cache/Cache.java).
In particular, the builder explains defaults, bounds and invalid combinations, while Cache
specifies null handling, concurrent use, atomic loading and restrictions on loader callbacks.
The intent is to bring that contract discipline to Blue, not reproduce Caffeine's cache machinery.
No Caffeine source was copied and there is no Caffeine dependency.

| Aspect | Earlier experiment | Polished implementation |
| --- | --- | --- |
| Entry point | Small builder, but many implicit rules | Documented defaults, validation, reuse and thread-safety; invalid null ingress rejected immediately |
| Public values | Immutable data with sparse documentation | Explicit missing/null semantics, immutable views, pointer/edit behavior, and failure contracts |
| Results | Mandatory CommitId coupled processing to history hashing | State, effects and gas; explicit optional BlueId calculation; revisions are simple branch counters |
| Compilation and routing | Both mixed in DocumentPlan | DocumentCompiler owns temporary construction; DocumentPlan owns immutable routing metadata |
| Event selection | Concatenated string keys; copy/sort on each event | Typed route keys; directly reuse sorted immutable lists when one index matches |
| Execution flow | Queue draining also handled dispatch details | `drain → deliver → execute → apply/emit`; clause validation is a named step |
| BEX code | Terse compile/evaluate naming and compressed control flow | Explicit compile-method names, consistent braces and formatting, clear invocation-local Frame |
| Reuse | Caffeine dependency and journal fingerprint cache | Four fixed-size JDK memo tables; no background maintenance or loading lock |
| Maintenance | Manual formatting; missing API docs ignored | Formatting enforced by `build`; full Javadoc checks and compiler warnings fail the build |

The built-in type catalogue is now initialized once. A builder copies it instead of reparsing
resource JSON and recalculating local identities on every construction. JSON loading now has
the same `String` and `Path` overloads as YAML. Examples and contributor instructions use the
current API.

## Boundaries that matter

`Blue` is reusable and thread-safe. A `ContractState` owns its admitted plans; cache eviction
cannot invalidate it. Every call has its own mutable Execution and each BEX invocation its own
Frame. Failed processing returns the original state and no published effects. A failed initial
admission returns no state. The API documents that distinction.

`Coordinator` is optional. It serializes one root, including that root's embedded tree. Different
roots progress independently. It neither persists a journal nor promises durable event delivery.
The host owns persistence, ingress authorization and delivery outside the engine.

The memo table intentionally has a narrow contract: one atomic-array slot per hash, replacement
on collision, identity comparison for Node keys, value comparison for BlueIds. Concurrent misses
can duplicate computation. Entries strongly retain content until replaced or cleared; the bound
is entries, not bytes. This is not Caffeine's adaptive eviction policy. A broader cache workload
and heap-retention study remain appropriate before production use.

## Verification

The standalone suite passes **72 tests, zero failures/errors/skips**, including the existing
36 self-contained BEX vectors. It covers rollback, clause changes, workflow parameters,
immutable snapshots, provider identity verification, and local execution with many embeddings.
New coverage includes disabled/collision-heavy caches, recursive loads, loader failure/retry,
builder reuse, mixed event-index ordering, and independent roots under concurrent calls.

A blocking-provider test holds admission on one root open and verifies that an operation on a
second root finishes before that provider is released. There is no timing threshold on business
performance in the test suite; bounded waits only prevent a concurrency failure hanging tests.

`./gradlew clean build example` additionally checks formatting, strict compilation, complete
Javadoc, artifact assembly and JMH compilation. The example initializes 385 documents and runs
17 workflows for a submit, visiting only its order and parent.

## Bounded before/after measurements

Same source workload and machine: Apple M3 Max, JDK 25.0.3, JMH 1.37, one fork per case,
two 500 ms warmups and three 500 ms measurements, 1 GiB heap, sample-time mode and GC profiler.
Baseline and candidate ran separately. These short measurements are diagnostic, not latency SLOs.

| Orders | Managed documents | Submit p50 before → after | Submit p99 before → after | Add p50 before → after |
| ---: | ---: | ---: | ---: | ---: |
| 1 | 4 | 54.02 → 22.08 µs | 73.73 → 34.69 µs | 21.34 → 13.25 µs |
| 128 | 385 | 54.85 → 25.34 µs | 75.52 → 41.18 µs | 23.20 → 14.53 µs |
| 10,000 | 30,001 | 56.00 → 26.94 µs | 79.36 → 39.42 µs | 25.95 → 15.46 µs |

At 128 orders, submit allocation changed from 106,213 to 73,668 bytes/op. Add allocation changed
from 45,463 to 40,260 bytes/op. Runtime still has a modest size-dependent cost; these numbers do
not establish constant-time processing or an allocation-free implementation.

The earlier engine performed journal hashing on every transaction. The candidate intentionally
removes that work and those gas charges, and also changes routing and memoization. This is a
combined revision comparison, not an isolated estimate for any one change and not evidence that
Caffeine itself caused the delay. Business workflow effects stay the same; history hashes are no
longer produced. Full canonical BlueId calculation, parsing, initial admission, export, network
delivery and durable storage are outside these per-operation timings.

Compact evidence: [polish comparison](measurements/polish-comparison.json). Earlier measurements
in RESULTS.md are explicitly historical. Raw JMH data is generated under ignored build output.

## Remaining quality gap

Readable code does not make an experimental protocol production-ready. The full Language/BEX
surface, canonical package compatibility, cycles/shared documents, durable recovery and external
authority/proof rules remain incomplete. There is no nullness checker, mutation-test campaign or
exhaustive concurrency verification. BEX compilation and tree admission remain the most demanding
parts to review. The next useful quality step is an independent semantic review of those paths,
followed by targeted tests and profiling of actual consumer workloads.
