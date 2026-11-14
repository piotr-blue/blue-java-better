package blue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** One restored predecessor serves admission and execution without losing its trust boundary. */
class RestoredLocalExecutionTest {
  private static final String COUNTER =
      """
      count: 0
      contracts:
        input: {type: Contracts/External Channel}
        increment:
          type: Contracts/Sequential Workflow
          channel: input
          steps:
            - type: Contracts/Update Document
              changeset:
                - {op: replace, path: /count, val: "${document('/count') + 1}"}
      """;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void restoredRuntimeBindingsOwnTheirNestedTariff(boolean local) {
    var language = Language.newBuilder().mount("Contracts", Contracts.publication()).build();
    var type = language.defineYaml("type: Contracts/Contract");
    var semantics = language.readYaml("description: Test immutable runtime ownership");
    var runtime =
        NativeStep.newBuilder(type, semantics, "ownership")
            .counter("work", 1)
            .execute(context -> {})
            .build();
    var configuration = Contracts.newBuilder().nativeStep(runtime).build();
    try (var execution = Contracts.Execution.using(configuration, null, key -> Optional.empty())) {
      var before =
          execution
              .initialize(
                  execution.prepare(Map.of("owner", ContractsFixtures.yaml(COUNTER)), "owner"))
              .requireApplied()
              .after();
      var caller =
          ContractModel.obj(
              ContractWire.copy(local ? before.localCheckpoint() : before.checkpoint()));
      var restored = local ? execution.restoreLocal(caller) : execution.restore(caller);
      var expected = restored.localCheckpoint();
      var metadata = local ? caller : ContractModel.obj(caller.get("snapshot"));
      var binding = ContractModel.obj(ContractModel.arr(metadata.get("runtimes")).getFirst());
      ContractModel.arr(ContractModel.arr(binding.get("tariff")).getFirst()).set(1, 999L);
      assertEquals(
          expected,
          restored.localCheckpoint(),
          "Caller mutation cannot change a restored runtime or its checkpoint identity");
    }
  }

  @Test
  void restoredIdentityAndStateRemainFixedWhenTheCheckpointMapChanges() {
    try (var execution =
        Contracts.Execution.using(Contracts.create(), null, key -> Optional.empty())) {
      var before =
          execution
              .initialize(
                  execution.prepare(Map.of("owner", ContractsFixtures.yaml(COUNTER)), "owner"))
              .requireApplied()
              .after();
      var checkpoint = before.localCheckpoint();
      var callerMap = new LinkedHashMap<>(checkpoint);
      var callerRuntimes = new ArrayList<>(ContractModel.arr(checkpoint.get("runtimes")));
      callerMap.put("runtimes", callerRuntimes);
      var restored = execution.restoreLocal(callerMap);
      var entry = Language.create().readYaml("kind: Increment");
      callerMap.put("identity", "changed after restore");
      callerMap.put("revision", 999L);
      callerRuntimes.add(Map.of("changed", true));
      assertEquals(checkpoint, restored.localCheckpoint());
      var whole = execution.process(before, entry, 1, AdmissionEvidence.empty()).requireApplied();
      var mapped =
          execution
              .processLocal(checkpoint, List.of(), entry, 1, AdmissionEvidence.empty(), null)
              .requireApplied();
      for (int attempt = 0; attempt < 2; attempt++) {
        var actual =
            execution
                .processRestoredLocal(
                    restored, List.of(), entry, 1, AdmissionEvidence.empty(), null)
                .requireApplied();
        assertSame(restored, actual.before());
        assertEquals(whole.after().blueId(), actual.after().blueId());
        assertEquals(whole.transition().gas(), actual.transition().gas());
        assertEquals(mapped.transition().receipt(), actual.transition().receipt());
        assertEquals(mapped.transition().observations(), actual.transition().observations());
        assertEquals(checkpoint, restored.localCheckpoint());
      }
      assertThrows(BlueException.class, () -> execution.restoreLocal(callerMap));
    }
  }

  @Test
  void onlyAnExplicitLocalRestoreFromThisOpenAttemptCanBeProcessed() {
    var event = Language.create().readYaml("kind: Increment");
    Contracts.Execution.State retained;
    var execution = Contracts.Execution.using(Contracts.create(), null, key -> Optional.empty());
    try (execution;
        var other = Contracts.Execution.using(Contracts.create(), null, key -> Optional.empty())) {
      var ordinary =
          execution
              .initialize(
                  execution.prepare(Map.of("owner", ContractsFixtures.yaml(COUNTER)), "owner"))
              .requireApplied()
              .after();
      assertThrows(
          IllegalArgumentException.class,
          () ->
              execution.processRestoredLocal(
                  ordinary, List.of(), event, 1, AdmissionEvidence.empty(), null));
      retained = execution.restoreLocal(ordinary.localCheckpoint());
      assertThrows(
          IllegalArgumentException.class,
          () ->
              other.processRestoredLocal(
                  retained, List.of(), event, 1, AdmissionEvidence.empty(), null));
      var after =
          execution
              .processRestoredLocal(retained, List.of(), event, 1, AdmissionEvidence.empty(), null)
              .requireApplied()
              .after();
      assertThrows(
          IllegalArgumentException.class,
          () ->
              execution.processRestoredLocal(
                  after, List.of(), event, 2, AdmissionEvidence.empty(), null));
    }
    assertThrows(
        IllegalStateException.class,
        () ->
            execution.processRestoredLocal(
                retained, List.of(), event, 1, AdmissionEvidence.empty(), null));
  }

  @Test
  void childCompletionsStillRequireTheSelectedGenerationAndMatchWholeGraphExecution() {
    try (var execution =
        Contracts.Execution.using(Contracts.create(), null, key -> Optional.empty())) {
      var members = ContractsFixtures.members(20);
      ContractModel.obj(
              ContractModel.obj(ContractModel.obj(members.get("child")).get("contracts"))
                  .get("input"))
          .remove("event");
      var before =
          execution.initialize(execution.prepare(members, "root")).requireApplied().after();
      var checkpoint = before.localCheckpoint();
      var childCheckpoint = before.rooted("child").localCheckpoint();
      var event = Language.create().readYaml("kind: tick");
      var child =
          execution
              .processRestoredLocal(
                  execution.restoreLocal(childCheckpoint),
                  List.of(),
                  event,
                  1,
                  AdmissionEvidence.empty(),
                  null)
              .requireApplied();
      var completion =
          Map.<String, Object>of(
              "before", childCheckpoint,
              "after", child.after().localCheckpoint(),
              "observations", child.transition().observations(),
              "event", event.blueId().value(),
              "timestamp", 1L);
      var restored = execution.restoreLocal(checkpoint);
      assertEquals(
          "LOCAL_EXECUTION_UNAVAILABLE",
          assertThrows(
                  BlueException.class,
                  () ->
                      execution.processRestoredLocal(
                          restored,
                          List.of(
                              Map.of(
                                  "path",
                                  "/children/active",
                                  "generation",
                                  2L,
                                  "completion",
                                  completion)),
                          event,
                          1,
                          AdmissionEvidence.empty(),
                          null))
              .code());
      var attachments =
          List.of(
              Map.<String, Object>of(
                  "path", "/children/active", "generation", 1L, "completion", completion));
      var actual =
          execution
              .processRestoredLocal(
                  restored, attachments, event, 1, AdmissionEvidence.empty(), null)
              .requireApplied();
      var mapped =
          execution
              .processLocal(checkpoint, attachments, event, 1, AdmissionEvidence.empty(), null)
              .requireApplied();
      var whole = execution.process(before, event, 1, AdmissionEvidence.empty()).requireApplied();
      assertEquals(whole.after().blueId(), actual.after().blueId());
      assertEquals(whole.transition().gas(), actual.transition().gas());
      assertEquals(mapped.transition().observations(), actual.transition().observations());
      assertEquals(mapped.transition().receipt(), actual.transition().receipt());
    }
  }
}
