package blue.packages;

import static org.junit.jupiter.api.Assertions.*;

import blue.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("packages")
class PackageGraphTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(strings = {"{", "{\"x\":1,\"x\":2}", "{\"x\":1}"})
  void identifiedJsonAssetsMustAlsoBeValidJson(String json) throws Exception {
    PackageFixtures.Publication bootstrap = PackageFixtures.bootstrap();
    PackageFixtures.Publication publication =
        PackageFixtures.publication(
            "json-example",
            "1.0.0",
            bootstrap,
            Map.of("language", bootstrap),
            Map.of(),
            Map.of("examples/document.json", json),
            null,
            Map.of());
    if (json.equals("{\"x\":1}")) {
      assertEquals(
          json,
          publication
              .load(directory.resolve("valid.bluepkg"))
              .documentation("examples/document.json"));
    } else {
      assertThrows(
          PackageException.class, () -> publication.load(directory.resolve("invalid.bluepkg")));
    }
  }

  @Test
  void transitiveLanguageEvidenceDoesNotReplaceAnExplicitImport() throws Exception {
    PackageFixtures.Publication bootstrap = PackageFixtures.bootstrap();
    PackageFixtures.Publication dependency =
        PackageFixtures.library("dependency", "1.0.0", bootstrap, Map.of(), Map.of());
    PackageFixtures.Publication invalid =
        PackageFixtures.publication(
            "consumer",
            "1.0.0",
            bootstrap,
            Map.of("dependency", dependency),
            Map.of(),
            Map.of(),
            null,
            Map.of());
    PackageException failure =
        assertThrows(
            PackageException.class,
            () -> invalid.load(directory.resolve("missing-import.bluepkg")));
    assertTrue(failure.getMessage().contains("explicit import"));
  }

  @Test
  void everyBranchMustSelectTheSameExactLanguageRelease() throws Exception {
    PackageFixtures.Publication bootstrap = PackageFixtures.bootstrap();
    PackageFixtures.Publication other =
        PackageFixtures.publication(
            "another-language", "1.0.0", null, Map.of(), Map.of(), Map.of(), null, Map.of());
    PackageFixtures.Publication dependency =
        PackageFixtures.library("foreign", "1.0.0", other, Map.of(), Map.of());
    PackageFixtures.Publication invalid =
        PackageFixtures.library(
            "consumer", "1.0.0", bootstrap, Map.of("foreign", dependency), Map.of());
    PackageException failure =
        assertThrows(
            PackageException.class, () -> invalid.load(directory.resolve("mixed.bluepkg")));
    assertTrue(failure.getMessage().contains("cross-Language"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void nativeExportsMustCarryTheGoverningRulesIdentity(boolean correct) throws Exception {
    PackageFixtures.Publication bootstrap = PackageFixtures.bootstrap();
    LanguageDocument protocolType =
        Language.create().defineJson(PackageFixtures.standard("ProtocolSpecification"));
    LanguageDocument rules = Language.create().readYaml("Deterministic tutorial rules");
    Language language =
        Language.newBuilder()
            .content(bootstrap.document())
            .content(protocolType)
            .content(rules)
            .build();
    LanguageDocument protocol =
        language.defineYaml(
            """
            type: {blueId: '%s'}
            name: Tutorial Protocol
            language: {blueId: '%s'}
            rules: {blueId: '%s'}
            requires: {}
            status: experimental
            """
                .formatted(protocolType.blueId(), bootstrap.id(), rules.blueId()));
    Map<String, Object> witness =
        Map.of(
            "type",
            PackageFixtures.ref(protocolType.blueId()),
            "name",
            "Tutorial Protocol",
            "language",
            PackageFixtures.ref(bootstrap.id()),
            "rules",
            PackageFixtures.ref(rules.blueId()),
            "requires",
            Map.of(),
            "status",
            "experimental");
    LanguageDocument nativeType =
        language.defineYaml(
            correct
                ? "specification: {blueId: '" + rules.blueId() + "'}\nargument: {type: Integer}"
                : "argument: {type: Integer}");
    PackageFixtures.Publication publication =
        PackageFixtures.publication(
            "native-example",
            "1.0.0",
            bootstrap,
            Map.of("language", bootstrap),
            Map.of("Instruction", nativeType),
            Map.of(),
            protocol,
            Map.of(protocol.blueId().value(), witness));
    if (correct) {
      BluePackage loaded = publication.load(directory.resolve("native.bluepkg"));
      assertEquals(rules.blueId(), loaded.protocol().orElseThrow().rules());
      assertEquals(Set.of(protocol.blueId()), loaded.declaredRequirements());
      assertEquals("experimental", loaded.protocol(protocol.blueId()).status());
    } else {
      PackageException failure =
          assertThrows(
              PackageException.class, () -> publication.load(directory.resolve("invalid.bluepkg")));
      assertTrue(failure.getMessage().contains("native type specification"));
    }
  }
}
