# Iteration 62 — immutable member metadata and shared snapshot indexes

This follows [iteration 61](ITERATION-61.md) and the [compositional gas revision](../gas-composition/STRATEGY.md). It changes the private representation of execution state. Public APIs, canonical snapshot fields, BlueIds, gas schedules, timeline ordering, and receipt formats are unchanged.

## The pass removed

Previously, an ordinary snapshot fork copied the member inventory and its member records. Even checking whether an existing encoded snapshot could be reused visited the member metadata. Neither action represented business execution. With a large rooted graph, repeated ownership copies and cache checks introduced sibling-dependent work before and after the actual local turn.

Member business metadata is now immutable. A change replaces the member record; its channel lifetimes and serials use persistent maps. Snapshot member and edge indexes use persistent ordered trees. A fork shares their roots and immutable records; a write copies an index path. Updating one member does not clone the other members. The old `SharedMetadataMap` helper is removed.

The member index tracks a committed-content token separately from its topology token. Updating a member's committed metadata changes the content token. A body representation change with the same committed metadata does not invalidate the snapshot encoding. A cached wire check now compares tokens and small headers rather than constructing and comparing a complete metadata list. Actual wire reconstruction still visits all members; the diagnostic counter `contracts.snapshotWire.memberVisits` makes that remaining work visible.

The ownership boundary is essential: a shared member must not be mutable business state. Declarations and encoded views are physical caches only. Cached proof views and idle results are published as immutable holders with their validity keys, so concurrent readers cannot combine one branch's edge list with another branch's cached proof or gas-field variant. Cache misses may duplicate computation; they cannot change a committed result.

## Verification strategy

The new sharing test forks a 1,000-member snapshot, verifies shared roots and records, changes one member, and checks that the original snapshot and identity remain unchanged. The new branch-concurrency test repeatedly reads four different edge views and both idle-proof forms from one shared member; it checks the actual edge lists, before-views, tariff field and independently calculated canonical digest. Existing finalization differential tests now construct separate cache-free member records for their reference branch, so shared cache clearing cannot accidentally weaken the comparison.

The full existing suite remains the semantic gate: both gas modes, canonical commitments, rollback, budgets, source selection, cycles, chunks and retained restoration. Local integration additionally checks real clauses, restart and indexed backlog readiness. The 100,000-source scheduler fixture is not a claim to have executed 100,000 complete Blue documents.

## First cold local measurement

The same Payment → Order → Agreement fixture reopens the durable runtime for every input. Immutable packages and JVM/JIT state may be reused; mutable document sessions are not. Timings run from append through durable completion of all three owners. Whole-root oracle execution is outside the timed interval.

| Metric | 10 Orders | 1,000 Orders |
| --- | ---: | ---: |
| First three durations | 82.36 / 48.49 / 41.10 ms | 346.70 / 324.65 / 292.62 ms |
| Median | 48.49 ms | 324.65 ms |
| Initialization | 219.41 ms | 14,997.44 ms |
| Snapshot forks across the three owners | 34 | 34 |
| Wire cache hits | 59 | 59 |
| Members visited on actual wire rebuilds | 54 | 4,014 |
| Members restored | 30 | 2,010 |
| Root inclusive gas | 157 | 157 |
| Whole-root document/gas/public-event comparisons | 3/3 | 3/3 |

The previous iteration's medians were 53.38/335.69 ms. These short runs do not establish a reliable percentage speedup. They establish semantic parity and remove full member copying and full metadata walks on cached snapshot checks. The operation still performs broad work elsewhere.

At 1,000 Orders, metadata-cache lookups fell from 91,292 to 55,188 for the repeated input, while metadata construction remained 2,013 records. The second independent run reproduced those counters. This quantifies eliminated metadata queries, not a change in semantic gas or proof content.

The second run measured 85.93/49.85/42.41 ms at 10 Orders and 341.70/333.05/316.07 ms at 1,000, with medians 49.85/333.05 ms. All twelve semantic comparisons across the two runs passed. Initialization in the repeat took 233.28/15,435.73 ms. Both reports are retained, including the slower repeat.

Payment costs 51, Order's own reaction costs 53, and Agreement's own reaction costs 53: the inclusive totals are 51, 104 and 157. The entire 10-Order and 1,000-Order documents naturally have different identities. At each size, separately processed execution is compared against that same complete document processed by the whole-root interpreter.

## Remaining boundary

Cold restore still decodes and validates the rooted inventory. Preparation restoration, admission, topology construction, dependency vectors, canonical hashing and outgoing publication still include broad passes. Persistent in-process indexes do not make persistent storage sparse automatically. Rebuilding a complete wire value still costs its size, and a required flat SHA-256 commitment still consumes its canonical bytes.

The next storage change must let native execution retain verified, independently addressable member/edge/preparation records across attempts and publish only changed records. It must carry ordering and completeness authority with those records, rather than trust an absent read as proof that nothing happened. Full portable snapshot construction remains an explicit compatible path. No page digest may silently replace a BlueId.

## Qualification and deployment

Final local gates passed: **2,495 library tests**, **82 ordinary local tests**, **15 clause tests**, **7 local scale tests**, and **821 AWS-host tests** — **3,420 tests in total**, with no failures or skipped tests. Formatting, Javadoc, distribution and qualification-JAR builds passed. The new iteration contributes three tests; the remaining tests are the existing semantic/integration suite rerun against the candidate.

The isolated AWS qualification used a temporary c7i.xlarge, a 1 GiB Java heap, two active processors, a two-CPU quota and a 1.5 GiB process memory limit. Owner storage reopened per input and transport caches were disabled. Immutable packages and JIT state could be reused. The fixture is the existing synthetic independent-owner scenario, not the full published-clause installation journey.

| AWS measurement | 10 Orders | 1,000 Orders |
| --- | ---: | ---: |
| Durable processing, four samples | 1,019.77 / 814.86 / 901.26 / 776.34 ms | 2,201.59 / 2,037.24 / 1,966.19 / 2,175.01 ms |
| Median durable processing | 858.06 ms | 2,106.12 ms |
| Median data acquisition | 180.89 ms | 2,048.95 ms |
| Median acquisition + processing | 1,021.02 ms | 4,177.11 ms |
| Initial admission/preparation | 383.59 ms | 17,946.12 ms |
| Initialization | 1,353.44 ms | 37,891.35 ms |
| Root gas on every input | 148 | 148 |
| Complete document/gas/public-event parity | 4/4 | 4/4 |

The combined median is calculated from the per-sample sums, not by adding the separate medians. Gas differs from the local fixture's 157 because these two fixtures contain different business programs. Each uses its own independent whole-root oracle. The small AWS case was slower than iteration 61 and the large case slightly faster; no statistically established latency improvement is claimed.

Qualification command: `70d835b0-d841-4dd5-8aef-0225ec4f8c07`. Artifact SHA-256: `f33228b624abc153a6398373df3745b3fcec4cfd8918bb9b1fbb21dfbbeb350e`. Temporary worker `i-0574a4837ef41fce1` was verified **terminated**. The full bounded measurements and termination confirmation are in `iteration62/`; application databases and credentials are excluded.

Decision: **KEEP** immutable ownership and removal of full member-copy/cache-check passes. This is a prerequisite for a sparse native storage boundary, not delivery of that boundary. Ratings remain: correctness **7/10**, compatibility **6/10**, clarity **8/10**, performance **3/10**, operations **5/10**. Flat graph-size latency remains an open objective.

The canonical preview at <https://d2buikczdox1rt.cloudfront.net> runs this candidate. Rollout command `d69431b2-5103-4454-9406-5eea20fac119` completed successfully, with internal health confirming durable canonical graphs, automatic timeline processing, native admission and `newOwnerGasSchedule: contracts-compositional-gas-1`. Runtime artifact SHA-256: `dff2e884c06c7336bc79c711035728f9f0633bc73a37437853d9a7ae0cc4df8b`. Existing histories retain their saved schedule.

Both live HTTPS acceptance journeys passed:

- Published Activation/Documents/Payment clauses: two Orders and Payments, ten operations, final Agreement paid total **400**. Each exact submitted input was matched to a committed root outcome. History, retained events, retries, account isolation and API-key creation/use/revocation passed. Submission through observed root completion ranged from **3,917.44 to 6,770.76 ms**, median **5,026.29 ms**. Including operation-catalogue lookup, the median was **5,561.27 ms**. Bootstrap took **5,362.96 ms**. These client measurements include network and polling; they are not the synthetic fixture's engine times.
- Mixed timelines: two signed external historical entries replayed to **14**, followed by a local operation to **19**. Bootstrap through replay took **1,787.14 ms**. Local submission through observation took **1,424.39 ms**, plus **490.26 ms** for operation lookup. Recorded restore/admission/execution took **104.69 ms**. The exact submitted input committed APPLIED; no owner input or deferred work remained.

Reports: `iteration62/deployment.json`, `live-clauses.json` and `live-mixed-timelines.json`. The acceptance accounts are isolated QA accounts; credentials are excluded. This iteration changes library internals consumed by both hosts. It does not change original blue-java, Python, shared package publications or existing user histories, and it performs no GitHub operation.
