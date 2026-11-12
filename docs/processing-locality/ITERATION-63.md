# Iteration 63 — sparse native preparation publication

This follows [iteration 62](ITERATION-62.md). It removes complete preparation-row export and comparison from ordinary child-result adoption. It preserves published-mode observations and receipts, the explicit compositional gas schedule, and the existing stored preparation format. It introduces no authored Blue type or new document interpreter.

## What changed

`Contracts.Execution.State.preparationDelta(previous, retained)` exports an opaque native preparation delta bound to the exact predecessor snapshot and environment. A protected preparation for that predecessor must have been installed in the current attempt. The host must retain its base and publish the merged preparation atomically with the target checkpoint. A missing base, changed membership/topology, changed termination marker, or unavailable changed content closure requires the existing full export.

Only changed member records are encoded. The persistent metadata map retains bounded edit ancestry containing keys, not prior document values. Related forks compare their intervening edits; unrelated maps or a truncated ancestry use a complete comparison. The bound is 4,096 keys per ancestry. Reverted edits are filtered by the actual before/after metadata, so physical mutation is not mistaken for a committed difference.

The first local candidate still exported 2,004 records at 1,000 Orders. Measurement exposed why: installing a completed child removed and reinserted its unchanged outgoing attachments, invalidating topology and forcing full preparation export. Adoption now inspects only that member's indexed outgoing range, removes genuinely removed attachments and replaces genuinely changed attachments. Unchanged edges retain their topology ownership. Source-closure traversal also uses outgoing ranges instead of repeatedly scanning the source's complete edge inventory.

The AWS adapter applies native preparation deltas directly to its immutable radix index. It reads the changed keys, writes changed rows/index paths, and publishes a new manifest pointing to the previous unchanged records. It does not enumerate or clone the complete previous index. It rejects another predecessor, environment or unknown member. Stored pages remain `myos.native-preparation-pages/1`, so existing history remains readable and rolling back the runtime does not require a storage migration.

The local adapter uses the same base-bound delta, verifies it and merges it into its existing stored preparation. **Local preparation storage still serializes a complete JSON artifact.** This iteration removes library export/re-encoding work there; it does not claim sparse SQLite publication. AWS has the sparse persistent-index update. Both adapters retain full export for initialization and structural/lifecycle changes.

## Verification

The new library test initializes 10 or 1,000 children, changes one child, and requires exactly two preparation rows: the child and containing root. Merging them into the protected base must equal a full preparation export. Both the merged preparation and an ordinary full checkpoint are then restored into fresh processors and run on the next input; their full observations, receipts and resulting BlueIds must match.

Map tests cover independent branches, edits subsequently reverted, removals, clears and bounded-history fallback. A 10,000-key map test counts comparator calls and requires a single changed-key comparison to follow the changed index paths. Adoption tests retain topology for unchanged attachments and invalidate it for a changed target/generation or removal. The cold AWS storage test patches one of 1,000 preparation rows with fewer than 30 blob reads/writes, retains the readable old version, and rejects wrong-base/environment/new-member patches.

The final library suite passes **2,500 tests**. Local integration passes **82 ordinary**, **15 clause**, and **7 scale** tests. The independent comparator also reran against the original Java implementation's built artifacts: **40 transitions matched complete observations and receipts** in published mode. The ordinary library build still has no sibling-repository or Python dependency.

## Cold local measurements

The fixture reopens the durable local runtime for each Payment input. Mutable document sessions are not resident; immutable packages, JIT state and OS file pages may be reused. Timings cover append through durable Payment, Order and Agreement completion. Whole-root oracle execution is outside the timed interval.

| Metric | 10 Orders | 1,000 Orders |
| --- | ---: | ---: |
| First final run, three samples | 83.89 / 50.21 / 45.97 ms | 332.50 / 303.86 / 319.90 ms |
| First final median | 50.21 ms | 319.90 ms |
| Independent repeat | 85.64 / 52.12 / 45.02 ms | 347.13 / 297.65 / 277.35 ms |
| Repeat median | 52.12 ms | 297.65 ms |
| Preparation rows exported across three owners | 6 | 6 |
| Edit keys examined | 6 | 6 |
| Topology edge visits | 23 | 2,003 |
| Prior topology edge visits | 43 | 4,003 |
| Root inclusive gas, every input | 157 | 157 |
| Exact document/gas/ordered-event comparisons | 6/6 | 6/6 |

The six exported rows represent one Payment row, two Order-rooted rows and three Agreement-rooted rows. This counts metadata publication, not duplicated business execution or extra gas. Gas remains Payment 51 + Order's own 53 + Agreement's own 53 = 157.

The earlier candidate run, which exposed unnecessary attachment replacement, is retained separately as `first-candidate-local.json`. The final/repeat reports establish elimination of a graph-sized preparation export pass and a graph-sized topology rebuild. They do not establish a reliable percentage latency improvement. The remaining topology visits come from cold reconstruction.

## Remaining work

This is a write-side native preparation improvement. Cold storage still supplies the complete rooted member/edge metadata and preparation inventory. Restore decodes it, admission and dependency-vector construction still enumerate substantial structure, and the exact native commitment still hashes a flat canonical snapshot. The local adapter also writes its complete JSON preparation. These remaining operations explain the substantial 10-to-1,000 timing gap.

The next boundary is a native reader that preserves individually addressable, verified records and indexes through restoration and processing. It must bind readiness to durable Coordination completeness and selected historical source results. A missing read is not evidence that a source has no earlier input. The immutable records and base-bound publication delta are prerequisites for that boundary, not a substitute for it.

Any proposal to avoid a required flat canonical commitment must name and version that semantic change explicitly. The current `ContractModel.Snapshot.identity()` feeds the complete canonical snapshot into SHA-256; `ContractRun.vector()` materializes required member/edge views. Faster storage pages cannot remove those operations by themselves. No physical page hash has been substituted for a BlueId or a native receipt.

## AWS qualification

All **822 AWS-host tests** pass, bringing the three-project total to **3,426 passing tests**, without failures or skipped tests. Formatting, Javadoc, distribution and qualification-JAR builds pass.

The final artifact ran on an isolated temporary c7i.xlarge with a 1 GiB heap, two active processors, a two-CPU quota and 1.5 GiB process memory limit. Owner storage reopened per input; transport caches and mutable session caching were disabled. Immutable package/JIT reuse remained possible. Oracle replay was outside the measured execution interval.

| AWS measurement | 10 Orders | 1,000 Orders |
| --- | ---: | ---: |
| Four durable processing samples | 988.82 / 822.94 / 788.34 / 738.48 ms | 1,892.88 / 1,831.11 / 1,826.67 / 1,870.71 ms |
| Median durable processing | 805.64 ms | 1,850.91 ms |
| Median acquisition | 166.35 ms | 1,951.93 ms |
| Median acquisition + processing | 971.99 ms | 3,800.62 ms |
| Initial admission/preparation | 392.83 ms | 17,445.41 ms |
| Initialization | 1,344.68 ms | 36,245.36 ms |
| Gas, every input | 148 | 148 |
| Exact document/gas/ordered-public-event comparisons | 4/4 | 4/4 |

The prior iteration's processing medians were 858.06/2,106.12 ms and combined medians 1,021.02/4,177.11 ms. The observed reduction is useful, but four inputs per size are not a statistical performance guarantee. The combined median is calculated from each sample's acquisition-plus-processing sum. This synthetic fixture differs from the local fixture, explaining gas 148 rather than 157.

Command `3afa56ca-7b6c-44e9-a07a-fbd623c02001` succeeded. Qualification artifact SHA-256: `ab8ced321fa172d6791fd02f92e724f8e620c3e631b54211667a6f14d6b414a1`. Worker `i-03e6c5f52a864b391` was verified **terminated**. Bounded reports are in `iteration63/`; credentials and application databases are excluded.

Decision: **KEEP** exact preparation deltas and preservation of unchanged attachment topology. The initial delta implementation alone did not remove the broad export because physical edge replacement forced fallback; the final candidate fixes that cause. Ratings remain correctness **7/10**, compatibility **6/10**, clarity **8/10**, performance **3/10**, operations **5/10**. The performance objective remains open; the next work is the cold native read/commitment boundary described above.

## Deployment and live acceptance

The qualified source was built into runtime artifact `627c319584c1901ceeec19919025f346d43456473110ae41e79893ebb144fbc9` and deployed to <https://d2buikczdox1rt.cloudfront.net>. SSM command `be2a1143-3026-4258-a314-f117967a3277` returned **Success**. Health confirms durable canonical processing and `contracts-compositional-gas-1` for new owner histories. Existing histories retain their saved rules. The runtime distribution and qualification JAR are different artifacts, so their SHA-256 values intentionally differ.

Both authenticated live journeys passed:

- Published Activation/Documents/Payment clauses: two Orders with Payments, ten operations, Agreement paid total **400**. Checks include two shared participant timelines, exact submitted-entry parent history, retained events, immutable earlier history, retries, account isolation, API-key use and revocation.
- External/local timelines: two signed historical external entries replay to **14**, then a local operation reaches **19**. The document ends idle with no deferred or queued work. This uses the actual external provider; its existing timelines are read only.

The clause journey's HTTPS submission-to-observed-Agreement median was **5,077.93 ms**, range **3,611.26–6,267.23 ms**. Including the initial operation catalogue read, the median was **5,583.78 ms**. Bootstrap took **5,393.81 ms**. These include client network and repeated history inspection; they are neither engine-only timings nor evidence of a live speedup. The previous iteration's corresponding median was 5,026.29 ms.

Mixed bootstrap through historical replay took **1,863.39 ms**. The local operation was observed after **1,532.51 ms**, excluding its **490.52 ms** earlier lookup. Its recorded restore/admission/execution phase was **108.93 ms**. Live evidence and nonsecret deployment health are retained under `iteration63/`; private account credentials are excluded.

The performance objective remains open. This iteration establishes bounded preparation-row publication for the selected ordinary update, while cold restore, native commitments, acquisition fan-out and real clause execution remain separate targets. The candidate is retained because its complete removed passes have direct counters and semantic checks, not because gas equality demonstrates flat elapsed time.
