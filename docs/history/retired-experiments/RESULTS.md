> Historical experiment, retired 2026-11-08. This is not a supported API guide.

# Initial experiment results — 2026-11-03

**Historical baseline before the polish iteration.** The current implementation has no Caffeine
dependency or CommitId. The results below describe the earlier source and its guarantees.
See [the current comparison](../../CAFFEINE-REVIEW.md) for the new build and measured before/after.

The experiment demonstrates a small public API and local execution of actual authored
workflows. It does **not** establish full Blue/Python parity or production readiness.

## Workload

The complex agreement has twelve parent ledger clauses. Every order has three audit clauses,
a workflow parameter and two nested fulfillment documents. At 128 orders this is 385 managed
documents; at 10,000 it is 30,001. A submit runs seventeen workflows, emits seventeen events,
and visits only the selected order and its parent. Added orders are admitted and initialized,
including their nested documents and clauses, and their parent reacts to initialization.

Code: `examples/`, `src/demo/java/blue/examples/Samples.java`, `src/jmh/java/blue/bench/LocalityBenchmark.java`.
The templates are experimental source packages, not the production Agreements package.

## Final JMH measurements

Apple M3 Max / arm64, JDK 25.0.3, JMH 1.37, one fork per case, two 500 ms warmups,
three 500 ms measurements, 1 GiB heap, sampling mode and GC profiler. No old-engine benchmark
ran concurrently. This is a short engineering run, not a stable production latency promise.

Submit advances the retained state on every operation and rotates across orders. Add uses an
immutable base snapshot with a reusable input template, adding one occurrence per invocation.
Parsing, initial admission and YAML export are outside these timers. The complete local
transaction, including CommitId construction, is inside them. Canonical full-root BlueId,
network delivery, signatures, external authorization and durable I/O are not included.

| Orders | Managed documents (complex) | Simple submit p50 | Complex submit p50 | Complex submit p99 | Complex add p50 |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 4 | 6.83 µs | 53.57 µs | 73.22 µs | 21.31 µs |
| 128 | 385 | 7.58 µs | 55.55 µs | 79.23 µs | 22.72 µs |
| 1,000 | 3,001 | 7.58 µs | 55.87 µs | 73.09 µs | 25.66 µs |
| 10,000 | 30,001 | 8.86 µs | 56.77 µs | 74.75 µs | 26.82 µs |

The complex submit median grew about 6% across a 10,000-fold increase in order count.
It is not mathematically constant: persistent-map paths, address lengths, memory locality
and garbage collection still cost time. There is no work proportional to unrelated siblings
in the steady-state scheduling path. Explicit global reads and full canonical hashing remain
size-dependent.

## Measured improvements within this experiment

Pointer parsing initially compiled regular expressions repeatedly. Replacing those with direct
character checks and avoiding repeated source-path parsing reduced complex-submit allocation
at 128 orders from about 261 KB to 106 KB per transaction (59%). Its median decreased from
72.2 to 55.6 µs. Other small hardening changes accompanied this run, so it is an engineering
before/after measurement rather than an isolated microbenchmark of the parser alone.

Compiled literal reuse preserves logical expression charges, including the first unaffordable
instruction. Small immutable values are reused. Recursive content hashing runs outside
Caffeine's atomic mapping callbacks; recursive `compute` caused a correctness failure during
testing and was removed.

## Previous Java implementation

The paired diagnostic runs both implementations on JDK 25, validates matching BEX values and
uses seven short batches after warmup. It is less rigorous than multi-fork JMH and reports
batch medians. Thirty-six self-contained BEX vectors match in both engines; six basic Language
identity vectors match. This is selected-surface evidence, not full conformance.

| Workload | Previous median | Experiment median | Ratio |
| --- | ---: | ---: | ---: |
| arithmetic | 1.604 µs | 0.292 µs | 5.5× |
| sum128 | 93.048 µs | 26.216 µs | 3.5× |
| counter | 754.187 µs | 13.543 µs | 55.7× |

The BEX rows perform the same tested computation with different gas tariffs. The counter row
has the same business update/event but **different protocol guarantees**: the experiment uses
its local journal rather than the old canonical checkpoint/receipt machinery. Its ratio must
not be presented as a drop-in replacement speedup.

A separate existing-engine, in-memory Coordination run on its configured JDK 21 built 128
orders sequentially in 73.0 seconds. It measured an existing submit at 76.5 ms, adding an order
at 91.5 ms plus 536.2 ms of content publication (627.6 ms total), and a new-order submit at
86.0 ms. Those are single diagnostic observations and include more protocol work than Local-1.
They establish the old integration's behavior, not an apples-to-apples speed ratio.

The final paired comparison was rerun from the delivered project. Exact baseline jar hashes
and the environment are in measurements/.

## Correctness and quality gates

The clean standalone build passed **60 tests, zero failures/errors/skips**. A separate final
comparison passed all 36 shared BEX vectors. The sample initialized its 385 managed documents
in 70.7 ms in one cold execution run after loading templates (not a startup latency distribution).

The standalone suite covers immutable edits, verified providers, type refinement, YAML/JSON,
BEX functions/loops/short-circuiting, 36 value vectors, parallel program evaluation, gas limits,
rollback after parent failure, rollback during commit construction, cache-neutral gas and
commitments, stale occurrence generations, source filters, concurrent coordinator calls,
clause vetoes and workflow parameters. A deterministic 200-call complex workload checks all
parent ledger and child audit counters. Tests do not read Python or require a sibling checkout.

Compilation uses `-Xlint:all -Werror`. Sources are formatted with google-java-format. The normal
build creates library, source and Javadoc jars and also compiles the JMH harness. Only compact
measurement summaries are retained in the repository; raw traces and expanded documents belong
under ignored build/ output.

## What the result does and does not justify

It supports continuing this architecture: an immutable public API, compiled BEX, retained
admission/plans, indexed delivery, persistent edits and separate history commitments.
The runtime is roughly 2,800 lines of Java, but its smaller scope is part of that size difference.

It does not yet justify replacing the existing library. Still needed: complete Language/BEX,
canonical package loading and actual Agreements compatibility, shared identities and cyclic
components, durable replay/checkpoints, external authority and a reviewed portable proof
protocol. Weighted cache policies and broader hostile-input qualification also remain work.

The implemented spec experiment is documented in EXPERIMENT-SPEC.md. No canonical package,
Python implementation or existing runtime source was changed.
