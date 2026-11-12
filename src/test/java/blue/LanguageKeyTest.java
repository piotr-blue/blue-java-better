package blue;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The native key fast path must preserve derived schemas and canonical non-Text encodings. */
@Tag("language")
@Tag("contracts")
class LanguageKeyTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void builtInTextAcceptsEveryAdmittedStringKey(boolean nativeReuse) {
    LanguageContext context = Language.create().context();
    context.reuseWithinHostBudget = nativeReuse;
    for (String key : List.of("", "a", "a/b~c", "ą", "😀", "0", "01", "true")) {
      assertDoesNotThrow(
          () -> LanguageSemantics.checkKey(key, LanguageCore.CORE.get("Text"), context));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void derivedTextKeepsItsSchema(boolean nativeReuse) {
    LanguageDocument shortText = Language.create().defineYaml("type: Text\nschema: {maxLength: 2}");
    Language language = Language.newBuilder().type("Short", shortText).build();
    LanguageContext context = language.context();
    context.reuseWithinHostBudget = nativeReuse;
    assertDoesNotThrow(() -> LanguageSemantics.checkKey("ab", shortText.blueId().value(), context));
    assertEquals(
        "SchemaViolation",
        assertThrows(
                LanguageFailure.class,
                () -> LanguageSemantics.checkKey("abc", shortText.blueId().value(), context))
            .category());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void nonTextKeysStillRequireTheirCanonicalEncoding(boolean nativeReuse) {
    LanguageContext context = Language.create().context();
    context.reuseWithinHostBudget = nativeReuse;
    assertDoesNotThrow(
        () -> LanguageSemantics.checkKey("1", LanguageCore.CORE.get("Integer"), context));
    assertThrows(
        LanguageFailure.class,
        () -> LanguageSemantics.checkKey("01", LanguageCore.CORE.get("Integer"), context));
    assertThrows(
        LanguageFailure.class,
        () -> LanguageSemantics.checkKey("True", LanguageCore.CORE.get("Boolean"), context));
    assertThrows(
        LanguageFailure.class,
        () -> LanguageSemantics.checkKey("[]", LanguageCore.CORE.get("List"), context));
  }
}
