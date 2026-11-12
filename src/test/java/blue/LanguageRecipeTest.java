package blue;

import static blue.LanguageSemantics.recipe;
import static blue.LanguageSemantics.recipeWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Composition preserves ordered requirements and the last exact representation of each identity.
 */
@Tag("language")
class LanguageRecipeTest {
  private static final ExactNode ONE = ExactNode.scalar("Integer", BigInteger.ONE);
  private static final ExactNode TWO = ExactNode.scalar("Integer", BigInteger.TWO);
  private static final String INTEGER = LanguageCore.CORE.get("Integer");
  private static final String TEXT = LanguageCore.CORE.get("Text");
  private static final String BOOLEAN = LanguageCore.CORE.get("Boolean");

  @Test
  void composingOneRecipeAndComposingItsPartsPreserveTheSameOrderedRequirements() {
    var original = recipeWith(List.of(TEXT, TEXT, INTEGER), ONE, TWO, ONE);
    var composed = recipeWith(List.of(INTEGER, BOOLEAN, INTEGER), original);
    var direct = recipeWith(List.of(INTEGER, BOOLEAN, TEXT), TWO, ONE);
    assertEquals(List.of(TWO, ONE), composed.parts());
    assertEquals(List.of(INTEGER, BOOLEAN, TEXT), composed.requirements());
    assertEquals(direct, composed);
    assertEquals(direct.key(), composed.key());
    assertEquals(direct.headKey(), composed.headKey());
    assertEquals(original, recipeWith(List.of(TEXT), original));
  }

  @Test
  void onePartCopiesMutableRequirementsWithoutAddingDuplicates() {
    var requirements = new ArrayList<>(List.of(INTEGER, INTEGER, TEXT));
    var composed = recipeWith(requirements, ONE);
    requirements.clear();
    assertEquals(List.of(INTEGER, TEXT), composed.requirements());
    assertEquals(List.of(ONE), composed.parts());
    assertEquals(recipeWith(List.of(INTEGER, TEXT), recipe(ONE)), composed);
    assertThrows(UnsupportedOperationException.class, () -> composed.requirements().clear());
    assertThrows(UnsupportedOperationException.class, () -> composed.parts().clear());
  }

  @Test
  void duplicateIdentitiesKeepTheirLastRepresentationAndRelativePosition() {
    ExactNode.Ref reference = new ExactNode.Ref(LanguageCore.identity(ONE));
    var inlineLast = recipe(ONE, reference, TWO, ONE);
    var referenceLast = recipe(ONE, TWO, reference);
    assertEquals(List.of(TWO, ONE), inlineLast.parts());
    assertEquals(List.of(TWO, reference), referenceLast.parts());
    assertEquals(inlineLast.key(), referenceLast.key());
    assertEquals(inlineLast.parts(), recipeWith(List.of(TEXT), inlineLast).parts());
    assertEquals(referenceLast.parts(), recipeWith(List.of(TEXT), referenceLast).parts());
  }
}
