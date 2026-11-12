package blue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Small canonical publications for isolated role/transport tests, not replacement golden vectors.
 */
public final class PackageFixtures {
  private PackageFixtures() {}

  public record Publication(
      BlueId id,
      Map<String, Object> witness,
      Map<String, Object> packages,
      Map<String, Object> specifications,
      LanguageDocument document,
      Map<String, byte[]> files) {
    public Path archive(Path path) throws IOException {
      return writeArchive(path, files);
    }

    public BluePackage load(Path path) throws IOException {
      return BluePackage.open(archive(path), id);
    }
  }

  public static Publication bootstrap() throws IOException {
    Language language = Language.create();
    LanguageDocument packageType = language.defineJson(standard("Package"));
    LanguageDocument protocolType = language.defineJson(standard("ProtocolSpecification"));
    return publication(
        "tutorial-language",
        "1.0.0",
        null,
        Map.of(),
        Map.of("Package", packageType, "Protocol Specification", protocolType),
        Map.of("README.md", "# Tutorial Language\n"),
        null,
        Map.of());
  }

  public static Publication library(
      String name,
      String version,
      Publication language,
      Map<String, Publication> imports,
      Map<String, LanguageDocument> exports)
      throws IOException {
    Map<String, Publication> selected = new TreeMap<>(imports);
    selected.put("language", language);
    return publication(
        name,
        version,
        language,
        selected,
        exports,
        Map.of("README.md", "# " + name + "\n"),
        null,
        Map.of());
  }

  public static Publication publication(
      String name,
      String version,
      Publication language,
      Map<String, Publication> imports,
      Map<String, LanguageDocument> exports,
      Map<String, String> assets,
      LanguageDocument protocol,
      Map<String, Object> extraSpecifications)
      throws IOException {
    Language.Builder builder = Language.newBuilder();
    LanguageDocument packageType = Language.create().defineJson(standard("Package"));
    builder.content(packageType);
    if (language != null) builder.content(language.document());
    Map<String, Object> packages = new TreeMap<>();
    Map<String, Object> specifications = new TreeMap<>(extraSpecifications);
    Map<String, Object> importRefs = new TreeMap<>(), importRows = new TreeMap<>();
    imports.forEach(
        (prefix, pkg) -> {
          builder.content(pkg.document());
          packages.putAll(pkg.packages());
          specifications.putAll(pkg.specifications());
          importRefs.put(prefix, ref(pkg.id()));
          importRows.put(
              prefix,
              Map.of(
                  "name",
                  pkg.witness().get("name"),
                  "version",
                  pkg.witness().get("version"),
                  "packageBlueId",
                  pkg.id().value()));
        });
    Map<String, Object> exportRefs = new TreeMap<>(),
        aliases = new TreeMap<>(),
        repositoryRecords = new TreeMap<>();
    List<Object> rows = new ArrayList<>();
    exports.forEach(
        (export, doc) -> {
          builder.content(doc);
          exportRefs.put(export, ref(doc.blueId()));
          aliases.put("Published/" + export, doc.blueId().value());
          doc.records()
              .forEach((id, record) -> repositoryRecords.put(id.value(), record.response()));
          Map<String, Object> row = new TreeMap<>();
          row.put("name", export);
          row.put("alias", "Published/" + export);
          row.put("blueId", doc.blueId().value());
          row.put("specification", null);
          row.put(
              "specificationMetadata",
              protocol == null ? null : protocol.node().at("/rules/blueId").text());
          rows.add(row);
        });
    Map<String, Object> documentation = new TreeMap<>(), bindings = new TreeMap<>();
    Map<String, byte[]> files = new TreeMap<>();
    for (var entry : assets.entrySet()) {
      LanguageDocument text = Language.create().readJson(LanguageCodec.wire(entry.getValue()));
      builder.content(text);
      byte[] bytes = entry.getValue().getBytes(StandardCharsets.UTF_8);
      documentation.put(
          entry.getKey(),
          Map.of(
              "content",
              ref(text.blueId()),
              "sha256",
              "sha256:" + PackageIntegrity.sha(bytes),
              "size",
              bytes.length));
      files.put(entry.getKey(), bytes);
    }
    if (protocol != null) {
      builder.content(protocol);
      exports
          .values()
          .forEach(
              doc ->
                  bindings.put(
                      doc.blueId().value(),
                      Map.of(
                          "specification",
                          Map.of("blueId", protocol.node().at("/rules/blueId").text()),
                          "protocol",
                          ref(protocol.blueId()),
                          "binding",
                          "native-metadata")));
    }
    Map<String, Object> witness = new TreeMap<>();
    witness.put("type", ref(packageType.blueId()));
    witness.put("format", BluePackage.SEMANTIC_FORMAT);
    witness.put("name", name);
    witness.put("version", version);
    witness.put("identityProfile", Language.EDITION);
    witness.put("bootstrapLanguage", language == null);
    witness.put("language", language == null ? null : ref(language.id()));
    witness.put("exports", exportRefs);
    witness.put("imports", importRefs);
    witness.put("protocol", protocol == null ? null : ref(protocol.blueId()));
    witness.put("specBindings", bindings);
    witness.put(
        "declaredRequirements", protocol == null ? List.of() : List.of(ref(protocol.blueId())));
    witness.put("documentation", documentation);
    LanguageDocument root = builder.build().defineJson(LanguageCodec.wire(witness));
    packages.put(root.blueId().value(), witness);
    Map<String, Object> records = new TreeMap<>();
    root.records().forEach((id, record) -> records.put(id.value(), record.response()));
    Map<String, Object> repository = new TreeMap<>();
    repository.put("format", "blue-python-repository/1");
    repository.put("name", name);
    repository.put("aliases", aliases);
    repository.put("records", repositoryRecords);
    repository.put("metadata", Map.of());
    repository.put(
        "identity",
        "sha256:"
            + PackageIntegrity.sha(
                PackageIntegrity.repositoryCanonical(repository).getBytes(StandardCharsets.UTF_8)));
    putJson(files, "package.blue.json", LanguageCodec.json(root.toJson()));
    putJson(files, "package-records.json", records);
    putJson(files, "repository.json", repository);
    Map<String, Object> manifest = new TreeMap<>();
    manifest.put("format", BluePackage.FORMAT);
    manifest.put("languageProfile", Language.EDITION);
    manifest.put("name", name);
    manifest.put("version", version);
    manifest.put("packageBlueId", root.blueId().value());
    manifest.put("language", language == null ? null : language.id().value());
    manifest.put("protocol", protocol == null ? null : protocol.blueId().value());
    manifest.put("specification", null);
    manifest.put("importedDefinitions", List.of());
    manifest.put("imports", importRows);
    manifest.put("exports", rows);
    manifest.put("semanticPackages", packages);
    manifest.put("semanticSpecifications", specifications);
    manifest.put(
        "repository",
        Map.of(
            "path",
            "repository.json",
            "identity",
            repository.get("identity"),
            "format",
            "blue-python-repository/1"));
    manifest.put(
        "validation",
        Map.of(
            "kind", "Repository.audit", "definitions", exports.size(), "passed", exports.size()));
    manifest.put("toolchain", Map.of("implementation", "Java test fixture"));
    manifest.put("files", inventory(files));
    putJson(files, "package.json", manifest);
    return new Publication(root.blueId(), witness, packages, specifications, root, files);
  }

  public static String standard(String name) throws IOException {
    try (var stream =
        Objects.requireNonNull(
            PackageFixtures.class.getResourceAsStream("/blue/standard/" + name + ".json"))) {
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  public static Map<String, String> ref(BlueId id) {
    return Map.of("blueId", id.value());
  }

  public static void putJson(Map<String, byte[]> files, String name, Object value) {
    files.put(name, (LanguageCodec.wire(value) + "\n").getBytes(StandardCharsets.UTF_8));
  }

  public static Map<String, Object> json(Map<String, byte[]> files, String name) {
    return LanguageCore.map(
        LanguageCodec.json(new String(files.get(name), StandardCharsets.UTF_8)));
  }

  public static void refreshInventory(Map<String, byte[]> files) {
    Map<String, Object> manifest = json(files, "package.json");
    manifest.put("files", inventory(files));
    putJson(files, "package.json", manifest);
  }

  private static Map<String, Object> inventory(Map<String, byte[]> files) {
    Map<String, Object> inventory = new TreeMap<>();
    files.forEach(
        (path, bytes) -> {
          if (!path.equals("package.json")) {
            inventory.put(
                path,
                Map.of("size", bytes.length, "sha256", "sha256:" + PackageIntegrity.sha(bytes)));
          }
        });
    return inventory;
  }

  public static Path writeArchive(Path path, Map<String, byte[]> files) throws IOException {
    Files.createDirectories(path.toAbsolutePath().getParent());
    try (var output = new ZipOutputStream(Files.newOutputStream(path))) {
      for (var entry : new TreeMap<>(files).entrySet()) {
        ZipEntry member = new ZipEntry(entry.getKey());
        member.setTime(0);
        output.putNextEntry(member);
        output.write(entry.getValue());
        output.closeEntry();
      }
    }
    return path;
  }
}
