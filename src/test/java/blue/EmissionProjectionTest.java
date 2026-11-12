package blue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Detached events preserve exact projection, closure selection and caller immutability. */
class EmissionProjectionTest {
  @Test
  void detachedProjectionMatchesTheExistingProjectionForValuesAndCyclicReferences() {
    Language language = Language.create();
    List<LanguageDocument> events = new ArrayList<>();
    for (String yaml :
        List.of(
            "kind: Event\namount: 7",
            "[1, 2, 3]",
            "hello",
            "left: {text: repeated}\nright: {text: repeated}")) {
      events.add(language.readYaml(yaml));
    }
    events.add(
        language
            .finalizeSet(
                List.of(
                    Language.Input.exactJson(
                        "{\"name\":\"Loop\",\"self\":{\"blueId\":\"this#0\"}}")),
                Language.Goal.VALUE)
            .members()
            .getFirst());
    for (var event : events) {
      var original = new ContractBridge(event.owner.toBuilder().content(event).build());
      Object expected = BexValues.freeze(original.project(event.semantic, false, Set.of(), true));
      var emission = new Contracts.Emission("member", event);
      assertEquals(expected, emission.value());
      assertEquals(expected, emission.value());
      assertSame(event, emission.event());
    }
  }

  @Test
  void eventUsesItsDetachedContentAndOwnerProviderAfterTheSessionIsClosed() {
    List<Contracts.Emission> emissions;
    List<Object> expected;
    try (var session =
        Contracts.create()
            .openYaml(
                """
                contracts:
                  input: {type: Contracts/External Channel}
                  emit:
                    type: Contracts/Sequential Workflow
                    channel: input
                    steps:
                      - type: Contracts/Trigger Event
                        event: {kind: Tick, nested: {amount: 9}}
                """)) {
      session.initialize().requireApplied();
      var transition = session.process(Map.of("go", true), 1).requireApplied();
      emissions = transition.emissions();
      expected = transition.events();
    }
    assertEquals(expected, emissions.stream().map(Contracts.Emission::value).toList());
    var event = emissions.getFirst().event();
    var records = event.records();
    var restored =
        Language.newBuilder()
            .provider(id -> Optional.ofNullable(records.get(id)))
            .build()
            .read(event.blueId());
    assertEquals(emissions.getFirst().value(), new Contracts.Emission("member", restored).value());
    @SuppressWarnings("unchecked")
    var value = (Map<String, Object>) emissions.getFirst().value();
    assertThrows(UnsupportedOperationException.class, () -> value.put("kind", "changed"));
  }
}
