# Additive gas revision 2 — implementation and local qualification

9 November 2026. Status: **bounded prototype implemented and tested; full additive-policy release
and constant-time parent processing are not complete.**

The auditor's requested vertical slice now runs through the real library and durable local runtime:
BEX Compute → Order → Agreement, with matching, Text work, scalar writes, emitted events and
protected persisted source completion reuse. There is no new authored Blue type or public gas
journal. The selected schedule is `ADDITIVE_PROTOTYPE`; existing histories and the default
`PUBLISHED` schedule keep their selection.

## What changed

1. Added the versioned prototype schedule with a 10,000,000 default **and maximum**. Candidate
   arithmetic is exact without granting larger allowances. Shared/member debit ordering and
   first rejected actions are retained across cuts.
2. Implemented a closed BEX lowering inside the canonical engine: iterative expression execution,
   lazy branches, explicit logical construction, Integer limb costs and paired UTF-8 scans. The
   compiler expression traversal is iterative too. Other depth-sensitive work has explicit
   prototype admission limits; this is not a claim that all BEX/Language helpers are iterative.
3. Added explicit scalar/flat-pattern Language obligations. Required false predicates, new output
   admission and supported target validation are paid. Unknown constraints, retyping, conversions,
   operators and native callbacks stop operationally instead of inheriting a guessed/free tariff.
4. Tightened source reuse. A single protected acyclic static source may be adopted under exact
   premises at default caps. Ambiguous decomposition, cycles in a source summary, custom budgets,
   changed predecessors and interleaving fall back to ordinary execution. General overlapping
   occurrence summaries are not enabled by simply merging member IDs.
5. Preserved the existing allocation algorithm. Selected-path calculation premises avoid an
   artificial full-document dependency. Old records and published package artifacts are unchanged.
6. Removed two physical passes in the bounded path: incoming results select affected attachment
   tickets directly; a proven ordinary scalar replacement keeps topology/channel lifetimes instead
   of reconciling the graph again. Full-scan/full-reconciliation equivalence is tested.
7. Enabled the schedule in `myos-simplified`, with schedule pinning, protected disk reuse and a
   durable three-attempt host allowance per owner/input. A restart or manual retry does not reset
   that allowance. A held job retains its input and does not fabricate a semantic rejection.

[Exact implemented policy and exclusions](POLICY.md) · [Java and Gradle usage](USAGE.md).

## The actual document story

Each fixture has one Agreement, N Orders, and one Payment inside each Order: **2N + 1 documents**.
Each Payment uses its own local Timeline. Types come from the normal pinned package catalog.

Initialization starts the members. Each Order emits `Order Initialized`; the Agreement increments
`initializedOrders`. Initialization necessarily performs work for every actual member/event.

Each measured input calls `record` on Payment 0. Payment increases `recordedAmount` by 42 and emits
`Payment Settled {amount: 42}`. The Order's real Compute body:

* queries whether the received amount is Integer — true;
* queries whether it is Text — false, and uses `$not` on that paid result;
* evaluates `$startsWith: [payment, pay]`;
* replaces its `paidTotal` with its previous total plus the amount.

It emits `Order Paid {amount: 42}`. The Agreement increments its own `paidTotal` and emits
`Agreement Payment Received {amount: 42}`. After five inputs, the relevant Payment, Order and
Agreement each report 210. Unrelated Orders remain unchanged.

Exactly three independently durable owner jobs commit per input. The next owner loads and verifies
its source's stored completion; there is no retained mutable document session. A separate regression
closes the application specifically **after the child commit and before parent adoption**, then
reopens and completes the parent from disk.

The fixture is an ordinary Blue document using published Contracts types. It is a bounded Order
scenario, not a claim that all published Agreements clause installers already run under this tariff.
The existing real-clause suite still passes under its retained published schedule.

## Accounting and result equality

At every tested graph size and every sample:

| Member | Own gas | Inclusive gas |
|---|---:|---:|
| Payment | 59 | 59 |
| Order | 123 | 182 |
| Agreement | 91 | **273** |

The inclusive Agreement bill is `59 + 123 + 91 = 273`. Summing the three inclusive history bills
would double-count retained contributions. Reuse reduces physical work; it is not a gas discount.

For each measured input, whole-root execution starts from that same rooted predecessor and processes
that same input under the new schedule. It must equal split execution in **root BlueId, admitted gas,
and ordered public events**. Different-sized Agreements naturally have different BlueIds; each is
compared with its own correct reference. There are thirty final measured comparisons across two
fresh-store runs, five inputs per size per run.

Contracts regressions additionally compare sufficient, exact and insufficient shared/member caps,
including the first rejected charge and rollback. Custom caps currently replay rather than
optimizing successful-summary adoption. A finite gas cap stops cyclic feedback and restores the
rooted predecessor. Successful complete receipt equality is tested where the invocation boundary
is identical; split-vs-whole execution does not assert identical operational receipt envelopes.

This is not an independent implementation of all Contracts semantics. The independent primitive
oracle walks generated arithmetic expressions and selects their actions itself; the whole-root
oracle deliberately uses the same canonical Contracts engine to test execution partitioning.

## Measured processing times

| Orders | Documents | Run A median ms | Run B median ms | Root inclusive gas |
| --- | --- | --- | --- | --- |
| 10 | 21 | 41.76 | 23.84 | 273 |
| 100 | 201 | 53.08 | 44.90 | 273 |
| 1000 | 2001 | 287.54 | 288.02 | 273 |

Times are milliseconds on this Mac, JDK 25. Each run uses a fresh SQLite store. A is ordered
10 → 100 → 1000; B reverses that order. The table reports medians of five operations per size per
run, including the first sample; individual samples are retained in the JSON files.

**Boundary:** append the Timeline entry through all three durable owner commits, including result
preparation and SQL publication. SQLite uses WAL and `synchronous=FULL`. Opening the runtime is
measured separately. Package loading and fixture construction before `start` are outside the
operation interval. Whole-root reference execution is also outside the interval.

The runtime is closed/reopened between operations, not the whole JVM. JIT, common immutable/type
caches and OS filesystem caches may remain warm. These are not cold-machine latency or production
SLA measurements. A reverse-order repetition exposes some of that warm-up variation.

| Orders | Start A ms | Start B ms | Open median A ms | Open median B ms |
| --- | --- | --- | --- | --- |
| 10 | 223.08 | 38.89 | 3.01 | 1.77 |
| 100 | 617.31 | 456.83 | 2.30 | 2.00 |
| 1000 | 11855.91 | 13461.12 | 2.07 | 2.35 |

These initialization measurements include starting all 2N+1 actual documents and their Order
initialization events. They do not measure adding one more Order; dynamic births are outside this
prototype's enabled write binding.

## Physical locality: improvement and remaining failure

The scalar fast path was retained only after matching full reconciliation's complete observations.
The saved baseline uses the same prototype tariff, same fixture and same durable measurement boundary.

| Orders | Before A/B medians ms | After A/B medians ms |
| --- | --- | --- |
| 10 | 41.25 / 23.88 | 41.76 / 23.84 |
| 100 | 54.49 / 48.39 | 53.08 / 44.90 |
| 1000 | 295.96 / 292.24 | 287.54 / 288.02 |

| Counter | 10 Orders | 100 Orders | 1000 Orders |
| --- | --- | --- | --- |
| Protected source adoptions | 2 | 2 | 2 |
| Affected attachment edges visited | 2 | 2 | 2 |
| Scalar topology reconciliations skipped | 3 | 3 | 3 |
| Route-capture edges visited | 23 | 203 | 2,003 |
| Finalization edges visited | 11 | 101 | 1,001 |
| Proof-vector edges visited | 85 | 715 | 7,015 |
| Blue identity bytes hashed | 85,531 | 200,976 | 1,359,080 |
| Native strict-validation calls | 49 | 49 | 49 |
| JSON characters decoded | 5,442 | 38,740 | 371,675 |
| SQL query calls | 1,060 | 1,628 | 7,054 |
| SQL update calls | 259 | 271 | 280 |
| SQL rows returned | 810 | 1,108 | 3,829 |

Counter rows are the final sample of run A. They include open/append/drain, while the headline
latency excludes the separately reported open time. SQL calls/returned rows are instrumented
application calls, not physical device I/O. `hashedBytes` covers the instrumented Blue identity
hash boundary, not every cryptographic operation. Decoded sizes are Java characters, not a claimed
complete UTF-8 byte inventory. Other existing materialization/phase counters remain in the raw JSON.

The useful result is that actual handler work, output admission, source reuse and affected attachment
installation remain bounded in this scenario. **Whole parent processing still grows with graph size.**

Why it still grows:

* The restored Agreement snapshot still exposes broad member/edge/progress metadata. Restoring,
  checking predecessor vectors and preparing its next publication revisit that structure.
* Route capture and graph finalization still touch wide edge inventories even though only one
  attachment's value changed.
* The current exact identity and receipt paths reconstruct/hash broad structures. Their gas
  surcharge is now zero; their CPU and allocation cost is not zero.
* Database reads and decoded metadata grow accordingly. No price-table change removes them.

| Agreement sample | Restore ms | Native execution ms | Prepare publication ms |
| --- | --- | --- | --- |
| 10 | 2.02 | 7.52 | 1.60 |
| 100 | 6.47 | 13.17 | 5.57 |
| 1000 | 66.58 | 108.30 | 51.20 |

Those phase measurements are one illustrative sample, not medians. Their sum is not the entire
append-through-commit latency: other owner jobs, timeline work, SQL transactions and publication
completion also contribute.

The next performance work should make restored parent metadata and publication deltas sparse,
maintain reverse-edge/route indexes without rebuilding wide inventories, and reuse authenticated
unchanged proof structure. Where the selected Blue identity encoding itself requires a wide digest,
measure and bound that work explicitly; a different commitment design would require its own
specified semantic revision. Storage-page IDs must not replace BlueIds.

## Verification and what is not claimed

* **2,528 library tests**, **84 local-runtime tests**, **15 existing clause tests**: zero failures,
  errors or skips. Formatting, Javadoc and benchmark compilation passed.
* The primitive suite includes **7,200** independently evaluated expression/budget combinations;
  this is a loop within a test, not 7,200 additional JUnit test cases.
* Matched/failed primitive Language obligations are compared with canonical Language matching.
* Text tests cover a false prefix, multibyte UTF-8, and an odd remaining-byte allowance.
* A 20,000-level expression executes iteratively; an unselected division-by-zero branch stays lazy.
* Inline/reference equivalence, inherited declarations, portable chunks, protected disk cuts,
  custom owner caps, exact shared limits and cyclic gas exhaustion are covered.
* Host holds persist across restart, cannot automatically consume unlimited fresh attempts, and
  allow unrelated owners to continue.

Full query/function/statement lowerings, general inherited validation, conversions, arbitrary native
capabilities, dynamic graph edits and general occurrence-ordered overlapping summary reuse remain
unfinished for this schedule. The prototype deliberately holds these cases. The complete tariff
also needs resource calibration at its 10-million maximum.

This iteration qualifies local execution. It provides no new AWS deployment or AWS latency claim.

## Evidence and source locations

* [Final run A](measurements-a.json), [final run B](measurements-b.json).
* [Same-tariff baseline A](before-scalar-a.json), [baseline B](before-scalar-b.json).
* [JUnit suite totals and names](verification.json).
* [Final source hashes](source-manifest.json), [baseline source hashes](before-scalar-source-manifest.json).
* Primitive lowering: `src/main/java/blue/AdditiveBex.java`, `AdditiveGas.java`, `BexPlanCompiler.java`.
* Accounting/composition: `ContractModel.java`, `ContractCompletions.java`, `ContractRun.java`.
* Local integration: `myos-simplified/src/main/java/myos/local/HostWork.java`, `LocalRuntime.java`.
* Repeatable measurement: `myos-simplified/src/qualification/java/myos/local/OrderScale.java`.

No final-price publication or replacement of historical evidence is implied by these prototype files.
