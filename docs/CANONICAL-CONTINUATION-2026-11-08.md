# Canonical processing: continuation after the six-hour campaign

The requested six-hour window ended at 21:35:29 UTC on 2026-11-08. Work continued because the
performance goal was still unmet. This report separates these additional experiments from the
37 iterations completed inside that window. These are changes to canonical processing and private
storage hints, not additional authored Blue types or a second interpreter.

**Current decision: flat latency remains unproven.** No change below alters the published Contracts
gas schedule, canonical document values, event ordering or receipt commitments. Source changes
remain staged until their applicable correctness and performance checks pass.

## Measurement correction

The local qualification's `Execution(..., false)` used to disable only calculation reuse. It still
supplied semantic facts and native preparation to its whole-root oracle. That was weaker than the
“uncached” label claimed. The switch now disables all protected preparation and calculation reuse.
The corrected oracle passes 10 exact receipt comparisons per completed 10/1000-Order run. It is
outside the measured interval. The AWS oracle already used ordinary native execution and did not
have this issue. Original-Java receipt comparisons are also unaffected.

Historical local measurements remain reported as measured. Their oracle may affect subsequent
JVM and storage-cache state, so small timing differences across this correction are not clean
attributions to a single optimization. The application/store is reopened before every measured
input; connection opening is excluded, and immutable package definitions are shared. Each input
still runs Payment, Order and Agreement as three durable jobs.

## Iteration 38 — remove unnecessary proof serialization: keep

A private canonical fragment used to render a second JSON string solely for a sort key. Most
fragments already had a known order and never used that string. Sorting callers now request their
own sort key. Canonical byte encodings are constructed directly, without a byte→String→byte trip.
Unicode, ordering and snapshot commitment tests pass. Ten complete receipt comparisons pass.
Medians changed from 46.912/356.288 ms to 46.543/348.019 ms for 10/1000 Orders. This is a smaller
allocation path, not evidence of a substantial latency improvement.

## Iteration 39 — reuse repeated invocation preflight: keep

Within one invocation, an unchanged immutable member body and the same outgoing edge generations
need no repeated walk of contract declarations and occurrence bindings. The first visit and every
changed body/edge use ordinary recognition and charging. The cache belongs only to that invocation;
it cannot suppress work or gas in a later invocation. Full library tests pass. A dedicated test
compares complete receipts at budgets below, exactly at, and above the gas needed to create an
embedded Payment. Medians are 44.079/335.408 ms. All ten whole-root comparisons pass.

## Iteration 40 — retain imported metadata rows: reject

The candidate owned each validated storage row and reused it as canonical metadata. Commitments
remained identical, but the median worsened to 52.750/356.570 ms, without reducing restoration time.
The code was reverted.

## Iteration 41 — persistent graph index: reject

A persistent ordered index allowed local forks to share all unchanged edges and member references.
Isolation, cycle, portable-replay and transaction tests passed. A focused 10/1000/10000-member test
verified no key visits when forking and a bounded search path for point replacement. Complete
processing nevertheless worsened to 48.478/354.209 ms: ordered lookups and general snapshot copies
offset the cheaper local fork. The implementation and experiment-specific tests were reverted.

## Iteration 42 — scoped completed Language facts: provisional

Protected native preparation already retains exact declaration, lifetime and marker information.
The candidate also retains the exact Language checks that actually completed for that member:
dependency closure and definition/value validation, separately for root and non-root occurrences.
No stronger check is inferred from a weaker one. The preparation environment binds the Language
and Contracts editions and installed runtime bindings; its member row binds the exact BlueId.

Installation requires the existing revocable attempt scope. Tests demonstrate exact cyclic receipt
parity without a global fact reader, rejection of installation outside a scope, isolation from a
different member identity, and revocation from every captured resolver after close. Portable input
has no authority to supply these facts. Older preparation rows without the optional field retain
ordinary behavior.

Initial local medians were 48.082/380.429 ms. Removing the local global memo table worsened the
1000-Order median to 769.994 ms, so that experiment was reverted. The strengthened oracle remains.
Reusing unchanged fact lists during publication gave 47.573/385.211 ms. These results prevented an
unconditional keep decision.

An isolated AWS run passed all eight exact receipt comparisons, with cold transport caches and no
mutable document-session cache. Unprofiled durable-processing medians were 838.998 ms for 10 Orders
and 4273.527 ms for 1000 Orders; acquisition is measured separately and excluded from those values.
The preceding final six-hour run measured 884.678/4512.704 ms. The difference is modest and does not
establish flat latency. Qualification command: `66f07867-417f-4cd2-b8e9-dfb26389eb7f`;
artifact: `563f5137abb0265c1f84210fd978f7ad04d120a6383bc6837d119b741eda0569`.
The temporary worker was terminated; the live preview was not changed by this experiment.

## Iteration 43 — defer unused member-body decoding: provisional

Protected restoration already owns retained content and native preparation. Eagerly opening every
member response also decoded bodies that the attempt might never use. The candidate keeps the
responses available and leaves ordinary identity/proof verification at the demand boundary.
Targeted storage, preparation and Agreement tests pass. All ten comparisons with the corrected
ordinary oracle pass. Local medians are 46.894/370.365 ms. This is still graph-size-dependent.

## Iteration 44 — avoid re-querying restored facts: host gate rejects lazy-loading combination

Restoring a semantic fact now also records that it came from protected durable storage in the
existing bounded fact caches. Without this, publication queried the durable memo table again to
find out whether it should export those same facts. The attempt scope still revokes the caches and
all derived memo branches. The full library build passes 2470 tests, and the local host passes
71 ordinary, 15 clause and 7 scale tests. Ten corrected-oracle comparisons pass. Local medians are
56.900/322.986 ms. Dynamic growth passed 20 corrected-oracle comparisons: adding an Order cost
34.394/346.464 ms and paying cost 40.318/333.134 ms. Initialization cost 237.692/14968.809 ms.
The independent original-Java comparison passed all 40 observations/receipts at 1, 10 and 20 Orders.

The full AWS host gate then failed 10 of 812 tests. Deferred member decoding exposed a missing
inline-declaration response during timeline reception inspection. The candidate was not deployed.
Local parity alone was insufficient to detect this representation-dependent regression.

## Iteration 45 — open inline declarations when used: gates pass, mixed performance

A protected declaration plan names exact nodes, some of which exist only inline inside its member's
retained response. Opening that member when its declarations are used makes those nodes available.
It still avoids opening unrelated members solely because a snapshot was restored. Ordinary Language
identity/proof checks remain active at this demand boundary. The fix does not create an alternative
receipt or change admission, gas, rollback or scheduling semantics.

Ten focused host tests pass, including four/eight-member cycles, nested birth and reopened owners.
The new library regression retains only the member response and inspects native timeline channels
before execution. It fails with NEEDS_RESOURCES without the fix and passes with the fix. Full gates
pass: 2471 library, 812 hosted-runtime and 93 local-host tests, with zero failures. Ten corrected
local whole-root comparisons pass.

The local medians regressed to 65.441/481.451 ms. The AWS result improved modestly: 829.196/3995.590 ms,
excluding acquisition and the profiled sample, with all eight exact receipt comparisons passing.
This is an isolated synthetic workload, not the real-clause HTTP journey. Its command is
`13d18aa9-5b83-42ca-96c1-95a9a40ef59e`; artifact SHA-256 is
`89b9e913f1e047a3978cf67b53e0465a8eb309a2db6f6686ba790149e80735b6`.
The temporary worker terminated. The mixed local/cloud result does not justify claiming a general
speedup; this candidate has not replaced the live preview.

## Iteration 46 — avoid execution witnesses while hydrating: provisional

The candidate opens retained member content through Language directly, preserving identity/proof
checks while avoiding execution-witness bookkeeping at declaration lookup. Ten corrected-oracle
comparisons pass. Local medians are 48.448/459.349 ms, still worse at 1000 Orders than before the
lazy-loading experiment. This refinement has not received the full host gate or AWS measurement.
It remains staged; the correct placement and cost of deferred member hydration are unresolved.

## Iteration 47 — reuse verified retained responses on demand: reject as a standalone optimization

The current lazy opener was reparsing member responses. The candidate routes on-demand opening
through the existing independently verified exact-response cache. A retained-envelope-only variant
measured 51.524/458.954 ms; including snapshot-bound provider responses measured 46.906/470.581 ms.
Both passed all ten exact receipt comparisons. Neither establishes an improvement over iteration 46.
The small opener remains an implementation helper for iteration 48's actually executing members,
not a claimed performance win or a source of semantic authority.

## Iteration 48 — retain channel and marker records, defer member bodies: provisional

Protected preparation now retains exact transport records for the channels and markers that native
inspection needs. These records contain no replacement execution rules: Language still checks each
demanded response's identity. Workflows and business state stay behind the exact member provider;
the selected member is hydrated when its local turn opens. Old preparation rows without the new
records use the prior member-loading fallback. Cyclic header identities retain the owning-set path.

This fixes the boundary exposed by iteration 44 without forcing every member to load during
preflight. A focused test removes the entire member response, inspects its public Timeline channel,
and processes an inapplicable source with the same full receipt and observations as ordinary native
execution. The same test covers older preparation records with an inline-only channel. Focused
owner, birth, cycle and real published-clause tests pass. The local reopened-store measurement
passes all ten exact receipt comparisons at medians 53.209/366.709 ms for 10/1000 Orders.
The original full graph-sized preparation data and metadata scans still remain; this is not flat
latency. Ratings stay correctness 7, compatibility 6, clarity 7, performance 3, operations 5.

## Iteration 49 — snapshot-bound lazy AWS content: reject and revert

Full host gates passed (812 tests), and all eight AWS exact receipt comparisons passed. However,
the unprofiled durable-processing medians were 861.820 ms for 10 Orders and 13580.806 ms for
1000 Orders. Acquisition is excluded. The 1000-Order root restore/admission/execution phase alone
cost 11.8–12.7 seconds. Deferring remote reads did not remove the mutation path's broad content
demand; the host loader was reverted to batched retrieval. The successful narrow no-body test in
iteration 48 does not establish locality for an actual mutating payment chain.

Qualification command: `0866164b-db6e-42ba-941f-8f81d6db5ef0`; artifact SHA-256:
`95a0aa6a5f2ecdcb424479bbe0578a26c2bc32ccf3552e9b8e9abe52dca5917b`.
The isolated run completed successfully and its temporary worker was terminated. No live rollout
was performed. Ratings remain correctness 7, compatibility 6, clarity 7, performance 3, operations 5.

Iteration 48's full library gate passed 2471 tests, and the local host passed 93 tests. Its dynamic
growth run passed all 20 exact receipt comparisons. Add-Order medians were 45.874/346.716 ms and
Payment-chain medians were 44.654/308.256 ms for 10/1000 Orders. These differ from the separate
fixed-size payment benchmark above and are not interchangeable measurements.

## Iteration 50 — retain completed lifecycle-marker checks: keep

The AWS host intentionally disables individual durable Language-fact queries, because remote
lookups for these small facts were expensive. Its preparation retained facts for current member
roots but not their lifecycle markers. After a mutation, strict validation revisited the unchanged
initialization marker and followed its `document` reference into the original authored graph.
The local host's global fact reader masked much of this cost. A fresh profile of the actual hosted
code path, running against the memory test store, reproduced the historical validation work.

A regression with unavailable initialization-history content failed before the change. Native
preparation now retains only the exact checks that completed for each lifecycle marker, with the
same scoped, revocable fact machinery as member checks. The structural check also reuses its
already established initialization-target existence check when the marker identity is unchanged.
It does not infer validation for changed markers or manufacture missing content. Explicitly reading
the unavailable history still fails. This exposed and fixed a public API inconsistency: durable
`State.read()` now translates internal failures through the same boundary as session reads.
Older preparation rows without marker facts remain supported by ordinary validation.

The focused restoration/content/gas tests pass. On the hosted code path with cold transport caches
and the local memory test store, the 1000-Order median improved from 1208.334 to 799.225 ms; the
candidate's 10-Order median is 65.972 ms. All eight independent whole-root receipt comparisons pass.
These are host-code diagnostic measurements, not local SQLite results or AWS results.

Full gates pass: 2472 library, 812 hosted-runtime and 93 local-host tests. All 20 local growth
receipt comparisons pass, with add medians 35.965/360.288 ms and payment medians 39.626/330.806 ms.
The local results remain in the recent range; no local speedup is claimed.

AWS passes all eight exact receipt comparisons, at unprofiled durable-processing medians
872.009/3481.431 ms for 10/1000 Orders. The earlier fully qualified candidate measured
829.196/3995.590 ms. Acquisition is excluded. Initialization is 1261.201/40581.045 ms. The final
profile's main costs are snapshot restoration and publication; historical validation is no longer
a leading inclusive stack. This is a bounded diagnostic improvement, not an SLA or flat latency.
Command: `4066a102-308a-4c20-b17c-f017b8b0cce9`; artifact SHA-256:
`4d9c5a079ccbbbc8721c6fa6f263b4a6f19e5ac9e6f78299160a0392ac686945`.
The temporary worker terminated. Ratings remain 7/6/7/3/5.

## Iteration 51 — selective content after marker reuse: hold outside the current source

A local test of the hosted code path with selective immutable-snapshot loading passes all eight
receipt comparisons. Medians are 58.313/564.876 ms for 10/1000 Orders, compared with iteration 50's
65.972/799.225 ms with batched loading. The 1000-Order sample makes 473 individual content reads,
considerably fewer than iteration 49's 4557 but still graph-size-dependent. Local memory-store
latency does not establish the remote cost of these reads. Older preparation rows also lack the
marker facts, so a selective default could reintroduce historical remote traversal on those rows.

The host loader was restored to iteration 50's batched implementation before promotion. The
selective-loader candidate is retained separately in the staging root for further qualification.
No AWS improvement or deployment is claimed for iteration 51. Ratings remain 7/6/7/3/5.

## Iteration 52 — correct the older HTTP qualifier's completion boundary: keep

The user reproduction command already waited for the exact original entry in Agreement history.
The older broad authentication/API qualifier still stopped some timing intervals at child business
state visibility. Its initial candidate-50 run passed functional checks and reached 400, but its
operation times must not be reported as exact parent completion.

That qualifier now waits for the Agreement's committed APPLIED history matching the submitted
entry, records that entry and parent version, and measures business visibility separately. Two
focused tests cover intervening unrelated publications and a matching rejected input. All 48
helper tests pass. The corrected HTTPS run passes login, account isolation, bootstrap/operation
idempotency, retained history/events and API-key creation/read/revocation. All ten operations
record their exact submitted entry and Agreement history version; the two-Order total reaches 400.
Bootstrap is 2700.558 ms; exact parent completion spans 2571.378–4905.067 ms, excluding separately
reported catalogue/initial-head lookup. These are HTTPS observations with polling and are not
comparable with the isolated native-owner timings. They do not demonstrate millisecond performance.
This gate checks API behavior and exact input completion, not an independent gas oracle.

The mixed-source check also passes: two existing public signed external entries replay to 14,
then a local operation reaches 19 with the exact submitted-entry receipt. Bootstrap through replay
is 1574.342 ms and local operation observation is 1150.734 ms, excluding lookup. The corresponding
server restore/admission/execution field is 112.539 ms, which excludes other processing/publication
and network work. No server runtime change was needed for this measurement correction.
Ratings remain 7/6/7/3/5.

## Iteration 53 — adaptive pinned snapshot reads: qualified, hold for combined comparison

The host now has a tested candidate that starts with point reads bound to one immutable snapshot
and attempts the existing batched closure after 64 distinct content requests. Repeated requests do
not consume the threshold. A speculative prefetch failure cannot require unrelated content; the
actual requested record still follows normal identity and semantic verification. The fallback is
bounded to one attempt and supports broad demand from older preparation shapes. The threshold is
experimental, not an asserted optimum. The suspected cold-cache prefetch-discard problem was ruled
out: transport-cache settings do not disable the repository's bounded page cache.

Four new provider tests cover narrow/broad demand, corrupt unrequested/requested content, pinned
versions and account isolation. All 816 host tests pass. Host-memory medians are 49.590/550.938 ms
for 10/1000 Orders and all eight exact whole-root receipt comparisons pass. A separate batched
membership optimization is being tested as iteration 54; it was not in the AWS 53 artifact.

AWS 53 passes all eight exact receipts at medians 833.198/3330.021 ms. Initialization is
1284.204/40553.535 ms. These are three unprofiled samples per size, acquisition excluded; the fourth
profiled sample is excluded from each median. This is a modest improvement over candidate 50's
872.009/3481.431 ms, not a flat-latency result. Command `fd6b7f2b-d059-40e1-9b54-b0dcb42bdfc8`;
artifact `f93667b72f0acc602738a02c01121ab0c34e76eae3c660ff4d057f106c6cb3bf`.
Worker `i-00c89c61b4977718f` is verified terminated. No promotion or live deployment yet; compare the
combined iteration 54 before retaining a release. Ratings remain correctness 7, compatibility 6,
clarity 7, performance 3, operations 5.

The user-requested audit ZIP remains an immutable earlier cutoff: it correctly labels iteration 53
as awaiting AWS qualification at packaging time. The audit also reran 40 exact original-Java
comparisons using the original existing JARs and current candidate 50 library classes; all passed.
It contains the corresponding JAR hashes and comparison log.

## Iteration 54 — batch retained membership before checkpoint export: keep

Checkpoint export was asking the retained catalogue about current member identities one at a time.
The existing pinned catalogue API already supports bounded membership hints. The host now passes
current member identities to that API before exporting. Hints only batch exact index reads: absent
identities remain absent, and historical/non-member dependencies still use normal point lookup.
No Blue value, gas rule, receipt, ownership or publication boundary changes.

Combined with iteration 53, the host-memory medians are 52.990/468.652 ms for 10/1000 Orders, with
eight exact receipts. The comparable iteration 53 sample's 1000-Order point reads fall from 481 to 194;
batches rise from 85 to 89. These are total physical content reads, not a count of demanded business
member bodies. The small case remains in the same local range.

Two additional native integration cases create 96 children and mutate one, comparing full receipt,
observations and final identity with ordinary whole execution. Both current preparations and simulated older
rows without languageFacts/markerFacts/headerRecords pass; the older case explicitly exercises the
adaptive broad-demand fallback. These tests remove optional fields from a protected preparation;
they do not migrate incompatible old execution editions. The final complete host suite passes 818 tests.

AWS 54 passes all eight receipts with medians 765.236/2546.845 ms for 10/1000 Orders, acquisition
excluded. The 1000 unprofiled samples are 2973.768,2546.845,2350.838 ms. Initialization is
1276.557/40468.742 ms. One comparable 1000 sample uses 190 point reads and 89 batches; the earlier
AWS 53 sample used 466 point reads and 86 batches. This is about 27% lower median than deployed 50's
3481.431 ms, with direct evidence of less remote read work; it is not flat latency or an SLA.
Command `dfa5ae70-ce0d-4967-88be-c9ebf24ff22a`; artifact
`dee45ea905c48fabac459c3eba5f422141820b4942a0b45fc2e367f1ed5698a2`.
Temporary worker `i-0b627689fa8b9877b` is verified terminated. Source promotion and live gates are recorded below.

Ratings remain correctness 7, compatibility 6, clarity 7, performance 3, operations 5. The next
performance work remains native parent restoration and publication: a 1000-Order root still costs
roughly 0.90–1.07 seconds for restore/admission/execution in this remote diagnostic, and preparation
plus storage/scheduling add further work. Local myos-simplified source and its previously reported
35.965/360.288 ms add and 39.626/330.806 ms payment medians are unchanged by these host-only edits.

## Iteration 54 deployment and live qualification

Candidate 54 was promoted with drift checks and per-file backups (four host source/test files and
compact library documentation/evidence; no library runtime or local-host source change). Deployment
`2ee2ada2-df07-4e3b-8633-e2812042007b` succeeded, including health checks, at 23:49:43 UTC on 8 November.
The runtime artifact is `0101bb6f95b1690fdf43c1e543ed49e17d4360ca3bf2f1d54a6a389db1dd99c7`.
Provider identity, webhook custody, sign-in pool and histories were preserved.

Both authenticated two-Order HTTPS runs pass the ten exact-entry parent completions, final total 400,
login/isolation, retries, retained events/history and API-key creation/read/revocation. The first
post-rollout run bootstrapped in 4963.558 ms and completed operations in 2556.421–7066.494 ms. A repeat
bootstrapped in 2616.133 ms with operations in 2446.031–5672.254 ms. Repeat medians are 3470.563 ms
placeOrder, 5231.470 ms preparePayment, 2744.463 ms acceptPaymentInstruction, 2461.349 ms reportTransfer
and 2929.147 ms acknowledgeTransfer. Each range excludes separately reported lookup and includes
HTTPS and exact parent-history polling. Both runs are retained; do not select only the faster run.

The repeat preparePayment median is slower than iteration 52's 4378.896 ms. These live samples do
not isolate cause and do not establish an application-wide performance improvement. A dedicated
published-clause qualification completed successfully, as recorded below; native phases and
storage are measured separately from HTTP and background activity. The synthetic 1000-Order median improvement remains established for its own
bounded workload; the real-clause performance question is unresolved.

Mixed-source live qualification passes: two signed external originals replay to 14, then a local
operation reaches 19 with its exact committed result. Bootstrap through replay is 1439.210 ms and
local observation 1319.442 ms excluding lookup. Server restore/admission/execution is 61.400 ms and
excludes other publication/scheduling/network work. No writes to the external provider were made.

## Current source and deployment boundary

The live canonical preview is now candidate 58 at
https://d2buikczdox1rt.cloudfront.net. Original blue-java, Python and shared package definitions were
not edited. Iteration 51 remains historical; its selective-loading idea was subsequently bounded
and qualified as 53/54. The user-requested audit ZIP keeps its original candidate 50 cutoff and
separate experimental 53 overlay; subsequent live results do not retroactively change that archive.

Functional live gates and selected exact comparisons pass. The flat graph-size latency objective
and the real-clause millisecond objective remain unmet. The next investigation targets native parent restoration and result preparation, with the
published-clause Payment-creation timing as an additional acceptance case. Optional preparation
measurements from iteration 55 are retained in source; they are not a deployed runtime change.

## Ratings after each iteration

These are engineering judgments, not benchmark results. Iterations through 54 used the same
rating: correctness **7**, compatibility **6**, clarity **7**, performance **3**, operations **5**.
The review follow-up separates those conservative historical ratings from an updated assessment
of the consolidated architecture; new diagnostic instrumentation itself earns no performance gain.
The successful comparisons cover specific cases and do not establish complete specification or
MyOS API/UI parity. Performance cannot receive a higher rating while 1000-Order processing still
costs substantially more than 10-Order processing.

## Evidence

Compact timings are in [campaign-continuation-2026-11-08](campaign-continuation-2026-11-08).
The local files record every timed sample and its receipt-parity result, including rejected
experiments. The unchanged library/local runtimes retain their previously successful 2472/93-test
qualification; the hosted runtime now passes 819 tests freshly. The 48 helper tests are unchanged.
Fresh AWS receipt and live HTTPS gates are recorded separately below. Earlier evidence remains in the [six-hour report](CANONICAL-CAMPAIGN-2026-11-08.md).

## Review follow-up and iterations 55–56

The supplied review assessed the immutable earlier audit, not the later candidate-54 deployment.
Its main recommendation is adopted: concentrate on Agreement restoration and preparation, measure
physical work, and defer broad complete-turn substitution or manifest partitioning until their
benefit is demonstrated. See [the detailed response and current plan](REVIEW-FOLLOW-UP-2026-11-09.md).

The isolated candidate-54 published-clause journey passes ten uncached whole-root comparisons and
finishes at 400. Bootstrap is 4567.006 ms; package loading is a separate 15810.415 ms. The median
preparePayment call is 4240.295 ms through the exact Agreement receipt, without HTTP. This is a
fresh process with the normal transport cache, not the zero-cache synthetic workload. Native
execution occurs independently in Order and Agreement; this diagnostic does not establish that
the adaptive loader caused or cured the live regression. Its worker is verified terminated.

Iteration 55 keeps optional JFR preparation measurements. A bounded local host-memory screen at
10/100/1000 Orders passes twelve exact receipts. Unprofiled medians are 53.683/70.390/460.681 ms.
In the profiled samples, snapshot indices encode 110/650/6050 records and about
87 KB/558 KB/5.27 MB respectively, while 54 index entries change at each size. These counters cover
Payment, Order and Agreement together; they are not unique published pages or network bytes.
The 1000-Order Agreement planning span is 156.137 ms, including membership 31.478, checkpoint
export 7.664, checkpoint pages 10.293, native preparation export 7.965, preparation pages 22.198
and operation catalogue 2.177 ms. These nested timings are one diagnostic sample, not medians.
The remaining planning work still needs attribution. No flat-latency claim is justified.

Iteration 56 tested remembering decoded immutable rows in the existing bounded record cache.
All 26 focused tests and twelve exact receipts passed. Medians were 54.886/75.708/450.772 ms;
encoding still covered 5213 records / 3.52 MB at 1000 Orders. A roughly 2% difference in the large
case, with slower small cases and continued broad reconstruction, is insufficient evidence to
retain the added work. The experiment was reverted. No AWS deployment was performed for 55/56.
The retained diagnostic code passes formatting; the 23 directly relevant storage/native tests
ran freshly for iteration 55 and their exact results were reused FROM-CACHE after the revert.

Current engineering assessment for the hosted implementation: semantic correctness 7/10,
selected compatibility 6/10, architecture/API coherence 8/10, performance against the target 3/10,
operations 5/10. The coherence increase recognizes the earlier interpreter consolidation, not a
new capability introduced by these measurements. Both new iterations retain that assessment.

## Iterations 57–59 — bounded evidence membership, reject local checkpoint experiment

Iteration 57 completes the preparation breakdown. In one profiled 1000-Order Agreement, aliases,
partitioning and ownership validation are 1.729/1.819/1.532 ms, source bindings 2.528 ms, while
canonical evidence costs 58.113 ms within a 153.141 ms plan. A second breakdown attributes 45.475 ms
to evidence catalogue membership lookup. This identifies a physical pass to remove, without assuming
all preparation time is encoding.

Iteration 58 reuses the exact previous protected manifest's occurrence inventory. That inventory
and its catalogue were committed together. Only keys absent from it need catalogue lookup, including
records returning from older history. Actual evidence loading still reconstructs all ordered
occurrences and verifies record keys against identities. No wire format, Blue value, gas, receipt,
current permission or completeness decision changes. A new regression test proves at least 99
unchanged memberships are reused while at most twelve new ones are queried, and compares both old
and new complete observations after cold reads. The initial test fixture reused an event at a new
timestamp and was correctly rejected; its second entry now has a distinct sequence.

Twelve host-memory comparisons pass. The immediate 10/100/1000 medians change from
49.742/71.514/425.041 to 49.782/76.179/396.140 ms; these small samples vary. The stronger structural
result is nine Agreement evidence lookups at both 10 and 1000 Orders, with 1998 exact memberships
reused in the large case. The remaining inventory scan and native rooted processing still grow.

AWS 58 passes eight exact receipts. Medians are 708.204/2225.608 ms for 10/1000 Orders, versus 54's
765.236/2546.845 ms. Large unprofiled samples are 2762.236/2225.608/2010.755 ms; the separately
profiled fourth sample is excluded. Initialization is 1163.843/36921.551 ms. The same isolated 2 CPU,
1 GiB heap, reopened owner storage and disabled transport-cache boundary is used; acquisition is
excluded. Command fc98496e-61f0-49d9-b42a-e3183d8c30fc; artifact
c9838cc437fb3be7cc6ff3cb672955f21b1e023a7ef948adc03961deb4418d84.
Worker i-04e5fbabe04468844 is verified terminated. All 819 host tests and formatting pass freshly.
Decision: retain 58; promotion, deployment and live gates are recorded below.

Iteration 59 tests eliminating duplicate local checkpoint export/save. The first regular-suite
invocation omitted packagesDir and failed fixture setup; the corrected invocation passes 71 tests.
A matched dynamic growth screen passes 16 exact comparisons for baseline and 16 for candidate.
Four-sample medians for 10 Orders are 39.606/43.568 ms baseline add/payment versus 43.810/48.278 ms
candidate; for 1000 they are 360.733/344.380 versus 373.678/353.801 ms. Reject the runtime change;
there is no demonstrated latency benefit. The retained change is solely the qualifier's counter
boundary: capture immediately after processing, before later inspection reopens the root.
Previously reported wall-clock intervals already excluded inspection. Reverted runtime source and
qualification compilation pass their checks. No Python, original Java or shared packages changed.

For 57, 58 and 59 the engineering ratings remain correctness 7, selected compatibility 6, coherence 8,
performance 3 and operations 5. The remaining work includes native cold metadata/preparation
restoration and full-root execution overhead. Neither the flat-size objective nor millisecond
AWS published-clause responsiveness is achieved. See the [review follow-up](REVIEW-FOLLOW-UP-2026-11-09.md)
for the detailed rationale and data boundaries.

## Candidate 58 deployment and live qualification

Source promotion used drift checks and per-file backups. Deployment
`9cb4d83f-cf45-44e8-8abb-39138496c709` succeeded at 00:30:03 UTC on 9 November, including health checks.
Artifact: `60f5faba0fb8afff682c3ade7a68150e695437a5be3a56a8439e29aeb1188aff`.
The preview remains https://d2buikczdox1rt.cloudfront.net. Provider identity, webhook custody,
shared sign-in pool and existing histories were preserved. There was no browser/UI change or new
browser-based acceptance claim.

The post-rollout authenticated two-Order HTTPS journey passes all ten exact submitted-entry parent
completions and reaches 400. Login, account isolation, bootstrap/operation retries, retained events,
initialization history and API-key creation/read/revocation pass. Bootstrap is 5236.780 ms; parent
completion spans 2895.191–6908.966 ms. Operation medians are 5297.154 placeOrder, 5665.063 preparePayment,
2992.099 acceptPaymentInstruction, 3081.961 reportTransfer and 3027.629 acknowledgeTransfer ms.
The interval includes HTTPS and polling and excludes separately recorded catalogue lookup.
This post-rollout run does not establish an application-wide speedup; real-clause latency remains
multi-second. No repeat was selected to replace the slower cold observations.

Mixed-source qualification passes: two signed external originals replay to 14, then a local original
reaches 19 with the exact committed APPLIED result. Bootstrap through replay is 1591.064 ms and local
operation observation is 1342.036 ms, excluding 463.807 ms lookup. The separately reported server
restore/admission/execution is 56.957 ms and excludes publication, scheduling and network work.
No writes to the external provider were performed.

The runtime from 58 is retained. The 59 runtime prototype remains reverted; only its qualification
counter-boundary correction remains. The earlier audit ZIP is unchanged. Next work stays focused
on native cold restoration and rooted execution work, not another account-wide cache. Ratings
remain 7/6/8/3/5 (correctness/selected compatibility/coherence/performance/operations), with the
full low-latency objective still unmet.
