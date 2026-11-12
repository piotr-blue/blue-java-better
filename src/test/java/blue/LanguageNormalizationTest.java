package blue;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Physical normalization reuse must preserve presentation, work and evidence boundaries. */
@Tag("language")
class LanguageNormalizationTest {
  @Test
  void repeatedInlineStructureSharesValuesWithoutSkippingNormalizationWork() {
    var context = new LanguageContext();
    var shared = Map.of("steps", List.of(Map.of("operation", "hello", "amount", 7)));
    var first = normalize(Map.of("key", "first", "workflow", shared), context);
    long firstWork = context.work.get();
    context.work.set(0);
    var second = normalize(Map.of("key", "other", "workflow", shared), context);
    assertEquals(firstWork, context.work.get());
    assertNotSame(first, second);
    assertSame(first.get("workflow"), second.get("workflow"));
    var cold = normalize(Map.of("key", "other", "workflow", shared), new LanguageContext());
    assertEquals(LanguageCore.identity(cold), LanguageCore.identity(second));
    assertEquals(LanguageCore.exactText(cold), LanguageCore.exactText(second));
    assertNotSame(cold.get("workflow"), second.get("workflow"));
    assertThrows(UnsupportedOperationException.class, () -> second.fields().clear());
  }

  @Test
  void equalIdentityDoesNotReplaceInlineContentWithAReferenceOrChangeTheLastPresentation() {
    var context = new LanguageContext();
    var leaf = normalize(Map.of("count", 7), context);
    var inline = normalize(Map.of("child", leaf), context);
    var reference =
        normalize(Map.of("child", Map.of("blueId", LanguageCore.identity(leaf))), context);
    assertEquals(LanguageCore.identity(inline), LanguageCore.identity(reference));
    assertNotEquals(LanguageCore.exactText(inline), LanguageCore.exactText(reference));
    assertInstanceOf(ExactNode.Value.class, inline.get("child"));
    assertInstanceOf(ExactNode.Ref.class, reference.get("child"));
    assertSame(reference, context.bodies.get(LanguageCore.identity(reference)));
    var again = normalize(Map.of("child", leaf), context);
    assertEquals(LanguageCore.exactText(inline), LanguageCore.exactText(again));
    assertSame(again, context.bodies.get(LanguageCore.identity(inline)));
  }

  @Test
  void equalityOfReferenceIdsCannotImportAnEstablishedCyclicBodyHint() {
    var context = new LanguageContext();
    String id = LanguageCore.CORE.get("Text") + "#0";
    var body = ExactNode.value(Map.of("next", new ExactNode.Local(0)));
    var established = normalize(Map.of("peer", new ExactNode.Ref(id, body)), context);
    var plain = normalize(Map.of("peer", new ExactNode.Ref(id)), context);
    assertEquals(established, plain); // Ref equality deliberately uses the identity only.
    assertNotSame(established, plain);
    var selected = (ExactNode.Ref) plain.get("peer");
    assertNull(selected.body());
    assertThrows(LanguageFailure.class, () -> LanguageCore.open(selected, context));
  }

  @Test
  void typedListsKeepTheSamePayloadRecordWithoutRewritingItsMaterialization() {
    var context = new LanguageContext();
    var source =
        Map.of("type", Map.of("blueId", LanguageCore.CORE.get("List")), "items", List.of(1, 2));
    var first = normalize(source, context);
    var payload = ExactNode.list(LanguageCore.exactItems(first.get("items")));
    var retained = context.bodies.get(LanguageCore.identity(payload));
    assertNotNull(retained);
    assertSame(first, normalize(source, context));
    assertSame(retained, context.bodies.get(LanguageCore.identity(payload)));
  }

  private static ExactNode.Value normalize(Object source, LanguageContext context) {
    return (ExactNode.Value) LanguageCore.normalize(source, context, "exact", false);
  }
}
