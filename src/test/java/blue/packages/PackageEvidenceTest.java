package blue.packages;

import static blue.packages.PackageTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import blue.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("packages")
class PackageEvidenceTest {
  @TempDir Path directory;

  @Test
  void anInlineChildCannotHideAForgedSeparateRecord() throws Exception {
    Map<String, byte[]> files = files("geometry");
    Map<String, Object> records = PackageFixtures.json(files, "package-records.json");
    String child = Language.create().readJson("0").blueId().value();
    assertTrue(records.containsKey(child));
    records.put(child, Map.of("mode", "exact", "text", "99"));
    PackageFixtures.putJson(files, "package-records.json", records);
    assertEquals(
        "ProviderBlueIdMismatch",
        assertThrows(PackageException.class, () -> BluePackage.open(altered(directory, files)))
            .category());
  }

  @Test
  void suppliedCoreRecordsCannotBypassVerificationThroughTheBuiltinRegistry() throws Exception {
    Map<String, byte[]> files = files("geometry");
    Map<String, Object> records = PackageFixtures.json(files, "package-records.json");
    String core = Language.create().readJson("1").at("").type().orElseThrow().value();
    records.put(core, Map.of("mode", "exact", "text", "99"));
    PackageFixtures.putJson(files, "package-records.json", records);
    assertEquals(
        "ProviderBlueIdMismatch",
        assertThrows(PackageException.class, () -> BluePackage.open(altered(directory, files)))
            .category());
  }

  @ParameterizedTest(name = "tampered claim={0}")
  @ValueSource(
      strings = {
        "witness",
        "root",
        "repository",
        "documentation",
        "audit",
        "extra-record",
        "extra-witness",
        "profile"
      })
  void selfConsistentInventoryDoesNotAuthenticateSemanticClaims(String kind) throws Exception {
    Map<String, byte[]> files = files("geometry");
    Map<String, Object> manifest = PackageFixtures.json(files, "package.json");
    switch (kind) {
      case "witness" ->
          map(map(manifest.get("semanticPackages")).get(IDS.get("geometry").value()))
              .put("name", "forged");
      case "root" ->
          files.put("package.blue.json", "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      case "repository" -> {
        Map<String, Object> repo = PackageFixtures.json(files, "repository.json");
        repo.put("identity", "sha256:forged");
        PackageFixtures.putJson(files, "repository.json", repo);
      }
      case "documentation" ->
          files.put("README.md", "different\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      case "audit" -> map(manifest.get("validation")).put("passed", 99);
      case "extra-record" -> {
        Map<String, Object> records = PackageFixtures.json(files, "package-records.json");
        LanguageDocument extra = Language.create().readYaml("unrelated: 42");
        records.put(extra.blueId().value(), Map.of("mode", "exact", "text", extra.toJson()));
        PackageFixtures.putJson(files, "package-records.json", records);
      }
      case "extra-witness" ->
          map(manifest.get("semanticPackages"))
              .put(
                  IDS.get("drawing").value(),
                  map(manifest.get("semanticPackages")).get(IDS.get("geometry").value()));
      case "profile" -> manifest.put("languageProfile", "invented/2");
      default -> throw new AssertionError(kind);
    }
    PackageFixtures.putJson(files, "package.json", manifest);
    assertThrows(PackageException.class, () -> BluePackage.open(altered(directory, files)));
  }

  @Test
  void missingEvidenceIsIncompleteAndNeverBecomesASuccessfulPackage() throws Exception {
    Map<String, byte[]> files = files("geometry");
    PackageFixtures.putJson(files, "package-records.json", Map.of());
    PackageException failure =
        assertThrows(PackageException.class, () -> BluePackage.open(altered(directory, files)));
    assertTrue(failure.incomplete());
  }

  @ParameterizedTest(name = "invalid version={0}")
  @ValueSource(strings = {"01.0.0", "1.0.0-01", "1.0.0-a..b", "1.0.0+build"})
  void validContentIdentityDoesNotExcuseAnInvalidPackageVersion(String version) throws Exception {
    PackageFixtures.Publication language = PackageFixtures.bootstrap();
    PackageFixtures.Publication invalid =
        PackageFixtures.library(
            "bad-version",
            version,
            language,
            Map.of(),
            Map.of("Point", Language.create().defineYaml("x: {type: Integer}")));
    PackageException failure =
        assertThrows(PackageException.class, () -> invalid.load(directory.resolve("bad.bluepkg")));
    assertEquals("InvalidPackage", failure.category());
    assertTrue(
        failure.getMessage().toLowerCase(java.util.Locale.ROOT).contains("version")
            || failure.getMessage().contains("prerelease"));
  }
}
