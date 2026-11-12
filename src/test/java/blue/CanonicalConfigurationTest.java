package blue;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Public execution selection and configuration reuse cannot introduce a second semantics. */
@Tag("contracts")
class CanonicalConfigurationTest {
  private static final String SOURCE =
      """
      count: 0
      contracts:
        input: {type: Contracts/External Channel}
        add:
          type: Contracts/Sequential Workflow Operation
          channel: input
          steps:
            - type: Contracts/Update Document
              changeset: [{op: replace, path: /count, val: {$add: [{$document: /count}, 1]}}]
            - type: Contracts/Trigger Event
              event: {kind: Counted, count: {$document: /count}}
      """;

  @Test
  void experimentalExecutionFacadesAreNotPublished() {
    for (String name :
        List.of(
            "Blue",
            "Coordinator",
            "DocumentRuntime",
            "ContractOwners",
            "ExecutionProfile",
            "Program",
            "ContractState"))
      assertThrows(ClassNotFoundException.class, () -> Class.forName("blue." + name), name);
  }

  @Test
  void preparedConfigurationMatchesAFreshCanonicalInterpreter() throws Exception {
    Contracts shared = Contracts.newBuilder().enableDocumentDispatch().build();
    Map<String, Object> expected = observation(shared);
    try (var executor = Executors.newFixedThreadPool(4)) {
      List<Future<Map<String, Object>>> results = new ArrayList<>();
      for (int i = 0; i < 32; i++) results.add(executor.submit(() -> observation(shared)));
      for (var result : results) assertEquals(expected, result.get());
    }
    assertEquals(expected, observation(Contracts.newBuilder().enableDocumentDispatch().build()));
  }

  private static Map<String, Object> observation(Contracts configuration) {
    try (Contracts.Session session = configuration.openYaml(SOURCE)) {
      session.initialize().requireApplied();
      var result = session.call("input", "add", Map.of(), 7).requireApplied();
      assertEquals(java.math.BigInteger.ONE, session.read("/count"));
      return Map.of(
          "receipt",
          result.receipt(),
          "observations",
          result.observations(),
          "snapshot",
          session.snapshot());
    }
  }

  @Test
  void canonicalSessionAndDurableExecutionProduceTheSameReceipt() {
    Contracts configuration = Contracts.create();
    Map<String, Object> snapshot;
    Contracts.Transition expected;
    try (Contracts.Session session = configuration.openYaml(SOURCE)) {
      session.initialize().requireApplied();
      snapshot = session.snapshot();
      expected = session.call("input", "add", Map.of(), 7).requireApplied();
    }
    Language language = Language.newBuilder().mount("Contracts", Contracts.publication()).build();
    LanguageContent event =
        language.prepare(
            Language.Input.yaml(
                """
                type: Contracts/Operation Request
                channel: input
                operation: add
                request: {}
                """));
    try (Contracts.Execution execution = Contracts.Execution.using(configuration, null)) {
      var actual =
          execution.process(execution.restore(snapshot), event, 7, AdmissionEvidence.empty());
      actual.requireApplied();
      assertEquals(expected.receipt(), actual.transition().receipt());
      assertEquals(expected.observations(), actual.transition().observations());
    }
  }
}
