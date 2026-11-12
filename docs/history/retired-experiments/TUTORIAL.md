> Historical experiment, retired 2026-11-08. This is not a supported API guide.

# Learning the API through the tutorial

For every lesson and source example in the archive, see the
[complete 18-lesson tutorial](TUTORIAL-COMPLETE.md).

These examples use `blue-java-better`. Run them with JDK 25:

```bash
./gradlew tutorialExample
```

The executable [TutorialExample](../../../src/demo/java/blue/examples/TutorialExample.java) checks its
results and writes YAML and canonical JSON under `build/reports/tutorial/`. Its tests do not need
Python, sibling checkouts, the original ZIP, or network access. Selected YAML examples from the
supplied `BLUE_MYOS_TUTORIAL_AND_CODEX_PROMPT_20260830/tutorial/tutorial.zip` are bundled unchanged
under `src/demo/resources/tutorial/original/`; `SOURCES.json` records their hashes. BlueId
placeholders are filled at runtime. The runnable demo also changes the two qualified Point name
labels to plain display labels; export bindings are configured separately. Embedded instructions
in the archive are not build scripts or additional implementation requirements.

## The small API to learn first

```java
Language language = Language.create();
LanguageDocument document = language.readYaml("message: hello\ncount: 7");
BlueId identity = document.blueId();
long count = document.at("/count").integer();
String yaml = document.toYaml();
String canonical = document.canonicalJson();
```

`LanguageDocument` is immutable, validated content with its supporting dependencies. `node()`
exposes its own exact fields as an immutable `Node`; `at()` reads effective values, including
inheritance and references. A `Language` instance can be reused across threads. Configure aliases,
providers and resource limits once with `Language.newBuilder()` when needed.

The design inspiration from [Caffeine's public API](https://github.com/ben-manes/caffeine/wiki/Population)
is a small set of named operations, immutable configuration after building, and ordinary views
for advanced use. This is an API design choice, not a claim of Caffeine's maturity or performance.
There is no Caffeine dependency.

| Intent | Call |
| --- | --- |
| Load completed content | `language.readYaml(text)` / `readJson(text)` |
| Load a reusable definition | `language.defineYaml(text)` / `defineJson(text)` |
| Read either from disk | The same methods accept `Path` |
| Make a completed instance | `definition.specializeYaml(text)` / `specializeJson(text)` |
| Make a subtype definition | `definition.extendYaml(text)` / `extendJson(text)` |
| Read effective content | `document.at(pointer)` |
| Inspect own exact content | `document.node()` |
| Edit and revalidate | `document.edit(editor -> ...)` |
| Control presentation | `document.collapse(paths)` / `expand(paths)` |
| Obtain content by identity | `language.read(id)` / `define(id)` |
| Create an unfetched reference | `language.reference(id)` |

A definition can intentionally leave values unfilled. A completed value must satisfy its active
payload requirements. `defineYaml` expresses that difference without a `DEFINITION` argument;
there is no automatic fallback from failed value validation to definition validation. The advanced
`Input`, `Goal` and explicit role APIs remain available and compatible.

## 1. Different spelling, same identity

The tutorial's `x: 1` and `x: {value: 1}` are equivalent:

```java
LanguageDocument shortForm = language.readYaml("x: 1");
LanguageDocument explicitForm = language.readYaml("x: {value: 1}");
boolean same = shortForm.blueId().equals(explicitForm.blueId()); // true
System.out.println(shortForm.canonicalJson());
```

`name()` and `description()` read metadata. `exists()` follows Language's concrete-value
presence rule: a non-root metadata-only node or unfilled declaration is not a concrete payload.

Changing `description: Some doc` to `description: Some changed doc` changes the identity.
An identity is a commitment to content, not a mutable document's address or revision number.

## 2. Inline content, references and providers

```java
LanguageDocument child = language.readYaml("name: Xyz\ndescription: Some doc");
LanguageDocument parent = language.readYaml("""
    y: 1
    x:
      name: Xyz
      description: Some doc
    """);
LanguageDocument collapsed = parent.collapse("/x");
LanguageDocument expanded = collapsed.expand("/x");
String name = collapsed.at("/x").name().orElseThrow(); // Xyz
boolean same = parent.blueId().equals(collapsed.blueId()); // true
```

A local map can supply any content, including ordinary values and collapsed document fragments:

```java
Map<BlueId, LanguageRecord> records = new ConcurrentHashMap<>(child.records());
Language reader = Language.newBuilder()
    .provider(id -> Optional.ofNullable(records.get(id)))
    .build();

LanguageContent reference = reader.reference(child.blueId()); // no fetch
LanguageDocument loaded = reader.read(child.blueId());        // fetch + strict validation
System.out.println(loaded.toYaml());
```

`LanguageProvider` is a functional interface. Replace the map lookup with your storage adapter;
returned evidence is always checked against its BlueId. Empty means unavailable evidence, not
an absent field. A read that needs it throws `LanguageException` with `incomplete() == true`.
Populate the store and explicitly retry. Admitted documents retain verified evidence for later
reads. A prepared reference may ask its provider again; no hidden global cache is installed.

## 3. Point, Colored Point and invalid instances

```java
LanguageDocument point = language.defineYaml("""
    name: Point
    x: {type: Integer}
    y: {type: Integer}
    """);
LanguageDocument coloredPoint = point.extendYaml("""
    name: Colored Point
    color: {type: Text}
    """);
LanguageDocument value = coloredPoint.specializeJson("""
    {"x": 2, "y": 3, "color": "blue"}
    """);
long x = value.at("/x").integer();              // 2
String color = value.at("/color").text();      // blue
boolean subtype = language.isSubtype(coloredPoint, point); // true
```

No provider wiring is needed here: the definition handle carries the required evidence into its
subtype and instances. A string in `x` is rejected. Definitions and instances are both documents;
`extend` requests definition validation, while `specialize` requests completed-value validation.

`name` is optional content metadata, not a package address or registration instruction.
The labels above are `Point` and `Colored Point`; they could also be omitted. The `extendYaml`
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
[package guide](../../PACKAGES.md) demonstrates this same Point example using real archives.

The original archive used qualified strings as `name` labels. Those bytes are legal as labels,
but imply no registration; these curated examples deliberately use plain labels instead. Editing
a label changes exact content and therefore its BlueId. Moving or re-exporting unchanged content
does not. Historical files in `src/demo/resources/tutorial/original/` remain unchanged.

For independently loaded documents, bind the selected package exports once:

```java
Language app = Language.newBuilder()
    .type("Geometry/Point", point)
    .type("Drawing/Coordinate", point)
    .type("Drawing/Colored Point", coloredPoint)
    .build();
LanguageDocument independent = app.readYaml("""
    type: Drawing/Colored Point
    x: 2
    y: 3
    color: blue
    """);
LanguageDocument first = app.readYaml("type: Geometry/Point\nx: 2\ny: 3");
LanguageDocument second = app.readYaml("type: Drawing/Coordinate\nx: 2\ny: 3");
boolean sameId = first.blueId().equals(second.blueId()); // true
```

If a specialization already contains a `type`, it is accepted when it is the same parent or a
subtype according to Language semantics. It is preserved, never silently overwritten. An
incompatible explicit type fails with `IncompatibleType`; unavailable type evidence still fails
as incomplete. This replaces the previous blanket rejection of every explicit `type`.

## 4. Lists and three equivalent ways to remove an element

```java
LanguageDocument original = language.readYaml("values: [1, 2, 3]");
LanguageDocument removed = original.edit(e -> e.remove("/values/items/1"));
LanguageDocument replaced = original.edit(e -> e.set("/values/items",
    Node.list(List.of(Node.integer(1), Node.integer(3)))));
LanguageDocument authored = language.readYaml("values: [1, 3]");

boolean sameIds = removed.blueId().equals(replaced.blueId())
    && removed.blueId().equals(authored.blueId());              // true
boolean sameCanonical = removed.canonicalJson().equals(replaced.canonicalJson())
    && removed.canonicalJson().equals(authored.canonicalJson()); // true
System.out.println(removed.canonicalJson());
```

The original still has three elements. The JSON pointer uses `/items/index` consistently for plain
and typed lists in `node()`, `at()` and `edit()`. Serialized JSON/YAML may use array shorthand.
Changing the order changes the identity. `items: []` is an actual empty list; `type: List` with no
payload is a different definition.

For a list constrained by a parent, edits must still preserve its inherited requirements.
A mutable authoring edit does not give permission to delete a fixed inherited append-only prefix.
Use `specializeYaml` when intentionally creating a new typed list version with Source controls:

```java
LanguageDocument log = language.readYaml("""
    type: List
    mergePolicy: append-only
    items: [{$previousItems: false}, opened]
    """);
LanguageDocument next = log.specializeYaml(
    "items: [{$previousItems: true}, approved]");
```

Edits are processed as exact content and never replay those construction controls. Exported wire
also needs an explicit matching role when imported:

```java
Language receiver = Language.newBuilder().content(next).build();
LanguageDocument restored = receiver.read(
    Language.Input.compactJson(next.canonicalJson()), Language.Goal.VALUE);
```

## 5. Copy-on-write through a reference

The original tutorial counter value is 9. Use it twice, then change one use:

```java
LanguageDocument original = language.readYaml("left: {value: 9}\nright: {value: 9}");
LanguageDocument shared = original.collapse("/left", "/right");
LanguageDocument changed = shared.edit(e -> e.set("/left/value", 10));
long left = changed.at("/left").integer();   // 10
long right = changed.at("/right").integer(); // 9
long before = shared.at("/left").integer();  // 9
```

Only references along edited ancestor paths are materialized. Other branches remain collapsed.
After the callback, the entire candidate is validated under the original definition/value goal.
There is no serialization/reparse round trip. A rejected edit leaves the original handle intact.
This is an authoring operation, not a managed transaction or a workflow authorization decision.

Missing ancestors are not invented. Inherited fields remain inherited; add an own contribution
or specialize the definition rather than expecting all inherited structure to be copied into
an edit. Editing inside a cyclic member requires a newly finalized complete owning set; this
convenience method rejects it instead of silently changing the kind of identity.

## 6. The counter's execution idea, explicitly adapted to Local-1

The tutorial includes MyOS timelines, actors and protocol checkpoints. Those are not implemented
by this experimental runtime. The separate
[local-counter.yaml](../src/demo/resources/tutorial/local-counter.yaml) adapts the increment
workflow to the local operation channel and bindings:

```java
Blue blue = Blue.create();
Node source = blue.readYaml(Path.of("src/demo/resources/tutorial/local-counter.yaml"));
Node executable = ExecutionProfile.local().applyTo(source);
ContractState state = blue.initialize(executable).requireApplied().state();
ProcessingResult result = blue.call(state, "increment",
    Node.object(Map.of("amount", Node.integer(3)))).requireApplied();
long counter = result.state().document().at("/counter").integer(); // 3
System.out.println(result.events()); // Counter Changed, value 3
```

The runnable example performs +3 and -1, verifies 0 → 3 → 2, checks emitted events, and exports
before/after YAML. Keep the returned `ContractState` for subsequent calls. Loading a canonical
`LanguageDocument` is not the same as compiling it into this experimental execution runtime.

The existing [agreement/order example](../src/demo/java/blue/examples/Example.java) demonstrates
related ideas from lessons 10, 13 and 14: keyed embedded orders, child initialization events,
parent reactions and operations on a selected child. Run `./gradlew example` for that Local-1
adaptation. The archive's shared managed cycles, durable history/recovery, MyOS authorization,
FINOS package installation and complete Agreement/Order/Payment protocol are not claimed here.
See [supported surface](../../SUPPORTED.md).
