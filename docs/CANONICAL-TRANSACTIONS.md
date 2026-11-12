# Private canonical graph transactions

`Contracts.Session.begin(event, timestamp[, evidence])` runs a complete canonical
invocation privately. The session changes only at `commit()`. This provides a host
with a durable-publication boundary; it does not introduce a different execution
edition or bypass the canonical scheduler.

```java
Contracts contracts = Contracts.create();
try (Contracts.Session session = contracts.openYaml(yaml)) {
  session.initialize().requireApplied();
  Map<String, Object> request = Map.of(
      "type", "Contracts/Operation Request",
      "channel", "input",
      "operation", "increment",
      "request", Map.of());
  try (Contracts.Transaction transaction = session.begin(request, 1L)) {
    Contracts.Transition result = transaction.finish().requireApplied();
    Map<String, Object> candidate = transaction.snapshot();
    BlueId root = transaction.blueId();
    String identity = transaction.identity();
    // A durable host atomically persists candidate, identity, and the command receipt here.
    // Publish in memory only after confirming that storage transaction succeeded.
    transaction.commit();
  }
}
```

Closing without committing discards the candidate. A stale candidate cannot
replace a newer session state. `advance()` can yield between canonical scheduling
units; an SCC is a complete unit, so a large cyclic unit can still take time.
`finish()` completes any remaining work. Methods on one transaction are serialized.
A native verifier still runs against explicit, snapshot-bound `AdmissionEvidence`;
private execution neither authenticates a caller nor chooses global input order.

## What "the same Blue document" means

For the same published packages, execution edition, admitted inputs and ordering,
the direct and private paths must have the same status, gas, ordered observations,
public events, canonical graph snapshot and identities. The implementation uses
the existing canonical scheduler. Physical storage pages are not Blue identities.

The differential suite checks trees with 1, 5 and 16 Orders and nested Payments;
self-cycles, two- and three-member feedback cycles; cyclic rejection; a changed
embedding that breaks a cycle; nonterminating feedback stopped by gas; and native
admission/replay rejection. Failed invocations publish neither a changed child nor
a changed parent. This is scoped to one canonical graph. It is not a claim that
independently committed graphs in a distributed host form a global transaction.

## Incremental Documents package

`../blue-packages/clause-documents-incremental` publishes an alternative under
`IncrementalDocuments`, with ID `67SMChJ95B6AJJZz3iKnsa6rxGJzGhvpbNTJv8U8s838`.
It is ordinary Blue/BEX. An embedded event or descendant update selects one entry
by its JSON pointer; slash/tilde escaping is preserved. Whole-collection changes
still reconcile the collection. Custom Document Base derivatives without an
entry-scope contract retain the conservative full refresh. Explicit `resume` is
also a full reconciliation. Shared configuration changes require reconciliation.

Its full initialization test installs Activation and the published Agreement
clause framework, creates a child, maps its owner and checks that it becomes usable. It then calls the child counter
operation and verifies exact direct/private execution parity.
Selector tests cover irrelevant events, repeated notifications, escaped keys and
collection-wide/custom-scope fallback. These tests load a bundled verified archive;
no Python checkout or runtime is required.

The new package is opt-in. Its package identities and gas differ from the old
publication. Equivalence compares direct versus optimized execution of the same
publication, not different BEX programs.

## Physical reuse and remaining cost

Archive restrictions now share private retained snapshots while keeping selection
maps and returned mutable snapshots independent. Snapshot export traverses shared
content closures once across graph members, retaining every root's list-prefix
witnesses. A differential test compares its complete record set with independent
per-document export, including cyclic proofs and supplied dependencies.

These changes preserve canonical gas. The engine still opens/preflights reachable
documents, validates graph metadata and finalizes/exports graph state. They do not
make total execution independent of graph size. See the host's
`docs/CANONICAL-GRAPHS.md` for the measured limits and durable adapter.

The host can inspect `session.graph()`, `session.member(key)`,
`session.operations(key)` and `session.reception()` without changing document content.
The graph description contains stable member keys and directed embedding edges, including
cycles. `readMember(key, pointer)` uses the same projection rules as root reads.
`transaction.reception()` describes the completed candidate before publication, allowing
its subscriptions to be committed with its state.

`session.begin(LanguageContent, timestamp, evidence)` borrows the event's verified-content
provider for the private transaction lifetime. It does not copy an entire source history
into the session or treat content possession as admission authority. Closing the
transaction releases that provider; successfully resolved immutable evidence remains
available under the ordinary retention rules.
