# Iteration 61 — skip quiet scheduling branches

This follows [compositional gas](../gas-composition/REPORT.md). That iteration established a new, explicit accounting schedule. This iteration changes physical scheduling under that schedule; it does not add an authored Blue type, change the gas tariff, or bypass timeline ordering.

## Decision and boundary

The candidate removes quiet singleton branches from the phase priority queue. A document with a pending input, initialization or held output remains work. Its containing ancestors remain work until their dependencies finish. Multi-member cyclic units retain their ordinary processing and seals, even if they currently have no inputs. Shared descendants and topology changes continue through the existing dependency/SCC machinery.

This is an optimization of the existing interpreter, not a second interpretation. Published gas mode keeps the previous scheduler. The diagnostic property `blue.physical.sparseScheduleDisabled=true` selects the previous queue behavior for differential checks under the new gas schedule.

A topology change discards the physical idle classification and recomputes it. Historical delivery tickets can address a parent outside the current edge graph. If such a delivery wakes a skipped member, the engine restores the ordinary queue before selecting more work. That conservative fallback avoids treating an earlier conclusion about a quiet branch as permanent authority. Forked attempts copy their phase state. The optimization is enabled only for in-process execution: portable chunks keep their original unit sequence, including zero-work units, so an optimization does not silently redefine previously issued chunk descriptors. A regression compares native execution and both local and portable chunk replay by exact final observations and receipt.

## What was measured locally

The synthetic Agreement → Order → Payment fixture reopens the durable local runtime for each input. Package data and normal JIT state can be reused, but mutable document sessions are not kept between inputs. Measurements include append through durable completion of all three owners. Each measured input is replayed with the whole-root interpreter and compared by complete root BlueId, inclusive gas and ordered public events.

| Metric | 10 Orders | 1,000 Orders |
| --- | ---: | ---: |
| Documents in the graph | 21 | 2,001 |
| Three measured durations, ms | 88.26 / 53.38 / 48.78 | 351.34 / 335.69 / 305.55 |
| Median, ms | 53.38 | 335.69 |
| Scheduling selections, repeated sample | 3 | 3 |
| Quiet units bulk-completed | 19 | 1,999 |
| Root inclusive gas | 157 | 157 |
| Whole-root semantic comparisons | 3/3 | 3/3 |

Before this change, the diagnostic 1,000-Order run selected 2,002 units. Afterward it selects three. The previous candidate's unprofiled medians were 52.62 and 326.22 ms. These short runs do not establish a statistically reliable percentage speedup. They do establish elimination of the per-quiet-unit queue operations. An earlier candidate run measured 48.73/311.81 ms; the final compatibility-preserving repeat is shown above, including its slower result. No end-to-end latency improvement is claimed.

The 1,000-Order case is still slower. In the second sample, the root's restore / execution / preparation phases took approximately 76.60 / 127.02 / 54.55 ms; at 10 Orders they took 2.49 / 10.26 / 2.19 ms. Physical counters still report 2,010 restored member records versus 30, 7,013 proof-vector edge visits versus 83, and 2,007 route captures versus 27 across the three owners.

The original diagnostic profile included JFR and counters, so its wall time is not used as a paired speed comparison. Its stacks show metadata parsing/serialization, semantic preparation restoration, validation, topology construction, admission and hashing. No single hot expression explains the whole remaining cost.

## Why the latency wall remains

The new gas law makes representation work free **in gas**, not free in CPU or storage. This scheduler optimization removes queue work but still compiles its plan from the restored graph. The host still reads the rooted snapshot and native preparation records; the engine still walks substantial graph metadata and constructs outgoing publication data. The AWS host additionally acquires source ranges and performs durable reads and writes.

A flat canonical digest commits the exact canonical bytes. If those bytes cover the complete member inventory, computing that digest from cold storage necessarily reads that inventory unless an applicable verified intermediate representation already exists. Making gas independent of that operation does not turn its I/O into a local lookup. We must separate the native storage/index boundary from materializing a complete portable snapshot, while continuing to return the same canonical Blue documents and preserving any identities required during actual business execution.

## Next implementation boundary

The next valuable change should remove an entire cold-storage/root-processing pass, not just increase a cache limit:

1. Persist member metadata, embedding edges, channel subscriptions and source-predecessor selections as independently addressable, versioned native records.
2. Retain the proof of timeline completeness and ordered input selection at the same atomic publication boundary as the affected document state. A sparse read must not become an unverified assumption that quiet timelines have no earlier input.
3. Load the changed member, its cyclic unit if any, its selected source completions, and the containing paths that actually react. Resolve authored bodies only when their rules or values are needed.
4. Keep the full portable snapshot/export path as an explicit operation, and identify every place where business semantics themselves require a whole-root identity or collection scan. Do not omit those operations from a timing while still promising their synchronous result.
5. Gate the storage change with independent whole-root results, gas and events; restart/crash, delayed import, same-time ordering, removal, shared-descendant and multi-round cycle cases; and storage-call/byte counters at 10 versus 1,000.

This iteration does not claim those five steps are implemented. The latency objective remains open.

## Verification and AWS status

Final local gates: **2,492 library tests, 82 local runtime tests, 821 AWS-host tests; all passed**, plus formatting, Javadoc, distribution and qualification-JAR builds. The library adds targeted cases for a 1,000-branch tree, changed ancestry, a historical delivery into a skipped branch, cyclic units, and portable chunk compatibility. Existing semantic gas tests also compare the sparse execution with the previous queue by exact observations and receipt at 1, 10 and 1,000 siblings. Local and host suites retain their ordering, restart, failure and cycle coverage.

Two bounded local runs passed all twelve whole-root semantic comparisons. The final run preserves portable chunk scheduling and is the one shown in the table. Initialization of all 1,000 Orders still takes approximately 15.70 seconds; initializing every document is real work, and this change makes no initialization speed claim.

Evidence files in `iteration61/` are bounded reports and logs, not retained application databases or raw profiler recordings. 


## AWS qualification

An isolated temporary c7i.xlarge worker ran the final artifact against DynamoDB with a 1 GiB heap, two active processors, a two-CPU quota and a 1.5 GiB process memory limit. Each input reopened owner storage; transport caches were disabled and mutable document sessions were not cached. Immutable packages and JVM/JIT state could be reused. The worker was verified terminated after the run.

| Metric | 10 Orders | 1,000 Orders |
| --- | ---: | ---: |
| Median durable processing | 832.06 ms | 2,167.34 ms |
| Median range acquisition | 161.66 ms | 2,096.39 ms |
| Median acquisition + durable processing | 993.72 ms | 4,338.07 ms |
| Root inclusive gas, every input | 148 | 148 |
| Whole-root document/gas/public-event comparisons | 4/4 | 4/4 |

This fixture's business rules differ from the local fixture, hence gas 148 rather than 157. Each fixture is compared against its own whole-root interpreter execution. The previous AWS candidate measured 916.64/2,233.83 ms processing and 1,077.53/4,480.18 ms including acquisition. One four-input repeat is insufficient to attribute the difference to this queue optimization or establish a latency SLA.

The qualification command was `ee3669c3-9cab-47a6-8da2-ca03f42c6e46`; the temporary worker was `i-017f3b70286edcd40`. Its artifact SHA-256 was `ffff97ffa471ac35d8ea84a9c5a03d40651a6df7098b1adc773a9e16a742a37f`. Full measurements and termination confirmation are in `iteration61/aws-report.json`, `aws-output.jsonl`, `aws-qualification.json`, and `aws-worker-termination.json`.

Decision: **KEEP** the elimination of unnecessary queue operations, with unchanged semantic results and portable chunk compatibility. Do not call the graph-size latency problem solved. Current myos-core-better ratings remain correctness **7/10**, compatibility **6/10**, clarity **8/10**, performance **3/10**, operations **5/10**. The native indexed-storage boundary above is the next priority.


## Deployment

The isolated canonical preview at <https://d2buikczdox1rt.cloudfront.net> now runs this candidate. SSM command `95a78878-0c96-46b8-8c1a-302b2e515767` completed successfully. Runtime artifact SHA-256: `3dfb410da8c1843533d332bc9eccfb0e66038bcd735ecf6025507be6fd17e4c4`. The service health result reports durable canonical graphs, automatic timelines, native admission and `newOwnerGasSchedule: contracts-compositional-gas-1`. Existing histories retain their saved schedule. The health result is the internal service check performed by the rollout; CloudFront's static root does not expose `/health`.

Both live HTTPS journeys passed:

- Published Activation/Documents/Payment scenario: two Orders and Payments, ten operation calls, final Agreement paid total **400**. Every exact submitted entry was checked against its committed Agreement outcome; history, events, retries, account isolation and API-key checks passed. Client submission-to-observed-root durations were **3,714.07–6,882.29 ms**, median **4,961.86 ms**. These include network/polling and remain far above a millisecond-level interactive target.
- Mixed sources: two signed external historical entries replayed to **14**, then a local timeline operation advanced the state to **19**, with the exact input committed APPLIED and no pending/deferred work. Bootstrap through historical replay took **1,682.69 ms**. Local submission through observation took **1,348.41 ms**, plus **452.27 ms** for operation lookup; the recorded restore/admission/execution portion was **114.23 ms**.

The reports are `iteration61/live-clauses.json` and `iteration61/live-mixed-timelines.json`. These acceptance journeys use isolated QA accounts. Their timings are not a claim that a full published-clause operation takes the smaller synthetic fixture's raw engine time.

The target project directories receive the verified source changes; the original blue-java, Python runtime and shared package definitions are unchanged by this iteration. No GitHub access or push was performed.
