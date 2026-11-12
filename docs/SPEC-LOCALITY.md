# Proposal: deterministic execution proportional to affected work

Status: original design proposal, 2026-11-03. No canonical specification has been edited.
The partial experiment now exists; see [current rules](history/retired-experiments/EXPERIMENT-SPEC.md) and [results](history/retired-experiments/RESULTS.md).

## Required experiment

A contains N independent managed documents under `/orders`. One operation changes B7;
B7 emits an event; A reacts with a fixed-size state update. Neither handler enumerates
all orders. Run with N = 1, 10, 100, 1,000 and 10,000, selecting an existing child at each
size. Separately add a child and handle its initialization event. Verify results as well
as timings. Do not use an ordinary nested map as a stand-in for actual Contracts/Coordination.

Steady-state routing and execution must visit only the changed child, its actual receivers
and required dependency paths. Zero unrelated sibling workflow executions is insufficient:
count sibling header reads, binding checks, proof construction and allocation too.

## Rules to change or make explicit

1. **Admit once, reuse exact facts.** Initial admission may inspect the complete graph.
   A later invocation can reuse verified immutable headers and bindings while their exact
   subject, specification, policy, occurrence generation and dependency context still match.
   Invalidating a fact must select its dependents, not clear/rebuild the entire graph.
2. **Index subscriptions and reverse dependencies.** A B7 completion selects its live
   receivers directly. A collection subscription is indexed when installed, not rediscovered
   by walking every collection member on every input. Dynamic topology pays for changed edges.
3. **Define gas for the incremental abstract machine.** Stop requiring full-closure preflight
   charges when unrelated facts are carried forward. Charge newly required checks, selected
   transitions and effects before work. A verified source completion is not charged as another
   execution at its parent. Receiving/validation work still has an explicit tariff.
4. **Keep gas deterministic.** Logical reuse eligibility must follow authenticated snapshot
   evidence and specified rules, never host cache warmth, elapsed time or thread scheduling.
   Define cold restoration/import verification separately; it may need to rebuild physical
   indexes. Bind imported proofs to exact inputs and reject stale/forged evidence.
5. **Separate identities from execution.** Updating one entry in a flat hashed collection may
   require a large new hash input or suffix fold even when only B7 executes. A Java cache cannot
   make an arbitrary changed cryptographic serialization O(1). Audit the current BlueId rules.
   If necessary propose a canonical tree/chunk representation with O(log N) update proofs, or
   another explicitly versioned commitment scheme. Do not silently change a Language BlueId.
6. **Carry unchanged exact values.** Use structural sharing and deltas, not serialization of the
   whole Agreement, full expanded snapshots or descendant traversal on every processing step.
   Changed root commitments and required validation remain real, measured work.
7. **Treat cycles honestly.** Distinct strongly connected components can be scheduled locally.
   Members of a genuinely changed cyclic component may require joint finalization and rounds.
   A giant cycle or an explicitly global business read is not a constant-work local update.
8. **Preserve safety and ordering.** Atomic publication, authenticated inputs, completeness,
   deterministic event ordering, dependency invalidation and rollback survive the redesign.
   Effects beyond the first unaffordable charge remain uncommitted. No counters are simply
   deleted to claim a speedup while the underlying unbounded work remains.

## Acceptance and measurement

Record package/specification identities, JDK, hardware, input size and output assertions.
Measure cold load/admission, warmed local execution, receiving, identity/proof work and durable
publication separately. Use JMH forks/warmup for the kernel and bounded end-to-end measurements
for Coordination. Record median/tail latency, allocated bytes, GC, gas and actual work counters.
Setup is outside the per-operation measurement. Test middle updates as well as append-only cases.

For an independent sibling update, require zero unrelated sibling visits in the execution and
routing stages. Expect O(affected work + changed proof paths), commonly O(log N) identity updates
if a tree representation is adopted. Set numerical latency targets after a reproducible baseline;
see RESULTS.md for the current partial measurements. Test cache eviction and restart to expose hidden warm-cache
assumptions. Shared children, cycles, topology changes and intentional collection-wide operations
get separate complexity expectations.

Implement revised behavior in an experimental path, publish new exact specification identities,
and run common vectors through Java and Python. Preserve old publication bytes. Old gas/receipt
fixtures cannot be silently rewritten and called parity; changed expectations need an explicit
semantic rationale. The existing runtime and production documents are not migrated by this plan.
