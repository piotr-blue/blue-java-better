package blue;

import static blue.ContractsFixtures.members;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Accounting laws shared by inline, retained-child and owner-local execution. */
@Tag("contracts")
class GasCompositionTest {
  private static Map<String, Object> yaml(String text) {
    return new org.yaml.snakeyaml.Yaml().load(text);
  }

  private static ContractProcessor processor(long limit) {
    return new ContractProcessor(
        Language.create(),
        ContractBindings.core().withGasSchedule(GasSchedule.STANDARD),
        limit,
        Map.of());
  }

  @Test
  void quietSiblingsContributeNoGas() {
    long expected = -1;
    for (int count : List.of(1, 10, 1000)) {
      ContractProcessor engine = processor(10_000_000);
      var before = engine.admit(members(count), "root").document();
      var result = engine.process(before, Map.of("kind", "tick"), 1);
      assertEquals("APPLIED", result.status(), result.observations().toString());
      assertEquals(1L, ((Number) engine.read(result.document(), "/count")).longValue());
      if (expected < 0) {
        expected = result.gas();
      }
      assertEquals(expected, result.gas(), "Sibling count " + count);
      String previous = System.getProperty("blue.physical.sparseScheduleDisabled");
      try {
        System.setProperty("blue.physical.sparseScheduleDisabled", "true");
        var ordinary = engine.process(before, Map.of("kind", "tick"), 1);
        assertEquals(ordinary.observations(), result.observations());
        assertEquals(ordinary.receipt(), result.receipt());
      } finally {
        if (previous == null) {
          System.clearProperty("blue.physical.sparseScheduleDisabled");
        } else {
          System.setProperty("blue.physical.sparseScheduleDisabled", previous);
        }
      }
    }
  }

  @Test
  void sparseLocalExecutionPreservesTheExistingPortableChunkSequenceAndReceipt() {
    var engine = processor(10_000_000);
    var before = engine.admit(members(10), "root").document();
    var expected = engine.process(before, Map.of("kind", "tick"), 1);
    for (boolean portable : List.of(false, true)) {
      var attempt = engine.begin(before, Map.of("kind", "tick"), 1);
      ContractAttempt.ChunkToken token;
      int chunks = 0;
      while ((token = attempt.nextChunk()) != null) {
        var result =
            portable ? processor(10_000_000).executeChunk(token.toData()) : attempt.execute(token);
        attempt.accept(token, result);
        chunks++;
      }
      var actual = attempt.finish();
      assertEquals(
          2 * before.state.documents.size(),
          chunks,
          "Portable INITIALIZE and PROCESS chunks keep their original unit sequence");
      assertEquals(expected.observations(), actual.observations());
      assertEquals(expected.receipt(), actual.receipt());
    }
  }

  @Test
  void childPlusParentEqualsWholeRootAndKeepsEvents() {
    ContractProcessor engine = processor(10_000_000);
    var before = engine.admit(members(10), "root").document();
    var childState = before.state.copy();
    childState.root = "child";
    childState.documents.keySet().removeIf(k -> !k.equals("child"));
    childState.edges.clear();
    var child =
        engine.process(new ContractProcessor.Document(childState), Map.of("kind", "tick"), 1);
    var actual =
        engine.receiveLive(before, List.of(engine.capture(child, List.of("child"))), Map.of());
    var expected = engine.process(before, Map.of("kind", "tick"), 1);
    assertEquals("APPLIED", actual.status(), actual.observations().toString());
    assertEquals(expected.document().blueId(), actual.document().blueId());
    assertEquals(expected.publicEvents(), actual.publicEvents());
    assertEquals(expected.gas(), actual.gas());
    assertEquals(
        expected.observations().get("gasByMember"), actual.observations().get("gasByMember"));
    assertTrue(actual.gas() > child.gas());
  }

  @Test
  void scheduleIsBoundToSnapshotsAndCannotSilentlyChange() {
    ContractProcessor engine = processor(10_000_000);
    var before = engine.admit(members(1), "root").document();
    var saved = engine.exportSnapshot(before);
    assertEquals(
        before.identity(),
        processor(10_000_000).importSnapshot(saved, before.identity()).identity());
    assertEquals(
        before.identity(),
        new ContractProcessor().importSnapshot(saved, before.identity()).identity());
    var obsolete = new LinkedHashMap<String, Object>(saved);
    var wire = new LinkedHashMap<String, Object>(ContractModel.obj(saved.get("snapshot")));
    wire.put("gasSchedule", "unsupported-history-policy");
    String changedIdentity = ContractModel.digest("contracts.snapshot/completion-1", wire);
    obsolete.put("snapshot", wire);
    obsolete.put("identity", changedIdentity);
    var failure =
        assertThrows(
            ContractModel.Error.class,
            () -> new ContractProcessor().importSnapshot(obsolete, changedIdentity));
    assertEquals("INCOMPATIBLE_GAS_SCHEDULE", failure.code);
  }

  @Test
  void exactLimitSucceedsAndOneLessRollsBack() {
    var engine = processor(10_000_000);
    var before = engine.admit(members(10), "root").document();
    var full = engine.process(before, Map.of("kind", "tick"), 1);
    var saved = engine.exportSnapshot(before);
    for (long limit : List.of(full.gas(), full.gas() - 1, 0L)) {
      var limited = processor(limit);
      var restored = limited.importSnapshot(saved, before.identity());
      var result = limited.process(restored, Map.of("kind", "tick"), 1);
      assertEquals(limit == full.gas() ? "APPLIED" : "GAS_LIMIT_EXCEEDED", result.status());
      if (limit < full.gas()) {
        assertEquals(before.identity(), result.document().identity());
      }
    }
  }

  @Test
  void overflowCannotWrapACharge() {
    var meter = new ContractModel.Meter(10_000_000);
    assertThrows(
        BexFailure.class, () -> meter.charge("integerWork", Long.MAX_VALUE, Long.MAX_VALUE));
    assertEquals(0, meter.used());
    var multiply = new BexGas(100);
    assertThrows(BexFailure.class, () -> multiply.charge("functionCall", Long.MAX_VALUE));
    assertEquals(0, multiply.used());
  }

  @Test
  void inlineAndCollapsedBodiesHaveEqualGasAndResults() {
    var engine = processor(10_000_000);
    Object source = members(1).get("child");
    var node = engine.blue.source(source);
    engine.blue.remember(node);
    var inline = engine.admit(Map.of("root", source), "root");
    var collapsed =
        engine.admit(Map.of("root", Map.of("blueId", ContractLanguage.identity(node))), "root");
    assertEquals("APPLIED", inline.status());
    assertEquals("APPLIED", collapsed.status());
    assertEquals(inline.gas(), collapsed.gas());
    assertEquals(inline.document().blueId(), collapsed.document().blueId());
    var first = engine.process(inline.document(), Map.of("kind", "tick"), 1);
    var restored = processor(10_000_000);
    var second =
        restored.process(
            restored.importSnapshot(
                engine.exportSnapshot(collapsed.document()), collapsed.document().identity()),
            Map.of("kind", "tick"),
            1);
    assertEquals(first.receipt(), second.receipt());
  }

  @Test
  void portableInvocationRetainsTheAccountingSchedule() {
    var engine = processor(10_000_000);
    var initial = engine.admit(members(3), "root").document();
    var capsule = engine.exportInvocation(initial, Map.of("kind", "tick"), 1);
    var restored = processor(10_000_000);
    var result =
        restored.process(restored.importInvocation(capsule, (String) capsule.get("identity")));
    assertEquals(engine.process(initial, Map.of("kind", "tick"), 1).receipt(), result.receipt());
  }

  @Test
  void overflowingNativeAdmissionCostCannotBecomeABusinessGasFailure() {
    var meter = new ContractModel.Meter(100);
    meter.schedule = GasSchedule.STANDARD;
    String identity = Language.create().readYaml("description: Admission test").blueId().value();
    var binding =
        new ContractNative.Binding(
            identity,
            identity,
            "external-channel",
            List.of(),
            List.of(),
            "test",
            Map.of("check", ContractModel.SAFE),
            false,
            List.of(),
            "live");
    var runtime = new ContractNative.RuntimeMeter(meter, binding, "root", true);
    var failure =
        assertThrows(ContractModel.Error.class, () -> runtime.charge("check", Long.MAX_VALUE));
    assertEquals("HOST_INCOMPLETE", failure.code);
    assertEquals(0, meter.used());
    assertEquals(0, meter.hostUsed);
  }

  @Test
  void admissionWorkExhaustionIsRetryableNotSemanticFailure() {
    var meter = new ContractModel.Meter(10);
    meter.hostDebit(ContractModel.HOST_LIMIT);
    var failure = assertThrows(ContractModel.Error.class, () -> meter.hostDebit(1));
    assertEquals("HOST_INCOMPLETE", failure.code);
    assertEquals(0, meter.used());
  }

  @Test
  void inheritedDeclarationsDoNotChargeForTypeExpansion() {
    var engine = processor(10_000_000);
    Object source = members(1).get("child");
    var type = engine.blue.source(Map.of("contracts", ((Map<?, ?>) source).get("contracts")));
    engine.blue.remember(type);
    var plain = engine.admit(Map.of("root", source), "root");
    var inherited =
        engine.admit(
            Map.of(
                "root",
                Map.of("count", 0, "type", Map.of("blueId", ContractLanguage.identity(type)))),
            "root");
    assertEquals("APPLIED", inherited.status(), inherited.observations().toString());
    assertEquals(plain.gas(), inherited.gas());
    assertEquals(
        engine.process(plain.document(), Map.of("kind", "tick"), 1).gas(),
        engine.process(inherited.document(), Map.of("kind", "tick"), 1).gas());
  }

  @Test
  void realFanoutStillCostsGasForEveryExecutedDocument() {
    long previous = 0;
    for (int count : List.of(1, 3)) {
      var engine = processor(10_000_000);
      var graph = members(count);
      for (int i = 1; i < count; i++) {
        graph.put("quiet" + i, graph.get("child"));
      }
      var initialized = engine.admit(graph, "root");
      assertEquals("APPLIED", initialized.status());
      var result = engine.process(initialized.document(), Map.of("kind", "tick"), 1);
      assertEquals("APPLIED", result.status());
      assertEquals(count, ((Number) engine.read(result.document(), "/count")).intValue());
      assertTrue(result.gas() > previous);
      previous = result.gas();
    }
  }

  @Test
  void receivingPreservesBudgetThresholdAndFirstRejectedCharge() {
    var creator = processor(10_000_000);
    var initial = creator.admit(members(3), "root").document();
    var childInput = initial.state.copy();
    childInput.root = "child";
    childInput.documents.keySet().removeIf(k -> !k.equals("child"));
    childInput.edges.clear();
    var childBefore = new ContractProcessor.Document(childInput);
    var child = creator.process(childBefore, Map.of("kind", "tick"), 1);
    var full = creator.process(initial, Map.of("kind", "tick"), 1);
    for (long cap : List.of(0L, child.gas() - 1, child.gas(), full.gas() - 1, full.gas())) {
      var engine = processor(cap);
      engine.blue.context.addEvidence(creator.blue.context.evidence());
      var before = engine.importSnapshot(creator.exportSnapshot(initial), initial.identity());
      engine.installCompletionReader(
          (processor, receipt) ->
              new ContractProcessor.CompletedRecord(
                  processor.importSnapshot(
                      creator.exportSnapshot(childBefore), childBefore.identity()),
                  processor.importSnapshot(
                      creator.exportSnapshot(child.document()), child.document().identity()),
                  child.observations(),
                  child.receipt(),
                  ContractLanguage.identity(creator.blue.source(Map.of("kind", "tick"))),
                  1L));
      var expected = engine.process(before, Map.of("kind", "tick"), 1);
      var source = engine.loadSourceCompletion(child.receipt(), List.of("child"));
      var actual = engine.receiveLive(before, List.of(source), Map.of());
      assertEquals(expected.status(), actual.status(), "cap=" + cap);
      assertEquals(expected.gas(), actual.gas(), "cap=" + cap);
      assertEquals(expected.document().blueId(), actual.document().blueId(), "cap=" + cap);
      assertEquals(
          expected.observations().get("rejected"),
          actual.observations().get("rejected"),
          "cap=" + cap);
    }
  }

  @Test
  void failedChildCannotBeAdoptedAsSuccessfulWork() {
    var engine = processor(10_000_000);
    var graph = members(3);
    graph.put(
        "child",
        yaml(
            """
            count: 0
            contracts:
              input: {type: Contracts/External Channel, event: {kind: tick}}
              run:
                type: Contracts/Sequential Workflow
                channel: input
                steps:
                  - type: Contracts/Compute
                    do: [{$fail: rejected}]
            """));
    var initial = engine.admit(graph, "root").document();
    var childInput = initial.state.copy();
    childInput.root = "child";
    childInput.documents.keySet().removeIf(k -> !k.equals("child"));
    childInput.edges.clear();
    var child =
        engine.process(new ContractProcessor.Document(childInput), Map.of("kind", "tick"), 1);
    assertEquals("FAILED", child.status());
    var failure =
        assertThrows(ContractModel.Error.class, () -> engine.capture(child, List.of("child")));
    assertEquals("UNESTABLISHED_SOURCE_COMPLETION", failure.code);
  }

  @Test
  @org.junit.jupiter.api.Timeout(10)
  void cyclicFeedbackExhaustsGasAndRollsBackInsteadOfRunningForever() {
    var engine = processor(500);
    Map<String, Object> graph = new LinkedHashMap<>();
    for (String key : List.of("a", "b", "c", "d")) {
      var member =
          yaml(
              """
              count: 0
              contracts:
                input: {type: Contracts/External Channel, event: {kind: tick}}
                embedded: {type: Contracts/Process Embedded, paths: [/child]}
                events: {type: Contracts/Embedded Node Channel, event: {kind: tick}}
                start:
                  type: Contracts/Sequential Workflow
                  channel: input
                  steps: [{type: Contracts/Trigger Event, event: {kind: tick}}]
                react:
                  type: Contracts/Sequential Workflow
                  channel: events
                  steps:
                    - type: Contracts/Update Document
                      changeset: [{op: replace, path: /count, val: {$add: [{$document: /count}, 1]}}]
                    - type: Contracts/Trigger Event
                      event: {kind: tick}
              """);
      member.put("key", key);
      member.put(
          "child",
          ContractProcessor.link(
              key.equals("d") ? "a" : String.valueOf((char) (key.charAt(0) + 1))));
      graph.put(key, member);
    }
    var initialization = engine.admit(graph, "a");
    assertEquals("APPLIED", initialization.status());
    var initial = initialization.document();
    var result = engine.process(initial, Map.of("kind", "tick"), 1);
    assertEquals("GAS_LIMIT_EXCEEDED", result.status());
    assertTrue(result.gas() <= 500);
    assertEquals(initial.identity(), result.document().identity());
    assertTrue(result.publicEvents().isEmpty());
  }

  @Test
  void persistedCutHasIdenticalMemberCapFailureAndNoBudgetReset() {
    var creator = processor(10_000_000);
    var initial = creator.admit(members(10), "root").document();
    var childInput = initial.state.copy();
    childInput.root = "child";
    childInput.documents.keySet().removeIf(k -> !k.equals("child"));
    childInput.edges.clear();
    var childBefore = new ContractProcessor.Document(childInput);
    var child = creator.process(childBefore, Map.of("kind", "tick"), 1);
    var whole = creator.process(initial, Map.of("kind", "tick"), 1);
    for (String owner : List.of("child", "root")) {
      long own =
          ((Number) ((Map<?, ?>) whole.observations().get("gasByMember")).get(owner)).longValue();
      for (long cap : List.of(0L, own - 1, own, own + 1)) {
        var engine =
            new ContractProcessor(
                Language.create(),
                ContractBindings.core().withGasSchedule(GasSchedule.STANDARD),
                10_000_000,
                Map.of(owner, cap));
        engine.blue.context.addEvidence(creator.blue.context.evidence());
        var before = engine.importSnapshot(creator.exportSnapshot(initial), initial.identity());
        engine.installCompletionReader(
            (processor, receipt) ->
                new ContractProcessor.CompletedRecord(
                    processor.importSnapshot(
                        creator.exportSnapshot(childBefore), childBefore.identity()),
                    processor.importSnapshot(
                        creator.exportSnapshot(child.document()), child.document().identity()),
                    child.observations(),
                    child.receipt(),
                    ContractLanguage.identity(creator.blue.source(Map.of("kind", "tick"))),
                    1L));
        var actual =
            engine.receiveLive(
                before,
                List.of(engine.loadSourceCompletion(child.receipt(), List.of("child"))),
                Map.of());
        var expected = engine.process(before, Map.of("kind", "tick"), 1);
        assertEquals(expected.status(), actual.status());
        assertEquals(expected.gas(), actual.gas());
        assertEquals(
            expected.observations().get("rejected"), actual.observations().get("rejected"));
        assertEquals(expected.document().blueId(), actual.document().blueId());
        assertEquals(expected.publicEvents(), actual.publicEvents());
      }
    }
  }

  @Test
  void affectedAttachmentLookupMatchesTheFullScan() {
    var engine = processor(10_000_000);
    var before = engine.admit(members(100), "root").document();
    var indexed = engine.process(before, Map.of("kind", "tick"), 1);
    System.setProperty("blue.physical.inputsFullScan", "true");
    System.setProperty("blue.physical.fullPatch", "true");
    try {
      var full = engine.process(before, Map.of("kind", "tick"), 1);
      assertEquals(full.observations(), indexed.observations());
    } finally {
      System.clearProperty("blue.physical.inputsFullScan");
      System.clearProperty("blue.physical.fullPatch");
    }
  }
}
