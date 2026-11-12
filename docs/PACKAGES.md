# Canonical packages: loading, names and content

`BluePackage` is a verified, immutable publication. `PackageLoader` reads it. `Language.Builder.mount`
adds selected exported names and the evidence they need to a Language context. Nothing is inferred
from a document's `name` field.

```java
BluePackage geometry = BluePackage.open(Path.of("geometry.bluepkg"), GEOMETRY);
Language language = Language.newBuilder().mount("Geometry", geometry).build();
LanguageDocument point = language.readYaml("type: Geometry/Point\nx: 2\ny: 3");
System.out.println(point.blueId());
System.out.println(point.canonicalJson());
```

The `GEOMETRY` constant is the exact package pin listed below, not the Point type's identity.
The runnable version uses the checked-in artifact, not a registry or a generated mock.

## Run every example

```bash
./gradlew packageExample
./gradlew packageTest
./gradlew build
```

The first command executes 18 named examples and writes YAML, JSON, canonical forms, identities and
`checks.txt` to `build/reports/packages/`. Each example is also an individual JUnit case in
`PackageExamplesTest`. [PackageExample.java](../src/demo/java/blue/examples/PackageExample.java)
contains the complete executable source. The snippets below are extracted from its named regions.
`check(condition, message)` throws `AssertionError` on failure. `expectLanguage` and `expectPackage`
assert the exact rejection category; they are demonstration helpers, not library methods.

Examples are independent. `geometry`, `drawing`, `newGeometry` and `content` are loaded from the
matching bundled archives when needed. A shared `PackageLoader loader = PackageLoader.create()`
is used where shown. Imports come from `blue.*`, `java.nio.file`, `java.io` and `java.util`.

## Publications and pins

The small Geometry/Drawing/Content publications are authored fixtures with a deliberately tiny
**tutorial Language package**. Their bootstrap identity differs from the real current Language
publication. Do not mount the two graphs together: that fails with `PackageSelectionMismatch`.
Example 18 uses a separate context for the real packages.

| Constant | Bundled archive | Exact package BlueId |
| --- | --- | --- |
| `GEOMETRY` | `packages/geometry.bluepkg` | `9Ru5h2T2c6N4daUET2LLHArEvQT6TR3QFPwTfteaq57v` |
| `DRAWING` | `packages/drawing.bluepkg` | `DmgQQFoRoikCw8SpyzgHxef3CZsyCkxkVbFDWa3EVbuZ` |
| `GEOMETRY_V2` | `packages/geometry-v2.bluepkg` | `8g8Xq4RS4pQmyLReVMPHFpgfnEoT6GBfyW153hUrALtN` |
| `CONTENT` | `packages/content.bluepkg` | `7QE5obau2Hu1pTrKtwCd1TBsgUUn5hGRdLbck4WbQmpg` |
| `TUTORIAL_LANGUAGE` | `packages/language.bluepkg` | `9gmTH7AViM69tjyPBsczyFq9G6XZZCYPnDrkgCQCNeWZ` |
| `CONTRACTS` | `packages/contracts.bluepkg` | `4Jmza3Z7eEDah9qtyYq5i2AqX5tKfUnLNKYAoAPoWzmF` |

`CURRENT_LANGUAGE` is `8XGYNufx24r7Dc5bGoeobcGNCn9roLzSfZCQyHBTaTx4`.
Declare each pin with `new BlueId("...")`. In runnable code the constants are on `PackageExample`.
[The fixture manifest](../src/demo/resources/packages/SOURCES.json) records archive SHA-256s and
provenance separately. Package BlueIds identify semantic publications; ZIP byte hashes identify
particular physical archives.

The small fixture definitions are intentionally plain:

```yaml
# geometry exports this definition as Point
x: {type: Integer}
y: {type: Integer}
```

```yaml
# drawing exports an extension as Colored Point
# The publication contains the exact Point BlueId in type.
type: {blueId: B8qeX9WKRBLKsoh1XeD9DCsJzYSufHXq2LcbQUPvPhSd}
color: {type: Text}
```

Drawing also re-exports that unchanged Point as `Coordinate`. Geometry v2 extends Point with
`z: {type: Integer}`. Content exports a Palette value and a cyclic Loop definition. Exported values
are valid definitions too; the package is not restricted to abstract schemas.

## 1. Open a pinned local archive

`path` is a `Path` to the bundled `geometry.bluepkg`, copied into the report directory by the demo. This convenient static method performs a fresh bounded load. Keep the returned package; use a shared loader when opening repeatedly.

```java
BluePackage geometry = BluePackage.open(path, GEOMETRY);
BlueId packageId = geometry.blueId();
BlueId pointId = geometry.export("Point");
LanguageDocument point = geometry.definition("Point");
check(packageId.equals(GEOMETRY), "The trusted package pin matches");
check(pointId.equals(point.blueId()), "Export selection carries verified evidence");
```

## 2. Load from application resources

Put the `.bluepkg` in `src/main/resources/packages/` in your application. This method closes its resource stream. Loading is local and never fetches dependencies from a network: the archive carries its exact dependency closure.

```java
PackageLoader packages = PackageLoader.newBuilder().language(TUTORIAL_LANGUAGE).build();
BluePackage geometry =
    packages.resource(
        PackageExample.class.getClassLoader(), "packages/geometry.bluepkg", GEOMETRY);
check(geometry.languageId().equals(TUTORIAL_LANGUAGE), "Language selection is pinned too");
```

## 3. Read a stream you own

`path` identifies the archive. `read` consumes but does not close the supplied stream, including on rejection. Use `try` with resources when you own it.

```java
try (InputStream input = Files.newInputStream(path)) {
  BluePackage geometry = loader.read(input, GEOMETRY);
  check(geometry.export("Point") != null, "The caller owns the stream lifetime");
}
```

## 4. Open a prepared directory

`directory` contains the four structural JSON files and the identified assets. All evidence and byte checks still apply. A sibling `package.bluepkg` inside that directory is ignored; this supports prepared output directories. The production loader never extracts ZIP members onto the filesystem.

```java
BluePackage geometry = loader.open(directory, GEOMETRY);
check(geometry.blueId().equals(GEOMETRY), "Folder and archive represent the same package");
```

## 5. Inspect metadata, exports, imports and documentation

A package name and version label describe the publication. They do not become a namespace automatically, and a version string never upgrades an exact reference.

```java
String publisherName = geometry.name();
String release = geometry.version();
Map<String, BlueId> exports = geometry.exports();
Map<String, BlueId> imports = geometry.imports();
String readme = geometry.documentation("README.md");
check(publisherName.equals("geometry") && release.equals("1.0.0"), "Release labels");
check(exports.containsKey("Point"), "Export names belong to the package");
check(imports.get("language").equals(TUTORIAL_LANGUAGE), "Imports are exact identities");
check(geometry.definition("Point").at("").name().isEmpty(), "No name metadata was invented");
```

## 6. Mount names, read YAML and export exact forms

`mount("Geometry", geometry)` makes this package's own exports available under `Geometry/…`. `readYaml` requires a complete value; `defineYaml` permits still-unfilled fields in a reusable definition. The inherited Integer fields are validated.

```java
Language language = Language.newBuilder().mount("Geometry", geometry).build();
LanguageDocument point =
    language.readYaml(
        """
        type: Geometry/Point
        x: 2
        y: 3
        """);
check(point.at("/x").integer() == 2, "Typed YAML is admitted as a complete value");
String yaml = point.toYaml();
String json = point.toJson();
String canonical = point.canonicalJson();
BlueId identity = point.blueId();
```

## 7. YAML, JSON and direct specialization agree

These are three ways to construct the same typed point. Field order and source syntax do not change its exact identity.

```java
LanguageDocument yaml = language.readYaml("type: Geometry/Point\nx: 2\ny: 3");
LanguageDocument json =
    language.readJson(
        """
        {"y": 3, "type": "Geometry/Point", "x": 2}
        """);
LanguageDocument direct = geometry.definition("Point").specializeYaml("x: 2\ny: 3");
check(
    yaml.blueId().equals(json.blueId()) && yaml.blueId().equals(direct.blueId()),
    "Same BlueId");
check(
    yaml.canonicalJson().equals(json.canonicalJson())
        && yaml.canonicalJson().equals(direct.canonicalJson()),
    "Same canonical form");
```

## 8. Different package names can select the same document

Here `Geometry/Point` and `Art/Coordinate` resolve to the same definition. The packages have different identities, but the unchanged exported content does not.

```java
Language language =
    Language.newBuilder().mount("Geometry", geometry).mount("Art", drawing).build();
LanguageDocument first = language.readYaml("type: Geometry/Point\nx: 2\ny: 3");
LanguageDocument second = language.readYaml("type: Art/Coordinate\nx: 2\ny: 3");
check(!geometry.blueId().equals(drawing.blueId()), "Different packages");
check(
    geometry.export("Point").equals(drawing.export("Coordinate")),
    "The same exported definition");
check(first.blueId().equals(second.blueId()), "The chosen namespace is not document identity");
```

## 9. Inherit across an exact package import

The drawing publication imports Geometry v1. Its Colored Point already refers to the exact Point definition. Mounting only `Colored Point` still brings the supporting evidence; it does not expose other export names.

```java
Language language =
    Language.newBuilder().mount("Art", drawing, List.of("Colored Point")).build();
LanguageDocument colored =
    language.readYaml(
        """
        type: Art/Colored Point
        x: 2
        y: 3
        color: blue
        """);
check(colored.at("/x").integer() == 2, "Imported Point fields are inherited");
check(colored.at("/color").text().equals("blue"), "Local extension is applied");
expectLanguage("UnknownTypeAlias", () -> language.defineYaml("type: Art/Coordinate"));
```

## 10. Extend a published type locally

No `DEFINITION` flag is needed. `extendYaml` returns a reusable definition; `specializeYaml` or `specializeJson` returns a complete value. Registering a local name with `.type(...)` is useful for application-only definitions. It does not publish a new `.bluepkg`.

```java
LanguageDocument coloredPoint =
    geometry
        .definition("Point")
        .extendYaml(
            """
            color: {type: Text}
            """);
Language language =
    Language.newBuilder().type("Application/Colored Point", coloredPoint).build();
LanguageDocument point =
    language.readYaml("type: Application/Colored Point\nx: 2\ny: 3\ncolor: red");
check(point.at("/color").text().equals("red"), "Local bindings and published types compose");
```

## 11. Choose imported namespaces explicitly

`imported("geometry")` uses the **publisher's direct import key**, returning an already-verified view of the exact dependency without I/O. `mount("Shapes", ...)` then chooses the **consumer's namespace**. Imported aliases never leak by themselves.

```java
BluePackage geometry = drawing.imported("geometry");
Language language =
    Language.newBuilder()
        .mount("Art", drawing)
        .mount("Shapes", geometry, List.of("Point"))
        .build();
LanguageDocument point = language.readYaml("type: Shapes/Point\nx: 2\ny: 3");
check(point.at("/y").integer() == 3, "The consumer chooses its names");
expectLanguage("UnknownTypeAlias", () -> language.defineYaml("type: geometry/Point"));
expectLanguage("UnknownTypeAlias", () -> language.defineYaml("type: Shapes/Origin"));
```

## 12. Keep two versions side by side

V1, V2 and Art coexist. Mounting V2 cannot retarget the Point reference stored in Art. This guarantees selection stability; it is not a promise that two versions are interchangeable.

```java
Language language =
    Language.newBuilder()
        .mount("V1", oldGeometry)
        .mount("V2", newGeometry)
        .mount("Art", drawing)
        .build();
LanguageDocument oldPoint = language.readYaml("type: V1/Point\nx: 2\ny: 3");
LanguageDocument newPoint = language.readYaml("type: V2/Point\nx: 2\ny: 3\nz: 4");
LanguageDocument artPoint =
    language.readYaml("type: Art/Colored Point\nx: 2\ny: 3\ncolor: blue");
check(
    !oldGeometry.export("Point").equals(newGeometry.export("Point")), "Distinct type versions");
check(newPoint.at("/z").integer() == 4, "V2 adds a dimension");
check(
    !oldPoint.at("/z").exists() && !artPoint.at("/z").exists(),
    "Existing imports stay pinned to V1");
```

## 13. Provide arbitrary content, including collapsed nodes

A `BluePackage` is also a `LanguageProvider`. Provider registration adds evidence without adding names. `reference` creates an opaque lazy reference; `read` validates a complete value. References may address unnamed supporting records too, using a known exact BlueId from the content graph.

```java
Language language = Language.newBuilder().provider(content).build();
BlueId paletteId = content.export("Palette");
LanguageContent reference = language.reference(paletteId);
check(
    reference.at("/colors/items/1").text().equals("blue"),
    "A package can provide ordinary content");
LanguageDocument palette = language.read(paletteId);
LanguageDocument collapsed = palette.collapse("/colors");
LanguageDocument expanded = collapsed.expand("/colors");
check(
    palette.blueId().equals(collapsed.blueId()) && palette.blueId().equals(expanded.blueId()),
    "Representation preserves identity");
expectLanguage("UnknownTypeAlias", () -> language.defineYaml("type: Content/Palette"));
```

## 14. Remove a list element in three equivalent ways

Edits address Exact structure, hence `/colors/items/1`. Supply a `Node.list(...)` when replacing an Exact list payload. All three operations below produce the same canonical form, while the package's original content remains unchanged.

```java
LanguageDocument palette = language.read(content.export("Palette"));
LanguageDocument removed = palette.edit(edit -> edit.remove("/colors/items/1"));
LanguageDocument replaced =
    palette.edit(
        edit ->
            edit.set(
                "/colors/items", Node.list(List.of(Node.text("red"), Node.text("green")))));
LanguageDocument authored = language.readYaml("colors: [red, green]");
check(
    removed.blueId().equals(replaced.blueId()) && removed.blueId().equals(authored.blueId()),
    "All three edits have the same identity");
check(
    removed.canonicalJson().equals(replaced.canonicalJson())
        && removed.canonicalJson().equals(authored.canonicalJson()),
    "All three canonical forms match");
check(palette.at("/colors/items/1").text().equals("blue"), "The original stays unchanged");
```

## 15. Resolve a cyclic definition

Cyclic content is supported through verified owning-set evidence. Package import graphs themselves must be acyclic. These are distinct rules.

```java
Language language = Language.newBuilder().provider(content).build();
LanguageDocument loop = language.define(content.export("Loop"));
check(loop.at("/self/self/self").exists(), "The imported cyclic proof supports traversal");
check(
    loop.blueId().equals(content.definition("Loop").blueId()),
    "Provider and export handle agree");
```

## 16. Edit values without changing the package

The new value retains the original exact type reference. Package exports and existing document handles are immutable.

```java
LanguageDocument original = geometry.definition("Point").specializeJson("{\"x\":2,\"y\":3}");
LanguageDocument changed = original.edit(edit -> edit.set("/x", 7));
check(
    original.at("/x").integer() == 2 && changed.at("/x").integer() == 7, "Immutable snapshots");
check(
    !original.blueId().equals(changed.blueId()), "A semantic change has a different identity");
check(original.at("").type().equals(changed.at("").type()), "The published type stays pinned");
```

## 17. Reuse verification with explicit bounds

A loader is safe to share across threads. Every load still reads and validates the input structure and checks pins. Identical member names and bytes can reuse semantic verification; paths, timestamps and claimed BlueIds are not cache keys. Concurrent misses may repeat verification. Cache bytes bound retained **expanded source sizes**, not actual JVM heap size.

```java
PackageLoader packages =
    PackageLoader.newBuilder()
        .language(TUTORIAL_LANGUAGE)
        .limits(PackageLimits.defaults())
        .cache(8, 16L << 20)
        .build();
BluePackage first = packages.open(path, GEOMETRY);
BluePackage again = packages.open(path, GEOMETRY);
check(first == again, "Identical validated bytes reuse a retained package");
expectPackage("PackageSelectionMismatch", () -> packages.open(path, DRAWING));
packages.clearCache();
check(
    first.definition("Point").specializeYaml("x: 2\ny: 3").at("/x").integer() == 2,
    "Existing handles survive cache clearing");
```

## 18. Load the actual Contracts → BEX → Language graph

`contracts.bluepkg` is an unchanged published archive from the shared packages repository. The bundled bytes are pinned and require no sibling checkout during a test or application run. This checks the real package graph, its native metadata and its declared requirements. Loading it does **not** install native execution handlers. Configure canonical `Contracts` separately for execution.

```java
PackageLoader packages = PackageLoader.newBuilder().language(CURRENT_LANGUAGE).build();
BluePackage contracts =
    packages.resource(
        PackageExample.class.getClassLoader(), "packages/contracts.bluepkg", CONTRACTS);
BluePackage bex = contracts.imported("bex");
BluePackage languagePackage = contracts.imported("language");
Language language =
    Language.newBuilder()
        .mount("Contracts", contracts)
        .mount("BEX", bex)
        .mount("Language", languagePackage)
        .build();
LanguageDocument compute = language.defineYaml("type: Contracts/Compute");
BluePackage.Protocol protocol = contracts.protocol().orElseThrow();
Set<BlueId> required = contracts.declaredRequirements();
check(
    compute.at("").type().orElseThrow().equals(contracts.export("Compute")),
    "Real export resolves");
check(
    protocol.language().equals(CURRENT_LANGUAGE) && !required.isEmpty(),
    "Protocol requirements are inspectable");
```

## Actual identity and canonical output

The first Point definition has BlueId:

```text
B8qeX9WKRBLKsoh1XeD9DCsJzYSufHXq2LcbQUPvPhSd
```

All three constructions in example 7, and both package aliases in example 8, produce:

```text
FZR5zkfBs51ffUE7DvdGPqLY6jHuTNo7M9f8G7CcQAPx
```

Use `System.out.println(point.canonicalJson())` for the exact canonical string:

```json
{"type":{"blueId":"B8qeX9WKRBLKsoh1XeD9DCsJzYSufHXq2LcbQUPvPhSd"},"x":{"type":{"blueId":"E2LM6qgzWG9ttagq2xTmiZkgYEAgkYedFCmU9v7NnVEq"},"value":2},"y":{"type":{"blueId":"E2LM6qgzWG9ttagq2xTmiZkgYEAgkYedFCmU9v7NnVEq"},"value":3}}
```

Use `toYaml()` or `toJson()` for readable Exact transport. Those exports can contain references;
a receiver still needs the package/evidence. Canonical form is not a resolved presentation or an
archive. To inspect inherited fields, use `at(pointer)` or `resolve()`; to physically expose selected
reference bodies, use `expand(...)`.

## Missing files, missing evidence, invalid packages

```java
try {
    BluePackage selected = loader.open(path, expectedPackageId);
    // Only a successful return gives a verified package.
} catch (PackageException failure) {
    System.err.println(failure.category());
    System.err.println(failure.incomplete());
}
```

| Category | Meaning |
| --- | --- |
| `PackageSelectionMismatch` | Wrong package pin, incompatible Language selection or unsupported profile |
| `InvalidPackage` | Invalid carrier, metadata, graph, file inventory or semantic package role |
| `AliasCollision` | A mount would give an existing name a different exact target |
| `ProviderBlueIdMismatch` | Supplied content does not have its claimed identity |
| `ProviderUnavailable` | Required exact evidence could not be established |
| `PackageUnavailable` | File/resource/stream I/O prevented loading |
| `OperationLimitExceeded` | The configured host bounds were reached |

Other underlying Language categories can identify malformed syntax or semantic definitions.
`incomplete()` is true for unavailable input/evidence and operation limits. It never converts
partial evidence into a successfully loaded package. Unknown exports or direct import keys throw
`NoSuchElementException`. Mount validation finishes before changing a builder: an unknown selection,
collision or mismatched Language leaves it unchanged. Empty or duplicate explicit selections fail.

`BluePackage.open(path)` without a pin is useful for local inspection. It still verifies integrity,
but does not establish that this is the publication you intended. For selected application packages,
provide a BlueId obtained from your trusted configuration.

## Gradle application setup

Until publication coordinates are decided, use an included build. This example requires Java 25.

```kotlin
// settings.gradle.kts in your application
rootProject.name = "package-consumer"
includeBuild("../blue-java-better")
```

```kotlin
// build.gradle.kts in your application
plugins { application }
repositories { mavenCentral() }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }
dependencies {
    implementation("blue.language:blue-java-better:0.1.0-SNAPSHOT")
}
application { mainClass.set("example.Main") }
```

Copy the selected `.bluepkg` into your application's `src/main/resources/packages/`, and load it
with `PackageLoader.resource(...)` as above. The library JAR intentionally does not bundle these
demo publications. Keep one verified package/context for normal document processing; opening an
archive on every event would repeat file I/O unnecessarily.

## Verification responsibilities

The implementation separates these boundaries:

1. **Transport:** paths, UTF-8/LF, supported member types, matching ZIP headers, CRCs, bounded
   compressed/expanded bytes, and duplicate/ambiguous-name rejection.
2. **Publication:** inventory digests, exact root identity, independently verified records,
   reachable witnesses, import and protocol DAGs, native associations, documentation and repository
   envelope checksum. Every export is admitted as a definition before a package is returned.
3. **Selection:** expected package/Language pins, exact direct dependencies and explicit local
   namespaces; no implicit version upgrade.
4. **Application:** use verified Language definitions and content. Declared protocol requirements
   remain data until an execution engine explicitly implements them.

The supported Language edition is `blue-language/2026-09-26-specification-metadata-1`, semantic
package model `blue-package-model/2-specification-metadata`, archive `blue-package/0.3-local`, and
repository carrier `blue-python-repository/1`. The historical words `local` and `python` in these
format identifiers do not impose an implementation language or select an alternative execution engine.

Canonical package construction/export, a restore/publish CLI, remote registry acquisition and automatic
capability installation remain future work. Selected published Agreements workflows run through canonical
Contracts and are qualified separately; see [the campaign report](CANONICAL-CAMPAIGN-2026-11-08.md). The test-only
`PackageFixtureGenerator` is deliberately not advertised as a public publishing API. The normal
build consumes its checked-in tiny publications; `preparePackageExamples` is a maintainer-only task.
See [the test map](TESTING.md) and [remaining capability gaps](CAPABILITY-GAPS.md).
