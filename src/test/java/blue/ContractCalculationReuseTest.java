package blue;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Native equivalence of protected calculation reuse, including accounting and rollback. */
@Tag("runtime")
@Tag("conformance")
class ContractCalculationReuseTest {
  private final Language language =
      Language.newBuilder().mount("Contracts", Contracts.publication()).build();
  private final Contracts configuration = Contracts.newBuilder().language(language).build();
  private final LanguageContent event = language.prepare(Language.Input.yaml("kind: tick"));

  private Map<String, Object> initial() {
    try (var processor = Contracts.Execution.using(configuration, null)) {
      var document =
          language.defineYaml(
              """
              child:
                count: 0
                contracts:
                  input: {type: Contracts/External Channel, event: {kind: tick}}
                  increment:
                    type: Contracts/Sequential Workflow
                    channel: input
                    steps:
                      - type: Contracts/Update Document
                        changeset: [{op: replace, path: /count, val: {$add: [{$document: /count}, 1]}}]
                      - type: Contracts/Trigger Event
                        event: {kind: changed, count: {$document: /count}}
              seen: 0
              contracts:
                embedded: {type: Contracts/Process Embedded, paths: [/child]}
                events: {type: Contracts/Embedded Node Channel}
                react:
                  type: Contracts/Sequential Workflow
                  channel: events
                  steps:
                    - type: Contracts/Update Document
                      changeset: [{op: replace, path: /seen, val: {$event: /event/count}}]
              """);
      return processor
          .initialize(processor.prepare("root", document))
          .requireApplied()
          .after()
          .checkpoint();
    }
  }

  @Test
  void standaloneChildCalculationsAreReusableInsideOneCanonicalRootedAttempt() {
    var checkpoint = initial();
    Map<String, Map<String, Object>> retained;
    try (var processor =
        Contracts.Execution.using(configuration, null).reuseCalculations(k -> Optional.empty())) {
      var before = processor.restore(checkpoint);
      String child = before.embeddings().getFirst().target();
      processor.process(before.rooted(child), event, 1, AdmissionEvidence.empty()).requireApplied();
      retained = serialized(processor.calculations());
      assertFalse(retained.isEmpty());
    }
    try (var ordinary = Contracts.Execution.using(configuration, null);
        var reused =
            Contracts.Execution.using(configuration, null)
                .reuseCalculations(k -> Optional.ofNullable(retained.get(k)))) {
      var expected =
          ordinary.process(ordinary.restore(checkpoint), event, 1, AdmissionEvidence.empty());
      var actual = reused.process(reused.restore(checkpoint), event, 1, AdmissionEvidence.empty());
      equivalent(expected, actual);
      assertTrue(
          reused.calculationStatistics().reused() >= 2, reused.calculationStatistics().toString());
      assertEquals(1L, ((Number) actual.after().read("/seen")).longValue());
    }
  }

  @Test
  void everySharedGasBoundaryHasIdenticalOutcomeAndFirstRejectedCharge() {
    var checkpoint = initial();
    Map<String, Map<String, Object>> retained;
    long gas;
    try (var processor =
        Contracts.Execution.using(configuration, null).reuseCalculations(k -> Optional.empty())) {
      gas =
          processor
              .process(processor.restore(checkpoint), event, 1, AdmissionEvidence.empty())
              .requireApplied()
              .transition()
              .gas();
      retained = serialized(processor.calculations());
    }
    long fallbacks = 0, hits = 0;
    for (long limit = 0; limit <= gas + 1; limit++) {
      var bounded = Contracts.newBuilder().language(language).gasLimit(limit).build();
      try (var ordinary = Contracts.Execution.using(bounded, null);
          var reused =
              Contracts.Execution.using(bounded, null)
                  .reuseCalculations(k -> Optional.ofNullable(retained.get(k)))) {
        var expected =
            ordinary.process(ordinary.restore(checkpoint), event, 1, AdmissionEvidence.empty());
        var actual =
            reused.process(reused.restore(checkpoint), event, 1, AdmissionEvidence.empty());
        equivalent(expected, actual);
        if (!actual.applied()) {
          assertEquals(actual.before().blueId(), actual.after().blueId());
          assertTrue(actual.transition().emissions().isEmpty());
        }
        fallbacks += reused.calculationStatistics().budgetFallback();
        hits += reused.calculationStatistics().reused();
      }
    }
    assertTrue(fallbacks > 0);
    assertTrue(hits > 0);
  }

  @Test
  void memberLocalCapsPreserveExactRejectionAndSharedPrefix() {
    var checkpoint = initial();
    Map<String, Map<String, Object>> retained;
    List<String> members;
    long gas;
    try (var processor =
        Contracts.Execution.using(configuration, null).reuseCalculations(k -> Optional.empty())) {
      var before = processor.restore(checkpoint);
      members = before.members().stream().map(member -> member.key()).toList();
      gas =
          processor
              .process(before, event, 1, AdmissionEvidence.empty())
              .requireApplied()
              .transition()
              .gas();
      retained = serialized(processor.calculations());
    }
    for (String member : members) {
      for (long cap = 0; cap <= gas + 1; cap++) {
        var ordinary = new ContractProcessor(language, 10_000_000, Map.of(member, cap));
        var reused = new ContractProcessor(language, 10_000_000, Map.of(member, cap));
        reused.calculations = new ContractCalculations(k -> Optional.ofNullable(retained.get(k)));
        var expected =
            ordinary.process(
                ordinary.importSnapshot(checkpoint, (String) checkpoint.get("identity")),
                Map.of("kind", "tick"),
                1);
        var actual =
            reused.process(
                reused.importSnapshot(checkpoint, (String) checkpoint.get("identity")),
                Map.of("kind", "tick"),
                1);
        assertEquals(expected.observations(), actual.observations(), member + " cap=" + cap);
        assertEquals(expected.receipt(), actual.receipt());
      }
    }
  }

  @Test
  void changedCauseOrDocumentDoesNotReuseThePreviousCalculation() {
    var checkpoint = initial();
    Map<String, Map<String, Object>> retained;
    Map<String, Object> next;
    try (var processor =
        Contracts.Execution.using(configuration, null).reuseCalculations(k -> Optional.empty())) {
      next =
          processor
              .process(processor.restore(checkpoint), event, 1, AdmissionEvidence.empty())
              .requireApplied()
              .after()
              .checkpoint();
      retained = serialized(processor.calculations());
    }
    for (var before : List.of(checkpoint, next)) {
      try (var ordinary = Contracts.Execution.using(configuration, null);
          var reused =
              Contracts.Execution.using(configuration, null)
                  .reuseCalculations(k -> Optional.ofNullable(retained.get(k)))) {
        equivalent(
            ordinary.process(ordinary.restore(before), event, 2, AdmissionEvidence.empty()),
            reused.process(reused.restore(before), event, 2, AdmissionEvidence.empty()));
        assertEquals(0, reused.calculationStatistics().reused());
      }
    }
  }

  @Test
  void nativeFunctionMatchingFallsBackWithoutChangingAnyGasBoundary() {
    var source =
        language.defineYaml(
            """
            count: 0
            contracts:
              input: {type: Contracts/External Channel}
              update:
                type: Contracts/Sequential Workflow
                channel: input
                steps:
                  - type: Contracts/Compute
                    functions:
                      apply:
                        args: {request: {}}
                        do:
                          - $appendChange:
                              op: replace
                              path: /count
                              val: {$var: {name: request, path: /amount}}
                          - $appendEvent:
                              amount: {$var: {name: request, path: /amount}}
                    do:
                      - $call:
                          function: apply
                          args: {request: {$event: /data}}
            """);
    LanguageContent input = language.prepare(Language.Input.yaml("data: {amount: 7}"));
    Map<String, Object> checkpoint;
    Map<String, Map<String, Object>> retained;
    long gas;
    try (var processor =
        Contracts.Execution.using(configuration, null).reuseCalculations(k -> Optional.empty())) {
      checkpoint =
          processor
              .initialize(processor.prepare("root", source))
              .requireApplied()
              .after()
              .checkpoint();
      var result =
          processor
              .process(processor.restore(checkpoint), input, 1, AdmissionEvidence.empty())
              .requireApplied();
      assertEquals(7L, ((Number) result.after().read("/count")).longValue());
      gas = result.transition().gas();
      retained = serialized(processor.calculations());
      assertTrue(retained.isEmpty(), "Native matching keeps this calculation in the evaluator");
    }
    long reusedCount = 0;
    for (long limit = 0; limit <= gas + 1; limit++) {
      var bounded = Contracts.newBuilder().language(language).gasLimit(limit).build();
      try (var ordinary = Contracts.Execution.using(bounded, null);
          var cached =
              Contracts.Execution.using(bounded, null)
                  .reuseCalculations(k -> Optional.ofNullable(retained.get(k)))) {
        var expected =
            ordinary.process(ordinary.restore(checkpoint), input, 1, AdmissionEvidence.empty());
        var actual =
            cached.process(cached.restore(checkpoint), input, 1, AdmissionEvidence.empty());
        try {
          equivalent(expected, actual);
        } catch (AssertionError error) {
          throw new AssertionError(
              "limit="
                  + limit
                  + " expected="
                  + ContractWire.write(expected.transition().observations())
                  + " actual="
                  + ContractWire.write(actual.transition().observations()),
              error);
        }
        reusedCount += cached.calculationStatistics().reused();
      }
    }
    assertEquals(0, reusedCount);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Map<String, Object>> serialized(
      Map<String, Map<String, Object>> values) {
    return (Map<String, Map<String, Object>>)
        (Map<?, ?>) ContractWire.json(ContractWire.write(values));
  }

  private static void equivalent(
      Contracts.Execution.Outcome expected, Contracts.Execution.Outcome actual) {
    assertEquals(expected.after().blueId(), actual.after().blueId());
    assertEquals(expected.transition().gas(), actual.transition().gas());
    assertEquals(
        ContractWire.write(expected.transition().observations()),
        ContractWire.write(actual.transition().observations()));
    assertEquals(expected.transition().receipt(), actual.transition().receipt());
  }
}
