package blue;

import static blue.ContractModel.m;
import static blue.ContractModel.obj;
import static blue.ContractModel.str;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.ContractModel.Document;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Shared closure traversal must preserve the complete independent-export record union. */
@Tag("contracts")
@Tag("conformance")
class ContractSnapshotExportTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void batchedClosureMatchesIndependentExportsWithListsAndCycles(boolean cyclic) {
    ContractProcessor processor = new ContractProcessor();
    Object child = m("history", List.of("created", "approved", "paid"));
    ContractProcessor.Document document;
    if (cyclic) {
      Map<String, Object> a = obj(CanonicalTransactionTest.cyclicMember("a", "b", false));
      Map<String, Object> b = obj(CanonicalTransactionTest.cyclicMember("b", "a", false));
      a.put("history", child);
      b.put("history", child);
      ContractProcessor.Result initial = processor.admit(m("a", a, "b", b), "a");
      assertEquals("APPLIED", initial.status());
      document = initial.document();
    } else {
      document =
          processor.create(
              m(
                  "one",
                  child,
                  "two",
                  child,
                  "contracts",
                  m(
                      "embedded",
                      m("type", "Contracts/Process Embedded", "paths", List.of("/one", "/two")))));
    }
    Map<String, Object> snapshot = processor.exportSnapshot(document);
    assertEquals(independentRecords(processor, document, Set.of()), snapshot.get("content"));
    ContractProcessor restored = new ContractProcessor();
    assertEquals(
        document.identity(), restored.importSnapshot(snapshot, document.identity()).identity());

    String root = document.blueId();
    Set<String> supplied = Set.of(root);
    assertEquals(
        independentRecords(processor, document, supplied),
        ContractStorage.export(processor, document.state, true, supplied).get("content"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void compactRecordsPreserveIdentityExecutionAndInspection(boolean cyclic) {
    ContractProcessor processor = new ContractProcessor();
    ContractProcessor.Document source;
    if (cyclic) {
      source =
          processor
              .admit(
                  m(
                      "a",
                      CanonicalTransactionTest.cyclicMember("a", "b", false),
                      "b",
                      CanonicalTransactionTest.cyclicMember("b", "a", false)),
                  "a")
              .document();
    } else {
      source =
          processor
              .initialize(
                  m(
                      "payload",
                      m(
                          "first",
                          m(
                              "items",
                              List.of("a".repeat(1000), "b".repeat(1000), "c".repeat(1000)))),
                      "contracts",
                      m("input", m("type", "Contracts/External Channel"))))
              .document();
    }
    Map<String, Object> compact = processor.exportSnapshot(source);
    Map<String, Object> inline;
    String previous = System.getProperty("blue.canonical.inlineRecords");
    String previousPrefixes = System.getProperty("blue.canonical.prefixWitnesses");
    try {
      System.setProperty("blue.canonical.inlineRecords", "true");
      System.setProperty("blue.canonical.prefixWitnesses", "true");
      inline = processor.exportSnapshot(source);
    } finally {
      if (previous == null) System.clearProperty("blue.canonical.inlineRecords");
      else System.setProperty("blue.canonical.inlineRecords", previous);
      if (previousPrefixes == null) System.clearProperty("blue.canonical.prefixWitnesses");
      else System.setProperty("blue.canonical.prefixWitnesses", previousPrefixes);
    }
    assertEquals(inline.get("snapshot"), compact.get("snapshot"));
    assertEquals(inline.get("identity"), compact.get("identity"));
    Contracts runtime = Contracts.create();
    try (Contracts.Session a = runtime.restore(inline, source.identity());
        Contracts.Session b = runtime.restore(compact, source.identity())) {
      assertEquals(a.read(""), b.read(""));
      CanonicalTransactionTest.compare(a, b, m("target", "a"), 1, true);
      assertEquals(a.read(""), b.read(""));
    }
    if (!cyclic)
      assertTrue(ContractWire.encode(compact).length() < ContractWire.encode(inline).length());
  }

  private static Map<String, Object> independentRecords(
      ContractProcessor processor, ContractProcessor.Document document, Set<String> supplied) {
    Map<String, Object> result = new TreeMap<>();
    for (Document member : document.state.documents.values())
      result.putAll(processor.blue.context.records(member.body, supplied));
    for (Object runtime : document.state.runtimes)
      for (String field : List.of("type_id", "semantics_id"))
        result.putAll(
            processor.blue.context.records(
                new ExactNode.Ref(str(obj(runtime).get(field))), supplied));
    return result;
  }
}
