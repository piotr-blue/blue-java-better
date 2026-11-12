# Canonical processing campaign — 8 November 2026

Latest update: [iteration 63 — sparse native preparation publication, 9 November](processing-locality/ITERATION-63.md). Ordinary child-result adoption now exports only changed preparation rows and preserves unchanged attachments. All 3,426 tests, twelve final local and eight DynamoDB semantic comparisons, and both live HTTPS journeys passed; the candidate is deployed. The selected update exports six preparation rows at both 10 and 1,000 Orders and retains equal gas. Cold local medians remain approximately 52/298 ms; AWS acquisition-plus-processing medians are 972/3,801 ms. Near-flat latency remains unachieved. [Gas strategy and compatibility](gas-composition/STRATEGY.md).

> This report preserves the original six-hour campaign. Candidate 50 was subsequently promoted
> and deployed; current results and remaining limits are in the
> [continuation report](CANONICAL-CONTINUATION-2026-11-08.md).

> Post-window qualification correction: the original local “uncached” comparison disabled
> calculation reuse but still loaded native preparation and semantic facts. It was a whole-root
> comparison, but not a fully uncached one. The continuation removes those hints from the oracle
> and reruns exact receipt checks. The AWS oracle and independent original-Java comparison were
> already independent of these hints. See [the continuation report](CANONICAL-CONTINUATION-2026-11-08.md).


Campaign window: 15:35:29–21:35:29 UTC. This report distinguishes completed gates from
work in progress. Status inside a numbered iteration describes that point in the campaign;
the Current result section gives the final integrated status. Earlier experiments are not
the supported runtime contract.

## Current result

**The canonical implementation is deployed and its selected parity checks pass. The requested
flat 10-versus-1,000-Order latency has not been achieved.** This report is not a claim that the
performance goal or complete MyOS compatibility is finished.

Live isolated preview: <https://d2buikczdox1rt.cloudfront.net>. Existing MyOS login accounts work;
this deployment uses fresh document storage. The previous live deployment and its histories were
not rewritten. The final runtime artifact is
`66042c7b4db5c7903369373d0cfcf5a8719b9b4a07153fbc8a80551ee3e2b40d`.

The actual project directories contain the retained changes: `blue-java-better`, `myos-simplified`, and
`myos-aws-test-better` (the repository implementing myos-core-better). Original `blue-java`, Python,
and shared package definitions were not edited. No GitHub access or push was performed.

### What changed

* Removed the alternative Local-1/Blue/Coordinator and detached DocumentRuntime interpreters.
  Direct sessions and durable host attempts now use the same canonical Contracts processor.
  Host records and owner aliases remain private storage structures, not authored Blue types.
* Preserved whole-root identity, gas, ordered events, failure and rollback. Corrected exact-content
  admission and nested BEX argument-view differences exposed by stronger comparisons.
* Aligned local/AWS deterministic rejection handling, retained original timeline identities and
  canonical ordering, and corrected periodic external refresh so a quiet source can advance its
  verified completeness boundary without a new entry.
* Reduced unnecessary copies, repeated declaration/source lookups, component/partition work,
  metadata encoding, preparation reconstruction and network batches. Kept immutable package reuse.
  Rejected changes that regressed latency or weakened historical subscription boundaries.
* Corrected misleading timing labels and required exact parent-history completion in the final
  clause qualifier. Complete qualification output is retained beyond SSM's inline truncation.

### Final measurements

Local measurements start after reopening durable storage and include appending, document
restoration, processing and committing. Connection/runtime opening is outside this interval. Each
operation starts without a resident mutable document session; the JVM and package catalogue stay
warm. Both final runs retain all five samples per size and operation, with no profiler enabled.
These are bounded diagnostic samples, not percentiles or an SLA.

| Workload | 10 Orders | 1,000 Orders |
| --- | ---: | ---: |
| Local add Order, final run median | 40.00 ms | 412.98 ms |
| Local Payment → Order → Agreement, final run median | 59.24 ms | 381.20 ms |
| Local add Order, independent repeat median | 35.58 ms | 359.57 ms |
| Local Payment chain, independent repeat median | 40.97 ms | 351.16 ms |
| AWS cold durable Payment chain, final unprofiled median | 884.68 ms | 4,512.70 ms |
| AWS cold initialization | 1,295.70 ms | 42,361.00 ms |

The AWS worker was isolated, CPU-limited to two cores with a 1 GiB Java heap; transport caches
were recreated per input. Each size has three unprofiled samples and one separately marked
profiled sample. The exact oracle runs outside measurement. This uses ordinary Contracts workflows;
it is not the complete Agreements/Documents/Payment installer workload.

The real published-clause test creates ten Orders and completes all fifty operations through the
Agreement's exact submitted-entry receipt. Median completion is **4,105.90 ms** for placeOrder,
**4,208.13 ms** for preparePayment, **2,230.89 ms** for acceptPaymentInstruction,
**2,582.80 ms** for reportTransfer and **2,374.16 ms** for acknowledgeTransfer. All fifty root
results match the uncached whole-root oracle; the final total is 2,000. A separate twenty-Order
run matches all one hundred root receipts and reaches 4,000, but some of its earlier timing checks
stopped at child visibility; those measurements are explicitly labelled and are not interchangeable.

The final live HTTPS reproduction on the custody-corrected artifact reaches 400 with two Orders
and exact parent-history waits: bootstrap **3,664.42 ms**, ten operation observations
**2,079.36–3,864.24 ms**, excluding separate catalogue lookups of about 450–490 ms.
This uses persistent TLS and includes network/polling. The preceding reproduction recorded
2,683.75 ms bootstrap and 1,817.71–2,876.86 ms observations; both runs are retained. No performance
improvement is claimed from this custody-only fix. It demonstrates a working console/API flow,
not the requested millisecond hosted performance.

### Verification and current rating

**3,372 Java tests pass**, with no failures, errors or skips: library 2,467; local 93; AWS host 812.
There are also 46 passing deployment/helper tests and 40 selected exact comparisons with original
`blue-java`. Cyclic qualification includes a twenty-member ring and eight concurrent eight-member
rings; repeated complete histories agree. The 100,000-source scheduler test uses placeholder
native states and must not be presented as executing 100,000 real Blue documents.

The HTTPS gate before the final custody-only correction passes authentication, account isolation,
idempotent bootstrap/operations, history and API-key revocation. The final deployed artifact is
rechecked through authenticated clause execution and mixed-source replay. External import separately
proves a strictly advancing verified boundary with an unchanged head. The retained reports identify
which artifact and measurement boundary each check covers.

| myos-core-better category | Final score / 10 |
| --- | ---: |
| Processing correctness evidence | 7 |
| Standard / original implementation compatibility | 6 |
| API and implementation clarity | 7 |
| Performance against the requested target | 3 |
| Operational readiness | 5 |

These are engineering judgments. Each iteration below records its keep/reject decision and rating.
Selected comparisons do not prove complete standard conformance, full MyOS API parity, HA, arbitrary
dynamic cycle repartitioning, or a production SLA. Old experimental histories require explicit replay.

### What still causes growth

Independent scheduling is established more strongly than independent computation. For a Payment
input, the host schedules the affected Payment, Order and Agreement. Each parent still reconstructs
and validates its rooted state and processes the original under that root's gas/rollback boundary.
Unchanged siblings can contribute metadata, topology, identity and proof work even though their
business workflows do not execute. The final repeated local add at 1,000 Orders spends a median
roughly 55 ms restoring, 201 ms invoking the native processor and 53 ms preparing publication.

The hosted path adds retained-content acquisition, source/access admission, publication and console
observation. The final real-clause JFR distinguishes these phases; for preparePayment the median
sum of native execution work across its affected owners is about 1,434 ms, so this is not solely
network latency or proof export. Per-phase medians must not be added as if they were one wall-clock
sample. The account publication manifest is still a possible contention boundary; this campaign
did not establish its capacity under many concurrent accounts or a hot single account.

The next substantive change should preserve immutable native member/edge structures and declaration
deltas across the host boundary, then implement bounded reuse of a child calculation/turn with the
same before-state, input, evidence, observation order, gas exhaustion and rollback rules. It must
remove required materialization, not replace batched reads with thousands of individual remote
lookups. A plain receive-and-add-gas shortcut is not compatible with the selected whole-root contract.
Logical gas can remain unchanged while physical work becomes incremental; cheaper physical execution
does not by itself require inventing another Blue language or pretending gas is elapsed time.

### Reproduce and inspect

Use the [preview guide](../../myos-core-better/docs/canonical-preview.md) in the host repository
for the authenticated two-Order tutorial. From that checkout:

```sh
python3 scripts/reproduce_canonical.py \
  --url https://d2buikczdox1rt.cloudfront.net \
  --email piotr@bluecontract.com --orders 2 \
  --report reports/my-canonical-clause-run.json
```

The script prompts for the password; it does not store it in the report. An API key is supported
through `MYOS_API_KEY`. The retained [evidence index](campaign-2026-11-08/README.md),
[machine-readable summary](campaign-2026-11-08/summary.json), and
[SHA-256 manifest](campaign-2026-11-08/MANIFEST.json) make the principal results portable. No large
trace archives or credentials are included in that evidence directory.

## Acceptance rules

* Published Language, BEX, Contracts and Coordination identities determine document semantics.
  Host indexes, owner aliases, delivery obligations and storage pages are private infrastructure.
* A whole-root invocation is the compatibility oracle: document identity, gas, ordered events,
  failure/rollback and exact native receipt must agree. Business counters alone are insufficient.
* Keep immutable package/type caches; do not require resident mutable document sessions.
* Measure storage restoration, execution, publication preparation and commit separately.
  Local CPU times are not AWS end-to-end latency.
* Preserve original accepted timeline entries. Old experimental checkpoints need explicit replay;
  relabelling their edition would not make their semantics compatible.
* Keep an optimization only after correctness checks and a measured benefit. No GitHub access.

## Iteration 1 — one canonical execution route (keep)

Completed gates:

| Gate | Result |
| --- | --- |
| Library `build` | 2,448 tests, no failures/errors/skips |
| Local `test clausesTest scaleTest` | 90 tests, no failures/errors/skips |
| AWS host `format build` | 788 tests, no failures/errors/skips |
| Deployment helper tests | 39 passed; mocked AWS calls |
| Separate original `blue-java` comparison | 40 transitions, exact observations and receipts equal |

The comparison exercises initialization, adding Orders, submitting an Order and settling a
Payment for 1, 10 and 20 Orders. The normal library build has frozen expected receipts and does
not depend on sibling Java or Python checkouts. The manual comparator remains an optional tool.

### Changes retained

The Local-1 `Blue`/`Coordinator` interpreter, `DocumentRuntime`, alternate execution profiles
and detached subtree bypasses were removed. `Contracts.Session` is the document API;
`Contracts.Execution` is an adapter for persisted canonical state and uses the same processor.
There is no `ContractOwners` Blue type or replacement Contracts semantics. Native `receive`
still has its distinct published meaning; the durable hosts use rooted `process` attempts.

Canonical member allocation now retains the invocation's sealed cause and allocation counter.
Private host aliases map invocation member keys to durable owner addresses without rewriting
native snapshots. This allows a child-created Payment to retain its independent durable owner
when its parent later reproduces the same rooted calculation.

Deterministic failure and gas exhaustion complete an input with rolled-back business state.
Missing resources and unverified input remain pending. The AWS host no longer writes duplicate
transaction checks for a rejected input. Rooted timeline routing now retains receiver lineage,
including embedded receivers, alongside committed state.

An exact-input regression exposed a concrete semantic error: creating a Payment and then paying
it produced the same business state but gas **6,425 versus 6,014**, depending on whether the input
arrived inline or by archived reference. The invocation boundary discarded the inline input's
already admitted identity witness. It now retains that witness, as required by the published
Contracts transport-invariance rule. New tests compare inline/reference receipts and gas limits
below, at and above the successful charge. This correction does not alter the 40 original-Java
workflow fixtures. It does not establish equivalence for every possible old-Java input.

`Session.process(LanguageContent, ...)` now accepts exact prepared content directly. Passing it
through an `Object` variable delegates to the same exact-content route rather than interpreting
the wrapper as an arbitrary scalar. Transactions use the same dispatch.

Deployment configuration now selects canonical processing. The console-native option adds
host services and packages; it does not select a different document language. Old experimental
checkpoint editions fail with an explicit replay requirement.

### Experiment rejected

A reusable processor-configuration template showed inconsistent gains in alternating paired
measurements. It was reverted. Existing transaction-copy constructors remain because they
implement private transaction isolation, not a configuration performance shortcut.

### Remaining performance baseline

The local cold-owner fanout test closes and reopens the runtime before one child operation. It
retains a shared package catalog and JVM, but no mutable document session. One observation per
size is diagnostic, not a statistically robust benchmark:

| Embedded children | Child restore + execute | Parent restore + rooted execute | Append through parent commit |
| ---: | ---: | ---: | ---: |
| 1 | 24.09 ms | 13.49 ms | 69.73 ms |
| 5 | 8.35 ms | 12.83 ms | 36.98 ms |
| 100 | 7.32 ms | 38.05 ms | 65.53 ms |
| 1,000 | 6.52 ms | 239.52 ms | 329.21 ms |

All cases compare the stored parent receipt with an uncached native attempt. These are simple
Contracts workflows, not the full published Agreements/Documents clause installation scenario.
The 100,000-timeline test separately verifies indexed scheduling; it does not measure execution
of 100,000 embedded document bodies. Parent cost still grows and is the next target.

### Ratings after iteration 1

These are engineering assessments, not independent certification:

| `myos-core-better` category | Score / 10 | Basis |
| --- | ---: | --- |
| Processing correctness | 7 | Full selected suites and exact receipt regressions; wider parity still needed |
| Compatibility | 6 | Selected original-Java workflows match; old experimental histories require replay |
| API and implementation clarity | 7 | One semantic engine; substantial legacy host plumbing still remains |
| Performance | 3 | Child work is small; parent restore/processing still scales with graph size |
| Operational readiness | 4 | Local gates pass; new release has not yet been qualified or deployed on AWS |

Next: repeated restarted Payment → Order → Agreement measurements at 10 and 1,000 Orders,
phase/counter attribution, targeted changes, then isolated AWS qualification of the tested build.

## Iteration 2 — measure the actual rooted cost (keep schema fix and qualification)

Added `myos-simplified`'s explicit `orderScale` qualification. An Agreement owns 10 or 1,000
Orders, each with one Payment. Order initialization emits a lifecycle event which the Agreement
counts. A Payment operation records 42, emits an event, updates its Order and then the Agreement.
Every sample closes/reopens the local runtime and requires exactly three jobs. After timing,
an independent native attempt without calculation reuse must reproduce the exact root receipt.
The benchmark can collect physical counters and a bounded JFR of its last operation.

The fixture uses ordinary published Contracts workflows. It does not stand in for the larger
published Agreements/Activation/Documents/Payment clause installation workload. The shared
package catalog and JVM remain warm; mutable sessions and local SQL connections do not.

Seven operations per size were recorded; the following median excludes sample zero. These
are small local samples, not a latency SLA or an AWS comparison:

| Version | 10 Orders: append through commit | 1,000 Orders: append through commit |
| --- | ---: | ---: |
| Iteration 1 baseline | 43.52 ms | 474.46 ms |
| Skip absent field-count predicate | 48.04 ms | 415.16 ms |
| Additional channel-lifetime memo experiment | 45.89 ms | 426.84 ms |

The retained change avoids counting every concrete object field when neither `minFields` nor
`maxFields` exists. Ordinary child/type/schema validation remains in place. All 2,448 library
tests pass and every measured root receipt matches the independent oracle. The small case
does not establish a speedup; the large case's execution interval falls from 267.78 to 192.37 ms.
The separate channel-lifetime memo was reverted: it passed semantic tests but did not improve
end-to-end results. Initialization of 1,000 Orders still takes about 26 seconds and needs work.

The diagnostic bridge counter reports 861 versus 871 body-open calls, while restored metadata
grows from 46 to 4,006 records. **That counter does not include all internal Language reads.**
JFR found additional Language traversal under schema validation, so the near-flat bridge count
must not be interpreted as proof that all physical body access is constant. Other broad costs
include admission, metadata construction and proof-record serialization. The 1,000-Order
payment's canonical observations occupy about 6.48 MB: 5.53 MB of unit results and 0.95 MB of
turn completions. Only three business documents change. This is a storage-reuse opportunity,
not permission to discard canonical evidence or change its receipt.

### Isolated DynamoDB measurement

The tested schema version ran on a temporary `c7i.xlarge` with a two-CPU quota, 1 GiB Java heap
and 1.5 GiB memory limit. It used isolated account `owner-qa-080a8bb7bdf0`, real DynamoDB and
signed timeline acquisition. The worker was terminated afterward. Four payment inputs per
size matched whole-native BlueId, gas and receipt, with exactly three owner publications.

| Orders / total documents | Initialization | Median durable processing, all 4 samples | Median root restore/admission/execution |
| --- | ---: | ---: | ---: |
| 10 / 21 | 1,421 ms | 461 ms | 66.50 ms |
| 128 / 257 | 7,080 ms | 692 ms | 278.37 ms |

Signed acquisition is separate: approximately 78–209 ms. The probe uses fresh mutable attempts
but bounded immutable caches warmed by initialization; its oracle runs in the same JVM. These
are not console HTTP times and not cold-storage measurements. The recorded steady operation
still makes around 35 point metadata reads, six commits and six content-publication calls.
The live console was not modified by this qualification.

Evidence: host report `reports/iteration2-aws-baseline.json`, SSM command
`337632ab-8202-4187-9aaa-831ed7feaad7`, artifact
`19fd7f44ccb492fa8970b4f19600cdaf0b7934a3f2cb814497de02887eca5fce`.

Ratings: correctness **7/10**, compatibility **6/10**, API clarity **7/10**, performance **3/10**,
operational readiness **5/10**. AWS parity and cleanup now have fresh evidence; the requested
flat latency is still unachieved. Next: reduce repeated proof/metadata storage and restoration,
then measure the same workload again before changing deployment.

## Iteration 3 — reuse canonical proof records; preserve lazy protocol resolution (keep)

Canonical unit and turn records now have private physical storage pages keyed by their native
commitments. Publication preserves the ordered occurrence lists, including duplicates. Reading
reconstructs the original observations byte-for-byte; no new Blue type or receipt rule is added.
SQLite commits new records with the outcome. The AWS host publishes immutable pages before the
conditional head update and reuses records only through the previous protected publication.
Old full-observation rows remain readable. Tests cover fresh repository instances, historical
proofs after later updates, rejection diagnostics, missing records, tenant separation and corrupt
AWS pages.

For seven payments at 1,000 Orders, local outcome rows shrank from **56.35 MB to 3.51 MB**.
The additional shared proof-record table occupies **24.75 MB**, including initialization and the
first payment. After that first payment, each root outcome introduces nine new native records
instead of repeating all 4,002 unit occurrences. This is a storage improvement; the measured
local median was **437.84 ms**, versus iteration 2's **415.16 ms**, and does not establish a latency
improvement. This first run overlapped regression tests. An isolated diagnostic run recorded
420.67, 402.54 and 426.17 ms after its first sample, with the last sample under JFR.

The full host suite exposed a real consequence of the earlier schema optimization: operation
grammar inspection created a resolver-free Language context. Eager schema traversal had happened
to preload some deferred type bodies, masking that defect. The grammar bridge now retains the
current verification attempt's lazy lookup. Identity checks remain ordinary, and another request
cannot inherit that resolver or its authority. The published Payment tutorial passes in whole-root
and independent-owner modes after the correction.

Full gates: **2,449 library tests, 92 local tests, 791 host tests**, all passing. A bounded JFR
attributes 4,695 of 8,912 initialization samples to preparing the canonical logical-prefix
commitment, including repeated TreeSet construction, sorting and string encoding. Payment
processing still spends substantial time restoring and validating graph metadata.

### AWS capacity failure is not a successful qualification

Isolated worker `i-08cddb05f208e5a96`, SSM command
`d5be8602-b30f-4c66-ac3b-2bed2df56e4e`, artifact
`be117579deada17e6c8476795f444407c0cfdfe7c42f5cf7592ebf001b3df0f2`, account
`owner-qa-f68122059ba3` used the same two-CPU/1 GiB Java limits as iteration 2.

| Orders | Initialization | Median processing, all four samples | Exact parity |
| ---: | ---: | ---: | --- |
| 10 | 1,508 ms | 507.12 ms | Four inputs pass |
| 128 | 7,209 ms | 759.23 ms | Four inputs pass |
| 1,000 | Failed during publication | Not measured | Not established |

The larger case exhausted the Java heap while encoding owner publication/history records.
The worker was terminated by the qualification cleanup. The live console remains unchanged.
Raw report: `myos-core-better/reports/iteration3-aws-evidence.json`. The run does not demonstrate
an AWS speedup, and no 1,000-Order latency claim is justified.

Ratings: correctness **7/10**, compatibility **6/10**, clarity **7/10**, performance **3/10**,
operational readiness **4/10**. The capacity failure reduces the operational score. Next:
remove repeated graph-wide event payloads from per-owner publications, and prepare canonical
logical-prefix inventories incrementally while preserving the exact committed bytes.


## Iteration 4 — bounded per-owner emissions and ordered proof inventories (keep)

The 1,000-Order heap failure had a concrete quadratic cause: each child publication copied
all graph emission occurrences, and its retained event-content index also received every
publishing member's emissions. Publication now groups occurrences by native emitter once and
stores only that owner's emissions and projected events. The complete rooted observations stay
in the canonical evidence store. A 1/8/64-child regression checks exact receipts, event ownership,
historical proof reconstruction and a linear bound on serialized per-owner publications.

Logical-prefix commitments now retain append-only ordered inventories and encode identities
once. Ordinary and cyclic establishment share their original canonical record order. No hash
domain, byte sequence, semantic gas or failure rule changes. Tests cover incremental ordering,
escaping, duplicate admission, independent forks and the original-Java golden receipts.

Gates: **2,451 library tests, 92 local tests, 794 host tests**, all passing. The staging-only
local test command was first run without its explicit package-directory property and failed to
find the sibling packages; rerunning with the correct package path passed. No test was removed.

| Restarted local scenario | 10 Orders | 1,000 Orders |
| --- | ---: | ---: |
| Initialization | 229.82 ms | 18,392.39 ms |
| Native initialization component | 115.77 ms | 15,747.47 ms |
| Median append through commit, samples 1–6 | 46.66 ms | 397.49 ms |
| Median total restoration | 3.95 ms | 68.20 ms |
| Median total execution | 22.44 ms | 205.48 ms |
| Median publication preparation | 4.35 ms | 72.21 ms |

Every measured operation still runs exactly Payment, Order and Agreement and matches the
independent native receipt. The previous 1,000-Order initialization measurements were 32.10 s
(overlapping tests) and 28.96 s (isolated JFR); this new unprofiled run is substantially faster,
but is not a controlled statistical claim. Shared packages/JVM remain warm, mutable sessions
are reopened for each sample. The large-operation median remains about 8.5 times the small one.

The isolated AWS run used the same two-CPU quota and 1 GiB heap as iteration 3. It completed all
sizes, with four exact-parity payments per size and three owner publications each:

| Orders | Initialization | Median durable processing | Median root restore/admission/execution |
| ---: | ---: | ---: | ---: |
| 10 | 1,306 ms | 446 ms | 61.53 ms |
| 128 | 4,639 ms | 601 ms | 281.37 ms |
| 1,000 | 45,895 ms | 3,288 ms | 2,568.02 ms |

The 1,000-Order calls were 5,926.81, 3,023.50, 3,551.74 and 2,886.18 ms. Signed acquisition
was separate, 390–517 ms at that size. These are not acceptable target times. This probe still
retains its comparison oracle in the same JVM, alongside immutable runtime caches; that must
be separated or explicitly profiled before treating this as production memory behavior.

Evidence: host `reports/iteration4-aws-inventory.json`, SSM command
`4c56185d-56da-4bc7-bab6-d69486cbeb7e`, artifact
`dfd8490813ff1bf7f2a18dd964fc1bcfaae6c29c62d2df79f271d1a610412134`.
Worker `i-025a31c7ed0074cf7` was terminated; the live console was unchanged.

Ratings: correctness **7/10**, compatibility **6/10**, clarity **7/10**, performance **3/10**,
operational readiness **5/10**. Keep the changes for measured initialization and storage/memory
improvements. Next: eliminate disposable graph preparation, reduce broad validation/publication
work and qualify AWS execution without a resident comparison oracle.


## Iteration 5 — remove disposable preparation and separate timed execution from replay (keep)

Local graph turns now inherit the invocation's existing logical ledger directly. The previous
constructor built and encoded a replacement ledger, including all carried document identities,
then discarded it immediately. Exact witness installation remains unchanged. All **2,451 library
tests** pass, and the repeated local 10/1,000 scenario retains exact receipts. Initialization
at 1,000 Orders fell again, from **18.39 s to 15.50 s** (native component 12.83 s). Median restarted
operation times are **43.99 ms and 389.02 ms**, respectively. The initialization benefit is useful;
there is still no flat operation latency.

The owner qualification no longer constructs or executes its whole-root comparison oracle
before timed operations. It reads the selected Payment through its committed metadata, processes
four signed originals, then reconstructs the same successive signed prefixes in separate in-memory
storage and checks initialization and every whole-root receipt. A small cold-reader smoke run
also checks the delayed replay and bounded JFR-summary path.

The AWS run reopened owner storage objects before each input, but **the DynamoDB transport's
bounded immutable-page cache was still shared**. This is not a fully cold storage claim. The
1 GiB heap / two-CPU run produced:

| Orders | Initialization | Four durable operation times | GC collection time during those operations |
| ---: | ---: | --- | --- |
| 10 | 1,293 ms | 688, 482, 451, 448 ms | 55, 5, 3, 12 ms |
| 1,000 | 44,647 ms | 6,564, 3,045, 3,088, 3,509 ms | 1,030, 569, 572, 926 ms |

Every initialization/payment comparison passed. The last operation at each size was profiled.
At 1,000 Orders, the third call made 2,286 content lookups and 2,798 batch lookup calls, mostly
served by the lower transport cache; their measured totals were about 12 ms and 37 ms. There
were 36 metadata gets, six commits and six content publications. This points to repeated decoding,
validation and allocation as well as network cost; it is not simply thousands of network calls.
The AWS adapter still disables persisted preparation, unlike the local host, after an earlier
lazy-loading experiment regressed. A subsequent change must address why that experiment failed.

The SSM command's final status was **Failed** because the optional JFR upload lacked S3 PutObject
permission, after all measurements and native comparisons completed. No permissions were
broadened. The worker was terminated and the helper now emits bounded CPU summaries through its
existing output instead. Raw AWS JFR files from this run were not retained. Evidence is
`reports/iteration5-aws-cold.json` and `reports/iteration5-aws-raw.json`; command
`3cf85b55-bbec-45af-a06b-4ca4a7ea6ce5`, artifact
`4036de4aa31526d3c7b10c29cd6151edacb9d3f550b999d156ac7e8360835a95`.

Ratings remain correctness **7/10**, compatibility **6/10**, clarity **7/10**, performance **3/10**,
operational readiness **5/10**. Next: reduce allocation and redundant metadata page handling,
measure a genuinely cold transport, and test protected preparation with improved physical storage.


## Iteration 6 — packed metadata, owned local copies and redundant head reads (keep)

Small snapshot metadata now shares bounded radix leaves; older page formats remain readable.
A local turn copies its writable member and borrows sealed dependency documents. General snapshot
copies, archive boundaries and speculative graph creation retain independent mutable copies.
A topology regression checks that sibling turns and later archive mutation remain isolated.
Publication trusts ownership registrations already bound atomically to its protected before-snapshot;
it no longer rereads every sibling head merely to reconfirm the same representative. New members,
changed representatives and changed unscheduled dependencies retain their original checks.

Gates: **2,452 library tests, 92 local tests, 796 host tests**, all passing; the separate original
Java comparator again passes **40 exact observation/receipt transitions**. Restarted local medians
are **43.88 ms (10 Orders)** and **399.28 ms (1,000 Orders)**; initialization is 242 ms and 15.62 s.
This does not demonstrate an operation-latency improvement over iteration 5 locally.

The qualification now disables both DynamoDB transport caches, reopens owner storage per input,
and defers comparison replay until after all measurements. Immutable package caches and the JVM
remain warm. This correction exposed that the previous three-second AWS result depended on warm
immutable storage pages. Before removing redundant ownership reads, the true cold 1,000-Order
operations took **13.01–13.73 seconds**, with exact parity. After the ownership/copy changes:

| Orders | Initialization | Four durable processing times | Median |
| ---: | ---: | --- | ---: |
| 10 | 1,247 ms | 1,040, 814, 759, 765 ms | 790 ms |
| 1,000 | 40,616 ms | 6,273, 5,881, 5,496, 5,803 ms | 5,842 ms |

The third large operation made **215 point-content reads and 683 batch-content calls**, versus
2,296 and 2,671 before the ownership change. Their measured durations fell from about 3.88 + 6.64 s
to 0.38 + 2.42 s. The six owner commits remained about 96 ms. Root restoration/admission/execution
still took 4.12–4.41 s; JFR attributes 142 of 216 sampled stacks to restoration. The preparation
strategy is the next target, not a claim that hardware or caches make the slope disappear.

All eight payments and both initializations matched independent whole-root native receipts.
Evidence: `reports/iteration6-aws-cold.json`, `reports/iteration6b-aws-cold.json` and corresponding
raw SSM output. Final command `ff3733ae-373f-449e-8494-e8bb4bf6ffd9`, artifact
`fde19eff12286f878ff96de91ecceea2bd1d87a3fed05bf3a139e00c54674a00`.
Worker `i-006ebdaf0a16c27a8` terminated successfully. Live service remains unchanged.

Ratings: correctness **7/10**, compatibility **6/10**, clarity **7/10**, performance **3/10**,
operational readiness **5/10**. Keep the physical storage/read reductions; do not present the
local noise or AWS seconds as the requested flat millisecond result. Next: page protected native
preparations, reuse shared declarations and load content only when an operation requires it.

## Iteration 7 — lazy preparation experiments (rejected)

Three protected preparation layouts preserved exact native results but increased AWS latency.
The original lazy candidate took approximately 31.68 s per 1,000-Order update. Persisting semantic
facts globally reduced this to 9.34 s, and batching catalogue reads plus reusing unchanged bindings
reduced it to 8.69 s. These remain worse than iteration 6's genuinely cold 5.84 s median. The
execution path was reverted. Account-global semantic fact storage and the experimental preparation
classes are not part of the retained design. The independent alias-resolution correctness fix and
unchanged-binding reuse remain, with regression coverage.

Evidence: host reports `iteration7-aws-cold.json`, `iteration7b-aws-cold.json`,
`iteration7c-aws-cold.json` and their raw SSM outputs. Dedicated workers were terminated.
Ratings: correctness **7**, compatibility **6**, clarity **7**, performance **3**, operations **5**.
Next: reduce actual native allocation and graph traversal, then reconsider the physical storage
layout without accepting slower execution simply because it is more lazy.

## Iteration 8 — bounded local topology and semantic fact lifetime (keep)

Outgoing-edge reconciliation uses a contiguous sorted range instead of scanning every edge.
Structural validation facts live with the document they describe. Only temporary restored-body
facts use the weighted global cache; ordinary structural checks no longer weigh an entire graph
on every lookup. Facts still bind the exact configuration, scope and content. This is reuse of
completed checks, not authority to skip a new contextual constraint.

A correctness regression also exposed premature input-provider closure: a completed invocation
could need input-only content while exporting its checkpoint even when cached semantic checks
had not opened it. Execution now retains that scoped input catalogue until close. The regression
publishes the checkpoint after processing and checks the previously missing content.

The measured number of BEX executions stayed **6**, with **6** reused calculations, for both 10
and 1,000 Orders. Body-open requests stayed **861**, and body validations **6**. In contrast,
restored members grew **46 → 4,006**, built metadata **52 → 4,012**, and idle proof units
**24 → 2,004**. This locates the remaining slope in rooted metadata/proof work rather than extra
business expressions. Pure expression reuse does not yet mean whole native child-turn reuse.

The actual published Activation / Incremental Documents / Payment / Manual Payments journey
passed with two Orders. On the local asynchronous scenario, worker counts 1, 4 and 8 gave backlog
times **1,263.67 / 753.26 / 732.10 ms**. All repeated runs retained identical histories, final heads
and emitted-event digests. The observed maximum concurrency was 1 / 4 / 4, respectively.
The whole-root native comparison also passed.

On AWS, bootstrap took **7,288 ms**, after a separate **15,374 ms** package/JVM setup. Ten actual
clause operations were observed complete in **1,212–5,672 ms**. The two Orders each paid 200 and
the Agreement reached 400, using the same two participant timelines. This was an application-API
probe, not HTTP; storage caches were bounded and warm. Transaction retries and provider/access
work contributed substantial delay (the final acknowledgement made 70 commit attempts, including
15 retryable attempts). This is correctness evidence, not an acceptable speed result.
Evidence: local `iteration8-payments/report.json`, host `iteration8-published-clauses.json`.
Ratings: correctness **7**, compatibility **6**, clarity **7**, performance **3**, operations **5**.
Next: streamline canonical encoding and SCC construction, and measure dynamic Order creation.

## Iteration 9 — canonical encoding and SCC allocation (keep)

Compact scalar records reuse pre-escaped UTF-8 keys only when their known UTF-16 order equals
Contracts' Unicode scalar order. Supplementary/BMP key order falls back to the general canonical
sort. Integer/long encoding avoids temporary string objects. New cases cover escaping, null,
minimum/maximum integers and Unicode ordering; committed identities are unchanged.

SCC construction uses hash adjacency sets rather than tree maps/sets at every insertion. The
entry order, component contents and sink-first selection remain explicitly canonical. Thirty
shuffled permutations, duplicate edges, a four-node cycle and Unicode labels retain the same
component order. All **2,456 library tests** pass.

An uncontended restarted local run with nine samples per size measured **44.374 ms** at 10 Orders
and **345.359 ms** at 1,000. Initialization was **230.66 ms / 15,476.33 ms**. Every measured native
receipt matched. This is a useful reduction from the campaign's 474 ms baseline, but still a
7.8-times size penalty, not the requested flat latency. Evidence: local
`build/qualification/iteration9-final/report.json`.
Ratings: correctness **7**, compatibility **6**, clarity **7**, performance **3**, operations **5**.
Next: test cold AWS layout alternatives and dynamic growth rather than only fixed-graph payments.

## Iteration 10 — per-history preparation pages (rejected); dynamic growth qualification (keep)

Paging semantic facts per history also preserved exact receipts but gave **950 ms / 14,828 ms**
AWS medians at 10 / 1,000 Orders. The larger case made 413 point reads and 1,739 batch reads;
batch time alone was 8.77 s. This candidate was reverted with the other preparation experiments.
A transient CLI network failure interrupted polling and cleanup; the completed Success result was
recovered, and the specific worker was then terminated successfully. Evidence: host
`iteration10-aws-cold.json` and `iteration10-aws-raw.json`.

The new restarted growth qualification starts with 10 or 1,000 Orders, adds five more through a
real operation, receives each new Order's initialization event at the Agreement, then pays the
newly created Payment and observes Order and Agreement reactions. Every add and payment is
compared to independent whole-root native processing, including the exact receipt.

Before the next admission fix, median add latency was **57.36 ms / 1,906.82 ms**, and subsequent
Payment latency **43.54 ms / 335.07 ms**. The large add spent roughly 0.35 s in native restoration,
execution and publication preparation; profiling identified repeated Language admission of the
addressed Agreement in the input path as a separate large cost. An initial host-only attempt to
retain that admission passed tests but did **not** improve the measured latency, so its mechanism
needs correction before any performance benefit can be claimed.

A separate canonical AWS preview was provisioned to preserve the old experimental histories.
It uses new DynamoDB/S3 storage, an independent provider identity and scoped reader custody,
while reusing the current Cognito sign-in pool and the existing console build. At this checkpoint
its infrastructure is ready, but application deployment and authenticated qualification are still
pending. The original live service is unchanged.
Ratings: correctness **7**, compatibility **6**, clarity **7**, performance **3**, operations **5**.
Next: fix admission propagation, qualify packed content storage, deploy the tested canonical
candidate to the separate preview and run real HTTPS operations.

## Iteration 11 — exact target admission and packed immutable content (keep)

The local host now persists each committed member's Language admission beside its protected native
publication. Operation recognition receives only the exact selected owner's prior admission; all
request grammar, target lineage and channel authority checks remain. TimelinePublisher also carries
its explicitly retained target facts into entry preparation, instead of discarding them first.
A regression proves that the same target is reusable only in the selected publisher and does not
teach ordinary Language admission or another publisher to trust missing content.

Five restarted add/payment pairs at each graph size retained exact native receipts. The 1,000-Order
add median fell **1,906.82 → 940.05 ms**, while subsequent Payment processing stayed approximately
**350 ms**. The first host-only admission experiment failed to improve latency and was not counted
as a success. The final library gate passes **2,457 tests**; the host gate passes **796 tests**.

Small content records now share radix leaves, while larger bodies retain separate immutable
addresses. Protected source export supports both forms without changing content identities,
account scope, pinned snapshot selection or the new-content limit. Current-closure discovery
batches larger lookup frontiers without enumerating obsolete historical catalogue records. Older
physical manifest formats remain readable; canonical snapshot/receipt bytes are unchanged.

| Cold AWS scenario | 10 Orders | 1,000 Orders |
| --- | ---: | ---: |
| Initialization | 1,346 ms | 41,659 ms |
| Median durable processing | 881 ms | 5,402 ms |
| Third operation point-content reads | 70 | 145 |
| Third operation batch-content calls | 56 | 450 |

The large case previously took 5,842 ms, with 215 point-content and 683 batch-content calls. This is
a modest physical read reduction, not a flat-latency result. Signed acquisition is separate and took
approximately **1.88–2.06 s** in the large case; it must not disappear from an end-to-end claim.
All measured initialization/payment receipts matched. The two-CPU / 1 GiB worker was terminated.
Evidence: `iteration11-aws-cold.json`, `iteration11-aws-raw.json`, command
`998d4004-90cc-4039-84d8-7bbdccc5a835`, artifact
`17c9d7b5773c95e215dbec53a6ada6d78ea9b89c5f8f694bfabaedbb2c210370`.

The tested canonical application was deployed successfully to a separate preview:
**https://d2buikczdox1rt.cloudfront.net**. It reuses the console UI and existing Cognito sign-in pool,
with fresh DynamoDB/S3 storage, its own provider key, its own dashboard encryption key and scoped
reader custody. Deployment health confirms the canonical edition, native admission and automatic
Timeline processing. The existing service and histories are retained. Deployment artifact:
`cefcf96ebd3ebae88afd18d2174941077c4d9ce67a89237ca4300c943f8608c0`.

A real HTTPS two-Order journey completed all ten business operations and propagated paid totals
Payment → Order → Agreement = 200 → 200 → 400. Observed operation times were **2.70–9.18 s**, including
client network and polling, excluding catalogue lookup. API-key creation and use succeeded; the
qualification helper initially omitted the required revocation idempotency header, then failed to
parse the successful empty 204 response. The key was confirmed revoked. These were helper failures;
the journey is not marked as a fully passing HTTP qualification until the corrected helper reruns.
The hosted performance remains unacceptable for the requested target.

Ratings: correctness **7**, compatibility **6**, clarity **7**, performance **3**, operations **5**.
Next: remove broad scheduler reconstruction on a single attachment change, avoid unnecessary local
completeness publications and repeat the real HTTPS journey against the next tested deployment.

## Iteration 12 — incremental local scheduler inputs and demand-driven AWS completeness (keep)

A local attachment change now applies the durable source delta. It does not delete and rebuild
unrelated siblings' pending inputs and coverage leaves. A database-trigger regression rejects any
unrelated input write while an attachment is added or removed. All **93 local tests**, including
scale and published-clause groups, pass. Established SCC membership changes still require explicit
migration; this change does not silently rewrite cyclic histories.

Five restarted add/payment pairs at each size, with exact native receipt comparison, measured:

| Local operation | 10 Orders | 1,000 Orders |
| --- | ---: | ---: |
| Add Order, including initialization reaction | 34.24 ms | 385.52 ms |
| Pay new Order, including Agreement reaction | 40.82 ms | 329.19 ms |

The large add improved from 940 ms after the admission fix, and from 1,907 ms before it. Payment
remains graph-size dependent. Evidence: local `build/qualification/iteration12-growth/report.json`.

The AWS scheduler advances local completeness only when a ready candidate needs a newer bound.
It retains the same atomic append/seal boundary and equal-timestamp canonical ordering. The host
suite passes **796 tests**. In the real published-clause application probe, the final acknowledgement
fell from 70 commit calls / 15 retryable conflicts to 37 / 1. However, operation latency did not
improve consistently: this iteration's two-Order probe took 1.55–7.97 s, bootstrap 5.38 s. We retain
the reduction in unnecessary writes, without claiming a measured latency improvement.
Evidence: host `reports/iteration12-published-clauses.json`.

The preview deployment is healthy, artifact
`c5a246f14de1eb5dbb651acdfbe1c19cc7d51787ef0d722a2112fadeda521716`.
The corrected HTTPS qualification **passes**: login, anonymous rejection, CSRF rejection, account
isolation, bootstrap retry, operation retry, ten business operations, two reused timelines, emitted
events and API-key create/use/revoke. Two Orders each reach paid total 200; the Agreement reaches
400. End-to-end observed operation times are **2.76–8.99 s**, including remote HTTP/polling and
excluding catalogue lookup. This confirms functionality, not the millisecond target. Evidence:
`reports/preview-http-https-qa-af109c76f12f.json`. Credentials remain in a local ignored 0600 file.
Ratings: correctness **7**, compatibility **6**, clarity **7**, performance **3**, operations **5**.
Next: remove avoidable partition comparison work and test compact preparation separately from
lazy content fetching.

## Iteration 13 — linear partition comparison and canonical API documentation (keep)

On topology changes, partition observation compared every current group against a list of every
previous group. A set now answers membership while preserving canonical iteration order and all
gas charges. The full library suite passes **2,457 tests**. Five restarted growth/payment pairs per
size retain exact native receipts. Add medians are **37.86 / 362.21 ms** at 10 / 1,000 Orders;
Payment medians are **42.00 / 338.86 ms**. This is a modest add improvement; the Payment difference
is noise, not an improvement. Evidence: local `build/qualification/iteration13-growth/report.json`.

Current admission, receiving-state and native/timeline-channel guides now use the actual
`Contracts.Session` and `Contracts.Execution` APIs. Obsolete `DocumentRuntime` examples and false
multiple-model guidance were removed from current guides. Historical iteration reports remain
clearly historical rather than being rewritten to hide the earlier design.
Ratings: correctness **7**, compatibility **6**, clarity **7**, performance **3**, operations **5**.
Next: assess the compressed preparation experiment on cold AWS storage.

## Iteration 14 — compressed protected native preparation (rejected)

This candidate retained compact native preparation with bounded decompression while preserving
batched current-content reads. It did not introduce a public Blue type or alter canonical bytes.
The focused host gate passed, and all eight AWS payment receipts plus initialization matched the
independent native oracle. Nevertheless, cold median latency was **906 ms / 5,474 ms** at 10 /
1,000 Orders, versus **881 ms / 5,402 ms** for the simpler packed-content version. Large-case
acquisition remained 2.00–2.26 s. The extra storage machinery is therefore **reverted**.
Evidence: host `reports/iteration14-aws-cold.json`, `iteration14-aws-raw.json`; artifact
`87d74fd12738cb42406b76c3159334cbf68f7d3c00655bdcd3520a6d6e1758fb`, command
`089b1d5b-4767-4915-b24c-dabf619fabab`. The worker was terminated.
Ratings: correctness **7**, compatibility **6**, clarity **7**, performance **3**, operations **5**.
Next: reduce physical page-read count with byte-bounded denser immutable leaves, then retain only
if the cold AWS measurements justify it.

## Iteration 15 — denser byte-bounded immutable indices (keep)

Canonical snapshot metadata/content indices now permit up to 128 entries per leaf, subject to a
32 KiB target. This reduces cold network round trips; point mutation still rewrites a bounded leaf
and its hash path. Existing 16-entry trees and their pinned historical roots remain readable. The
new regression mixes leaf layouts, updates/deletes entries, reads old roots and checks byte bounds.
The integrated host gate passes **797 tests**. The separate original `blue-java` comparator again
passes **40 exact observation and receipt comparisons** at 1 / 10 / 20 Orders.

| Cold AWS operation | 10 Orders | 1,000 Orders |
| --- | ---: | ---: |
| Initialization | 1,020 ms | 36,381 ms |
| Median durable processing | 738 ms | 4,690 ms |
| Third operation batch-content calls | 45 | 264 |
| Third operation batch-content time | 168 ms | 1,283 ms |

At 1,000 Orders, the preceding accepted layout measured 5,402 ms and 450 batch-content calls.
All initialization and payment comparisons remain exact. Signed acquisition remains approximately
1.94–2.09 s in the large case and is separate from durable processing. Thus this is a physical I/O
improvement, not proof of flat graph-size latency. Evidence: host `reports/iteration15-aws-cold.json`,
`iteration15-aws-raw.json`; artifact
`237cd7a1a8da65b7bdc1491bba86816edbe704d1fd0fe4c910407c8542e97e10`, command
`b65d59a2-9e58-411a-85ee-e1555b9553f7`. The temporary worker was terminated.
Ratings: correctness **7**, compatibility **6**, clarity **7**, performance **3**, operations **5**.
Next: record the actual published clause workflow's exclusive restore, admission, native execution,
preparation and commit phases. Keep the canonical whole-root gas/failure boundary fixed.


## Iteration 16 — actual-clause phase recording and a stronger oracle (keep)

An optional Java Flight Recorder event now measures each owner attempt's restore, admission,
execution, preparation and commit phases. It is host telemetry: it does not enter a Blue document,
canonical gas or a receipt. The actual published Activation/Documents/Payment journey ran on a
separate AWS worker with two Orders. Ten application-API operations completed in **1.65–8.42 s**.
Warm content I/O was small while individual owner restoration took approximately **0.66–2.09 s**.
This points to CPU spent reconstructing and validating representations, not just DynamoDB latency.
Overlapping owner attempts cannot be summed as exclusive end-to-end wall time. Subsequent event
records include the original entry identity so unrelated overlapping attempts remain distinguishable.

The local real-clause oracle now compares each published receipt, gas and full observations against
uncached native whole-root execution, as well as final state and ordered events. This stronger check
found a previously hidden 25-gas mismatch on an acknowledgement. The old final-state/event-only
qualification did not establish gas parity for this case. That mismatch is addressed in iteration 17.
The AWS worker was terminated. Evidence: host `reports/iteration16-published-clauses.json` and
`reports/clauses-ee421ae93e94/result.json`. SSM truncated the duplicated final output; the individual
operation rows were retained. The next probe prints compact progress and the full result only once.
Ratings: correctness **7**, compatibility **6**, clarity **7**, performance **3**, operations **5**.
Next: fix the representation-dependent charge, then repeat exact real-clause validation.

## Iteration 17 — provider verification is not authored document construction (keep)

A missing type loaded through a provider could call the same observer used to charge native
construction. In the failing published-clause case a cold built-in Text definition was counted as
newly constructed business content. Warm/full-checkpoint execution did not count it. State and
events agreed, but gas differed by **25**. Replaying the same stored checkpoint with calculation
reuse disabled reproduced the mismatch, so calculation reuse itself was not the cause.

Language now retains provider-decoding evidence through its normal observer while a separate
construction observer excludes provider decoding. A scoped depth boundary is restored in `finally`,
including invalid-provider failures. Authored construction remains charged. The calculation artifact
edition advances to `blue-java-better/calculation-2`; old derived artifacts are not silently reused.
Historical committed receipts are not rewritten, and earlier affected histories are not requalified by
this change. Both regression cases and the full **2,459-test library suite** pass.

Fresh real-clause runs with 1, 4 and 8 workers produce identical complete histories. Each compares
four queued report/acknowledgement inputs against uncached whole-root execution: exact receipts,
gas, full observations, final BlueIds and ordered events all agree. Backlog times were **1,198 / 745 /
707 ms** for these bounded two-Order scenarios. Local protected owner restore medians are about
**2.2–2.5 ms**; native execution medians **77–94 ms**. These are local processing measurements,
not AWS HTTP latency. Evidence: local `build/qualification/iteration17-payments/report.json`.
Ratings: correctness **7**, compatibility **6**, clarity **7**, performance **3**, operations **5**.
Next: reuse installed immutable package definitions during AWS restoration, leaving account data
in durable storage and preserving portable complete exports.


## Iteration 18 — reuse verified installed package definitions (keep)

The AWS execution closure no longer reloads and reinstalls definitions already supplied by the
exact deployed public package catalogue. Account document content remains durable and is still
loaded/verified. Missing or undeployed definitions take the ordinary closure path. A regression
checks effective inherited values and proves complete portable exports are unchanged. The full host
suite passes **799 tests**, the local gate **93**, and the original Java comparison **40** exact cases.

The real two-Order AWS clause journey completes all ten operations. Individual restores now measure
**119–684 ms**, versus approximately **660–2,090 ms** before this change. Final acknowledgement
is **2,983 ms**, versus **6,602 ms** in iteration 16. First add remains **7,399 ms**; bootstrap **5,424
ms**. These are application API measurements without HTTP. Package reuse improves restoration but
does not establish millisecond end-to-end processing. Evidence: host
`reports/iteration18-published-clauses.json`, artifact
`2f902a3c2bb176f3c64e168e68b86a585013787f96debe95a0607f4b6a9ed98e`.
The isolated worker was terminated.

Post-fix restarted local growth qualification remains exact: add medians **36.04 / 362.01 ms**, pay
**41.71 / 347.45 ms** at 10 / 1,000 Orders. A 20-member settlement ring and eight independent
8-member rings feeding a ledger also pass exact receipts, gas, full observations and repeatability
across 1 / 4 / 8 worker schedules. The multi-ring run records eight overlapping native calls and
identical complete histories; the single ring correctly remains one atomic cyclic unit. Evidence:
local `build/qualification/iteration17-growth`, `iteration17-cycle`, `iteration17-rings`.
Ratings: correctness **7**, compatibility **6**, clarity **7**, performance **3**, operations **5**.
Next: test bounded protected preparations and add the same strict whole-root oracle to the AWS
published-clause journey. An application result alone does not establish exact native gas parity.


## Iteration 19 — small protected preparations; held pending a stronger oracle

The candidate retains a native, verified preparation for graphs of at most 64 members, only when
its encoded size is at most 96 KiB. The complete immutable checkpoint is staged in the same
publication before this hint is retained. Larger graphs use the ordinary batched restore path.
This is a private storage optimization, not an authored Blue contract or an alternative gas model.
The focused storage tests passed, but this was not enough to accept the candidate.

The AWS real-clause probe now replays every root history entry using the native whole-root
processor, with neither source-result reuse nor protected preparations nor calculation reuse.
It compares the exact receipt, scalar gas, full observations and final BlueId. The first Order
creation differed by **1,549 gas**, despite identical final documents and emitted events. The probe
correctly failed. Disabling preparation, calculation reuse and installed-package reuse individually
reproduced the discrepancy locally. Loading the complete historical catalogue merely moved the
failure to a later operation; it was not a valid repair. No unqualified candidate was deployed.

The isolated worker was terminated. Its command was
`8e014b9f-3a42-45d2-86ee-9aa1dd777888`, followed by read-only history audit
`f16091eb-df82-41d4-9567-db8a66bda5db`. The latter submitted no business operations.
The functional measurements from the failed qualification are diagnostic only, not an acceptance.

Decision: **HOLD** the bounded preparation candidate until exact parity and latency are rechecked.
Ratings: correctness 6/10, compatibility 6/10, clarity 7/10, performance 3/10, operations 5/10.
Next: fix the representation-dependent native accounting, retain the stricter oracle, then rerun.

## Iteration 20 — preserve nested document views during BEX argument matching

The first differing native charge was traced to a function argument's nested `parties` value.
The restored path supplied its collapsed reference while the whole-root replay supplied the inline
object, although both identify the same Blue value. BEX argument matching preserved an exact view
only when it was the entire argument. For an object or list containing such a view, `blueValue`
exported that view prematurely and discarded its captured identity evidence. The Language
construction boundary then charged for different physical input shapes.

BEX now validates/converts ordinary values while preserving nested exact views until the native
Language matcher receives them. Public Blue output still exports ordinary wire data. Null and
non-finite values remain rejected. No tariff was changed and no child gas was summed into a parent.
The private calculation-artifact edition advances to `calculation-3`, preventing reuse of artifacts
made under the old conversion. Existing published receipts are not rewritten.

The actual published clause acceptance journey passes in both host modes, with exact whole-root
receipt, gas, observations and state comparisons after Order creation and the full payment journey.
The two cases took 40.6 seconds total locally. These are test-suite times, not operation latency.
Focused regression coverage exercises document views nested in both objects and lists during
function argument matching. Full suites, original-Java comparison and fresh AWS qualification
are the next gates; this paragraph does not claim they have already passed.

Decision: **KEEP**, subject to the remaining gates. Ratings: correctness 7/10, compatibility 6/10,
clarity 7/10, performance 3/10, operations 5/10. The flat 10/1,000-order target remains unmet.


Iteration 20 gates passed: **2,461 library tests**, **800 host tests**, the local suite, and **40
exact original-blue-java comparisons** (initialization, add, submit and settle at 1/10/20 Orders).
The focused regression fixture was corrected twice: it originally passed an unnormalized object
to an internal view helper, then used BEX's reserved `value` parameter name. Those were test-fixture
errors; the production correction remained unchanged.

Fresh AWS published clauses also pass **10 exact whole-root comparisons**, without oracle source,
calculation or protected-preparation reuse. Bootstrap was **5,141.88 ms**. Operations were:
place Order 6,335.50 / 4,637.65 ms; prepare Payment 5,032.97 / 4,172.70 ms; accept instruction
1,336.67 / 1,081.36 ms; report transfer 1,646.15 / 1,767.46 ms; acknowledge 2,566.64 / 2,375.07 ms.
The Agreement ends at 400. Native owner restore spans were **19.79–128.67 ms**, versus
119–684 ms in iteration 18. The bounded preparation candidate from iteration 19 is therefore
**kept** for the published-clause path after the native correctness fix.

Evidence: `reports/iteration20-published-clauses.json`, command
`773ee974-1869-494a-a6ba-f754781a33ee`, artifact
`25a207402d18fd515d16ff3f64b0d7c0ba5ada878f3769d9cc2faaa02dbc92a9`.
Worker `i-06d688b440559f138` was terminated. These are application API timings without HTTPS,
not CPU times or proof that the end-to-end target is met.

The separate cold synthetic AWS run passed all eight receipts. Its unprofiled medians were
**947.53 / 5,544.48 ms** at 10 / 1,000 Orders; initialization **1,317.70 / 41,415.78 ms**.
Signed acquisition was measured separately: medians **180.56 / 2,211.32 ms**. The large-case
profile still spends substantial CPU in native restore, evidence import and semantic validation;
GC within measured operations was 484–745 ms. This run is slower than iteration 15, so the
real-clause restore gain must not be generalized to large synthetic graphs.
Evidence: `reports/iteration20-aws-cold.json`, command
`d2c0dc09-ed16-448e-89b2-8bb1f609dc6e`; worker `i-095b472722a62ca75` terminated.

The 10/1,000 local growth run passed every receipt. Its first small-case samples overlapped the
separate original-Java comparison on the same laptop, so its timings are diagnostic rather than
a clean comparative benchmark. A final isolated local measurement will replace them.


## Iteration 21 — bounded immutable batches of 100 pages (keep)

Immutable read-ahead now uses one shared bound of 100 pages rather than duplicated 32-page
limits. Pages are independently hash checked, tenant scoped, and at most 128 KiB; the raw payload
bound is 12.5 MiB before SDK overhead. Missing/corrupt pages fail, consistency remains strong,
and partial AWS responses retry only unprocessed keys with the existing bounded backoff.
The [AWS BatchGetItem contract](https://docs.aws.amazon.com/amazondynamodb/latest/APIReference/API_BatchGetItem.html)
permits up to 100 items/16 MiB, and may return partial results. The regression verifies a full
100-page response in reverse order as well as the existing partial/corrupt response cases.

The focused storage gates pass. Fresh cold AWS medians are **888.66 / 4,915.07 ms** at 10 /
1,000 Orders, compared with 947.53 / 5,544.48 ms in iteration 20. Initialization was
1,191.70 / 39,107.37 ms. All eight operation receipts match. At the third 1,000-Order sample,
content batch calls fall **263 → 125**, with batch-read time 1,437.36 → 1,200.02 ms.
Acquisition remains separate (medians 166.73 / 2,443.44 ms). The large graph is still much slower.

Evidence: `reports/iteration21-aws-cold.json`, `iteration21-owners-raw.json`, command
`a82de354-7ae8-424e-b0a5-179c06b815bb`, artifact
`d7c2027c38b3746d9555fd18c1167f9adafc1ecd82dae50d87a6d3f01d7cee6e`.
Worker `i-0f2f2e950488b839d` terminated. Decision: **KEEP**. Ratings: correctness 7/10,
compatibility 6/10, clarity 7/10, performance 3/10, operations 5/10.
Next: reduce redundant native cold validation while preserving evidence and detached values.


## Iteration 22 — deferred retained bodies with explicit value lifetime (keep for qualification)

A native preparation already establishes prior structural validation, but ordinary checkpoint
restore still decoded every supplied historical record. Protected restore now owns the supplied
record map and verifies bodies as demanded. It opens current member bodies to establish inline
fragments referenced by prepared contract recipes. It does not revalidate unrelated historical
content. Unprepared portable restore still validates the supplied envelope normally.

The initial candidate failed host tests: merely retaining records left prepared inline fragments
unavailable. Opening member bodies fixed that boundary, but detached `LanguageDocument` values
then exposed a second missing lifetime: they carried opened bodies and the external lookup, but
not the retained record trie. Detached values now capture that immutable trie as well. This is
constant-time structural sharing, not a global cache of mutable documents. A corrupted demanded
record still fails its exact identity check; caller mutation cannot alter a detached value.

Regressions cover these boundaries, unused historical data, runtime mismatch, wrong snapshot,
cycles and gas-limit rollback. The corrected path passes exact published-clause replay. No failed
candidate was promoted or deployed. Private retained preparations remain host authority, never
client-provided documents or portable proofs.

## Iteration 23 — immutable native preparation rows (measurement pending)

Large preparation artifacts are split using the existing immutable record/radix storage. Each
member's unchanged row and its index paths are reused; a tiny metadata pointer is committed with
the successful checkpoint. Historical roots remain readable. Existing small inline preparations
remain supported. This removes the 64-member/96-KiB restriction without introducing a new public
Blue type or an alternate Contracts processor.

The 1,000-row storage regression updates/removes two rows, verifies old and new histories, checks
bounded page size and fewer than 30 new pages, and rejects cross-account and unknown-format reads.
The integrated gates pass **2,463 library tests and 802 host tests**. One test fixture initially
attempted to extend a final store class; it was corrected to reuse the existing adapter. Two Gradle
invocations then collided on a shared library test-results directory (EOF/missing result file).
They were both allowed to finish, and a single isolated gate passed. That harness collision is
not reported as a product regression or a successful test run.

The AWS published-clause journey passes 10 exact whole-root comparisons and reaches 400. The
large cold measurement is still pending, so no large-graph speedup is claimed yet. Decision:
**HOLD** the combined storage optimization pending its measurement. Ratings remain 7/6/7/3/5.


Iteration 23 cold AWS measurement passed all eight exact receipts: medians **779.48 / 4,376.17 ms**
at 10 / 1,000 Orders, versus 888.66 / 4,915.07 ms in iteration 21. Initialization was
1,135.97 / 37,363.13 ms; separate acquisition medians 182.18 / 1,879.29 ms. This justifies keeping
the combined deferred-verification and paged-preparation change, while still falling far short
of flat latency. Real-clause operations also passed 10 exact comparisons (1.03–7.24 s; bootstrap
4.84 s); those noisy end-to-end values do not establish a uniform improvement over iteration 20.

Evidence: `reports/iteration23-aws-cold.json`, command `70eebcd0-f8ad-4f9f-b062-27c09f8023b5`,
artifact `df8c234e4f237ff942594935212a721e72dc55142fa9ec12cebd438f9b947ad6`;
published clauses command `305b752f-fbbf-4379-b733-e174286e780f`, artifact
`31e5d88db1949a2f4ef9ccafc7332882b950e9b3ca2767c7b01310079d12ea9a`.
The dedicated workers were terminated. Decisions for 22 and 23: **KEEP**. Ratings remain
correctness 7/10, compatibility 6/10, clarity 7/10, performance 3/10, operations 5/10.


## Iteration 24 — selective remote body loading (reject the measured candidate)

The host retained the exact checkpoint envelope and batched member headers, leaving other bodies
behind the protected content provider. Exact receipts passed, but the combination with iteration
22's eager member opening triggered too many remote reads. Cold AWS medians worsened to
**952.39 / 10,121.41 ms** at 10 / 1,000 Orders. Initialization was 1,147.18 / 39,609.98 ms.
The two-Order real-clause flow still passed ten exact comparisons and reached 400; that does not
justify accepting the synthetic large-graph regression. This candidate was not deployed.

Evidence: `reports/iteration24-aws-cold.json`, command `55cd66b5-f40a-4a87-b5c6-132a1cf797f4`,
artifact `79e36a792f86d5a9aa84c5baa500434f3bb804b36f476e9b85912998683a4a60`.
Decision: **REJECT as measured**. Ratings remain correctness 7/10, compatibility 6/10,
clarity 7/10, performance 3/10, operations 5/10. Next: remove the native eager-opening requirement
and requalify the combination; restore iteration 23's host loader if it remains slower.

## Iteration 25 — restore preparation without forcing every member body (qualification)

The local restart test exposed the same unnecessary materialization in another form: forced
opening bypassed the bounded verified-response cache, raising large-case restoration from roughly
55 ms to 190 ms. The first correction still forced opening when the envelope contained no bodies;
that candidate remained slower and was rejected before promotion.

The corrected implementation supplies owned retained records, reuses verified evidence only for
member records actually present in the envelope, and leaves absent records behind the exact
content provider. Detached language values keep the immutable record trie, so they remain valid
after the execution scope closes. Unprepared portable restore retains ordinary verification.

The complete isolated gate passes 2,463 library and 802 host tests. The local restarted 10/1,000
Order run passes every add/payment receipt; all five samples are retained, including the first.

- 10 Orders: initialize 287.87 ms; median add 42.14 ms; median payment chain 45.36 ms.
- 1000 Orders: initialize 17388.92 ms; median add 385.77 ms; median payment chain 348.20 ms.

This removes the local regression, not graph-size growth. AWS qualification is pending.
Ratings remain 7/6/7/3/5. Next: measure the combined host/native loader and screen the measured
JSON field-name interning hotspot without changing any Blue semantics.


Iteration 25 AWS result: all eight receipts match, but medians **870.57 / 9,529.00 ms** still
reject the selective remote loader. The two-Order published-clause journey passes all ten exact
comparisons (bootstrap 4.82 s; operations 1.00–6.28 s). Keep the corrected native preparation path
for its local behavior; **revert the host loader to iteration 23's batched current closure**.
Evidence: `reports/iteration25-aws-cold.json`, command `11dd1b3f-dee6-4973-8a1f-e5a76fac9c9b`,
artifact `3d771bb8c0817a5040f3d4e9e8d67d77d91c62c75ac7403eac1d7113b338708c`;
published clauses command `cbeef4b0-cc70-4101-9189-9783c14421dd`, artifact
`8eba79ea6a1bd22d996a99e9ab216292bf20d2ed9dc940016f6a506232deb9e1`.
Both dedicated workers were terminated. No selective remote loader was promoted or deployed.


## Iteration 26 — bounded parser symbol tables and the measured batched loader (keep)

JSON parsers still canonicalize field names within their own bounded symbol tables, but no longer
intern arbitrary document and content-identity keys into the JVM-wide string pool. The AWS profile
had sampled `String.intern`; this change removes a shared global cost without changing values,
validation, canonical output or gas. It applies to both library codecs and host JSON readers.
The local timing difference is within run-to-run noise; no local speedup is claimed.

Together with the restored batched host loader and corrected preparation path, the isolated full
library/host gate passes. Cold AWS medians are **772.35 / 4,049.74 ms** at 10 / 1,000 Orders;
separate signed acquisition medians are 147.55 / 1,815.34 ms. All eight exact receipts match.
This is a combined measurement, not an isolated attribution of all savings to field-name parsing.
The previous accepted combined run was 779.48 / 4,376.17 ms. No resident mutable graph is retained.

Evidence: `reports/iteration26-aws-cold.json`, command `c099e5bc-5681-456d-821b-01c975b5e354`,
artifact `dfd8a0b3705f42e9b179579943ddd07c5b38e5d506f96cc43c0d76fdd90b579f`;
worker `i-023d009b05b87f089` terminated. Local growth again passes every receipt; medians are
47.70 / 384.68 ms add and 48.75 / 346.33 ms payment chain. Decision: **KEEP**, with performance
still 3/10. Other ratings remain correctness 7, compatibility 6, clarity 7, operations 5.
Next: final integrated qualification and deploy the accepted implementation to the isolated preview.

## Iteration 27 — deferred irrelevant source cursors (reject)

A targeted append currently advances every subscribed owner's cursor, even where it cannot invoke
business behavior. A candidate deferred those irrelevant cursor writes until the next relevant
input. Its fixed-subscription regression covered restart, observers, unknown classification and
catch-up. Existing access-history tests exposed the change to cursor advancement; preserving
coverage-only advancement fixed one case, but broader analysis found the real problem.

A later handler or subscription change can make a previously excluded input relevant. A lagging
cursor could then replay old inputs under the new policy. Safe deferral needs a versioned
subscription/exclusion boundary tied to the owner's canonical history; simply skipping cursor
writes does not establish it. The candidate and its candidate-only test were removed. Existing
ordering/access tests remain unchanged. **No candidate was deployed or promoted.**

Decision: **REJECT** rather than weaken correctness for a lower benchmark. Ratings remain
7/6/7/3/5. The next scheduler optimization should retain a compact exclusion frontier or indexed
subscription history, with explicit handler-install/remove tests, before changing this behavior.


## Iteration 28 — remove metadata field-name canonicalization (reject)

After global interning was removed, the profile sampled Jackson's byte-field symbol-table copying
19 times in 213 CPU samples. Disabling metadata field-name canonicalization was tested separately.
All 802 host tests and the AWS real-clause replay passed, but cold medians were **896.65 /
4,381.19 ms**, worse than the selected 772.35 / 4,049.74 ms. Initialization was 1,258.83 /
40,769.28 ms. The changed parser path did not establish a benefit and was reverted.

The real-clause journey reached 400 and passed ten exact comparisons; bootstrap was 4.83 s and
operations 1.07–6.17 s. Correctness alone is not evidence to retain a performance change.
Evidence: `reports/iteration28-aws-cold.json`, command `66637489-5adb-477d-85b0-7e58da652c7b`,
artifact `3a1604b203224bfb9aa2099bea7f540ccc06b54dafb12a1b52f7d3db17f975ee`;
clauses command `3b6d1bbc-a400-4946-a543-266c972d61a3`, artifact
`aa32c22f6223d0c9935edf77e029f1e5af5c48d67331b55c6f99004f389abd41`.
Both temporary workers were terminated. Decision: **REJECT**, retain iteration 26 configuration.
Ratings remain correctness 7/10, compatibility 6/10, clarity 7/10, performance 3/10, operations 5/10.

The unused member-header batching overload and its experiment-only test from iteration 24 were
also removed; ordinary pinned-content reads remain. Current test count is therefore one lower
than the temporary 802-test host suite, not a silently dropped conformance requirement. The local
final gate passes 71 ordinary, 15 published-clause and 7 scale tests. The library gate passes
2,463 tests. The original-Java comparison again passes 40 exact observation/receipt comparisons.
Deployment-helper tests pass 42 cases. Python syntax was checked without writing bytecode after
macOS denied the default bytecode-cache path; this was a tooling permission issue, not an app failure.
Next: deploy the selected build, exercise HTTPS authentication/API/clauses and external imports.


## Iteration 29 — two bounded concurrent immutable read batches (reject)

Two independent batches of at most 100 DynamoDB content pages were fetched concurrently, with
caller-owned pending content excluded, all identities verified before caching, and interruption
propagated. Three focused regressions and the owner/clauses suite passed. Cold AWS medians were
776.17 / 3,994.19 ms (10 / 1,000 Orders), versus the selected 772.35 / 4,049.74 ms. Initialization
was 1,072.52 / 36,186.22 ms. All eight exact comparisons passed.

The large-case change is about 1.4%, with a slightly slower small case; this single run does not
establish a repeatable benefit worth the extra concurrent I/O and cancellation machinery. Both
implementation files were restored and the experiment-only tests removed. **REJECT**.
Evidence: command `6f47b8fc-cc9a-4931-9219-12dda6ef597e`, artifact
`cbb9dd1d231ae6a448437db9b0da8bc3785097ac8ae124da13ae33e4ea4be214`,
`reports/iteration29-aws-cold.json`; temporary worker `i-0051dc3387aa5e45b` terminated.
Ratings remain 7/6/7/3/5. Next: repair a real external-completeness issue discovered through HTTPS.

## Iteration 30 — remote polling must advance quiet completeness (qualification)

The selected implementation was deployed to the isolated canonical preview, with digest
`223473c5f54ff80e266a36b39c7616e6e9c646df4a353e7c2a176c2b97da8112`
and successful health-checked command `88b60b78-6be4-4bb1-baa2-2edf258d60c7`.
Authenticated HTTPS qualification passed login, authorization/CSRF isolation, bootstrap retry,
two shared participant Timelines, Payment→Order→Agreement propagation, immutable history and
API-key creation/use/revocation. The two-Order journey reached 400. Bootstrap was 6.16 s and
observed operations 1.41–6.31 s. An external import ran concurrently on that QA account, so this
is functional evidence, not a clean comparative latency experiment.

The live external provider was healthy and its existing public timeline imported correctly.
A stronger check then waited 90 seconds for a strictly newer completeness boundary after refresh
and failed. Automatic polling was requesting `minimumCompleteBefore` equal to its existing
boundary. The published provider contract permits reusing that checkpoint forever. This did not
make an invalid prefix valid, but could keep a healthy quiet external timeline artificially stale.

Remote polling now asks for the provider's fresh available frontier without a minimum. Internal
co-located sources retain demand-driven reads and the idle-head fast path. A future outstanding
demand remains pending when the provider cannot yet satisfy it. No host clock is substituted for
signed external evidence. The acquisition method remains available for explicit demand fulfillment;
`refresh` selects the ordinary no-minimum provider request. Local authority selection requires
matching provider and Coordination identities, not merely a transport wrapper's Java class.

Focused tests prove quiet advancement, unchanged entry head, an unsatisfied future demand, private
access verification, and actual directory polling without the stale minimum. Full suite and live
recheck pending. Ratings remain 7/6/7/3/5 until qualification completes.


Iteration 30 qualified: the full host build passes **802 tests** with no failures/errors/skips.
Deployment command `e45017ff-78df-49fb-8822-7caad5aeb2a3` succeeded, artifact
`cb792d9f552965a06ba1c64c88c99735d90fab3ba4c08aabfc2924eea4b4a891`.
The live external check now advances completeness from 1794170064460000 to
1794170065572000 without changing its entry head; imported writes still return 403.
A fresh mixed-source document replays the two existing external amount=7 inputs to 14, then
commits its submitted local increment to 19 and returns to IDLE. Bootstrap plus historical replay
was 2.59 s; local submission through observed commit was 1.42 s, with 121.60 ms native execution
and a separate 482.47 ms operation/head lookup. These are distinct measurement boundaries.

Evidence: `reports/external-qa-9cb53bfc65c2.json`, `reports/mixed-qa-1bc73cb48450.json`.
The external provider was not changed. The report deliberately fails if its expected example
entries change, rather than silently accepting another business result. Decision: **KEEP**.
Ratings remain 7/6/7/3/5. The current behavior is better qualified, but neither full compatibility
nor the latency target is established.

## Iteration 31 — indexed immutable-record presence checks (qualification)

A fresh restarted local run still shows size growth: unprofiled add medians 37.36 / 369.65 ms,
payment medians 43.39 / 360.30 ms at 10 / 1,000 Orders. All 20 exact receipts pass. The last add
sample at each size used JFR and is excluded from these add medians. Future qualification output
now explicitly labels that sample `profiled` so consumers cannot accidentally pool it.

The 1,000-Order profile has 184 samples, including whole-graph validation, topology, canonical
encoding and immutable-map access. Inspection found `ContractData.FrozenMap` and the preencoded
record wrapper inherited `AbstractMap.containsKey`, which scans entries. Both now delegate to
their underlying immutable map. Null remains distinct from absence; iteration/canonical order,
immutability and detached ownership remain unchanged. A focused JMH benchmark covers 10/1,000/
10,000-field rows and both representations. The complete library and host gates and timings are
pending; this is a local data-structure improvement, not a claim of constant graph processing.


Iteration 31 library gate: **2,466 tests**, zero failures/errors/skips. The same 20,000-query
probe (half present-null, half absent) measured large frozen-row medians 76.90 → 0.31 ms at
1,000 fields and 967.67 → 0.15 ms at 10,000; encoded rows 71.36 → 0.62 and 739.06 → 0.15 ms.
The short probe is diagnostic, not a deployment-latency forecast. A separate bounded JMH run
(one fork, two 200 ms warmups, three 200 ms measurements) reports present-null lookups at
3.49 / 3.75 ns for frozen 1,000 / 10,000-field rows and 4.86 / 5.36 ns for encoded rows.
Small rows deliberately retain their compact linear scalar representation. Decision: **KEEP**
the straightforward delegation; no graph-wide latency claim. Ratings remain 7/6/7/3/5.

The user-facing HTTPS reproduction client also passes on the iteration 30 preview. It creates
its own Agreement, two Orders and two Payments, uses the same two participant Timelines at every
level, checks both Payment/Order totals and Agreement total 400, and verifies unrelated child
identities remain unchanged. Its bootstrap is 3.33 s; submitted-entry-through-committed-receipt
observations are 2.05–3.92 s, with native execution 306–912 ms and separate lookup 500–530 ms.
Evidence: `reports/iteration30-user-reproduction.json`. This is the exact documented client,
not merely the lower-level Java qualification harness.


Iteration 31 first AWS run: all eight exact receipts match, but cold medians **1,023.74 /
4,930.84 ms** and initialization 1,323.12 / 43,202.98 ms are slower than iteration 26. The
microbenchmark cannot be substituted for this result. An identical-build repeat is being run
before attributing the difference. Command `74563473-13af-492c-aa0c-ea7ea65cb759`, artifact
`c5cdead5d989f4ad69d55fef76bec4a9146fb94a766d8b1d344b7f78a9a06040`, worker
`i-07b00cfae4b935415` terminated. Report: `reports/iteration31-aws-cold.json`.

## Iteration 32 — build ownership publication indexes once (qualification)

Publication preparation previously filtered the entire member→unit map for each owner, then
rescanned every embedding for each unit. This is quadratic during large graph initialization.
A private invocation-local `OwnerPartition` now indexes unit members and outgoing inter-unit
occurrences once, preserving aliases, member order, edge order and occurrence generations.
It stores no document body and introduces no authored Blue type. Internal cycle edges are not
mistaken for separately scheduled child dependencies; shared external occurrences remain distinct.

`Contracts.Execution.State.components()` also reuses the existing immutable physical topology
cache instead of recomputing SCCs every time the host inspects the same frozen state. Structural
changes still invalidate that cache. The public result is deeply immutable.

Focused host tests pass, including published clauses. The new native cycle fixture was initially
invalid (indistinguishable preliminary members, then the wrong Process Embedded contract key);
those fixture errors were corrected to distinct content and the published `embedded` key before
qualification. Native semantic oracles were not changed. Full gates and timing are pending.
Ratings remain 7/6/7/3/5; next: verify end-to-end behavior and measured initialization/publication.


Iteration 31 identical-build AWS repeat: **760.45 / 4,238.64 ms**, initialization
1,118.85 / 36,399.80 ms, all eight exact receipts matching. Command
`41fa4b65-bcbd-4341-8013-586812f8926c`, artifact
`2ee7981f48e75497a3a691a37d7c57d41b464afe331f07849e4f85eb783aee1b`, worker
`i-0def29a6cb586f8ea` terminated. Both runs are retained; this variability prevents attributing
an end-to-end gain to the map change. The simpler indexed lookup is retained for its established
local complexity improvement. Ratings remain 7/6/7/3/5.

Iteration 32 complete native gate passes **2,467 tests** and host gate **804 tests**, with no
failures/errors/skips. AWS 10/1,000 cold and a larger 20-Order actual-clause journey are running
on separate isolated workers; neither measures against the live console process.

## Iteration 33 — index adopted timelines before channel matching (qualification)

Both whole-graph and independently scheduled ingress scanned every available timeline for every
native receiving channel. With distinct sources, this is O(channels × sources) before native work.
An invocation-local `AdoptedTimelineIndex` enumerates account-owned registrations once, indexing
exact Timeline identity and public (provider identity, URL) bindings. Each channel then performs
one lookup. Duplicate registrations remain a list and still trigger the original ambiguity error;
private timelines never satisfy a Public Timeline Channel; cross-account registrations are excluded.

This is selection only, not permission or evidence caching. Existing historical access checks,
source cuts, signature verification and native receiving-lifetime admission remain unchanged.
Tests cover exact/public/private binding, provider and URL distinctions, absent headers, ambiguity,
immutable matches and source enumeration independent of repeated lookups. Integration qualification
includes private admission, graph ingress and the published clause journey. Full gate pending.
Ratings remain 7/6/7/3/5; next: final integrated build, deployment and retained evidence.


Iteration 32 cold AWS result: all eight exact receipts pass; medians **863.63 / 4,733.85 ms**,
initialization 1,371.16 / 46,020.81 ms. Command `a9c3f275-f5d4-40eb-a02b-88f4537653b2`, artifact
`0f1ddc9f7f1918423853cff3e1622cbea027a8407e452a2b925c5d89dd81201f`, worker
`i-0d52e27930e872c53` terminated. No overall latency improvement is claimed. The change removes
explicit repeated publication scans and preserves semantic behavior; it is retained on that narrower
basis, with another final integrated measurement pending. Independent workers shared the same AWS
account/table service with the separate clause campaign; these are not paired same-host experiments.

The final local run passes 71 ordinary, 15 clause and 7 scale tests and every exact growth receipt.
At 10 / 1,000 Orders, median add is **40.00 / 412.98 ms**, median Payment chain **59.24 / 381.20 ms**,
initialization **275.71 / 17,356.14 ms**. Earlier selected runs were lower; keep all measurements.
This remains a clear failure of the flat-latency target, not a completed locality optimization.

Iteration 33 full host gate passes **807 tests**. The source-enumeration regression performs 2,000
lookups after building an index from 1,000 registrations and confirms the source collection is still
visited exactly 1,000 times, not scanned for each lookup. Private/public matching and ambiguity
semantics remain integration-tested. Decision: **KEEP**. Ratings remain 7/6/7/3/5.

## Iteration 34 — accurate timing boundaries and complete qualification output (keep)

Inspection found that legacy `executionNanos` is a host aggregate: restoration, admission and native
invocation, excluding result preparation/commit. It is not pure engine CPU. The API field remains
compatible, but the reproduction report now names it `restoreAdmissionExecutionMillis`. The scale
probe uses `rootRestoreAdmissionExecutionMillis`; its old `nativeInitializationMillis` becomes
`initialPrepareAdmissionMillis` because that interval also includes declaration discovery and
source catch-up. Earlier raw reports are preserved with this correction, not silently rewritten.
Exclusive JFR `executionMillis` remains the native invocation phase.

Large qualification output exceeds SSM's inline response. Future owner/clause commands retain stdout
under the existing authorized S3 report prefix, then collect the full bounded file before terminating
the worker. Missing, ambiguous, truncated listings or oversized reports fail collection; an incomplete
report cannot silently qualify a successful command. Four new helper tests bring the deployment/helper
suite to **46 passing tests**. No credentials are added to report output.

The already-running 20-Order job has a separate read-only watcher that uploads its complete stdout
only after the final result appears. This avoids adding repeated network work during measured calls.
The source-index runtime is being deployed to the isolated preview; the final cold measurement uses
the full-output collector. Ratings remain 7/6/7/3/5. Next: verify deployment, retain final evidence,
promote the validated changes back to the three working repositories, and report remaining work.


The 20-Order AWS run completed successfully and its complete 200,737-byte JSON report was retained.
It reaches **4,000** and passes **100 exact whole-root comparisons** through version 101, without
source-result, preparation or calculation-cache reuse in the oracle. Command
`8156cd89-d580-4535-b0ef-3b6075ddcde6`, artifact
`cca72b17e8a4eff5811f2ecfdfcc1fbe529303fc9dc831d83a875f94a9e42b41`, worker
`i-03946e49f9f5b91b9` terminated. Full report: `reports/iteration32-clauses20-full.json`.
SSM's 24,000-byte inline field truncated the final JSON exactly as anticipated; the independent
post-result S3 retention succeeded and supplies the complete evidence.

| Operation, 20 samples | Median observed condition | First | Twentieth |
| --- | ---: | ---: | ---: |
| Place Order | 4,673.88 ms | 6,467.57 ms | 5,862.63 ms |
| Prepare Payment | 4,165.65 ms | 5,399.11 ms | 5,624.22 ms |
| Accept Payment Instruction | 1,117.26 ms | 1,291.17 ms | 1,171.99 ms |
| Report Transfer | 2,108.50 ms | 2,067.82 ms | 2,171.86 ms |
| Acknowledge Transfer | 3,456.75 ms | 2,520.95 ms | 4,710.49 ms |

Bootstrap is 5,178.30 ms; catalogue preparation is a separate 15,017.73 ms. These are console
application API measurements on the dedicated worker, without HTTPS. The qualifier's old
`parentObservedMillis` label is broader than its actual condition: some operations waited only
for a child state, and only acknowledgement necessarily waited for the Agreement total. The next
iteration makes every operation wait for its own committed Agreement input; do not compare these
medians with that stricter boundary as if the measurement were unchanged.

## Iteration 35 — distinguish child visibility from exact parent completion (qualification)

The 20-Order JFR trace contains parent attempts from previous submitted inputs finishing inside
a later operation's interval. This is expected when an earlier check only waits for a child value;
it is not evidence that one input was duplicated. All 100 committed root inputs still match the
whole-root oracle. Summing those overlapping attempt durations would misstate wall time.

The real-clause probe now records the submitted entry ID and its Agreement history version,
waits for that exact root outcome to be APPLIED, and separately records `stateObservedMillis`.
`parentObservedMillis` now ends only after the same input is committed at the Agreement and its
expected state is visible. Per-attempt records retain entry IDs for attribution. This aligns with
the documented user reproduction client's existing exact-history wait. A bounded 10-Order run
checks 50 complete operation chains and native comparisons using the full-output collector.
No production execution semantics changed. Ratings remain 7/6/7/3/5; next: final evidence/report.

The final preview runtime deployment succeeded: command
`3ca76de3-af14-43b7-b014-f61d9c8b256d`, artifact
`c319d0234de7be067980daf6d78901c761683275a0002f4adfc78267a9c33cdc`.
HTTPS authentication, isolation, bootstrap idempotency, operation retry, history, API keys and the
two-Order 400-total journey pass again (`preview-http-https-qa-27ae8e168684.json`). The original
Java comparison also passes all 40 exact observations/receipts on the final library code.
The validated source changes were hash-checked and promoted to all three requested repositories;
subsequent qualification/documentation changes will be promoted after their final review.


Final iteration 35 results: all **50** exact root comparisons pass; total **2,000**. Command
`e1b63eb3-bf7c-4768-8071-3c017fe88078`, qualification artifact
`dc6d0b65f20da1d1a347417c79efd7122c41c58ee780da4ef2aae5e03ea909e8`, worker
`i-0cddebb56e844baef` terminated. The full-output collector succeeds. Every timed attempt in this
run carries the same original entry as its operation, avoiding the earlier cross-operation overlap.
Bootstrap is 5,017.11 ms; package catalogue construction is separately 15,749.84 ms.

| Operation | Median exact Agreement completion | Median business-state visibility |
| --- | ---: | ---: |
| Place Order | 4,105.90 ms | 4,105.90 ms |
| Prepare Payment | 4,208.13 ms | 4,201.94 ms |
| Accept Payment Instruction | 2,230.89 ms | 1,070.37 ms |
| Report Transfer | 2,582.80 ms | 1,379.07 ms |
| Acknowledge Transfer | 2,374.16 ms | 2,368.69 ms |

The final cold owner run also passes eight exact receipts. Its final unprofiled medians are
884.68 / 4,512.70 ms at 10 / 1,000 Orders. The last sample at each size is profiled and retained
separately, not mixed into these medians. Command `b696f3c2-5943-4f45-bd17-dc839a55d59c`,
worker `i-0d348bc1ebde2e399` terminated. Retained evidence includes initialization, all individual
samples, storage-call counters and original command/artifact metadata. No end-to-end gain is
claimed for iterations 31–33 from these noisy runs; their bounded lookup/enumeration improvements
are established separately.

## Iteration 36 — current guides, repeated locality check and retained audit evidence (keep)

A final API audit found no Local-1/Blue/Coordinator/DocumentRuntime/ContractOwners implementation
in production Java, but four current guides still contained retired snippets behind migration
banners. The Language, package, native-step and timeline-verification guides now teach the actual
`Language`, `Contracts.Session` and `Contracts.Execution` APIs. The timeline recovery section now
documents the implemented protected-checkpoint boundary instead of saying it is unimplemented.
Historical reports remain explicitly marked as history. No runtime behavior changes in this iteration.

A second final local growth run reopens storage for every operation, executes five additions and
five Payment chains at each size, and compares twenty exact root receipts. All pass. Medians are
35.58 / 359.57 ms for addition and 40.97 / 351.16 ms for Payment at 10 / 1,000 Orders. Both final
runs show substantial growth; the difference is not explained away as measurement noise.

The final HTTPS reproduction uses the same account API as the console, reaches 400 and waits for
each of ten exact Agreement receipts. External refresh again advances verified completeness with
an unchanged head and rejects imported-timeline appends. Selected test summaries, raw scenario
results and command identities are retained in a compact hash-manifested directory; huge traces,
binaries and private credentials stay out of it.

Ratings: correctness **7**, compatibility **6**, clarity **7**, performance **3**, operations **5**.
Decision: **KEEP** documentation and measurement corrections. The next implementation target is
native/host incremental rooted state and declaration propagation with an exact semantic reuse
boundary. Increasing worker count or relabelling child-visible times is not a substitute for that work.

## Response to the supplied review

| Review finding | What this campaign did | Remaining limit |
| --- | --- | --- |
| Multiple incompatible public execution models | Removed alternative interpreters; consolidated current guides | Historical reports are retained as explicitly historical evidence |
| Local/AWS rejection differs | Same consume-on-deterministic-failure, wait-on-resource policy; rollback regressions | Wider host fault and retry campaigns remain useful |
| Fast receiving changes gas/rollback | Retained rooted original-input semantics and exact comparisons | Complete child-turn reuse preserving those semantics remains unfinished |
| Loader reconstructs broad current state | Bounded pages, batching, retained package/preparation facts, provider-lifetime fix | Current-root materialization still grows |
| Broad source/component scans | Invocation-local adopted-source and partition indices | Publication, declaration and rooted validation still visit broad state |
| Misleading timings / small synthetic workload | Real 20-Order and strict 10-Order clauses; exact history wait; timing-label correction | Millisecond hosted target is unmet |
| One account-wide publication boundary | Preserved atomic safety and qualified replay | Hot-account contention and production HA are unqualified |
| Cyclic representation amplification | Reduced copies and retained ordered proof inventories; exact ring tests | Cyclic business work and complete evidence can inherently scale with the cycle |


## Final physical-work diagnosis

A separate instrumented run from the promoted local checkout measures three Payment operations
at each size, reopening the runtime every time. All six exact uncached root comparisons pass.
These timings are diagnostic and are not substituted for the uninstrumented medians above.
The following counters are identical across all three samples except the explicitly ranged bridge
counter. Counts include the three owner attempts for one original input.

| Physical work per input | 10 Orders | 1,000 Orders |
| --- | ---: | ---: |
| BEX calculations executed / reused | 6 / 6 | 6 / 6 |
| Native member records restored | 46 | 4,006 |
| Metadata records built | 52 | 4,012 |
| Graph preflight edge visits | 78 | 7,008 |
| Idle-unit proof records built | 24 | 2,004 |
| Proof-vector edge visits | 82 | 7,012 |
| Native scheduler ready selections | 48 | 4,008 |
| Native topology edge visits | 41 | 4,001 |
| Instrumented bridge body opens, successive samples | 804 / 858 / 861 | 804 / 858 / 861 |

The bridge body-open counter does **not** count every Language/schema read. Its flat value must
not be used to claim complete physical locality. The broad native scheduler/proof/metadata terms
are still present despite only three durable jobs and unchanged business-calculation counts.
Measured exclusive digest work is about 2.1–2.8 ms versus 28.9–33.2 ms. Digesting alone therefore
does not explain the complete 1,000-Order latency; restoration, native traversal and publication
also contribute. Retained raw counters: `campaign-2026-11-08/local-physical-work.json`.

## Iteration 37 — exact-version signing custody visibility (keep)

The final mixed-source rerun hit HTTP 503 during bootstrap. A bounded server log records an AWS
Secrets Manager `ResourceNotFoundException`. Source inspection found that `ReaderCustody` handles
newly acknowledged version visibility, but its later generic `SigningSecret` read used a fresh
SDK client and a single read. This creates an inconsistent recovery boundary. The evidence supports
a visibility race as a plausible cause, rather than proving which Secrets Manager replica answered.

Both paths now share a bounded lookup for the **same exact ARN/name and version**. Only not-found
visibility is retried, with the existing six delays (25–800 ms). No replacement key is generated,
no latest-version alias is used, authorization errors are not retried and returned identity must
still match. Missing material remains a failure after the bound. Five new tests cover these
boundaries; the focused custody suites pass. The full host gate passes **812 tests** with no failures, errors or skips. Live mixed-source replay passes on the deployed fix.

The mixed-source qualification client now records its bootstrap command before submitting and
accepts `--command` to resume the exact request after an uncertain response. It cannot silently
substitute a fresh command and call that an idempotency check. Earlier failed qualification is
retained separately from the eventual successful rerun, if any.

Final iteration ratings remain correctness 7 / compatibility 6 / clarity 7 / performance 3 /
operations 5. This change addresses bootstrap reliability, not graph-size-dependent latency.

### Specification boundary behind the remaining proof cost

The shared Contracts specification, Appendix A00, explicitly defines private processor SHA-256
commitments and states that they are not BlueIds. Its snapshot commitment covers the sorted member
metadata and edge records. Private durable owner/index records are therefore infrastructure, not
additional authored `Core/*` or `ContractOwners` document types. This campaign did not add such types.

The retained implementation still hashes broad canonical snapshot/result encodings. Reusing an
unchanged record's encoded bytes saves allocations but does not eliminate reading those bytes when
hashing a changed enclosing stream. Exporting an entire proof also necessarily costs at least its
output size. These costs must be kept separate from unnecessary revalidation and repeated scheduling.

A future explicitly versioned Merkle-structured commitment could make a bounded change update a
bounded path while retaining full proof export on demand. That would change private commitment and
receipt identities and needs a versioned standard decision and compatibility tests; it must not be
silently substituted under the current edition. The present campaign preserves exact receipts and
does not claim it has solved that commitment-format problem. Most measured cost still exceeds the
exclusive digest time, so reducing native/host traversal remains useful without a specification change.

The custody-preserving deployment succeeded, command `011844bb-832d-4cc1-bbde-7320bfcf5826`, artifact
`66042c7b4db5c7903369373d0cfcf5a8719b9b4a07153fbc8a80551ee3e2b40d`. An earlier deployment submission omitted the already configured callback custody;
the preflight rejected it before changing the running service. The corrected deployment preserves
provider, reader and callback custody. The first mixed rerun succeeded on the unchanged prior build,
which is retained as evidence that the failure was intermittent; it is not credited to the fix.
The bounded retry is independently established by the new exact-version tests.

Scale/native latency measurements above were taken before this custody-only correction. The signing
change does not alter document semantics or native processing, but no new full scale benchmark is
claimed for the final deployment artifact.

After the successful deployment, a fresh mixed document replays both external entries to 14 and
commits the exact local increment to reach 19 with an idle backlog. Retained report:
`campaign-2026-11-08/aws-mixed-final-runtime.json`. Bootstrap through historical replay is
2,843.46 ms; the local exact-parent observation is 1,434.71 ms plus a separate 451.26 ms catalogue
lookup. This is a functional recovery check, not an improvement claim from one timing sample.
Decision: **KEEP** the custody correction. Source and deployment retain the same canonical
Contracts semantics and the current signing versions.

The last authenticated two-Order reproduction on the deployed custody-corrected artifact also
passes all ten exact parent-history waits, both nested Payment workflows, shared participant
Timeline assertions and the unchanged first-Order/Payment identity checks. Final Agreement total:
**400**. The raw result is `campaign-2026-11-08/aws-final-custody-user-reproduction.json`.

The optional mixed-qualification `--command` resume is restricted to an uncertain bootstrap.
If its saved report already contains a document, the CLI refuses before contacting AWS, preventing
a second business operation from being mistaken for a bootstrap retry. The final evidence manifest
verifies all retained report bytes; the source promotion baseline verifies all 1,378 project files
present at that audit without changing the original Java or Python projects.

## Final cyclic qualification and service state

The final promoted library was checked again with **eight eight-member cycles**, feeding one ledger.
Forty-eight timeline entries were preloaded before execution. Each of three runs completed 96 atomic
owner invocations. The 1-worker run compares all 48 root receipts with an uncached whole-root oracle;
the 4- and 8-worker runs produce identical complete-history, all-head, ordered-emission and root
identity digests. Observed native concurrency is respectively 1, 4 and 8. This demonstrates
independent cyclic groups running concurrently while each group retains its atomic semantics.

Backlog wall times are 5,784 / 3,221 / 3,319 ms for 1 / 4 / 8 workers, excluding the independent
oracle. More workers did not improve this small fixture beyond four. These are local backlog
measurements, not per-operation AWS latency or a claim that arbitrary cycles have constant cost.
The final report includes the library artifact hash and all source/completeness records:
`campaign-2026-11-08/local-final-rings64.json`.

The closing AWS check confirms the preview instance is running with passed instance/system checks,
the console returns HTTP 200, and no temporary qualification workers remain. The existing previous
live deployment was not modified. Ratings remain 7 / 6 / 7 / 3 / 5; the graph-size locality target
remains explicitly open after this six-hour campaign.

The final byte-level provenance check also confirms that bundled Language, BEX and Contracts
archives match their current `blue-packages` distributions. Documents, Payment, ManualPayments,
Orders and IncrementalDocuments match the shared published archives and their recorded checksums.
This verifies the package bytes used by the qualified runtime; it is not a claim of exhaustive
implementation of every service declared by those packages.
