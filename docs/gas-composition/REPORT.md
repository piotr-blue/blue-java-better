# Compositional gas implementation and qualification — 9 November 2026

Subsequent updates: [iteration 61](../processing-locality/ITERATION-61.md) removes quiet scheduling units from native execution; [iteration 62](../processing-locality/ITERATION-62.md) shares immutable snapshot metadata and removes full inventory walks on cached snapshot checks; [iteration 63](../processing-locality/ITERATION-63.md) publishes changed preparation rows and preserves unchanged attachment topology during child-result adoption. Iteration 63 contains the latest deployment and timings. These changes preserve this accounting law; the measurements below remain the historical iteration-60 evidence.

This report accompanies [the strategy](STRATEGY.md) and [the API/host usage](USAGE.md). It separates semantic accounting from elapsed time. The accounting change is implemented behind an explicit opt-in schedule. It does not yet make large Agreement processing physically independent of the number of quiet embedded documents.

## What the change establishes

For a successful Payment → Order → Agreement reaction, the new accounting obeys:

```text
Payment's own work:                 51
Order's own reaction:              +53
Agreement's own reaction:          +53
-------------------------------------
Agreement's inclusive gas:         157
```

An independent Payment result reports 51. An Order result includes its Payment and reports 104. The Agreement result includes both and reports 157. Adding 51 + 104 + 157 would double-count the descendants. Persisted `gasByMember` records retain the local contributions; a receiving parent incorporates a protected source ledger once.

For the tested operation, adding quiet siblings changes neither those contributions nor the inclusive gas. Each 10-Order and 1,000-Order sample reported `[51, 104, 157]`. This is not a claim that all operations cost the same: initialization really initializes all new Orders, a workflow that scans all Orders pays for that work, and genuine fan-out or cyclic feedback costs each executed turn.

Expansion, collapsing, package/definition loading, representation traversal and engine proof bookkeeping do not consume semantic gas in the new schedule. BEX execution, actual contract handlers, patches, event delivery and local turns still do. Host admission and language resource limits independently bound the uncharged physical work.

## What happens to an input

1. The existing timeline authority or external import records an input and establishes completeness. The scheduler retains Coordination ordering, including equal timestamps across different timelines.
2. A Payment executes in its own durable history. Its protected completion binds its predecessor, resulting state, cause, schedule, events and gas.
3. The Order scheduler selects the exact historical Payment completion for this input. It does not fetch the Payment's latest head and treat that as the expected predecessor.
4. In the new schedule, the Order adopts eligible successful completed work and its gas ledger, then executes its own reaction. Shared descendants are charged once. Event occurrence identities and payloads remain part of the result.
5. The Agreement performs the corresponding adoption/reaction from the Order's exact completion. Each parent publishes its own retained view. Independent child histories remain independent.
6. An insufficient cap, failed child, or mismatched predecessor uses ordinary processing where necessary to preserve the parent's budget and rollback behavior. A parent failure never rewinds an independently committed child.
7. Cyclic components retain the existing atomic execution boundary. They are not decomposed into speculative independent commits. All actual rounds are metered.

The correctness oracle is a fresh, uncached whole-root invocation under the **same new schedule**. The comparison checks complete root BlueId, gas and ordered public events. Whole-root and receiving receipts can differ because they describe different execution provenance. Published mode keeps its existing exact-receipt tests.

## Code boundaries and compatibility

`GasSchedule.PUBLISHED` is still the library default. `GasSchedule.COMPOSITIONAL_V1` is the explicit draft override, persisted as `contracts-compositional-gas-1`. It is not a published gas-6 receipt and does not claim old Python/Java gas parity. Both modes use the existing Contracts interpreter, Language/BEX implementation and published authored contract types.

The library binds the selected schedule into snapshots, portable invocation policy, retained completion validation and prepared configuration. The local host pins its storage directory's schedule. The AWS host pins each new independent-owner history and restores its saved schedule even if the creation default changes. Existing histories are not silently reinterpreted.

The public configuration is deliberately small:

```java
Contracts contracts = Contracts.newBuilder()
    .language(language)
    .gasSchedule(GasSchedule.COMPOSITIONAL_V1)
    .build();
```

Two other changes belong to this draft semantics revision and need review alongside the tariff:

- Zero-work member proofs are sparse; receiving provenance is not a byte-identical ordinary rooted receipt.
- Dynamically created embedded member identities derive from the creator, attachment path, retained generation and child value, instead of the outer invocation. A regression found that otherwise a child creating a grandchild independently produced different IDs/events from whole-root execution. Published allocation remains unchanged.

No shared `blue-packages` publication, original `blue-java` implementation or Python implementation was changed. Publishing the new semantics as a package edition remains a separate step.

## Resource and failure safety

Nonnegative multiplication is checked before calculating a cost. Allowances are checked before addition, so an enormous positive debit cannot wrap into a negative or small value. The diagnostic attempted total saturates instead of overflowing. Per-member and shared gas caps remain enforced. An endless four-member event-feedback cycle hits the gas cap and rolls back rather than executing forever.

Native acceptance outside a contract turn has an independent 10-million-unit host budget. Even an overflowing native admission cost yields retryable `HOST_INCOMPLETE`, not a committed business gas failure. Existing Language limits cover traversal steps, depth, values, provider fetches, input bytes and numeric sizes. A host resource failure does not prove a deterministic document failure and must not consume the input.

Successful source reuse is intentionally conservative. A failed child's retained record cannot issue a successful completion capability. Both hosts fall back to the parent's original invocation for rejected sources. A parent which previously rolled back may hold an older child view than the independent child; mismatched predecessors also cannot be adopted. LIVE receiving with insufficient allowance falls back so the same first rejected microcharge is preserved. The separate HISTORICAL receiving contract is not claimed to have identical LIVE failure prefixes.

These are in-process protections. A trusted Java native extension that loops without using its meter is not forcibly interrupted by gas. CPU deadlines, worker isolation and memory limits remain host responsibilities. This experiment is not a general Java sandbox or a production-readiness certificate.

## Local 10/1,000 qualification

Source: `evidence/local-report.json`. This uses the real local storage adapter, canonical Contracts/BEX and signed local timeline routing, with a deliberately small synthetic Payment/Order/Agreement business rule. It is not the full commercial Agreements clause installation benchmark.

Each case contains one Agreement, one Payment per Order and all Orders embedded: 21 documents at size 10, 2,001 at size 1,000. Initialization emits Order events that the Agreement counts. Each sample calls the same Payment operation, increments its amount by 42, causes the Order's reaction and updates the Agreement's total. Three samples finish at totals 42, 84 and 126. Exactly three owner jobs execute per sample.

The local runtime is closed and reopened before every input. Immutable package data is shared; mutable user-document sessions are not resident. The timer includes append and durable drain, and excludes later inspection and the independent oracle. The operating system may cache file pages; this is not a machine reboot or a cold-filesystem experiment.

| Metric | 10 Orders | 1,000 Orders |
| --- | ---: | ---: |
| Documents | 21 | 2,001 |
| Initialization | 260.15 ms | 15,908.91 ms |
| Update sample 1 | 93.55 ms | 353.66 ms |
| Update sample 2 | 52.62 ms | 326.22 ms |
| Update sample 3 | 48.98 ms | 305.17 ms |
| Median append through commit | **52.62 ms** | **326.22 ms** |
| Payment inclusive gas | 51 | 51 |
| Order inclusive gas | 104 | 104 |
| Agreement inclusive gas | **157** | **157** |
| Complete document/gas/ordered-public-event oracle | 3/3 pass | 3/3 pass |

The two sizes necessarily have different full documents and different BlueIds: one contains 10 Orders and the other 1,000. The exact identity comparison is **independent processing versus whole-root processing within each size**. Across sizes, the selected operation has the same local effects and gas.

## Why time still increases

The second sample isolates the root Agreement:

| Agreement phase | 10 Orders | 1,000 Orders |
| --- | ---: | ---: |
| Restore | 2.72 ms | 70.45 ms |
| Execute | 9.03 ms | 126.06 ms |
| Prepare result | 2.00 ms | 52.08 ms |

The larger case's Payment and Order restore/execute in a few milliseconds each. The Agreement still restores a rooted native state and constructs graph-dependent state, validation, dependency and publication structures. Reusing a child's successful business work removes repeated child computation; it does not remove all of those root-wide passes.

This iteration removes the representation-charging preflight, duplicate logical representation traversals and zero-work unit proof construction in the opt-in mode. It does not replace the native snapshot's member/edge representation with a persistent incrementally updated execution index. Thus it satisfies the measured additive gas law, but **does not satisfy near-flat wall-clock processing**. No million-document latency claim is made.

The next architectural work should pass changed-member/attachment deltas directly through persistent native indexes and publication, preserve unchanged subtrees by reference, and materialize the whole ordinary Blue document only on demand. Each change must still match the whole-root oracle. Any change to the required canonical commitment must be an explicit semantics proposal, not a storage hash relabelled as a BlueId.

## Verification and AWS status

The final library gate passed **2,487 tests**, formatting and Javadoc. The local host passed **82 tests**; the AWS host passed **821 tests**, formatting and distribution/qualification builds. Deployment helpers passed **48 Python tests**. These are Java-host helper tests, not tests that run the Python Blue interpreter. Raw counts are in `evidence/test-counts.json`.

The added laws cover quiet siblings at 1/10/1000, inline/collapsed representations, inherited declarations, exact-cap/one-less/zero rollback, imported-source cap thresholds, arithmetic overflow, native-admission overflow, an endless four-member feedback cycle, actual fan-out, schedule pinning, portable invocation, dynamic embedded births and failed-source eligibility. The local integration extends chain, diamond, shared cycle, connected cycles, cycle plus leaf, and cyclic-root comparisons to the new schedule, with repeated deterministic input batches and fresh runtime opens. Rejected-child recovery and independent-child progress after parent failure are exercised under both schedules.

The new accounting does not add a separate portable-proof protocol. Old/new schedule mixing is rejected; attaching an existing old-schedule history to a new-schedule execution requires an explicit replay/migration design. Source adoption is restricted to protected local records established by this engine, not arbitrary remote claims.

AWS deployment identity and actual measurements follow below. The local numbers above are not estimates of network or DynamoDB latency.


## AWS qualification

An isolated temporary `c7i.xlarge` worker in `eu-west-1` ran the same host code against DynamoDB. The Java process used a 1 GiB heap, two active processors, a two-CPU quota and a 1.5 GiB process memory boundary. Owner storage was reopened before every input, transport content/record caches were disabled, and mutable execution sessions were not cached. Immutable package data and normal JVM/JIT state remained reusable. The worker was subsequently verified **terminated**.

This synthetic fixture has one Agreement and one Payment per Order. Its rule is slightly different from the local fixture, so its gas is 148 rather than 157; compare equality across sizes within each fixture, not the absolute gas between two different programs. Four signed inputs per size were independently replayed afterward. **All eight comparisons passed for full resulting document BlueId, gas and ordered public events.** Initialization also matched its whole-root receipt.

| Metric | 10 Orders | 1,000 Orders |
| --- | ---: | ---: |
| Initialization | 1,275.77 ms | 35,381.18 ms |
| Durable processing samples | 1021.02 / 939.84 / 893.44 / 859.33 ms | 2276.95 / 2394.80 / 2171.68 / 2190.71 ms |
| Median durable processing | **916.64 ms** | **2,233.83 ms** |
| Median signed-range acquisition | 171.15 ms | 2,223.79 ms |
| Median acquisition + durable processing | **1,077.53 ms** | **4,480.18 ms** |
| Root inclusive gas, every input | **148** | **148** |
| Native whole-root semantic comparisons | 4/4 pass | 4/4 pass |

Acquisition is shown separately and also included in the combined number: omitting it would conceal a second scaling cost. The timer for durable processing starts after the signed range has been acquired. These measurements do not include the user's browser/network path. They are bounded samples, not p95/SLA estimates or a throughput campaign.

The second sample recorded 39 metadata reads and six commits at both sizes, but content lookup calls grew from 77 to 186 and batched content reads from 35 to 77. These are storage-adapter calls, not a count of provider polls. The counter snapshot now ends with timed execution, before result inspection and oracle replay; no query operation was recorded in this processing window. Root restore/admission/execution was 137.66 ms versus 1143.82 ms. Both CPU-side rooted reconstruction and cold storage access remain material. Acquisition also grows; its source-publication/fan-out path needs separate profiling before assigning that cost to a specific method.

This is the second full AWS run. The earlier run also passed all eight semantic comparisons, with medians 855.87/2,195.68 ms processing-only and 1,019.17/4,435.09 ms including acquisition. Its I/O window inadvertently included post-timing inspection; that boundary was corrected before this repeat. The `*-first.*` evidence files preserve the earlier run. Thus **16/16 measured AWS inputs passed** over two runs, with flat semantic gas and persistent wall-time growth.

Evidence: `evidence/aws-report.json`, `evidence/aws-output.jsonl`, `evidence/aws-qualification.json` and `evidence/aws-worker-termination.json`. Qualification command: `d792f18a-f2f0-4cd3-a985-bb54f0e9c4bf`. No speedup over the previous AWS candidate is claimed from this small run; the measured value of this iteration is a compositional, representation-independent gas law with verified semantic reuse.

## Deployment

The isolated canonical preview was deployed at <https://d2buikczdox1rt.cloudfront.net>.

- Runtime artifact SHA-256: `7640589682c3e6bccef1544a10a035f6513547edf33d2c26f97d5b160ff11a32`.
- Deployment command: `91c69b10-6ad1-4553-a20f-29145d1344b4`; SSM returned Success.
- Health reports `newOwnerGasSchedule: contracts-compositional-gas-1`, durable storage and automatic timeline processing.
- The creation default is new-schedule independent owners. Previously stored histories retain their saved schedule. No old documents were bulk-migrated.

The final live Activation/Documents/Payment clause journey **passed**: two Orders and their Payments, ten operation calls, final Agreement paid total **400**. It checked each submitted Timeline Entry against its committed Agreement history, retained events, historical views, retries, account isolation and API-key creation/use/revocation. Source: `evidence/live-clauses.json`. HTTPS client submission through exact Agreement completion ranged **3501.09–6746.58 ms**, median **4970.77 ms**. These include client network and polling and are not raw BEX timing; they remain far above the requested target.

The local CLI also passed a fresh-directory/restart HTTP smoke test: counter values 1 then 2, semantic gas 49 each time, and the persisted new schedule after restart (`evidence/local-cli.json`). Mixed external/local Timeline acceptance also **passed** (`evidence/live-mixed-timelines.json`): two signed historical external inputs replayed to count **14**, followed by a local operation reaching **19**. Bootstrap through replay took 1,771.79 ms. The local operation was observed committed after 1,427.75 ms (excluding an earlier 462.36 ms lookup); its recorded restore/admission/execution phase was 48.74 ms. Missing required external coverage remains a readiness condition; the new gas rules do not bypass it.

## Iteration decision and remaining work

**Keep** the explicit semantic gas schedule, per-member inclusive accounting, verified child reuse, version-pinned storage and safety corrections. **Do not claim** near-flat physical processing or old gas-6 receipt parity for the new schedule. This uses the same Contracts engine, with a deliberately versioned draft accounting/identity contract.

Qualification found and corrected three substantive boundaries: dynamic member IDs depended on the outer root; failed child outcomes were not eligible successful completions; and console snapshot inspection also had to select the stored schedule. The first live clause attempt exposed the inspection problem as HTTP 400 from the children endpoint. It is retained as a failed acceptance attempt, not counted as a successful journey.

The next work, in order:

1. Define a persistent native member/edge/preparation representation whose update consumes only changed source members and affected ancestor paths. The current root snapshot is still too broad even with child reuse.
2. Carry exact source transitions, gas ledgers and ordered events through that boundary, retaining attachment generations, historical predecessors and atomic cyclic components. Continue using whole-root execution as the correctness oracle.
3. Remove full-root finalization/metadata/publication reconstruction where unchanged structures can be retained. Separately identify any canonical commitment that actually requires a broad hash; propose a narrowly versioned rule if necessary.
4. Profile acquisition and subscription fan-out independently. The AWS fixture has a shared timeline; signed-range acquisition still grows substantially with the number of subscribed documents. Processing-only improvements must not hide this cost.
5. Repeat cold storage measurements with the same boundaries, then the real Agreements clauses. Optimize storage requests after reducing demanded state, not merely by retaining all documents in memory.
6. Review and publish the gas/identity semantics edition in `blue-packages` only after its contract is accepted. Add other implementations against that same edition; do not relabel legacy receipts.

Current judgement of `myos-core-better` (0–10, engineering assessment rather than a certification): correctness **7**, compatibility **6**, architectural clarity **8**, performance against the requested target **3**, operations **5**. Gas composability is materially improved; the end-to-end latency requirement remains open.
