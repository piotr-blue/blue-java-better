# Canonical execution performance and specification findings

Measured locally on 2026-11-04. These are bounded engineering measurements, not production SLAs.
Both implementations ran the same source documents, Operation Requests, explicit timestamps,
canonical gas policy and complete receipt calculation. All nine operation receipts and gas totals
matched exactly, as did initialization receipts. The old runtime was compiled from the current
local `blue-java` Language/BEX/Contracts sources with the same JDK and cached dependency JARs;
no old source, Python or package source was modified. Its Gradle offline dependency resolution
failed, so the isolated comparison used `javac`, not an old unverified binary.

## Results

Each graph starts empty and grows through actual `addOrder` operations. Each measured add is then
followed by submit (creating a payment) and settle (updating the order and parent). Every measured
operation runs from the same immutable starting snapshot. There are four warmups and nine samples,
one recorded JVM per implementation, Java 25.0.3, `-Xms256m -Xmx2g`. Setup and export/inspection are
outside operation timing; event admission, scheduling, execution, validation, sealing and receipt
hashing are inside. The source and runner are in the repository; raw sample arrays and receipts
are in [canonical-runtime.json](measurements/canonical-runtime.json).

| Existing orders | Operation | Old median ms | Improved median ms | Speedup | Same gas |
| ---: | --- | ---: | ---: | ---: | ---: |
| 1 | add | 5.34 | 4.48 | 1.19× | 5763 |
| 1 | submit | 4.47 | 4.13 | 1.08× | 5218 |
| 1 | settle | 3.83 | 3.38 | 1.13× | 5201 |
| 16 | add | 15.02 | 11.73 | 1.28× | 12047 |
| 16 | submit | 13.46 | 10.87 | 1.24× | 11383 |
| 16 | settle | 13.75 | 10.68 | 1.29× | 11366 |
| 128 | add | 355.47 | 184.64 | 1.93× | 58974 |
| 128 | submit | 427.61 | 191.30 | 2.24× | 57415 |
| 128 | settle | 391.05 | 187.93 | 2.08× | 57398 |

At 128 orders this is roughly a twofold improvement. It is **not constant-time**: the improved
one-order run is about 3–4 ms, while 128-order work remains about 185–191 ms. Small-run differences
are noisy; the maximum/sample arrays are included instead of implying statistically established
percentile guarantees. This is an in-memory canonical Contracts comparison, not a database or
Coordination-service throughput claim.

The 128-order graph took approximately 10.59 seconds to build through 128 canonical calls in the
improved runtime versus 18.92 seconds in the old one. That is cumulative construction, not the time
of one operation. One Java host processes all business behavior authored in the three source files.

## Changes that helped

1. The standard Contracts catalogue is loaded and verified once, then installed as immutable
   native evidence. Processor creation no longer re-publishes every built-in type and rules text.
2. Canonical Language remains the only Language implementation. Native views retain immutable
   references; they do not serialize documents merely to pass values between layers.
3. Successful dependency-closure checks are reused for unchanged immutable content in native
   execution. Previously every patch walked the full graph again; bulk initialization even hit
   the independent host step limit. Logical gas remains separately charged. Public Language's
   explicit operation limits retain their existing behavior.
4. Compiled Compute programs are cached by exact step and resolved definition identities.
   Definition selection, authority and logical reads still happen on every invocation. A test
   changes the selected shared function and proves the new program runs.
5. Owned immutable BEX input/result containers skip redundant recursive freezing. Untrusted
   mutable inputs are still copied and cycles rejected.
6. Logical-prefix evidence rows are decoded/encoded once per invocation, then their canonical
   fragments are reused. Sort order and final commitment bytes are unchanged.
7. Private receipt encoding uses a buffered UTF-8 sink with bounded quoted-string reuse.
8. The bounded concreteness cache uses linked access order and constant-time eviction. Sampling
   had found hash-table iterator scans in its former eviction path. Schema observation wrappers
   are also memoized per immutable head.

The public session API avoids eager wire export on each call. Export, inspection, snapshot
verification and history compaction are explicit operations. Sessions are isolated across graphs.

## What the current rules still make expensive

The authoritative sources are `blue-bex/spec/bex-final-gas.md` and
`blue-contracts/spec/contracts-deterministic-4.md` in the supplied package repository.

- **Whole-graph admission and selection.** Accepting headers, graph membership and dependency
  bindings have to be considered. Unrelated siblings do not execute business handlers, but they
  can still participate in selection, validation and completion evidence.
- **Flat graph-wide commitments.** Snapshot/vector and completion hashes bind complete ordered
  inventories. SHA-256 of a changed flat serialization requires those bytes again. Memoization
  of immutable pieces helps; it cannot turn a different flat hash into a path-local Merkle update.
- **Logical-prefix commitments.** Turn evidence includes accumulated manifests, validation proofs,
  established identities and prior work. More evidence means more bytes to order and hash. The
  BEX arithmetic in this scenario is small compared with this bookkeeping.
- **Bulk initialization and creation barriers.** Starting with all 128 uninitialized orders
  exposed recursive processing of other pending initializations after an initialization patch.
  The old canonical implementation exhausted all 10,000,000 gas, with 6,624 work occurrences
  recorded before failure. A stack sample showed nested creation barriers and logical-prefix
  encoding. This case is deliberately reported separately, not hidden by the steady-state table.
  The improved runtime retains these selected canonical rules; it does not silently waive gas or
  change the schedule. Sequentially adding the children does work.
- **Some application operations are inherently global.** Validating aggregate uniqueness, scanning
  every clause for a replacement, or computing identity for a whole newly built configuration
  cannot always be reduced to just the edited leaf without sufficient retained evidence.

## Recommended next specification revision

These are proposals, not unpublished changes masquerading under the existing rules ID:

1. Specify pending initialization ownership across nested phases. A creation barrier should
   distinguish newly attached dependencies from members already owned by an outer initialization
   phase, retaining the required pre-initialization views. Align the executable algorithm with
   the prose's once-per-activation rule and add a many-sibling initialization vector.
2. Use authenticated persistent/Merkle structures for graph membership, views and work evidence.
   Bind unchanged branches once, with precise verification and invalidation rules.
3. Define admission/routing indexes whose proofs justify ignoring unrelated document headers.
4. Charge the selected changed work and supplied verification evidence rather than mandating
   repeated serialization of all untouched members. Preserve deterministic failures and ordering.

A semantic change needs a new exact rules identity and a matching reference corpus. The current
Language, BEX and Contracts package publications and the Python implementation were left intact.
Canonical Coordination, native timer/mandate services and cross-process durability are not part
of these measurements.
