> Historical experiment, retired 2026-11-08. This is not a supported API guide.

# Blue Java Better: the complete tutorial mapping

This guide maps **all 18 lesson folders and every YAML example** in the supplied `tutorial.zip`
to the current Java API. It is a tutorial about what this checkout can actually do, not a claim
that its experimental runtime implements the entire MyOS tutorial. The original descriptions
are learning goals, not additional commands to modify the engine.

Start with `Language` for canonical content. Use `Blue`/`ContractState` for the separate
experimental Local-1 runtime. The two APIs currently have distinct semantic coverage.
`LanguageDocument` is immutable admitted content; `Node` is a generic immutable tree;
`ContractState` is an opaque admitted execution snapshot owned by one `Blue` instance.

The everyday vocabulary is small:

| Intent | API |
| --- | --- |
| Read a complete value | `language.readYaml(text)` / `readJson(text)` |
| Declare a reusable type | `language.defineYaml(text)` / `defineJson(text)` |
| Derive a reusable type | `type.extendYaml(text)` / `extendJson(text)` |
| Make a complete instance | `type.specializeYaml(text)` / `specializeJson(text)` |
| Read an effective value | `document.at(pointer)` |
| Inspect own exact structure | `document.node()` |
| Change authored content and validate | `document.edit(editor -> ...)` |
| Print identity / canonical wire | `document.blueId()` / `canonicalJson()` |
| Expand / collapse references | `document.expand(paths)` / `collapse(paths)` |
| Fetch and admit by identity | `language.read(id)` / `define(id)` |
| Create an unfetched commitment | `language.reference(id)` |

The YAML/JSON loading methods also accept `Path`. Definition versus completed-value intent is
expressed by method names; common calls need no `DEFINITION` enum. Advanced structural inspection,
wire roles and cyclic finalization retain explicit `Input`/`Goal` parameters where these distinctions
matter. Exact exports must be reloaded as Exact; compact canonical wire as `Input.compactJson`,
not as authored Source that could replay list construction.

## Run the guide

All 18 sections also run as separately named cases in `./gradlew scenarioTest` and the ordinary
`./gradlew test` / `build` gate. The tests and this runnable guide share the same snippet methods.
See [test strategy and ownership](TESTING.md) for focused semantic and runtime checks.

Use JDK 25 from the project root:

```bash
./gradlew completeTutorialExample
```

[CompleteTutorialExample.java](../src/demo/java/blue/examples/CompleteTutorialExample.java)
contains all snippets, imports, definitions and checks in execution order. It is a demo source,
not a new runtime feature. Every section's checks are bounded and run without Python, the original
ZIP, external package services, or a database. A passing section proves only the behavior stated
in that section; an unavailable feature is explained rather than simulated.

The snippets below are bodies of independent lesson methods in that class. They share only:

```java
private static final Language language = Language.create();
private static final Blue blue = Blue.create();
```

`check`, `expectInvalid`, `expectIncomplete`, `expectLanguage`, `expectBlue`, `local`, `request`
and the domain factory methods are **tutorial helpers**, whose full code is included. They are
not additional Blue library APIs. Checks throw even when Java assertions are disabled.

## Coverage at a glance

| Lesson | Current mapping |
| --- | --- |
| [01. Content identity and canonical forms](#lesson-01) | Canonical Language |
| [02. Content graphs, references and missing evidence](#lesson-02) | Canonical Language |
| [03. Point, Colored Point and conformance](#lesson-03) | Canonical Language |
| [04. Ordinary lists and equivalent edits](#lesson-04) | Canonical Language |
| [05. Immutable content and a running document](#lesson-05) | Local-1 adaptation; managed lineage is unavailable |
| [06. Counter, operations, BEX, events and gas](#lesson-06) | Local-1 adaptation |
| [07. Collapsed roots, ordinary values, channels and operations](#lesson-07) | Canonical content plus Local-1 adaptation |
| [08. History and reopening](#lesson-08) | In-memory snapshots only; durable history unavailable |
| [09. Dynamic behavior, protected state and generalization](#lesson-09) | Clause-based alternative; direct contract mutation/generalization unavailable |
| [10. Embedding and child reactions](#lesson-10) | Local tree adaptation; historical catch-up unavailable |
| [11. The same content in two places](#lesson-11) | Independent local occurrences; shared managed identity unavailable |
| [12. Cycles, deterministic gas failure and detach](#lesson-12) | Canonical cyclic content; no shared cyclic execution |
| [13. Stable-key order collections](#lesson-13) | Local-1 tree adaptation |
| [14. Agreement, Order and Payment events](#lesson-14) | Acyclic event-propagation adaptation only |
| [15. Failure, no matching handler and retry](#lesson-15) | Local outcomes; durable command lifecycle unavailable |
| [16. Repository types, Money and cold-chain events](#lesson-16) | Canonical provider pattern plus scaled-integer Local-1 example |
| [17. What can be concluded, and a new domain](#lesson-17) | Recap without claiming missing host features |
| [18. Order initialization, a host and a factory](#lesson-18) | Local-1 adaptation of all four extra fixtures |

<a id="lesson-01"></a>

## 01. Content identity and canonical forms

**Status: Canonical Language.**

Source examples: `01_xyz.yaml`, `02_xyz_changed.yaml`, `03_scalar_compact.yaml`, `04_scalar_explicit.yaml`

`01_xyz.yaml` becomes `xyz`; changing its description creates a different identity.
The compact and explicit scalar files both denote the same content. Calculation and reading
have no persistence side effect. The map below is an explicit, in-memory content store.
`toYaml()` exports the current exact presentation; `canonicalJson()` prints compact canonical
wire. An identity does not by itself prove that the document satisfies a type.

```java
LanguageDocument xyz = language.readYaml("""
    name: Xyz
    description: Some doc
    """);
LanguageDocument changed = xyz.edit(e -> e.set("/description", "Some changed doc"));
check(!xyz.blueId().equals(changed.blueId()), "Description changes identity");
LanguageDocument compact = language.readYaml("x: 1");
LanguageDocument explicit = language.readYaml("x: {value: 1}");
check(compact.blueId().equals(explicit.blueId()), "Scalar spelling does not change identity");
check(compact.canonicalJson().equals(explicit.canonicalJson()), "Same canonical form");
Map<BlueId, LanguageRecord> contentStore = new HashMap<>();
contentStore.putAll(xyz.records());
Language reader = Language.newBuilder()
    .provider(id -> Optional.ofNullable(contentStore.get(id)))
    .build();
LanguageDocument reopened = reader.read(xyz.blueId());
check(reopened.blueId().equals(xyz.blueId()), "Reopen stored content");
System.out.println(compact.canonicalJson());
```

<a id="lesson-02"></a>

## 02. Content graphs, references and missing evidence

**Status: Canonical Language.**

Source examples: `01_parent_inline.yaml`, `02_parent_reference.template.yaml`, `03_missing_reference.template.yaml`

The inline and referenced parent fixtures map to the same identity. Expansion and collapse
change presentation, not content identity. `at()` follows references and inheritance;
`node()` inspects the document's own exact representation.

For unavailable content, use `prepare` if the intention is to inspect structural identity
without admitting the complete value. Strict `readYaml` or `admit` will require its dependencies.
`LanguageProvider` responses are checked against the requested identity. Returning an unrelated
body is rejected, even if the provider labels it with the expected key.

`name()` reads metadata. `exists()` asks about concrete-value presence; a non-root node with
only name/description is not a concrete payload, so it is not the right metadata test.

```java
LanguageDocument parent = language.readYaml("""
    y: 1
    x: {name: Xyz, description: Some doc}
    """);
LanguageDocument collapsed = parent.collapse("/x");
LanguageDocument expanded = collapsed.expand("/x");
check(parent.blueId().equals(collapsed.blueId()), "Collapsed identity");
check(parent.blueId().equals(expanded.blueId()), "Expanded identity");
check(collapsed.at("/x").name().orElseThrow().equals("Xyz"), "Metadata through reference");

LanguageDocument child = language.readYaml("message: available later");
LanguageContent missing = language.prepare(Language.Input.yaml(
    "name: Parent with missing child\nchild: {blueId: " + child.blueId() + "}"));
BlueId structuralId = missing.blueId();
expectIncomplete(() -> language.admit(missing, Language.Goal.VALUE));
Language supplied = Language.newBuilder().content(child).build();
LanguageDocument valid = supplied.admit(missing, Language.Goal.VALUE);
check(valid.blueId().equals(structuralId), "Supplying evidence preserves identity");
check(valid.at("/child/message").text().equals("available later"), "Verified child");

Language wrong = Language.newBuilder()
    .provider(id -> Optional.of(LanguageRecord.json("42")))
    .build();
expectLanguage("ProviderBlueIdMismatch", () -> wrong.read(child.blueId()));
```

<a id="lesson-03"></a>

## 03. Point, Colored Point and conformance

**Status: Canonical Language.**

Source examples: `01_point_type.yaml`, `02_colored_point_type.template.yaml`, `03_valid_point.template.yaml`, `04_valid_colored_point.template.yaml`, `05_invalid_point.template.yaml`

`defineYaml` expresses the role formerly selected by `DEFINITION`. `extendYaml` creates a reusable
subtype and `specializeYaml` creates a completed value. Handles carry their supporting definitions;
this sequence needs no provider or registration step.

`name` is optional content metadata, not a package address or registration instruction.
The labels below are `Point` and `Colored Point`; they could also be omitted. The `extendYaml`
call sets the new document's type to the parent BlueId. It does not derive a package name.

Package exports and consumer imports live outside those definitions:

| Imported package prefix | Export name | Target |
| --- | --- | --- |
| `Geometry` | `Point` | `point.blueId()` |
| `Drawing` | `Coordinate` | the same `point.blueId()` |
| `Drawing` | `Colored Point` | `coloredPoint.blueId()` |

`Geometry/Point` means the consumer's chosen import prefix plus a package export name. The
prefix need not equal the publication's own name. The same exact document can be exported from
multiple packages, under different names, without acquiring a new identity. Two package roots
may have different identities while exporting the same document identity.

The code uses `.type(qualifiedName, definition)` to install these already-resolved bindings and
retain their content. It is the low-level Language registration API, not a package constructor
or a `.bluepkg` verifier/loader. Canonical loading is now available through `BluePackage` and
`PackageLoader`; use `Language.Builder.mount` to establish verified scoped exports. The
[package guide](PACKAGES.md) demonstrates this same Point example using real archives.

The original archive used qualified strings as `name` labels. Those bytes are legal as labels,
but imply no registration; these curated examples deliberately use plain labels instead. Editing
a label changes exact content and therefore its BlueId. Moving or re-exporting unchanged content
does not. Historical files in `src/demo/resources/tutorial/original/` remain unchanged.

The three valid type/instance templates no longer need manual BlueId string substitution.
An explicit top-level `type`, when supplied, must be the same parent or a compatible subtype.
It is preserved, never overwritten. The invalid instance still has structural content identity;
strict admission rejects its Text `x` against Integer. Editing a type creates new content and does
not migrate existing instances.

```java
LanguageDocument point = language.defineYaml("""
    name: Point
    description: A two-dimensional integer point.
    x: {type: Integer}
    y: {type: Integer}
    """);
LanguageDocument coloredPoint = point.extendYaml("""
    name: Colored Point
    description: A Point with a text color.
    color: {type: Text}
    """);
LanguageDocument plain = point.specializeYaml("x: 2\ny: 3");
LanguageDocument colored = coloredPoint.specializeJson("""
    {"x":2,"y":3,"color":"blue"}
    """);
check(language.isSubtype(coloredPoint, point), "Subtype");
check(colored.at("/x").integer() == 2, "Inherited Integer requirement");
check(colored.at("/color").text().equals("blue"), "Own color");
Language app = Language.newBuilder()
    .type("Geometry/Point", point)
    .type("Drawing/Coordinate", point)
    .type("Drawing/Colored Point", coloredPoint)
    .build();
LanguageDocument fromGeometry = app.readYaml("type: Geometry/Point\nx: 2\ny: 3");
LanguageDocument fromDrawing = app.readYaml("type: Drawing/Coordinate\nx: 2\ny: 3");
check(fromGeometry.blueId().equals(fromDrawing.blueId()), "Different export names, same instance");
check(fromGeometry.canonicalJson().equals(fromDrawing.canonicalJson()), "Same exact content");
check(fromGeometry.at("").type().orElseThrow().equals(point.blueId()), "Type resolves to content identity");
check(point.at("").name().orElseThrow().equals("Point"), "Exporting does not rewrite the label");
LanguageContent invalid = app.prepare(Language.Input.yaml("""
    type: Geometry/Point
    x: not-an-integer
    y: 3
    """));
check(invalid.blueId() != null, "Invalid semantic value still has structural identity");
expectInvalid(() -> app.admit(invalid, Language.Goal.VALUE));
LanguageDocument revised = point.edit(e -> e.set("/description", "A revised definition"));
check(!point.blueId().equals(revised.blueId()), "New definition identity");
check(plain.at("").type().orElseThrow().equals(point.blueId()), "Existing instance retains old type");
```

<a id="lesson-04"></a>

## 04. Ordinary lists and equivalent edits

**Status: Canonical Language.**

Source examples: `01_list_compact.yaml`, `02_list_explicit.yaml`, `03_list_reordered.yaml`, `04_empty_list.yaml`, `05_type_only_list.yaml`

All five list fixtures are covered below. A type-only list and an empty list are different;
reordering also changes identity. There are three independent routes to `[1,3]`: delete an item,
replace the sequence, or author a new document. Both identity and canonical JSON agree.

Language pointers use `/items/index`, including plain lists. This applies to `node()`, `at()`
and `edit()`, although serialized JSON/YAML may use array shorthand. Edits never mutate the
original and never replay Source list construction. Inherited positional and append-only
restrictions still apply. Managed collections in lesson 13 are object maps instead.

```java
LanguageDocument compact = language.readYaml("values: [1, 2, 3]");
LanguageDocument explicit = language.readYaml("values: {items: [1, 2, 3]}");
LanguageDocument reordered = language.readYaml("values: [3, 2, 1]");
LanguageDocument empty = language.readYaml("values: {items: []}");
LanguageDocument typeOnly = language.defineYaml("values: {type: List}");
check(compact.blueId().equals(explicit.blueId()), "List spelling");
check(!compact.blueId().equals(reordered.blueId()), "Order matters");
check(!empty.blueId().equals(typeOnly.blueId()), "Empty payload differs from declaration");
LanguageDocument removed = compact.edit(e -> e.remove("/values/items/1"));
LanguageDocument replaced = compact.edit(e -> e.set("/values/items",
    Node.list(List.of(Node.integer(1), Node.integer(3)))));
LanguageDocument authored = language.readYaml("values: [1, 3]");
check(removed.blueId().equals(replaced.blueId()), "Remove versus replace");
check(removed.blueId().equals(authored.blueId()), "Remove versus author");
check(removed.canonicalJson().equals(replaced.canonicalJson()), "Canonical remove/replace");
check(removed.canonicalJson().equals(authored.canonicalJson()), "Canonical remove/author");
check(compact.at("/values").size() == 3, "Original is unchanged");
System.out.println(removed.canonicalJson());
```

<a id="lesson-05"></a>

## 05. Immutable content and a running document

**Status: Local-1 adaptation; managed lineage is unavailable.**

Source examples: `01_bare_document.yaml`

The bare document can be canonical Language content, or separately be parsed for Local-1.
These are different APIs with different guarantees. `Blue.initialize` returns admitted execution
state. `Coordinator` optionally owns that state in memory and serializes calls per root.

Unlike the archive's MyOS host, repeated `Coordinator.initialize` creates distinct local handles.
There is no lookup by historical BlueId, epoch zero protocol record, READY state machine or durable
session deduplication. The local numeric `DocumentId` is not the source `documentId` field or a
canonical lineage identity. Inert documents do not require an execution header.

```java
String yaml = """
    name: Xyz
    description: Some managed document with no application contracts.
    """;
LanguageDocument content = language.readYaml(yaml);
Node source = blue.readYaml(yaml);
ProcessingResult initialized = blue.initialize(source).requireApplied();
ContractState state = initialized.state();
check(state.revision() == 0, "Local initial revision");
check(initialized.events().isEmpty(), "No invented business events");
Coordinator coordinator = new Coordinator(blue);
Coordinator.DocumentId first = coordinator.initialize(source);
Coordinator.DocumentId second = coordinator.initialize(source);
check(!first.equals(second), "Initialize does not deduplicate sessions");
check(coordinator.snapshot(first).document().equals(source), "Inert state");
check(content.at("").name().orElseThrow().equals("Xyz"), "Content remains independent");
```

<a id="lesson-06"></a>

## 06. Counter, operations, BEX, events and gas

**Status: Local-1 adaptation.**

Source examples: `counter.yaml`

The counter keeps separate `increment` and `decrement` operations and the archive's business event.
The `counter(blue)` helper shown below is the full local definition. It uses a local operation
channel and a complete explicit execution profile. `local(...)` and `request(...)` are small
helpers belonging to this tutorial, defined at the end; they are not library methods.

`blue.call` is trusted host ingress. It does not authenticate Alice/Bob, create Timeline Entries,
advance canonical checkpoints, or produce epoch proofs. The caller must authorize a request
before using it. The sample retains `direction`, `amount` and resulting `counterAfter` on its event.
`$document` reads step-entry state; `$resultValue` reads tentative updates. Using `$document` in
that final event would incorrectly report the previous counter value.

```java
ContractState zero = blue.initialize(counter(blue)).requireApplied().state();
ProcessingResult plusThree = blue.call(zero, "increment", request("amount", 3)).requireApplied();
ProcessingResult minusOne = blue.call(plusThree.state(), "decrement", request("amount", 1)).requireApplied();
check(zero.document().get("counter").integer() == 0, "Old snapshot");
check(plusThree.state().document().get("counter").integer() == 3, "+3");
check(minusOne.state().document().get("counter").integer() == 2, "-1");
check(minusOne.events().getFirst().event().get("counterAfter").integer() == 2, "Updated event value");
check(minusOne.state().revision() == 2, "Two local continuations");
System.out.println(blue.writeYaml(minusOne.events().getFirst().event()));
System.out.println("Local gas: " + minusOne.gasUsed());
```

Complete local definition used above:

```java
private static Node counter(Blue blue) {
  return local(blue, """
      name: Tutorial Counter
      counter: 0
      contracts:
        in: {type: Local/Operation Channel}
        increment:
          type: Contracts/Sequential Workflow Operation
          channel: in
          steps:
            - type: Contracts/Compute
              do:
                - $failIf:
                    cond: {$ne: [{$kind: {$binding: call/request/amount}}, integer]}
                    message: amount must be an integer
                - $appendChange:
                    op: replace
                    path: /counter
                    val: {$add: [{$document: /counter}, {$binding: call/request/amount}]}
                - $appendEvent:
                    kind: Tutorial/Counter Changed
                    direction: increment
                    amount: {$binding: call/request/amount}
                    counterAfter: {$resultValue: /counter}
        decrement:
          type: Contracts/Sequential Workflow Operation
          channel: in
          steps:
            - type: Contracts/Compute
              do:
                - $failIf:
                    cond: {$ne: [{$kind: {$binding: call/request/amount}}, integer]}
                    message: amount must be an integer
                - $appendChange:
                    op: replace
                    path: /counter
                    val: {$subtract: [{$document: /counter}, {$binding: call/request/amount}]}
                - $appendEvent:
                    kind: Tutorial/Counter Changed
                    direction: decrement
                    amount: {$binding: call/request/amount}
                    counterAfter: {$resultValue: /counter}
      """);
}
```

<a id="lesson-07"></a>

## 07. Collapsed roots, ordinary values, channels and operations

**Status: Canonical content plus Local-1 adaptation.**

Source examples: `01_reference_counter.yaml`, `02_counter_value.yaml`, `03_counter_with_collapsed_value.template.yaml`, `04_owner_channel.yaml`, `05_counter_collapsed_channel.template.yaml`, `06_increment_operation.yaml`, `07_counter_collapsed_operation.template.yaml`, `08_unknown_contract_reference.template.yaml`

The first part demonstrates exact content and copy-on-write. The actual counter fixture contains
`value: 9`; the archive README's claim that the original remains `7` is inconsistent. Here the
edited use becomes `10` and the shared original remains `9`.

The second part covers the root, channel, operation and unknown-contract reference cases using
`NodeProvider` in Local-1. Content ids in this portion are calculated by that runtime's `blueId`;
these are not asserted interchangeable with canonical Language's richer value model. The root,
channel and operation use explicit matching definitions rather than stale IDs from the archive.

A failed call or initialization is returned to the caller; supplying content and calling again
is a **new explicit attempt**, not MyOS same-command persistence/resume. The provider API itself
does not record durable demands. Do not infer that all unused references remain cold during
strict canonical admission: it validates the required closure. Selected observations can be lazy.

```java
LanguageDocument shared = language.readYaml("counterValue: {value: 9}\nunrelatedColdValue: {value: 9}")
    .collapse("/counterValue", "/unrelatedColdValue");
LanguageDocument changed = shared.edit(e -> e.set("/counterValue/value", 10));
check(changed.at("/counterValue").integer() == 10, "Changed use");
check(changed.at("/unrelatedColdValue").integer() == 9, "Other use");
check(shared.at("/counterValue").integer() == 9, "Shared original");

Node source = counter(blue);
Map<BlueId, Node> store = new HashMap<>();
Blue runtime = Blue.newBuilder().nodeProvider(id -> Optional.ofNullable(store.get(id))).build();
BlueId rootId = blue.blueId(source);
check(!runtime.initialize(Node.reference(rootId)).applied(), "Missing root fails");
store.put(rootId, source);
check(runtime.initialize(Node.reference(rootId)).applied(), "Retry supplied root");

Node channel = source.at("/contracts/in");
Node operation = source.at("/contracts/increment");
BlueId channelId = blue.blueId(channel);
BlueId operationId = blue.blueId(operation);
Node collapsed = source.edit(e -> e
    .set("/contracts/in", Node.reference(channelId))
    .set("/contracts/increment", Node.reference(operationId)));
check(!runtime.initialize(collapsed).applied(), "Executable references are required");
store.put(channelId, channel);
store.put(operationId, operation);
ContractState resolved = runtime.initialize(collapsed).requireApplied().state();
ProcessingResult incremented = runtime.call(resolved, "increment", request("amount", 1)).requireApplied();
check(incremented.state().document().get("counter").integer() == 1, "Resolved operation executes");
BlueId unknownId = blue.blueId(blue.readYaml("name: unavailable contract"));
Node unknown = source.edit(e -> e.set("/contracts/unknown", Node.reference(unknownId)));
check(!runtime.initialize(unknown).applied(), "Unknown direct contract cannot be silently ignored");
```

<a id="lesson-08"></a>

## 08. History and reopening

**Status: In-memory snapshots only; durable history unavailable.**

Source examples: the lesson README (no YAML files).

This lesson has a README but no YAML. Keep the actual `ContractState` returned by processing.
A retained snapshot supports a local branch; it is not an epoch receipt or portable recovery record.
Exporting its document is useful for inspection, but parsing that YAML does not recover compiled
state, occurrence generations, or a lineage.

There is no `reopenByBlueId`, `restoreEpoch` or durable `resumeCommand` API to show. A different
`Blue` instance cannot continue a state owned by the original instance. Calling `initialize` on
exported YAML starts fresh processing and may repeat initialization; it is not reopening history.

```java
ContractState epochZero = blue.initialize(counter(blue)).requireApplied().state();
ContractState epochOne = blue.call(epochZero, "increment", request("amount", 1)).requireApplied().state();
ContractState epochTwo = blue.call(epochOne, "increment", request("amount", 1)).requireApplied().state();
List<ContractState> retained = List.of(epochZero, epochOne, epochTwo);
ContractState branch = blue.call(retained.get(1), "increment", request("amount", 10)).requireApplied().state();
check(branch.document().get("counter").integer() == 11, "Branch from retained snapshot");
check(epochTwo.document().get("counter").integer() == 2, "Original branch is unchanged");
String inspectionYaml = blue.writeYaml(epochOne.document());
check(!inspectionYaml.isEmpty(), "Export is available");
expectBlue(() -> Blue.create().call(epochOne, "increment", request("amount", 1)));
```

<a id="lesson-09"></a>

## 09. Dynamic behavior, protected state and generalization

**Status: Clause-based alternative; direct contract mutation/generalization unavailable.**

Source examples: `01_dynamic_contract_host.yaml`, `02_installed_operation.yaml`, `03_generalization_base_type.yaml`, `04_generalization_subtype.template.yaml`, `05_generalization_instance.template.yaml`

The original install/remove fixtures write `/contracts/installed`. Local-1 protects `/contracts`,
so running that exact design is unsupported. The working adaptation installs/removes a clause,
and its operation is called `clauses/installed/run`. This different operation name is explicit.
The definition is in `dynamicHost`/`installedClause` below. Removing behavior preserves business
fields; initialization and migration are separate concerns. A clause may supply `validateChange`
for a pure state-dependent veto (see the existing clause examples).

The two marker-removal operations are not evidence of canonical marker protection: Local-1 has
no canonical initialized/checkpoint markers. It protects the entire contracts field instead.

The three generalization fixtures request `nearest-valid-ancestor`. That runtime policy is not
implemented. Canonical Language can reject an invalid edit and explicitly construct a different
value from an ancestor, as shown below, but that is **authoring a new value**, not an automatically
generalized managed transition. An explicit `required: true` makes the intended requirement clear.

```java
ContractState initial = blue.initialize(dynamicHost(blue)).requireApplied().state();
ProcessingResult installed = blue.call(initial, "installOperation",
    Node.object(Map.of("clause", installedClause(blue)))).requireApplied();
ProcessingResult ran = blue.call(installed.state(), "clauses/installed/run", Node.object()).requireApplied();
check(ran.state().document().get("phase").text().equals("installed-operation-ran"), "Installed behavior");
ContractState removed = blue.call(ran.state(), "removeInstalled", Node.object()).requireApplied().state();
check(!blue.call(removed, "clauses/installed/run", Node.object()).applied(), "Removed behavior");
ProcessingResult protectedEdit = blue.call(initial, "tryRemoveContracts", Node.object());
check(!protectedEdit.applied() && protectedEdit.state() == initial, "Protected edit rolls back");

LanguageDocument base = language.defineYaml("name: Base\nphase: {type: Text}");
LanguageDocument subtype = base.extendYaml("""
    name: Subtype
    requiredDatum: {type: Text, schema: {required: true}}
    """);
LanguageDocument value = subtype.specializeYaml("phase: initial\nrequiredDatum: present");
expectInvalid(() -> value.edit(e -> e.remove("/requiredDatum")));
LanguageDocument explicitlyReauthored = base.specializeYaml("phase: initial");
check(explicitlyReauthored.at("/phase").text().equals("initial"), "Explicit ancestor authoring");
```

Complete local definition used above:

```java
private static Node dynamicHost(Blue blue) {
  return local(blue, """
      name: Dynamic Contract Host
      phase: initial
      clauses: {}
      contracts:
        in: {type: Local/Operation Channel}
        installOperation:
          type: Contracts/Sequential Workflow Operation
          channel: in
          steps:
            - type: Contracts/Compute
              do:
                - $appendChange:
                    op: add
                    path: /clauses/installed
                    val: {$binding: call/request/clause}
        removeInstalled:
          type: Contracts/Sequential Workflow Operation
          channel: in
          steps:
            - type: Contracts/Compute
              do:
                - $appendChange: {op: remove, path: /clauses/installed}
        tryRemoveContracts:
          type: Contracts/Sequential Workflow Operation
          channel: in
          steps:
            - type: Contracts/Compute
              do:
                - $appendChange: {op: remove, path: /contracts/in}
      """);
}

private static Node installedClause(Blue blue) {
  return local(blue, """
      name: Installed behavior
      contracts:
        in: {type: Local/Operation Channel}
        run:
          type: Contracts/Sequential Workflow Operation
          channel: in
          steps:
            - type: Contracts/Compute
              do:
                - $appendChange: {op: replace, path: /phase, val: installed-operation-ran}
      """);
}
```

<a id="lesson-10"></a>

## 10. Embedding and child reactions

**Status: Local tree adaptation; historical catch-up unavailable.**

Source examples: `01_child_counter.yaml`, `02_host.yaml`, `child.yaml`

`01_child_counter.yaml` becomes the counter helper, with a changed event name documented below.
`02_host.yaml` becomes `counterHost`. `child.yaml` is an **input envelope** containing a `child`
property, not a second independent definition; the request below has exactly that shape.

The host declares `/child` as embedded, attaches the authored child and reacts to its events.
Local-1 requires a declared fixed embedding path to exist at initialization, so the helper
starts with an inert `child: {}` and replaces that whole occurrence during attachment.
Later calls target `/child` within the root snapshot. A separately initialized child's historical
states cannot be attached as an independently shared session. There is no retained-history
frontier or catch-up scheduler in this API.

```java
ContractState host = blue.initialize(counterHost(blue)).requireApplied().state();
ProcessingResult attached = blue.call(host, "attachChild",
    Node.object(Map.of("child", counter(blue)))).requireApplied();
ProcessingResult result = blue.call(attached.state(), "/child", "increment", request("amount", 2)).requireApplied();
check(result.state().document().at("/child/counter").integer() == 2, "Child changed");
check(result.state().document().get("observedCounter").integer() == 2, "Parent reacted");
check(result.visitedDocuments().equals(Set.of("", "/child")), "Affected documents");
```

Complete local definition used above:

```java
private static Node counterHost(Blue blue) {
  return local(blue, """
      name: Embedded Counter Host
      child: {}
      observedCounter: -1
      contracts:
        in: {type: Local/Operation Channel}
        embedded: {type: Contracts/Process Embedded, paths: [/child]}
        attachChild:
          type: Contracts/Sequential Workflow Operation
          channel: in
          steps:
            - type: Contracts/Compute
              do:
                - $appendChange: {op: add, path: /child, val: {$binding: call/request/child}}
        fromChild:
          type: Contracts/Embedded Node Channel
          sourcePath: /child
          event: {kind: Tutorial/Counter Changed}
        reflectChild:
          type: Contracts/Sequential Workflow
          channel: fromChild
          steps:
            - type: Contracts/Compute
              do:
                - $appendChange: {op: replace, path: /observedCounter, val: {$document: /child/counter}}
      """);
}
```

<a id="lesson-11"></a>

## 11. The same content in two places

**Status: Independent local occurrences; shared managed identity unavailable.**

Source examples: `01_two_slot_host.yaml`, `02_second_parent.yaml`

The two-slot and second-parent fixtures require one evolving source session shared by several
occurrences. Reusing one immutable `Node` here creates **independent occurrences**. A `documentId`
field or repeated BlueId does not turn them into one shared managed identity.

This executable counterexample is deliberate: changing `/first` leaves `/second` and the other
parent untouched. It shows exactly where a translation of the original pass criteria must stop.
Independent occurrence cursors, source-once processing and cross-parent catch-up need the future
shared graph engine. Local-1 also has one atomic scope for a root and its children; it does not
independently commit source and parent applications as the archive describes.

```java
Node child = counter(blue);
Node twoSlot = local(blue, """
    name: Two-slot Host
    contracts:
      embedded: {type: Contracts/Process Embedded, paths: [/first, /second]}
    """).with("first", child).with("second", child);
ContractState firstParent = blue.initialize(twoSlot).requireApplied().state();
ContractState secondParent = blue.initialize(twoSlot.with("name", Node.text("Second Parent"))).requireApplied().state();
ProcessingResult result = blue.call(firstParent, "/first", "increment", request("amount", 1)).requireApplied();
check(result.state().document().at("/first/counter").integer() == 1, "First changed");
check(result.state().document().at("/second/counter").integer() == 0, "Second is independent");
check(secondParent.document().at("/first/counter").integer() == 0, "Other root is independent");
```

<a id="lesson-12"></a>

## 12. Cycles, deterministic gas failure and detach

**Status: Canonical cyclic content; no shared cyclic execution.**

Source examples: `01_b.yaml`, `02_a.yaml`, `a.yaml`

Language can finalize a cyclic **content** set and verify its complete proof. This is not an
executable A↔B managed component. Serve that same owning proof for every member identity that strict validation may request.
The example creates two minimal content members, then separately
shows a Local-1 event loop rolling back at its gas limit. Local failure code is `GAS_LIMIT`, not
the archive's `GAS_LIMIT_EXCEEDED`; no canonical trace identity or admitted-trace export is promised.

`01_b.yaml` and `02_a.yaml` require shared cyclic execution and therefore cannot meet their pass
criteria here. `02_a.yaml` already embeds a B under `/peer`; it is not an empty A template.
`a.yaml` is a captured, wrapped B state with a cyclic member reference and checkpoint metadata.
It does not contain the complete owning proof or managed history and must not be treated as a
self-contained runnable cycle. Editing an owning set means finalizing a new complete set;
`LanguageDocument.edit` deliberately rejects editing inside cyclic members.

Tree detachment is available in lesson 13. SCC splitting into independently managed sessions is not.

```java
Language.CyclicSet cycle = language.finalizeSet(List.of(
    Language.Input.exactJson("""
        {"name":"A","peer":{"blueId":"this#1"}}
        """),
    Language.Input.exactJson("""
        {"name":"B","peer":{"blueId":"this#0"}}
        """)),
    Language.Goal.VALUE);
LanguageDocument a = cycle.members().get(0);
check(a.at("/peer").name().orElseThrow().equals("B"), "Cyclic content traversal");
check(a.at("/peer/peer").name().orElseThrow().equals("A"), "Cycle closes");
Language.Builder verifierBuilder = Language.newBuilder();
for (LanguageDocument member : cycle.members()) {
  verifierBuilder.record(member.blueId(), LanguageRecord.cyclic(cycle.proofJson()));
}
Language verifier = verifierBuilder.build();
check(verifier.read(a.blueId()).blueId().equals(a.blueId()), "Owning proof verification");

Blue bounded = Blue.newBuilder().gasLimit(2_000).build();
ContractState before = bounded.initialize(loopingDocument(bounded)).requireApplied().state();
ProcessingResult first = bounded.call(before, "startLoop", Node.object());
bounded.clearCaches();
ProcessingResult retry = bounded.call(before, "startLoop", Node.object());
check(!first.applied() && first.failure().code().equals("GAS_LIMIT"), "Bounded loop");
check(first.state() == before && first.events().isEmpty() && first.changes().isEmpty(), "Atomic rollback");
check(first.gasUsed() == retry.gasUsed(), "Deterministic local gas");
```

Complete local definition used above:

```java
private static Node loopingDocument(Blue blue) {
  return local(blue, """
      name: Bounded event loop
      phase: initial
      contracts:
        in: {type: Local/Operation Channel}
        startLoop:
          type: Contracts/Sequential Workflow Operation
          channel: in
          steps:
            - type: Contracts/Compute
              do:
                - $appendChange: {op: replace, path: /phase, val: tentative}
                - $appendEvent: {kind: Again}
        again: {type: Contracts/Triggered Event Channel, event: {kind: Again}}
        repeat:
          type: Contracts/Sequential Workflow
          channel: again
          steps:
            - type: Contracts/Compute
              do:
                - $appendEvent: {kind: Again}
      """);
}
```

<a id="lesson-13"></a>

## 13. Stable-key order collections

**Status: Local-1 tree adaptation.**

Source examples: `01_order.yaml`, `02_order_host.yaml`, `03_list_collection_negative.yaml`

`order` and `orderHost` below contain the full local definitions. The host uses an object map,
`$pointerJoin`, explicit add/remove operations, and a parent listener for initialization.
The order includes the lifecycle/approve behavior from lesson 18 so it can be reused there.
Duplicate keys are rejected by the authored workflow rather than silently replacing a child.

Deleting a key removes that occurrence; calling it then fails. Re-adding authored content creates
a new local occurrence and runs its initialization. It does not replay an independent source's
retained epochs. A third key containing the same content has independent state, as in lesson 11.
The negative fixture uses `items: []`; the example also tests a native list directly, avoiding
ambiguity between Local-1 generic objects and canonical list serialization.

```java
ContractState state = blue.initialize(orderHost(blue)).requireApplied().state();
state = blue.call(state, "addOrder", orderRequest("order-001", order(blue, "one"))).requireApplied().state();
state = blue.call(state, "addOrder", orderRequest("order-002", order(blue, "two"))).requireApplied().state();
check(state.documentCount() == 3, "Root and two orders");
check(state.document().get("initializedOrderCount").integer() == 2, "Initialization reactions");
ProcessingResult ready = blue.call(state, "/orders/order-002", "markReady", Node.object()).requireApplied();
check(ready.state().document().at("/orders/order-002/status").text().equals("ready"), "Targeted operation");
ContractState removed = blue.call(ready.state(), "removeOrder",
    Node.object(Map.of("orderKey", Node.text("order-001")))).requireApplied().state();
check(!blue.call(removed, "/orders/order-001", "markReady", Node.object()).applied(), "Detached occurrence");
ContractState readded = blue.call(removed, "addOrder", orderRequest("order-001", order(blue, "one"))).requireApplied().state();
check(readded.document().get("initializedOrderCount").integer() == 3, "New occurrence initialized");
check(!blue.initialize(orderHost(blue).with("orders", Node.list(List.of()))).applied(), "List collection rejected");
```

Complete local definition used above:

```java
private static Node order(Blue blue, String customerReference) {
  return local(blue, """
      name: Tutorial Order
      documentId: tutorial-order
      quantity: 3
      status: draft
      initializedState: pending
      contracts:
        life: {type: Contracts/Lifecycle Event Channel}
        initialize:
          type: Contracts/Sequential Workflow
          channel: life
          steps:
            - type: Contracts/Compute
              do:
                - $appendChange: {op: replace, path: /initializedState, val: initialized}
                - $appendEvent:
                    kind: Tutorial/Order Initialized
                    orderDocumentId: {$document: /documentId}
        in: {type: Local/Operation Channel}
        markReady:
          type: Contracts/Sequential Workflow Operation
          channel: in
          steps:
            - type: Contracts/Compute
              do:
                - $appendChange: {op: replace, path: /status, val: ready}
                - $appendEvent: {kind: Tutorial/Order Ready}
        approve:
          type: Contracts/Sequential Workflow Operation
          channel: in
          steps:
            - type: Contracts/Compute
              do:
                - $appendChange: {op: replace, path: /status, val: approved}
                - $appendEvent:
                    kind: Tutorial/Order Approved
                    orderDocumentId: {$document: /documentId}
      """).with("customerReference", Node.text(customerReference));
}

private static Node orderHost(Blue blue) {
  return local(blue, """
      name: Managed Order Host
      initializedOrderCount: 0
      lastInitializedOrder: none
      orders: {}
      contracts:
        in: {type: Local/Operation Channel}
        embedded: {type: Contracts/Process Embedded, collectionPaths: [/orders]}
        addOrder:
          type: Contracts/Sequential Workflow Operation
          channel: in
          steps:
            - type: Contracts/Compute
              do:
                - $failIf:
                    cond: {$hasKey: {object: {$document: /orders}, key: {$binding: call/request/orderKey}}}
                    message: Order key already exists
                - $appendChange:
                    op: add
                    path: {$pointerJoin: [orders, {$binding: call/request/orderKey}]}
                    val: {$binding: call/request/order}
        removeOrder:
          type: Contracts/Sequential Workflow Operation
          channel: in
          steps:
            - type: Contracts/Compute
              do:
                - $appendChange:
                    op: remove
                    path: {$pointerJoin: [orders, {$binding: call/request/orderKey}]}
        fromOrder:
          type: Contracts/Embedded Node Channel
          sourcePath: /orders
          event: {kind: Tutorial/Order Initialized}
        recordInitialization:
          type: Contracts/Sequential Workflow
          channel: fromOrder
          steps:
            - type: Contracts/Compute
              do:
                - $appendChange:
                    op: replace
                    path: /initializedOrderCount
                    val: {$add: [{$document: /initializedOrderCount}, 1]}
                - $appendChange:
                    op: replace
                    path: /lastInitializedOrder
                    val: {$event: /event/orderDocumentId}
                - $appendEvent:
                    kind: Tutorial/Host Observed Order Initialization
                    orderDocumentId: {$event: /event/orderDocumentId}
      """);
}

private static Node orderRequest(String key, Node order) {
  return Node.object(Map.of("orderKey", Node.text(key), "order", order));
}
```

<a id="lesson-14"></a>

## 14. Agreement, Order and Payment events

**Status: Acyclic event-propagation adaptation only.**

Source examples: `01_agreement.yaml`, `02_order.yaml`, `03_payment.yaml`

The archive requests `Agreement → Order ↔ Payment`, with actors, a shared cyclic component and
atomic cyclic publication. That exact topology is unavailable. The working example below uses
`Agreement → Order → Payment`, so payment events propagate upward and the aggregate can be checked.
It does **not** implement Payment listening back to Order, Alice/Bob authorization, independent
sessions or durable recovery. This narrower example is not a substitute for the original protocol.

The archived `capture` operation sets `state: captured` without checking `captureAllowed`, despite
its business description. This guide does not claim that authorization is enforced. The local
sample ignores consecutive captured events while the order remains paid, but a
production payment agreement still needs explicit permission and state-transition rules.

```java
ContractState state = blue.initialize(paymentAgreement(blue)).requireApplied().state();
state = blue.call(state, "/orders/order-001", "confirm", Node.object()).requireApplied().state();
state = blue.call(state, "/orders/order-001/payment", "authorize", Node.object()).requireApplied().state();
ProcessingResult captured = blue.call(state, "/orders/order-001/payment", "capture", Node.object()).requireApplied();
check(captured.state().document().at("/orders/order-001/payment/state").text().equals("captured"), "Payment");
check(captured.state().document().at("/orders/order-001/status").text().equals("paid"), "Order reaction");
check(captured.state().document().get("paidOrders").integer() == 1, "Agreement aggregate");
check(captured.events().stream().filter(e -> e.event().get("kind").text().equals("Tutorial/Order Paid")).count() == 1,
    "One paid event");
```

Complete local definition used above:

```java
private static Node paymentAgreement(Blue blue) {
  Node payment = local(blue, """
      name: Payment Instruction
      state: created
      contracts:
        in: {type: Local/Operation Channel}
        authorize:
          type: Contracts/Sequential Workflow Operation
          channel: in
          steps:
            - type: Contracts/Compute
              do:
                - $appendChange: {op: replace, path: /state, val: authorized}
                - $appendEvent: {kind: Tutorial/Payment Authorized}
        capture:
          type: Contracts/Sequential Workflow Operation
          channel: in
          steps:
            - type: Contracts/Compute
              do:
                - $appendChange: {op: replace, path: /state, val: captured}
                - $appendEvent: {kind: Tutorial/Payment Captured}
      """);
  Node order = local(blue, """
      name: Agreement Order
      status: draft
      confirmed: false
      contracts:
        embedded: {type: Contracts/Process Embedded, paths: [/payment]}
        in: {type: Local/Operation Channel}
        confirm:
          type: Contracts/Sequential Workflow Operation
          channel: in
          steps:
            - type: Contracts/Compute
              do:
                - $appendChange: {op: replace, path: /confirmed, val: true}
                - $appendChange: {op: replace, path: /status, val: confirmed}
                - $appendEvent: {kind: Tutorial/Order Confirmed}
        fromAuthorized:
          type: Contracts/Embedded Node Channel
          sourcePath: /payment
          event: {kind: Tutorial/Payment Authorized}
        markAuthorized:
          type: Contracts/Sequential Workflow
          channel: fromAuthorized
          steps:
            - type: Contracts/Compute
              do:
                - $appendChange: {op: replace, path: /status, val: payment-authorized}
        fromCaptured:
          type: Contracts/Embedded Node Channel
          sourcePath: /payment
          event: {kind: Tutorial/Payment Captured}
        markPaid:
          type: Contracts/Sequential Workflow
          channel: fromCaptured
          steps:
            - type: Contracts/Compute
              do:
                - $returnIf: {cond: {$eq: [{$document: /status}, paid]}, value: true}
                - $appendChange: {op: replace, path: /status, val: paid}
                - $appendEvent: {kind: Tutorial/Order Paid}
      """).with("payment", payment);
  return local(blue, """
      name: Alice-Bob Agreement
      paidOrders: 0
      contracts:
        embedded: {type: Contracts/Process Embedded, collectionPaths: [/orders]}
        fromPaid:
          type: Contracts/Embedded Node Channel
          sourcePath: /orders
          event: {kind: Tutorial/Order Paid}
        countPaid:
          type: Contracts/Sequential Workflow
          channel: fromPaid
          steps:
            - type: Contracts/Compute
              do:
                - $appendChange: {op: replace, path: /paidOrders, val: {$add: [{$document: /paidOrders}, 1]}}
      """).with("orders", Node.object(Map.of("order-001", order)));
}
```

<a id="lesson-15"></a>

## 15. Failure, no matching handler and retry

**Status: Local outcomes; durable command lifecycle unavailable.**

Source examples: the lesson README (no YAML files).

The README-only lesson asks for typed durable MyOS results. Local-1 instead returns
`ProcessingResult`: success or failure, retained/new state, changes, events, gas and work counts.
An unmatched event succeeds without effects; it is not a distinct `NO_MATCH` status.

This example checks an invalid request, an unmatched event, and deterministic retry of the same
input against the same immutable state with caches cleared. The counter explicitly rejects
non-integer amounts in its BEX rather than relying on unsupported canonical operation request
validation. Missing evidence and identity mismatches are covered by lessons 2/7; gas by lesson 12;
foreign engine state by lesson 8. Restart, durable demands, READY heads and exactly-once external
event delivery have no equivalent API yet. Rejected calls expose no tentative events or changes.

```java
ContractState before = blue.initialize(counter(blue)).requireApplied().state();
ProcessingResult unmatched = blue.process(before, blue.readYaml("kind: No matching channel")).requireApplied();
check(unmatched.events().isEmpty() && unmatched.changes().isEmpty(), "No matching effects");
check(unmatched.state().document().equals(before.document()), "Unchanged document");
ProcessingResult invalid = blue.call(before, "increment",
    Node.object(Map.of("amount", Node.text("wrong"))));
check(!invalid.applied() && invalid.state() == before, "Invalid request rolls back");
check(invalid.events().isEmpty(), "No rejected events escape");
ProcessingResult first = blue.call(before, "increment", request("amount", 1)).requireApplied();
blue.clearCaches();
ProcessingResult retry = blue.call(before, "increment", request("amount", 1)).requireApplied();
check(first.state().document().equals(retry.state().document()), "Same resulting document");
check(first.events().equals(retry.events()) && first.changes().equals(retry.changes()), "Same effects");
check(first.gasUsed() == retry.gasUsed(), "Same gas");
```

<a id="lesson-16"></a>

## 16. Repository types, Money and cold-chain events

**Status: Canonical provider pattern plus scaled-integer Local-1 example.**

Source examples: `01_temperature_logger.yaml`, `02_vaccine_shipment.yaml`

The archive supplies **no concrete repo.blue or FINOS fixture/identity** for parts A/B. We cannot
invent one or claim a tested FINOS integration. The first example creates a clearly named local
Money definition to demonstrate the same provider boundary. Replace it only with verified
records from an actual pinned publication when integrating that repository. The
[canonical package loader](PACKAGES.md) can now provide those verified definitions; a concrete
FINOS publication and its execution integration are still absent from this example.

Canonical Language supports `Double` (`9.5` below). Local-1 execution does not; the cold-chain
adaptation therefore uses integer milli-degrees (`9500 == 9.5°C`). It uses a tree-local logger,
explicitly raises an excursion above 8000 milli-degrees, and verifies the shipment reaction.
The archive's logger emits an excursion for **every** report; adding a threshold here is an
intentional improvement, not a claim that the source fixture already validates temperature.
Actual clinical, transport or payment policies are outside these teaching examples.

```java
LanguageDocument money = language.defineYaml("""
    name: Money
    amountMinor: {type: Integer}
    currency: {type: Text}
    """);
Map<BlueId, LanguageRecord> records = new HashMap<>();
AtomicInteger fetches = new AtomicInteger();
Language reader = Language.newBuilder().provider(id -> {
  fetches.incrementAndGet();
  return Optional.ofNullable(records.get(id));
}).build();
expectIncomplete(() -> reader.define(money.blueId()));
records.putAll(money.records());
LanguageDocument loadedType = reader.define(money.blueId());
LanguageDocument amount = loadedType.specializeYaml("amountMinor: 1250\ncurrency: EUR");
check(amount.at("/amountMinor").integer() == 1250, "Local Money example");
check(fetches.get() > 0, "Selective provider used");
check(language.readYaml("temperatureC: 9.5").at("/temperatureC").number() == 9.5, "Canonical Double");

ContractState state = blue.initialize(shipment(blue)).requireApplied().state();
ProcessingResult excursion = blue.call(state, "/logger", "reportTemperature", request("temperatureMilliC", 9500)).requireApplied();
check(excursion.state().document().get("status").text().equals("quarantined"), "Shipment status");
check(excursion.state().document().get("releaseBlocked").bool(), "Release blocked");
check(excursion.state().document().get("excursionCount").integer() == 1, "One excursion");
```

Complete local definition used above:

```java
private static Node shipment(Blue blue) {
  Node logger = local(blue, """
      name: Temperature Logger
      temperatureMilliC: 4000
      state: normal
      contracts:
        in: {type: Local/Operation Channel}
        reportTemperature:
          type: Contracts/Sequential Workflow Operation
          channel: in
          steps:
            - type: Contracts/Compute
              do:
                - $appendChange:
                    op: replace
                    path: /temperatureMilliC
                    val: {$binding: call/request/temperatureMilliC}
                - $if:
                    cond: {$gt: [{$binding: call/request/temperatureMilliC}, 8000]}
                    then:
                      - $appendChange: {op: replace, path: /state, val: excursion}
                      - $appendEvent: {kind: Tutorial/Temperature Excursion}
      """);
  return local(blue, """
      name: Vaccine Shipment
      status: releasable
      releaseBlocked: false
      excursionCount: 0
      contracts:
        embedded: {type: Contracts/Process Embedded, paths: [/logger]}
        fromExcursion:
          type: Contracts/Embedded Node Channel
          sourcePath: /logger
          event: {kind: Tutorial/Temperature Excursion}
        quarantine:
          type: Contracts/Sequential Workflow
          channel: fromExcursion
          steps:
            - type: Contracts/Compute
              do:
                - $appendChange: {op: replace, path: /status, val: quarantined}
                - $appendChange: {op: replace, path: /releaseBlocked, val: true}
                - $appendChange: {op: replace, path: /excursionCount, val: {$add: [{$document: /excursionCount}, 1]}}
      """).with("logger", logger);
}
```

<a id="lesson-17"></a>

## 17. What can be concluded, and a new domain

**Status: Recap without claiming missing host features.**

Source examples: the lesson README (no YAML files).

The archive recap describes a larger MyOS system than this project implements. What the guide
actually establishes is: canonical content operations, typed definitions, validated edits,
verified providers, local immutable execution snapshots, operation/event processing, local gas,
clauses and tree embedding. The status table at the start remains the boundary of the claim.

For the final exercise, this local Device definition can be specialized, evolved, referenced
and stored without adding a domain-specific Java class to the engine. An event-driven domain
uses the same authored workflow pattern as the counter/shipment helpers. Its host must separately
supply authentication, persistence, reliable delivery and shared graph coordination if required.

```java
LanguageDocument device = language.defineYaml("""
    name: Device
    serial: {type: Text, schema: {required: true}}
    firmware: {type: Integer, schema: {minimum: 1}}
    """);
LanguageDocument first = device.specializeYaml("serial: sensor-17\nfirmware: 1");
LanguageDocument upgraded = first.edit(e -> e.set("/firmware/value", 2));
check(first.at("/firmware").integer() == 1, "Old device content");
check(upgraded.at("/firmware").integer() == 2, "New device content");
expectInvalid(() -> first.edit(e -> e.set("/firmware/value", 0)));
```

<a id="lesson-18"></a>

## 18. Order initialization, a host and a factory

**Status: Local-1 adaptation of all four extra fixtures.**

Source examples: `01-order.yaml`, `02-host.yaml`, `factory-host.yaml`, `factory-input.yaml`

This folder has no README but contains four additional examples. `01-order.yaml` provides
lifecycle initialization, `markReady` and `approve`. `02-host.yaml` accepts arbitrary keys but its
listeners only cover `order-01` and `order-02`. Our `orderHost` listens to initialization from the
whole `/orders` collection, so arbitrary keys also work. The callback reads the emitted event's
`orderDocumentId`, not a hard-coded child's path.

`factory-host.yaml` exposes the fixed-slot `createOrder01` operation. `factory-input.yaml` is an
`order:` request envelope. It should be submitted as the request, not initialized as a root.
The full `factoryHost` helper below retains that fixed-slot operation. We add a duplicate guard;
the original fixed-slot fixture lacks one. As in other local examples, an embedded order is a
new tree occurrence, not a reference to an independent managed session.

```java
ContractState host = blue.initialize(orderHost(blue)).requireApplied().state();
ProcessingResult added = blue.call(host, "addOrder", orderRequest("order-02", order(blue, "customer-42"))).requireApplied();
check(added.state().document().at("/orders/order-02/initializedState").text().equals("initialized"), "Lifecycle ran");
check(added.state().document().get("initializedOrderCount").integer() == 1, "Host observed initialization");
check(added.events().stream().anyMatch(e -> e.event().get("kind").text().equals("Tutorial/Host Observed Order Initialization")),
    "Parent event");
ContractState factory = blue.initialize(factoryHost(blue)).requireApplied().state();
Node factoryInput = Node.object(Map.of("order", order(blue, "customer-42")));
ProcessingResult created = blue.call(factory, "createOrder01", factoryInput).requireApplied();
ProcessingResult approved = blue.call(created.state(), "/orders/order-01", "approve", Node.object()).requireApplied();
check(approved.state().document().at("/orders/order-01/status").text().equals("approved"), "Factory order approved");
check(!blue.call(created.state(), "createOrder01", factoryInput).applied(), "Duplicate slot rejected");
```

Complete local definition used above:

```java
private static Node factoryHost(Blue blue) {
  Node operation = blue.readYaml("""
      type: Contracts/Sequential Workflow Operation
      channel: in
      steps:
        - type: Contracts/Compute
          do:
            - $failIf:
                cond: {$hasKey: {object: {$document: /orders}, key: order-01}}
                message: Order slot already exists
            - $appendChange: {op: add, path: /orders/order-01, val: {$binding: call/request/order}}
      """);
  // Author a new host before initialization. This is not a live /contracts mutation.
  return orderHost(blue).edit(e -> e.set("/contracts/createOrder01", operation));
}
```

## Shared tutorial helpers

These functions do not hide business behavior. `local` parses explicitly authored Local-1 YAML
and supplies its exact profile. Each nested executable document or clause is passed through it
separately. `request` only builds a one-field request. The remaining helpers are assertions.

```java
private static Node local(Blue blue, String yaml) {
  return ExecutionProfile.local().applyTo(blue.readYaml(yaml));
}

private static Node request(String key, long value) {
  return Node.object(Map.of(key, Node.integer(value)));
}

private static void check(boolean condition, String message) {
  if (!condition) {
    throw new AssertionError(message);
  }
}

private static void expectInvalid(Runnable action) {
  try {
    action.run();
  } catch (LanguageException failure) {
    check(!failure.incomplete(), "Expected a conclusive rejection");
    return;
  }
  throw new AssertionError("Expected invalid Language content");
}

private static void expectIncomplete(Runnable action) {
  try {
    action.run();
  } catch (LanguageException failure) {
    check(failure.incomplete(), "Expected incomplete evidence");
    return;
  }
  throw new AssertionError("Expected incomplete Language evidence");
}

private static void expectLanguage(String category, Runnable action) {
  try {
    action.run();
  } catch (LanguageException failure) {
    check(failure.category().equals(category), "Unexpected Language error: " + failure.category());
    return;
  }
  throw new AssertionError("Expected Language error: " + category);
}

private static void expectBlue(Runnable action) {
  try {
    action.run();
  } catch (BlueException expected) {
    return;
  }
  throw new AssertionError("Expected a rejected local-runtime operation");
}
```

## Reloading exports safely

Supporting records must accompany referenced definitions when moving content to another process.
For example, after obtaining any `LanguageDocument original`:

```java
Language.Builder receiver = Language.newBuilder();
original.records().forEach(receiver::record);
Language destination = receiver.build();
LanguageDocument fromExact = destination.read(
    Language.Input.exactJson(original.toJson()), original.goal());
LanguageDocument fromCompact = destination.read(
    Language.Input.compactJson(original.canonicalJson()), original.goal());
```

These preserve content identity. They do not recreate `ContractState` or managed history.
For a provider-backed application, store records durably and implement `LanguageProvider` over
that store. The in-memory maps in this tutorial intentionally make no durability claim.

## Complete archive index

Each source file is accounted for below. Runtime fixtures are mapped to the section's explicit
adaptation or limitation; their original MyOS type names/BlueIds are not silently reinterpreted.
The extra snapshot and request-envelope files are included, not mistaken for new managed roots.

| Archive path | Guide section |
| --- | --- |
| `01_blue_workbench_and_identity/01_xyz.yaml` | [Lesson 01](#lesson-01) |
| `01_blue_workbench_and_identity/02_xyz_changed.yaml` | [Lesson 01](#lesson-01) |
| `01_blue_workbench_and_identity/03_scalar_compact.yaml` | [Lesson 01](#lesson-01) |
| `01_blue_workbench_and_identity/04_scalar_explicit.yaml` | [Lesson 01](#lesson-01) |
| `01_blue_workbench_and_identity/README.md` | [Lesson 01](#lesson-01) |
| `02_content_graph_and_references/01_parent_inline.yaml` | [Lesson 02](#lesson-02) |
| `02_content_graph_and_references/02_parent_reference.template.yaml` | [Lesson 02](#lesson-02) |
| `02_content_graph_and_references/03_missing_reference.template.yaml` | [Lesson 02](#lesson-02) |
| `02_content_graph_and_references/README.md` | [Lesson 02](#lesson-02) |
| `03_types_and_liskov/01_point_type.yaml` | [Lesson 03](#lesson-03) |
| `03_types_and_liskov/02_colored_point_type.template.yaml` | [Lesson 03](#lesson-03) |
| `03_types_and_liskov/03_valid_point.template.yaml` | [Lesson 03](#lesson-03) |
| `03_types_and_liskov/04_valid_colored_point.template.yaml` | [Lesson 03](#lesson-03) |
| `03_types_and_liskov/05_invalid_point.template.yaml` | [Lesson 03](#lesson-03) |
| `03_types_and_liskov/README.md` | [Lesson 03](#lesson-03) |
| `04_lists_and_canonical_forms/01_list_compact.yaml` | [Lesson 04](#lesson-04) |
| `04_lists_and_canonical_forms/02_list_explicit.yaml` | [Lesson 04](#lesson-04) |
| `04_lists_and_canonical_forms/03_list_reordered.yaml` | [Lesson 04](#lesson-04) |
| `04_lists_and_canonical_forms/04_empty_list.yaml` | [Lesson 04](#lesson-04) |
| `04_lists_and_canonical_forms/05_type_only_list.yaml` | [Lesson 04](#lesson-04) |
| `04_lists_and_canonical_forms/README.md` | [Lesson 04](#lesson-04) |
| `05_content_vs_managed_documents/01_bare_document.yaml` | [Lesson 05](#lesson-05) |
| `05_content_vs_managed_documents/README.md` | [Lesson 05](#lesson-05) |
| `06_counter_timelines_workflows_bex_gas/README.md` | [Lesson 06](#lesson-06) |
| `06_counter_timelines_workflows_bex_gas/counter.yaml` | [Lesson 06](#lesson-06) |
| `07_reference_transparency_and_missing_content/01_reference_counter.yaml` | [Lesson 07](#lesson-07) |
| `07_reference_transparency_and_missing_content/02_counter_value.yaml` | [Lesson 07](#lesson-07) |
| `07_reference_transparency_and_missing_content/03_counter_with_collapsed_value.template.yaml` | [Lesson 07](#lesson-07) |
| `07_reference_transparency_and_missing_content/04_owner_channel.yaml` | [Lesson 07](#lesson-07) |
| `07_reference_transparency_and_missing_content/05_counter_collapsed_channel.template.yaml` | [Lesson 07](#lesson-07) |
| `07_reference_transparency_and_missing_content/06_increment_operation.yaml` | [Lesson 07](#lesson-07) |
| `07_reference_transparency_and_missing_content/07_counter_collapsed_operation.template.yaml` | [Lesson 07](#lesson-07) |
| `07_reference_transparency_and_missing_content/08_unknown_contract_reference.template.yaml` | [Lesson 07](#lesson-07) |
| `07_reference_transparency_and_missing_content/README.md` | [Lesson 07](#lesson-07) |
| `08_managed_history_and_reopen/README.md` | [Lesson 08](#lesson-08) |
| `09_dynamic_contracts_and_generalization/01_dynamic_contract_host.yaml` | [Lesson 09](#lesson-09) |
| `09_dynamic_contracts_and_generalization/02_installed_operation.yaml` | [Lesson 09](#lesson-09) |
| `09_dynamic_contracts_and_generalization/03_generalization_base_type.yaml` | [Lesson 09](#lesson-09) |
| `09_dynamic_contracts_and_generalization/04_generalization_subtype.template.yaml` | [Lesson 09](#lesson-09) |
| `09_dynamic_contracts_and_generalization/05_generalization_instance.template.yaml` | [Lesson 09](#lesson-09) |
| `09_dynamic_contracts_and_generalization/README.md` | [Lesson 09](#lesson-09) |
| `10_managed_embedding_and_catchup/01_child_counter.yaml` | [Lesson 10](#lesson-10) |
| `10_managed_embedding_and_catchup/02_host.yaml` | [Lesson 10](#lesson-10) |
| `10_managed_embedding_and_catchup/README.md` | [Lesson 10](#lesson-10) |
| `10_managed_embedding_and_catchup/child.yaml` | [Lesson 10](#lesson-10) |
| `11_shared_child_and_occurrences/01_two_slot_host.yaml` | [Lesson 11](#lesson-11) |
| `11_shared_child_and_occurrences/02_second_parent.yaml` | [Lesson 11](#lesson-11) |
| `11_shared_child_and_occurrences/README.md` | [Lesson 11](#lesson-11) |
| `12_cycles_gas_and_detach/01_b.yaml` | [Lesson 12](#lesson-12) |
| `12_cycles_gas_and_detach/02_a.yaml` | [Lesson 12](#lesson-12) |
| `12_cycles_gas_and_detach/README.md` | [Lesson 12](#lesson-12) |
| `12_cycles_gas_and_detach/a.yaml` | [Lesson 12](#lesson-12) |
| `13_stable_key_embedded_collections/01_order.yaml` | [Lesson 13](#lesson-13) |
| `13_stable_key_embedded_collections/02_order_host.yaml` | [Lesson 13](#lesson-13) |
| `13_stable_key_embedded_collections/03_list_collection_negative.yaml` | [Lesson 13](#lesson-13) |
| `13_stable_key_embedded_collections/README.md` | [Lesson 13](#lesson-13) |
| `14_agreement_order_payment/01_agreement.yaml` | [Lesson 14](#lesson-14) |
| `14_agreement_order_payment/02_order.yaml` | [Lesson 14](#lesson-14) |
| `14_agreement_order_payment/03_payment.yaml` | [Lesson 14](#lesson-14) |
| `14_agreement_order_payment/README.md` | [Lesson 14](#lesson-14) |
| `15_failure_restart_and_no_match/README.md` | [Lesson 15](#lesson-15) |
| `16_real_types_finos_and_cold_chain/01_temperature_logger.yaml` | [Lesson 16](#lesson-16) |
| `16_real_types_finos_and_cold_chain/02_vaccine_shipment.yaml` | [Lesson 16](#lesson-16) |
| `16_real_types_finos_and_cold_chain/README.md` | [Lesson 16](#lesson-16) |
| `17_recap_and_future/README.md` | [Lesson 17](#lesson-17) |
| `18_orders/01-order.yaml` | [Lesson 18](#lesson-18) |
| `18_orders/02-host.yaml` | [Lesson 18](#lesson-18) |
| `18_orders/factory-host.yaml` | [Lesson 18](#lesson-18) |
| `18_orders/factory-input.yaml` | [Lesson 18](#lesson-18) |

Archive SHA-256: `176d42f32755048785205be9a5730459b8dc468a876a0788ab4bf7dbc12e5062`.

## Verification

All 18 sections of the executable companion were compiled and run against this checkout.
All documented assertions passed, including expected failures and the explicit demonstrations
of unsupported shared-state behavior. This verifies the adaptations, not the original MyOS
acceptance criteria. No engine semantics were changed for this guide.

## What remains outside this guide's implementation claims

Canonical Language is implemented for the fixed edition in `Language.EDITION`; runtime binding to
canonical BEX/Contracts/Coordination is separate work. Local-1 does not provide MyOS timelines,
actor authorization, durable commands/demands, canonical epochs/checkpoints/gas receipts,
historical session reopening, source-shared managed identities, occurrence catch-up, cyclic
managed component publication, external event delivery, real FINOS execution integration or `.bluepkg` runtime
installation. See [SUPPORTED.md](SUPPORTED.md) and [EXPERIMENT-SPEC.md](EXPERIMENT-SPEC.md).

The archive's examples also contain teaching shortcuts: qualified strings used as type labels
in lesson 3 (labels are not export bindings), the counter-value mismatch in lesson 7,
the missing capture permission check in lesson 14, the unconditional excursion in lesson 16, and
the two hard-coded listener paths in lesson 18. The adaptations above name those changes explicitly.
