package blue.contracts;

import static org.junit.jupiter.api.Assertions.*;

import blue.Contracts;
import blue.Language;
import blue.LanguageDocument;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Projected values expand repeated branches but stop references to an active ancestor. */
@Tag("contracts")
class ProjectionTest {
  @Test
  void exactEmissionsRetainContentAndMemberOwnershipAfterSessionCloses() {
    List<Contracts.Emission> emitted;
    String root;
    try (Contracts.Session session =
        Contracts.create()
            .openYaml(
                """
                count: 0
                contracts:
                  input: {type: Contracts/External Channel}
                  act:
                    type: Contracts/Sequential Workflow
                    channel: input
                    steps:
                      - type: Contracts/Trigger Event
                        event: {kind: Tick, amount: 7}
                """)) {
      root = session.rootMember();
      assertEquals(root, session.graph().root());
      session.initialize().requireApplied();
      emitted = session.process(Map.of("message", "go"), 1).requireApplied().emissions();
    }
    assertEquals(1, emitted.size());
    assertEquals(root, emitted.getFirst().member());
    assertEquals("Tick", emitted.getFirst().event().at("/kind").text());
    var records = emitted.getFirst().event().records();
    var restored =
        Language.newBuilder()
            .provider(id -> java.util.Optional.ofNullable(records.get(id)))
            .build()
            .read(emitted.getFirst().event().blueId());
    assertEquals(7, restored.at("/amount").integer());
  }

  @Test
  void rolledBackInvocationDoesNotPublishTentativeEmissions() {
    try (var session =
        Contracts.create()
            .openYaml(
                """
                count: {type: Integer, value: 0}
                contracts:
                  input: {type: Contracts/External Channel}
                  fail:
                    type: Contracts/Sequential Workflow
                    channel: input
                    steps:
                      - type: Contracts/Trigger Event
                        event: {kind: Tentative}
                      - type: Contracts/Update Document
                        changeset: [{op: replace, path: /missing/child, val: 1}]
                """)) {
      session.initialize().requireApplied();
      var before = session.blueId();
      var result = session.process(Map.of("message", "go"), 1);
      assertFalse(result.applied());
      assertEquals(before, session.blueId());
      assertTrue(result.events().isEmpty());
      assertTrue(result.emissions().isEmpty());
    }
  }

  @Test
  void equalSiblingSubtreesAreEachProjectedInObjectsAndLists() throws Exception {
    Map<String, Object> leaf = Map.of("amount", 42);
    Map<String, Object> branch = Map.of("next", leaf, "also", leaf);
    Map<String, Object> source =
        Map.of("left", branch, "right", branch, "rows", List.of(branch, branch));
    Map<String, Object> projectedLeaf = Map.of("amount", BigInteger.valueOf(42));
    Map<String, Object> projectedBranch = Map.of("next", projectedLeaf, "also", projectedLeaf);
    Object expected =
        Map.of(
            "left",
            projectedBranch,
            "right",
            projectedBranch,
            "rows",
            List.of(projectedBranch, projectedBranch));
    try (Contracts.Session session =
        Contracts.create()
            .open(
                Language.create()
                    .readJson(
                        new com.fasterxml.jackson.databind.ObjectMapper()
                            .writeValueAsString(source)))) {
      assertEquals(expected, session.read(""));
      assertEquals(expected, session.read(""));
      assertEquals(projectedLeaf, session.read("/right/next"));
    }
  }

  @Test
  void ancestorCycleIsCollapsedToItsExactReference() {
    Language language = Language.create();
    LanguageDocument loop =
        language
            .finalizeSet(
                List.of(
                    Language.Input.exactJson(
                        "{\"name\":\"Loop\",\"self\":{\"blueId\":\"this#0\"}}")),
                Language.Goal.VALUE)
            .members()
            .getFirst();
    try (Contracts.Session session = Contracts.create().open(loop)) {
      Map<String, Object> expected =
          Map.of("name", "Loop", "self", Map.of("blueId", loop.blueId().value()));
      assertEquals(expected, session.read(""));
      assertEquals(expected, session.read("/self/self"));
    }
  }
}
