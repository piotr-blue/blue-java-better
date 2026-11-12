package blue;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Protected attachment evidence avoids body reads but never certifies a different graph cut. */
@SuppressWarnings("unchecked")
class LocalBindingRecoveryTest {
  @ParameterizedTest
  @ValueSource(ints = {1, 32, 1000})
  void recoveryDoesNotReopenUnchangedBodies(int count) {
    var fixture = new Fixture(count);
    assertInstanceOf(String.class, fixture.root.get("bindings"));
    assertTrue(
        ((String) fixture.root.get("bindings")).length() <= 128,
        "Binding commitment must have fixed size");
    try (var recovery = fixture.recovery(true)) {
      var restored = recovery.restoreLocalGraph(fixture.root, fixture.members, fixture.edges);
      assertEquals(fixture.initial.identity(), restored.identity());
      assertEquals(fixture.initial.blueId(), restored.blueId());
    }
  }

  @Test
  void aDifferentHistoricalAttachmentIsRejected() {
    var fixture = new Fixture(3);
    var edge =
        fixture.edges.stream().filter(e -> e.target().equals("child")).findFirst().orElseThrow();
    for (var replacement :
        List.of(
            new Contracts.Embedding(edge.source(), edge.path(), "quiet1", edge.generation()),
            new Contracts.Embedding(
                edge.source(), edge.path(), edge.target(), edge.generation() + 1),
            new Contracts.Embedding(
                edge.source(), "/children/invented", edge.target(), edge.generation()),
            new Contracts.Embedding(edge.source(), edge.path(), "missing", edge.generation()))) {
      var changed = new ArrayList<>(fixture.edges);
      changed.set(changed.indexOf(edge), replacement);
      try (var recovery = fixture.recovery(false)) {
        assertThrows(
            BlueException.class,
            () -> recovery.restoreLocalGraph(fixture.root, fixture.members, changed));
      }
    }
    var missing = new ArrayList<>(fixture.edges);
    missing.remove(edge);
    try (var recovery = fixture.recovery(false)) {
      assertThrows(
          BlueException.class,
          () -> recovery.restoreLocalGraph(fixture.root, fixture.members, missing));
    }
  }

  @Test
  void legacyCheckpointStillUsesOrdinaryEdgeValidation() {
    var fixture = new Fixture(3);
    var members = fixture.members.stream().map(LocalBindingRecoveryTest::legacy).toList();
    var root = members.stream().filter(m -> "root".equals(m.get("root"))).findFirst().orElseThrow();
    try (var recovery = fixture.recovery(false)) {
      assertEquals(
          fixture.initial.identity(),
          recovery.restoreLocalGraph(root, members, fixture.edges).identity());
    }
    try (var recovery = fixture.recovery(false)) {
      assertThrows(BlueException.class, () -> recovery.restoreLocalGraph(root, members, List.of()));
    }
  }

  @Test
  void corruptedBindingEvidenceCannotPassTheCheckpointCommitment() {
    var fixture = new Fixture(3);
    var changed = new LinkedHashMap<>(fixture.root);
    changed.put("bindings", Map.of());
    var members = new ArrayList<>(fixture.members);
    members.set(members.indexOf(fixture.root), changed);
    try (var recovery = fixture.recovery(false)) {
      assertThrows(
          BlueException.class, () -> recovery.restoreLocalGraph(changed, members, fixture.edges));
    }
  }

  private static Map<String, Object> legacy(Map<String, Object> record) {
    var copy = new LinkedHashMap<>(record);
    copy.remove("bindings");
    copy.remove("identity");
    copy.put("identity", ContractModel.digest("contracts.local-state/1", copy));
    return copy;
  }

  private static final class Fixture {
    final Contracts.Execution.State initial;
    final List<Map<String, Object>> members = new ArrayList<>();
    final List<Contracts.Embedding> edges;
    final Map<String, Object> root;
    final Map<String, Object> records;
    final Set<String> protectedBodies = new HashSet<>();

    Fixture(int count) {
      var sources = ContractsFixtures.members(count);
      ContractModel.obj(
              ContractModel.obj(ContractModel.obj(sources.get("child")).get("contracts"))
                  .get("input"))
          .remove("event");
      var engine =
          new ContractProcessor(
              Language.create(), ContractBindings.core().withGasSchedule(GasSchedule.STANDARD));
      var admitted = engine.admit(sources, "root");
      assertEquals("APPLIED", admitted.status());
      var state = admitted.document().state;
      var full = engine.exportSnapshot(admitted.document());
      records = ContractModel.obj(full.get("content"));
      for (String key : state.documents.keySet()) {
        var selected = state.copy();
        selected.root = key;
        members.add(ContractLocalCheckpoint.checkpoint(engine, selected));
        protectedBodies.add(state.documents.get(key).blueId);
      }
      root = members.stream().filter(m -> "root".equals(m.get("root"))).findFirst().orElseThrow();
      edges =
          state.edges.values().stream()
              .map(e -> new Contracts.Embedding(e.source(), e.path(), e.target(), e.generation()))
              .toList();
      try (var ordinary = recovery(false)) {
        initial = ordinary.restore(full);
      }
    }

    Contracts.Execution recovery(boolean forbidBodies) {
      var language =
          Language.newBuilder()
              .provider(
                  id -> {
                    if (forbidBodies)
                      assertFalse(
                          protectedBodies.contains(id.value()),
                          "Structural recovery reopened a protected owner body: " + id);
                    return Optional.ofNullable(records.get(id.value()))
                        .map(raw -> new LanguageRecord(ContractModel.obj(raw)));
                  })
              .build();
      return Contracts.Execution.using(
          Contracts.newBuilder().language(language).build(), null, key -> Optional.empty());
    }
  }
}
