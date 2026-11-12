package blue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Execution locality and adversarial cut bindings; whole-graph semantics remain the oracle. */
@SuppressWarnings({"unchecked", "try"})
class LocalOwnerExecutionTest {
  private static ContractProcessor engine(Language language) {
    return new ContractProcessor(
        language, ContractBindings.core().withGasSchedule(GasSchedule.STANDARD));
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 20, 1000})
  void restartedOwnersNeverFetchUnchangedSiblingBodies(int siblings) {
    var fixture = fixture(siblings);
    Map<String, LanguageRecord> records = new HashMap<>();
    ((Map<String, Object>) fixture.creator.exportSnapshot(fixture.initial).get("content"))
        .forEach((id, raw) -> records.put(id, new LanguageRecord(ContractModel.obj(raw))));
    var forbidden = new HashSet<String>();
    fixture.initial.state.documents.forEach(
        (key, doc) -> {
          if (key.startsWith("quiet")) forbidden.add(doc.blueId);
        });
    Set<String> fetched = new HashSet<>();
    Language language =
        Language.newBuilder()
            .provider(
                id -> {
                  assertFalse(forbidden.contains(id.value()), "Opened an unrelated Order: " + id);
                  fetched.add(id.value());
                  return Optional.ofNullable(records.get(id.value()));
                })
            .build();
    Map<String, Object> root =
        ContractLocalCheckpoint.checkpoint(fixture.creator, fixture.initial.state);
    var childState = fixture.initial.state.copy();
    childState.root = "child";
    childState.documents.keySet().removeIf(k -> !k.equals("child"));
    childState.edges.clear();
    var child = ContractLocalCheckpoint.checkpoint(fixture.creator, childState);
    var event = fixture.creator.blue.source(Map.of("kind", "tick"));
    fixture
        .creator
        .blue
        .context
        .records(event)
        .forEach((id, raw) -> records.put(id, new LanguageRecord(ContractModel.obj(raw))));
    var facts = fixture.creator.blue.context.validationFacts(records::containsKey);
    var childEngine = engine(language);
    Map<String, Object> completion;
    try (var scope =
        childEngine.blue.context.validationScope(key -> Optional.ofNullable(facts.get(key)))) {
      var result = ContractLocal.process(childEngine, child, List.of(), event, 1);
      completion =
          Map.of(
              "before",
              child,
              "after",
              ContractLocalCheckpoint.checkpoint(childEngine, result.outcome().snapshot),
              "observations",
              result.outcome().observations(),
              "event",
              ContractLanguage.identity(event),
              "timestamp",
              1L);
      var roots = new ArrayList<ExactNode>();
      roots.add(result.outcome().snapshot.documents.get("child").body);
      for (Object raw : result.outcome().events)
        roots.add(new ExactNode.Ref((String) ContractModel.obj(raw).get("event")));
      childEngine
          .blue
          .context
          .records(roots, records::containsKey)
          .forEach((id, raw) -> records.put(id, new LanguageRecord(ContractModel.obj(raw))));
    }
    var parentEngine = engine(language);
    try (var scope =
        parentEngine.blue.context.validationScope(
            key -> {
              for (String id : forbidden)
                assertFalse(key.endsWith("/" + id), "Queried sibling metadata: " + key);
              return Optional.ofNullable(facts.get(key));
            })) {
      var result =
          ContractLocal.process(
              parentEngine,
              root,
              List.of(
                  Map.of("path", "/children/active", "generation", 1L, "completion", completion)),
              event,
              1);
      var expected = fixture.creator.process(fixture.initial, event, 1);
      assertEquals("APPLIED", expected.status(), expected.observations().toString());
      assertEquals(
          expected.document().blueId(),
          new ContractProcessor.Document(result.outcome().snapshot).blueId());
      assertEquals(expected.gas(), result.outcome().gas);
      assertEquals(expected.publicEvents(), result.outcome().publicEvents);
      assertEquals(1, result.outcome().snapshot.documents.size());
      assertEquals("owner", result.outcome().observations().get("executionBoundary"));
      var boundary =
          assertThrows(
              ContractModel.Error.class,
              () ->
                  ContractCompletions.restore(
                      expected.document().state,
                      result.outcome().observations(),
                      result.outcome().receipt()));
      assertEquals("INCOMPATIBLE_EXECUTION_BOUNDARY", boundary.code);
      assertTrue(Collections.disjoint(forbidden, fetched));
      assertThrows(
          ContractModel.Error.class,
          () ->
              parentEngine.exportSnapshot(
                  new ContractProcessor.Document(result.outcome().snapshot)),
          "A protected partial checkpoint must never be advertised as a portable graph proof");
    }
  }

  @Test
  void sourceCausePredecessorAndAttachmentGenerationAreChecked() {
    var f = fixture(2);
    var root = ContractLocalCheckpoint.checkpoint(f.creator, f.initial.state);
    var child = f.initial.state.copy();
    child.root = "child";
    child.documents.keySet().removeIf(k -> !k.equals("child"));
    child.edges.clear();
    var childCheckpoint = ContractLocalCheckpoint.checkpoint(f.creator, child);
    var event = f.creator.blue.source(Map.of("kind", "tick"));
    try (var scope = f.creator.blue.context.validationScope(key -> Optional.empty())) {
      var childResult = ContractLocal.process(f.creator, childCheckpoint, List.of(), event, 1);
      var completion =
          new LinkedHashMap<String, Object>(
              Map.of(
                  "before",
                  childCheckpoint,
                  "after",
                  ContractLocalCheckpoint.checkpoint(f.creator, childResult.outcome().snapshot),
                  "observations",
                  childResult.outcome().observations(),
                  "event",
                  ContractLanguage.identity(event),
                  "timestamp",
                  1L));
      var stale =
          assertThrows(
              ContractModel.Error.class,
              () ->
                  ContractLocal.process(
                      f.creator,
                      root,
                      List.of(
                          Map.of(
                              "path",
                              "/children/active",
                              "generation",
                              2L,
                              "completion",
                              completion)),
                      event,
                      1));
      assertEquals("LOCAL_EXECUTION_UNAVAILABLE", stale.code);
      completion.put("timestamp", 2L);
      assertThrows(
          ContractModel.Error.class,
          () ->
              ContractLocal.process(
                  f.creator,
                  root,
                  List.of(
                      Map.of(
                          "path", "/children/active", "generation", 1L, "completion", completion)),
                  event,
                  1));
      var corrupted = new LinkedHashMap<>(root);
      corrupted.put("revision", 999L);
      assertEquals(
          "INVALID_LOCAL_CHECKPOINT",
          assertThrows(
                  ContractModel.Error.class,
                  () -> ContractLocal.process(f.creator, corrupted, List.of(), event, 1))
              .code);
    }
  }

  @Test
  void structuralFallbackReusesProtectedAdmissionButStillChecksHistoricalEdges() {
    var f = fixture(20);
    Map<String, Object> full = f.creator.exportSnapshot(f.initial);
    Map<String, Object> records = ContractModel.obj(full.get("content"));
    List<Map<String, Object>> members = new ArrayList<>();
    for (String key : f.initial.state.documents.keySet()) {
      var rooted = f.initial.state.copy();
      rooted.root = key;
      members.add(ContractLocalCheckpoint.checkpoint(f.creator, rooted));
    }
    Map<String, Object> root =
        members.stream().filter(m -> "root".equals(m.get("root"))).findFirst().orElseThrow();
    var edges =
        f.initial.state.edges.values().stream()
            .map(e -> new Contracts.Embedding(e.source(), e.path(), e.target(), e.generation()))
            .toList();
    Language language =
        Language.newBuilder()
            .provider(
                id ->
                    Optional.ofNullable(records.get(id.value()))
                        .map(raw -> new LanguageRecord(ContractModel.obj(raw))))
            .build();
    var configuration = Contracts.newBuilder().language(language).build();
    var event = language.prepare(Language.Input.yaml("kind: tick"));
    try (var unscoped = Contracts.Execution.using(configuration, null)) {
      assertThrows(
          IllegalStateException.class, () -> unscoped.restoreLocalGraph(root, members, edges));
    }
    try (var ordinary = Contracts.Execution.using(configuration, null);
        var recovered = Contracts.Execution.using(configuration, null, key -> Optional.empty())) {
      var expectedBefore = ordinary.restore(full);
      var actualBefore = recovered.restoreLocalGraph(root, members, edges);
      assertEquals(expectedBefore.identity(), actualBefore.identity());
      assertEquals(expectedBefore.blueId(), actualBefore.blueId());
      var expected =
          ordinary.process(expectedBefore, event, 1, AdmissionEvidence.empty()).requireApplied();
      var actual =
          recovered.process(actualBefore, event, 1, AdmissionEvidence.empty()).requireApplied();
      assertEquals(expected.transition().observations(), actual.transition().observations());
      assertEquals(expected.after().blueId(), actual.after().blueId());
      assertTrue(
          actual.after().preparationDelta(actualBefore, ignored -> true).isEmpty(),
          "Member preparation cannot invent a persisted full-graph delta predecessor");
    }
    try (var recovered = Contracts.Execution.using(configuration, null, key -> Optional.empty())) {
      assertThrows(
          BlueException.class, () -> recovered.restoreLocalGraph(root, members, List.of()));
    }
    var corrupted = new LinkedHashMap<>(root);
    corrupted.put("revision", 999L);
    var broken = new ArrayList<>(members);
    broken.set(members.indexOf(root), corrupted);
    try (var recovered = Contracts.Execution.using(configuration, null, key -> Optional.empty())) {
      assertThrows(
          BlueException.class, () -> recovered.restoreLocalGraph(corrupted, broken, edges));
    }
    try (var recovered = Contracts.Execution.using(configuration, null, key -> Optional.empty())) {
      assertThrows(
          BlueException.class,
          () ->
              recovered.restoreLocalGraph(
                  root, members.stream().filter(m -> m != root).toList(), edges));
    }
  }

  @Test
  void historicalMetadataMustMatchItsProtectedOwner() {
    var f = fixture(2);
    var checkpoint = ContractLocalCheckpoint.checkpoint(f.creator, f.initial.state);
    var full = f.creator.exportSnapshot(f.initial);
    var metadata = ContractModel.obj(full.get("snapshot"));
    assertEquals(
        full.get("identity"),
        Contracts.Execution.materializeLocalMetadata(checkpoint, metadata).get("identity"));
    var wrong = new LinkedHashMap<>(metadata);
    wrong.put("root", "child");
    assertThrows(
        BlueException.class, () -> Contracts.Execution.materializeLocalMetadata(checkpoint, wrong));
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "add,false",
    "replace,false",
    "remove,false",
    "add,true",
    "replace,true",
    "remove,true"
  })
  void ordinaryContainerWritesUseTheSameSemanticsWithOrWithoutTheGraph(
      String operation, boolean inherited) {
    var creator = engine(Language.create());
    Map<String, Object> patch = new LinkedHashMap<>(Map.of("op", operation, "path", "/detail"));
    if (!operation.equals("remove")) patch.put("val", Map.of("amount", 42, "reference", "bank"));
    Map<String, Object> source =
        new LinkedHashMap<>(
            ContractsFixtures.yaml(
                """
                contracts:
                  input: {type: Contracts/External Channel}
                  change:
                    type: Contracts/Sequential Workflow
                    channel: input
                    steps: []
                """));
    var declarations = ContractModel.obj(source.get("contracts"));
    ContractModel.obj(declarations.get("change"))
        .put(
            "steps",
            List.of(Map.of("type", "Contracts/Update Document", "changeset", List.of(patch))));
    if (!operation.equals("add")) source.put("detail", Map.of("amount", 0));
    if (inherited) source.put("type", Map.of("detail", Map.of()));
    var initial = creator.initialize(source);
    assertEquals("APPLIED", initial.status());
    var event = creator.blue.source(Map.of("kind", "tick"));
    var checkpoint = ContractLocalCheckpoint.checkpoint(creator, initial.document().state);
    try (var scope = creator.blue.context.validationScope(key -> Optional.empty())) {
      var local = ContractLocal.process(creator, checkpoint, List.of(), event, 1).outcome();
      var full = creator.process(initial.document(), event, 1);
      assertEquals("APPLIED", full.status());
      assertEquals(
          full.document().blueId(), local.snapshot.documents.get(local.snapshot.root).blueId);
      assertEquals(full.gas(), local.gas);
      assertEquals(full.publicEvents(), local.publicEvents);
    }
  }

  private record Fixture(ContractProcessor creator, ContractProcessor.Document initial) {}

  private static Fixture fixture(int count) {
    var members = ContractsFixtures.members(count);
    ((Map<String, Object>)
            ((Map<String, Object>) ((Map<String, Object>) members.get("child")).get("contracts"))
                .get("input"))
        .remove("event");
    var creator = engine(Language.create());
    var initial = creator.admit(members, "root");
    assertEquals("APPLIED", initial.status());
    return new Fixture(creator, initial.document());
  }
}
