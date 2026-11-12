# Canonical BEX and Contracts

This is the current canonical execution API. It shares the `Language` implementation and verified
package evidence already in this library. Use `Bex` and `Contracts` for the published rules.
The earlier experimental execution models have been removed.

## A small compiled BEX program

```java
Bex bex = Bex.create();
Bex.Program sum = bex.compileYaml("expr: {$add: [2, 3]}");
Bex.Result result = sum.execute().requireSuccess();
System.out.println(result.value()); // BigInteger 5
System.out.println(result.gas());
```

Compilation freezes source data, validates all operators, function calls, variable references,
static positions and intrinsic identities. A compiled program can run concurrently. Variables,
returned effects and gas belong to one invocation. Shared definitions and a selected entry can
be supplied with `compile(source, definition, entry)`.

```java
Bex.Program add = bex.compileYaml("""
    do:
      - $appendChange:
          op: replace
          path: /total
          val: {$add: [{$document: /total}, {$event: /amount}]}
      - $appendEvent: {kind: Added, amount: {$event: /amount}}
      - $return: {$event: /amount}
    """);
Bex.Input input = Bex.inputs()
    .document(Map.of("total", 3))
    .event(Map.of("amount", 4))
    .build();
Bex.Result evaluated = add.execute(input).requireSuccess();
System.out.println(evaluated.value());     // 4
System.out.println(evaluated.changeset()); // replace /total with 7
System.out.println(evaluated.events());    // Added, amount 4
```

The inputs have not changed. Standalone BEX returns effects as data. Contracts applies them
atomically within the workflow. Failed BEX execution exposes no partial effects; inspect
`failure()`, `message()`, `gas()` and `rejected()`. `Bex.undefined()` is distinct from Java null.
Collections are immutable; integers are arbitrary precision. `compileJson` is also available.

`Bex.newBuilder().language(language)` supplies canonical matching and identity context.
`intrinsic(typeId, implementation)` explicitly installs a deterministic host intrinsic. Its live
meter must be charged before doing work. Package data cannot install executable Java code.

## Open, initialize and call an agreement

```java
Contracts contracts = Contracts.create();
String source = Files.readString(Path.of("agreement.yaml"));
try (Contracts.Session agreement = contracts.openYaml(source)) {
    Contracts.Transition initialized = agreement.initialize().requireApplied();
    Contracts.Transition processed = agreement.call("input", "submit", Map.of(), 1)
        .requireApplied();
    System.out.println(processed.events());
    System.out.println(processed.gas());
    System.out.println(processed.receipt());
    LanguageDocument document = agreement.document();
    System.out.println(document.toYaml());
    System.out.println(document.canonicalJson());
    System.out.println(document.blueId());
}
```

`openYaml`, `openJson` and `open(LanguageDocument)` admit the graph without initialization effects.
`initialize` performs initialization. A failed invocation leaves the session's state unchanged.
`process(event, timestamp)` delivers an ordinary external event. Time is explicitly supplied;
the engine does not consult the wall clock. A standard Operation Request is made by `call`.

A channel name is not a document path. For example, distinct `order-one` and `payment-one`
channels let a request select the intended embedded document. If multiple documents expose the
same accepting channel, canonical selection can deliver to multiple documents. The optional
Operation Request `document` field is a version precondition, not an address.

Repeated event content with a different timestamp is an event-timestamp conflict under the
published rules. Give distinct business requests distinct content (for example, a request ID).
Replaying the same event/time from the same starting snapshot reproduces its receipt.

## Orders, activity and nested payments

Run:

```bash
./gradlew canonicalDemo
```

The complete authored definitions are in `src/demo/resources/canonical/agreement.json`,
`order.json` and `payment.json`. They are ordinary canonical Blue/BEX, not Java business handlers.
The example runs these steps:

| Step | Contract behavior | Public result at the agreement |
| --- | --- | --- |
| Initialize | Initialize the agreement's processing metadata | No application event |
| `addOrder` | Add `/orders/one`; initialize the child; child emits `Order Initialized` | Increment activity and initialized-order count; emit `Agreement Order Added` |
| Order `submit` | Validate draft state; create `/payment`; emit `Order Submitted` | Increment submitted-order and activity counts; emit `Agreement Order Submitted` |
| Payment `settle` | Validate pending state; mark paid; emit `Payment Settled` | Order marks itself paid and emits `Order Paid`; agreement adds 42 to its paid total and emits `Agreement Payment Received` |

The final order and payment states are `paid`, agreement paid total is 42, and activity count is 3.
The example's 626 / 5,308 / 4,796 / 4,789 gas totals are semantic charges, not milliseconds.
Tests repeat the story with unrelated sibling orders, reject duplicate additions atomically,
and compare cold restoration with warm execution.

`Embedded Node Channel.sourcePath` selects one **exact direct-child path**. Omit it to observe
all direct children and filter their inner events; it is not a collection-prefix selector.
Workflows receive `{sourcePath, event}`. A payment's event reaches the order; the order's own
subsequent event reaches the agreement. Grandchild events are not silently flattened into the root.

## Actual published clauses

```bash
./gradlew canonicalClausesDemo
```

This example loads an unchanged, pinned Connections archive from the shared package repository.
Its verified import graph includes Agreements and Clauses. It initializes two real
`Connections/Hello Clause` instances: one changes `/greeting` on initialization; another changes
`/activity` when the document emits `Connections/Ping`. The package's own BEX installer constructs
the contracts. The Java host does not reproduce the installer.

For application-selected files:

```java
PackageLoader packages = PackageLoader.create();
BluePackage connections = packages.open(Path.of("connections.bluepkg"));
BluePackage agreements = connections.imported("Agreements");
Language language = Language.newBuilder()
    .mount("Connections", connections)
    .mount("Agreements", agreements)
    .build();
Contracts contracts = Contracts.newBuilder()
    .language(language)
    .require(connections)
    .require(agreements)
    .build();
```

Use the `open(path, expectedBlueId)` overload when a trusted pin is available. Mounts control
source aliases; `require` checks exact execution requirements. Data-only imports do not grant
native capabilities. These tests do not qualify every unpublished payment or reseller package.
A concrete channel filter is used in the example: `{channel: input}`. A bare, otherwise empty
channel can disappear from the installer's effective `$document` object projection; this is an
important distinction when reconstructing `/contracts` through BEX.

## State, concurrency and lifetime

Share `Contracts` freely. Open one `Session` per independent agreement graph. Calls on one session
are synchronized and atomic; different sessions share only immutable configuration and package
verification. The library does not require a journal, database, background worker or global lock.

```java
Map<String, Object> saved = agreement.snapshot();
String expectedIdentity = agreement.identity();
try (Contracts.Session restored = contracts.restore(saved, expectedIdentity)) {
    // Continue with the same document identities and processing metadata.
}
agreement.compact(); // retain current verified state; release obsolete working history
```

Keep the expected snapshot identity in trusted host state. A hash supplied alongside an untrusted
snapshot is not an authenticity guarantee. `close()` releases session-owned history; returned
Language handles retain their own immutable evidence. `compact()` is intentionally explicit and
traverses current reachable dependencies; it does not serialize and re-import them. Required historical
references remain available. Portable restore still verifies untrusted wire and expected identity.
There is no automatic persistence or authenticated provider service.

## Verification and scope

The full build runs the unchanged Language and BEX corpora, canonical Contracts reference receipts,
public APIs, portable execution, graph/cache invariants, independent-session concurrency and both
business scenarios. See `TESTING.md` for ownership and selectors. Canonical Coordination,
Timeline/Time/Mandates services, remote worker API and web application remain separate work.
