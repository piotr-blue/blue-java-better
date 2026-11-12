package blue.language;

import static blue.Language.Goal.DEFINITION;
import static blue.Language.Goal.VALUE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.Language;
import blue.LanguageDocument;
import blue.LanguageException;
import blue.LanguageRecord;
import blue.Node;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("language")
class LanguageIngressTest {
  private final Language language = Language.create();

  @Test
  void typedSourcePreservesExactNumbersWithoutChangingTextIngressPolicy() {
    var integer = new java.math.BigInteger("9007199254740993123456789");
    var source =
        Node.object(java.util.Map.of("amount", Node.integer(integer), "ratio", Node.number(0.25)));
    var value = language.read(source, VALUE);
    assertEquals(
        language
            .readYaml("amount: {type: Integer, value: '" + integer + "'}\nratio: 0.25")
            .blueId(),
        value.blueId());
    assertEquals(value.blueId(), language.prepare(source).blueId());
    assertEquals(integer, value.at("/amount").bigInteger());
    assertThrows(LanguageException.class, () -> language.readJson(integer.toString()));
    var integerDouble =
        Node.object(java.util.Map.of("type", Node.text("Integer"), "value", Node.number(1.0)));
    assertEquals(
        language.readJson("{\"type\":\"Integer\",\"value\":1.0}").blueId(),
        language.read(integerDouble, VALUE).blueId());
    assertThrows(LanguageException.class, () -> language.prepare(Node.missing()));
    Node deep = Node.integer(1);
    for (int i = 0; i <= Language.Limits.defaults().depth(); i++) {
      deep = Node.list(java.util.List.of(deep));
    }
    Node tooDeep = deep;
    assertThrows(LanguageException.class, () -> language.prepare(tooDeep));
  }

  @Test
  void authoringInspectionIncludesUnfilledInheritedFieldsWithoutChangingValuePresence() {
    LanguageDocument document =
        language.defineYaml(
            """
            type:
              unfilled: {type: Text}
              inherited: 1
            own: true
            """);
    assertEquals(
        java.util.List.of("inherited", "own", "unfilled"), document.resolve().declaredFields());
    assertEquals(java.util.List.of("inherited", "own"), document.resolve().keys());
    assertFalse(document.at("/unfilled").exists());
    assertTrue(document.at("/absent").declaredFields().isEmpty());
    assertThrows(
        UnsupportedOperationException.class, () -> document.resolve().declaredFields().add("bad"));
  }

  @Test
  void definitionsAndValuesHaveDistinctAdmissionObligations() {
    var definition = language.readYaml("type: Integer\nschema: {minimum: 1}", DEFINITION);
    assertEquals(DEFINITION, definition.goal());
    assertThrows(
        LanguageException.class,
        () -> language.readYaml("type: Integer\nschema: {minimum: 1}", VALUE));
    var prepared =
        language.prepare(Language.Input.yaml("type: Integer\nvalue: 0\nschema: {minimum: 1}"));
    assertNotNull(prepared.blueId());
    assertThrows(LanguageException.class, () -> language.admit(prepared, VALUE));
  }

  @Test
  void scalarCodecsHandleDoubleLargeIntegerAndStrictInputGrammar() {
    var decimal = language.readJson("0.1");
    assertEquals(0.1, decimal.resolve().number());
    assertEquals(Node.Kind.DOUBLE, decimal.node().get("value").kind());
    assertEquals(language.readJson("0.0").blueId(), language.readJson("-0.0").blueId());
    var large = language.readYaml("type: Integer\nvalue: '9007199254740993'");
    assertEquals("9007199254740993", large.resolve().bigInteger().toString());
    assertThrows(LanguageException.class, () -> language.readJson("9007199254740993"));
    assertThrows(LanguageException.class, () -> language.readYaml("x: 1\nx: 2"));
    assertThrows(LanguageException.class, () -> language.readJson("null"));
    assertThrows(
        LanguageException.class,
        () -> language.readJson("{\"blueId\":\"" + large.blueId() + "\",\"extra\":null}"));
    assertThrows(LanguageException.class, () -> language.readJson("{\"value\":3,\"x\":4}"));
  }

  @Test
  void completeAdmissionRejectsBadUnselectedSiblingWhileSelectedReadCanSucceed() {
    String invalid = "good: 7\nbad: {type: Integer, value: 0, schema: {minimum: 1}}";
    var prepared = language.prepare(Language.Input.yaml(invalid));
    assertEquals(7, prepared.resolve().at("/good").integer());
    assertThrows(LanguageException.class, () -> language.admit(prepared, VALUE));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidDocuments")
  void diagnosticsRetainTheExactRejectionLocation(
      String scenario, String source, String path, String phase, String category) {
    LanguageException failure =
        assertThrows(LanguageException.class, () -> language.readYaml(source));
    assertEquals(category, failure.category());
    assertFalse(failure.incomplete());
    assertEquals(path, failure.diagnostic().get("path").text());
    assertEquals(phase, failure.diagnostic().get("phase").text());
    assertEquals("Invalid", failure.diagnostic().get("outcome").text());
  }

  private static Stream<Arguments> invalidDocuments() {
    return Stream.of(
        Arguments.of(
            "escaped object field",
            "outer:\n  'a/b~c': {type: Integer, value: 0, schema: {minimum: 1}}",
            "/outer/a~1b~0c",
            "validation",
            "SchemaViolation"),
        Arguments.of(
            "list item",
            "rows:\n  - {type: Integer, value: 0, schema: {minimum: 1}}",
            "/rows/items/0",
            "validation",
            "SchemaViolation"),
        Arguments.of(
            "inline type definition",
            "type:\n  bad: {type: Integer, value: 0, schema: {minimum: 1}}",
            "/type/bad",
            "validation",
            "SchemaViolation"),
        Arguments.of(
            "root value",
            "type: Integer\nvalue: 0\nschema: {minimum: 1}",
            "",
            "validation",
            "SchemaViolation"),
        Arguments.of(
            "source normalization",
            "outer:\n  'a/b~c': {type: Text, value: 2}",
            "/outer/a~1b~0c",
            "source-construction",
            "InvalidScalar"));
  }

  @Test
  void providersUseTheCanonicalLanguageApi() {
    var value = language.readYaml("value: 42");
    var supplied =
        Language.newBuilder()
            .provider(id -> Optional.of(LanguageRecord.json(value.canonicalJson())))
            .build();
    assertEquals(
        42,
        supplied
            .read(Language.Input.exactJson("{\"blueId\":\"" + value.blueId() + "\"}"), VALUE)
            .resolve()
            .integer());
    assertThrows(
        IllegalArgumentException.class, () -> value.resolve().at("/missing").observation("typo"));
  }

  @Test
  void namedEntryPointsKeepDefinitionAndValueValidationDistinct(@TempDir Path directory)
      throws Exception {
    String yaml = "type: Integer\nschema: {minimum: 1}";
    String json = "{\"type\":\"Integer\",\"schema\":{\"minimum\":1}}";
    Path yamlFile = Files.writeString(directory.resolve("type.yaml"), yaml);
    Path jsonFile = Files.writeString(directory.resolve("type.json"), json);
    LanguageDocument type = language.defineYaml(yaml);
    assertEquals(Language.Goal.DEFINITION, type.goal());
    assertEquals(type.blueId(), language.defineJson(json).blueId());
    assertEquals(type.blueId(), language.defineYaml(yamlFile).blueId());
    assertEquals(type.blueId(), language.defineJson(jsonFile).blueId());
    assertThrows(LanguageException.class, () -> language.readYaml(yaml));
    assertThrows(LanguageException.class, () -> language.readJson(jsonFile));
    Files.writeString(yamlFile, "value: 7");
    Files.writeString(jsonFile, "7");
    assertEquals(language.readJson("7").blueId(), language.readYaml(yamlFile).blueId());
    assertEquals(language.readJson("7").blueId(), language.readJson(jsonFile).blueId());
    assertEquals(7, type.specializeYaml("value: 7").at("").integer());
    assertThrows(LanguageException.class, () -> type.specializeJson("{\"value\":0}"));
  }
}
