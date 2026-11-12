package blue;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("contracts")
class ContractExecutionTest {
  @Test
  void frozenStateComponentsAreImmutableAndReuseTheExistingTopology() {
    Map<String, Object> members = new LinkedHashMap<>();
    for (String member : List.of("a", "b", "c", "d")) {
      String next =
          switch (member) {
            case "a" -> "b";
            case "b" -> "c";
            case "c" -> "d";
            default -> "a";
          };
      members.put(
          member,
          Map.of(
              "member",
              member,
              "next",
              Contracts.Execution.link(next),
              "contracts",
              Map.of(
                  "embedded",
                  Map.of("type", "Contracts/Process Embedded", "paths", List.of("/next")))));
    }
    try (Contracts.Execution execution = Contracts.Execution.using(Contracts.create(), null)) {
      var state = execution.prepare(members, "a");
      List<List<String>> components = state.components();
      assertEquals(List.of(List.of("a", "b", "c", "d")), components);
      assertSame(components, state.components());
      assertThrows(UnsupportedOperationException.class, components::clear);
      assertThrows(UnsupportedOperationException.class, () -> components.getFirst().clear());
      var initialized = execution.initialize(state).requireApplied().after();
      assertEquals(components, initialized.components());
      assertEquals(components, state.components());
    }
  }

  @Test
  @SuppressWarnings("try")
  void durableSemanticFactsAreGoalScopedAndRevokedFromCapturedResolvers() {
    Language language = Language.create();
    LanguageDocument value = language.readYaml("amount: 7");
    ContractLanguage source = new ContractLanguage(language);
    source.install(value);
    source.admitLocal(value.exact, "value");
    Map<String, Boolean> facts = new HashMap<>(source.validationFacts(id -> true));
    facts.keySet().removeIf(key -> key.contains("/validate/definition/"));
    LanguageFacts retained = new LanguageFacts(key -> Optional.ofNullable(facts.get(key)));
    ContractLanguage recovered = new ContractLanguage(language);
    var exact = new ExactNode.Ref(value.blueId().value());
    String recipe = LanguageSemantics.recipe(exact).key();
    var expected = new LanguageContext.ValidationKey(recipe, "value", true);
    ContractLanguage captured;
    try (var scope = recovered.validationScope(key -> Optional.ofNullable(facts.get(key)))) {
      assertTrue(retained.validated(expected));
      assertFalse(
          retained.validated(new LanguageContext.ValidationKey(recipe, "definition", true)));
      assertFalse(retained.validated(new LanguageContext.ValidationKey(recipe, "value", false)));
      captured = recovered.fork();
      recovered.retain(List.of(exact));
      captured.retain(List.of(exact));
    }
    assertThrows(LanguageFailure.class, () -> recovered.retain(List.of(exact)));
    assertThrows(LanguageFailure.class, () -> captured.retain(List.of(exact)));
  }

  @ParameterizedTest
  @CsvSource({
    "false,false,false",
    "false,true,false",
    "true,false,false",
    "true,true,false",
    "false,false,true",
    "false,true,true",
    "true,false,true",
    "true,true,true"
  })
  void recoveredOwnerDefersUnusedWorkflowBodiesAndPreservesNativeObservations(
      boolean prepared, boolean collapsedContracts, boolean attemptProvider) {
    Language writer = Language.newBuilder().mount("Contracts", Contracts.publication()).build();
    LanguageDocument steps =
        writer.readYaml(
            """
            - type: Contracts/Compute
              expr: {$unknownOperator: true}
            """);
    writer = writer.toBuilder().content(steps).build();
    LanguageDocument source =
        writer.defineYaml(
            """
            count: 0
            contracts:
              small: {type: Contracts/External Channel, event: {kind: small}}
              large: {type: Contracts/External Channel, event: {kind: large}}
              cheap:
                type: Contracts/Sequential Workflow
                channel: small
                steps:
                  - type: Contracts/Update Document
                    changeset: [{op: replace, path: /count, val: 1}]
              unused:
                type: Contracts/Sequential Workflow
                channel: large
                steps: {blueId: STEPS}
            """
                .replace("STEPS", steps.blueId().value()));
    if (collapsedContracts) source = source.collapse("/contracts");
    Contracts.Execution creator =
        Contracts.Execution.using(Contracts.newBuilder().language(writer).build(), null);
    var initialized = creator.initialize(creator.prepare("root", source)).requireApplied().after();
    LanguageDocument completed = initialized.document();
    Map<BlueId, LanguageRecord> records = completed.records();
    assertTrue(
        initialized.validationFacts(id -> false).isEmpty(),
        "Transient validation is not a durable retention fact");
    Map<String, Boolean> facts =
        initialized.validationFacts(id -> records.containsKey(new BlueId(id)));
    var artifact = initialized.preparation(id -> records.containsKey(new BlueId(id)));
    assertTrue(initialized.preparation(id -> false).isEmpty());
    Set<BlueId> fetched = new HashSet<>();
    LanguageProvider provider =
        id -> {
          fetched.add(id);
          return Optional.ofNullable(records.get(id));
        };
    var readerBuilder = Language.newBuilder().mount("Contracts", Contracts.publication());
    if (!attemptProvider) readerBuilder.provider(provider);
    Language reader = readerBuilder.build();
    var envelope = initialized.checkpoint();
    var stored =
        Map.<String, Object>of(
            "snapshot",
            envelope.get("snapshot"),
            "identity",
            envelope.get("identity"),
            "content",
            Map.of());
    Contracts configuration = Contracts.newBuilder().language(reader).build();
    try (Contracts.Execution recovered =
        Contracts.Execution.using(
            configuration,
            null,
            key -> Optional.ofNullable(facts.get(key)),
            snapshot -> prepared ? Optional.of(artifact) : Optional.empty())) {
      if (attemptProvider) recovered.withContent(provider);
      var before = recovered.restore(stored);
      assertEquals(0, before.member(before.root()).at("/count").integer());
      assertFalse(
          fetched.contains(steps.blueId()),
          "Restoring headers must not fetch an unused workflow's steps");
      var small = reader.prepare(Language.Input.yaml("kind: small"));
      var actual = recovered.process(before, small, 1, AdmissionEvidence.empty()).requireApplied();
      assertEquals(1, ((Number) actual.after().read("/count")).intValue());
      assertFalse(
          fetched.contains(steps.blueId()),
          "Unrelated work must not fetch an unused workflow's steps");
      try (Contracts.Execution portable = Contracts.Execution.using(configuration, null)) {
        if (attemptProvider) portable.withContent(provider);
        var expected =
            portable
                .process(portable.restore(stored), small, 1, AdmissionEvidence.empty())
                .requireApplied();
        assertEquals(expected.transition().observations(), actual.transition().observations());
        assertEquals(expected.after().blueId(), actual.after().blueId());
        fetched.clear();
        LanguageContent large = reader.prepare(Language.Input.yaml("kind: large"));
        var selected = recovered.process(actual.after(), large, 2, AdmissionEvidence.empty());
        var ordinary = portable.process(expected.after(), large, 2, AdmissionEvidence.empty());
        assertFalse(
            selected.applied(), "The unknown BEX operator must fail when its workflow is selected");
        assertTrue(fetched.contains(steps.blueId()), "A selected workflow must load its body");
        assertEquals(ordinary.transition().observations(), selected.transition().observations());
      }
    }
  }
}
