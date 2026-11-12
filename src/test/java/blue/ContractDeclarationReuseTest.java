package blue;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Live plans survive catalogue eviction, but never a changed program or evidence context. */
@Tag("contracts")
class ContractDeclarationReuseTest {
  @Test
  void liveDocumentAndItsCopyKeepTheirPlanAcrossStateChanges() {
    ContractProcessor processor = new ContractProcessor();
    ContractModel.Document document = member(processor, source(1, "input"));
    var expected = processor.registry.contracts(document);
    assertSame(expected, processor.registry.contracts(document.copy()));
    document =
        document.withBody(
            processor
                .blue
                .open(document.body)
                .with("count", new ExactNode.Value(Map.of("value", java.math.BigInteger.TWO))));
    assertSame(expected, processor.registry.contracts(document));
    assertThrows(
        UnsupportedOperationException.class, () -> expected.get("input").put("event", Map.of()));
  }

  @Test
  void programChangesAndDifferentEvidenceContextsRebuildThePlan() {
    ContractProcessor processor = new ContractProcessor();
    ContractModel.Document document = member(processor, source(1, "input"));
    var original = processor.registry.contracts(document);
    var changed = document.withBody(member(processor, source(1, "replacement")).body);
    var replacement = processor.registry.contracts(changed);
    assertFalse(replacement.containsKey("input"));
    assertTrue(replacement.containsKey("replacement"));
    assertTrue(original.containsKey("input"));
    processor.registry.clearDeclarations();
    assertNotSame(original, processor.registry.contracts(document));
    ContractProcessor independent = new ContractProcessor();
    assertNotSame(original, independent.registry.contracts(document));
    processor.blue.context = independent.blue.context;
    processor.blue.refresh();
    assertNotSame(original, processor.registry.contracts(document));
  }

  private static Map<String, Object> source(int count, String channel) {
    return Map.of(
        "count", count, "contracts", Map.of(channel, Map.of("type", "Contracts/External Channel")));
  }

  private static ContractModel.Document member(ContractProcessor processor, Object source) {
    var state = processor.create(source).state;
    return state.documents.get(state.root);
  }
}
