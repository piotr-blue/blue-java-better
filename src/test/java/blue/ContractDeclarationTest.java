package blue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Declaration reuse must preserve per-document channel and marker validation. */
@Tag("contracts")
class ContractDeclarationTest {
  private static final Map<String, Object> OPERATION =
      Map.of(
          "type",
          "Contracts/Sequential Workflow Operation",
          "channel",
          "input",
          "steps",
          java.util.List.of());
  private static final Map<String, Object> INPUT = Map.of("type", "Contracts/External Channel");

  @Test
  void changingLifecycleMarkersRetainsUnchangedHeadersButRechecksTheContractMap() {
    ContractProcessor engine = new ContractProcessor();
    var prepared = prepare(engine, Map.of("contracts", Map.of("input", INPUT, "run", OPERATION)));
    var before = engine.registry.contracts(prepared.state.documents.get(prepared.state.root).body);
    var result = engine.initialize(prepared);
    assertEquals("APPLIED", result.status());
    var initialized = result.document();
    var after =
        engine.registry.contracts(initialized.state.documents.get(initialized.state.root).body);
    assertEquals(before, after);
    assertThrows(
        UnsupportedOperationException.class, () -> after.get("run").put("channel", "missing"));
    // A previously valid operation is invalid when its channel disappears.
    assertThrows(
        ContractModel.Error.class,
        () -> prepare(engine, Map.of("contracts", Map.of("run", OPERATION))));
    // Cached declarations never authorize a reserved marker at the wrong key.
    assertThrows(
        ContractModel.Error.class,
        () -> prepare(engine, Map.of("contracts", Map.of("initialized", INPUT, "input", INPUT))));
  }

  private static ContractProcessor.Document prepare(ContractProcessor engine, Object source) {
    return engine.prepare(Map.of("root", source), "root");
  }
}
