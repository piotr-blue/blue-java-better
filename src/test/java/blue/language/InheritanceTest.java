package blue.language;

import static blue.Language.Goal.DEFINITION;
import static blue.Language.Goal.VALUE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.Language;
import blue.LanguageDocument;
import blue.LanguageException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("language")
class InheritanceTest {
  private final Language language = Language.create();

  @Test
  void selectedContentRetainsInheritedDefinitionsRefinementsAndCyclicIdentity() {
    var parent =
        language.defineYaml(
            "optional: {type: Text}\n"
                + "amount: {type: Integer, schema: {minimum: 1}}\n"
                + "rows: [{type: Text}]");
    var document = parent.extendYaml("amount: {schema: {maximum: 10}}");
    assertEquals(
        language.defineYaml("type: Text").blueId(),
        document.at("/optional").content().orElseThrow().blueId());
    var selected = document.at("/amount").content().orElseThrow();
    assertEquals(document.at("/amount").constraints(), selected.resolve().constraints());
    assertFalse(selected.resolve().isConcrete());
    assertEquals(
        language.defineYaml("type: Text").blueId(),
        document.at("/rows/items/0").content().orElseThrow().blueId());
    assertTrue(document.at("/missing").content().isEmpty());
    assertThrows(LanguageException.class, () -> document.at("/amount/value").content());
    var cycle =
        language
            .finalizeSet(
                java.util.List.of(Language.Input.exactJson("{\"next\":{\"blueId\":\"this#0\"}}")),
                Language.Goal.VALUE)
            .members()
            .getFirst();
    assertEquals(cycle.blueId(), cycle.at("/next").content().orElseThrow().blueId());
  }

  @Test
  void inheritancePreservesExactOriginAndRefinesContextualTypes() {
    var type = language.readYaml("name: Person\nage: {type: Integer}\ncountry: PL", DEFINITION);
    var app = Language.newBuilder().type("Example/Person", type).build();
    var person = app.readYaml("type: Example/Person\nage: 42");
    assertEquals("PL", person.resolve().at("/country").text());
    assertTrue(language.lookupExact(person, "/country").isMissing());
    assertEquals(42, person.resolve().at("/age").integer());
    assertTrue(app.isSubtype(person, type));
    assertThrows(LanguageException.class, () -> app.readYaml("type: Example/Person\nage: bad"));
  }

  @Test
  void concretePresenceDistinguishesRootDefinitionsFromInheritedValues() {
    var scalar = language.defineYaml("type: Integer");
    assertTrue(scalar.resolve().exists());
    assertFalse(scalar.resolve().isConcrete());
    assertFalse(language.defineYaml("name: Only metadata").resolve().isConcrete());
    var parent = language.defineYaml("country: PL\nage: {type: Integer}");
    var child = parent.extendYaml("city: {type: Text}");
    assertTrue(child.resolve().isConcrete());
    assertTrue(child.at("/country").isConcrete());
    assertFalse(child.at("/age").isConcrete());
    assertFalse(child.at("/missing").isConcrete());
    var reference = language.reference(new blue.BlueId("11111111111111111111111111111111"));
    assertThrows(LanguageException.class, () -> reference.resolve().isConcrete());
  }

  @Test
  void specificationMetadataIsOwnAndNeverInherited() {
    var type =
        language.readYaml(
            "name: Base\nspecification: {name: Rules}\nx: {type: Integer}", DEFINITION);
    assertTrue(type.resolve().specification().isPresent());
    var child = language.specialize(type, "x: 1", VALUE);
    assertTrue(child.resolve().specification().isEmpty());
  }

  @Test
  void minimizationRetainsEffectiveMeaningAndBoundRecords() {
    var base = language.readYaml("name: Base\nx: 1", DEFINITION);
    var doc = language.specialize(base, "y: 2", VALUE);
    var reader = Language.newBuilder().content(doc).build();
    var minimized = reader.readJson(language.minimize(doc));
    assertEquals(1, minimized.resolve().at("/x").integer());
    assertEquals(2, minimized.resolve().at("/y").integer());
  }

  @Test
  void exactRefinementAndSchemaConjunctionKeepInheritedRestrictions() {
    var base = language.readYaml("amount: {type: Integer, schema: {minimum: 1}}", DEFINITION);
    var overlay = language.prepare(Language.Input.yaml("amount: 4"));
    var value = language.refine(base, overlay, VALUE);
    assertEquals(4, value.resolve().at("/amount").integer());
    assertTrue(language.preserves(value, base));
    assertTrue(language.sameView(value, language.admit(value, VALUE)));
    assertEquals("object", language.inspect(value).get("kind").text());
    var minimum = language.prepare(Language.Input.yaml("minimum: 1"));
    var stricter = language.prepare(Language.Input.yaml("minimum: 3\nmaximum: 9"));
    var merged = language.refineSchema(minimum, stricter);
    assertEquals(3, merged.resolve().at("/minimum").integer());
    assertFalse(language.conjoinSchemas(minimum, stricter).fields().isEmpty());
    assertThrows(LanguageException.class, () -> language.refineSchema(stricter, minimum));
  }

  @Test
  void extendAndSpecializeRetainDependenciesAndDistinguishIntent() {
    LanguageDocument point =
        language.defineYaml("name: Point\nx: {type: Integer}\ny: {type: Integer}");
    LanguageDocument colored = point.extendYaml("name: Colored Point\ncolor: {type: Text}");
    assertEquals(Language.Goal.DEFINITION, colored.goal());
    assertTrue(language.isSubtype(colored, point));
    LanguageDocument value = colored.specializeJson("{\"x\":2,\"y\":3,\"color\":\"blue\"}");
    assertEquals(Language.Goal.VALUE, value.goal());
    assertEquals(2, value.at("/x").integer());
    assertEquals("blue", value.at("/color").text());
    assertThrows(
        LanguageException.class, () -> colored.specializeYaml("x: wrong\ny: 3\ncolor: blue"));
    assertEquals(
        colored.blueId(),
        point.extendJson("{\"name\":\"Colored Point\",\"color\":{\"type\":\"Text\"}}").blueId());
  }

  @Test
  void explicitSameTypeAndSubtypeArePreservedButIncompatibleTypesAreRejected() {
    LanguageDocument point = language.defineYaml("x: {type: Integer}\ny: {type: Integer}");
    LanguageDocument colored = point.extendYaml("color: {type: Text}");
    Language reader =
        Language.newBuilder().type("Test/Point", point).type("Test/Colored", colored).build();
    LanguageDocument parent = reader.define(point.blueId());
    LanguageDocument implicit = parent.specializeYaml("x: 2\ny: 3");
    assertEquals(implicit.blueId(), parent.specializeYaml("type: Test/Point\nx: 2\ny: 3").blueId());
    LanguageDocument value = parent.specializeYaml("type: Test/Colored\nx: 2\ny: 3\ncolor: blue");
    assertEquals(colored.blueId().value(), value.node().at("/type/blueId").text());
    assertEquals("blue", value.at("/color").text());
    LanguageException failure =
        assertThrows(
            LanguageException.class, () -> parent.specializeYaml("type: Text\nvalue: wrong"));
    assertEquals("IncompatibleType", failure.category());
    assertThrows(
        IllegalArgumentException.class,
        () -> reader.specialize(parent, Language.Input.exactJson("{}"), Language.Goal.VALUE));
  }
}
