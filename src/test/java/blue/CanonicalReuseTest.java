package blue;

import static blue.ContractModel.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("contracts")
class CanonicalReuseTest {
  @Test
  void physicalReuseChangesNeitherGasNorReceipts() {
    String expected;
    long gas;
    try (Contracts.Session warm = CanonicalAgreementTest.open(Contracts.create(), 5)) {
      Contracts.Transition outcome =
          warm.call(
              "order-0", "submit", Map.of("payment", CanonicalAgreementTest.payment("0")), 100);
      outcome.requireApplied();
      expected = outcome.receipt();
      gas = outcome.gas();
    }
    System.setProperty("blue.canonical.revalidateClosures", "true");
    System.setProperty("blue.canonical.recompile", "true");
    try (Contracts.Session cold = CanonicalAgreementTest.open(Contracts.create(), 5)) {
      Contracts.Transition outcome =
          cold.call(
              "order-0", "submit", Map.of("payment", CanonicalAgreementTest.payment("0")), 100);
      outcome.requireApplied();
      assertEquals(expected, outcome.receipt());
      assertEquals(gas, outcome.gas());
    } finally {
      System.clearProperty("blue.canonical.revalidateClosures");
      System.clearProperty("blue.canonical.recompile");
    }
  }

  @Test
  void cachedComputeUsesTheCurrentlySelectedDefinition() {
    ContractProcessor processor = new ContractProcessor();
    Object source =
        m(
            "count",
            0,
            "logic",
            definition(1),
            "contracts",
            m(
                "input", m("type", "Contracts/External Channel", "event", m("kind", "run")),
                "go",
                    m(
                        "type",
                        "Contracts/Sequential Workflow",
                        "channel",
                        "input",
                        "steps",
                        List.of(
                            m(
                                "type",
                                "Contracts/Compute",
                                "definition",
                                "/logic",
                                "entry",
                                "apply"))),
                "edit", m("type", "Contracts/External Channel", "event", m("kind", "edit")),
                "replace",
                    m(
                        "type",
                        "Contracts/Sequential Workflow",
                        "channel",
                        "edit",
                        "steps",
                        List.of(
                            m(
                                "type",
                                "Contracts/Update Document",
                                "changeset",
                                List.of(
                                    m(
                                        "op",
                                        "replace",
                                        "path",
                                        "/logic",
                                        "val",
                                        m("$event", "/logic"))))))));
    ContractProcessor.Document document = processor.create(source);
    ContractProcessor.Result first = processor.process(document, m("kind", "run"), 1);
    assertEquals("APPLIED", first.status());
    assertEquals("1", processor.read(first.document(), "/count").toString());
    ContractProcessor.Result edit =
        processor.process(first.document(), m("kind", "edit", "logic", definition(9)), 2);
    assertEquals("APPLIED", edit.status(), edit.observations().toString());
    ContractProcessor.Result second =
        processor.process(edit.document(), m("kind", "run", "sequence", 2), 3);
    assertEquals("APPLIED", second.status(), second.observations().toString());
    assertEquals("9", processor.read(second.document(), "/count").toString());
  }

  static Object definition(int value) {
    return m(
        "functions",
        m(
            "apply",
            m(
                "do",
                List.of(m("$appendChange", m("op", "replace", "path", "/count", "val", value))))));
  }

  @Test
  void cyclicGraphsFinalizeAndColdRestoreWithoutLosingMemberIdentities() {
    ContractProcessor processor = new ContractProcessor();
    Object a = member("b", "a");
    Object b = member("a", "b");
    ContractProcessor.Result initial = processor.admit(m("a", a, "b", b), "a");
    assertEquals("APPLIED", initial.status(), initial.observations().toString());
    ContractProcessor.Result result = processor.process(initial.document(), m("target", "a"), 1);
    assertEquals("APPLIED", result.status(), result.observations().toString());
    assertEquals("1", processor.read(result.document(), "/count").toString());
    assertEquals("0", processor.read(result.document(), "/peer/count").toString());
    ContractProcessor cold = new ContractProcessor();
    ContractProcessor.Document restored =
        cold.importSnapshot(
            processor.exportSnapshot(result.document()), result.document().identity());
    assertEquals(result.document().blueId(), restored.blueId());
    assertEquals("1", cold.read(restored, "/peer/peer/count").toString());
  }

  static Object member(String peer, String target) {
    return m(
        "count",
        0,
        "peer",
        ContractProcessor.link(peer),
        "contracts",
        m(
            "embedded", m("type", "Contracts/Process Embedded", "paths", List.of("/peer")),
            "input", m("type", "Contracts/External Channel", "event", m("target", target)),
            "run",
                m(
                    "type",
                    "Contracts/Sequential Workflow",
                    "channel",
                    "input",
                    "steps",
                    List.of(
                        m(
                            "type",
                            "Contracts/Compute",
                            "do",
                            List.of(
                                m(
                                    "$appendChange",
                                    m(
                                        "op",
                                        "replace",
                                        "path",
                                        "/count",
                                        "val",
                                        m("$add", List.of(m("$document", "/count"), 1))))))))));
  }
}
