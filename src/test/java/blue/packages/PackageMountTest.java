package blue.packages;

import static blue.packages.PackageTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import blue.*;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("packages")
class PackageMountTest {
  @Test
  void aliasesAreScopedAndTheSameDefinitionCanBelongToDifferentPackages() {
    BluePackage geometry = load("geometry"), drawing = load("drawing");
    assertEquals(geometry.export("Point"), drawing.export("Coordinate"));
    assertNotEquals(geometry.blueId(), drawing.blueId());
    Language language =
        Language.newBuilder().mount("Geometry", geometry).mount("Drawing", drawing).build();
    LanguageDocument first = language.readYaml("type: Geometry/Point\nx: 2\ny: 3");
    LanguageDocument second = language.readYaml("type: Drawing/Coordinate\nx: 2\ny: 3");
    assertEquals(first.blueId(), second.blueId());
    assertEquals(first.canonicalJson(), second.canonicalJson());
    assertEquals(
        first.blueId(), geometry.definition("Point").specializeYaml("x: 2\ny: 3").blueId());
    assertTrue(geometry.definition("Point").at("").name().isEmpty());
  }

  @Test
  void importsSupplyContentButOnlyExplicitMountsSupplyNames() {
    BluePackage drawing = load("drawing");
    Language language =
        Language.newBuilder().mount("Art", drawing, List.of("Colored Point")).build();
    LanguageDocument colored =
        language.readYaml("type: Art/Colored Point\nx: 2\ny: 3\ncolor: blue");
    assertEquals(2, colored.at("/x").integer());
    for (String alias : List.of("geometry/Point", "Published/Point", "Art/Coordinate")) {
      assertEquals(
          "UnknownTypeAlias",
          assertThrows(LanguageException.class, () -> language.defineYaml("type: " + alias))
              .category());
    }
    BluePackage dependency = drawing.imported("geometry");
    assertEquals(IDS.get("geometry"), dependency.blueId());
    Language explicit = Language.newBuilder().mount("Pinned", dependency, List.of("Point")).build();
    assertEquals(2, explicit.readYaml("type: Pinned/Point\nx: 2\ny: 3").at("/x").integer());
    assertThrows(NoSuchElementException.class, () -> drawing.imported("unknown"));
  }

  @Test
  void versionsCoexistWithoutRetargetingInheritedDefinitions() {
    BluePackage drawing = load("drawing"), newer = load("geometry-v2");
    Language language = Language.newBuilder().mount("Art", drawing).mount("New", newer).build();
    LanguageDocument colored =
        language.readYaml("type: Art/Colored Point\nx: 2\ny: 3\ncolor: blue");
    assertFalse(colored.at("/z").exists());
    assertEquals(4, language.readYaml("type: New/Point\nx: 2\ny: 3\nz: 4").at("/z").integer());
    assertNotEquals(drawing.export("Coordinate"), newer.export("Point"));
  }

  @Test
  void invalidSelectionsAndConflictsDoNotPartiallyMutateTheBuilder() {
    BluePackage geometry = load("geometry");
    Language.Builder builder = Language.newBuilder().alias("Bad/Point", geometry.export("Origin"));
    PackageException collision =
        assertThrows(PackageException.class, () -> builder.mount("Bad", geometry));
    assertEquals("AliasCollision", collision.category());
    assertEquals(
        "UnknownTypeAlias",
        assertThrows(LanguageException.class, () -> builder.build().defineYaml("type: Bad/Origin"))
            .category());
    for (List<String> names :
        List.of(List.<String>of(), List.of("Point", "Point"), List.of("Origin", "Absent"))) {
      assertThrows(PackageException.class, () -> builder.mount("Selected", geometry, names));
    }
    assertThrows(PackageException.class, () -> builder.mount("../bad", geometry));
    assertThrows(
        LanguageException.class, () -> builder.build().defineYaml("type: Selected/Origin"));
    Language before = builder.build();
    builder.mount("@repo.blue/shapes", geometry).mount("@repo.blue/shapes", geometry);
    assertThrows(LanguageException.class, () -> before.defineYaml("type: @repo.blue/shapes/Point"));
    assertEquals(
        2,
        builder.build().readYaml("type: '@repo.blue/shapes/Point'\nx: 2\ny: 3").at("/x").integer());
  }

  @Test
  void plainProviderAccessNeedsNoAliasAndPreservesCyclicEvidence() {
    BluePackage content = load("content");
    Language language = Language.newBuilder().provider(content).build();
    LanguageDocument palette = language.read(content.export("Palette"));
    assertEquals("blue", palette.at("/colors/items/1").text());
    assertTrue(language.define(content.export("Loop")).at("/self/self").exists());
    assertEquals(content.export("Palette"), palette.collapse("/colors").blueId());
    assertThrows(LanguageException.class, () -> language.defineYaml("type: Content/Palette"));
    assertThrows(UnsupportedOperationException.class, () -> content.exports().clear());
    assertThrows(UnsupportedOperationException.class, () -> content.records().clear());
  }
}
