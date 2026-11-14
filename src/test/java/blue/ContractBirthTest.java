package blue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Cross-check local fresh-child execution against trusted whole-graph publication evidence. */
class ContractBirthTest {
  private static final String SOURCE =
      """
      orders: {}
      contracts:
        input: {type: Contracts/External Channel}
        embedded: {type: Contracts/Process Embedded, collectionPaths: [/orders]}
        place:
          type: Contracts/Sequential Workflow
          channel: input
          steps:
            - type: Contracts/Update Document
              changeset:
                - {op: add, path: /orders/first, val: {count: 1}}
      """;

  @Test
  void oneFreshOrderHasTheSameFinalIdentityAndGasInBothExecutionModes() {
    try (var execution =
        Contracts.Execution.using(Contracts.create(), null, key -> Optional.empty())) {
      var source = ContractsFixtures.yaml(SOURCE);
      var before =
          execution
              .initialize(execution.prepare(Map.of("owner", source), "owner"))
              .requireApplied()
              .after();
      var entry = Language.create().readYaml("kind: Place Order");
      var whole = execution.process(before, entry, 10, AdmissionEvidence.empty()).requireApplied();
      var local =
          execution
              .processLocal(
                  before.localCheckpoint(),
                  List.of(),
                  entry,
                  10,
                  AdmissionEvidence.empty(),
                  id -> null)
              .requireApplied();
      assertEquals(whole.after().blueId(), local.after().blueId());
      assertEquals(whole.transition().gas(), local.transition().gas());
      assertEquals(whole.after().embeddings(), local.after().embeddings());
      var restored =
          execution
              .processRestoredLocal(
                  execution.restoreLocal(before.localCheckpoint()),
                  List.of(),
                  entry,
                  10,
                  AdmissionEvidence.empty(),
                  id -> null)
              .requireApplied();
      assertEquals(local.after().blueId(), restored.after().blueId());
      assertEquals(local.transition().receipt(), restored.transition().receipt());
      assertEquals(local.after().embeddings(), restored.after().embeddings());
      var child = whole.after().embeddings().getFirst().target();
      assertEquals(whole.after().rooted(child).blueId(), local.after().rooted(child).blueId());
      var proof = execution.singleBirthCompletion(whole);
      assertEquals("contracts.single-birth/1", proof.get("kind"));
      var birth = ContractModel.obj(proof.get("birth"));
      var leaf = ContractModel.obj(proof.get("leaf"));
      assertEquals(leaf.get("root"), birth.get("member"));
      assertEquals(whole.transition().receipt(), proof.get("receipt"));
      var observations = whole.transition().observations();
      assertTrue(ContractBirth.canAdopt(proof, observations, "parent", "source"));
      assertFalse(
          ContractBirth.canAdopt(proof, observations, "parent", (String) birth.get("member")));
      var forged = new HashMap<>(proof);
      forged.put("receipt", "unrelated");
      assertFalse(ContractBirth.canAdopt(forged, observations, "parent", "source"));
      assertEquals(Map.of(), execution.singleBirthCompletion(local));
    }
  }

  @Test
  void ambiguousOrPreexistingTargetRequiresWholeGraphResolution() {
    try (var execution =
        Contracts.Execution.using(Contracts.create(), null, key -> Optional.empty())) {
      var before =
          execution
              .initialize(
                  execution.prepare(Map.of("owner", ContractsFixtures.yaml(SOURCE)), "owner"))
              .requireApplied()
              .after();
      var entry = Language.create().readYaml("kind: Place Order");
      var checkpoint = before.localCheckpoint();
      var rejected =
          assertThrows(
              BlueException.class,
              () ->
                  execution.processLocal(
                      checkpoint,
                      List.of(),
                      entry,
                      10,
                      AdmissionEvidence.empty(),
                      id -> checkpoint));
      assertEquals("LOCAL_EXECUTION_UNAVAILABLE", rejected.code());
      assertEquals(0, before.embeddings().size());
      assertTrue(execution.process(before, entry, 10, AdmissionEvidence.empty()).applied());
    }
  }
}
