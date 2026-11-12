package blue;

import static blue.ExactNode.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

/** Reusing an unchanged child requires the same contextual obligation, not just its BlueId. */
class LocalValidationTest {
  @Test
  void validatedTimelineHeadDoesNotWalkThePreviousEntryChainToBorrowFacts() {
    LanguageContext context = new LanguageContext();
    ExactNode chain = value(Map.of("sequence", scalar("Text", "0")));
    for (int i = 1; i <= 32; i++)
      chain = value(Map.of("sequence", scalar("Text", Integer.toString(i)), "previous", chain));
    ExactNode before = value(Map.of("entry", chain));
    LanguageSemantics.strict(before, context, "value");
    ExactNode nextEntry = value(Map.of("sequence", scalar("Text", "33"), "previous", chain));
    ExactNode after = value(Map.of("entry", nextEntry));
    context.durableClosures.add(LanguageCore.identity(before));
    context.establishedValues =
        Map.of(
            LanguageCore.identity(nextEntry),
            LanguageSemantics.head(LanguageSemantics.recipe(nextEntry), context));
    context.work.set(0);
    LanguageSemantics.inheritUnchangedValidation(after, before, context);
    assertTrue(
        context.work.get() < 20,
        "Borrowing prior facts must stop at the consumer-verified entry head: "
            + context.work.get());
    LanguageSemantics.strict(after, context, "value");
  }

  @Test
  void stricterInheritedConstraintStillRejectsAnUnchangedScalar() {
    var engine = new ContractProcessor();
    var before = engine.blue.source(Map.of("row", Map.of("score", 1)));
    var type =
        engine.blue.source(
            Map.of("score", Map.of("type", "Integer", "schema", Map.of("minimum", 5))),
            "definition");
    var after =
        engine.blue.node(
            Map.of(
                "row",
                Map.of("type", Map.of("blueId", ContractLanguage.identity(type)), "score", 1)),
            "value",
            false,
            null);
    engine.blue.context.retainLocalClosure(ContractLanguage.identity(before));
    engine.blue.context.inheritUnchangedValidation(after, before);
    assertThrows(ContractModel.Error.class, () -> engine.blue.certify(after));
  }

  @Test
  void previouslyAbsentContainerDoesNotGrantValueValidationToItsRequiredChildren() {
    var engine = new ContractProcessor();
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("schema", Map.of("required", false));
    row.put("must", Map.of("type", "Integer", "schema", Map.of("required", true)));
    var before = engine.blue.source(Map.of("row", row));
    row.put("present", 1);
    // Normalize exact syntax without authoring aliases or prematurely validating the new value.
    Map<String, Object> exactRow = new LinkedHashMap<>(row);
    exactRow.put(
        "must",
        Map.of(
            "type",
            Map.of("blueId", ContractLanguage.CORE.get("Integer")),
            "schema",
            Map.of("required", true)));
    var after = engine.blue.node(Map.of("row", exactRow), "value", false, null);
    engine.blue.context.retainLocalClosure(ContractLanguage.identity(before));
    engine.blue.context.inheritUnchangedValidation(after, before);
    assertThrows(ContractModel.Error.class, () -> engine.blue.certify(after));
  }
}
