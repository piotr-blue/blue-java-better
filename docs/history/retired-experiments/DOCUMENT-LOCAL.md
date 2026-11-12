> Historical experiment, retired 2026-11-08. This is not a supported API guide.

# Independent documents in a durable host

`Contracts` remains the canonical graph processor. `DocumentRuntime` is the separate
`blue.document-local/1` protocol for a trusted application host. It reuses Language, BEX and local
Contracts workflows. It does not manufacture a canonical graph receipt for asynchronous work.

```java
DocumentRuntime runtime = DocumentRuntime.newBuilder()
    .language(Language.create())
    .gasLimit(10_000_000)
    .build();

try (DocumentRuntime.Session counter = runtime.openYaml("""
    count: 0
    contracts:
      input: {type: Contracts/External Channel}
      increment:
        type: Contracts/Sequential Workflow Operation
        channel: input
        steps:
          - type: Contracts/Update Document
            changeset:
              - op: replace
                path: /count
                val: {$add: [{$document: /count}, 1]}
    """)) {
    counter.initialize().requireApplied();
    DocumentRuntime.Update update =
        counter.call("input", "increment", Map.of(), 1).requireApplied();
    System.out.println(counter.read("/count")); // 1
    Map<String, Object> checkpoint = counter.checkpoint();
    try (DocumentRuntime.Session restored = runtime.restore(checkpoint)) {
        System.out.println(restored.read("/count")); // 1
    }
}
```

A runtime configuration is shareable. Each session owns mutable execution state, serializes its own
calls and has an explicit lifetime. A host may cache sessions by document/version. A failed or
uncertain durable publication must invalidate the speculative session; process memory is not truth.
The MyOS Better example uses a bounded leased cache and conditional DynamoDB publication.

## Embedding ownership

An `Update` contains public events, new child `attachments` and `replacedPaths`. The parent retains
its local fields and embedding declaration; the host stores child links separately. A child is
initialized in its own session under edition 1. Its events are delivered to the parent's existing Embedded Node
Channels through `receive(commandId, deliveries, timestamp, reader)`.

An attachment also exposes `initialBlueId()`, the authored child identity before initialization.
For a prepared child, `document()` already contains initialized state. A durable host can retain
the original identity beside that first publication without rerunning initialization. This value
is transient host provenance, not a canonical snapshot field or portable history proof.

```java
DocumentRuntime.Delivery delivery =
    new DocumentRuntime.Delivery("/orders/first", Map.of("kind", "Order Paid", "amount", 42));
DocumentRuntime.Update reaction = agreement.receive(
    "stable-source-version-and-parent-id", List.of(delivery), 2, reader).requireApplied();
```

The host must atomically publish the local checkpoint, child attachments/links, command result and
outbox before acknowledging a command. Stable command IDs and source versions provide deduplication
and ordering. A source document is already committed when its parent runs: a later parent failure
cannot retroactively roll it back. This is a real semantic difference from a canonical graph attempt.

A `Reader` supplies children from the parent invocation's pinned immutable link versions. Ordinary
local reads return the supplied local value. Collection/ancestor reads must merge those links with
the **current** local projection and omit `replacedPaths`; they must not reconstruct the old parent
snapshot or resurrect a removed child. The three-argument `read` overload exposes that information.
Direct reads under an earlier replacement/removal are resolved locally before consulting the host.
Whole-document/collection materialization is explicit work proportional to selected content.

The reader is also a `LanguageProvider`: override `find(BlueId)` when a projected child contains
exact references not already available in the parent, including initialized-document metadata.
Return a `LanguageRecord` from the pinned child's content. Its identity is verified on demand;
returning a projection never authenticates its references. The default `find` returns empty, so
simple scalar-only readers remain functional-interface lambdas. Provider access ends with the
invocation. A successful copy retains the required verified closure in its own checkpoint, so a
later restore does not require the old reader. The host must bound bulk reads before allocating
whole collections; these physical bounds remain separate from canonical Blue gas.

A checkpoint omits package records already installed in the runtime. Restore requires the same
verified dependencies; identities and the snapshot commitment are still checked. Canonical exports
continue to include their complete closure. The local document's BlueId identifies its local body,
not an implicitly expanded asynchronous network. Do not compare it with a full-graph receipt ID.

## What has been checked

- Agreement initialization, order addition/init, nested payment and child-to-parent reactions agree
  with canonical business fields and public parent events in the qualified example.
- Actual Agreements/Connections clause installation, checkpoint restore and subsequent events work.
- Rejected local execution leaves the document unchanged. Invalid timestamps do not poison a session.
- The MyOS host tests durable publication, restart, duplicate commands, two writers, stale-state
  rejection, transactional collection reads and a 20,000-sibling immutable index.

This API does not supply canonical Coordination/Timeline/Mandates/Time native services. The initial
host creates fresh children; attaching an existing shared document and arbitrary distributed cycles
are not qualified. Edition 1 cannot initialize a parent that depends on its freshly initialized
children; the explicit preview below addresses that case. Changing an active embedding boundary requires first removing its
links. These boundaries are not claims of full MyOS or distributed canonical parity.

See [iteration decisions](ITERATIONS-2026-11.md) and the companion MyOS project's architecture,
operations and test reports. Keep canonical `Contracts` for consumers requiring whole-graph atomicity,
cyclic scheduling or canonical receipts.

## Host command identity

A durable host should use the command-identified overload:

```java
DocumentRuntime.Update update = session.call(
    "invoice-42-payment-1", "input", "record", Map.of(), 1000L, reader);
```

The host stores that command identifier and request hash with the resulting document transaction.
Different identifiers may carry the same request. Retrying one identifier returns its stored outcome;
reusing it for different input is rejected by the host. The local checkpoint commits to the command
and event identity as a text value, without retaining the original event body or following a reference
to the preceding document version. Persist the original input separately with the command receipt. The shorter `call` overload retains
canonical payload identity, and is useful when the event itself already carries a unique identity.

For an authored channel that matches an envelope, use `session.process(commandId, event, timestamp,
reader)`. The event is unchanged for matching and BEX. This overload has the same compact commitment
as the command-identified `call`; the shorter `process(event, timestamp, reader)` retains canonical
payload identity and may retain content explicitly referenced by that payload.


## Atomic fresh-subtree initialization preview

Opt in explicitly; the default and existing edition-1 checkpoints retain their behavior:

```java
DocumentRuntime runtime = DocumentRuntime.newBuilder()
    .initializeFreshSubtrees()
    .build();

try (DocumentRuntime.Session agreement = runtime.openYaml(source)) {
    DocumentRuntime.Update initialized = agreement.initialize().requireApplied();
    for (DocumentRuntime.Attachment attachment : initialized.attachments()) {
        DocumentRuntime.Prepared child = attachment.prepared().orElseThrow();
        try (DocumentRuntime.Session order = runtime.restore(child.checkpoint())) {
            // Persist this checkpoint, child.events() and child.attachments() atomically
            // with the agreement and the parent-to-child link.
        }
    }
}
```

`blue.document-local/2-preview` keeps only the newly authored tree in a temporary canonical scheduler.
Children initialize before a parent reads them, including a child added by an earlier workflow step.
Child initialization events precede the parent's locally emitted initialization events, as in
canonical Contracts. The temporary graph is then split into independent checkpoints. Existing host
children never enter it; their immutable versions are still read through the pinned reader.

A prepared attachment's `document()` is an initialized inspection value. It must **not** be reopened
as authored input; the authored-state safeguard rejects it. Restore `prepared().get().checkpoint()`
instead. Its ordered `events()` belong in durable history but have already been consumed by its
parent, so do not enqueue them again. A two-argument `Attachment(path, definition)` still represents
an uninitialized edition-1 attachment. Hosts must distinguish the two forms. The companion MyOS
Core does: prepared children start with version-one links and a completed initial dispatch cursor,
with no initial outbox duplicate. Subsequent independent child updates use the ordinary outbox.

Preparation and its child checkpoints are included in the invoking update's gas/work, not charged
again during host persistence. A failed workflow publishes no child attachments or parent effects.
The host still must atomically commit all new documents and links; library success is not a durable
commit. Root initialization and same-command creation are atomic, while later child-to-parent
propagation remains eventual.

The preview bounds each temporary graph to 16 newly allocated children plus its root. It rejects
shared/cyclic fresh scopes, imported processing graphs, and list-indexed embedding paths. Use stable
object keys for independent children; identical canonical children that alias one logical member
cannot be silently duplicated. Removing an inherited embedding that cannot be detached also fails.
No implicit migration between local editions is allowed. This is an explicitly tested preview,
not a new published Contracts semantics identity or a claim of distributed graph parity.

The public API tests cover bottom-up reads, dynamic barriers, event order/history, nested children,
restore, rollback, authored-state protection, bounds and edition incompatibility. Host tests cover
atomic publication, durable history, retry and restart without duplicate initialization delivery.


## Host-owned embedding lifetimes

`blue.document-local/3-preview` retains edition2's atomic fresh-child initialization but keeps
cross-command embedding lifetimes entirely in the durable host. A detached checkpoint contains no
historical per-path `edgeSerials` entries. The temporary canonical graph still tracks generations
within an attempt, and canonical `Contracts` is unchanged. This removes the checkpoint growth with
unrelated siblings; it does not make collection scans or whole-graph exports constant-time.

```java
DocumentRuntime runtime = DocumentRuntime.newBuilder()
    .edition(DocumentRuntime.HOST_LINKS_EDITION)
    .build();
```

This is an explicit protocol choice. `create()` still selects edition1 and
`initializeFreshSubtrees()` still selects edition2. A host selecting edition3 must authenticate each
incoming child delivery against its durable parent link: source document, link generation and next
source version. It must recheck that link after a conditional-publication conflict. Reusing a path
must establish a new lifetime. The library's path-only `Delivery` is not an authentication mechanism.

An existing detached edition2 checkpoint can be migrated explicitly:

```java
try (DocumentRuntime.Session migrated = runtime.migrate(edition2Checkpoint)) {
    Map<String, Object> nextCheckpoint = migrated.checkpoint();
    // Atomically publish against the old host version, preserving links and their generations,
    // pending deliveries, historical versions and command deduplication. Record this migration.
}
```

Migration verifies the old commitment before changing anything. It preserves the local document,
channel checkpoints and revision state, removes only redundant lifetime counters, and produces a
new checkpoint commitment. It rejects uninitialized/undetached graphs, edition1 inputs and implicit
restore upgrades. The source checkpoint remains valid under edition2. A host must retain supported
old runtimes for historical reads and unmigrated children. Ordinary processing never auto-migrates.

MyOS Better publishes migration through the same conditional transaction as business operations,
with immutable history, a separate command fingerprint domain and ordered empty-event propagation
to any parents. This is an administrative host action, not a new authored workflow operation.
