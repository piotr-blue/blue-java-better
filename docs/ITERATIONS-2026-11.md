> Historical experiment log. Several APIs described here were removed on 2026-11-08. Current behavior, retained changes and measurements are in [the canonical campaign report](CANONICAL-CAMPAIGN-2026-11-08.md) and [migration guide](../CANONICAL-MIGRATION.md).

# November runtime iterations

## Objective and invariants

Improve the library and create an isolated MyOS deployment using per-document durable execution.
Published canonical Language, BEX and Contracts identities must not silently change.
No package publisher, Python changes, existing MyOS deployment changes, or GitHub access.
Each iteration records its decision, correctness checks and measured scope. Caffeine is a design
reference for ownership, small APIs, immutable values and measured optimizations, not a dependency
or a quality certification.

## Architecture decision before implementation

- Keep canonical graph execution for exact compatibility and proof consumers.
- Add a separately named document-local protocol for a trusted application host. Local execution,
  atomic persistence and an outbox replace global proof construction. Incoming child updates are
  deduplicated, ordered and delivered to parent listeners. Unrelated siblings are not executed.
- Separate the parent business state from its indexed embedding edges. Full document materialization
  and canonical identity are explicit read/export operations; these necessarily visit selected content.
- Prefer DynamoDB conditional transactions and per-document records to SQLite or one account-wide
  journal. Use an official AWS SDK, durable inbox/outbox work, bounded caches and explicit tenant keys.
  One root remains a serialization point when every child event changes that root; adding workers
  cannot make that single mutable aggregate infinitely parallel.
- Cross-document atomicity, global proof receipts and cyclic fixed-point processing cannot be
  silently equated with asynchronous delivery. Reject unsupported configurations explicitly and
  compare business document states/events at quiescence.

## Iteration 1 — lazy standalone BEX Language capabilities

Candidate: do not allocate a canonical Language bridge for arithmetic/read-only BEX evaluations.
Allocate it only on the first matching or identity operation, confined to that invocation.
No global mutable resolver or identity-evidence sharing is introduced.
Decision: **keep**. All 197 canonical BEX tests pass. Compiled add/read expression: 20,000 warm-up evaluations, nine batches of 20,000; median 1.758 microseconds before, 1.236 after. Single-JVM engineering sample; no throughput or statistical-confidence claim.


## Iteration 2 — independent document execution

Candidate: `DocumentRuntime`, explicitly labeled `blue.document-local/1`, delegates embedding
ownership to a durable host. The existing canonical handlers process lifecycle, BEX workflows
and child event deliveries. Checkpoints omit installed shared package records. Process-view
history is not used as a second durable store. No canonical graph receipt is exposed.

First parity test passes: initialize agreement, add and initialize an order, submit and create
a payment, settle payment, deliver to order and agreement. Root counters, child business states
and parent event batches match canonical execution at quiescence. Checkpoint restore and
rejection rollback pass. Attachment mutation and durability qualification remain in progress.

AWS selection: the user explicitly requested the existing MyOS deployment credentials. They
resolve to account 581571886327; the selected region is eu-west-1. A separately named stack
will be used. Existing MyOS stacks, identity services, databases and public DNS are untouched.

## Iteration 3 — repair the Contracts effect boundary

The new attachment tests found an existing bug: Update Document rejected a changeset selected
directly from an event because the interpreter returned a lazy exact list instead of java.util.List.
The boundary now reads list/patch-container structure through the BEX value protocol, retaining exact
payload handles. CanonicalEffectTest reproduces the failure with a single normal workflow.
Validation is unchanged; this accepts a legitimate list rather than weakening patch checks.
Focused canonical and document-local tests pass; full conformance qualification follows.

## Iteration 4 — immutable host embedding index

A 16-way radix index stores embedding paths in bounded immutable pages. Updating one link stages
only its hash path and commits a new root pointer. The old root still reads the old child version.
The 20,000-sibling test passes with at most eight page reads/writes, preserves every other entry,
and verifies deletion and no-op updates. The benchmark intentionally targets the failure mode
where rewriting a plain sibling map would grow linearly with unrelated documents.

Iteration checkpoint, 2026-11-05: full library build passes **1,968 tests, zero failures/errors/skips**,
formatting and strict Javadoc. Decisions: keep iterations 1 and 3; keep iteration 2 as an explicitly
experimental host-local API with its documented semantic boundary; keep iteration 4 as the host
index foundation. The canonical engine remains the default. The host-local API does not yet claim
all distributed MyOS capabilities, cycle support, full historical graph parity or production HA.
The first AWS stack reached CREATE_COMPLETE: one host, isolated table and private artifact bucket.

## Iteration 5 — bounded host session reuse

Decision: **keep**. A leased session cache is fenced by persisted versions and discarded on a failed
or uncertain publication. Cache ownership is per document, with entry/estimated-byte/use-count bounds.
The installed package inventory is computed once per DocumentRuntime configuration. Two-host CAS,
response-lost-after-commit, retry rollback and restart tests pass. A concurrent test exposed starvation
with five immediate retries; bounded jittered backoff now prevents one writer repeatedly winning.

## Iteration 6 — one durable wake-up per document

Decision: **keep**. All outbox versions remain durable and ordered; only one work-index item per
document is queued. Clearing the last wake-up checks both its revision and the document head, so a
concurrent command cannot lose its notification. Empty child initialization batches start at delivered
version one. The memory adapter now indexes partitions and work rather than scanning history. Tests
cover forty pending versions, rearming a drained document and two-host publication.

The live adapter initially omitted DynamoDB ConditionCheckItem from its table-scoped IAM permission.
AWS refused the first transaction using an explicit read condition. The experiment stack policy was
corrected; no broader table or existing deployment access was added.

## Iteration 7 — skip installed package records during checkpoint traversal

Decision: **keep**. Previous local checkpoints exported installed package closures and removed them
after serialization. Export now stops at supplied, verified identities. Normal canonical export keeps
its full closure. All 1,969 library tests pass, including actual Agreements/Connections clauses restored
and invoked from compact host checkpoints. In the bounded 128-order/384-payment local probe, median
three-level completion fell from 12.13 ms to 9.58 ms; leaf execution stayed about 1 ms. These are
engineering samples from separate JVMs; the earlier sample also had JFR enabled, so this is indicative
rather than an isolated causal percentage or a durability/SLA claim.

## Iteration 8 — collapse every child within checkpoint records

Decision: **revert**. Writing one shallow record per node appeared attractive but changed the physical
shape expected by runtime marker readers. Restore tests failed with INVALID_INTEGER for referenced
checkpoint timestamps. Five tests caught the issue. The candidate was not deployed. We retain the
verified record shape; a future representation change needs explicit reader semantics and qualification.

## Iteration 9 — direct durable-work hints and throttling recovery

Decision: **keep**. Publication gives the local worker a bounded scheduling hint; durable work rows
remain the recovery authority. A test proves payment-to-order-to-agreement propagation performs zero
work-index scans. A live DynamoDB run reached 512+ orders and then returned a mixed throttling/conflict
cancellation. The adapter now classifies only known retryable cancellation reasons; whole attempts
retry with bounded backoff and discard speculative state. Validation/unknown failures still surface.
The resumed run passed 8,927 initialized orders without increasing the parent payload with siblings:
8,388 compressed bytes / 114,172 JSON bytes / 123 content records, compared with the small local
one-order probe's 8,062 compressed bytes / 113,662 JSON bytes / 121 content records. Different keys and
latest events account for small differences; this is a size observation, not a throughput claim.

## Iteration 10 — speculative head reuse

Decision: **keep for durable qualification**. A 16 MiB byte-bounded cache avoids a strong head read
for locally known external-call candidates. Every successful or rejected result still conditions on
that head version. A new test forces a stale business rejection and proves it is reevaluated against
the actual current state. Conflict/uncertain publication invalidates the candidate. Local in-memory
medians did not improve (about 9.35 versus 9.54 ms end-to-end); the intended benefit is avoiding a
DynamoDB round trip, so AWS qualification remains required before any latency claim.

## Iteration 11 — read-your-writes across detached collections

Decision: **keep**. The host previously rebuilt a collection from the old parent snapshot. It now
merges pinned children into the current local projection and suppresses links changed earlier in the
same workflow. A second removal no longer resurrects the old child. Tests cover add, replacement,
removal, rollback, and refusing to remove an active boundary silently. Complete projections retain
execution declarations, with explicit restore fallback for the first experimental stored rows.

## Iteration 12 — complete the lazy effect boundary and service inspection

Decision: **keep**. Compute can return an entire effect object selected from an event, and constructed
patch lists can contain lazy records. Malformed extra patch fields still reject atomically. Local
execution now validates timestamps before work. The library build passes **1,972 tests**. The host
passes **18 tests**, including real loopback HTTP requests and immutable event history after outbox
acknowledgement. HTTP errors distinguish malformed input, missing documents and invalid Blue input.
The service distribution includes authored examples and a runnable HTTP workflow. Gradle's deprecated
configuration syntax was removed, and deployment archive metadata is normalized for reproducibility.

## Iteration 13 — suspended delivery and explicit redrive

Decision: **keep** after the full host suite. Live release qualification follows. Propagation is now a
separate component with a durable ordered outbox, rather than mixed into document execution. A
business rejection records its parent, path, version, attempt and diagnostic; removes the source
from automatic scheduling; and retains every undelivered version. Later child commits preserve the
suspension. Inspection and an idempotent retry endpoint let an operator correct the parent and resume.
The integration test covers restart, a premature retry, a later successful retry, two queued versions,
retained failure records and no repeated aggregate effects. This is eventual propagation, not a
cross-document rollback guarantee.

The adapter also rejects missing/unknown/all-None cancellation reasons rather than treating them as
retryable errors. Unused Apache and Netty HTTP transports were removed; the explicitly configured
URLConnection transport remains. Dependency locks were regenerated.

## Iteration 14 — host command identity without history retention

Decision: **keep**. The recovery scenario exposed identical empty operation requests being recognized
as the same canonical event at a different timestamp. A local call overload now accepts a stable host
command identifier. Its checkpoint identity commits to that identifier and the unchanged event;
channel filters and BEX still see the original operation request. The host's durable command receipt
rejects identifier reuse with different input. The original payload-identity call and canonical
Contracts behavior remain available unchanged.

An earlier candidate addressed each call to the current document identity. Its functional tests
passed, but review found that retaining the referenced predecessor through each checkpoint could
create a history chain. That candidate was **reverted before deployment**. The retained implementation
has a forty-call test bounding checkpoint record count, including restore and repeated empty payloads.

## Iteration 15 — bounded parallel propagation

Decision: **keep**. The private service has two workers by default, configurable from one to 32.
A process-local source claim avoids duplicate work among its workers; conditional publication and
parent link versions remain the authority across processes. Wake signals are bounded and can wake
more than one worker. A test runs two host instances and four workers while two accounts submit
repeated empty payment requests with duplicate HTTP-style command retries; both root aggregates
advance exactly once per command and the durable queue drains completely. This is a concurrency
correctness test, not a production throughput qualification. Removing unused HTTP transports reduced
the installed distribution's library directory from approximately 21 MiB to 14 MiB.

## Iteration 16 — explicit published Document Dispatch capability

Decision: **keep**. A read-only probe of the actual current Hello-with-consent tutorial initially
failed on its Dispatch rules identity. The implementation was present internally but inaccessible
from the public builders. `Contracts.Builder.enableDocumentDispatch()` and the corresponding
`DocumentRuntime` option now install that exact published capability; package loading still cannot
silently enable native code. Three tests run the real Activation and Hello definitions, consent,
validation failure, canonical restore, local restore, and rejection when restoring without the
required capability. Fixtures are self-contained, pinned in the test resources, and require no sibling
repository or Python code.

The authored consent operation requires an exact document-version request; omitting it correctly
rejects. This also exposes a remaining efficiency concern: canonical event records that reference
prior full documents can retain history through content closure. The host-command fast path avoids
that reference, but version-bound authored workflows need a separately qualified storage treatment.
The first full catalogue probe is one scenario, not a claim that every catalogue application passes.

## Iteration 17 — reproducible, service-readable deployment and rollback

Decision: **keep**. The next EC2 release failed its startup health check. Investigation found that
Gradle's downloaded JARs carried mode 0600 into the tar archive. Changing release ownership to root
made those JARs unreadable by the service account. Archive metadata now normalizes directories and
executables to 0755 and ordinary files to 0644. The corrected release passed health and the real HTTP
workflow. Deployment now records a last-healthy symlink and restores it after a failed health check;
the rollback branch itself remains to be fault-injected. No existing MyOS deployment was touched.

## Iteration 18 — measure durable I/O before optimizing it

Decision: **keep as instrumentation**. The host records low-cardinality counts and wall time for
record reads, queries, content publication and transactions. Payloads and document identifiers are
not collected. The benchmark takes per-operation deltas outside target lookup, and the private service
exposes process-lifetime counters at `/metrics`. These are host diagnostics, never inputs to Blue gas
or decisions. The completed original 20,000-order run measured 1.83 ms median leaf execution, 31.88 ms
leaf durable commit and 200.24 ms payment-to-order-to-agreement completion. Its 128 measured calls all
produced the expected aggregate. Controlled working-set comparisons are still running.

## Iteration 19 — compact command commitments and independent input audit

Decision: **keep**, with full regression qualification before deployment. An exact-version operation
can reference the preceding document, whose checkpoint references an earlier event, and so on. A
sixteen-call counter probe grew from 28 to 163 content records and from 14,643 to 92,567 checkpoint
characters. This growth is history retention, even with no embedded documents.

Host-identified local events now retain a command commitment containing the event's identity as
text, rather than retaining the event body and following its document reference. BEX and filters
still receive the original event. The same probe stays at 25–26 records and 11,555–11,729 characters;
forty-call regression tests also verify restore and final state. Canonical Contracts and the shorter
payload-identity process API are unchanged. The durable host stores original inputs with command
receipts and exposes them for inspection; its inputs are copied before execution so caller mutation
cannot change the recorded request. Typed internal actions replace a nullable argument sequence.
This is an explicit document-local storage protocol, not a canonical graph-proof optimization.

Current catalogue probes also found `01-order-payment` and `02-reseller-ledger` initialization rejecting
an obsolete `requestAuthorize` binding on the selected Payment Clause. A bounded run of the original
Java executable against the same package directory confirmed the same rejection. Neither the shared
packages nor Python were edited. Hello, amendments and a 57-step payment-instruction protocol probe
passed their state/status assertions; broader event/restore coverage is still being incorporated.

## 20. Pinned service runtime configuration — keep

The service can now load a bounded `myos.runtime/1` manifest once at startup. Each archive has an
expected semantic package blueId; mounts select its root or a chain of verified imports. Optional
Document Dispatch must be selected explicitly, and `DocumentRuntime.Builder.require(BluePackage)`
checks exact package requirements against the configured native capabilities before storage opens.
Incoming documents cannot turn on native code. Paths cannot escape the runtime directory, including
through symlinks. No publisher or implicit network fetch was added.

Tests cover a real pinned Contracts archive under a consumer-chosen prefix, import traversal,
wrong pins, unknown features/fields, null feature entries, path escape, and Dispatch rejection unless
selected. Two fixture mistakes found during testing were corrected: imports are case-sensitive
(`bex` in this publication), and the Blue reserved `value` field is not an ordinary business property.
No production semantics were relaxed to make those fixtures pass. Keep after full library and host
builds; ordinary tests require no AWS or Python source tree.

## 21. Real clauses across a durable JSON boundary — keep

The pinned Hello/Activation example now runs through the host's create/process/read/command API,
including consent, a validated action, rejected input and cold restart. This found a real integration
defect missed by simple counter examples: a BEX projection of installed clause schemas can contain
undefined list slots. They are not JSON null or empty objects, and ordinary Jackson serialization
correctly rejected them. The in-memory record store previously allowed that non-JSON value through.

The host now stores projections as JSON data with an explicit sidecar list of undefined JSON pointers.
It reconstructs those values for BEX reads and exposes the same sidecar on HTTP reads. Ordinary null,
empty objects, escaped field names and list positions round-trip independently; no user field is
reserved for a sentinel. The canonical checkpoint remains separate and unchanged. The integration
fixture now serializes every record before publication, so it exercises the durable representation.

A runnable private HTTP example includes byte-identical pinned archives, a runtime manifest, the
Hello document and a report-generating client. The read API exposes `localBlueId`, explicitly the
local body identity rather than the identity of an expanded embedding graph. Native policy checks
remain explicit. This expands practical clause coverage; it does not claim all catalogue packages
or original MyOS identity/account services are implemented.

## 22. Batch independent durable reads — keep

The measured three-level path used roughly 21 individual record reads and six conditional
transactions per payment. The host now batches each command's receipt/scheduling reads and each
source delivery's gate/head reads. Each record remains strongly consistent; a batch is explicitly
not an atomic multi-record snapshot. All previous conditional-write fences remain in force.
Partial AWS batch responses retry only unprocessed keys with bounded delay; missing records remain
missing, duplicate keys are removed, and interruption cannot silently turn unprocessed keys into
absence. The full host suite (29 tests) passes, including two-host concurrency and rejected-parent
recovery. The keep/revert decision follows the controlled AWS comparison, not test timing assertions.

The AWS candidate (`159ed79b17c5126ec447fad95bf99b46d71de6ed7fba086f4d03549d24e5426f`)
completed all aggregate checks. With the same 20,000-order graph and eight active orders, median
completion changed from 192.70 to 182.05 ms, and leaf commit from 25.77 to 23.19 ms. The measured
path made exactly nine single reads plus six batch calls per operation, instead of about 21 single
reads; transaction count stayed six. This is a modest measured improvement, not a throughput SLA.
The candidate also includes the projection correctness fix, so wall time is a release comparison;
the reduced round-trip count is the direct evidence for this optimization. Keep.

## 23. Bound materialized read projections — keep the revised read path

The real Hello application expands to about 993 KB of BEX projection, roughly half in `clauses` and
half in `clauseRuntime`. In the warmed local HTTP baseline, a message update took 118.27 ms median
although its execution took 8.99 ms; full local reads took 36.32 ms. The first AWS Hello action took
694.88 ms, including only 44.99 ms of kernel execution. These are different environments and warm-up
histories, not directly comparable latency claims.

A candidate treats the expanded projection as an optional 64 KiB read cache. Large documents keep
only their canonical local checkpoint and embedding index on subsequent writes, while small
projections retain their current fast read path. Missing projections restore from checkpoints, and
external child reads select only the requested field. The full 30-test host suite passes, including
real clauses, serialized restart, large child field reads, and full expanded reads with undefined
schema slots. The latter also exposed and fixed an old JSON-copy path that could not preserve BEX
undefined. The candidate's keep/revert decision must include its explicit full-read tradeoff.

The first checkpoint-only candidate reduced warmed Hello update latency from 118.27 to 12.47 ms,
but made full reads regress from 36.32 to 713.84 ms because every read cold-restored the runtime.
**Do not keep that first read path.** The revised candidate reuses an available execution session only
when its version exactly matches the loaded durable head. It takes a document lock with `tryLock`,
never waits while holding another document lock, and falls back to the immutable checkpoint if the
session is busy or stale. Read leases do not advance the mutation-history reuse counter. This keeps
the write improvement without requiring an unsafe shared mutable session or weakening version checks.

## 24. Keep scheduling revisions monotonic — keep

Concurrency review found an ABA risk in deleting a drained wake-up record and recreating it later
at revision one. An earlier observation could then have the same numeric revision as a different
scheduling state. Draining now clears the queue-index fields but retains the small scheduling record,
so rearming advances its revision. It remains absent from the work index while idle. A regression
test captures an old wake-up, drains/rearms the source, and proves the old conditional write cannot
publish. This costs one small retained row per document that has produced work; it does not retain
outbox payloads or change business document versions.

## 25. Avoid repeatedly merging the installed evidence inventory — keep

Cold restore of a 161,240-byte Hello checkpoint containing 99 local records took 567.05 ms median
in an isolated JVM probe (five warm-up restores, fifteen samples). Inspection found that each
record verification started from the installed package inventory and then merged that entire map
back into the accumulator. This multiplied installed-package work by checkpoint record count.

The candidate reuses the persistent root of the already verified prefix and replaces the accumulator
root after each verified response. It still removes the selected record's cached body before opening
that response, so inline values, built-in definitions or another cyclic member cannot authenticate a
forged supplied record. Publication still happens only after the whole batch succeeds. Four new
adversarial/atomicity tests and the complete 1,981-test library suite pass; the full 32-test host suite
also passes. No trusted-checkpoint bypass, proof omission or new capability assumption is introduced.

The same isolated restore probe fell from 567.05 to 77.22 ms median (about 7.3× faster), with exactly
the same 161,240-byte checkpoint and 99 content records. Keep. This improves cold restore and periodic
session renewal without changing what is verified. Remaining restore work is still measurable.

## 26. Reuse verified Dispatch publication evidence — keep

Session construction installed the bundled Dispatch policy by converting its already verified
`BluePackage` back into raw provider responses and verifying them again. It now installs that
immutable, pinned package evidence directly, matching the existing Contracts package path. Public
untrusted checkpoint records still go through independent verification; loading a package still does
not enable its native capability. The full 1,981-test library suite passes, including actual consent
rules, portable restore and missing-capability rejection. The next isolated restore measurement
separates this change from iteration 25.

The host also has a metadata-only `/documents/head` read, so clients can bind requests to a version
and local Blue identity without rendering a megabyte of schema data. HTTP tests cover its tenant
boundary and ensure it returns no document projection. Invalid JSON roots (including null) are
rejected as client errors.

The revised projection candidate measured 12.21 ms warmed Hello update median (p95 17.19 ms), versus
118.27 ms (p95 198.88 ms) before. Full local reads measured 73.79 ms median versus 36.32 ms. Keeping
expanded schemas out of every durable write is worthwhile, with this explicit read tradeoff. The
metadata-only endpoint avoids full rendering when only a request target is needed. Cold session
renewal remains visible in maximum latency; iterations 25 and 26 address that separately. Keep the
revised path, not the first version that cold-restored every read.

The isolated restore median fell further, from 77.22 to 59.40 ms on the identical checkpoint. Keep.
Together with iteration 25 this is about a 9.5× cold-restore improvement, without turning off evidence
validation. The subsequent full host suite contains 33 passing cases, including both projection
policies for old/new record compatibility.

## 27. Introduce compact records through a compatible reader — keep

Older experimental readers assumed the optional projection was always present. To preserve a usable
rollback, the new binary first runs in `MYOS_PROJECTIONS=eager` mode: it understands omitted projections
but still writes full ones. Only after it is healthy does the deployment switch to `bounded` mode.
Rollback now restores the last healthy systemd configuration as well as the binary, and an exit trap
covers failures during service activation as well as a failed health check. Both stages passed health checks and the 80,001-document workload. A later release-directory
permission failure exercised automatic rollback successfully; the deployment was corrected and
then activated. Deliberate configuration-failure injection is tracked separately from that observed
failure, rather than silently treating every recovery mode as covered.


## 28. Fair durable discovery under sustained local traffic — keep

Local hints previously bypassed durable index discovery whenever they made progress. Discovery also
always began at shard zero and fetched only the first page. A busy process could therefore delay
another process's committed work indefinitely; a waiting source could hide later rows in its shard.

The candidate reserves periodic discovery while local traffic continues, rotates all 16 shards, and
retains an opaque page cursor for each shard. Local delivery attempts are bounded even when stale
hints cannot make progress. Discovery does not hold its cursor lock during business execution. GSI
results are still re-read consistently and every delivery remains conditionally fenced. A pending
cursor is advisory; eventual index changes may require another pass, and no time-to-delivery SLA is
claimed under an overloaded worker pool.

Two deterministic fairness tests fail with the old drain algorithm and pass with the new one: remote
work behind continuous local hints, and ready work behind a waiting source in the same shard. Empty
scan wraparound and full DynamoDB cursor-key preservation have separate tests. The normal local
payment/order/agreement path still needs no index scan before its discovery deadline. The full host
build passes 37 tests with formatting and strict compiler checks. Keep; live throughput and restart
qualification follows separately.


## 29. Read labels directly from interpreted heads — reverted

A local JFR probe of the actual Hello installer found repeated Language refinement and validation
beneath BEX view inspection. Warm initialization measured about 162 ms, with a separate roughly
60 ms admission cost. A candidate read `name`/`description` from the already interpreted head instead
of constructing an exact composed value. All 1,981 existing tests passed, but that was insufficient:
a new adversarial case showed that reading metadata could then hide an invalid nested refinement
which the previous path rejected. The candidate is reverted. The metadata-ownership and nested
validation tests remain. A faster representation is not acceptable if it silently weakens rejection.


## 30. Avoid re-proving that an admitted object key is built-in Text — keep

The installer profile also found repeated construction, hashing and schema validation of ordinary
object keys against the exact built-in Text type. An admitted key is already Text and this exact
builtin adds no constraint. Native execution now recognizes that case; derived Text key types and
all non-Text encodings still use full validation. Public Language operations retain the original
physical-limit path, and canonical BEX/Contracts gas is unchanged.

The full library build passes 1,990 cases, including six new native/public key cases, derived length
constraints, canonical numeric/Boolean encodings, Unicode keys, the retained nested-validation
regression, and the stored gas/receipt vectors. In the same local Hello probe (five warm-up and
fifteen measured initializations), median initialization fell from 146.29 to 119.90 ms. Every sample
retained gas 225,898 and exactly the same resulting document identity. This is a narrower, measured
optimization; the unsafe metadata shortcut from iteration 29 remains reverted.


## 31. Preassemble package evidence for new sessions — reverted as low value

A candidate captured immutable installed package evidence once per DocumentRuntime, then seeded new
sessions from those persistent map roots. It shared no validation cache, logical witness or document
state. Isolation and forged-response tests passed together with the full suite; gas and final Hello
identities were unchanged.

However, minimal session admission improved only from 0.495 to 0.423 ms median (20 warm-up, 100
samples), while real Hello initialization was effectively unchanged at 119.90 versus 121.47 ms in
the small comparison. That roughly 73-microsecond saving does not justify a new environment layer
across four kernel classes at this stage. Revert the candidate and its implementation-specific tests;
retain the measurements and focus on the measured concurrent-host bottleneck instead.


## 32. Measure concurrent accounts and exercise recovery — keep the evidence, tune further

The isolated DynamoDB service completed 512 payments across 16 accounts, each with eight orders and
two payments per order. Every root total matched. On the fair scheduler's unchanged binary, two
workers completed 10.84 payments/second and eight completed 19.69. Eight workers increased leaf
commit median from 286.86 to 577.34 ms, so throughput improved at a latency cost; a production worker
count is not selected from that result alone.

A separate JFR run measured 97% average host CPU utilization on two vCPUs. Restore accounted for
762 sampled stacks versus 325 for business dispatch, and 59 young collections occurred during the
roughly 40-second setup/processing run. Native socket wait samples are excluded from that attribution;
inclusive stack categories can overlap. This supports investigating session churn and exact-record
verification rather than assuming DynamoDB alone explains concurrent latency.

Live restart qualification stopped the worker service, committed a fresh order and payment, confirmed
pending propagation, then restarted. The root reached one initialized order and total 42 in about
3.49 seconds including startup. A repeated payment command after restart did not change the total or
root version. A deliberately invalid runtime configuration then failed activation; an independent
check confirmed restoration of the exact healthy release, complete service unit and eight-worker
health. These are focused failure tests, not an availability-zone disaster-recovery qualification.


## 33. Reuse the complete verified exact response — keep, qualify live

Each restore was parsing and hashing the same immutable workflow records again. The candidate uses a
bounded 32 MiB / 2,048-entry cache of independently verified exact JSON responses. Keys include the
claimed identity, complete response text and host limits. Different text, source-mode environments,
YAML and cyclic proofs take the ordinary verifier. A hit restores all inline normalized bodies and
list payloads, not a blanket trust decision for later records in the batch. Final state validation
and invocation gas remain unchanged; a failed batch still publishes no evidence to its caller.

The full library build passes 1,993 cases and the host passes 37. Added adversarial cases cover a
forged response after a valid cache entry, source-mode confusion, stricter host limits, inline type
bodies and typed-list payload retention. Existing forged cyclic members and batch atomicity tests
still pass. Cache entries contain only immutable verified bodies and are bounded process-wide;
they do not retain live sessions or confer native capabilities.

In sequential isolated JVM probes, restoring the same 24,152-byte payment checkpoint (36 records)
fell from 1.98 to 1.14 ms median, with ten warm-up and 100 measured restores. The 161,240-byte Hello
checkpoint fell more modestly from 39.05 to 36.81 ms (five warm-up, fifteen samples). These are warm
record-cache measurements of cold session restoration, not first-ever verification costs. Keep the
candidate for live qualification; many-account throughput is the next acceptance measurement.

The host now exports low-cardinality session hits, loads, load time, version misses, capacity eviction,
history renewal and occupancy alongside storage counters. The concurrent client captures counters
before and after its measured phase and records observed worker configuration. No tenant/document
identifiers are used as metric labels.


Iteration 33's AWS release `1d43fa8abaca…` completed the same eight-worker, 16-account workload at
21.51 payments/s, versus 19.69 on the prior fair-scheduler release. Leaf commit median/p95 changed
from 577/975 to 450/778 ms. This release also contains the retained Text-key optimization, so the live
result is not attributed entirely to record reuse. All 512 payments and final root totals passed.
The measured phase recorded 1,131 session loads, 448 reuses and 1,046 capacity evictions. Restores
consumed 13.98 aggregate wall seconds across worker threads. The next tuning target is now measured.

## 34. Make session capacity an explicit bounded host setting — keep

The fixed 64-entry / 128 MiB cache cannot hold this 400-document working set. Capacity, estimated
bytes and mutation-history reuse are now immutable policy values. Defaults remain 64 / 128 MiB / 32
uses; startup rejects a byte budget above half the JVM heap. Active leases stay pinned until their
operation ends, so the budget is an eviction target, not a hard heap guarantee. The server's request
admission and input limits remain necessary.

The full host suite passes 40 tests. New cache tests verify exact-version reloads, history renewal,
read leases that do not age execution history, survival of pinned sessions under pressure and
invalid bounds. The live candidate uses 256 entries / 512 MiB, retaining the same 32-use history
limit and eight workers. Keep or revert after checking completed root totals, cache misses and times.


The 256-entry / 512 MiB AWS candidate (`8c77707873e3…`) completed 23.75 payments/s with all 512
payments and root totals correct. Leaf commit median/p95 were 406/674 ms. The measured phase had
591 loads, 973 reuses, 335 capacity evictions and 6.62 aggregate restore wall seconds, versus
1,131 / 448 / 1,046 / 13.98 seconds at 64 entries / 128 MiB. Keep the configurable policy and this
explicit deployment allocation; general defaults stay conservative. This is a single bounded run,
not a universal cache sizing rule or a hard memory guarantee.

## 35. Serialize exact document keys, not hash stripes — keep correctness, measure live

The host had 256 lock stripes. Different documents could wait on one another solely because their
hashes collided, and selected reads could unnecessarily fall back to a cold restore. A small leased
lock registry now retains one lock per active document key. Holders and waiters retain the same
entry; the last release removes it. Acquisition never waits while holding the registry's map lock.
Cross-document reads still use nonblocking acquisition to avoid lock-order deadlocks.

The full host build passes 43 cases. Dedicated tests cover equal-hash independent documents,
reentrant leases, failed nonblocking acquisition, and concurrent release/reacquisition without
losing mutual exclusion or retaining inactive locks. Existing cross-host fencing and projection
concurrency tests pass. This does not alter DynamoDB version fencing, business meaning or gas.


Iteration 35's release `e71fe974bb28…` completed the concurrent workload at 24.70 payments/s
(394/692 ms leaf commit median/p95), versus 23.75 at the same cache/worker settings before the lock
change. The small difference is not isolated beyond one run; retain the independently tested removal
of hash-collision interference. A separate profile run completed 23.46 payments/s.

The controlled 80,001-document payment probe measured 2.51 ms runtime call, 24.45 ms leaf commit and
190.32 ms completion. Interleaved real order additions (16 warm-up and 48 samples per graph) measured
202.01 ms completion on the small graph and 211.73 ms on the graph starting at 20,000 orders.
The large graph had two cold immutable-index page reads per operation, taking 7.32 ms median; the
small graph had zero. Both made five commits and the same parent reactions. The runtime-call timer
includes the host reader's storage waits, so its 4.98 versus 12.95 ms is not a CPU-only comparison.
The large graph now has 20,064 orders and 80,257 documents.

## 36. Page direct child links at an immutable parent version — keep

Large graphs need a read API that does not expand every child. A bounded index continuation now
returns up to 100 links in stable hash order, seeking past prior pages rather than rescanning them.
An opaque cursor pins the original parent version and is bound to its tenant/document. Historical
reads accept the child version returned by that view. Current updates cannot introduce duplicates,
skip old children or mix snapshot versions across pages. Parent event-observation version zero
remains explicit for newly initialized children whose first events have not propagated.

The full host build passes 45 cases. Tests cover bounded reads in the existing 20,000-sibling index,
Unicode paths, complete pagination, immutable-root changes, cursor mismatches, historical misses
and HTTP forms. Shared draining setup moved into HostFixtures; tests no longer depend on another
test class. Existing full reads remain available and explicitly proportional to requested content.


## 37. Stop sealing unused canonical source receipts in DocumentRuntime — keep

The current AWS profile has 177 restore stacks versus 866 execution stacks (inclusive sampled
counts, including setup). Restore is no longer the largest sampled runtime category. The local
host still called completion retention, which sealed and hashed observations for a canonical
SourceCompletion that DocumentRuntime never exposes. That receipt is now retained only in canonical
Contracts execution. Checkpoints, business effects, event identities, validation and gas are unchanged.

All 1,994 library cases and 45 host cases pass. A new boundary test confirms ordinary canonical
results still issue completion capabilities and detached host results do not. An initial short-warmup
probe was inconclusive, so acceptance uses two alternating isolated JVM runs per version: 1,024 warm-up
and 1,024 measured leaf calls, renewed every 32 mutations. Baseline medians were 0.586/0.598 ms versus
0.532/0.529 ms; allocated bytes per call fell from about 1.61 MB to 1.48 MB. Every gas sample and final
checkpoint identity matched. These are in-process leaf measurements, not DynamoDB latency claims.


## 38. Execute a detached turn without unused graph completion records — keep, qualify live

DocumentRuntime always executes exactly one local member; child documents are effects owned by the
host. It now uses a small DocumentExecution helper for that invariant. The existing workflow, event,
validation and metering code still runs. Canonical graph unit/turn completion records and their
logical-context commitments are omitted only for this host mode; source event identities and
checkpoints retain their existing meaning. Canonical acyclic/cyclic/chunk execution is unchanged.

All 23 stored single-document Contracts oracle cases now also run through DocumentRuntime, comparing
state, public events, status and exact gas limits against the canonical path. All 2,017 library cases
and 45 host cases pass. The contractsTest selector includes document-local tests as well as the
canonical tag, matching the documented ownership strategy.

In the bounded warmed leaf probe, median runtime fell from 0.520 to 0.456 ms and allocations from
1.47 MB to 1.29 MB per call. All 1,024 measured gas values and the final checkpoint identity matched
the preceding retained implementation. Keep this measured simplification for AWS qualification.


Iteration 38's actual Hello initialization retained gas and document identities, with 120.67 versus
120.13 ms median (five warm-up / fifteen measured): no meaningful installer speedup is claimed.
AWS release `84c97ae30d14…` completed the concurrent workload at 24.67 payments/s, essentially the
same as 24.70 before these two local runtime changes. Leaf commit median/p95 were 414/688 ms.
Keep the measured local CPU/allocation reduction without presenting it as an AWS throughput gain.

## 39. Keep qualification programs reproducible and separate from the service — keep

The add-order, leaf-call, Hello-initialization and pending-outbox recovery programs now live in a
separate qualification source set. Ordinary builds compile them but do not execute any benchmark
or contact AWS. qualificationJar produces a separate tool artifact; it is absent from the service
distribution. The maintained run_growth.py controller verifies the account/stack, pins the deployed
release and tool digest, preserves unique evidence files and restarts the service on every exit.

The synthetic payment probe now distinguishes actual stored order count from the eligible named
population after growth runs. This prevents later reports from silently retaining the original
80,001-document count after real orders were added. All 45 host cases and strict compilation pass;
all controller scripts compile. Dependency locks include the new qualification configurations.


## 40. Replace JVM-global URLConnection pooling with the SDK's explicit Apache 5 pool — keep

A profile with TLS handshake events enabled recorded **942 new DynamoDB TLS connections** during
the bounded 16-account workload including setup. Earlier profiles had that event disabled, so their
zero count was not evidence of connection reuse. The service's URLConnection transport relies on
JVM-global pooling; a maintained per-client pool is a better fit for this long-running host.

The candidate uses the official AWS Apache5HttpClient from the unchanged SDK 2.46.17 BOM, with
96 connections (64 admitted HTTP requests plus at most 32 propagation workers), a bounded three-second
connection acquisition timeout, and the existing connection/socket/API timeouts. There is no custom
transport, TLS bypass, retry policy or credential handling. All 45 host cases and strict compilation
pass with locked dependencies. Keep/revert awaits equivalent live completion and handshake evidence.

Sources: [AWS HTTP client selection](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/http-configuration.html)
and [URLConnection pooling behavior](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/http-configuration-url.html).
AWS documents Apache 5 as the default maintained synchronous transport in SDK 2.46+, while
URLConnection is optimized for smaller dependency/startup requirements and has JVM-global pool settings.


Iteration 36's live pagination check passed on release `84c97ae30d14…`: all 20,064 direct orders
were enumerated in 201 pages of at most 100 while a payment changed. Every continuation retained
parent version 41,825 although the current parent advanced to 41,826. Historical parent and child
reads remained unchanged. Page median/p95 were 65.95/81.29 ms on the cold large index; this is an
explicit bounded read cost and is separate from selected-document processing.

The comparable warmed TLS-enabled run completed all 512 payments with correct root totals in
13.07 seconds (39.17 payments/s), versus 16.16 seconds (31.69/s) with URLConnection. New DynamoDB
TLS connections fell from 942 to 6. First post-deployment runs were 30.22 versus 24.67 payments/s;
server warm-up materially affects both and is not attributed to the transport. Keep Apache 5.
Leaf commit median/p95 in the warmed candidate were 247.97/459.51 ms, versus 274.84/439.80 ms;
throughput improved, but the p95 did not. These bounded runs on two vCPUs are not a capacity SLA.
Release: `a9880858a831…`; profiled command: `69de5a6e-e368-43aa-83f1-1474ce7d0587`.


## 41. Bound API views before expanding a large graph — keep

An explicit expanded read could previously assemble every descendant despite selected-document
processing being local. API views now bound document visits (256), structural traversal units
(262,144), text (4,194,304 UTF-16 code units including keys), and embedding depth (64). The traversal
uses iterators and checks each local projection before copying it. It counts repeated occurrences;
these are resource bounds, not a heap-byte estimate or Blue gas schedule. Exceeding a bound returns
HTTP 413 / VIEW_LIMIT_EXCEEDED and points to version-pinned child pagination.

All 50 host tests pass. New tests cover inclusive document/depth limits, cumulative projections,
Unicode keys/text, null versus undefined, early termination of wide collections, invalid bounds and
unchanged committed state/paged access after rejected expansion. Canonical business execution is
unchanged; this boundary applies to read APIs. Unbounded cross-document reads inside a workflow
remain a separate host resource-admission issue, not something hidden by this API limit.


## 42. Qualify two service processes against real conditional storage — keep

Two independent JVMs with four workers each shared the experiment DynamoDB table. Across four
accounts and 28 documents, 128 concurrent calls represented 64 unique payment commands. Paired
submissions through different processes returned exactly identical committed receipts. Every
leaf, order and root total matched; both processes reconstructed identical version-pinned expanded
views. Reusing a command ID with different content was rejected. Completion took 6.48 seconds,
including propagation and verification; this is a race/recovery qualification, not a scaling SLA.

The maintained controllers pin the release and script digest, serialize against deployments,
stop both temporary units on exit and restore normal service. Release `519e24169910…`; command
`4ffc9fcd-9803-496e-8bb4-4b9be247bb22`. This tests independent processes, not independent machines
or an availability-zone outage. Duplicate workers can still consume duplicate CPU before CAS.

The preceding pooled-transport growth run also passed: 20,064 orders / 80,257 documents measured
2.03 ms leaf runtime, 20.08 ms durable leaf commit and 142.90 ms root completion. Interleaved order
addition measured 175.83 ms on the small graph and 187.04 ms on the large one. It added another
64 real orders with three payments each: the large graph now contains 20,128 orders / 80,513 documents.


## 43. Complete parentless documents in their original transaction — keep

New root records now retain a host-owned `mayHaveParents=false` routing fact. The current protocol
only creates fresh child trees; existing roots cannot acquire parents. Their public events remain
in immutable history, but their dispatch version advances in the original state transaction, with
no redundant outbox/wake/acknowledgment cycle. Children and unknown legacy records retain the normal
durable propagation path. Attaching existing roots in a future protocol must transactionally upgrade
this fact first. No existing record was guessed to be parentless or silently migrated.

All 53 host cases pass, including root events/restart/idempotency, conservative legacy decoding,
40-version child backlogs, monotonic wake revisions and concurrent publication. A three-level fresh
payment path requires five conditional publications rather than six.

The first comparable AWS run was slower (30.27 versus 34.00 payments/s), despite fewer reads and
transactions; restoration time also rose. A warmed profiled run completed 41.19 payments/s versus
39.17 on the preceding pooled-transport release, with every root total correct. Keep the bounded
structural reduction, not a claim of a stable throughput percentage. Release `e6f3612e0241…`.
The already-created 20,128-order graph keeps its conservative legacy routing path.


## 44. Reuse immutable Language heads in captured views — keep the lazy variant

The real Hello installer profile attributed many samples to rebuilding heads while composing exact
views. Captured Contracts views now share an immutable persistent root of already computed Language
heads. Ordinary admission starts with its existing mutable map; it changes representation only on
first capture. Subsequent memo entries remain local. New-evidence verification uses a separate copy
with empty memos, and active/provisional type plans are never shared. Validation proofs, witnesses,
logical gas ledgers and newly supplied evidence authority are not shared by this change.

All 2,019 library cases and 53 host cases pass. New ownership tests check shared immutable heads,
isolation of later evidence/memos, and forged evidence rejection after warming a view. Existing
canonical gas/receipt, cyclic, refinement, host-limit and disabled-reuse tests remain unchanged.

An eager persistent-map variant reduced initialization allocations but increased admission cost;
the retained variant converts only on capture. In 25-warm-up / 30-sample Hello runs, initialization
median was 95.95 versus 115.84 ms, with 172.30 versus 364.78 MB allocated. Admission was 36.02 versus
33.81 ms and 128.49 versus 128.22 MB allocated. The total lifecycle improves while this small
admission tradeoff remains explicit. All gas values (225,898) and final BlueIds match.

A short leaf probe initially suggested a regression, so it was not accepted as decisive. Four
alternating isolated JVMs with 4,096 warm-ups / 2,048 samples measured candidate medians 0.416/0.443 ms
and baseline 0.421/1.034 ms. Baseline's slower run persisted across quarters; do not discard it or
claim a leaf speedup from the average. Typical allocations were 1.298/1.300 MB for the candidate
versus 1.291 MB in the faster baseline run. Every measured gas sample, recorded amount and final
checkpoint identity matched. Retain the substantial installer allocation reduction; AWS and broader
workload qualification follow, rather than claiming all operations became faster.


Iteration 44 was built from both actual user checkouts and deployed as `d3a274be73b3…`. The published
Hello/Activation scenario passed again (consent 190.93 ms, Hello 121.52 ms, rejection 59.59 ms).
Its first concurrent profile completed 33.26 payments/s; the subsequent warmed profile completed
41.77/s, with leaf commit median/p95 249.16/433.30 ms and every aggregate correct. The preceding
retained warmed profile was 41.19/s, so no isolated AWS throughput gain is claimed for view sharing.


## 45. Compare text in metered Unicode blocks — keep the block variant

BEX equality and Contracts scalar ordering no longer allocate code-point arrays for both complete
strings. One package-private helper compares 64-scalar blocks, using JDK region comparison for equal
blocks and scalar ordering within a differing block. It preserves Unicode ordering, BEX's unequal
scalar-length short circuit, and the exact logical charge boundaries. Contracts still admits each
side separately; BEX still admits its two-unit block together. No gas schedule changes.

The first scalar-at-a-time candidate was rejected: long ASCII equality slowed from 4.88 to 13.23 µs,
despite lower allocation. The retained block variant passed 2,039 library cases, including scalar
and surrogate boundaries and the first unaffordable gas charge; all 53 host cases also pass.
The maintained JMH benchmark calls the public BEX API, uses distinct equal string storage, two forks,
three 200 ms warm-ups and three 200 ms measurements per case with allocation profiling.

| Comparison | Baseline µs / bytes | Retained µs / bytes |
| --- | ---: | ---: |
| ASCII 16, first mismatch | 1.258 / 12,256 | 1.273 / 12,272 |
| ASCII 16, equal | 1.244 / 12,256 | 1.271 / 12,292 |
| ASCII 4096, first mismatch | 3.114 / 44,840 | 1.271 / 12,284 |
| ASCII 4096, equal | 5.003 / 49,640 | 3.008 / 16,920 |
| Supplementary Unicode 4096, equal | 22.959 / 83,657 | 17.494 / 17,055 |
| Supplementary Unicode 4096, last mismatch | 22.872 / 83,453 | 15.540 / 17,056 |

These are bounded local microbenchmarks, not AWS throughput claims. The slight short-string overhead
remains visible; the substantial long-string allocation reduction justifies retaining the change.


## 46. Bound workflow expansion and carry exact child references across restart — keep

A parent can explicitly read a complete embedded collection, which is intrinsically proportional to
that collection. The host now bounds cumulative embedding reads within each attempt: 256 child visits,
262,144 structural values/index entries, 4,194,304 UTF-16 code units and depth 64 by default. Repeated
reads count actual work. Selected scalar reads do not materialize the other fields or siblings.
An external request exceeding these host bounds returns HTTP 413 / WORKFLOW_READ_LIMIT_EXCEEDED,
without publishing state or a command receipt. These are host refusals, not new canonical gas charges.
The same command can retry on a host with larger configured bounds. Parent propagation instead records
an explicit HOST_RESOURCE_LIMIT suspension; redrive does not execute the committed child again.

Qualification exposed a separate restart defect: copying an initialized child copied metadata with
an exact reference to its original body, but the restored parent lacked that supporting content.
`DocumentRuntime.Reader` now also supplies optional Language records by identity. The host consults
only checkpoints of children actually read, and only when the Language kernel asks for missing
content. Every record is verified. Provider access ends with the invocation, including captured
views; rejected raw records cannot poison a retry. Verified content needed by a successful copy
remains in the parent's checkpoint. Neither full child execution state nor unused checkpoint
contents are imported into the parent.

All 2,043 library cases and 58 host cases pass, including forged/missing evidence, retry, captured
view lifetime, copy/restore, cumulative reads, unchanged state/receipt, HTTP error classification,
and audited parent recovery. Strict formatting, compilation, Javadoc and qualification builds pass.

Four alternating local JVM runs used eight orders, two payments each, 128 warm-ups and 512 measured
payments. Baseline local/leaf-commit/completion medians were 0.632/1.082/3.048 ms and
0.634/1.098/3.113 ms; candidate medians were 0.643/1.098/3.093 ms and 0.642/1.098/3.102 ms.
Every total matched (26,880). Retain for correctness and bounded resource use, with a small observed
local overhead; this is not presented as a speed improvement. AWS qualification follows separately.


Iteration 46 is deployed from the actual checkouts as `987ebe478f48…`. Cold AWS qualification forced
session restoration after every mutation: bulk read refused without a state change; scalar read and
two whole initialized-child copies passed, including restoration of the copied references. Command
`8149a1ae-0e1d-42d8-809b-5bf034aa1e1b`; the temporary service stopped and primary health passed.
The existing 20,128-order / 80,513-document graph then passed 128 warm-ups and 128 measured payments
on eight active orders: 1.972 ms leaf runtime, 20.342 ms durable leaf commit and 145.667 ms completion.
Every aggregate matched. Command `5ad7c5e9-2de1-461b-a61a-47d5a69ea269`. This is consistent with the
previous 142.901 ms completion result; no large-graph speedup is claimed for this correctness change.

## 47. Pin and isolate scale qualification — keep

The original scale controller used the mutable `current` release path, reused result names and could
run alongside the normal worker service. It now verifies deployment/account/stack metadata, selects
the immutable release path, reserves the deployment/qualification lock, uses unique report names
and restores/health-checks the service on exit. Only synthetic `bench-*` accounts are accepted.
A hermetic controller check made no network calls and verified shell syntax, release pinning,
instance selection, restoration and the probe deadline. The live 80,513-document qualification above
used this controller successfully. These changes improve measurement/recovery reliability; they do
not change document execution semantics. A force-killed host still requires operational recovery.


## 48. Allocate canonical output buffers in proportion to the output — keep

A leaf JFR attributed a substantial allocation share to the fixed 16 KiB buffer created for every
canonical serialization. The buffer now starts at 512 bytes and grows only when filled, up to the
same 16 KiB cap. It drains before growing, with no shared mutable buffers or thread-local retention.
Canonical bytes, Unicode handling, identities, gas and exported formats are unchanged.

All 2,052 library cases and 58 host cases pass. Nine boundary cases cover empty/short/large output,
UTF-8 fragments, supplementary characters, signed 64-bit extremes and explicit flushes.
Four alternating JVM leaf runs (4,096 warm-ups / 2,048 samples) measured baseline 0.408/0.435 ms and
1.282/1.291 MB allocated, versus candidate 0.399/0.430 ms and 1.076/1.085 MB. All gas arrays, final
identities and totals matched. Retain the roughly 16% allocation reduction; no large speedup claimed.

Hello initialization (25 warm-ups / 30 samples) was 96.38 ms / 173.49 MB before and 96.97 ms /
175.39 MB after; admission was 35.03 ms / 128.29 MB versus 35.59 ms / 129.33 MB. Growing buffers cost
roughly 1% more allocation for this large workload. That measured tradeoff remains explicit.

The separately retained iteration-46 AWS release completed its first concurrent profile at 33.31
payments/s and its warmed profile at 42.99/s (512 operations across 16 accounts, all totals checked).
The warmed leaf commit median/p95 was 233.98/415.40 ms. The buffer candidate was not deployed for
those measurements and receives no credit for them.


## 49. Avoid discarded collections when publishing immutable Language heads — keep

The allocation profile also showed empty maps and lists being constructed and immediately replaced
while freezing schema projections. Mutable schema construction still gets independent containers;
immutable publication now constructs only the retained collections. Empty head field/label maps and
empty frozen schema maps use immutable empty values. No mutable schema instance is shared and no
validation, ancestry, enum or refinement rule is skipped.

All 2,052 library cases and 58 host cases pass. Four alternating leaf JVMs (4,096 warm-ups / 2,048
samples) measured baseline medians 0.394/0.399 ms and 1.077/1.081 MB allocated, candidate
0.381/0.394 ms and 1.023/1.022 MB. Gas arrays, final identities and totals match.
Hello (25 warm-ups / 30 samples) admission moved from 35.80 ms / 127.72 MB to 31.22 ms / 99.72 MB;
initialization from 94.96 ms / 172.30 MB to 85.47 ms / 170.49 MB. Retain the allocation reductions;
these bounded local measurements are not a production latency guarantee.

## 50. Make canonical APIs the onboarding path — keep

The README previously led its runnable examples and implementation map through the older Local-1
runtime despite introducing canonical execution at the top. It now presents a clear API boundary
map, canonical Language/BEX/Contracts examples, verified packages and the independent-document
host's explicit atomicity/proof differences. Local-1 usage and its incomparable timings have a
separate historical guide. Dated quality scores are marked historical rather than current claims.
The new README Java snippets were compiled with strict warnings and executed (BEX 5, counter 1,
no events); local documentation links were checked. The host README also clarifies that runtime
call timing includes child I/O requested from inside a workflow, while excluding durable publication.
No public API was removed or redirected, and no functional behavior changed in this documentation pass.


## 51. Qualify independent machines and cross-AZ recovery — keep

Added a bounded two-machine controller and workload with exact release/digest checks, an isolated
no-ingress peer in another AZ, a deployment lock, explicit cleanup, and a three-hour compute-stop
fallback. Two hermetic tests verify generated shell syntax, exact MYOS_TABLE configuration and durable
health admission. The first launcher attempt accidentally substituted part of the environment variable
name and started a memory-only peer. That attempt was cancelled, restored and deleted; its results are
rejected. The corrected run used the same DynamoDB table from eu-west-1a and eu-west-1b.

Run cluster-b8378a1751934cd5, release 68eed31031466dc6da2971af30d91fca79b85fb0dca8550ebdd0f2214b77df36:
256 concurrent submissions represented 128 unique commands across four accounts / 28 documents.
Every duplicate receipt and final graph view matched across machines. Every leaf, order and root
aggregate was checked; conflicting payload reuse failed. Call medians were 161.51/185.51 ms and
submission phases 3.235/3.223 seconds; duplicate-heavy qualification is not a throughput capacity test.

With both services stopped, a standalone primary process then committed work and exited while its
outbox remained pending. Only the other-AZ service restarted; it completed all initialization and
payment propagation, root paidTotal 42. The primary was restored healthy and the temporary stack/disk
were deleted. This qualifies cross-machine persistence and recovery, not network-partition behavior
or prolonged AZ failure. The companion host documents the complete method and remaining boundaries.
The same deployed release passed published Hello/Activation (consent 178.64 ms, Hello 106.78 ms,
rejection 47.45 ms). No runtime change is attributed to this qualification iteration.

## 52. Give each host read its own physical-work allowance — keep

A repeated-projection benchmark exposed an existing correctness bug: independent Session.read calls
shared one cumulative Language work counter. A sufficiently long-lived read-only session eventually
failed with HOST_INCOMPLETE; a failed oversized read also poisoned a subsequent small selected read.
The faster projection candidate merely reached the old lifetime limit sooner. Partial benchmark
results from that discovery are rejected.

ContractProcessor.read now starts a fresh physical-work/demand interval. It retains verified content
and semantic memo entries, and does not reset execution gas or alter workflow evaluation. Public
Contracts and DocumentRuntime documentation explicitly describes the per-read allowance.
Two regression cases cover 10,000 independent reads through both APIs and a single exponentially
expanded shared-reference read followed by a tiny selected read. Both cases fail against the saved
old binary and pass against the fix. The oversized read still fails with the configured limit.
This is a correctness repair, not removal of a host resource bound.

## 53. Keep one ancestry set during projection — keep

Projection previously copied every ancestor key into a fresh HashSet at each nested object/list.
It now owns one traversal-local set and removes the current key in a finally block. Equal sibling
subtrees still expand independently; only references to active ancestors are collapsed. Added focused
public-API tests for repeated object/list branches and exact cyclic references.

All 2,056 library cases pass. A clean JMH comparison applied iteration 52 to both baseline and
candidate (two forks, three 200 ms warm-ups and three 200 ms measurements per case, GC profiler).
Depth/width 4/1: 2.181 → 1.769 microseconds, 7,788 → 6,088 B/op;
4/32: 62.999 → 51.205 microseconds, 156,386 → 105,434 B/op;
16/1: 7.962 → 5.472 microseconds, 23,654 → 14,342 B/op;
16/32: 281.489 → 204.645 microseconds, 655,721 → 373,771 B/op.
Every case completed all six measurement samples. Retain the 22–43% allocation reduction for these
projected shapes. This does not establish the same percentage improvement for business execution.


## 54. Bound projected output during construction — keep

Added explicit ProjectionLimits and bounded read overloads to Contracts.Session and
DocumentRuntime.Session. Values include containers, scalar/undefined values and object entries;
text counts UTF-16 code units in keys and text values; root nesting depth is zero. Limits reject
before adding the next output value. Literal Contracts sections are compacted directly instead of
first exporting their entire trees. Ordinary read(pointer) retains its existing behavior and
independent Language work allowance.

A Reader can optionally supply projection limits for the local data it receives. The durable host
uses the remaining attempt allowance for workflow collection/ancestor reads and for cold child/API
projections. Cached projections are checked before copying. Resource refusal preserves the host's
VIEW_LIMIT_EXCEEDED / WORKFLOW_READ_LIMIT_EXCEEDED policy and never publishes a receipt or state
change. This bounds output construction, not all verifier/provider/runtime heap allocations.

All 2,062 library cases and 61 host cases pass. New checks cover exact inclusive bounds, Unicode,
typed/cyclic references, literals, absence, an exponentially shared reference graph, refusal before
the Reader callback, cold restoration, unchanged state and successful selected-read retry.

Ordinary unbounded-projection JMH (same two-fork/six-sample method as iteration 53) measured:
4/1 depth/width 1.790 → 1.798 us, 6,076 → 6,120 B/op;
4/32 51.906 → 53.019 us, 105,434 → 109,286 B/op;
16/1 5.324 → 5.518 us, 14,327 → 14,750 B/op;
16/32 201.127 → 205.602 us, 360,708 → 360,691 B/op.
Keep the explicit resource protection with this measured roughly 0–4% microbenchmark time cost;
no speed improvement is claimed. No heap reservation or arbitrary-workflow constant-time promise
is implied.

The separately deployed iteration-53 release is
57fdf9d5b902ae6062d35b80a8f705976c3602e9d80457a5b23fb96299ee5b96.
It passed the cold workflow-read/reference-copying qualification (SSM
5dc3c21c-1891-4217-a130-ee16ec4942c2). The new construction limits are not credited to that older release.


## 55. Stop oversized optional-cache admission before full projection — keep

The host's bounded projection cache previously expanded a complete new document, encoded it, then
usually discarded it if the JSON exceeded 64 KiB. It now constructs at most 65,536 values / UTF-16
units and 256 output levels while considering a cache entry. An output-limit refusal omits the cache;
the verified checkpoint remains authoritative. The exact serialized-byte check still applies to
candidates that fit those structural bounds. Explicit eager comparison mode is unchanged.

All 62 host cases pass with the 2,062-case retained library. A compact graph containing 18 levels of
shared references (262,144 logical leaf occurrences) still commits without a projection cache and
refuses a requested oversized view without changing state. Both saved baseline and candidate pass
that semantic case; no newly supported semantics are claimed.

New qualification-only HostInitTiming measures admission, initialization, checkpoint construction,
optional projection and publication to MemoryStore, excluding readback and AWS I/O. Four alternating
JVMs, each with 25 warm-ups and 30 Hello samples, measured baseline medians 184.75/184.98 ms and
351.79/351.30 MB allocated, versus candidate 141.80/135.07 ms and 277.21/277.38 MB. All gas arrays and
final identities match. Retain this reduction of discarded work.

The accompanying eight-order/two-payment/512-operation runs measured baseline leaf medians
0.6289/0.6255 ms and complete propagation 3.026/3.060 ms; candidate 0.6272/0.6293 ms and 3.085/3.069 ms.
All 25-document totals matched (26,880). This supports a small ordinary-path cost, not a payment
speedup. Large initialization improvement is not attributed to remote storage latency.

Separately, deployed iteration 53 passed another 80,513-document qualification: 20,128 stored orders,
eight active orders, 128 warm-ups plus 128 measured payments; medians 2.090 ms leaf runtime,
20.036 ms leaf commit and 141.663 ms through order/agreement. All aggregates matched, final total
103,572. SSM 5c2e8aa6-d830-421d-b587-a62e7238283b; the normal service was restored healthy.


## 56. Keep optional cache work from vetoing valid execution — keep

A follow-up probe configured Language work limits of 1,000, 5,000 and 10,000 steps. The document's
mandatory admission/initialization/checkpoint work completed, but optional projection inspection
exhausted its separate allowance and prevented host publication. This was reproduced against the
saved iteration-55 binary. Depending on where the boundary was reached, the library exposed
HOST_INCOMPLETE or OperationLimitExceeded.

The bounded-cache branch now treats those physical-work refusals like output-limit refusals and
omits the cache. The catch encloses only optional session.read; execution and checkpoint creation
remain outside it, and all other failures still propagate. The same three probes now return APPLIED.
All 65 host cases pass, including the compact shared graph at the three low allowances and default
allowance. No ordinary-path speedup or new exception-code normalization is claimed.

The actual iteration-55 deployment
5b88644bca7e1a37dcd43be9e8afe1857aaa32d6ddc2f22fd7bc88e74a7dc586 passed published Hello/Activation
(approve 187.43 ms, Hello 144.84 ms, rejection 50.80 ms) and cold workflow reads/reference copying.
Those single cold action timings are variable and do not show an AWS action-latency gain from the
cache change. Workflow qualification SSM 5b6a5742-aacc-4881-9bc6-281d3e2b0d85 ended at version 4,
count 3 with all checks passing. The low-work-budget fallback is retained locally after that release.


## 57. Reuse normalized immutable recipes — keep

Language composition previously flattened and deduplicated even a single already-normalized recipe.
It now reuses that recipe when no new requirements are introduced, and reuses its immutable parts
when only requirements change. A single exact node takes a direct path. Multipart composition
preserves the last representation of each identity by collecting backwards and reversing once,
without quadratic front insertion. Requirements retain first-occurrence order; mutable inputs are
still snapshotted. Three focused invariant tests and all 2,065 library / 65 host cases pass.

Four alternating warmed JVMs measured leaf medians of 0.3898/0.3920 ms and 1,022,248/1,031,272 bytes
before, versus 0.3798/0.3858 ms and 963,144/970,708 bytes after. Hello admission allocation fell from
100.12/100.40 MB to 90.28/90.98 MB; initialization allocation was essentially unchanged. Hello
initialization time overlapped (87.36/96.42 ms baseline, 91.17/93.54 ms candidate). Gas arrays,
results and final identities match throughout. Keep the simpler path and roughly 6% leaf allocation
reduction; do not claim a general initialization or AWS throughput speedup.

Evidence: recipe-composition-{leaf,hello}-{baseline,candidate}-*.jsonl and summary.log in the ignored
qualification reports. No shared publication or canonical semantics changed.


## 58. Public, scoped native Contracts steps — keep

Canonical Contracts had internal native extension hooks but no public step registration. New
NativeStep supplies an immutable exact type/semantics binding, declared fields and tariff, subtype
opt-in, and a thread-confined callback. Contracts and DocumentRuntime builders accept registrations;
package loading alone still installs no code and does not satisfy unsupported protocol requirements.
The callback exposes immutable inputs, pinned reads, read-only BEX, gas and buffered patches/events,
termination and explicit rejection. Its authority closes and releases callbacks on every exit.
Native Java remains trusted deterministic host code, not a sandbox or CPU-time limiter.

This exposed two existing internal issues. Native reads projected only the local body, bypassing
DocumentRuntime's pinned child reader. They now use the logical view and the same bounded host-read
path as BEX. Host/access failures retain their original category instead of becoming RUNTIME_FAILURE.
The live native meter's remaining allowance also now includes intervening BEX charges. Reinstating
the saved old internal classes made both regression tests fail: a selected child read returned the
local placeholder instead of the host's value, and BEX charges did not lower remainingGas().

All 2,075 library cases and 65 host cases pass; strict compilation, formatting and Javadoc pass.
Ten public-API native tests cover mixed BEX execution and event listeners, portable bindings, changed
semantics rejection, missing/conflicting registrations, immutable builders/data, gas exhaustion,
handler failure and explicit rejection, thread/lifetime boundaries, pinned reads and output limits,
subtype opt-in, and termination after add/remove effects. See docs/NATIVE-STEPS.md for runnable API
patterns. This supplies steps, not the still-missing native channel/admission/timeline services.
No performance improvement is claimed for this functional addition.

Separately, the deployed iteration-57 release
928431a3cdca51edd4d5c6bfed24d16aa8815e33267501ff93c0a3cc89d0fdd3 passed another large-graph probe:
20,128 orders, 80,513 documents, eight active orders, 128 warm-ups/128 measured payments; 2.077 ms
leaf runtime, 20.042 ms leaf commit, 143.279 ms completion. All totals matched (114,324), SSM
87a8ad3e-077b-4ed4-9ab2-cd3f285ba26d. Published Hello/Activation also passed (SSM
b88e9cd5-0662-488f-9713-536fb39a8c89); cold approve/Hello/reject timings were 242.97/118.36/73.78 ms.
The native-step addition is not credited to that deployed release.

The previous iteration-55 warmed concurrent profile completed 512 checked payments at 39.24/s,
248.60 ms median / 441.14 ms p95 leaf commit and 10.15 ms median runtime. Its first run was 33.43/s;
this does not improve on the older warmed 42.99/s sample. JVM history, eviction pressure and remote
I/O remain material; the local allocation/cache gains are not presented as a stable AWS throughput gain.


## 59. Pin and fence concurrent-load qualification — keep

The concurrent HTTP load launcher previously checked only the instance and successful deployment
command, read examples through the mutable current symlink, and did not hold the deployment lock.
It now verifies the instance/table/bucket and artifact identity before launch, holds the same lock
as deployment, checks the running process classpath against that exact immutable release, checks
healthy durable mode and all worker/cache settings, pins example paths, verifies the tool digest,
and rejects a process restart during measurement. Profiling uses a bounded recording and an exit
trap. Reports carry both tool/controller hashes and process RSS/high-water/thread samples.

Five hermetic launcher tests pass (the prior cluster checks plus generated shell syntax, immutable
release/lock/profile ordering, target mismatch and live release/capacity refusal). An AWS smoke run
on deployed iteration 57 passed one real payment and all aggregates; it recorded RSS 476,924 →
495,024 KiB, 37 → 40 threads. This is a functional launcher check, not throughput evidence. SSM
9d41686a-c1bc-4456-87ed-0785a0cc9148. Retain stronger evidence isolation before comparing cache sizes.
No processing semantics or runtime performance improvement is claimed for this tooling change.


## 60. Double session-cache capacity — revert the setting; keep evidence

Compared 256 entries / 512 MiB estimated capacity with 512 entries / 1,024 MiB on the same deployed
artifact, d853de018a57b3c4ec86f57f35e3471f5174471fdb53f8894934717da7b8822f. Four fresh JVM phases
used baseline/candidate/candidate/baseline order, each with two profiled 16-account, 512-operation
runs. All eight runs checked every aggregate. Workers, hardware, examples and client/controller
bytes were fixed; process identity/configuration were verified by the iteration-59 runner.

Warmed baseline throughput was 39.81/44.10 payments/s; larger-cache throughput 40.54/39.26/s.
Warmed leaf-commit medians were 251.02/211.97 ms versus 227.10/229.75 ms. Larger capacity reduced
measured session loads from 597/597 to 384/380 and evictions from 551/555 to 236/236, but did not
establish an end-to-end throughput gain. First-run throughput also overlapped: 28.59/31.01 versus
30.08/28.55/s. Retain the smaller capacity; the final phase restored it explicitly.

Observed warmed process RSS was 1,127,880/1,119,148 KiB at baseline and 1,076,820/1,060,224 KiB for
larger capacity. These are whole-process samples including setup/JIT history, not a retained-heap
measurement or hard cache bound. No OOM/restart occurred. The result points away from cache capacity
alone as the dominant throughput constraint; it does not prove that cache size is irrelevant to
other workloads. Raw results, settings, command IDs and memory samples are in capacity-60-* reports.


## 61. Qualify and clarify pinned workflow snapshots — keep

Two deterministic host tests now isolate the workflow read contract. A child can commit a newer head
while its parent still reads the previously published child version. Only delivery advances that
parent's link. If another host advances the parent link while a workflow is speculative, the STATE
compare-and-set rejects publication and the complete workflow retries: the read counter increments
once, the newer pinned value is observed, and retrying the command returns the same receipt.

Both tests passed before changing code. Removed an always-empty child-read-check list from Core and
clarified the public Reader contract: publication fences the parent's complete embedding view;
immutable historical children do not require comparing their current heads. This is a cleanup and
qualification of existing behavior, not relaxed consistency or a claimed speed improvement.
All 2,075 library tests and 67 host tests pass, plus five hermetic qualification checks.

A separate two-document initialization probe found a real remaining gap. Canonical Contracts lets
parent initialization read child.ready=true after the child's initializer; the document-local host
returns FAILED / INVALID_RECORD because it detaches children and initializes the parent first.
The probe and design notes are retained under reports/initialization-parity. This has not been fixed
by iteration 61. Existing large payment measurements do not exercise that dependency and remain
valid for their stated workload. The next iteration must address fresh-subtree initialization and
same-workflow child reads without loading existing siblings or silently changing published identities.


## 62. Atomic initialization of fresh child trees — keep as an explicit preview

Confirmed and reproduced the edition-1 parent-initialization gap with canonical Contracts as oracle.
Added opt-in `initializeFreshSubtrees()` and the distinct `blue.document-local/2-preview` checkpoint
edition. Only fresh children enter the temporary canonical scheduler; initialized members are split
into compact, independently restorable checkpoints. Prepared attachments retain ordered child event
history and nested attachments. Host publication restores them without executing initialization again,
sets the initial link/dispatch cursor to version one, and does not enqueue events already consumed by
the parent. The existing default edition and deployed service have not changed behavior.

Fixed two cross-scope hazards in the prototype: fresh child reads cannot consult the parent's storage
reader, and child-local writes cannot become parent embedding replacement effects. The ordinary
single-document path still skips unused completion records; temporary child barriers retain the
records needed for canonical event ordering. Initialization failures roll back the complete fresh
attempt. Authored processor-state rejection remains intact. The bounded host protocol explicitly
rejects more than 16 new children, shared/cyclic fresh members, imported graphs and list-indexed
attachments rather than duplicating members or shifting child addresses silently.

All **2,082 library tests and 71 host tests** pass. Seven new library cases and four host cases own
initialization order, same-workflow reads, event history, nested restore, rollback, edition isolation,
structural limits, idempotent creation and restart. Strict formatting/compilation/Javadoc pass.

Four alternating JVM leaf runs (4,096 warmups, 2,048 measured calls each) compared the retained-61 jar
with the preview-enabled candidate. Baseline medians: **0.378875/0.380750 ms**, allocation
963,856/961,944 bytes. Candidate: **0.372438/0.384333 ms**, allocation 958,608/963,432 bytes. All leaf
gas, checkpoint snapshot identities and final totals matched. No material leaf slowdown or speedup
is established; this iteration fixes behavior while retaining the fast path. This is not a claim
that initializing 16 children costs the same as initializing one.

Decision: retain the explicitly selected preview and host support, with no implicit migration or
switch of existing AWS documents. The current service still runs iteration59/edition1; fresh-tree
AWS qualification and version-aware service configuration are separate follow-up work. Full native
Coordination/MyOS parity, existing shared attachments and distributed cycles remain open. Evidence:
`fresh-*-62.log`, `reports/initialization-parity/leaf-*.json`, focused test XML and the baseline probe.


## 63. Version-aware durable execution — keep; AWS checks pass

Added an explicit host RuntimeCatalog. Runtime manifests list supported local editions and the
creation default. Stored checkpoints select their original runtime; even a new child attached by an
old parent remains on the parent's edition. New roots can use the fresh-subtree preview while the
same process continues serving existing edition-1 accounts. Package/native configuration is pinned
once and shared across the selected runtimes. No silent migration, downgrade or unknown-edition
fallback is allowed. The library exposes `DocumentRuntime.edition()` for accurate host reporting.

Five host cases qualify mixed-edition publication, new children under old parents, restart with a
changed creation default, unsupported execution refusal, manifest rejection and HTTP health fields.
All **2,082 library tests and 76 host tests** pass. Seven hermetic launcher tests include generated
whole-deployment shell, exact-edition health refusal, release locking and profile/receipt agreement.
The deploy script selects an artifact-bundled manifest, and the ordinary scale probe now passes that
same configuration to its separate JVM. Other specialized tools remain explicitly scoped to their
own configurations; no preview performance claim is inferred from them.

Decision: retain explicit version selection rather than reinterpret existing checkpoints. AWS
rollout/testing is the next qualification step; no new cloud result is claimed here yet. An older
binary lacking edition2 cannot process new preview records, so rollback compatibility is explicit.


AWS qualification for iteration63: deployed artifact
`d243a5be67a2b77164eb34b1547fb482869ac5caa15b789b2dde983b2dd9698a`, SSM
`66cb9a5d-5b19-4b2d-85e9-b059cf6d2526`, healthy with creation edition2-preview and restoration editions
1/2-preview. Fresh initialization preserves `ICP` ordering, retains the child's initial event in
history, and a later child operation produces `ICPC` exactly once; retry returns its original result.
Dynamic add-and-read passes. SSM `736ef6a2-5f61-4dc0-8376-06a44e977f95`, report
`reports/d243a5be67a2-fresh-cd27225a.json`. Single cold create/leaf/completion were 578.96/47.56/180.17ms;
these are smoke observations, not warmed benchmark evidence.

The existing edition1 tree with 20,128 orders/80,513 documents passes under the same mixed-edition
release: 128 warmups +128 measured payments, eight active orders, checked paid total125,076. Median
runtime **2.056197ms**, leaf commit **20.828583ms**, full propagation **153.338149ms**. SSM
`f340f0b2-9171-4f87-9169-14032a5d7361`, report `reports/d243a5be67a2-probe-5b76b0e1.jsonl`.
The service was restored healthy. These measurements qualify old-record compatibility; they are
not mislabeled as a tree initialized under edition2. A separate fresh-edition scale run follows.


Fresh-edition scale follow-up for63: the separate account `bench-fresh-128-iteration63` created128
orders with3 payments each (513 documents) through real workflows in15.187s. After128 warmups,
32 measured calls over128 active orders returned median runtime1.568367ms, leaf commit23.754161ms,
full propagation139.460741ms, checked paid total6,720. SSM
`a0ca3768-57d2-4b6b-af5e-9f34a729c675`, report `reports/d243a5be67a2-benchmark-cbc2a002.jsonl`.
Its working set differs from the eight-active-order legacy probe, so this is functional/scale
qualification, not an apples-to-apples speedup claim. The service was restored healthy.

## 64. Resumable scale preparation and stored-edition reporting — keep

Separated authored graph preparation (`populate` / `--prepare-only`) from timed payment probes.
Preparation uses the same deterministic create/add command IDs and real BEX workflows. A restart
reuses committed receipts, creates only missing orders and validates the final initialized count.
Changing an already-used order definition is rejected; no payment operation is hidden in setup.
The ordinary combined benchmark remains available. Reports now identify the actual root's stored
local edition, independently of the service creation default; HTTP head/read responses do likewise.

Two preparation tests plus existing mixed-edition checks pass: **2,082 library tests,78 host tests**.
Eight hermetic launcher checks verify profile propagation, separate populate/probe commands, exact
release locking and automatic service restoration. A real built CLI smoke run prepared one order/
one payment (three documents), reported edition2-preview, and issued no payment operations. Deployment
readiness polling now quietly retries empty startup responses instead of printing JSON tracebacks.

Decision: retain setup/measurement separation and explicit provenance. This is qualification and
observability work, not a new runtime speedup. Larger fresh-edition preparation is the next AWS step.


Iteration64 deployment: artifact `fb1b0c9a91612e6a9a18633399c847f2892d4ea9619e4d55e1f061d818201131`,
SSM `1d5bdded-53e7-44ff-aaf8-a55eefa0f96c`, healthy with both local editions supported. A separate
fresh-edition20,000-order preparation is active under SSM `d84dece7-8dfa-4a97-800f-1701db84b572`.
It has no timed payment phase. Latest progress for the status report:6,656 orders /492.4seconds;
this is an in-progress preparation observation, not a completed large-tree qualification.


## 65. Public native channels and detached callback values — keep

Added explicit `NativeChannel` registration to canonical Contracts and both DocumentRuntime editions.
Handlers decide acceptance; ordinary checkpoint deduplication, operation envelopes and workflows
still own processing. Bindings preserve exact type/semantics/tariff identities, explicit subtype
opt-in, immutable builder snapshots and declared peer access. Scoped callbacks are read-only and
share gas. A native channel does not imply Timeline, admission/signature verification or a complete
Coordination protocol implementation.

The first integration tests exposed an existing public NativeStep defect: event/call/evaluation
results could expose internal ExactView objects instead of Java maps. The focused regression fails
against retained iteration64 with `RUNTIME_FAILURE / ClassCastException`, then passes with the new
value boundary. NativeStep and NativeChannel now materialize detached immutable maps/lists/scalars
only on access, reuse repeated input snapshots within a callback, and release the snapshot cache
when the callback closes. Materialization checks ProjectionLimits.defaults() during construction;
compact shared-reference events cannot expand unboundedly. Access errors preserve their category
instead of becoming generic callback failures. This also catches oversized host-read results.

Ten native-channel tests and the existing native-step suite cover ordinary/operation events,
ignored/duplicate inputs, exact restore bindings, builder mutation, subtype opt-in, peers, thread and
lifetime rules, gas/Java failure, bounded event expansion and retry after refusal. The public-value
regression checks nested maps/lists, BigInteger values, immutable BEX results and missing values.
Initial test failures also identified two fixture mistakes: peers require lists, and `items` is a
reserved Language field rather than an ordinary payload property; neither was fixed by weakening
production validation.

Full strict builds pass: **2,093 library tests and 78 host tests**, no failures or skipped cases,
plus formatting/Javadoc/qualification compilation. Existing launcher code is unchanged from64,
whose eight hermetic checks passed. Decision: keep the API addition and correctness/resource fix.
No execution-speed improvement is claimed for this iteration. The AWS release remains64 while its
fresh-tree preparation holds the release lock; no deployment is attempted during that workload.


## 66. Select native event fields before projecting — keep

Added `context.event(pointer)` to native steps and channels. A rule checking `/kind` now reads that
field directly from the admitted event view, then materializes only the selected value under the
same output bounds. Complete `event()` remains available. Both return detached immutable public
values; neither exposes an internal view. Missing fields/list positions return undefined, malformed
RFC 6901 pointers retain `INVALID_POINTER`, and escaped keys have regression coverage. A compact
shared payload that correctly exceeds the full-event output bound now passes a channel that reads
only `/kind`, with its ordinary workflow result checked.

JMH isolates callback-value construction, not whole workflow execution. Two fresh forks per case,
three 500ms warmups and four 500ms measurements per fork, fixed 128–512MiB heap, one thread:

| Payload rows | Complete event then get kind | Select /kind | Complete / selected allocated bytes |
| ---: | ---: | ---: | ---: |
| 16 | 7.199us | 0.206us | 11,880 / 2,388 |
| 1,024 | 424.130us | 0.237us | 734,604 / 2,560 |

All four cells contain both complete forks/eight measurements. The initial harness mistakenly reused
one physical Language work budget across many invocations and eventually refused work; those
partial results were discarded. The corrected harness performs the same per-invocation reset as
the processor. The Gradle benchmark task now defaults to JMH fail-on-error even with custom options,
unless explicitly overridden. No large AWS latency improvement is attributed to this microbenchmark.

Full strict builds pass: **2,095 library tests and 78 host tests**, no failures or skipped cases.
Decision: retain selective reads and fail-fast measurement tooling. This avoids unnecessary work
without weakening full-event output bounds or changing canonical business execution/gas.


## 67. Render diagnostic paths only on rejection — keep

A fresh Hello host-initialization profile showed eager diagnostic pointer construction among the
largest allocation sites. Validation previously concatenated and escaped the full ancestor path
for each visited field, including successful validation. Diagnostic paths now share immutable parent
links and render the RFC 6901 string only when enriching a failure. Traversal order, validation,
work/gas accounting, failure category and path/phase semantics are unchanged.

Five explicit ingress regressions cover escaped fields, list items, inline type definitions, root
failures and source-construction failures. Complete diagnostic objects captured from retained66
match candidate output byte for byte. The unchanged published Language corpus passes, as do all
**2,100 library tests and 78 host tests**, with strict compilation/format/Javadoc checks.

Four isolated JVMs alternate baseline/candidate/candidate/baseline. Each has25 warmups and30 measured
Hello creations; measurement includes admission, initialization, checkpoint and MemoryStore
publication, excluding readback/AWS. Median elapsed ms and allocated bytes per creation:

| Run | Variant | Milliseconds | Allocated bytes |
| ---: | --- | ---: | ---: |
| 0 | Baseline66 | 135.030 | 266,876,276 |
| 1 | Candidate67 | 130.440 | 249,946,684 |
| 2 | Candidate67 | 133.989 | 250,252,884 |
| 3 | Baseline66 | 145.812 | 266,760,388 |

Every gas array is identical (225,898 per initialization); every initialized local identity is
`G6ZFc4QGrPe33hGS6xEJJrnNrqcRtM6NnpdJXjwpk9nt`. Allocation falls about6.3%. Timing is noisy and does
not establish a broad latency guarantee. Decision: keep the smaller allocation cost and simpler
success path. Hello initialization still allocates roughly250MB and needs further work; no claim
that this fixes the entire clause-installation cost. The JFR run was diagnostic only; the paired
measurements do not have profiling enabled. Large trace files remain outside the published projects.


## 68. Qualify fresh-edition scale and pin growth measurements — keep tooling; fix identified gap next

The iteration64 fresh-edition preparation completed20,000 orders/80,001 documents through authored
workflows in2,270.282s. SSM `d84dece7-8dfa-4a97-800f-1701db84b572` succeeded and restored a healthy
service. All orders initialized. This workload is separate from the old edition1 tree.

Matched sequential payment probes on the unchanged deployed64 artifact use128 warmups,128 measured
calls, fixed seed and8 active orders. Both check every root aggregate and actual stored edition:

| Stored orders | Runtime median ms | Leaf commit median ms | Full propagation median ms |
| ---: | ---: | ---: | ---: |
| 20,000 | 3.600474 | 22.576331 | 253.092288 |
| 128 | 2.041697 | 19.911978 | 122.394286 |

Large SSM `7bbec6b5-37b0-4bd0-a671-aff475eedec7`, small SSM
`b4ab9d66-3904-4c55-80be-3a11ef0a2d17`; both succeeded and restored exact-edition health.
The large run has900 store gets versus898 small, but far more get time. Read-only stored-record
inspection found the fresh parent's snapshot carries one `edgeSerials` entry per added order:
128 entries/2,322 JSON bytes versus20,000 entries/408,890 bytes. Local content remains71 records in
both cases. The old edition1 large root lacks this accumulation. This is a real fresh-preview
locality defect, not evidence of constant cost. Root total10752/17472 respectively is checked.

Growth qualification now selects the deployed runtime catalogue instead of hardcoded edition1,
records the stored edition/cache policy, and refuses comparisons across different editions. Its
launcher validates the current release under lock, pins the qualification JAR and restores verified
service health on every exit. Nine hermetic checks pass, including execution of the generated trap
with local stand-ins. Local real-workflow smoke runs pass in both editions (two warmups/four measured
additions per graph). No ordinary build launches AWS or qualification workloads.

Decision: keep the measurement fixes. Library/host production code is unchanged from67; the passing
suite remains2,100 library tests/78 host tests. Next work must externalize redundant embedding
lifetime bookkeeping without weakening stale-generation rejection or silently reinterpreting stored
edition2 checkpoints. No fix for the newly identified large-tree gap is claimed in this iteration.


The corrected growth probe also completed on deployed64, SSM
`a0f48668-d2aa-47ba-b6db-998938ed8e7a`, tool SHA
`e93a8f0a9d02de5b8f053cb245169207e18a9ee995c7bc96d67b020e118da5f8`.
The large graph now has20,064 orders/80,257 documents. Interleaved48 measured additions per graph
(after16 warmups) returned small/large median engine18.165/85.907ms, commit73.669/211.205ms,
completion78.131/251.265ms. All initialization totals passed; the service recovered healthy.
Its preceding repeated large payment probe returned3.597/22.174/243.215ms runtime/commit/completion,
confirming the earlier slow large-preview observation. This strengthens the need to remove retained
per-path internal generation metadata; it does not claim that correction is already deployed.


## 69. Move cross-command embedding lifetimes to the durable host — keep explicit preview

Edition3 (`blue.document-local/3-preview`) removes retained per-path generation counters after
splitting an initialized fresh graph. Internal generation checks remain within the temporary graph;
the host's immutable Link source/generation/version owns subsequent stale-delivery protection.
Defaults, edition1/2 restore and canonical Contracts are unchanged. This changes checkpoint semantics
and therefore is a separate explicit edition, with no automatic upgrade.

`DocumentRuntime.migrate` verifies an edition2 checkpoint, rejects undetached graphs, preserves local
business/channel state and clears only redundant counters. The host publishes it with conditional
STATE, history, command receipt and outbox updates. It preserves the embedding index and pending
source versions. Migration fingerprints are separated from existing ordinary fingerprints.

A deterministic regression found a peer could complete the same migration between the first receipt
lookup and STATE read. The initial candidate rejected the already-upgraded checkpoint. The corrected
host rereads the migration receipt in that case; the regression now passes. Other tests inject a
concurrent business commit, an old-child delivery racing with replacement, and a lost commit response.
Cold restart delivers pending events exactly once after migrating both parent and child.

Full strict builds pass: **2,104 library tests and 84 host tests**, no failures/skips. Ten hermetic
launcher checks pass. The standalone migration tool also passes against eight actual initialized
orders: eight counters become zero, snapshot512→393 bytes; complete snapshot comparison confirms
only the counters changed, with body identity and link root preserved. That smoke timing is not a
performance comparison. Library tests repeat128 attachments and cold restore in both editions:
edition2 retains128/129 counters, edition3 retains zero while all child-init reactions remain correct.

Decision: retain the explicit protocol, migration path and qualification tools. No AWS speedup is
claimed yet. The deployed release is still64 until this candidate is installed and measured on the
same large graph. Further native protocols, MyOS interfaces and production qualification remain open.

Iteration69 deployment completed with exact edition1/2/3 health. Artifact `eadc71663cf40b5f43b773e9ff917d5706c5080c2c11eca01d95b2cc068bb0dd`, SSM `36790f70-63bb-4f1e-9d5f-cff21a4e3969`. Stored editions are unchanged; same-binary pre/post migration measurements follow.


## 70. Qualify both fresh-child editions and the large migrated graph — keep

The four existing durable fresh-initialization scenarios now run for both edition2 and edition3.
Canonical parent reads/events, initialized dynamic children, immutable child history, no duplicate
initial delivery after restart and failure rollback all pass. The full host build passes **88 tests**;
library production code remains69 with2,104 passing tests. No production change or new deployment is
needed for this test matrix. Decision: keep the expanded compatibility coverage.

Same-binary AWS pre/post migration measurements use deployed69. Until their results are collected,
no large-tree latency improvement is asserted for the new edition.


Same-binary AWS migration qualification completed. Large/small roots changed only redundant
`edgeSerials` in their complete committed snapshots (20,064→0 and128→0 counters). The host index,
local body identity, previous historical checkpoint and retry receipt remained valid. Compact large
snapshot373,056→401 bytes; small2,462→397 bytes. Children remain in their original edition.

| Root edition | Small completion median/p95 ms | Large completion median/p95 ms |
| --- | ---: | ---: |
| Before, edition2 | 130.121 /146.646 | 254.458 /297.731 |
| After, edition3 | 147.580 /176.735 | 120.036 /134.202 |

Same release69;128 warmups/128 measured payments, eight active orders, every aggregate checked.
Large runtime/leaf commit medians fall3.493/23.296→1.982/19.931ms. Small runs do not show a speedup.
The large size penalty disappears for this bounded-working-set case, not for arbitrary scans or
contention. The subsequent interleaved real-addition qualification is still in progress.


Interleaved real additions also pass:16 warmups/48 measured calls per graph, small/large completion
91.049/101.084ms; engine22.923/30.667ms; commit86.410/94.697ms. Large calls make1–3 cold index reads
(median7.279ms), small calls none. Both commit once with no retries or queue scans. This explains most
of the remaining gap and is indexed lookup, not sibling-wide processing. Direct post-workload reads
confirm zero counters in both roots; snapshot398/401 bytes and compressed STATE5,150/5,209 bytes,
small/large. The large tree has20,128 orders/80,513 documents. Growth SSM `0b0dde42-e2da-4531-9598-224617a9f702`
succeeded and restored healthy service. The preceding repeated large payment run returned140.832ms
completion, supporting a real reduction without a fixed latency guarantee. Decision: keep69's
protocol/migration and70's expanded qualification. Full MyOS/native parity is still incomplete.


## 71. Retain immutable BEX step snapshots — keep

Compute and emit results are already privately frozen by BEX. Contracts now retains those snapshots
instead of deep-copying them into mutable values; patch-step result wrappers also freeze their owned
contents. A later step can read an earlier snapshot without rebuilding its tree. No callback views
are newly detached: HostValue handling stays as before, and public native callbacks retain their
separate bounded immutable-value boundary.

The new effect-boundary test captures a nested document value, changes the document, emits the old
value, reads the prior patch and emitted result, and cold-restores the final snapshot. All canonical
fixtures and **2,105 library tests /88 host tests** pass, including strict builds.

Alternating isolated JVMs (25 warmups/30 measured Hello host creations each) measured:

| Run | Variant | Median ms | Allocated bytes |
| ---: | --- | ---: | ---: |
| 0 | Baseline69 | 130.702 | 252,491,748 |
| 1 | Candidate71 | 127.418 | 250,411,060 |
| 2 | Candidate71 | 127.861 | 250,727,156 |
| 3 | Baseline69 | 129.107 | 252,256,276 |

All gas arrays and initialized identities match. Allocation falls about0.7%; this is a modest
improvement, not the main clause-installation fix. Decision: keep the simpler ownership-preserving
reuse. The deployed AWS artifact remains69; its large-tree migration qualification is unaffected.

Follow-up analysis filters the earlier JFR to Core.create stacks only: ProjectedData copying was
primarily readback work outside the timed initialization. After the already-retained diagnostic-path
fix, compiled-program cache weighing is a substantial initialization allocation site. Investigation
must distinguish per-document compilation/cache admission from actual BEX execution.


## 72. Preserve configured native code during canonical compaction — keep

`Contracts.Session.compact()` reconstructed a processor from Language/bindings alone, omitting
publicly registered NativeStep/NativeChannel implementations. Two public regressions reproduce
failure when compacting an initialized native-step or native-channel document. The session now
retains its immutable Contracts configuration and rebuilds through the same processor factory used
by open/restore. Close releases that configuration reference with the working state.

Compaction preserves identity; the subsequent native step matches an uncompacted reference's exact
gas and receipt. Native-channel acceptance and duplicate suppression also survive. Both regressions
failed before the fix and pass now. The full strict library build passes **2,107 tests**, and the
host build passes **88 tests**. No processing speedup is claimed. Decision: keep the smaller,
configuration-consistent rebuild path. DocumentRuntime already used its original runtime factory.

The initialization investigation also measured seven cached immutable compiled programs totalling
3,030,618 estimated bytes in a Hello session (one hit/seven misses). They fit the existing bounds,
but independent sessions compile/weight them again. A configuration-owned bounded cache of verified
immutable programs is the next candidate; document state, authority and invocation data must never
be shared through it.


## 73. Share immutable compiled BEX programs within runtime configuration — keep

Independent sessions previously rebuilt and weighed the same static programs. Contracts and
DocumentRuntime now own a bounded program cache per immutable configuration (8 MiB estimated weight,
512 entries, 2 MiB per entry). Session processors reuse it; independent configurations do not share
it. Keys bind the exact step and selected definition identities. No document, invocation context,
compiler callback or admission decision is retained. Compilation occurs outside the cache lock;
concurrent misses may duplicate pure work instead of blocking unrelated executions.

Definition selection, native admission, structural validation and logical reads still precede lookup.
Warm code cannot authorize a missing or different definition. Recompile mode bypasses the cache.
Tests cover immutable reuse, changed/missing definitions, exact cold/warm receipts and gas, concurrent
independent sessions, oversized candidates, failed compilation and eviction without invalidating
code already in use. Strict library build passes 2,112 tests; the full host build passes 88 tests.

Final alternating isolated JVMs (25 warmups/30 samples each, no concurrent builds) measured:

| Run | Variant | Median ms | Allocated bytes |
| ---: | --- | ---: | ---: |
| 0 | Baseline72 | 129.714 | 250,594,800 |
| 1 | Candidate73 | 123.699 | 241,666,168 |
| 2 | Candidate73 | 125.905 | 240,462,640 |
| 3 | Baseline72 | 134.021 | 251,039,548 |

Every gas sample and initialized identity matches. The two candidate allocation medians are about
3.9% below the two baseline medians. Timing gains are modest and local, not an AWS SLA. The initial
measurement before adding selected-entry weight/eviction coverage is retained separately in staging.
Decision: keep configuration-scoped static-code reuse. Definition materialization and logical reads
remain in the hot path; this change does not solve all clause initialization overhead. AWS remains69.


## 74. Reuse immutable BEX definition field values — keep

Every Compute still selects and validates its current definition, then logically visits each
constants/functions field in the original order. Only the following unmetered projection is reused:
an exact-identity-keyed, configuration-owned cache retains immutable BEX maps, never provider handles,
document state, logical evidence or callback closures. It has independent bounds of8MiB estimated
weight,512 entries and2MiB per entry. Invalid non-map shapes are not retained; recompile mode bypasses
reuse. The program and field caches together have at most16MiB configured estimated weight.

Two focused regressions check input/returned snapshot isolation and unchanged invalid-field failure
receipt/gas after warm reuse. Existing changed-definition, missing-definition, concurrent-session and
cache-disabled canonical tests also pass. Strict full builds:2,114 library tests and88 host tests.

Alternating isolated JVMs,25 warmups/30 measured Hello creations per run:

| Run | Variant | Median ms | Allocated bytes |
| ---: | --- | ---: | ---: |
| 0 | Baseline73 |126.288 |241,212,152 |
| 1 | Candidate74 |120.115 |221,427,868 |
| 2 | Candidate74 |114.394 |221,613,076 |
| 3 | Baseline73 |128.385 |240,894,160 |

Gas arrays and initialized identities match across all runs. Allocation drops about8.1% and timing
improves in this bounded local case. Decision: keep. The cache does not skip canonical logical visits;
first-use materialization still occurs. Inspection also found repeated descendant compaction in that
materialization path, which is the next candidate. AWS remains deployed69.


## 75. Stop recompacting converted BEX descendants — keep

ContractBridge.bexData previously converted each child, then recursively compacted those converted
children again at every ancestor. One shared shallow field conversion now accepts either recursive
conversion (raw wire data) or already-converted children (bexData). Scalar/list recognition stays in
one place; metadata, derived scalar types, arbitrary-width integers and repeated references survive.
Logical reads/gas and evidence handling are unchanged. Full builds pass2,119 library / 88 host tests.
Five added cases cover metadata/typed references and nested object/list depths1,16,64.

A dedicated JMH benchmark isolates conversion after admission, with setup equality checks. Four
alternating JVM runs use3×300ms warmup/measurement iterations per depth and GC profiling:

| Nested depth | Baseline74 us/op | Candidate75 us/op | Baseline bytes/op | Candidate bytes/op |
| ---: | ---: | ---: | ---: | ---: |
| 4 |1.86–1.88 |0.673–0.676 |11,464 |3,864 |
| 16 |18.48–18.65 |2.73–2.76 |109,416 |15,320 |
| 64 |259.67–262.53 |9.73–9.89 |1,491,054 |50,792 |

At depth64 this isolated stage is about26× faster with96.6% fewer allocated bytes. That is not a
whole-document speedup. In alternating warmed Hello host creations, allocation falls only about1.1%
(221.5/222.0→219.0/219.7MB) and medians114.99/115.12ms versus113.84/114.53ms establish no end-to-end
time gain. Gas/identity arrays all match. Definition reuse from74 already avoids much of this path
in that warm scenario. Decision: keep the simpler single-pass conversion and its scaling benchmark.
AWS remains69. The first JMH attempt could not bind its local controller socket inside the sandbox;
it performed no measurements and was rerun with the required local-process permission.


## 76. Sample stored embedding links for full-working-set qualification — keep

A broader AWS probe on release69 failed with Unknown embedding because it treated the root's
20,128 initialized orders as keys o0..o20127. The final 128 keys actually came from growth probes and
have growth-* names. This is a workload-selector defect, not evidence of successful or failed runtime
scaling. SSM d33f418b-4002-4161-94a2-25bb0005517f failed; its recovery trap restored exact-edition healthy
service. Read-only inspection6d8f4843-078b-4fb0-8b07-6d1f2312b28a confirmed the cause.

The host now offers an explicit probe-links CLI mode and --stored-links qualification option.
It enumerates actual /orders links from a pinned paginated root version before timing, checks the
requested population count, and samples those exact paths with the existing fixed seed. Enumeration
and target lookup remain outside operation timings and are disclosed. The old sequential-key probe
is unchanged so its earlier comparisons retain their meaning. The local regression creates a growth
key, finds both documents, refuses a count mismatch before any payment, executes both targets and
checks the root aggregate. Full host build passes 89 tests; all 10 hermetic launcher checks pass,
including the new explicit generated mode. Library stays at iteration 75 with 2,119 tests. AWS full-working-set
qualification follows deployment; no new large-working-set result is claimed at this point.


Iteration76 deployed successfully:artifact a5e1239c34b2bfabf90478f8fc693370ab101342d51c576d70c6d78d8f1e148b,
SSM efc84a4f-6556-4780-b973-a26e45cf4261, exact edition1/2/3 support and healthy service confirmed.
The corrected large probe5ea200b4-b87a-4722-a131-b58d30b9b698 succeeded, sampling the full20,128-order
population (80,513 documents) with128 warmups/128 measured payments. Runtime1.806ms, leafcommit23.417ms,
full propagation126.214ms medians; every aggregate matched. A matching128-order sample is in progress.


## Full-population random target qualification — iteration76

The deployed iteration 76 artifact successfully ran matching stored-link probes over 128 and 20,128 orders.
Each uses 128 warmups / 128 measured payments with fixed seed 1729, selected uniformly from its entire
order-link population (three payments per order). Every payment checks the agreement aggregate.
The CLI retains 64 sessions / 128 MiB estimated capacity / 32 uses and drains synchronously; this is not
concurrent HTTP load. These are 128 samples per population, not operations on every document.

| Stored population | Runtime median | Leaf commit median | Full propagation median | Propagation p95 |
| --- | ---: | ---: | ---: | ---: |
|128 orders /513 documents |1.816ms |25.642ms |145.618ms |168.932ms |
|20,128 orders /80,513 documents |1.806ms |23.417ms |126.214ms |144.656ms |

The same artifact and selection mode serve both runs. This extends the earlier eight-active-order
qualification to random selection from the whole stored population. It shows no size penalty in
these samples; the lower large-run latency is cloud variation, not evidence that more orders help.
Session miss counters were not captured, so no measured cold-miss rate is claimed. Source/parent
runtime editions remain mixed by explicit stored version; no implicit migration occurred.

Target enumeration took 0.708 s small / 12.131 s large before timing and warms index pages. Per-call target
lookup also remains outside operation timing. Applications addressing a known document do not need
this benchmark inventory, but enumeration/expansion is an explicit size-dependent read cost.
Both measured windows used 640 conditional transactions (five per payment) with zero reported storage
failures or retries. Full source/input/aggregate checks passed; storage I/O remains the main end-to-end
cost. Neither graph scans in authored BEX nor hot-parent contention have become constant-time.

Large SSM 5ea200b4-b87a-4722-a131-b58d30b9b698 and small 9cd2002f-34df-4d6e-8e34-ba66142646df completed
Success and restored healthy service. Compact samples and storage/p95 summaries are in
`reports/overnight-2026-11-05/working-set-comparison-76.json`. The earlier failed selector run may have
committed earlier synthetic calls; each successful rerun uses the current aggregate as its baseline.


Published Hello/Activation passes on release76 too: state/activation, rejection identity/version,
exact retained approval receipt/input, Terms Accepted and Hello Said event payloads verified against
pinned package export IDs. The temporary qualification service was stopped and main health passed.
See hello-summary-76.json for provenance and the hash of the full inspection report; that29MB raw
report is deliberately excluded from the compact project evidence. No full native/MyOS parity or
production-readiness claim follows from these bounded tests.

A fresh allocation profile of current75 filters only Core.create stacks. It points next to persistent
map updates, canonical identity rendering, concrete values and repeated schema/constraint objects.
Head.constraints already has a per-head cache; future work must not reimplement it. The compact
next-candidate note also identifies five durable commits per leaf-to-root update. Combining parent
publication and source acknowledgment would require crash/race/redrive/legacy-path qualification;
no such change has been made or claimed. Full scope remains active.


## 77. Share empty immutable schema observations — keep

Many distinct Language heads have no schema facets. They previously created separate frozen schema
containers and Contracts constraint adapters, then rebuilt empty observation maps during logical
visits. They now share one frozen empty schema and its immutable empty adapter/map. Nonempty schemas
still own snapshots; explicit false, zero, bounds, authored facets and enum data are not treated as
absence. Contracts adapters freeze their input at construction. Mutable drafts and subsequent copies
cannot mutate published observations. No logical visit, validation, identity or gas rule is skipped.

Three focused schema ownership tests and the full strict suite pass: 2,122 library tests / 89 host
tests. Alternating isolated Hello JVMs (25 warmups / 30 measured creations each) retain identical gas
arrays and initialized identities. Baseline75 medians116.52/115.84ms,218.83/219.82MB; candidate77
111.73/112.81ms,195.65/194.25MB. Allocation falls about11.1%; the local timing gain is modest.
Decision: keep the shared immutable empty values. AWS remains deployed76 until further qualified
changes are ready. The next candidate combines parent publication with child-event acknowledgment;
its correctness and transaction-count evidence are separate work.


## 78. Publish a parent update with its source acknowledgment — local qualification passes

Previously each three-level payment required five durable transactions: payment, order, payment
acknowledgment, agreement, order acknowledgment. For a single-parent fresh-tree delivery, Core can
now append the source outbox deletion/DISPATCH advancement/WAKE conditions to the parent's atomic
publication. Only that parent's business state changes; the already committed child is never rolled
back. The plan is attempt-local and marked complete only after a successful conditional commit.

A parent conflict retries without reusing stale source conditions. An existing parent receipt,
rejection, multiple-parent or paginated fan-out, overlapping keys and the100-action limit keep the
separate acknowledgment path. Source head and monotonic WAKE revision checks remain intact. The
host still cannot attach an existing document to a new parent: that future API must fence topology
before this optimization can be used with concurrent parent additions. This does not add distributed
cycle support or change the document-local business protocol.

Nine focused cases cover the3/5-publication modes, complete document/checkpoint/history/event equality,
conditional fallback, a lost commit response, concurrent source advancement, restart between an older
parent receipt and its acknowledgment, multiple-parent delivery, and transaction bounds/key overlap.
Existing concurrency, rejection/redrive, migration/replacement, startup and recovery tests also pass.
Strict builds:2,122 library tests /98 host tests;10 hermetic launcher checks pass. The original scheduler
count assertion expected5 and observed3; it was updated to the intended lower count after the new
independent semantic/recovery checks passed.

Both actual CLI modes also pass against the JSON-compatible in-memory store:128 warmups/8 measured
payments on4 orders, all totals checked,40 versus24 measured conditional calls. This proves transaction
count reduction, not AWS latency. MYOS_COMBINE_ACKS=false retains the separate path; --separate-acks
selects it for same-artifact qualification. Health/probe metadata exposes the selected policy. Probes
now include measured-window session counter deltas, cache capacities and distinct target counts.
Decision: retain locally for isolated AWS qualification; no remote speedup is asserted yet.


## 79. Avoid allocating unused mutable schema drafts — keep

New unconstrained heads now start with the existing immutable empty schema. Authored schemas replace
it; editing an existing head obtains the independent mutable copy already required by that API.
The production mutation audit found only copied-head enum filtering, which retains its mutable copy.
A focused ownership test covers sibling heads, explicit false/zero and post-publication mutation.
Full builds pass2,123 library/98 host tests. No logical read, identity or gas rule changed.

Four alternating isolated Hello JVMs (25 warmups/30 samples) compare baseline77 against79. Baseline
110.90/113.10ms and195.23/195.01MB allocated; candidate111.34/109.57ms and190.89/190.05MB. Exact gas
and identity arrays match. Allocation falls about2.4%; timing overlaps, so no established latency
gain is claimed. Keep the simpler initialization with fewer discarded containers. AWS remains78.

## 80. Qualify independent processes under the deployed protocol — local checks pass

The existing two-process launcher defaulted its temporary services to edition1, regardless of the
main deployment's selected runtime. It now pins the immutable release, selected runtime manifest,
acknowledgment policy and exact durable health on both services. Its EXIT handler stops both temporary
services, restores the primary and verifies its policy/edition even after a workload failure.
The reusable health generator accepts explicit port/policy checks without changing existing callers.
Generated-shell tests execute the real recovery handler with stand-ins and reject wrong policy,
edition or durable mode. All11 hermetic launcher checks pass. This improves qualification fidelity;
it does not add a runtime feature or claim an AWS race result before the run completes.


## Completed same-artifact acknowledgment comparison — release78

The deployed release78 passed all four bounded probes and restored exact-profile healthy service.
Each run uses128 warmups/128 measured payments, seed1729, actual stored link selection and a64-entry,
128MiB estimated session cache with32-use renewal. Order: large separate/combined, then small
combined/separate. Enumeration and target lookup remain outside operation timing. Each payment
checks the agreement's expected total. These are synchronous CLI probes, not concurrent HTTP load.

| Population / policy | Completion median | Completion p95 | Transactions/payment | Distinct orders/payments |
| --- | ---: | ---: | ---: | ---: |
|small-separate|149.069ms|168.340ms|5|82 / 109|
|small-combined|109.350ms|137.861ms|3|82 / 109|
|large-separate|127.578ms|151.253ms|5|128 / 128|
|large-combined|103.614ms|124.510ms|3|128 / 128|

All512 measured updates and their warmups passed aggregate checks; every measured window has zero
reported storage failures/retries. Large separate/combined both had260 session loads,124 hits and256
capacity evictions; their128 measured calls hit128 distinct orders/payments. Thus the result is not
restricted to the earlier eight-order hot set. Small runs sample82 orders/109 payments from128 orders.
The transaction count falls640→384 per measured window. Median completion improves18.8% large and
26.6% small in these runs. Different cloud observations can vary; no SLA or whole-system throughput
claim follows. Gas/business equivalence and crash/race cases remain independently checked locally.

Decision: keep combined publication/acknowledgment, retaining the explicit separate fallback and
configuration switch. Exact sample arrays, cache/storage metrics, command IDs and hashes are in
ack-comparison-78.json and its compact raw reports. Source/parent mixed runtime editions are retained;
this storage change does not implicitly migrate any stored business semantics.


Iteration80 AWS qualification succeeded on deployed release78: two independent JVMs, exact
host-links profile, combined acknowledgments, shared DynamoDB. All128 submissions/64 unique
commands across4 accounts/28 documents retained identical retry receipts, exact leaf/order/root
totals and matching historical views; conflicting identifier reuse was rejected. The bounded race
run took6.814s with487.674ms median submitted-call time, including duplicate contention and cold
services sharing2vCPUs. It is correctness evidence, not a steady-state throughput result. Both
temporary services stopped and the8-worker primary restored exact-profile healthy status.
SSM bac0dca6-3370-495f-bf4d-122639960504 completed Success. Decision: keep the corrected qualification
launcher and the separately measured release78 storage optimization. Full two-machine/soak and
native MyOS protocol scope remain open. No new EC2 resource was created for this two-JVM run.


## 81. Expose snapshot-bound native admission — keep

`NativeChannel.verifyAdmission` now connects the existing pre-execution admission hook to a public,
thread-confined `NativeAdmission` context. Separate immutable `AdmissionEvidence` is keyed by exact
channel type; missing/unused evidence fails before external selection. The verifier sees exact
snapshot, payload and execution-input identities, timestamp, declaration/generation/checkpoint rows,
and a shared root-local gas allowance. It returns a bounded immutable projection; channel handlers
receive only that verified projection. The projection participates in the invocation commitment.

Canonical sessions and all three document-local editions accept explicit evidence. Command-identified
local calls expose their command-envelope identity separately from the unchanged payload identity.
No permit is cached across calls. Portable chunk reconstruction reverifies the original evidence and
retains exact gas/receipt parity. Evidence is validated against the private record domain at capture,
preventing locally accepted data from later failing solely because it cannot be transported for replay.
The optional admission fee is positive; non-verifying channels preserve their existing counter rules.

Fourteen new cases qualify HMAC-bound snapshot/input/payload/time/generation/channel rejection,
missing/unused evidence, independent mutable inputs, immutable verified output, context lifetime,
all local editions, checkpoint deduplication, cold restore/compaction, shared gas, callback failure,
output bounds, builder isolation, concurrent sessions, projection commitments and detached replay.
Full strict builds pass2,137 library/98 host tests. Initialization does not perform external admission.
No new package or native Timeline/Time semantics is silently enabled.

The paired non-verifying Hello probe (25 warmups/30 samples in each of four JVMs) compares baseline79
106.37/115.86ms and190.61/190.19MB with candidate81 108.47/109.47ms and190.91/190.43MB. Gas/identity
arrays match exactly. Timing overlaps and allocation differs about0.14%; no performance gain or
meaningful regression is established. Decision: keep the capability with its explicitly tested boundary.

This is a native admission SPI, not a complete native-service implementation. Snapshot/member IDs are
not stable managed account/document IDs. Trusted host scope, durable original-input/evidence retention,
ordered Timeline/Time eligibility, effect provenance and timer progress remain next integration work.
The MyOS HTTP API does not yet forward proof evidence. See docs/NATIVE-ADMISSION.md for usage and
protocol obligations. Release81 is now deployed; the host still does not enable a native Timeline/Time service.


## Release81 checkpoint qualification

Deployment and both AWS probes completed successfully. The main service was restored with its exact
edition3 profile, combined acknowledgments and eight workers after the Hello qualification.

The large tree still contains20,128 orders/80,513 documents. After128 warmups,32 uniformly selected
stored-link payments passed all aggregate checks, with96 conditional commits (three per payment),
zero storage failures/retries,2.317ms median measured payment runtime and122.283ms median completion.
This is an unpaired, smaller smoke sample. It does not establish a regression or improvement relative
to release78's103.614ms controlled result. Target inventory/lookup were outside operation timing.

Published Hello/Activation passed initialization, approval, message/audit update, rejection preserving
complete local identity/version, retained approval input/result and exact event payload assertions.
Single observed approve/hello/reject HTTP steps took167.109/153.884/53.498ms; these are not warmed
latency distributions. The qualification uses edition1 explicitly, then restores the edition3 service.
The compact summary retains the hash and source location of the29MB full report, not that large trace.

A separate four-JVM local payment comparison used4096 warmups/2048 samples per JVM, restoring every32
calls outside timing, with no storage or parent work. Baseline79 medians were0.379/0.374ms and candidate81
0.390/0.379ms. Exact gas, final identity and payment result matched. These overlapping observations
show no established material regression; they are not evidence for the cost of native verification.

Timing definitions: `localPaymentMillis` starts after session acquisition and excludes cold restore;
`leafCommitMillis`, despite its legacy name, measures the complete leaf call through publication,
including reads and execution, not just a DynamoDB transaction. `endToEndMillis` includes parent
propagation and verification of the root total. None of these is interchangeable with pure CPU time.

Evidence: [release81 manifest](../reports/overnight-2026-11-05/manifest-81.json),
[Hello assertions](../reports/overnight-2026-11-05/hello-summary-81.json), and
[isolated payment comparison](../reports/overnight-2026-11-05/admission-leaf-summary-81.json).


## 82. Scoped durable native ingress and command ownership — keep locally

The library now supports bulk immutable `AdmissionEvidence.copyOf`/`asMap`, an explicit
`DocumentRuntime.Session.admit` result distinguishing verified authority from failed admission, and
current-document `requiresNativeAdmission` introspection. Ordinary `process`/`call` identities remain
unchanged. A required admission with no active verifier fails before workflows. Trusted detached child
delivery does not request unrelated external permits or select external native channels.

The companion host adds `/documents/admit` behind a stable `MYOS_ADMISSION_NAMESPACE` configuration.
It derives a domain-separated command commitment from authoritative namespace/account/document/command,
retains bounded original event/time/evidence/protocol data, rejects conflicting completed input, and
re-verifies after conditional state conflicts. Exact duplicates reconcile existing receipts after
restart or uncertain publication. Installed protocol code must verify the scoped input commitment;
no proof field supplies its own trusted target. No production native protocol/key is installed by
setting the namespace or loading a package.

Review found and closed command-ID poisoning: an invalid proof must not claim the ID before its valid
owner. Unverified rejection now uses a check-only state-revision fence with no receipt write. Verified
business failures retain their original input and result. Legacy evidence-free process/call endpoints
also refuse protected documents without claiming IDs. The DynamoDB adapter and in-memory store now
both accept1..100 writes/checks, including check-only transactions; the fake-SDK test checks exact
key/revision, absence of mutations and conditional-conflict handling.

Tests cover real signed cross-account/document/namespace replay with identical snapshots, all three
local editions, caller mutation, JSON transport, cold restart, lost response, re-verification after a
competing publication, command poisoning, authorized workflow failure, unsupported verifiers and
child-to-native-parent delivery. All2,139 library/109 host tests and11 hermetic launcher checks pass.
Strict formatting/compiler/Javadoc and qualification compilation pass. One initial failure fixture
incorrectly assumed replacing an absent field must fail; it was corrected to an explicit authored
`$failIf`. That changed the test trigger, not language patch semantics.

A four-JVM non-native leaf probe with4096 warmups/2048 samples had an anomalous candidate fork:
baseline0.477/0.371ms, candidate0.997/0.364ms. The raw evidence is retained. A bounded repeat with
16384 warmups/4096 samples measured baseline0.353/0.355ms and candidate0.358/0.352ms. Allocation medians
were895680/900648 bytes versus904200/897136 bytes. Both comparisons retain exact gas arrays, final
identity and payment amount. No material steady-state regression or speedup is established; the
short-warmup variability is not hidden. These probes exclude storage, parent work and session restore;
they do not measure production native-verifier latency.

Decision: keep the capability and boundary corrections. Source iteration82 is locally qualified;
AWS remains release81. Next is live qualification of scoped admission/check-only fencing and native
service integration, not a claim that published Timeline/Time protocols now work. The existing runtime
manifest still does not install a production native verifier. Full authentication, preparation,
ordering/closure, effect provenance, scheduling/cancellation and production qualification remain.


## 83. Live signed admission and deployed large-tree checkpoint — keep

Added a separately packaged NativeAdmissionProbe and a pinned AWS launcher. The probe creates fresh
Ed25519 keys, binds the public key into a synthetic semantics identity, and exercises the actual
DynamoDB adapter. Its private key is never printed or persisted. The launcher verifies deployed
host/library/tool digests, serializes against deployment, bounds execution time and checks that the
main service PID and exact health profile are unchanged. Two additional hermetic launcher tests pass.

Release83 deploys source82 runtime plus these qualification tools. Deployment, native qualification
and large-tree smoke all returned authoritative SSM Success. The main service is healthy on edition3
with combined acknowledgments and eight workers; nativeAdmission remains false because no production
native protocol has been installed. A namespace alone cannot install executable semantics.

The signed probe passed forgery, cross-account/document/command/time/namespace replay, condition-only
rejection without command ownership, legacy-ingress poisoning, valid reuse of a rejected ID, exact
events/state, retained proof, cold receipt reuse, changed-proof conflicts and stale-proof rejection.
After16 warmups,32 measured updates took32.044ms median durable call time, including7.075ms runtime.
Snapshot read/signature preparation was separately5.861ms median. All32 commits succeeded without
retry; gas was720 each. Final count49/version50. This is a small correctness qualification, not a
steady-state benchmark. Cold reconstruction occurs inside the same JVM with the same in-memory key;
it does not qualify production key persistence or the published Timeline/Time protocols.

The20,128-order/80,513-document tree passed128 warmups and32 uniformly selected stored-link payments.
Payment runtime median2.453ms, full leaf call24.997ms, full propagation/root verification109.063ms.
Every aggregate check passed, with96 commits and zero storage failures/retries. Target enumeration
took11.850s outside operation timing; target lookup was also excluded. The result is an unpaired
smoke check, not evidence of improvement over release78's controlled comparison.

Current recorded strict checks:2,139 library/109 host tests,13 launcher checks. Latest published
Hello/Activation live qualification remains release81. Artifact/provenance/raw arrays are retained
in reports/overnight-2026-11-05/manifest-83.json. No binaries, keys or large traces are archived there.
Decision: keep the deployment and reusable qualification tools. Full MyOS/native service integration,
large single-document initialization costs and sustained production qualification remain unfinished.


## 84. Keep a validation recursion path without copying ancestor sets — keep

A fresh JFR allocation profile of Hello host creation identified HashSet copies in ContractWork.visit.
Validation now pushes/pops recipe keys in one call-local ancestor set, with finally cleanup. Completed
siblings still use the separate pending/proven proof ledger; cyclic backedges keep their old behavior.
No validation predicate, traversal order, logical charge or admission rule changes.

Full strict library/host builds pass2,139/109 tests. Four isolated Hello JVMs with25 warmups/30 samples
retain exact gas arrays and initialized identities. Baseline82 medians112.705/110.579ms and
190.212/192.239MB; candidate84 113.841/107.100ms and185.328/185.289MB. Allocation falls roughly3.1%
across the fork medians; timing overlaps, so no established latency speedup is claimed. Keep the
simpler per-walk state. This reduces validation's physical ancestor storage from repeated depth-sized
copies to one active path; it does not remove the logical validation work or recursion depth limit.
AWS remains release83. See initialization-summary-84.json and the raw samples in the evidence archive.


## 85. Hash the identity bytes already rendered for charging — keep

Contracts identity establishment rendered the same canonical helper twice: once to count UTF-8 bytes
for the SHA-256 gas blocks and again for hashing. It now hashes the already rendered bytes through
a package-private LanguageCore.hashBytes helper. No digest, serialization, gas ordering or identity
rule changes; there is no new cache or public API. SHA padding arithmetic remains long-valued.

Full strict builds pass2,139 library/109 host tests. Four isolated Hello JVMs use25 warmups/30 samples
each and retain every exact gas and initialized identity. Baseline84 medians106.411/106.606ms and
185.113/185.509MB; candidate85 105.403/102.996ms and177.863/178.106MB. Allocation falls about4.0%,
with a modest local timing improvement in this bounded sample, not an AWS throughput claim. Keep the
removed duplicate work. AWS remains83. See initialization-summary-85.json and raw sample arrays.


## 86. Use a typed, context-local validation memo key — keep

Language validation previously concatenated a complete recipe key, validation goal and occurrence
role for every visit. A small ValidationKey record now retains those three immutable components.
Memo entries remain context-local; no resolver, semantic rule or host-budget check is skipped.
Two focused regression cases verify that a definition or optional absent field cannot pre-certify
a missing root payload. LanguageValidationTest owns this boundary in the test strategy.

All2,141 library/109 host tests and strict build checks pass. Four isolated Hello JVMs with25 warmups
and30 samples retain exact gas and initialized identities. Baseline85 medians103.245/103.408ms and
177.501/177.195MB; candidate86 102.342/103.504ms and174.772/174.208MB. Allocation falls about1.6%;
timing overlaps. Keep the clearer structured key and reduced copying, without a latency claim.
Across these sequential comparisons initialization remains roughly100ms and174MB, still substantial.
These changes do not establish full native-service parity or sustained production throughput.


## Release86 AWS checkpoint — qualification passed

Deployed artifact b3db2baa472cdd7eeeda40afe8f422eca9995fb43b93a0386a218bc34356a5f9 using command
3bebc2ed-3bc7-4ed0-a752-d3a19a44f6c3. The installed library digest matches the locally measured86
candidate. Deployment, published Hello/Activation and the large-tree probe all returned SSM Success.
Main service health confirms durable edition3, support for1/2/3, combined acknowledgments and8 workers.
No production native service was added; latest live synthetic admission qualification remains83.

Hello/Activation retained exact inactive initialization, approval, message/audit update, failed edit
identity/version, original receipt/input and emitted Terms Accepted/Hello Said payload assertions.
Single HTTP approve/hello/reject observations were196.761/153.213/70.420ms, not warmed distributions.
The large tree passed128 warmups and32 uniformly selected payment operations across20,128 orders and
80,513 documents. Medians:1.989ms payment runtime,27.243ms complete durable leaf call,126.073ms full
propagation including root verification. All96 commits succeeded with zero failures/retries. Final
paidTotal113358. Enumeration took14.938s outside operation timing; target lookup was also excluded.

This unpaired smoke result does not establish an AWS speedup or regression: full completion was
109.063ms on83 and126.073ms on86, while runtime was2.453/1.989ms. Cloud/storage and scheduling costs
remain visible. The controlled small/large comparison is still release78's evidence. Local84–86
allocation improvements are supported by paired JVM samples, not by these cloud medians.

Evidence: reports/overnight-2026-11-05/manifest-84-86.json. Compact Hello assertions retain the full
report hash/S3 key; neither its29MB trace nor JFR/binaries are copied into the project evidence.
The fresh86 allocation profile identifies eager successful-tariff error strings and boxed/BigInteger
validation in primitive gas debits as the next candidates. They are not changed in86. Full native
protocols, original MyOS application integration, distributed sharing/cycles and production-scale
concurrency qualification remain required work.


## 87. Keep primitive gas checks primitive — keep

BEX tariff lookup now constructs its unknown-counter message only on failure. Contracts integer
validation has a primitive long path; boxed Integer/Long values delegate to it rather than allocating
BigInteger values and successful error-context varargs. BigInteger records retain exact conversion
and overflow behavior. No tariff, ordering, gas allowance or public API changed.

Fourteen additional cases qualify primitive/record bounds, invalid input categories, oversized
integers, unknown tariffs and retention of the first rejected shared/local charge. Full strict builds
pass 2,155 library tests and 109 host tests. Four isolated Hello JVMs (25 warmups/30 samples) retain
exact gas and initialized identities. Baseline86 medians 103.221/104.223 ms and 176.139/173.649 MB;
candidate87 101.039/102.180 ms and 174.078/172.355 MB. Allocation reduction is about 1% across fork
medians, smaller than the sampled profile suggested. Keep the simpler successful paths and measured
small reduction; no broad latency or AWS gain is established. AWS remains release86.


## 88. Pinned provider signature authentication — keep as a protocol component

Added ProviderSignatures for Coordination 1.0.0-deterministic.2. Adoption verifies the exact package
pin and provider ancestry, reads inherited endpoint/key fields and binds that provider identity to
the published provider-object, completeness-guarantee and reader-request signature domains. Wire
hex lengths/case and canonical key coordinates are checked; JDK Ed25519 performs point validation
and signature verification. Verifiers share no mutable Signature instance. No private key, signing
oracle, network client, permission grant or runtime registration is introduced.

Twenty-five cases cover three independent RFC 8032 vectors, actual published entry/guarantee/read
objects, expansion-preserved identity, inherited provider fields, changed object/provider/domain,
reusable reader queries, invalid endpoints, malformed/noncanonical keys/signatures, wrong package
and definition/type selection, and concurrent verification. All 2,180 library/109 host tests and
strict checks pass. The 675 KB self-contained package is a test fixture, with identity/checksum and
provenance recorded; tests never read the sibling repository. No performance claim is made for this
new component. The measured gas change is independently recorded in iteration87.

Decision: keep the independently qualified authentication component as groundwork for native
provider integration. It intentionally does not claim full provider/evidence/feeder conformance:
exact envelope/request/source binding, allowed object kinds, required-source completeness, access
state and executable Timeline/Time/Coordination/Mandates semantics still need implementation.
A public method authenticating a signature must not be confused with admission of its entire claim.

The protocol review identified a performance constraint for the next layer: recursively admitting
every predecessor would reopen an ever-growing history. Prefix advancement must inspect only a new
source suffix plus authenticated prior-prefix facts, while preserving missing-content failures and
typed field/ancestry checks. Arbitrary caller-supplied hashes are not such facts. This layer has not
yet been implemented. AWS remains release86; it has no production native verifier installed.
See docs/PROVIDER-AUTHENTICATION.md for API usage, precise obligations and primary references.


## 89. Incremental signed timeline prefixes — keep

Added the public TimelineVerifier with opaque Prefix/Result/Entry values. It accepts the actual
published Timeline Range Evidence and Timeline Successor Evidence, verifies exact provider/source
bindings and signatures, strict timestamps, adjacency, exclusive bounds, original messages and
complete final heads. Both proof forms share one prefix model. An authenticated successor remains
outside the closed prefix until a later bound closes it; later evidence cannot skip or replace it.
Empty-prefix assertions, missing bodies, old-bound replay and retained-head contradictions are distinct.

Supplied message fields are selected separately from contextual declaration metadata. Domain defaults
and explicitly supplied unvalued definitions work without adding the base property's documentation
to their identities. Recognized Operation Requests reuse the existing Contracts shape/token/document
validator. Actual declared type ancestry selects protocol roles. The adversarial Actor case exposed
that Language's structural subtype relation was too permissive for these roles; provider adoption
was corrected too, with a structurally compatible but unrelated provider regression case.

Strict validation of new content uses private invocation-local facts for the previously established
head. Those facts are created only after validation, cannot be supplied by a wire hash and cannot
leak into ordinary Language admission. Additional contextual constraints still run. The prefix
retains detached headers for at most the closed head and authenticated successor; it retains no
predecessor bodies or growing history list. Host code must retain content and serialize progress.
There is no unchecked prefix restore API and no native runtime is enabled by this component.

All 2,204 library/109 host tests and strict checks pass. The public API suite is grouped into prefix
advancement, integrity boundaries, original content and locality, including concurrent independent
calls. Locality tests remove all historical bodies before the next advancement. A separate three-JVM
probe uses128 warmups and128 measured one-entry proofs per size, with the same issued prefix in each
measurement window. Every proof fetches exactly one new entry and returns the expected identity/bound.

| Prior entries | Fork0 median | Fork1 median | Fork2 median |
| --- | ---: | ---: | ---: |
| 1 | 3.299 ms | 1.120 ms | 1.135 ms |
| 128 | 2.290 ms | 1.139 ms | 1.748 ms |
| 1,024 | 1.413 ms | 1.192 ms | 1.108 ms |
| 10,000 | 1.153 ms | 1.514 ms | 1.128 ms |

The first measured groups show JVM warmup/order effects; their slower values are retained. Allocation
medians range3.36–3.78 MB, with no history-size growth. Timing includes signature verification,
scoped strict validation and the one new-body fetch/parse. It excludes initial history establishment,
transport construction, network/storage and document execution. This is evidence of history locality,
not a production latency SLA or a claim that arbitrarily large new messages take constant time.

Because this adds a scoped mechanism to the shared Language kernel, a four-JVM Hello regression
comparison also ran: baseline88 102.286/100.475 ms and172.150/172.375 MB, candidate89 101.887/102.095 ms
and174.868/173.005 MB. Gas arrays and all initialized identities match. Latencies overlap; the small
allocation increase (about1% across fork medians) is reported, not called an optimization. Retain the
verified history capability; no initialization or AWS speedup is claimed.

AWS remains release86. No deployment or live native conformance claim was made in this iteration.
Required next work includes response/request correlation, pagination, current and historical access,
required-source ordering/closure, trusted durable prefix provenance, evidence retention/equivocation
and integration with the native feeder/services. The new API is documented in TIMELINE-VERIFICATION.md;
measurements and provenance are archived in manifest-89.json.


## 90. Signed discovery and contiguous read pagination — keep

Added TimelineReader with immutable Read/Page values, typed signed failures and shared prefix
authority with TimelineVerifier. It authenticates the published Provider Descriptor, exact request
correlation, checkpoint guarantees and inline response/page/cursor boundaries. Positive page limits
use the provider's arbitrary-precision advertisement; baseline operations do not need to appear in
the optional-operation list. Private reader authorization and HTTP transport remain host duties.

Checkpoint-only reads distinguish a signed claim from an acquired contiguous prefix. Unknown heads
are not fetched or seeded as validated facts. Every page extends the exact established predecessor,
pins its checkpoint, preserves a known successor and checks timestamps, limits and final heads.
Only a terminal chain, or a refreshed bound for an already established identical head, issues a
completed prefix. Empty checkpoint claims cannot contradict established entries. Continuations keep
only the starting prefix and latest boundary, not old pages or their snapshots.

Review caught and fixed a nonfinal-page validation gap: deferring the entries field skipped its list
schema. A failing regression reproduced this; list and item refinements now run before a page can be
discarded. Extra header fields remain validated. Unknown checkpoint-head links are inspected only as
partial transport and never certify Language admission. The shared Language validation algorithm has
an internal occurrence-role overload; existing callers still use the same root role. Timeline URL
validation now rejects the trailing slash already forbidden by the published specification.

Full strict staged builds pass 2,237 library tests and 109 host tests with no failures or skips.
Thirty-three reader cases are grouped by discovery/requests, checkpoint/failure claims, pagination
continuity and wire/selected content. They include arbitrary-precision limits, timestamp seeks,
mixed successor/read advancement, malformed signatures/correlation, contradictory claims and long
pagination with unavailable old bodies. No Java test reads Python or a sibling package checkout.

The standalone public-API probe ran three isolated JVMs. Each size/mode uses128 warmups and128 final
one-entry page verifications against the same issued continuation. Historical records are removed;
every measured verification fetches zero old/future bodies. Parsing-plus-verification medians:

| Previous pages | Fork0 | Fork1 | Fork2 |
| --- | ---: | ---: | ---: |
| 1 | 2.145 ms | 2.026 ms | 2.064 ms |
| 128 | 2.115 ms | 2.194 ms | 2.063 ms |
| 1,024 | 2.092 ms | 2.086 ms | 2.084 ms |

Prepared-response verification medians are1.984–2.231ms. Parsing-plus-verification allocates about
5.53–5.55MB per page, with no growth with previous-page count. This is still substantial allocation.
The probe excludes earlier-page establishment, request construction, network/storage and execution;
it repeats one final page per window and does not measure sequential appends or production throughput.
Raw arrays, parse modes, wire sizes and exact artifact digest are retained in reader-locality-summary-90.json.

A four-JVM Hello regression comparison uses25 warmups/30 samples. Baseline89 medians103.563/99.332ms
and173.830/172.798MB; candidate90 103.927/100.278ms and172.677/172.603MB. All exact gas arrays and
initialized identities match. Timing overlaps; this is a compatibility check, not an initialization
speedup claim. Initialization still needs separate work.

Decision: keep the qualified reader component. AWS remains release86 and no new native service has
been deployed or enabled. Exact-entry/content retrieval, current and historical access, durable
prefix/evidence provenance, required-source closure, native feeder/services, original MyOS app
integration and distributed sharing/cycles remain unfinished. Next work should connect these protocol
components to executable services and the durable host, not mistake isolated verification for full
native conformance. Usage and boundaries are in docs/TIMELINE-READING.md.


## 91. Executable Timeline Access and first-update locality — keep

Added TimelineAccess, a serialized process-local replay of one confirmed access document on the
adopted provider's reserved public control timeline. Exact package/program/channel checks precede
execution. Agent publication initializes the actual document through Contracts. Subsequent standard
Operation Requests target the original identity and execute the published BEX replacement program
with the complete original entry. The exact Timeline/Public Timeline channel bindings and Dispatch
are selected internally; this does not install a general native service into the host.

Each applicable disposition retains its actual Contracts transition and after-document. Unrelated
entries advance source coverage without a fabricated invocation. Every accepted replacement must
succeed; a failed suffix leaves the previously published view intact. Historical states are validated
individually: review reproduced a bug where a later valid replacement hid an invalid intermediate
timeline URL. The failing case now rejects the whole suffix, preserving the earlier established view.

An exploratory warmed benchmark hid first-update growth. After importing1/128/1024 control entries,
the first operation reopened old predecessor validation and discovered previously imported content
for receipt retention. A proposed admission bypass passed equality checks but did not remove the
cost; it was reverted. The retained implementation scopes the opaque verifier-issued prefix head
across Contracts resolver views and revokes that scope afterward. Strict new-content/refinement
checks and ordinary public admission remain intact. Each verified new suffix is retained once,
including unrelated entries, so a later operation need not rediscover old content.

Four alternating JVMs compare the original staged access implementation with the final candidate;
second-pair size order is reversed. Each size records the first real update separately, then16 real
sequential warmups/16 measured updates. The first update is included in those16 warmups. The request
contains one key and one timeline, not a large permission set.

| Prior entries | First update baseline (two JVMs) | First update candidate (two JVMs) | Baseline allocation | Candidate allocation |
| --- | --- | --- | --- | --- |
| 1 | 31.839 / 6.766 ms | 30.869 / 6.785 ms | 16.17 / 13.89 MB | 16.08 / 13.78 MB |
| 128 | 11.321 / 12.176 ms | 7.436 / 7.283 ms | 26.00 / 25.97 MB | 13.79 / 13.81 MB |
| 1,024 | 44.666 / 80.484 ms | 7.677 / 29.016 ms | 110.41 / 114.19 MB | 13.74 / 15.84 MB |

The first group in each JVM is colder; all observations remain reported. Candidate warmed medians
are5.547–7.420ms with13.13–13.27MB allocated. Exact gas arrays, receipt commitments and resulting
document identities match in every compared run. This establishes removal of the tested first-update
history growth, not a production latency SLA. Timing includes proof verification, actual BEX/Contracts,
receipt/event/view projection; it excludes history import, transport preparation, network and storage.
The process-local tracker retains content/history and is not a bounded durable replay cache.

All2,257 library/109 host tests and strict checks pass. Twenty access cases are grouped into
confirmation, executed replacements and integrity/atomicity. They cover altered definitions,
foreign-provider timelines, duplicate/invalid initial lists, exact emitted events, historical state,
human actors, forged proofs, duplicate publications, failed batches and private prefix fact lifetime.
A public regression removes old records: replay can use its issued prefix, but ordinary Language
admission still fails on that missing history before and after replay.

A separate four-JVM Hello regression compares actual90 with91,25 warmups/30 samples. Baseline
102.649/101.914ms and173.001/172.840MB; candidate104.130/103.420ms and172.625/173.162MB. Exact gas
and initialized identities match. No initialization improvement is claimed; allocation is essentially
unchanged and the observed timing difference is about1–2% in this bounded comparison.

Decision: keep the qualified capability, historical-state fix and measured prefix/retention reuse;
revert the ineffective bypass. The published duplicate-list BEX algorithm still contains nested scans
and can require quadratic work for large permission sets. No publication or gas rule was rewritten.
AWS remains86, with no production native service enabled. Current grant union/enforcement, HTTP
acquisition, trusted durable history/provenance, required-source closure, feeder/services and the
original MyOS application integration remain unfinished. See TIMELINE-ACCESS.md and manifest-91.json.


## 92. Durable source acquisition and incremental evidence transport — keep

Added a tenant-scoped TimelineArchive to the alternative host. It verifies signed evidence against
its established prefix, retains actual exact/cyclic records, and publishes head/history/idempotency
receipt in one conditional transaction. Immutable pages precede publication. Empty coverage and authenticated successors survive restart without prematurely closing a future entry. A lost response can be
retried; competing duplicate acquisitions return one original receipt. Different losing proposals
are retained when their separate write succeeds, without claiming that a race proves provider fraud.
A failed retention write is explicitly unconfirmed. No feeder or native endpoint is enabled yet.

Each acquired batch pins its content-index root. Old views cannot use later content. Fresh restoration
replays and verifies retained evidence, including stored claims and history association; a timestamp
or hash alone cannot reconstruct authority. An already established importer checks its old anchor and
verifies only new batches when another importer advances. Altered bounds, rewritten receipts, missing
history, false signatures and cross-account access are covered by focused tests.

LanguageContent.records(predicate) exports new closures using lazy membership checks rather than
copying an existing store inventory. LanguageRecord.toJson/fromJson retain exact text or cyclic
proof/body envelopes as unverified evidence. Four tests cover omission with unavailable old bodies,
append-only list compact-wire round trips, cyclic proofs and forged identities. Ordinary full export
still uses its existing path without allocating a BlueId for every predicate visit.

The immutable embedding index was extracted into a typed RadixIndex used by both links and evidence.
Three hashes captured from the old compiled LinkIndex match exactly after insertion, update and
removal; the existing 20,000-sibling bounded-path and pagination checks still pass. Content envelopes
use bounded 64 KiB chunks with manifest, page, length and whole-content hash checks. This storage
format is internal and does not change Blue identities or require an embedding-page migration.

Full strict builds pass **2,261 library tests and 130 host tests**, without failures or skips.
The 21 added host cases are grouped by acquisition/restoration, failure boundaries, chunk integrity
and existing index-wire compatibility. Java tests use bundled publications and public RFC test keys;
no Python implementation, external database or sibling package source is read by a test.

Three isolated JVMs rotate size order. Each creates real one-entry acquisitions, then measures 16
sequential warmups and 16 appends. Another importer catches up after each append. Acquisition includes
JSON parsing, signatures, content export/indexing and JSON-boundary MemoryStore publication; proof
construction/signing, network, AWS and business execution are excluded. Exact acquired heads and
proof identities match across JVMs. No historical batch is read on the writer's normal append path;
observer catch-up reads its one retained anchor and one new batch.

| Prior acquisitions | Append median, three JVMs | Observer catch-up, three JVMs | Fresh restore, three JVMs |
| --- | --- | --- | --- |
| 1 | 2.826 / 1.610 / 1.829 ms | 2.142 / 1.292 / 1.466 ms | 28.664 / 9.581 / 8.847 ms |
| 128 | 1.880 / 1.689 / 1.939 ms | 1.441 / 1.301 / 1.470 ms | 209.654 / 177.853 / 238.308 ms |
| 1,024 | 1.759 / 1.857 / 1.756 ms | 1.314 / 1.360 / 1.326 ms | 1,396.150 / 1,431.853 / 1,399.906 ms |

The first JVM/size group remains slower and is not discarded. Median append allocation is 4.94–5.92MB;
index/content publication rises from 30 pages/24.1KB to 44 pages/31.8KB as index depth grows. These are
staged-page counts, not DynamoDB request counts. Warm history is not rescanned, but this is not a
constant-storage-write claim. Cold restoration at 1,024 acquisitions allocates 4.28–4.32GB cumulatively
(not retained heap), reads all 1,024 batches and remains a material scalability gap. Histories and
orphaned staged content have no garbage collector yet. Keep correctness and warm locality; do not
present linear cold replay as the final production restart strategy.

A separate four-JVM regression compares actual 91 and candidate 92 host/library pairs with 25 warmups
and 30 Hello initializations. Baseline medians 103.742/101.916ms and 172.53/174.91MB; candidate
99.922/101.750ms and 172.43/174.81MB. Exact gas arrays and initialized identities match. This shows
compatible behavior with overlapping timing/allocation, not an established initialization speedup.

Decision: keep the qualified archive, transport API and compatible index extraction. AWS remains 86;
no new live-storage/native-service claim follows from in-memory publication tests. Required-source
ordering, historical access enforcement, feeder/native admission, efficient trusted durable restart,
original MyOS interfaces and distributed sharing/cycles remain. The host guide is
TIMELINE-ACQUISITION.md; raw measurements, test logs and provenance are in manifest-92.json.


## 93. Atomic source consumption and session-renewal locality — keep

The host now merges retained entries from an explicit fixed required-source set, ordered by timestamp,
timeline BlueId and entry BlueId. Every source must close strictly beyond the candidate timestamp;
quiet/missing/equal-bound sources block it. Cursors retain batch offsets and normalize only empty or
exhausted batches. Frozen content and coverage are bound to receiver state and progress revisions.
Successful actual BEX execution, document history/events/receipt and source disposition publish in one
conditional transaction. Lost responses, competing consumers, restart, altered inputs, inapplicable
coverage and business rejection are tested. Source-set changes fail closed pending historical rebase.
This is internal integration, not an automatic feeder or published native-channel implementation.

A median-only diagnostic hid a serious renewal cost. With 1,024 earlier signed/executed entries, each
32-use session replacement reopened predecessor validation: 85–94ms and390–393MB allocated. Keeping
opaque TimelineVerifier.Entry headers allows DocumentRuntime.Session.admit(command, entry, reader,
evidence) to reuse the exact validated original within one invocation. Original identity/time and
native authorization remain intact; message content still validates on demand. Scoped facts are not
serialized into checkpoints or ordinary Language documents. The host retains at most256 recent
headers; eviction falls back to ordinary validation. This is a count bound, not a byte-weighted cache.

Four alternating baseline/candidate JVMs reverse size order for pair two. Every historical entry is
actually signed, acquired, selected and executed. After1/128/1024 prior entries,16 sequential warmups
and16 samples use BEX message amounts. The measured call includes native verification, BEX, session
loading/checkpoint export and atomic JSON-boundary MemoryStore publication. Acquisition, signing,
selection and readback are separate. Each measured window contains one session renewal.

| Prior entries | Renewal before (two JVMs) | Renewal after (two JVMs) | Allocated before | Allocated after |
| --- | --- | --- | --- | --- |
| 1 | 14.549 /5.003ms | 8.278 /1.947ms | 15.48 /12.39MB | 5.68 /3.89MB |
| 128 | 18.082 /15.201ms | 3.061 /1.878ms | 51.02 /54.05MB | 4.77 /3.75MB |
| 1,024 | 84.804 /93.550ms | 2.126 /2.300ms | 392.79 /390.26MB | 5.20 /5.21MB |

Candidate median calls range1.20–3.51ms, including the colder first group. Some ordinary-call allocation
increases: at1024 entries, about2.13→2.94MB, while eliminating the large renewal allocation. Exact gas,
checkpoint identities and checkpoint sizes match every baseline/candidate window. Final checkpoints
are14,656/14,665/14,671bytes for the three sizes. These are local sequential measurements, not AWS
latency, native protocol conformance or a throughput guarantee. Old backlog entries outside the header
window can still reopen history. Cold archive replay remains linear and was not optimized here.

Full strict builds pass2,263 library/147 host tests, zero failures/errors/skips. Two new public library
cases remove predecessor bodies before restored-session execution, retain exact gas/checkpoints and
prove missing native evidence still fails. Ordinary Language admission still refuses the missing
history afterward. Seventeen host cases cover source ordering/publication and actual execution,
including within-batch restart, frozen original-message reads and cache eviction.

Four separate Hello JVMs compare actual92 to93 with25 warmups/30 samples. Baseline medians107.266/
109.517ms and173.30/176.96MB; candidate108.423/107.820ms and176.13/176.61MB. Exact gas/identities match;
timing/allocation ranges overlap and no initialization gain is claimed. Initialization remains costly.

Decision: keep the source/result atomicity and measured renewal fix. AWS remains86; no native service
was enabled. Required-source derivation, historical rebase/access, published channel bindings, lineage,
transport/provider services, efficient authenticated cold restoration, arbitrary old backlog, memory
weight/retention, original MyOS integration and distributed sharing/cycles remain unfinished. The host
TIMELINE-PROCESSING.md and manifest-93.json retain the detailed method, raw arrays and capability limits.

## 94. Exact Timeline channel adapter and authenticated access reuse — keep

`TimelineChannels.verifiedBy(publication, historyVerifier)` now selects the two exact published
Coordination channel bindings. Contracts and DocumentRuntime builders install both and Dispatch
before initialization, checking all conflicts before mutation. The generic NativeChannel tariff is
unchanged. The published adapter charges only accept:1 and refuses a mismatched runtime before calling
the host verifier. It requires the pinned Coordination publication and an explicit verifier.

The immutable request distinguishes the original entry from a local command envelope and includes
the exact receiving snapshot, timestamp, declarations, generations, headers and checkpoints. The
verifier returns exactly one assessment per unchanged receiver. Missing/duplicate/altered rows and
inconsistent operation audiences reject before execution. Missing history is a rejection, never a
guessed INAPPLICABLE. The adapter builds the closed published commitment, sorts by code-point order,
includes negative assessments/reasons and omits raw transport data. The existing authenticated
Timeline Access tracker supplies its verified-history policy through this same adapter, removing
its duplicated native-binding/projection implementation.

This is a protocol adapter and trusted host SPI, not a complete native service. General receiving
history, source closure/derivation, target lineage, private access and feeder integration remain
unfinished. The host's TimelineInputs qualification still uses a separate HMAC test channel. A
user-written verifier can be wrong; callback presence alone does not establish authorization.

Eleven new cases cover exact binding fields/tariffs, runtime mismatch, Unicode assessment ordering,
original-entry identity, restoration, atomic registration conflicts, invalid receiver assessments,
negative receipt sensitivity and transport omission. The existing20 actual signed Access cases also
pass. Full strict builds pass2,274 library/147 host tests, zero failures/errors/skips.

The first final-artifact comparison used four alternating JVMs with1/128/1024 prior entries,
16 sequential warmups and16 measured signed updates. Exact gas, receipts and after-documents matched,
but first-workload timing varied: at1024, baseline7.18/11.44ms and candidate6.82/15.77ms.
These arrays are retained, not replaced by a more flattering run. A separate bounded comparison with
96 warmups/32 samples and reversed1/1024 order tested warmed behavior:

| Prior entries | Baseline medians, two JVMs | Adapter medians, two JVMs | Baseline allocation | Adapter allocation |
| --- | --- | --- | --- | --- |
| 1 | 5.584 /5.534ms | 5.627 /5.580ms | 13.398 /13.482MB | 13.536 /13.559MB |
| 1,024 | 5.447 /5.609ms | 5.243 /5.793ms | 13.394 /13.511MB | 13.547 /13.587MB |

All exact gas, receipts and document arrays match. Warmed timing overlaps; adapter allocation is
roughly0.6–1.1% higher in these paired observations. No speedup is claimed. The measured call includes
signature/history verification, actual Contracts/BEX and result projection. History establishment,
proof construction, transport and storage are excluded. The retained tracker is not a cold-recovery
or bounded-memory benchmark. First-use measurements remain separately recorded.

The independent Hello comparison uses25 warmups/30 samples in four alternating JVMs. Baseline
101.568/103.859ms and174.54/174.31MB compares with102.211/103.826ms and173.07/174.14MB. Exact gas and
initialized identities match; ranges overlap. This adds no established initialization improvement.

Decision: keep the reusable exact profile and removal of duplicate access-specific plumbing, with
its measured allocation tradeoff. No gas or published package change was made. AWS remains86;
general native services were not enabled. Cold recovery (~1.4s/4.3GB cumulative allocation at1024
entries in the earlier archive measurement), old backlog validation, full MyOS integration,
distributed sharing/cycles and production qualification remain. Usage is in TIMELINE-CHANNELS.md;
manifest-94.json binds the raw arrays, executed probes, fixture, source qualification and artifacts.


## 95. Atomic document lineage and bounded identity lookup — keep

The native-enabled host now publishes initial and committed local body identities with state,
immutable history and source progress in the same conditional transaction. A per-document coverage
revision certifies complete indexing through each committed version. Account-scoped identity rows
retain the first publication and distinguish independent managed documents with identical bodies.
Lookup reads coverage first, membership second and one exact historical checkpoint third; it neither
scans history nor treats arbitrary available content as established lineage. Exact snapshot/edition
association and the requested receiving version are checked. The three-read bound is tested after
1 and128 operations; payload size still affects each read.

Prepared fresh children now expose their authored pre-initialization identity through
Attachment.initialBlueId(). This transient provenance is not serialized into canonical snapshots.
Old constructor overloads remain; the record gains a fourth component. It supplies initial identity
for newly created children, not a general reconstructed origin for restored documents. Canonical gas,
receipts and document/checkpoint identities remain unchanged in qualification.

One batched index read and usually two writes (coverage plus new identity) are added to an existing
native-enabled publication. The namespace-disabled/default AWS configuration does no index work.
Index writes count toward the100-item transaction limit. Existing unindexed histories or gaps left
by older nonindexing writers require explicit migration, which is not implemented yet. A race with
newer coverage retries; an actual gap fails closed. Failed initialization publishes no lineage.
Shared members in one fresh graph remain explicitly unsupported; tests retain that rejection and
check identical children under two independently created parents instead.

Full strict builds pass2274 library/162 host tests, no failures/errors/skips. Fifteen new host cases
cover isolation, historical/version lookup, repeated identities, initial child provenance, failed
initialization, mixed writers, lost responses, preparation races, corrupt associations, exact
publication coupling and bounded reads. Two existing fresh-subtree library tests now assert the
original child identity separately from its initialized state.

Four alternating JVMs compare actual94 to95, reverse size order in pair two and execute every signed
historical input. Each size uses16 warmups/16 samples after1/128/1024 prior entries. Timed admission
includes native test-HMAC, real BEX, checkpoint/session work and JSON-boundary MemoryStore publication;
signing, acquisition, selection and readback are separate. At1024 entries, baseline medians1.028/1.025ms
compare with1.058/1.116ms; allocation2.932/2.934MB compares with2.979/2.979MB. Renewal remains about2ms.
First-size startup variation is retained (baseline3.314 versus candidate3.333ms). Exact gas, document
identities and checkpoint sizes match in all four runs. The index adds cost; no speedup or AWS/native
service qualification is claimed. This is an internal routing prerequisite, not the completed feeder.

A separate four-JVM Hello comparison with25 warmups/30 samples keeps native indexing disabled.
Baseline medians102.816/101.057ms and172.824/171.537MB compare with101.295/101.741ms and172.715/172.858MB.
Exact gas/identities match and ranges overlap. Initialization remains materially expensive.

Decision: keep bounded, atomically established identity lookup and the initial-child provenance.
The general Timeline verifier, automatic source derivation, private access/operation routing,
efficient cold recovery, MyOS interfaces, Mandates/Time, distributed sharing/cycles and production
qualification remain incomplete. The index covers initial and committed local bodies, not arbitrary
expanded graph views or transient intra-invocation identities. AWS remains86. DOCUMENT-LINEAGE.md and
manifest-95.json record the boundary, measurements and artifact provenance.


## 96. Published Timeline ingress through the durable host — keep

The host now derives its required public sources from actual receiving declarations and executes
original signed Timeline entries through the exact published channel bindings. TimelineIngress uses
TimelineArchive verification, TimelineInputs closure/order and DocumentLineage target history. There
is no test HMAC permit on this path. Both Public Timeline Channel and exact Timeline/Actor selection
are tested. This is internal configured Java infrastructure, not an automatically enabled HTTP service.

DocumentRuntime.Session.reception() exposes an immutable snapshot/receiver view using the same
Contracts registry and receiver extraction as execution. It resolves composites/all channels and raw
workflow/native-handler observers once per inspection, rather than reparsing YAML or walking every
selector separately for every receiver. Header identities preserve supplied values above the published
schema boundary; contextual field descriptions do not alter provider/timeline/actor identity.
Session.operationRequest(originalMessage) uses Contracts grammar and scoped lazy content access.
Inspection does not initialize, invoke verifiers, advance channels or grant authority. Terminated
receivers expose no active native channels.

The ingress freezes the receiving version, source cut, original entry/time, target lineage and every
channel assessment. The native callback checks snapshot, scoped input identity, original occurrence,
exact declarations/lifetimes/checkpoints and evidence binding. Its frame exists only during one
synchronous call, is isolated per thread and is removed in finally. Arbitrary serialized source-cut
JSON cannot create it. Receiver history must use the same managed store as Core. Unknown targets and
missing/equal source bounds wait. Unaddressed operations can reach raw observers without executing
their operation. Established all-negative input publishes assessments/progress without document work.

Local command checkpoints now have an immutable TIMELINE_EVENT index tied to the exact source-history
publication, input identity, receiving version and timestamp. Admission compares original Timeline
order, never a guessed order of command envelopes. The additional row shares the document transaction.
Same-command publication races now recover the winner's exact receipt; different competing commands
still require reselection. A signed-source-driven child emits an event that updates its independent
parent through the durable outbox, with repeat draining leaving the aggregate unchanged.

Source sets AND declaration/lifetime/routing shape are pinned in progress. An actor change on the same
source requires explicit historical rebase rather than silently skipping backlog. Rebase is still
unimplemented. Private sources wait for historical access; signatures alone do not authorize private
reads. Source records from older ingress versions lack original-checkpoint/receiving-shape association
and need migration. Full native protocol/service, historical embedding/access obligations, private
revocation/restoration, provider transport, Mandates/Time and original MyOS interfaces remain required.

Strict builds pass2279 library/178 host tests, zero failures/errors/skips. Five library tests own
semantic inspection/callback agreement, inheritance, immutability, restoration, termination and
operation recognition. Sixteen host cases own real signed ingress, closure, both bindings, actors,
targeting/observers, public/private boundary, concurrent scopes/workers, lost responses, missing
checkpoint history, store association, lifetime changes and child-to-parent propagation. Initial
failures exposed contextual header identity, lazy message scope and same-command conflict recovery;
those implementation defects were fixed. Fixture-only corrections used the actual published composite
name, YAML indentation and embedded delivery's /event/amount path. An initial pre-init inspection
rejection assumption was removed: prepared snapshots can be inspected without executing initialization.

Three JVMs reverse size order in the middle. Each prelude of1/128/1024 actual signed/executed entries
is followed by64 warmups and32 measured calls. The timed call includes receiving inspection, source
selection/closure, message recognition, lineage/checkpoint lookups, native admission, real BEX,
session restoration when needed, export and atomic JSON-boundary MemoryStore publication. Signing,
acquisition and readback are outside the call; acquisition is reported separately. Every operation
checks business totals and public events. All per-size gas, identity and checkpoint arrays agree
across forks.

| Prior entries before64 warmups | Full-step medians, three JVMs | p95, three JVMs | Measured renewal calls | Median allocation |
| --- | --- | --- | --- | --- |
| 1 | 3.108 /1.663 /3.003ms | 4.028 /3.800 /4.274ms | 7.094 /5.463 /7.075ms | 4.177 /4.332 /4.181MB |
| 128 | 2.225 /1.748 /2.295ms | 3.778 /3.362 /3.015ms | 4.676 /3.896 /5.995ms | 4.896 /4.414 /4.893MB |
| 1024 | 1.679 /1.797 /1.766ms | 3.685 /3.423 /3.650ms | 3.685 /3.423 /3.650ms | 4.337 /4.961 /4.335MB |

First-workload JVM variation remains visible; larger history is not intrinsically faster. No history
size penalty is established in these samples, but this is not a throughput or AWS latency claim. This
measures a newly implemented path, not a before/after speedup. Warm acquisition medians1.84–2.38ms are
separate; cold archive recovery still replays all history. The existing256-header count bound does not
solve arbitrary old backlog or weighted memory. Final local checkpoints are17,644/17,653/17,658bytes.

A separate four-JVM95→96 regression retains the identical HMAC source workload to check the shared
receiver refactor and extra checkpoint publication. Exact gas/identities/checkpoint sizes match.
At1024, baseline1.237/1.133ms and2.975/2.978MB compare with candidate1.091/1.131ms and3.138/3.126MB.
Timing overlaps; allocation increases about5.0–5.5%. This cost is retained for original-order integrity,
not advertised as an optimization. The Hello comparison uses25 warmups/30 samples: baseline101.889/
102.310ms and172.582/172.588MB; candidate102.114/103.068ms and172.499/172.482MB. Exact gas/IDs match;
initialization remains expensive, with no established improvement.

Decision: keep the working public-source integration, shared semantic inspection and original-order
publication/recovery. No published package or gas rule was changed. AWS remains86 and was not rechecked
live or redeployed in96. Full MyOS integration, native services, private/historical ingress, distributed
sharing/cycles, retention, efficient cold recovery and production qualification remain unfinished.
RECEIVING-STATE.md, TIMELINE-INGRESS.md and manifest-96.json retain API usage, boundaries and evidence.


## Iteration97 — current runtime deployed and published Timeline path qualified on DynamoDB

Keep. No runtime code, gas or publication changes since96. Corrected the capability table to reflect
implemented host source derivation and lineage while retaining private/rebase/service gaps. The
companion host adds a separately packaged live qualification using ephemeral Ed25519 private keys;
the exact Coordination fixture is outside its service JAR. Both published channel bindings, actual
BEX, durable parent events and cold original-order continuation passed locally and on DynamoDB.
The host's iteration97 report records all commands, bounds and samples. Library2279/host178 tests and
14 launcher checks pass; staged/actual JAR hashes match. AWS now runs the qualified96 runtime in the97
distribution, not86. Published Hello/Activation and the80,513-document tree pass again. No production
parity, performance improvement or service-level native feeder is claimed. New evidence identifies
idle outbox discovery and cold history replay as separate host costs for the next iteration.


## Iteration98 — reject delayed background polling; retain the comparison tool

No library runtime, gas or specification change. A companion-host polling candidate passed187 host
cases but delayed unhinted updates in a four-JVM comparison. It reduced idle work reads from about
98–108/s to16–32/s while unhinted completion increased from35–108ms to285–297ms. Both candidates
used an explicit250ms shared discovery gate. This cost conflicts with prompt propagation, so the
policy and candidate-only tests were reverted. The final host JAR exactly matches97, with178 retained
host tests; unchanged library2279 and16 launcher checks remain green. The reusable qualification tool
runs real Server workers and separates idle I/O from hinted/unhinted completion. Its prepared AWS
launcher has only hermetic qualification so far. No AWS action occurred in98. Raw arrays, source patch
and artifact pins are in manifest-98; the host's WORKER-DISCOVERY.md documents method and limitations.

## Iteration99 — prepare the host input identity without redundant admission

Keep a small host-only simplification. NativeInput now uses Language.blueId(Input) for its own
two-string command/event-identity envelope. The actual event, native authority and document still
follow their full validation paths. A focused test compares the result with fully admitted content
and independently changes namespace, account, document, command and event. Library code, gas and
published semantics are unchanged. The full host build passes179 tests; the unchanged library has2279.

Four alternating baseline/candidate/candidate/baseline JVMs execute actual published public Timeline
inputs after1/128/1024 earlier entries, with64 warmups and32 measured calls per size. All gas, identity
and checkpoint-size arrays match. Each matched workload/order allocates0.124–0.173MB less per call
(about2.9–4.1%). Timing is mixed: at128 entries the ascending pair improves2.259→2.206ms but the
descending pair worsens1.765→2.033ms. At1024 the pairs are1.842→1.721ms and1.743→1.731ms.
Keep the more appropriate identity-only API and consistent allocation reduction; do not claim a
general latency improvement. These are local MemoryStore measurements, not AWS or throughput tests.
No deployment occurred;97 remains the last live qualification. See manifest-99 for raw evidence.

## Iteration100 — preserve persistent-map paths when evidence has not changed

Keep. Cold archive profiling identified repeated immutable-map updates alongside larger semantic
validation costs. LanguageMap now keeps its existing root when the identical value is registered
again. Bulk insertion skips empty/self inputs, retains the normal empty-target bulk-copy path and
updates only changed entries otherwise. Distinct equal objects still replace the previous instance;
null/absent keys remain distinct. No validation, work counter, gas, public API or specification changes.
Two capability tests protect physical snapshot retention, replacement identity, nulls and old-view
immutability. Full strict builds pass2281 library/179 host tests.

Four unprofiled JVMs run baseline/candidate/candidate/baseline, reversing1/128/1024 history-size order
for the second pair. Fresh archive construction and full signed-history verification are included
in cold timing. Warm acquire and observer catch-up each have16 warmups/16 samples. Exact restored
heads/bounds, every head/evidence/position, committed content roots and logical history/publication
work match. No historic proof or signature is skipped.

| History | Baseline cold time, two JVMs | Candidate cold time | Baseline allocation | Candidate allocation |
| --- | --- | --- | --- | --- |
| 128 | 215.639 /179.898ms | 198.803 /166.886ms | 548.713 /536.333MB | 438.658 /438.113MB |
| 1024 | 1448.600 /1469.626ms | 1304.384 /1369.735ms | 4363.189 /4284.345MB | 3493.712 /3502.089MB |

At1024 this is about7–10% lower elapsed time and18–20% lower cumulative allocation. Warm acquisition
medians1.867/1.844→1.685/1.670ms and allocation5.567/5.485→4.714/4.703MB also improve in this bounded
local run. One-entry cold times are mixed27.693/8.386→27.380/9.844ms; do not generalize a universal
latency gain. Recovery remains linear in history and still allocates gigabytes cumulatively.

The first candidate used a generic forEach bulk path. Its Hello medians regressed100.049/100.527→
102.249/104.716ms and allocation171.762/173.329→173.973/176.124MB. That candidate was replaced, not
promoted. The retained revision restores an efficient empty-target copy and avoids the callback.
The final four-JVM Hello comparison (25 warmups/30 samples) is100.323/100.911→99.734/100.107ms and
172.154/172.440→170.209/171.005MB. Exact gas and initialized identities match. This is a small allocation
improvement, not a solved initialization problem or a throughput guarantee.

An initial measurement check incorrectly required identical bytes for intermediate radix pages.
Different insertion orders can produce different temporary page bytes. The corrected comparison
checks every committed content root plus exact semantic results; raw physical counts/bytes remain
reported. Diagnostic JFR sampling is separate from the paired runs; its initial startup allocations
are excluded by requiring a restore frame. All raw comparisons and the superseded candidate are
retained in compact evidence referenced by manifest-100. The host report records the AWS rollout.


## 101. Bounded durable history read-ahead — keep after paired AWS recovery

Fresh TimelineArchive recovery previously read every history row, batch manifest and batch body
individually. It now reads immutable history in windows of at most32 positions, prefetching each
manifest and only its first64KiB chunk. One-batch warm catch-up keeps the old single-read path.
AtomicStore's default content batch preserves compatibility with memory/fault stores; DynamoStore
uses strongly consistent BatchGetItem with deduplication and bounded retry of unprocessed keys.
Every page remains tenant-scoped, size-bounded and hash-checked. Existing32MiB page-cache bounds remain.

Read-ahead never establishes authority. Every batch still passes TimelineVerifier in order with its
own historical content root. Recovery pins the initial committed head, checks exact history keys and
versions, and publishes its cached verified prefix only after all signatures, entries, bounds,
successors and final roots agree. Tests cover a source advancing during a65-entry recovery, missing
later history, corrupt prefetch, partial DynamoDB responses, interruption and later-chunk corruption.
The staged strict host build passes186 cases;18 hermetic qualification-launcher checks and both local
recovery/native probe preflights pass. Library code is unchanged from100 (2281 passing cases).

Four local baseline/candidate/candidate/baseline JVMs retain exact restored heads, all committed
content roots, original identities and logical history work. Their MemoryStore path cannot measure
network savings. At1024 entries local cold medians/observations are1331.433/1359.384ms baseline versus
1364.467/1362.868ms candidate, with3.50–3.53GB cumulative allocation for both: no CPU speedup claim.
Hello initialization medians100.775/101.606ms versus100.388/100.835ms overlap; gas/identities match.
The final qualification JAR differs from the paired local tool only because a local preflight mode
was added to TimelineRecoveryProbe; the measured HostInitTiming and ColdTimelineProbe are unchanged.

The isolated AWS comparison seeded49- and128-entry histories once with the baseline, then used the
same public fixture and stored signed evidence for four JVMs in ABBA order. Each source/fork performs
one unmeasured recovery and three samples, each with a fresh Repository and TimelineArchive. The SDK
and JVM are warmed; source-definition admission/setup are outside timing. This is archive recovery,
not whole-service cold startup or steady-state event execution.

| History | Baseline fork medians | Candidate fork medians |
| --- | ---: | ---: |
|49 acquisitions |1314.639 /1452.210ms |1030.645 /1073.089ms |
|128 acquisitions |2318.995 /2722.958ms |1850.083 /1942.893ms |

Matched pairs improve roughly20–29%. At49 entries50 single row reads plus308 single content reads
become1 row read,2 history batches,210 content reads and4 content batches (358→217 store calls).
At128 entries129+860 single reads become1+4+604+8 (989→617). Transport retries, if any, would be inside
a batch call; these are store-call counts, not a direct HTTP request trace. All samples restore the
same signed heads/completeness bounds and report zero storage failures. Median main-thread cumulative
allocation falls220–221→213–214MB and528–531→499–501MB respectively. Other evidence/index pages are
still demand-loaded, and signature/content verification still scales with retained history.

SSM comparison3adb7abb-add7-48e4-8cae-e815b45ce169 reached Success and restored the main100 service/profile.
Candidate hostcdfa2a61d8516707d3ba60f9f43c3b65db8b58e47c23fb2069e922c1ab153264;
common library293ea1139bbe5863f2e3b46de611fe584c4dd8b8b1951807ea63d7c7e92da3a1;
final tool7907aaf82e7b8262498294041661f846882ff61c4d025297ad2286c4ae0c8253.
Keep the bounded transport change; no gas, package, signature, authority or specification change.
Full MyOS/native feeder integration, private/rebase, Mandates/Time, distributed sharing/cycles,
initialization allocation, retention and production qualification remain unfinished.


Iteration101 was promoted to both actual projects; their host build passes186 tests and its runtime/
qualification JAR hashes match the measured stage. Library2281 results apply to unchanged code.
Distributionb130a3fae8415fcfdbe0579738a96e61fce351f3f11a8755ac45b5113472f88a is deployed. All live commands reached Success:

- deploymenta7c0c3b2-2013-448a-b099-7ba6f6ff11e7;
- published Timelined1bedafa-494c-41db-b194-13b25c61eba0;
- Hello4599dbe0-24de-48d8-a494-7484daba4272;
- large tree56880cf8-9737-4052-b918-258fb9de6a02.

The post-deploy Timeline check uses16 warmups/32 samples, two commits/sample, gas752, no recorded
storage failures/retries, exact events and original-order restart continuation. Medians: ingress
67.369ms, runtime5.365ms, through parent plus quiescence170.213ms,
separate acquisition46.141ms. Cold archive/session recovery after49 entries is942.106ms.
These are unpaired acceptance observations; the controlled same-history comparison above supports
the optimization decision. Final child51/parent350 and the following exactly-once entry pass.

Published Hello/Activation preserves initialization, activation, exact message/audit/events, input
receipt and rejection identity/version. Single approve/hello/reject HTTP observations are
205.870/114.838/51.539ms; they are not latency distributions.
The existing20,128-order/80,513-document tree passes128 warmups and32 measured payments across32 distinct
orders/payments: runtime2.169ms, durable leaf25.899ms and through agreement110.319ms medians.
There are96 commits, zero recorded failures/retries and finalpaidTotal133518.
Target enumeration11.930s and target lookup are separate and excluded.
The main service is healthy afterward with8 workers and nativeAdmission=false; temporary Hello stopped.
No steady-state speedup or production-throughput claim follows from these bounded smoke results.
See manifest-101 for compact raw evidence and artifact/source checks.


## 102. Trusted Timeline configuration and normal HTTP processing — keep

The normal Main/Server now loads explicitly adopted provider/timeline definitions and exposes source
head, signed-evidence acquisition and one-entry processing. HTTP selects an account-local alias;
provider keys, trust and the required source set are not request-controlled. Exact runtime receiving
declarations determine every required source. Namespace and aliases are conditionally persisted and
cannot silently change across restart. Source files have pinned BlueIds, strict UTF-8, bounded size
and confined paths. This is a trusted loopback operator API, not new application user authentication.

Startup initially rejected the full Coordination publication requirement, correctly: the selected
Timeline channel profile does not implement the entire native protocol. Package purpose now defaults
to execution (unchanged strict behavior); explicit definitions loads verified type data only. The
separate timelines section installs exact supported channel bindings and Dispatch. Full execution
requirements continue to fail closed. No canonical package, gas or signature rule changed.

The final strict staged host build passes195 tests, zero failures/errors/skips;19 hermetic launcher
checks pass. Library runtime code is unchanged from100/101 (2281 recorded passing tests). New tests
cover real HTTP execution, strict quiet-source closure, invalid signatures, missing/corrupt referenced
content, forged admission claims, parent events, restart, persistent alias/namespace changes,
conditional startup races and lost commit responses. Main shares one store with the native runtime
and closes it on failed bootstrap.

A final-artifact local probe starts actual Main and uses only HTTP, with4 warmups/8 samples. It passes
all state/event/closure/idempotency assertions. Its MemoryStore does not establish process-restart
durability. Host f717652430e8e3051e5d7d6d73aaf8d372c151fde69768b36ad28fc989ef00fd;
library293ea1139bbe5863f2e3b46de611fe584c4dd8b8b1951807ea63d7c7e92da3a1;
tool c08b7ffb11ca57bfad25760d2f0737ab64d707a619b0a09e79f823686a9d8a82.
This is integration evidence, not a paired performance optimization. The separate DynamoDB probe
will restart Main, continue the original signed history and verify parent propagation exactly once.

Decision: keep the integration; promotion/deployment evidence follows. Automatic provider HTTP
acquisition/pagination and durable receiver scheduling are the next required work. Private access,
receiving-history rebase, Mandates/Time, distributed sharing/cycles, full MyOS APIs and production
qualification remain incomplete. The static source registry is bounded to64 sources; it is a bootstrap
configuration, not the final multi-tenant registration service.


Iteration102 is promoted, deployed and qualified. The actual host build passes195 tests and its host/
library/qualification JARs match the final staged artifacts. Deploymentc08ba929-5d17-4522-b0b0-904937064607
reached Success for distribution720128ba00768a24f69de6da86048a258d3ec643471fec335841181b0e661f9a.

The real-Main HTTP/DynamoDB qualificationa0d51edc-8c2d-4df5-923b-30a04c9ab925 passed:
invalid signature does not advance history; missing/equal-bound quiet sources block; a caller cannot
choose an incomplete source set; original entries execute exactly once; parent events update totals;
acquisition retries are idempotent; stopping/restarting Main retains aliases/progress and the next
original entry executes once. Final child/parent count98. Provider signing keys stayed ephemeral.
With4 warmups/8 samples, acquisition/HTTP ingress/through-parent medians are
90.150/97.135/200.044ms; runtime within ingress 16.060ms.
This short warm-up qualification is not a steady-state or paired timing comparison. Parent completion
includes HTTP polling; these numbers are not interchangeable with the earlier direct-ingress probe.
The final-artifact MemoryStore Main probe also passed, with separate acquisition/ingress/parent
medians7.189/7.091/17.601ms and no process-restart persistence claim.

The published non-native Hello/Activation checkde97d572-74f2-49ea-b4e1-13d3d0cb1474 also reached Success and preserved
inactive initialization, approval, message/audit updates, exact event payloads and original receipt,
and rejection identity/version. Single approve/hello/reject HTTP observations are
157.793/108.669/50.026ms; not latency distributions. Its temporary service stopped. Main was restored and
healthy with8 workers, host-links3-preview, combined acknowledgments, nativeAdmission=false and
timelineProcessing=false. Test sources belong to the temporary configured runtime; the ordinary
manifest does not adopt them. No new AWS machine was created.

Decision: **keep**. This completes explicit normal-service integration, not the automatic feeder.
The 80,513-document iteration101 evidence is retained; no large-tree or contention rerun was performed
in102 because the library/execution/storage algorithms are unchanged. New checks target the changed
startup, package-selection and HTTP/native-service boundary. See manifest-102 for source/artifact pins,
compact raw evidence and checks. Automatic acquisition/scheduling and the full remaining objective
are still unfinished.


## 107 — Public Timeline page collection and automatic host integration

Added `TimelineReader.Page.claimedHeadId()` so a bounded page collector can distinguish a signed head
whose body will arrive later from already acquired content. Its documentation and tests preserve the
difference between a claim and a completed verified prefix. No Language, BEX, Contracts or gas rule
changed. Strict library checks pass2281 tests (identical-input cache reuse where applicable).

The Better host now connects signed HTTPS acquisition to durable subscriptions/fanout and automatic
receiver execution. It also verifies and suppresses differently anchored evidence that establishes no
new completeness/head/successor fact. Actual Main plus HTTPS/DynamoDB qualification proves quiet-source
closure, signature refusal, repeat-poll deduplication, restart and exactly-once child/parent effects.
Full details and timing boundaries are in the host's iteration107 report and manifest107.

Decision: **keep**. The automatic path is opt-in, bounded and public-source-only. Dynamic provider
management, long-read continuation and remaining native/production parity are still required.


## 108 — Host discovery reuse and measured automatic latency

No library execution or semantics change. The host reuses a verified source-owned TimelineReader for
at most five minutes and invalidates it after any failed attempt; fresh responses still undergo the
ordinary reader and archive checks. The host adds bounded phase diagnostics and corrects qualification
median/completion boundaries. Its226 tests and real-Main HTTPS/DynamoDB restart qualification pass.
Six-sample automatic append-to-parent median changes638.490→433.721ms; the configured100ms polling and
remaining storage/signature work are included. This is not a BEX-runtime or large-tree scaling claim.
See host iteration108 and its retained raw observations for scope, limitations and the keep decision.


## 109 — Accepted successor proofs for bounded host acquisition

Added a focused immutable request/response operation to verified read pages:
`page.fetchLastEntry(request).accept(response)`. It authenticates the reusable acceptance attestation
for exactly the last verified page entry. It does not claim that an unread checkpoint is complete or
advance the caller's prefix. The host builds ordinary Timeline Successor Evidence and uses the existing
archive verifier; entries strictly before the accepted successor close, while the successor remains
pending. No package/spec, Language, BEX, Contracts or gas rule changed.

Four new library cases cover valid partial proofs, exact identity/signature requirements, referenced
attestation objects, signed failures, unavailable content and malformed fields. Strict full checks
pass 2285 tests. The host passes 231 tests, fixes prompt reselection after conditional propagation-wake
conflicts, and qualifies chunked acquisition plus actual-Main cold restart on DynamoDB. All 41 source
occurrences execute once and update the independent parent. See host iteration 109 for raw evidence,
timing boundaries and remaining native/production gaps. Decision: **keep**.

## 111. Host owner Timeline console and persistent provider identity

No Blue library code or semantics changed. The Better host now exposes private account-owned Timeline
creation, signed message append and original entry viewing in the existing MyOS-style console. The
host loads a pinned Secrets Manager version once at startup; incorrect key/endpoint/identity fails
closed. Main restart preserves exact entries, provider identity and append receipts. Host tests,
authenticated browser checks and explicit scope are in the host's iteration 111 report.

Keep the integration. It does not make the ordinary runtime a complete native provider: signed
reader authorization, current/historical access, dynamic adoption and native operation delivery
remain unfinished. Console appends save signed messages; document Operations and Payment flow still
use the explicitly selected host-links edition. Library qualification remains 2285 passing tests
from the unchanged iteration 109 runtime code.

## 112 — Shared exact Timeline request parsing and checkpoint-bound host reads

Added the immutable `TimelineRequests` public parser for an independently selected provider and
timeline. Consumer pagination and host assembly now share the same read-request rules. It validates
inherited values, target identity, bounds, exclusive selectors and entry references without treating
an unresolved selector as absent. Parsed data grants no permission or history membership; the host
must recognize selectors in the selected durable source and check authorization on every use.

Strict library checks and Javadocs pass 2298 tests. The new 13 parser cases have an explicit place in
the test strategy. No package, Language, BEX, Contracts, signature-domain or gas semantics changed.
The Javadoc check initially rejected accessors without a main description; those public descriptions
are now complete. Existing consumer tests protect unchanged checkpoint and continuation behavior.

The host's new internal provider read assembler is qualified against the existing `TimelineReader`.
It retains checkpoints across append/restart, bounds pages by count and bytes, and seeks without
scanning all earlier entry bodies. On the existing AWS host, equal-sized retained reads at 9/137
entries have warm medians 33.728/34.299 ms and empty-publisher-cache medians 47.939/48.870 ms, with
identical storage call counts at both lengths. These are provider/storage timings, not document
execution or HTTP latency. See host iteration 112 for exact scope and raw evidence.

Decision: **keep**. The shared-package archive was checked byte-for-byte against the bundled exact
Coordination publication. The host read component remains internal; owner Timeline publication on
AWS continues using release 111. Signed-reader authorization, access/control history, content routes,
dynamic adoption and the remaining native/production work are not completed by this parser.

## 113 — Tentative access execution and authenticated request parsing (candidate)

`TimelineAccessProgram` executes the exact published initial access document and update workflow for
an owning provider before atomic publication. It produces real canonical state, receipts, gas and
events; it issues neither a consumer history prefix nor a permission certificate. Protected snapshot
restoration checks a separately retained identity and original initialization target. Incremental
snapshot evidence omits previously retained complete closures and resolves them through an explicit,
revocable provider scope, still verifying exact evidence normally. No stored wire value can mint a
verified timeline prefix. Cold predecessor validation remains proportional to retained history.

`TimelineRequests.reader` validates optional reader signatures and exact inner request identity.
`access` shares that envelope verification for public Get Timeline Access Request without fetching
or confirming its target. Source/permission/ownership checks stay at their separate host boundary.
Strict library checks and Javadocs pass 2313 tests. Provider-preparation tests compare exact IDs,
receipts, gas and events against independent consumer replay across restored updates, test rollback,
wrong targets and missing predecessor evidence, and compare full/incremental snapshot commitments.

The host candidate adds durable control/access state, source content and native protocol routes, with
281 passing tests. Local measurements show private reader cost remains stable across 9/40 control
entries, but cold access-update content reads grow with history; see host report 113. Decision and
promotion remain pending the remaining performance/real-service qualification. Live release 111 and
actual source checkouts at 112 are unchanged at this checkpoint.


### Iteration 113 — protected publisher recovery follow-up (kept and deployed)

Added `TimelinePublisher` as a producer-only tentative validation head, deliberately distinct from
consumer `TimelineVerifier` evidence. Recovery verifies a separately trusted protected-storage digest
and binds the exact package/provider/source. `TimelineAccessProgram` can restore its original snapshot
with its last consumed input checkpoint and execute a separately prepared current entry. Invocation
scopes reuse schema facts and stop retention at durable heads; neither fact escapes into ordinary
Language admission or a consumer proof. Full snapshots remain evidence-dependent; incremental exports
use the existing exact-content index.

Five new library tests cover strict candidate validation, exact source/order/time, corrupt/wrong-source
recovery, cold updates with historical bodies unavailable and differential receipts/gas/events. Full
library check and strict Javadocs pass (2318 tests). Host interleaving/restart/corruption tests pass too.
This keeps canonical semantics and gas unchanged, adding an explicit protected-publisher recovery API.
The local measurement removes the long-history read amplification. Isolated DynamoDB qualification,
promoted artifact matching and deployed CloudFront grant/read/revoke qualification also pass. Cold
index reads still add AWS latency at longer histories; no flat total-latency claim is made. See the
host's HOSTED-TIMELINES.md for before/after data, trust boundaries and limits.


### Iteration114 — scoped content retrieval and private host acquisition (kept and deployed)

Added immutable `TimelineReader.ContentRead` via `reader.content(request)`. It verifies a bare
source-bound request, provider signature, exact response correlation and inline body identity.
Prepared content remains unadmitted: missing referenced definitions and required-value constraints
are handled by ordinary Language admission after retrieval. Signed failures never advance history.
Shared request inspection reuses the already adopted verifier without repeating provider adoption
for every content fetch. Four focused tests cover successful unresolved definitions, route/source
constraints, wrong bodies/correlation/providers/signatures and invalid extras/failures.

All 2322 library tests and strict Javadocs pass. The host adds pinned private reader custody, bounded
content fetching, revoked-read refusal and independent public control acquisition, with 296 tests.
No Language/BEX/Contracts semantics, canonical gas or business processing algorithms changed.
See the host's iteration114 report and Timeline service guide for explicit remaining boundaries.

The deployed CloudFront qualification passes private acquisition, fresh archive restore without
occurrence replay, revoked-read refusal and the existing desktop/mobile console workflows. A probe
assertion was corrected for legitimate completeness-bound advancement; the runtime stayed unchanged.


### Iteration115 — host historical private admission (kept; library code unchanged)

The host now binds an explicit document-owned access-at-original-input policy to canonical
`TimelineAccess.track` replay, source cuts and native admission.314 host tests pass. A bounded local
probe found7.435→181.619ms cold replay for1→65 control occurrences, versus0.004021→0.004459ms cached
selection with zero archive reads. Full samples, semantics and remaining retention/rebase limits are
in the host report and TIMELINE-PRIVATE-ACCESS.md. Existing2322 library test evidence remains114; no
public library API, canonical semantics, gas or business execution kernel changed in this iteration.


### Iteration116 — scoped canonical replay reuse (kept; source qualified)

Profiling the115 cold replay found repeated operation grammar validation and runtime construction,
not just signature checks. The baseline sampled stacks repeatedly constructed a Contracts registry
for every operation, including identical requests and unchanged initial access definitions.

Three changes stay within existing trust and semantic boundaries:

- Cache successful operation grammar checks by exact request identity inside one TimelineContent
  verification view. Every original entry still validates its source, actor, timestamp, chain and
  contextual constraints; its operation marker must identify the exact message before reuse.
- Reuse one grammar registry for distinct requests in the same evidence view, installing newly
  validated immutable content. Neither successful checks nor the registry survive that view.
- Give each canonical access invocation the opaque header of its already verified original entry,
  using the existing invocation-scoped facility from DocumentRuntime. Close the scope on success or
  failure. Native access checks still run; no stored permission Boolean, new semantics or wire proof
  shortcut is introduced. The old prefix-only scope method is now unnecessary and removed.

Four new test cases cover repeated grammar with invalid marker/time, distinct malformed requests
without partial prefix publication, and8/64-update bulk-versus-individual replay. The latter compares
exact gas, receipts, events, observations and every historical document, including use after close.
All2326 library tests, strict Javadocs, formatting and all314 host tests pass.

The first grammar-reuse candidate helped repeated requests, while changing-request latency remained
within run variation. Sharing the registry extends the improvement to changing requests. Per-entry
scope reuse is retained with exact equivalence coverage; an isolated substantial speedup from that
subchange is not claimed.

The final comparison used the same qualification-tool JAR against separately pinned baseline115 and
candidate library JARs. Two independent JVM rounds reversed variant order. Each round used two
warmups and eight cold replays per size; the table pools16 measured cold samples. Each replay checks
its exact executed state identity against the canonical publisher. Changing requests retain the
transport reader key and replace an additional key each time. Allocation is main-thread bytes during
the action, not retained or peak heap. Both versions use MemoryStore with signed imports prepared
before measurement. No HTTP, DynamoDB, business document execution or parent propagation is timed.

|65 control entries|Baseline115 median|Candidate median|Reduction|Allocated bytes, baseline → candidate|
|---|---:|---:|---:|---:|
|Repeated request|180.310ms|147.164ms|18.4%|481,429,744 →332,666,464 (30.9%)|
|Changing request|209.198ms|166.372ms|20.5%|554,745,384 →401,639,496 (27.6%)|

The one-entry medians stayed around7ms, with no consistent improvement. Warm selections stayed
around0.004–0.005ms and performed zero archive reads. Cold replay still grows with control history,
and remaining temporary allocation is too high for a claim of production-grade cold recovery.
Consumer checkpoint/retention work, historical policy rebase, dynamic native console routing and
broader native service coverage remain unfinished. No new payment latency improvement is claimed.

Decision: KEEP the measured combined change. Source is qualified; the live console remains115 until
this candidate is included in the next tested deployment. Raw comparisons, scope metadata and exact
binary digests are in `iteration-116-comparison.json`; no profile data is runtime authority.


## Iteration117 — immutable identity metadata (keep)

The116 allocation profile identified canonical helper serialization inside logical identity
construction as the largest sampled allocation source. Ordinary immutable values now retain their
canonical UTF-8 helper length alongside the existing digest. Contracts still traverses and charges
all required children, identity construction, rebuilt members and every logical hash block. The
memo avoids repeated serialization and hashing; it is not admission evidence or a construction
witness. Cyclic-token hashes never populate this ordinary memo, and pure lists retain their folding
algorithm. No published semantic edition, tariff or receipt format changes.

`ContractIdentityTest` owns exact cold/warm charges, Unicode/escaping, failed gas limits, typed lists,
objects, merge policy, pure-list folding and distinct cyclic scopes. The full library and host suites
are required before promotion. Previously published canonical fixture identities remain unchanged.

Two reversed-order JVM rounds compare the same qualification tool against preserved116 and117
binaries. Each replay population has16 cold samples; allocation counts bytes on the calling thread,
not retained heap. Signed import is setup; network, DynamoDB and parent execution are excluded.

| Local scenario |116 median|117 median|Allocated bytes,116 →117|
|---|---:|---:|---:|
|65 control entries, repeated requests|141.430 ms|120.786 ms|329,705,636 →255,952,776|
|65 control entries, changing requests|168.727 ms|144.153 ms|401,586,980 →327,049,724|
|One control entry, repeated scenario|7.127 ms|7.246 ms|15,301,648 →15,314,500|
|One control entry, changing scenario|6.981 ms|7.353 ms|15,321,940 →15,348,352|
|Payment runtime,256 samples per variant|1.181 ms|1.176 ms|935,648 →894,656|
|Hello-clause host initialization,30 samples per variant|113.628 ms|105.707 ms|172,510,616 →160,565,808|

The longer replays improve14.6% with18.6–22.4% less allocation. Initialization improves7.0%; the
payment latency difference is negligible, with4.4% less allocation. Single-entry cold replay is
slightly slower in this bounded run; no single-entry speedup is claimed. Business probes check exact
gas arrays and final identities/state across every run. Replay probes check canonical permission
state and keys. Keep the modest, scoped optimization; it does not solve history-length growth or
consumer recovery. Raw samples and pinned binary digests are in the117 report artifacts.

This local comparison makes no new AWS latency claim. Deployment and post-deployment qualification
are recorded separately. Earlier source-qualified116 changes are included in the next release.


### Release117 qualification

All2,337 library tests and314 host tests pass, with no failures or skips, plus strict compilation,
formatting and Javadoc. Rebuilding the promoted source produces identical library, host and tool JARs.
Release117 was deployed successfully with existing provider identity/custody and runtime settings.
Desktop/mobile browser qualification passes bootstrap, editable operation dialogs, child creation,
payment →order →agreement propagation, historical views, Timeline creation/append/entry content,
signed private acquisition, owner grant/revoke and denied access after revocation. QA uses separate
documents. Read-only inspection confirms the user's existing graph remains512 documents:128 orders,
383 payments, version247 and paidTotal4956. No new user business operation was needed for this release.

The manifest117 retains raw local comparisons, test/build logs, source digests, release pins, browser
results/screenshots and the post-deployment graph result. Earlier116 source-only status is historical;
its changes are now live in117. Business Timeline processing remains disabled in the ordinary service.


## Iteration118 — definition traversal iterators (revert)

A fresh117 Hello initialization JFR sample changed the next target: recursive `visitDefinition`
iterator allocation outweighed fresh canonical serialization. The candidate used indexed recipe
parts and sorted-map `forEach` traversal, preserving the selected children, read order and charges.
Existing definition sharing/failure/concurrency, published BEX/Contracts and clause integration tests
passed. Two reversed-order JVM rounds checked exact gas arrays and final identities/state.

| Local scenario |117 median|Candidate median|Allocated bytes,117 →candidate|
|---|---:|---:|---:|
|Payment runtime,256 samples per variant|1.018 ms|1.096 ms|873,396 →890,504|
|Hello initialization,40 samples per variant|103.445 ms|101.058 ms|159,393,768 →157,141,544|

Initialization improved only2.3% and allocation1.4%; the payment comparison was7.6% slower with2.0%
more allocation. These bounded observations do not isolate a causal payment regression, but they
also do not demonstrate a useful overall improvement. Decision: REVERT. Production source is restored
byte-for-byte to117. No AWS deployment or semantic change was made for118. The deployed117 console
and512-document walkthrough remain the current accepted checkpoint.

The sanitized profile summary and raw benchmark samples are retained with manifest118. Raw JFR and
its process/system metadata remain temporary diagnostics and are excluded from the project report.
Next work should target repeated definition interpretation more substantially, while preserving
per-invocation validation, evidence opening and exact logical charges; a cached value alone is not
sufficient authority to skip those steps.


## Iteration119 — invocation-local definition read reuse (revert)

The next candidate recorded the ordered charge sequence after a complete successful definition
read. Reuse was confined to the identical invocation ledger and evidence context, bounded to8 entries /
512 KiB, with oversized inputs falling back to ordinary traversal. It preserved individual charge
boundaries, current owner attribution and first-read manifest/witness establishment. Five focused
tests covered warm/cold exact charges, each gas-limit boundary, changed definitions, new ledgers,
replacement evidence contexts and large/many-definition fallback. Existing definition, portable,
canonical BEX/Contracts and published-clause tests also passed.

Two reversed-order JVM rounds checked exact gas arrays and final state/identities. The real workload
did not benefit:

| Local scenario |117 median|Candidate median|Allocated bytes,117 →candidate|
|---|---:|---:|---:|
|Payment runtime,256 samples per variant|1.096 ms|1.107 ms|891,716 →892,000|
|Hello initialization,40 samples per variant|103.445 ms|107.661 ms|159,855,000 →161,581,336|

A separate instrumented two-initialization diagnostic recorded2 definition-read cache hits and8
misses, despite6 shared definition-value cache hits. The opportunity inside one invocation is too
small for this implementation to offset recording and lookup cost. Initialization was4.1% slower
and allocated1.1% more; the payment difference was about1%. Decision: REVERT, including the proposed
class and its candidate-specific tests. Their sources and measurements remain in the119 evidence
folder for inspection. All accepted source and rebuilt JARs match117; no119 AWS deployment occurred.

The finding redirects optimization toward first-time interpretation and validation rather than an
additional memo at this boundary. Capability documentation was refreshed to distinguish implemented
host private-read/history features from still-missing consumer recovery and native console routing.


## Iteration120 — owner Timeline sharing controls (keep, deployed)

The console's new Sharing tab exposes the already implemented executed Timeline Access protocol.
Owners can grant a reader public key, inspect current canonical access documents and revoke a whole
grant. Existing native multi-key/multi-Timeline grants are shown with their complete scope; revocation
confirms that scope. Different active native permissions cannot be silently overwritten by the simple
single-reader form. Empty grants can be reactivated. No parallel authorization model or console-account
invitation system was added; reader applications use signed native requests.

Host grant/reactivation and revoke use one canonical publication with a durable command receipt.
Older grant retries after revocation never reactivate access; older revoke retries after a new grant
never revoke it again. This is tested across restart and lost replies. The bounded64-grant inventory
batches membership metadata reads. Native Create/Update/Get semantics remain unchanged. All owner
routes retain login, Origin and account isolation checks; the UI never requests a private reader key.

Decision: KEEP this functional capability. All319 host tests pass, plus frontend typecheck/build and
4 existing frontend tests. The unchanged library's2,337-test qualification is reused from117 after
exact source and rebuilt JAR comparison. Browser qualification passes bootstrap, Operations, payment
propagation, History, Timeline append/content, Sharing grant/reload/revoke/regrant, mobile layout,
foreign-account refusal and the real signed private-reader protocol. Test grants use separate QA
Timelines and ephemeral keys; they are revoked afterward. Desktop/mobile screenshots were inspected.

The final deployed artifact is `2c36ee582280c1d64d38f7de6802676f4778e221564d0ae9147a7b6259a963ca`,
SSM command `ab7b4720-266a-476f-8e60-0dbe930d753a`: Success. A preliminary build was superseded by
the reviewed guard protecting active multi-key/multi-Timeline grants; final qualification applies to
this artifact only. Provider identity, key custody, business runtime settings and table are unchanged.
The user agreement is verified read-only:512 documents,128 orders,383 payments, version247,
paidTotal4956. No business latency improvement is claimed for this UI/access-adapter release.

Native business Timeline routing, compact consumer recovery, policy rebase, shared/cyclic durable
attachments and Mandates/Time remain unfinished. The work remains active. Manifest120 retains the
source/artifact hashes, checks, browser results/screenshots and graph verification.


## Iteration121 — render identity helpers without temporary maps (keep)

Ordinary exact nodes already retain fields in canonical key order. Identity calculation now renders
those fields directly, using the existing canonical scalar/quote renderer, instead of creating a
second tree of helper maps and sorting their keys again. List folding renders its fixed helper shape
in canonical `elem`, then `prev` order. All buffers remain invocation-local. No resolver evidence,
logical charges, identity-cache lifetime, node admission or public API changes.

Six new Language identity cases check nested helpers, Unicode/escape ordering, scalar categories,
typed/pure lists, merge policies and cyclic token separation against ordinary helper serialization.
The complete library suite passes2,343 tests, with strict formatting and Javadoc. The unchanged
Contracts oracle and gas-limit tests pass. The host suite and live deployment qualification are
recorded in manifest121; the source comparison pins the baseline to release120 (library117).

A first bounded baseline/candidate/candidate/baseline screen had substantial between-process timing
variation, so one further comparison increased JVM warm-up. Every run checks exact gas and final
identities/state. The follow-up uses1,024 warm-up payments and512 measured payments in each fresh JVM;
Hello uses10 warm-ups and30 samples. There are two JVMs per variant:

| Local scenario |120 median|121 median|Allocated bytes,120 →121|
|---|---:|---:|---:|
|Payment runtime,1,024 measured calls per variant|0.446 ms|0.424 ms|813,204 →811,400|
|Hello initialization,60 measured calls per variant|94.733 ms|93.919 ms|158,779,644 →155,388,484|

Decision: KEEP for the simpler physical construction and3.39 MB (2.14%) lower initialization
allocation. Initialization latency is essentially unchanged. The observed4.84% payment difference
is a small local measurement with between-JVM variation, not a throughput or AWS latency guarantee.
This does not solve the remaining cold definition interpretation/history replay costs. The first
screen, follow-up, inputs, exact-result checks and artifact digests are retained with manifest121.

Tracing console-native routing also confirmed the remaining boundary: hosted Timeline ownership
and provider publication do not establish an executed receiving policy. Dynamic durable adoption,
private historical access, source closure and account-isolated scheduling must precede that bridge.
No implicit Timeline-to-host-operation shortcut or runtime-profile change was introduced.


Release121 is deployed and live browser qualification passes bootstrap, Operations, parent payment
propagation, History, Timeline creation/append/content, Sharing grant/revoke/regrant, foreign-account
refusal and mobile layout. The unchanged provider key/identity also passes real signed private-reader
checks. The user graph was verified read-only after deployment:512 documents,128 orders,383 payments,
version247 and paidTotal4956. Separate QA documents exercised mutations. Artifact
`61933a8475988af31b34c2de27e3d0c4566a77a80a86fa741ada56a782cdb795`, SSM
`1c678f69-791a-4f96-bc11-c8c6999eeada`: Success. The host and console artifacts are byte-identical to120;
only the library runtime artifact changed. No new AWS business-latency comparison was performed.


## Iteration122 — durable, account-scoped Timeline directory (keep)

Added the opt-in `myos.timeline-directory/1` source profile. Operators can atomically adopt exact
provider/Timeline pins after startup, list sources in pages and recover their portable definition
closures after restart without source files. Configuration, alias, Timeline and provider/URL indexes
are published together; lost replies resolve against the complete immutable publication. Changed
bindings require an explicit migration. Directory aliases and content remain account-scoped.

Frozen receiving declarations select their exact source indexes. A warm single-source lookup makes
3 metadata reads both before and after128 unrelated sources, without inventory or content reads.
Parents with no native channels perform no directory lookup. Optional archive reuse is capped at64
entries; eviction preserves durable signed history and process phase counters. The count bound does
not solve retained-history byte bounds or cold replay cost.

The explicit signed-discovery endpoint verifies the reserved public control identity without
advancing history or granting permission. Directory-backed private receivers require that discovery
and exact control adoption before publication. Existing historical private-access checks still decide
permission at the original input, including revoke/restore behavior. Static automatic acquisition is
unchanged. Directory automatic acquisition and reader custody currently fail startup; this is the
foundation for subsequent console-owned source adoption and automatic processing, not that complete
integration. The ordinary public console keeps its existing host-links operations.

Decision: KEEP the functional capability. All334 host tests pass, including actual HTTP child-to-parent
execution across restart, private history in both source profiles, portable collapsed metadata and
cache eviction. Four launcher tests pass. The unchanged library reuses release121's2,343-test evidence
after exact source/artifact comparison. Final Main/HTTP and AWS results are retained in manifest122.
See [the directory guide](../../myos-core-better/docs/TIMELINE-DIRECTORY.md) in the host project for configuration and APIs.


The AWS Main/HTTP directory qualification passed with DynamoDB and a second fresh Main process:
zero bootstrap source files, exact source adoption, foreign-account refusal, rejected rebinding,
invalid-signature refusal, strict quiet-source closure, original-entry deduplication and continued
child/parent execution. All8 original inputs were reflected in the final child/parent total56.
The bounded run used2 warm-ups and4 measured inputs:

| Inclusive phase | AWS median |
|---|---:|
|Signed source acquisition|102.12 ms|
|Native ingress through durable child response|141.87 ms|
|Runtime execution within ingress|15.86 ms|
|Ingress through observed parent update|227.96 ms|

Acquisition is separate; the remaining rows overlap and must not be added. These small qualification
samples have little JVM warm-up and are not steady-state timing or a before/after speedup claim.
SSM `13cd4f31-5c49-49ec-88e0-3e229f444cc5` completed successfully and restored the normal service's
exact host-links profile. The public console still does not automatically execute Timeline messages.


Release122 is deployed as artifact
`3b15d72315320a1b63c6a26e56b1a2aaa7761434a9fe58dc164d6e727f1a16e3`;
SSM `59bfb43f-18a1-405d-8189-5fc49c6e1252` completed successfully. After restoring the
ordinary service, live browser qualification passed bootstrap, operation dialogs, payment propagation,
immutable History, Timeline creation/append/content, Sharing grant/revoke/regrant, account isolation
and signed private-reader revocation. The original user agreement was then verified read-only:
512 documents, 128 orders, 383 payments, version247 and paidTotal4956. Business mutations ran in
separate QA documents. The console and library runtime artifacts remain unchanged from121.
Manifest122 records source/artifact hashes, full host tests, native restart qualification, live browser
results and the graph check. No new AWS business-latency comparison was performed in this iteration.


## Iteration123 — isolate durable Timeline work by runtime binding (keep)

Reproduced a scheduler correctness defect: a runtime could consume a different runtime's READY
receiver from the shared work index and mark it waiting with no usable sources. Discovery now uses
the full immutable account binding as its index scope, and receiver execution also checks that this
worker supports every subscribed source. Unexpected hints receive the same ownership checks.
Source fanout can wake a receiver for another capable worker without executing it on an incomplete
worker. Namespace, Coordination and private-access/directory profile remain distinct.

Existing subscriptions recover through each adopted source's bounded fanout, including a lost
publication response and a fresh worker. This resets migration fanout from the beginning, preserves
failed-receiver blocks and does not replay business effects. Static startup recovers its explicitly
configured sources; directory users repeat the same adoption. Old/new mixed workers and downgrade
require explicit migration; documents created before subscriptions existed still need migration.

The deterministic locality check queues1,024 receivers belonging to another binding. Discovering
and settling the one owned receiver still returns one work item, requires one strong job-row read,
and issues no subscription query. Ordinary acquired-source and receiver updates reuse the protected
job's immutable binding with no extra per-occurrence account lookup or hashing. These are I/O-count
checks, not an AWS latency or throughput claim.

Decision: KEEP for scheduler correctness and bounded discovery. Dynamic directory automatic
acquisition, reader custody and the console-native business bridge remain unfinished. Final test,
Main/HTTPS and deployment evidence will be recorded in manifest123.


All342 host tests pass, along with four launcher tests. The unchanged library source/JAR retains
release121's2,343-test qualification. The promoted host JAR exactly matches the tested artifact
`534c65302a4e67b8ce56980a0535113424057053ad84462b17887335da06ab62`.
The local actual-Main/HTTPS probe passes. AWS qualification also passes with DynamoDB and a fresh
Main process: rejected invalid signatures, quiet-source closure, duplicate suppression, automatic
child/parent execution and an offline arrival recovered exactly once. Twelve original inputs
produce final total84. The two warm-ups/four measured samples yield529.13 ms median and569.06 ms
maximum/p95 from append through observed parent update. This includes the100 ms source polling
interval, HTTPS signing/verification, storage and background scheduling. It is a bounded functional
qualification with limited warm-up, not a before/after latency comparison or throughput guarantee.
Diagnostic phase totals overlap across threads; they cannot be summed into one request duration.

Release123 artifact `be1bd7e321c347358237e216621b9b4225d9a94e3228b9dc25ce44b0740c6e22` is deployed;
SSM `387471b2-7df6-4012-8e48-ea476da0f62b`: Success. Automatic qualification SSM
`60aca0ad-2ab1-446d-a214-0e1668ef0fc9`: Success, including restoration of the ordinary console's
exact host-links profile. The public console still does not execute appended Timeline messages
automatically. Its business payment measurements remain pinned to104.


After service restoration, live browser qualification passes bootstrap, operation dialogs, payment
propagation, immutable History, Timeline creation/append/content, Sharing grant/revoke/regrant,
account isolation and signed private-reader revocation. The original user agreement is verified
read-only:512 documents,128 orders,383 payments, version247 and paidTotal4956. Business mutations
ran in separate QA documents. Manifest123 retains source/runtime pins and all qualification outputs.

Next performance investigation: the automatic probe's observation windows include repeated work
index queries by otherwise idle parent-propagation workers. `Propagation.drain` currently scans up
to16 shards when it delivers no hinted work, on top of timed discovery. This is a concrete code and
I/O observation, not a proven share of end-to-end latency. Measure idle queries, burst wakeup latency
and crash/restart discovery before changing that policy; preserve fairness under continuous hints.


## Iteration124 — advisory push wakeups (revert)

Tested two bounded AWS variants against the current worker polling: DynamoDB Stream/Pipe/SQS hints,
then immediate asynchronous SQS publication after a confirmed transaction with Streams as recovery.
Both reduced idle work-index queries from about93–109/s to4/s and preserved exact totals, gas and
versions. All359 candidate host tests passed, including unknown commit outcomes, bounded overflow,
queue failure, duplicate hints and crash recovery.

Stream-only discovery regressed independent-producer completion from239–251ms to402–452ms. Immediate
notification reduced that path from252–270ms to206–238ms, but same-worker completion regressed from
162–171ms to189–202ms. These are ranges of two run medians from small, separately pinned campaigns;
not confidence intervals or a throughput qualification. The producer uses a separate Core in the
same JVM, not another machine.

Decision: REVERT both candidates. Keep release123 and its dependency graph; do not add SQS to the
shipping host. Archive the experiments and remove their temporary infrastructure. Reduced idle
reads do not by themselves justify slowing the normal console payment path. The exact source of
the same-worker overhead remains unproven; future work should isolate notification transport and
stale-hint read costs before adopting a new scheduling policy. Full details and artifact pins are
in the host's docs/PERFORMANCE-EXPERIMENT-124.md and manifest124. No library runtime change.


Validation after revert: the restored host and qualification JARs exactly match123; the restored
342-test host check passes (Gradle reused matching test results), and the library JAR remains121.
The live browser passes bootstrap, payment propagation, immutable History, operation dialogs,
Timeline creation/append/content, Sharing grant/revoke/regrant and signed private-reader denial.
A fresh QA payment had10.50ms runtime,261ms browser response and517ms until all parent updates were
observed; this single sample is not a performance guarantee. The original user agreement is checked
read-only:512 documents,128 orders,383 payments,version247,paidTotal4956.

The experimental CloudFormation stack is DELETE_COMPLETE, the original stack is UPDATE_COMPLETE,
and the retained DynamoDB table is ACTIVE with its original stream setting restored. No candidate
runtime was promoted or deployed as the normal service. Source changes retained for124 are reports
only. Both AWS comparison commands completed successfully and restored release123.


## Iteration125 — automatic acquisition from the durable source directory (keep)

The directory can now register automatic acquisition after startup, with no provider/Timeline source
files. Public source adoption and its polling registration share one conditional transaction. Repeating
old adoption adds a missing polling registration without rebinding definitions or resetting history.
Private sources require an explicit loopback-operator custody registration referencing an exact
protected secret version. Account, protocol, provider, Timeline and key correspondence are verified
before registration and on cold custody load; only non-secret references are stored. Current provider
permission and the document's historical business-access policy remain independent requirements.

Discovery pages four runtime-scoped shards fairly, with at most64 queued/running jobs,64 cached pull
sessions and1–8 acquisition workers. Incomplete chunks can rejoin the bounded queue immediately. A
growing shard cannot starve another. Retry backoff is durable and preserves monotonic revisions across
recovery: a deterministic test first reproduced an old-success/delete/recreate race, then passed with
the retained fix. Healthy unchanged polling writes no scheduling record and repeats no business effect.

All351 host tests and four launcher tests pass. The actual Main/HTTPS directory probe passes with
zero source files, runtime HTTP adoption, invalid-signature refusal, strict quiet-source closure,
duplicate suppression and child/parent execution. Its two warm-ups/four samples yield99.80ms median
and132.86ms maximum/p95 through the parent in memory. These are inclusive functional-qualification
timings, not a before/after speedup or throughput guarantee. Library runtime/source remains121.

The bounded tests cover130 adopted sources, namespace isolation, competing workers, offline input
after restart, private-reader revocation/restoration and custody mismatch. Private custody is tested
locally; AWS private-key provisioning and its end-to-end qualification remain separate. Distributed
polling leases/provider quotas, historical byte bounds, retirement/key rotation and owner-authorized
console adoption/routing remain unfinished. The ordinary console still uses host-links operations;
Timeline append there does not automatically invoke business operations.


AWS qualification and deployment are complete. Release
`da719d40abe5f3848b93f1c1070d79472e35c6dc861b483596028036728c2578`
passed deployment health checks and the automatic-directory probe against DynamoDB. The probe used
two warm-ups and four samples, restored an offline arrival in a fresh Main process, and finished at
the expected parent count of 84. Median through-parent latency was 643.77 ms; the maximum (also the
nearest-rank p95 for four samples) was 663.62 ms. These signed-acquisition measurements include polling,
verification and persistence; they are not directly comparable to the host-links payment benchmark.
Both runtime JAR hashes match the promoted build. The qualification restored the ordinary host-links
service successfully, with automatic native business execution still disabled in its manifest.

Live browser verification passed editable bootstrap, operation dialogs, payment propagation, immutable
History, Events, private Timeline creation and signed entries, Sharing grant/revoke/regrant and
cross-account denial. A fresh small QA graph showed 9.19 ms payment runtime, 253 ms browser response
and 504 ms until all parent updates were observed. This is one functional sample, not a performance
campaign. The original user's agreement was checked read-only: 128 orders, 383 payments, 512 documents,
version 247 and paidTotal 4956. The library runtime remains unchanged. Decision: KEEP iteration 125;
its benefit is durable runtime adoption and bounded, isolated acquisition, not a claimed latency gain.
Evidence and exact source hashes are retained in manifest-125.json.


## Iteration126 — batch parent-delivery reads (keep)

Wake-up, head and dispatch acknowledgement reads now share one strong per-key batch. Discovery
prefetches bounded groups of at most 25 sources (100 records with legacy outboxes). Existing revision
fences and command receipts still decide publication; the batch is not a transactional snapshot.
No runtime edition, storage format, package pin, queue policy or business semantics changed.

Seven tests cover read budgets, stale/suspended/deleted index entries, failed reads, legacy hashes,
a source advancing after read and a peer completing prefetched work. All 358 host tests and three
launcher tests pass. Library runtime is unchanged from121. The live baseline/candidate campaign
used eight workers, eight measured payments per case, and recovery of 64 payments on separate orders.
All gas/version arrays, exact totals and durable acknowledgements matched.

The candidate reduced average backlog get/getAll calls from1007.5 to807.5 (19.9%). This is a measured
store-call reduction, not a claim about billed units or HTTP retries. Hinted medians remained in the
162–172ms baseline range; the candidate backlog durations of3186–3212ms lay within the2964–3250ms
baseline range. There is no demonstrated latency speedup. Keep the small read-batching improvement
for lower read-call overhead; root-update serialization and global queue discovery remain open work.
The detailed report is docs/PERFORMANCE-PROPAGATION-READS-126.md.


Deployment and qualification are complete. Release
`66d53c20bc4b9cd96b4a17fdfb09e1a0ea3ac41acceef6d736e5af875ff7ccaf`
passed health checks with the existing host-links profile, provider identity and worker/cache capacity.
The signed automatic-directory probe passed on DynamoDB, including a fresh-process restart and an
offline arrival, with the expected final count84. Its two warm-ups/four samples measured607.75ms
median through the parent; this separate functional run is not a paired speedup result.

Live browser verification passed bootstrap, operations, payment/order/agreement propagation,
immutable History, Events, Timeline creation/append/content, reader grant/revoke/regrant and account
isolation. The fresh small QA graph recorded13.94ms runtime,262ms browser response and515ms until all
three updates were visible. The original user graph remains512 documents (128orders,383payments),
version247,paidTotal4956, verified read-only. Exact evidence and source pins are in manifest126.


## Iteration127 — cached parent snapshots with freshness recovery (keep)

Parent delivery now reuses the existing bounded speculative head cache, as direct operations already
do. Conditional publication remains authoritative. A cached child-version gap refreshes before
waiting, and a parent-processing exception refreshes before suspending the source when a peer has
already changed the parent. Both missing safeguards were reproduced in focused failing tests before
the final implementation. The first measured candidate was not promoted because it could suspend a
child after a peer had amended an oversized workflow into a valid bounded one.

All364 host tests pass. The corrected binary was separately measured in four local and four AWS
forks with the same64-order backlog and single-payment fixture. Gas, versions, totals and durable
acknowledgements match. AWS get/getAll calls average812.5 before and723 after (11.0% fewer). Hinted
medians are149–162ms baseline versus143–154ms candidate; unhinted medians overlap. Recovery is
about2.9–3.0s for both. Keep the redundant-read removal with the freshness guards, without claiming
a material backlog/throughput improvement. Direct library runtime, storage formats and execution
editions are unchanged. Details: docs/PERFORMANCE-PARENT-SNAPSHOTS-127.md.

Deployment and qualification are complete. Release
`694e90e5da55dbd5319c29917b5c9751ff24c9f826013f68284beeaa15476954`
passed health checks with the existing host-links profile, provider identity and worker/cache capacity.
The signed automatic-directory probe passed on DynamoDB, including a fresh-process restart and an
offline arrival, with expected final count 84. Its two warmups/four samples measured 627.29 ms
median through the parent; this separate functional run is not a paired speedup result.

Live browser verification passed bootstrap, operations, payment/order/agreement propagation,
immutable History, Events, Timeline creation/append/content, reader grant/revoke/regrant and account
isolation. The fresh small QA graph recorded 7.01 ms runtime, 273 ms browser response and 575 ms until
all three updates were visible. The original user graph remains 512 documents (128 orders, 383 payments),
version 247, paidTotal 4956, verified read-only. Exact evidence and source pins are in manifest 127.

## Iteration 128 — publish small embedding pages with document state (keep)

The previous iteration 127 is deployed and qualified. This iteration combines small immutable
embedding-page sets with the existing conditional document/source transaction. Large sets retain
the bounded page-first fallback. Newly staged pages enter the repository cache only after a
successful commit; failed or uncertain outcomes preserve durable-receipt recovery. No document
format, runtime edition, gas or library source changes.

All 374 host tests pass. Four local forks preserve results without a proven memory speedup. Four AWS
forks preserve gas, versions, totals and acknowledgements. 64-order recovery falls from 3.17–3.33 s to
2.50–2.85 s (17.8% shorter on average), with 128 commits and zero conflicts per fork. Hinted
payment medians improve; unhinted timings are mixed. Keep the change for measured recovery latency,
while noting transactional page writes consume more capacity and may conflict across concurrent
writers. No cost saving, sustained-throughput or SLA claim. See docs/PERFORMANCE-CONTENT-PUBLICATION-128.md.

Deployment and qualification are complete. Release
`0347a23bcb93bc4a20ec4b46776ef5e359374efbe9d83642b2dacc6c470abc60`
passed health checks with the existing profile, provider identity and worker/cache settings.
The automatic-directory probe passed on DynamoDB, including a fresh-process restart and offline
arrival, with final count 84. Its two warmups/four samples measured 555.00 ms median through the
parent; this separate functional qualification is not a paired speedup measurement.

Live browser checks passed bootstrap, operation dialogs, payment/order/agreement propagation,
immutable History, Events, Timeline creation/append/content, reader grant/revoke/regrant and tenant
isolation. The fresh small QA graph recorded 6.03 ms runtime, 279 ms browser response and 583 ms until
all three updates were visible. The original user graph remains 512 documents (128 orders, 383 payments),
version 247 and paidTotal 4956, verified read-only. The ordinary console still uses host-links;
Timeline append does not automatically call a business operation.

The actual checkout's stale root deployment metadata is updated to this verified release, preserving
its old value in the evidence archive. Runtime sources and exact artifacts are pinned in manifest 128.

## Iteration 129 — current large-graph worker and contention qualification (keep tooling)

The prior 128 change is deployed and qualified. This iteration changes qualification tooling and
reports only; the runtime/library stay unchanged. The new probe loads the deployed runtime manifest,
samples actual stored order links and uses real Server workers. Fresh local 8/256-order cases and a
mixed-edition regression (edition 3 root, edition 2 children) pass; all 25 control-script tests pass.

Four AWS forks on existing 128/20,128-order graphs pass 512 measured payments, 64 warmups and 12 receipt
replays, checking every touched leaf/order value, root total and durable acknowledgement. Sequential
root completion medians are 129–137ms small and 129–133ms large. Eight concurrent callers complete 64
payments in 2.75–2.84s small and 2.86–3.09s large. Larger active sets cause 130 session restores and 71
evictions versus 80/0 small; all measured phases have zero cold content reads after untimed target
selection. Size alone is not isolated by the concurrent test. Next: hold working set constant and
profile restoration. These are finite batches, not sustained production throughput.

The first tool incorrectly selected only edition 3; it was fixed to use the actual multi-edition
manifest. A later summary parser assumed JSON was the last log line and rejected a passing result
followed by a shutdown diagnostic. All three passing samples were retained; only the missing fourth
was completed, using the exact same artifacts. Per-sample command provenance is retained. The main
service was restored after every AWS job. Keep the tools/evidence; no runtime optimization or new
deployment is claimed. Details: docs/PERFORMANCE-GRAPH-LOAD-129.md; manifest 129.


## Iteration 130 — fixed working-set scale comparison and restoration profiling (keep tooling)

The previous 129 qualification is retained. This iteration holds 16 active orders/37 sampled payments
constant across stored 128-order and 20,128-order graphs. Four fresh JVMs on unchanged deployed128 pass
512 measured payments, 64 warmups and 12 receipt replays, including every touched value and durable
acknowledgement. Sequential root medians are 113.62/120.00 ms small and 114.66/114.41 ms large. Concurrent
64-payment batches take 2.94–3.01 seconds across both sizes. All phases have zero capacity evictions;
concurrent restores fall to 18–26, while concentrated updates cause 22–38 conditional retries. There
is no stored-size penalty shown in these warmed samples, and no throughput/SLA or cold-read claim.
Full inventory remains explicitly linear: 166–176 ms small versus 11.36–12.74 seconds large, untimed.

Local JFR on 256 active orders puts cold restoration in 36/114 sequential and 23/89 concurrent CPU
samples. Evidence admission accounts for 24/36 and 15/23 of those restoration samples; certification
also appears. Allocation weights on first samples include preceding work, so an apparent projection
hotspot is not used to justify a rewrite. Raw JFR stays temporary; only sanitized aggregate evidence
is retained. Next: isolate evidence-admission cost and measure bounded reuse without weakening
validation. All26 control-script tests and fresh/mixed-edition local qualifications pass.

Keep the probe controls, safe local profiling and evidence. Runtime/library source and artifacts are
unchanged; release128 remains deployed and the ordinary service was restored after the successful
AWS campaign. No new runtime optimization is claimed. See docs/PERFORMANCE-WORKING-SET-130.md and
manifest130 in the host project.


## Iteration 131 — checkpoint evidence candidates (revert runtime, keep tests/probe)

The previous130 working-set qualification changed the next target to cold session evidence admission.
A reference-only checkpoint candidate preserved BlueIds but changed literal Contracts projections and
protected checkpoint representation; restoration tests failed. It was fully reverted.

A same-batch inline-verification reuse candidate kept exact wire bytes. Focused regressions caught
canonical text/depth/value bounds after shorthand, mixed-format publication ordering, and canonical
Double decimal-scale limits. All were corrected before measurement of the final candidate. Its
extra index was bounded to4096 entries, with ordinary verification on overflow. The fully corrected
candidate passed2349 library/374 host tests and all1536 fixed-fixture transition checks, including
byte-identical checkpoints, exact gas, events, identities and projections.

In baseline/candidate/candidate/baseline JVMs, payment restoration improved0.885→0.688ms, but order
restoration slowed0.919→0.982ms with13% more allocation. Agreement restoration improved only
0.996→0.949ms. Separate repeatable-payment and package-backed Hello initialization medians/allocation
were essentially unchanged. Decision: REVERT the added runtime complexity. Faster intermediate
candidates had correctness defects and are not accepted speedup evidence.

Five API-boundary regressions and the local96-checkpoint measurement probe are retained. The original
runtime sources/JAR are restored; retained library tests total2348. No AWS changes or user-graph
mutations occurred, and release128 remains deployed. Next investigate byte-identical exact serialization
without changing representation or verification rules. See host docs/PERFORMANCE-CHECKPOINT-RESTORE-131.md
and manifest131, which distinguish every rejected candidate from retained artifacts.


## Iteration 132 — direct exact transport encoding (KEEP)

Exact JSON rendering now walks immutable nodes directly instead of building a temporary export tree.
Existing scalar/Unicode rules are reused; inline/reference, bare/typed list and cyclic wire shapes are
byte-identical. Sixteen encoding cases and all 2,364 library/374 companion host tests pass. Across
1,536 fixed-checkpoint transitions, bytes, identities, gas, events and projections match the baseline.
Export allocation falls 30–41% and its local median time 10–20%; whole-transition results are mixed,
so no end-to-end speedup is claimed. This does not include either reverted restoration shortcut from
131. The companion report `PERFORMANCE-EXACT-SERIALIZATION-132.md` records full methods and scope.
