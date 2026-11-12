package blue.language;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import blue.BlueId;
import blue.Language;
import blue.LanguageDocument;
import blue.LanguageException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("language")
class TypeBindingsTest {
  private final Language language = Language.create();

  @Test
  void multipleQualifiedExportNamesIdentifyTheSameUnmodifiedContent() {
    LanguageDocument point =
        language.defineYaml(
            """
            name: A display label
            x: {type: Integer}
            y: {type: Integer}
            """);
    BlueId originalId = point.blueId();
    String originalJson = point.toJson();
    Language consumer =
        Language.newBuilder()
            .type("Geometry/Point", point)
            .type("Drawing/Coordinate", point)
            .build();
    LanguageDocument first = consumer.readYaml("type: Geometry/Point\nx: 2\ny: 3");
    LanguageDocument second = consumer.readYaml("type: Drawing/Coordinate\nx: 2\ny: 3");
    LanguageDocument direct = point.specializeYaml("x: 2\ny: 3");

    assertEquals(first.blueId(), second.blueId());
    assertEquals(first.blueId(), direct.blueId());
    assertEquals(first.canonicalJson(), second.canonicalJson());
    assertEquals(originalId, first.at("").type().orElseThrow());
    assertEquals(originalId, second.at("").type().orElseThrow());
    assertEquals(originalId.value(), first.node().at("/type/blueId").text());
    assertEquals(originalId, point.blueId());
    assertEquals(originalJson, point.toJson());
    assertEquals("A display label", consumer.define(originalId).at("").name().orElseThrow());
  }

  @Test
  void nameIsNotAnAliasOrPackageRegistration() {
    LanguageDocument point = language.defineYaml("name: Geometry/Point\nx: {type: Integer}");
    Language contentOnly = Language.newBuilder().content(point).build();
    LanguageException missingAlias =
        assertThrows(
            LanguageException.class, () -> contentOnly.readYaml("type: Geometry/Point\nx: 2"));
    assertEquals("UnknownTypeAlias", missingAlias.category());

    LanguageDocument relabeled = point.edit(e -> e.set("/name", "Point"));
    assertNotEquals(point.blueId(), relabeled.blueId());
    Language consumer = Language.newBuilder().type("Drawing/Coordinate", point).build();
    assertEquals(
        point.blueId(),
        consumer.readYaml("type: Drawing/Coordinate\nx: 2").at("").type().orElseThrow());
    assertEquals("Geometry/Point", point.at("").name().orElseThrow());
  }
}
