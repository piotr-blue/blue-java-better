> Historical 10 November experiment with `myos-simplified`. Its timing, gas and storage results
> apply to those recorded source revisions and fixtures, not the current hosted service.
> See the maintained [architecture](../ARCHITECTURE.md) for the supported API and trust boundaries.

# Owner-local processing — implementation and qualification

10 November 2026. **The supported update path no longer restores sibling document bodies or
execution records.** It uses persisted own-owner state and selected source completions. The
whole-root interpreter remains the semantic reference. This is a bounded optimization, not a
claim of constant-time arbitrary graph processing or a completed additive-policy release.

## Result

The prior Payment → Order → Agreement scenario took about 288 ms at 1,000 Orders.
It now takes **36.89 / 45.64 ms median** in two fresh-store runs. Every measured input matches
whole-root execution in final Agreement BlueId, inclusive gas and exact ordered public event
occurrence records. The total is **273 gas**, independent of the tested number of Orders.

These are three independently durable owner commits per operation, not an in-memory function
benchmark. The runtime closes and reopens between operations. JVM/JIT, common package caches and
OS filesystem caches can remain warm. Initialization and complete graph inspection remain costly.

## What was wrong

The previous split path reused the child's execution but restored a graph-shaped parent snapshot.
That pulled in descendants' execution metadata, reconstructed broad predecessor/route structures,
and revisited unchanged validation records when preparing the parent publication. Gas-free physical
work still consumed CPU, allocations and database reads.

A second dependency was hidden in event occurrence identity: the old source-turn commitment included
physical graph dependency inventories. Reproducing it required information about unrelated members.
A third dependency was validation bookkeeping: after avoiding body restoration, inference and export
still queried facts separately for unchanged children. All three paths have been addressed here.

## Blue identity is preserved

A reference to a child's BlueId and that child's inline content retain the same Blue identity.
Computing an existing reference's identity does not fetch the child. We do not replace BlueIds with
storage IDs, change Blue's canonical content encoding, or require sibling content to update one child.

There is still shallow work on the parent's own immediate map of references and attachment-generation
map. The current encoding/copying hashes or visits those immediate entries. That is a remaining width
dependency; it is not restoration of Order1's execution state or content. It is measured rather than
being billed as semantic gas. This iteration does not establish O(1) latency for a million children.

## Processing an input, step by step

1. The existing timeline service accepts the original entry and the existing durable scheduler
   establishes its ordered, complete frontier. Equal timestamps, external evidence and reverse
   dependency obligations use the existing rules; this change introduces no new ordering shortcut.
2. Payment loads its own protected checkpoint and runs the ordinary Contracts interpreter. It writes
   its new Blue content, events, gas contributions, history and completion atomically.
3. The ready Order job reads its own checkpoint plus the selected Payment result. It checks original
   event/time, previous child BlueId and attachment generation. The child may have advanced again;
   the Order still consumes the exact historical result for this input.
4. The interpreter installs the selected child's new reference and runs Order's own handlers. It
   does not re-run Payment or open unrelated children. The same occurs at Agreement for the Order.
5. Publication writes one new owner record and the changed attachment version. Existing backlog,
   coverage index and public outbox progress commit in the same transaction.
6. Inspection or fallback can reconstruct an ordinary whole-root snapshot from immutable historical
   owner records and attachment versions. That graph-wide operation is deliberately off the local
   processing path. It uses historical versions, never current child heads.

There is no retained mutable document session. Common immutable package/types can remain cached.
Protected checkpoint/receipt records are host storage details, not new Blue document types.

## Library and storage changes

`ContractLocal` is a small adapter around the existing admission and `ContractRun.runLocal`
interpreter. `Contracts.Execution.processLocal` takes an own-owner checkpoint, selected attachment
completions and the original input. `State.localCheckpoint` exports one member's protected state;
`localContent` exports only new native content, cutting at known durable closures.

`myos-simplified` keeps `local_states`, `local_heads`, `local_attachments` and `local_results` in its
existing SQLite store. Selected input/path indexes drive the update. Full graph checkpoint creation
is not performed for each owner commit. Own state still includes immediate attachment generations.

Validation reuses only facts implied by an already validated predecessor: identical contextual
recipes with the same root-occurrence role. Changed constraints and newly present values still pass
ordinary validation. Closure availability is separate from semantic validity. Concrete-value facts
are retained explicitly so validation need not open a sibling merely to find a concrete value.
Inference/export records an already established fact without querying it again for every child.

The local receipt has a separate `contracts.local-result/1` domain and an explicit owner execution
boundary. Exporting a partial owner snapshot as a portable graph checkpoint fails. Ordinary completion
restoration rejects the local boundary. Protected storage records are not self-authenticating foreign
proofs: the host must supply all selected inputs and preserve its transactional/trust boundary.

## Versioning and compatibility

`GasSchedule.ADDITIVE_PROTOTYPE` now selects **contracts-additive-prototype-3**.
Revision 3 source-turn event commitments include the member, owner-before BlueId, phase, turn serial,
original cause/time, selected seeds, lifecycle and actual incoming source results/tickets. They omit
physical graph-wide dependency inventories. Whole and split execution both use this rule.

This changes event occurrence identities relative to revision 2. It is explicit versioning, not a
claim that old receipts stay byte-identical. The earlier schedule remains available as
`GasSchedule.ADDITIVE_PROTOTYPE_V2` with persisted identity `contracts-additive-prototype-2`.
Reopen old stores using that enum; use a fresh directory for revision 3. Do not relabel old evidence.
Default `PUBLISHED` and the existing `COMPOSITIONAL_V1` selection are unchanged.

The previous revision's bounded tariffs, overflow limits and host attempt allowance remain. Prices
for this workload are unchanged: Payment own 59; Order own 123; Agreement own 91. Inclusive owner
bills are 59, 182 and 273. Summing inclusive bills would double-count adopted contributions.

## Measurements

The fixture uses published Contracts types and pinned normal packages. It has N Orders, one Payment
per Order and one Agreement, so there are 2N+1 actual documents. Each Payment has its own timeline.
An operation records 42, triggers real BEX matching/Text/arithmetic work in Order, emits Order Paid,
and updates Agreement's total and event. Five operations leave the changed chain at 210.
Unrelated Orders remain unchanged. This is the existing bounded fixture, not an arbitrary full-clause
installer benchmark or an order-creation benchmark.

Run A: 10 → 100 → 1000. Run B: 1000 → 100 → 10. Five operations per size, medians including the
first operation; **30 checked operations** in total. SQL tracing/counters were enabled in both runs.

| Orders | Documents | A median ms | B median ms | Root gas |
| --- | --- | --- | --- | --- |
| 10 | 21 | 39.26 | 24.03 | 273 |
| 100 | 201 | 27.80 | 27.78 | 273 |
| 1000 | 2001 | 36.89 | 45.64 | 273 |

The prior revision-2 medians were 41.76/23.84, 53.08/44.90 and 287.54/288.02 ms respectively,
from the [previous qualification](../additive-gas-v2/REPORT.md). That is a historical comparison of
the same workload and durable measurement boundary, not a simultaneous controlled CPU benchmark.
This revision also changes occurrence commitments as disclosed above.

**Boundary:** Timeline append through all three durable commits, including preparation/publication.
SQLite WAL with `synchronous=FULL`. Open is measured separately. Package loading, input construction,
whole-root oracle execution and subsequent inspection are outside the operation interval. The JVM
is not restarted between samples. Small samples and outliers do not establish p99 or an AWS SLA.

| Orders | Start A ms | Start B ms | Open median A ms | Open median B ms |
| --- | --- | --- | --- | --- |
| 10 | 237.64 | 47.18 | 3.16 | 2.16 |
| 100 | 672.53 | 496.41 | 2.38 | 2.43 |
| 1000 | 13067.16 | 14286.59 | 2.28 | 2.53 |

Starting all members includes all Order initialization events and remains proportional to actual
work. There is no claim that starting 2,001 documents now takes tens of milliseconds.

### Physical work

Last sample of run A; counters include open/append/drain, while the headline latency excludes open.
SQL counts are application queries, not disk device I/O. Hash bytes cover the instrumented Blue
identity boundary, not every digest in the process.

| Counter | 10 Orders | 100 Orders | 1000 Orders |
| --- | --- | --- | --- |
| Owner records restored | 5 | 5 | 5 |
| Selected source results adopted | 2 | 2 | 2 |
| Route-capture edges | 0 | 0 | 0 |
| Graph finalization edges | 0 | 0 | 0 |
| Proof-vector edges | 4 | 4 | 4 |
| Content-body SQL reads | 79 | 79 | 79 |
| Validation-fact SQL reads | 621 | 621 | 626 |
| All SQL queries | 999 | 1027 | 1053 |
| SQL rows returned | 777 | 805 | 826 |
| SQL updates | 251 | 263 | 272 |
| Instrumented Blue identity bytes hashed | 138401 | 150799 | 276693 |

Five restored owner records means the three executing owners plus the two selected source records.
It does not mean five entire descendant graphs. Before this change the 1,000-Order sample visited
2,003 route edges, 1,001 finalization edges, 7,015 proof-vector edges and made 7,054 SQL queries.

The remaining increase in query count comes mainly from the existing balanced completeness index;
wide parent map copying/hashing and immediate generation metadata also remain. Validation queries
can vary with literal/boolean facts and accumulated history. Content-body reads stay constant in
this comparison. There is no per-sibling body/validation-record fetch in the tested local path.
The 1,000-Order latency is still higher than the warmed 10-Order latency; it is substantially reduced,
not proven perfectly flat.

## Verification

**2,535 library tests + 92 runtime tests + 15 published-clause tests = 2,642 passing tests.**
Zero failures, errors or skips. Library formatting, Javadoc and benchmark compilation passed.

- Fresh provider/processor tests at 1, 20 and 1,000 unchanged siblings throw immediately if an
  unchanged sibling body or its validation fact is requested. They compare BlueId, gas and exact
  public event occurrence records with whole-root processing.
- Changed inherited constraints still reject invalid values. A previously absent object becoming
  present does not inherit validation for required descendants it never validated.
- Wrong original cause/time, stale attachment generations and corrupted checkpoint identity are
  rejected/held for whole replay; partial checkpoints cannot become ordinary portable proofs.
- Restart tests consume a child's saved result after shutdown, including a child advancing while
  its parent is claimed. The parent catches up with exact historical results.
- Four concurrent workers process shared input without duplicate owner commits. A shared input
  affecting distinct children uses the whole-root fallback, preserving source ordering.
- Chain, diamond, four-member cycle, connected cycles, cycle plus independent leaf and cyclic-root
  shapes run four repeated batches with shuffled equal-time inputs and a fresh runtime per batch.
  Root document/gas/public events match whole processing. Cycles use the ordinary path.
- Existing published Activation/Documents/Payment clause journey and validation tests still pass.
  They do not establish that all these clauses run under the bounded additive tariff.

The whole-root oracle is the same canonical interpreter with a different execution partition. This
is strong partitioning-equivalence evidence, not a second independent implementation of the spec.

## What remains

The optimized path supports the current bounded, initialized, static, acyclic scalar-update subset,
at the default shared cap without custom member caps. A single distinct selected source can be
attached at multiple paths. Cycles, multiple distinct source completions, termination, rollback and
unsupported decomposition use whole-root processing. Graphs with external channel event predicates
are excluded from local checkpoint eligibility because idle predicate evaluation can itself cost gas.

Bindings not implemented by the additive prototype still report host incompleteness. This iteration
does not add arbitrary BEX/functions, dynamic document births/topology changes, native capabilities,
or a complete tariff. Published functionality remains on its existing policy.

Remaining performance work: page/index the owner's immediate attachment generations, avoid repeated
wide map construction where the canonical representation permits it, reduce fixed fact-query overhead,
and qualify broader source composition and dynamic changes before enabling them. Any proposed new
content commitment encoding needs separate specification; storage keys must never masquerade as BlueIds.

No AWS implementation/deployment was changed or benchmarked here. The tested sources are installed
into `blue-java-better` and `myos-simplified`; Python and shared packages are unchanged.

## Reproduce and inspect

Local commands and storage guide (`../../../myos-simplified/docs/owner-local-2026-11-10.md`, historical external artifact; not bundled).
Evidence: [run A](measurements-a.json), [run B](measurements-b.json),
[test suite totals](verification.json), [changed source hashes](source-manifest.json).
Measurement JSON retains samples, phases and counters, without database copies or gas-trace dumps.

Key files: library `ContractLocal`, `ContractPreparations`, `ContractWork`, `LanguageSemantics`,
`LanguageContext`, `Contracts`; runtime `LocalOwners`, `Publications`, `Repository`, `LocalRuntime`;
tests `LocalOwnerExecutionTest`, `LocalValidationTest`, `GraphShapeParityTest`, `OwnerConcurrencyTest`;
repeatable fixture `OrderScale`.
