# Canonical Language

`Language` implements `blue-language/2026-09-26-specification-metadata-1`. It has no runtime
or test dependency on another checkout, Python, or Caffeine. The standard registry and the
published conformance vectors are bundled; their origins and hashes are in
[LANGUAGE-SOURCES.json](LANGUAGE-SOURCES.json).

The semantic algorithms were adapted from the original Blue Java implementation and checked
against the canonical Language specification. They are not wrappers around that library. The
new API uses immutable content handles, private operation state, and persistent evidence maps.

## Three different things

| API | Meaning |
| --- | --- |
| `LanguageContent` | Prepared exact content and supporting evidence. Its BlueId does not certify semantic validity. |
| `LanguageDocument` | Exact content successfully admitted as a definition or completed value. |
| `LanguageView` | Selected observations of own and inherited contributions. It does not replace the exact content. |

`Language` is immutable and thread-safe. Builders are mutable and not thread-safe. Providers must
be thread-safe when the processor is shared. Each operation has its own working state and limits.

```java
Language language = Language.create();
LanguageDocument document = language.readYaml("message: hello\ncount: 7");

BlueId id = document.blueId();
long count = document.at("/count").integer();
String exactYaml = document.toYaml();
String exactJson = document.toJson();
```

`readYaml` and `readJson` perform **Value** admission. Use `defineYaml` / `defineJson` for a
reusable schema that leaves payloads unfilled. All four accept strings or UTF-8 file paths.
See the [tutorial](TUTORIAL.md) for the small everyday API. `prepare(Input)` only constructs exact content;
`admit(content, goal)` subsequently checks its complete dependency closure and constraints.
A selected read can succeed while strict admission rejects an unrelated invalid child.

## Inheritance and specialization

```java
LanguageDocument person = language.defineYaml("""
    name: Person
    age:
      type: Integer
      schema: {minimum: 0}
    country: PL
    """);

Language app = Language.newBuilder()
    .type("Example/Person", person)
    .build();

LanguageDocument alice = app.readYaml("type: Example/Person\nage: 42");
assert alice.resolve().at("/country").text().equals("PL");
assert app.lookupExact(alice, "/country").isMissing();
```

`name: Person` is content metadata. The separately registered `Example/Person` is an authoring
alias, normally formed from an imported package prefix and export name. Registering the same
`person` as `Other/Customer` would resolve to the same BlueId; neither binding changes the
node's label or contents. Package membership is not inferred from `name`. See the
[package/export examples](PACKAGES.md#8-different-package-names-can-select-the-same-document).

The country is inherited, not inserted into the exact origin. A conflicting fixed value or a
negative age fails admission. `person.specializeYaml("age: 42")` is equivalent
to authoring a new node whose `type` references `person`.

`person.extendYaml(...)` creates a reusable subtype. Both extension and specialization accept an
explicit type only if it is the same parent or a compatible subtype, and never overwrite it.
`document.edit(...)` applies exact authoring edits and validates the result; it does not replay
Source controls or mutate the original. Lists in `node()` and editing always use `/items/index`,
even if the serialized wire uses array shorthand.

`refine(base, overlay, goal)` combines already exact contributions without replaying Source.
`isSubtype(candidate, parent)` checks nominal/type semantics; `preserves(candidate, parent)`
checks preservation of requirements. `refineSchema` narrows existing predicates;
`conjoinSchemas` combines independent predicates. Schema arithmetic uses exact rational
comparisons while Double identities follow the required binary64 encoding.

## List forms and successive versions

All list construction is explicit. Plain lists, typed lists, positional restrictions, holes,
full presentations, pure positional edits, append-only prefixes and suffixes are supported.
The Source controls are `$pos`, `$replace`, `$previous`, and `$previousItems`; they are not
ordinary operations to be rerun when exact content is reopened.

A positional list can keep a fixed second item while filling its first slot:

```java
LanguageDocument slots = language.defineYaml("""
    type: List
    mergePolicy: positional
    items: [{type: Integer}, fixed]
    """);
LanguageDocument filled = slots.specializeYaml("items: [{$pos: 0, value: 7}]");
assert filled.resolve().at("/items/0").integer() == 7;
assert filled.resolve().at("/items/1").text().equals("fixed");
```

An append-only log can have successive content versions:

```java
LanguageDocument v1 = language.readYaml("""
    type: List
    mergePolicy: append-only
    items: [{$previousItems: false}, opened]
    """);
LanguageDocument v2 = v1.specializeYaml("items: [{$previousItems: true}, approved]");
LanguageDocument v3 = v2.specializeYaml("items: [{$previousItems: true}, completed]");
// v1, v2, v3 retain 1, 2, 3 elements respectively and have distinct BlueIds.
```

Removing or replacing an inherited append-only prefix fails. Positional edits likewise cannot
contradict fixed inherited values. `itemType` constrains every element, including appended ones.
For raw payload lists, `foldList(prefixId, suffix)` computes the extended list identity without
fetching the prefix. A typed list-node identity and its payload-list identity are different:
do not pass a typed node's ID as a payload prefix.

## Input roles and round trips

| Role | Accepted meaning |
| --- | --- |
| `SOURCE` | Authored aliases, shorthand and one-time construction. `Input.yaml/json` use this role. |
| `EXACT` | Existing exact content. Use `Input.exactYaml/exactJson` for `toYaml/toJson` output. |
| `DIRECT` | Exact input with reference-only type and specification slots. Construct an `Input` with this role explicitly. |
| `COMPACT` | Compact exact wire with verified list-prefix decoding. Use `Input.compactJson`. |

A failed role is never silently retried under another one. Reopening exported lists must not
append their suffix a second time. Preserve supporting records across process boundaries:

```java
var receiver = Language.newBuilder();
v3.records().forEach(receiver::record);
var restored = receiver.build().read(
    Language.Input.compactJson(language.compactJson(v3)), Language.Goal.VALUE);
assert restored.blueId().equals(v3.blueId());
assert restored.resolve().size() == 3;
```

Integers beyond the JSON safe-integer range use the specification's typed text encoding.
Doubles remain distinct from Integers. YAML parsing preserves text, rejects duplicate keys,
anchors, aliases, explicit tags and multiple documents. JSON rejects duplicate keys, invalid
Unicode, trailing values and nonfinite numeric values.

## Incremental content transport

A receiver that retains complete record closures can request only new records without enumerating
its existing inventory. The membership predicate is a transport optimization, not an admission token.

```java
Map<BlueId, LanguageRecord> retained = first.records();
Map<BlueId, LanguageRecord> additions = next.records(retained::containsKey);
Language.Builder receiving = Language.newBuilder();
retained.forEach(receiving::record);
additions.forEach((id, record) ->
    receiving.record(id, LanguageRecord.fromJson(record.toJson())));
LanguageDocument restored = receiving.build().read(next.blueId());
```

Use a persistent store lookup as the predicate when the inventory is large. Return true only if the
receiver already has that identity's complete supporting closure, and keep the answer stable during
export. A missing dependency still fails when the receiver resolves it. Ordinary `records()` exports
the full closure as before.

`LanguageRecord.toJson()` encodes a provider envelope containing exact text or a cyclic owning-set
proof and claimed body. `LanguageRecord.fromJson()` reloads that envelope as **unverified** evidence.
It differs from `LanguageRecord.json(text)`, which wraps the exact document body itself. Neither API
authenticates a claimed BlueId before resolution. Compact document wire and provider-record envelopes
are separate formats; preserve the document wire too when its original presentation matters.

## Providers, expansion and arbitrary content

Providers can serve any exact content, not just type declarations. Responses are verified against
the requested BlueId. An empty response means unavailable evidence, not an absent document field.

```java
LanguageDocument offered = language.readYaml("details: {amount: 42}");
Language reader = Language.newBuilder()
    .provider(id -> id.equals(offered.blueId())
        ? Optional.of(LanguageRecord.json(offered.toJson()))
        : Optional.empty())
    .build();
LanguageContent reference = reader.reference(offered.blueId());
// No fetch during construction of this reference.
long amount = reference.at("/details/amount").integer();
```

The `nodeProvider` adapter also accepts an existing `NodeProvider` of exact acyclic content.
A cyclic member needs `LanguageRecord.cyclic(proof)` (optionally with a separately claimed body).
Providers are consulted in registration order. References never authorize unverified content.
Verified dependencies stay with admitted handles; repeated selected reads of a prepared reference
can call its provider again. There is no implicit shared network cache or journal.

```java
var original = language.readYaml("details: {amount: 42}\nnote: hello");
var collapsed = language.collapse(original, "/details");
var expanded = language.expand(collapsed, "/details");
assert original.blueId().equals(collapsed.blueId());
assert original.blueId().equals(expanded.blueId());
var fragments = language.split(original, "/details/amount");
```

Expansion materializes exact references; it does not flatten inheritance. Expanded type and
specification slots retain their semantic meaning in subsequent reads and builder registrations. Cuts select complete
nodes. `/items` is a sequence slot, not a complete node. Selecting `/items/0` is valid when that
item exists. Splitting includes the exact ancestor fragments needed to reconnect a nested cut.

`minimize(document)` produces smaller Source with the same effective meaning; its BlueId may
change. `inspect(document)` produces a complete resolved **description for inspection**, not
reloadable exact content. Specification metadata is attached to its own node and is not inherited.

## Cyclic content

```java
var loop = language.finalizeSet(List.of(Language.Input.exactJson("""
    {"name":"Loop","self":{"blueId":"this#0"}}
    """)), Language.Goal.VALUE);
var member = loop.members().getFirst();
assert member.resolve().at("/self/self").exists();
// member.blueId(): MASTER#index; loop.proofJson(): complete canonical owning set.
```

Finalization accepts Exact symbolic members, verifies the strongly connected set and returns
members in the caller's order with canonical identities. Type cycles are rejected; permitted
recursive data is not confused with cyclic inheritance. A bare `MASTER#index` is a commitment,
not a proof: external resolution requires the complete owning-set evidence.

## Limits and the execution boundary

`LanguageException.category()` gives a stable failure category. `incomplete()` distinguishes
missing evidence/resource exhaustion from invalid content. `diagnostic()` contains structured
details and paths where established. Limits apply to original input, provider bodies and cyclic
proofs. `Language.Limits` controls work, depth, value count, fetches, text bytes, numeric token
length and decimal scale. These are host resource bounds, not execution gas.

This processor supports the edition named by `Language.EDITION`. Successive list versions are
supported; arbitrary historical Language semantic editions are not emulated.

Use `Language` directly for these content operations. Canonical `.bluepkg` loading and mounting
are described in the [package guide](PACKAGES.md). `Contracts` binds the selected Language
configuration to canonical BEX and Contracts execution; loading a package alone does not install
native capabilities. See [canonical execution](CANONICAL-EXECUTION.md) for processing documents
and [capability boundaries](CAPABILITY-GAPS.md) for the supported Coordination integrations.

## Run the examples

```bash
./gradlew languageTest
./gradlew languageExample
```

The example checks identity, providers, inheritance, lists, cyclic content and schema evolution.
Its source is [`CompleteTutorialExample.java`](../src/demo/java/blue/examples/CompleteTutorialExample.java).
Use `toYaml()`, `toJson()` or `canonicalJson()` on a document to produce the corresponding export.
