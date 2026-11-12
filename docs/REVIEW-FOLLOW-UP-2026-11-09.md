# Review follow-up: current evidence and next implementation

The feedback is useful and its central priority is accepted. We should preserve the consolidated
canonical implementation and concentrate on large-parent restoration and result preparation.
A leaf Payment becoming a few milliseconds faster cannot solve a large Agreement spending
hundreds of milliseconds restoring and preparing its result.

## Scope and chronology

The reviewer explicitly did not inspect the new archive's source or rerun it. Their review covers
the immutable November 9 audit cutoff: candidate 50, with iteration 53 supplied separately as an
experiment. That was the correct boundary of the report they received. Since that cutoff:

- Candidate 53 was qualified on AWS; candidate 54 combined it with batched retained-membership
  checks. The combined candidate passed the full 818-test host suite and eight exact AWS receipts.
- Candidate 54 was deployed successfully. Its synthetic 10/1000-Order medians are
  765.236/2546.845 ms, compared with candidate 50's 872.009/3481.431 ms. These small samples show
  a bounded improvement, not a latency guarantee or near-flat work.
- Two live HTTPS journeys passed twenty exact submitted-entry Agreement completions in total.
  Payment creation is still slow; the repeat median was 5231.470 ms. Mixed signed external/local
  replay passed. These are functional gates, not independent gas comparisons.
- A subsequent isolated real-clause journey passed ten uncached whole-root receipt comparisons,
  reaching the expected total of 400. No HTTP was included in this measurement.

The earlier archive is not being silently replaced. The deployment and later evidence are recorded
in the continuation report. Current runtime URL: https://d2buikczdox1rt.cloudfront.net.

## Real-clause diagnostic that completed after the audit

This runs the published clause tutorial through the console application API and durable DynamoDB
host, stopping at the exact submitted entry's committed Agreement result and expected business
state. It is a new process with normal transport caches, not the synthetic zero-cache workload.
Catalogue loading is measured separately. Each median below has only two samples.

| Measurement | Milliseconds |
| --- | ---: |
| Package catalogue loading, once | 15810.415 |
| Agreement bootstrap | 4567.006 |
| Place Order, median | 5551.032 |
| Prepare Payment, median | 4240.295 |
| Accept payment instruction, median | 2250.466 |
| Report transfer, median | 2272.601 |
| Acknowledge transfer, median | 2365.493 |

For the first preparePayment call, native execution was 1180.373 ms in Order and 1110.214 ms in
Agreement; preparation was 488.862 and 183.372 ms respectively. Restore was 4.006 and 3.335 ms.
Thus the earlier large synthetic graph and this small real-clause graph have different dominant
costs. The real-clause path repeats meaningful native execution in ancestors. Its native execution
phase can also contain demanded content hydration, so these numbers do not fully exonerate or
implicate the loader. A controlled paired comparison is required to assign that cause.

The oracle disabled calculations, protected preparations and source-result reuse, and compared ten
complete rooted transitions. This establishes selected parity, not arbitrary-graph compatibility.
The temporary worker was confirmed terminated.

## What the feedback changes in the plan

1. Treat computational locality as an acceptance criterion. Record counts and bytes alongside
   complete durable parent-completion time; identify an eliminated pass instead of reporting only
   another percentage improvement.
2. Use the existing canonical State/checkpoint/preparation structures. First inspect their existing
   ownership and reuse mechanisms; do not invent another processor, authored type, or competing
   change protocol.
3. Separate demanded work from how reads are scheduled. Batching genuine demand and stopping
   unnecessary demand are different changes, with different correctness conditions.
4. Screen experiments with focused tests and a small exact-comparison workload. Broader gates and
   real HTTPS/mixed-source checks belong before a runtime promotion, not after every prototype.
5. Keep complete native-turn reuse as a narrowly measured follow-on. Defer account-manifest
   partitioning until contention measurements justify it; it is not the first remedy for parent
   reconstruction in a single operation.

## Iteration 55: measure the preparation boundary

Optional JFR events now separate retained-membership lookup, checkpoint export, snapshot-page
construction, protected preparation export/pages, operation catalogue, overall planning and flush.
Snapshot-index events also count examined, encoded, reused, changed and removed records and
encoded bytes. Counters and timing never enter Blue state, gas, observations or acceptance.
Instrumentation is retained in local source; the AWS deployment remains candidate 54.

A fixed Payment input was applied at 10, 100 and 1000 Orders, with three unprofiled samples and
one separately profiled sample at each size. The workload has Payment → Order → Agreement and
uses the hosted code path on a memory store. Owner storage is recreated for each input, transport
caches are disabled, and the immutable package configuration/JVM remain. This is neither the
myos-simplified disk benchmark nor an AWS latency measurement. The oracle runs after timing.

| Orders | Complete operation median, ms | Records encoded in profiled operation | Encoded bytes | Changed index entries |
| --- | ---: | ---: | ---: | ---: |
| 10 | 53.683 | 110 | 87481 | 54 |
| 100 | 70.390 | 650 | 558194 | 54 |
| 1000 | 460.681 | 6050 | 5274366 | 54 |

All twelve exact receipt comparisons passed. Encoded bytes are the inputs to snapshot-index JSON
encoding across all three owner attempts. They are not network bytes, unique pages, or document
body demand. Multiple indices can contain distinct records for the same member. The 54 changed
entries are index replacements, not 54 new physical pages.

One profiled 1000-Order Agreement planning span measured 156.137 ms:

| Included phase | Milliseconds |
| --- | ---: |
| Retained-membership lookup | 31.478 |
| Checkpoint export | 7.664 |
| Snapshot pages | 10.293 |
| Native preparation export | 7.965 |
| Native preparation pages | 22.198 |
| Operation catalogue | 2.177 |

These phases are included in the planning span, not additive to it. The remaining roughly 74 ms
includes other planning work and measurement overhead and has not yet been fully attributed.
Flush was another 1.206 ms. The sample points to broad physical work; it does not justify assigning
all latency to serialization or claiming a complete phase breakdown.

## Iteration 56: reject a cache-only remedy

I tested using the existing 32 MiB bounded immutable record cache to retain decoded snapshot rows,
allowing equality checks to skip some encoding during publication. It adds no semantic authority,
but freezing/caching records also adds work and not all rows remain available at large sizes.

The 26 focused storage/native/cache tests passed, as did twelve exact receipt comparisons. The
10/100/1000 medians were 54.886/75.708/450.772 ms. At 1000 Orders, 5213 records still needed encoding
(3520802 bytes), with 54 changed index entries. The large median differed by only about 2%, and
both smaller cases were slower. This one bounded screen does not show a reliable overall gain.

Decision: reject and revert the cache change. Keep the diagnostic instrumentation. After reverting,
formatting passes and the 23 directly relevant storage/native tests reuse their successful
initial iteration-55 results from Gradle's cache; that initial run executed the tests. No whole-stack campaign or AWS
rollout was spent on the rejected prototype.

## Source findings and the next implementation boundary

The feedback's warning about duplicate mechanisms is appropriate. The native implementation
already preserves immutable member metadata and encoded fragments in `ContractModel.Document`.
`Snapshot.copy` carries caches; `Snapshot.wire` can reuse member metadata; `Snapshot.identity`
reuses fragments while feeding the required canonical bytes to SHA-256. These are valuable
existing mechanisms, not missing features to reinvent.

The host still receives a complete map-shaped checkpoint and `CanonicalSnapshots.save` builds
complete document and edge maps before updating indices. Protected preparation similarly exports
and compares a complete member map. Physical deduplication prevents unnecessary writes, but it
happens after much of the metadata traversal and encoding work. `OwnerGraphs.preparePages` also
rebuilds ownership partitions and source bindings around that result.

The next change should preserve exact unchanged records or trusted change information across that
boundary, bound to the before and after native snapshots. It must cover metadata changes even when
a body's BlueId is unchanged, edge generations, removal/re-addition, cyclic members and rejected
invocations. A simple comparison of business BlueIds is insufficient. No current source coverage,
permission, ordering decision or owner association may be inferred from immutable body equality.

The next diagnostic will split the remaining planning span into alias/partition validation,
evidence, reception/lineage and source-binding work. This avoids prematurely choosing the 22 ms
encoding subphase as the only target while leaving a larger unidentified pass intact.

Classify each remaining graph-wide pass:

| Class | Action |
| --- | --- |
| Host reconstruction of unchanged values | Remove or make incremental without changing semantics. |
| Native validation or required observable traversal | Reuse only under exact established validity conditions. |
| Canonical commitment/output construction | Preserve required bytes and identities; measure the irreducible work. |

The current snapshot commitment is a digest over complete canonical metadata, not an incrementally
replaceable Merkle root. Reusing fragments avoids re-encoding, but those bytes still enter SHA-256
when the commitment changes. Replacing that commitment with a different one would be a separately
versioned protocol change. Nothing in iterations 55/56 changes it. This does not establish that
hashing dominates current latency; it identifies one concrete limit to a literal constant-work claim.

## Current assessment

The review correctly distinguishes architectural progress from speed. I update hosted architecture
and API coherence to 8/10 to recognize the earlier consolidation. Semantic correctness remains
7/10, selected compatibility 6/10, performance against the requested target 3/10 and operations
5/10. These are scoped engineering judgments, not independently audited scores. Instrumentation
and the rejected cache prototype do not earn a latency or production-readiness increase.

The immediate target remains fewer unrelated members and records processed per invocation, exact
receipt/gas/event parity, and short durable parent completion. The millisecond AWS objective and
near-flat graph-size growth have not been achieved.

## Evidence

- [Continuation and deployment history](CANONICAL-CONTINUATION-2026-11-08.md)
- [Isolated real-clause result](campaign-continuation-2026-11-08/aws-iteration54-clauses.json)
- [Iteration 55 samples and preparation counters](campaign-continuation-2026-11-08/host-memory-iteration55.json)
- [Rejected iteration 56 samples](campaign-continuation-2026-11-08/host-memory-iteration56.json)
- [Focused iteration 56 tests](campaign-continuation-2026-11-08/focused-iteration56.json)

## Subsequent iteration: identify and remove one evidence lookup pass

Iteration 57 split the previously unattributed planning time. In one 1000-Order local host-memory
sample, evidence retention consumed 58.113 ms of the root's 153.141 ms planning span. A further
profile attributed 45.475 ms to catalogue membership lookup, 5.649 ms to inventory construction,
7.393 ms to new evidence encoding, and less than 1 ms each to reading the previous manifest and
encoding the new manifest. These are individual diagnostic samples, not an additive comparison
with earlier medians.

Iteration 58 reuses the exact occurrence inventory already committed with the preceding evidence
manifest and catalogue. Only keys absent from that inventory need catalogue lookup; older returning
records still use the ordinary index. No document result, gas, event ordering or receipt changes.
It does not substitute child turns or reuse current authorization/completeness decisions.

The root now performs nine catalogue membership lookups at both 10 and 1000 Orders; at 1000 it
reuses 1998 established memberships. The immediate host-memory median changes from 425.041 to
396.140 ms at 1000 Orders; 10 Orders is 49.742 versus 49.782 ms. The 100-Order samples fluctuate
71.514 versus 76.179 ms. Twelve exact receipts pass. Unlike a cache-hit percentage, the lookup count
shows a specific graph-wide storage pass has disappeared. Inventory construction is still linear.

The isolated AWS screen passes eight exact receipts. Medians are 708.204 ms at 10 Orders and
2225.608 ms at 1000, compared with candidate 54's 765.236/2546.845 ms. The three unprofiled large
samples are 2762.236/2225.608/2010.755 ms; the fourth, profiled sample is excluded. Initialization is
1163.843/36921.551 ms. Acquisition is excluded; mutable document sessions are not retained and
owner storage is reopened per input. These are small diagnostic samples, not an SLA. The worker
was confirmed terminated. Runtime promotion and live gates are recorded in the continuation report.

## Local duplicate-checkpoint experiment: reject

Iteration 59 attempted to export and save the invocation root's completed envelope only once.
The regular local suite passes 71 tests, and both baseline and candidate pass 16 exact comparisons
in a matched dynamic add-Order/Payment screen. The first test command omitted the package path
and failed during fixture loading; the corrected command executed the complete regular suite.

| Variant | 10 Orders: add / payment, ms | 1000 Orders: add / payment, ms |
| --- | ---: | ---: |
| Baseline | 39.606 / 43.568 | 360.733 / 344.380 |
| Candidate | 43.810 / 48.278 | 373.678 / 353.801 |

These are four-sample medians with a reopened runtime before each operation and the uncached oracle
outside timing. This screen does not support a performance win, so the runtime change was reverted.
A measurement correction is retained: physical counters are now sampled immediately after drain,
before subsequent inspection reopens the Agreement. Earlier counter totals included those later
reads; operation wall-clock timing did not. Do not interpret those older counter totals as solely
processing work.

The next library-level target is cold native metadata/preparation restoration and repeated rooted
execution passes, which dominate the local result. A source map still has to become a native
Snapshot before the existing immutable structures can be reused. Avoiding that reconstruction
requires an explicit, checked storage/native boundary; it cannot be justified by a larger cache.

## Live status after promotion

Candidate 58 is deployed successfully on the existing preview. The full host gate passes 819 tests;
the isolated AWS screen passes eight exact receipts. Live checks pass ten submitted-entry Agreement
completions (total 400), login/isolation, retries, events/history and API keys, plus mixed signed
external/local replay 14→19. This is API acceptance, not a fresh browser UI test or independent gas
oracle. The dedicated AWS parity screen supplies the separate gas/receipt evidence.

The post-rollout two-Order HTTP bootstrap is 5236.780 ms; operations span 2895.191–6908.966 ms excluding
catalogue lookup. That remains far from the target and is not presented as a real-clause speedup.
The deployed runtime artifact is 60f5faba0fb8afff682c3ade7a68150e695437a5be3a56a8439e29aeb1188aff.
Full deployment and measurement details are in the continuation report. The older audit archive
retains its original cutoff.
