package blue.examples;

import blue.BlueId;
import blue.BluePackage;
import blue.Language;
import blue.LanguageContent;
import blue.LanguageDocument;
import blue.LanguageException;
import blue.Node;
import blue.PackageException;
import blue.PackageLimits;
import blue.PackageLoader;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipInputStream;

/** Executable canonical package guide. Bundled publications need no sibling checkout or network. */
public final class PackageExample {
  public static final BlueId GEOMETRY = new BlueId("9Ru5h2T2c6N4daUET2LLHArEvQT6TR3QFPwTfteaq57v");
  public static final BlueId DRAWING = new BlueId("DmgQQFoRoikCw8SpyzgHxef3CZsyCkxkVbFDWa3EVbuZ");
  public static final BlueId GEOMETRY_V2 =
      new BlueId("8g8Xq4RS4pQmyLReVMPHFpgfnEoT6GBfyW153hUrALtN");
  public static final BlueId CONTENT = new BlueId("7QE5obau2Hu1pTrKtwCd1TBsgUUn5hGRdLbck4WbQmpg");
  public static final BlueId TUTORIAL_LANGUAGE =
      new BlueId("9gmTH7AViM69tjyPBsczyFq9G6XZZCYPnDrkgCQCNeWZ");
  public static final BlueId CONTRACTS = new BlueId("4Jmza3Z7eEDah9qtyYq5i2AqX5tKfUnLNKYAoAPoWzmF");
  public static final BlueId CURRENT_LANGUAGE =
      new BlueId("8XGYNufx24r7Dc5bGoeobcGNCn9roLzSfZCQyHBTaTx4");
  private static final PackageLoader loader = PackageLoader.create();

  private PackageExample() {}

  public static void main(String[] args) throws Exception {
    Path output = args.length == 0 ? Path.of("build/reports/packages") : Path.of(args[0]);
    Files.createDirectories(output);
    StringBuilder transcript = new StringBuilder();
    for (Lesson lesson : lessons()) {
      lesson.run(output);
      transcript.append(lesson.title()).append(": passed\n");
    }
    Files.writeString(output.resolve("checks.txt"), transcript);
    System.out.print(transcript);
    System.out.println("States, identities and canonical forms: " + output.toAbsolutePath());
  }

  static List<Lesson> lessons() {
    return List.of(
        new Lesson("01. Pinned local archive", PackageExample::localArchive),
        new Lesson("02. Classpath resource", PackageExample::classpathResource),
        new Lesson("03. Caller-owned stream", PackageExample::callerStream),
        new Lesson("04. Prepared directory", PackageExample::preparedDirectory),
        new Lesson("05. Package metadata and documentation", PackageExample::metadata),
        new Lesson("06. Mount and read YAML", PackageExample::mountAndRead),
        new Lesson(
            "07. YAML, JSON and direct specialization share identity",
            PackageExample::sameIdentity),
        new Lesson("08. Two packages, one definition", PackageExample::twoPackages),
        new Lesson("09. Inheritance across package imports", PackageExample::inheritance),
        new Lesson("10. Local extension of a published type", PackageExample::localExtension),
        new Lesson("11. Explicit import namespaces", PackageExample::explicitImports),
        new Lesson("12. Coexisting pinned versions", PackageExample::versions),
        new Lesson("13. Arbitrary collapsed content", PackageExample::collapsedContent),
        new Lesson("14. Three list edits, one canonical form", PackageExample::listEdits),
        new Lesson("15. Cyclic evidence from a package", PackageExample::cyclicEvidence),
        new Lesson("16. Immutable documents after loading", PackageExample::immutableEdits),
        new Lesson("17. Bounded caching and pin failures", PackageExample::boundedLoader),
        new Lesson(
            "18. Real Contracts, BEX and Language declarations", PackageExample::realContracts));
  }

  @FunctionalInterface
  interface Action {
    void run(Path output) throws Exception;
  }

  record Lesson(String title, Action action) {
    void run(Path output) throws Exception {
      action.run(output);
    }

    @Override
    public String toString() {
      return title;
    }
  }

  private static void localArchive(Path output) throws Exception {
    Path path = copyResource(output, "geometry");
    // region localArchive
    BluePackage geometry = BluePackage.open(path, GEOMETRY);
    BlueId packageId = geometry.blueId();
    BlueId pointId = geometry.export("Point");
    LanguageDocument point = geometry.definition("Point");
    check(packageId.equals(GEOMETRY), "The trusted package pin matches");
    check(pointId.equals(point.blueId()), "Export selection carries verified evidence");
    // endregion localArchive
    Files.writeString(output.resolve("point.definition.yaml"), point.expand("").toYaml());
    Files.writeString(
        output.resolve("identities.txt"),
        "Geometry package: " + packageId + "\nPoint definition: " + pointId + "\n");
  }

  private static void classpathResource(Path output) {
    // region classpathResource
    PackageLoader packages = PackageLoader.newBuilder().language(TUTORIAL_LANGUAGE).build();
    BluePackage geometry =
        packages.resource(
            PackageExample.class.getClassLoader(), "packages/geometry.bluepkg", GEOMETRY);
    check(geometry.languageId().equals(TUTORIAL_LANGUAGE), "Language selection is pinned too");
    // endregion classpathResource
  }

  private static void callerStream(Path output) throws Exception {
    Path path = copyResource(output, "geometry");
    // region callerStream
    try (InputStream input = Files.newInputStream(path)) {
      BluePackage geometry = loader.read(input, GEOMETRY);
      check(geometry.export("Point") != null, "The caller owns the stream lifetime");
    }
    // endregion callerStream
  }

  private static void preparedDirectory(Path output) throws Exception {
    Path directory = unpackFixture(output.resolve("prepared"), "geometry");
    // region preparedDirectory
    BluePackage geometry = loader.open(directory, GEOMETRY);
    check(geometry.blueId().equals(GEOMETRY), "Folder and archive represent the same package");
    // endregion preparedDirectory
  }

  private static void metadata(Path output) throws Exception {
    BluePackage geometry = load("geometry", GEOMETRY);
    // region metadata
    String publisherName = geometry.name();
    String release = geometry.version();
    Map<String, BlueId> exports = geometry.exports();
    Map<String, BlueId> imports = geometry.imports();
    String readme = geometry.documentation("README.md");
    check(publisherName.equals("geometry") && release.equals("1.0.0"), "Release labels");
    check(exports.containsKey("Point"), "Export names belong to the package");
    check(imports.get("language").equals(TUTORIAL_LANGUAGE), "Imports are exact identities");
    check(geometry.definition("Point").at("").name().isEmpty(), "No name metadata was invented");
    // endregion metadata
    Files.writeString(output.resolve("geometry-readme.md"), readme);
  }

  private static void mountAndRead(Path output) throws Exception {
    BluePackage geometry = load("geometry", GEOMETRY);
    // region mountAndRead
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
    // endregion mountAndRead
    Files.writeString(output.resolve("point.yaml"), yaml);
    Files.writeString(output.resolve("point.json"), json);
    Files.writeString(output.resolve("point.canonical.json"), canonical);
    Files.writeString(output.resolve("point.blueId.txt"), identity.value());
  }

  private static void sameIdentity(Path output) {
    BluePackage geometry = load("geometry", GEOMETRY);
    Language language = Language.newBuilder().mount("Geometry", geometry).build();
    // region sameIdentity
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
    // endregion sameIdentity
  }

  private static void twoPackages(Path output) {
    BluePackage geometry = load("geometry", GEOMETRY);
    BluePackage drawing = load("drawing", DRAWING);
    // region twoPackages
    Language language =
        Language.newBuilder().mount("Geometry", geometry).mount("Art", drawing).build();
    LanguageDocument first = language.readYaml("type: Geometry/Point\nx: 2\ny: 3");
    LanguageDocument second = language.readYaml("type: Art/Coordinate\nx: 2\ny: 3");
    check(!geometry.blueId().equals(drawing.blueId()), "Different packages");
    check(
        geometry.export("Point").equals(drawing.export("Coordinate")),
        "The same exported definition");
    check(first.blueId().equals(second.blueId()), "The chosen namespace is not document identity");
    // endregion twoPackages
  }

  private static void inheritance(Path output) throws Exception {
    BluePackage drawing = load("drawing", DRAWING);
    // region inheritance
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
    // endregion inheritance
    Files.writeString(output.resolve("colored-point.yaml"), colored.toYaml());
  }

  private static void localExtension(Path output) {
    BluePackage geometry = load("geometry", GEOMETRY);
    // region localExtension
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
    // endregion localExtension
  }

  private static void explicitImports(Path output) {
    BluePackage drawing = load("drawing", DRAWING);
    // region explicitImports
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
    // endregion explicitImports
  }

  private static void versions(Path output) {
    BluePackage oldGeometry = load("geometry", GEOMETRY);
    BluePackage newGeometry = load("geometry-v2", GEOMETRY_V2);
    BluePackage drawing = load("drawing", DRAWING);
    // region versions
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
    // endregion versions
  }

  private static void collapsedContent(Path output) throws Exception {
    BluePackage content = load("content", CONTENT);
    // region collapsedContent
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
    // endregion collapsedContent
    Files.writeString(output.resolve("palette.collapsed.yaml"), collapsed.toYaml());
    Files.writeString(output.resolve("palette.expanded.yaml"), expanded.toYaml());
  }

  private static void listEdits(Path output) throws Exception {
    BluePackage content = load("content", CONTENT);
    Language language = Language.newBuilder().provider(content).build();
    // region listEdits
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
    // endregion listEdits
    Files.writeString(output.resolve("palette.edited.canonical.json"), removed.canonicalJson());
  }

  private static void cyclicEvidence(Path output) {
    BluePackage content = load("content", CONTENT);
    // region cyclicEvidence
    Language language = Language.newBuilder().provider(content).build();
    LanguageDocument loop = language.define(content.export("Loop"));
    check(loop.at("/self/self/self").exists(), "The imported cyclic proof supports traversal");
    check(
        loop.blueId().equals(content.definition("Loop").blueId()),
        "Provider and export handle agree");
    // endregion cyclicEvidence
  }

  private static void immutableEdits(Path output) {
    BluePackage geometry = load("geometry", GEOMETRY);
    // region immutableEdits
    LanguageDocument original = geometry.definition("Point").specializeJson("{\"x\":2,\"y\":3}");
    LanguageDocument changed = original.edit(edit -> edit.set("/x", 7));
    check(
        original.at("/x").integer() == 2 && changed.at("/x").integer() == 7, "Immutable snapshots");
    check(
        !original.blueId().equals(changed.blueId()), "A semantic change has a different identity");
    check(original.at("").type().equals(changed.at("").type()), "The published type stays pinned");
    // endregion immutableEdits
  }

  private static void boundedLoader(Path output) throws Exception {
    Path path = copyResource(output, "geometry");
    // region boundedLoader
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
    // endregion boundedLoader
  }

  private static void realContracts(Path output) throws Exception {
    // region realContracts
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
    // endregion realContracts
    Files.writeString(
        output.resolve("contracts-requirements.txt"),
        "Package: "
            + contracts.blueId()
            + "\nLanguage: "
            + languagePackage.blueId()
            + "\nBEX: "
            + bex.blueId()
            + "\nProtocol: "
            + protocol.blueId()
            + "\nRules: "
            + protocol.rules()
            + "\nRequired: "
            + required
            + "\n");
  }

  private static BluePackage load(String name, BlueId expected) {
    return loader.resource(
        PackageExample.class.getClassLoader(), "packages/" + name + ".bluepkg", expected);
  }

  private static Path copyResource(Path directory, String name) throws Exception {
    Files.createDirectories(directory);
    Path path = directory.resolve(name + ".bluepkg");
    try (InputStream input = resource(name)) {
      Files.copy(input, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
    return path;
  }

  private static InputStream resource(String name) {
    return Objects.requireNonNull(
        PackageExample.class.getResourceAsStream("/packages/" + name + ".bluepkg"));
  }

  private static Path unpackFixture(Path directory, String name) throws Exception {
    // Only the small, pinned, bundled example is unpacked here; the production reader never
    // extracts ZIPs.
    Files.createDirectories(directory);
    try (ZipInputStream input = new ZipInputStream(resource(name))) {
      for (java.util.zip.ZipEntry entry = input.getNextEntry();
          entry != null;
          entry = input.getNextEntry()) {
        Path path = directory.resolve(entry.getName()).normalize();
        check(path.startsWith(directory), "Fixture path stays in output directory");
        Files.createDirectories(path.getParent());
        Files.copy(input, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
    }
    return directory;
  }

  private static void check(boolean valid, String message) {
    if (!valid) throw new AssertionError(message);
  }

  private static void expectLanguage(String category, Runnable action) {
    try {
      action.run();
    } catch (LanguageException failure) {
      check(
          failure.category().equals(category),
          "Unexpected Language category: " + failure.category());
      return;
    }
    throw new AssertionError("Expected Language failure: " + category);
  }

  private static void expectPackage(String category, Runnable action) {
    try {
      action.run();
    } catch (PackageException failure) {
      check(
          failure.category().equals(category),
          "Unexpected package category: " + failure.category());
      return;
    }
    throw new AssertionError("Expected package failure: " + category);
  }
}
