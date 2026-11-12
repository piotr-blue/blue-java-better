package blue;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Protected restoration must preserve exact native observations, including metering failures. */
@Tag("contracts")
class ContractPreparationTest {
  @Test
  void protectedMutationDoesNotRevalidateTheUnchangedInitializationHistory() {
    Language language = Language.newBuilder().mount("Contracts", Contracts.publication()).build();
    LanguageDocument authored =
        language.readYaml(
            """
            count: 0
            contracts:
              input: {type: Contracts/External Channel, event: {kind: tick}}
              increment:
                type: Contracts/Sequential Workflow
                channel: input
                steps:
                  - type: Contracts/Update Document
                    changeset: [{op: replace, path: /count, val: {$add: [{$document: /count}, 1]}}]
            """);
    Map<String, Object> checkpoint;
    Map<String, Object> preparation;
    Map<BlueId, LanguageRecord> records;
    Contracts.Transition expected;
    try (var creator =
        Contracts.Execution.using(Contracts.newBuilder().language(language).build(), null)) {
      var state = creator.initialize(creator.prepare("root", authored)).requireApplied().after();
      checkpoint = new LinkedHashMap<>(state.checkpoint());
      records = state.document().records();
      preparation = state.preparation(id -> records.containsKey(new BlueId(id)));
      expected =
          creator
              .process(
                  state,
                  language.prepare(Language.Input.yaml("kind: tick")),
                  1,
                  AdmissionEvidence.empty())
              .requireApplied()
              .transition();
    }
    checkpoint.put("content", Map.of());
    var historicalReads = new java.util.concurrent.atomic.AtomicInteger();
    Language recovered =
        Language.newBuilder()
            .mount("Contracts", Contracts.publication())
            .provider(
                id -> {
                  if (id.equals(authored.blueId())) {
                    historicalReads.incrementAndGet();
                    return Optional.empty();
                  }
                  return Optional.ofNullable(records.get(id));
                })
            .build();
    try (var processor =
        Contracts.Execution.using(
            Contracts.newBuilder().language(recovered).build(),
            null,
            key -> Optional.empty(),
            key -> Optional.of(preparation))) {
      var state = processor.restore(checkpoint);
      var actual =
          processor.process(
              state,
              recovered.prepare(Language.Input.yaml("kind: tick")),
              1,
              AdmissionEvidence.empty());
      actual.requireApplied();
      assertEquals(expected.receipt(), actual.transition().receipt());
      assertEquals(expected.observations(), actual.transition().observations());
      assertEquals(0, historicalReads.get());
      // Reusing a completed check does not manufacture the historical content itself.
      assertThrows(
          BlueException.class, () -> actual.after().read("/contracts/initialized/document/count"));
    }
  }

  @Test
  void coldPreparationCanInspectInlineTimelineDeclarationsWithoutLoadingMemberBodies() {
    BluePackage coordination =
        PackageLoader.create()
            .resource(
                getClass().getClassLoader(),
                "coordination/coordination.bluepkg",
                ProviderSignatures.COORDINATION_PACKAGE);
    Language language =
        Language.newBuilder()
            .mount("Contracts", Contracts.publication())
            .mount("Coordination", coordination)
            .build();
    Contracts configuration =
        Contracts.newBuilder()
            .language(language)
            .timelineChannels(
                TimelineChannels.verifiedBy(
                    coordination,
                    request ->
                        request.receivers().stream()
                            .map(
                                receiver ->
                                    new TimelineChannels.Assessment(
                                        receiver,
                                        TimelineChannels.Disposition.INAPPLICABLE,
                                        false,
                                        "Other source"))
                            .toList()))
            .build();
    Map<String, Object> checkpoint;
    Map<String, Object> preparation;
    Contracts.Reception expected;
    Contracts.Transition expectedTransition;
    LanguageContent event = language.prepare(Language.Input.yaml("unused: true"));
    try (var creator = Contracts.Execution.using(configuration, null)) {
      var input =
          language.readYaml(
              """
              contracts:
                input:
                  type: Coordination/Public Timeline Channel
                  provider:
                    type: Coordination/Timeline Provider
                    endpoint: https://provider.example
                    publicKey: d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a
                  url: https://provider.example/history
              """);
      var state = creator.initialize(creator.prepare("root", input)).requireApplied().after();
      checkpoint = state.checkpoint();
      var records = ContractModel.obj(checkpoint.get("content"));
      preparation = state.preparation(records::containsKey);
      expected = state.reception();
      // The current preparation needs only retained channel/marker responses. Legacy hints
      // still fall back to opening this independently retained member response.
      checkpoint =
          Map.of(
              "snapshot",
              checkpoint.get("snapshot"),
              "identity",
              checkpoint.get("identity"),
              "content",
              Map.of(state.blueId(), LanguageRecord.json(state.document().toJson()).response()));
      assertEquals(1, expected.channels().size());
      expectedTransition =
          creator.process(state, event, 1, AdmissionEvidence.empty()).requireApplied().transition();
    }
    for (boolean legacy : new boolean[] {false, true}) {
      Map<String, Object> selectedPreparation = new LinkedHashMap<>(preparation);
      Map<String, Object> selectedCheckpoint = new LinkedHashMap<>(checkpoint);
      if (legacy) {
        Map<String, Object> rows = new LinkedHashMap<>();
        ContractModel.obj(preparation.get("documents"))
            .forEach(
                (member, raw) -> {
                  Map<String, Object> row = new LinkedHashMap<>(ContractModel.obj(raw));
                  row.remove("headerRecords");
                  rows.put(member, row);
                });
        selectedPreparation.put("documents", rows);
      } else selectedCheckpoint.put("content", Map.of());
      try (var restored =
          Contracts.Execution.using(
              configuration,
              null,
              key -> Optional.empty(),
              key -> Optional.of(selectedPreparation))) {
        var state = restored.restore(selectedCheckpoint);
        assertEquals(expected, state.reception());
        var actual = restored.process(state, event, 1, AdmissionEvidence.empty()).requireApplied();
        assertEquals(expectedTransition.receipt(), actual.transition().receipt());
        assertEquals(expectedTransition.observations(), actual.transition().observations());
      }
    }
  }

  @Test
  void memberFactsNeedNoGlobalFactLookupAndPreserveOrdinaryColdReceipts() {
    var fixture = fixture(true);
    var rows = ContractModel.obj(fixture.preparation().get("documents"));
    assertTrue(
        rows.values().stream()
            .anyMatch(
                row -> !ContractModel.arr(ContractModel.obj(row).get("languageFacts")).isEmpty()));
    Contracts configuration = Contracts.newBuilder().language(fixture.language()).build();
    for (boolean legacy : new boolean[] {false, true}) {
      Map<String, Object> selected = new LinkedHashMap<>(fixture.preparation());
      if (legacy) {
        Map<String, Object> oldRows = new LinkedHashMap<>();
        rows.forEach(
            (member, value) -> {
              Map<String, Object> old = new LinkedHashMap<>(ContractModel.obj(value));
              old.remove("languageFacts");
              old.remove("markerFacts");
              old.remove("headerRecords");
              oldRows.put(member, old);
            });
        selected.put("documents", oldRows);
      }
      try (var ordinary = Contracts.Execution.using(configuration, null);
          var prepared =
              Contracts.Execution.using(
                  configuration, null, key -> Optional.empty(), key -> Optional.of(selected))) {
        var event = fixture.language().prepare(Language.Input.yaml("kind: tick"));
        var expected =
            ordinary
                .process(
                    ordinary.restore(fixture.checkpoint()), event, 1, AdmissionEvidence.empty())
                .requireApplied();
        var actual =
            prepared
                .process(
                    prepared.restore(fixture.checkpoint()), event, 1, AdmissionEvidence.empty())
                .requireApplied();
        assertEquals(expected.transition().observations(), actual.transition().observations());
        assertEquals(expected.transition().receipt(), actual.transition().receipt());
        assertEquals(expected.after().blueId(), actual.after().blueId());
      }
    }
  }

  @Test
  @SuppressWarnings("try")
  void semanticPreparationIsRevokedFromEveryCapturedResolverAtScopeClose() {
    Language language = Language.create();
    var value = language.readYaml("scopeTest: retained member").semantic;
    ContractLanguage source = new ContractLanguage(language);
    ContractLanguage.strict(value, source, "value");
    List<String> facts = source.semanticPreparation(value);
    assertTrue(facts.contains("closure"));
    assertTrue(facts.contains("value/true"));
    ContractLanguage receiver = new ContractLanguage(language);
    assertThrows(
        IllegalStateException.class,
        () -> receiver.restoreSemanticPreparation(value, new ArrayList<>(facts)));
    ContractLanguage captured;
    var lookups = new java.util.concurrent.atomic.AtomicInteger();
    try (var scope =
        receiver.validationScope(
            key -> {
              lookups.incrementAndGet();
              return Optional.empty();
            })) {
      receiver.restoreSemanticPreparation(value, new ArrayList<>(facts));
      captured = receiver.readSnapshot();
      assertEquals(facts, receiver.semanticPreparation(value));
      assertEquals(facts, captured.semanticPreparation(value));
      assertTrue(
          receiver.validationFacts(id -> true).isEmpty(),
          "Restored facts need no second publication");
      assertEquals(
          0, lookups.get(), "Protected member facts must not query durable memo storage again");
      var different = language.readYaml("scopeTest: another member").semantic;
      assertTrue(receiver.semanticPreparation(different).isEmpty());
    }
    assertTrue(receiver.semanticPreparation(value).isEmpty());
    assertTrue(captured.semanticPreparation(value).isEmpty());
  }

  @Test
  void retainedInputCatalogueSurvivesProcessingUntilItsCheckpointIsPublished() {
    var fixture = fixture(false);
    LanguageDocument foreign = fixture.language().readYaml("name: Input-only document\nvalue: 17");
    LanguageContent entry =
        fixture.language().toBuilder()
            .content(foreign)
            .build()
            .prepare(
                Language.Input.yaml("kind: tick\nreference: {blueId: '" + foreign.blueId() + "'}"));
    Contracts configuration = Contracts.newBuilder().language(fixture.language()).build();
    Map<String, Object> expected;
    Map<String, Boolean> facts;
    String receipt;
    try (var first = Contracts.Execution.using(configuration, null)) {
      var outcome =
          first
              .process(first.restore(fixture.checkpoint()), entry, 1, AdmissionEvidence.empty())
              .requireApplied();
      expected = outcome.after().checkpoint();
      Map<?, ?> retained = (Map<?, ?>) expected.get("content");
      assertTrue(retained.containsKey(foreign.blueId().value()));
      facts = outcome.after().validationFacts(retained::containsKey);
      receipt = outcome.transition().receipt();
    }
    try (var restored =
        Contracts.Execution.using(
            configuration,
            null,
            key -> Optional.ofNullable(facts.get(key)),
            key -> Optional.of(fixture.preparation()))) {
      var outcome =
          restored
              .process(restored.restore(fixture.checkpoint()), entry, 1, AdmissionEvidence.empty())
              .requireApplied();
      assertEquals(receipt, outcome.transition().receipt());
      assertEquals(expected, outcome.after().checkpoint());
    }
  }

  @ParameterizedTest
  @CsvSource({
    "false,20",
    "false,200",
    "false,2000",
    "false,1000000",
    "true,20",
    "true,200",
    "true,2000",
    "true,1000000"
  })
  void preparedAndOrdinaryColdExecutionHaveIdenticalReceipts(boolean cycle, long limit) {
    var fixture = fixture(cycle);
    Contracts configuration =
        Contracts.newBuilder().language(fixture.language()).gasLimit(limit).build();
    try (var ordinary = Contracts.Execution.using(configuration, null);
        var prepared =
            Contracts.Execution.using(
                configuration,
                null,
                key -> Optional.ofNullable(fixture.facts().get(key)),
                key -> Optional.of(fixture.preparation()))) {
      var plain = ordinary.restore(fixture.checkpoint());
      var cached = prepared.restore(fixture.checkpoint());
      LanguageContent entry = fixture.language().prepare(Language.Input.yaml("kind: tick"));
      for (int i = 1; i <= 3; i++) {
        var expected = ordinary.process(plain, entry, i, AdmissionEvidence.empty());
        var actual = prepared.process(cached, entry, i, AdmissionEvidence.empty());
        assertEquals(expected.transition().observations(), actual.transition().observations());
        assertEquals(expected.transition().receipt(), actual.transition().receipt());
        assertEquals(expected.after().blueId(), actual.after().blueId());
        if (!expected.applied()) break;
        plain = expected.after();
        cached = actual.after();
      }
    }
  }

  @Test
  void anotherRuntimePreparationFallsBackAndAnotherSnapshotIsRejected() {
    var fixture = fixture(false);
    var otherRuntime = new HashMap<>(fixture.preparation());
    otherRuntime.put("environment", "a different runtime");
    Contracts configuration = Contracts.newBuilder().language(fixture.language()).build();
    try (var prepared =
        Contracts.Execution.using(
            configuration, null, key -> Optional.empty(), key -> Optional.of(otherRuntime))) {
      assertEquals(
          fixture.checkpoint().get("identity"), prepared.restore(fixture.checkpoint()).identity());
    }
    var wrongSnapshot = new HashMap<>(fixture.preparation());
    wrongSnapshot.put("snapshot", "another checkpoint");
    try (var prepared =
        Contracts.Execution.using(
            configuration, null, key -> Optional.empty(), key -> Optional.of(wrongSnapshot))) {
      assertThrows(BlueException.class, () -> prepared.restore(fixture.checkpoint()));
    }
  }

  @Test
  void protectedRestoreDefersUnusedBodiesButPortableRestoreChecksTheWholeEnvelope() {
    var fixture = fixture(false);
    LanguageDocument extra = fixture.language().readYaml("message: Not part of the checkpoint");
    Map<String, Object> content =
        new LinkedHashMap<>(ContractModel.obj(fixture.checkpoint().get("content")));
    content.put(extra.blueId().value(), Map.of("text", "{broken", "format", "json"));
    Map<String, Object> checkpoint = new LinkedHashMap<>(fixture.checkpoint());
    checkpoint.put("content", content);
    Contracts configuration = Contracts.newBuilder().language(fixture.language()).build();
    try (var ordinary = Contracts.Execution.using(configuration, null);
        var prepared =
            Contracts.Execution.using(
                configuration,
                null,
                key -> Optional.empty(),
                key -> Optional.of(fixture.preparation()))) {
      assertThrows(BlueException.class, () -> ordinary.restore(checkpoint));
      var state = prepared.restore(checkpoint);
      assertEquals(checkpoint.get("identity"), state.identity());
      var event = fixture.language().prepare(Language.Input.yaml("kind: tick"));
      assertTrue(prepared.process(state, event, 1, AdmissionEvidence.empty()).applied());
    }
  }

  @Test
  void retainedContentOwnsResponsesAndStillVerifiesDemandedIdentities() {
    LanguageDocument value = Language.create().readYaml("message: A retained body");
    Map<String, Object> response = new HashMap<>(LanguageRecord.json(value.toJson()).response());
    ContractLanguage retained = new ContractLanguage(Language.create());
    retained.supplyRetainedEvidence(Map.of(value.blueId().value(), response));
    LanguageDocument detached = retained.document(new ExactNode.Ref(value.blueId().value()));
    response.put("text", "{broken");
    assertEquals("A retained body", detached.at("/message").text());
    assertEquals(
        value.toJson(),
        LanguageCore.exactText(retained.open(new ExactNode.Ref(value.blueId().value()))));
    ContractLanguage corrupt = new ContractLanguage(Language.create());
    corrupt.supplyRetainedEvidence(
        Map.of(value.blueId().value(), Map.of("text", "123", "format", "json")));
    assertThrows(
        LanguageFailure.class, () -> corrupt.open(new ExactNode.Ref(value.blueId().value())));
  }

  private record Fixture(
      Language language,
      Map<String, Object> checkpoint,
      Map<String, Object> preparation,
      Map<String, Boolean> facts) {}

  @SuppressWarnings("unchecked")
  private static Fixture fixture(boolean cycle) {
    Language language = Language.newBuilder().mount("Contracts", Contracts.publication()).build();
    Contracts configuration = Contracts.newBuilder().language(language).build();
    try (var creator = Contracts.Execution.using(configuration, null)) {
      Map<String, Object> members = new LinkedHashMap<>();
      for (int i = 0; i < (cycle ? 4 : 2); i++) {
        Map<String, Object> doc =
            (Map<String, Object>)
                ContractWire.parse(
                    """
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
                            event: {count: {$document: /count}}
                    """,
                    "yaml");
        doc.put("index", i);
        if (cycle || i == 0) {
          ((Map<String, Object>) doc.get("contracts"))
              .put(
                  "embedded",
                  Map.of("type", "Contracts/Process Embedded", "paths", List.of("/next")));
          doc.put("next", Contracts.Execution.link("member-" + ((i + 1) % 4)));
        }
        members.put("member-" + i, doc);
      }
      var after = creator.initialize(creator.prepare(members, "member-0")).requireApplied().after();
      Map<BlueId, LanguageRecord> records = after.document().records();
      var exported = after.checkpoint();
      Map<String, Object> checkpoint =
          Map.of(
              "identity",
              exported.get("identity"),
              "snapshot",
              exported.get("snapshot"),
              "content",
              Map.of());
      // Portable roundtrip deliberately discards all Java object and resolver identity.
      Map<String, Object> preparation =
          (Map<String, Object>)
              ContractWire.json(
                  ContractWire.write(after.preparation(id -> records.containsKey(new BlueId(id)))));
      assertFalse(preparation.isEmpty());
      Language recovered =
          Language.newBuilder()
              .mount("Contracts", Contracts.publication())
              .provider(id -> Optional.ofNullable(records.get(id)))
              .build();
      return new Fixture(
          recovered,
          checkpoint,
          preparation,
          after.validationFacts(id -> records.containsKey(new BlueId(id))));
    }
  }
}
