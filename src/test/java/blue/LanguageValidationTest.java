package blue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Validation reuse preserves the goal and whether the occurrence must be present. */
@Tag("language")
class LanguageValidationTest {
  @ParameterizedTest
  @ValueSource(strings = {"definition", "optional-field"})
  void aValidDeclarationDoesNotCertifyAMissingRootPayload(String precedingUse) {
    LanguageContext context = Language.create().context();
    ExactNode missing =
        ExactNode.value(Map.of("type", new ExactNode.Ref(LanguageCore.CORE.get("Integer"))));
    if (precedingUse.equals("definition")) {
      LanguageSemantics.validate(LanguageSemantics.recipe(missing), context, "definition");
    } else {
      ExactNode parent = ExactNode.value(Map.of("optional", missing));
      LanguageSemantics.validate(LanguageSemantics.recipe(parent), context, "value");
    }
    LanguageFailure failure =
        assertThrows(
            LanguageFailure.class,
            () -> LanguageSemantics.validate(LanguageSemantics.recipe(missing), context, "value"));
    assertEquals("SchemaViolation", failure.category());
    assertEquals("scalar payload required", failure.getMessage());
  }
}
