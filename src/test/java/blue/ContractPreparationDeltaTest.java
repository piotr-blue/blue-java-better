package blue;

import static blue.ContractModel.obj;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Changed native preparation must be equivalent to a complete export after a cold restart. */
@Tag("contracts")
class ContractPreparationDeltaTest {
  @ParameterizedTest
  @ValueSource(ints = {10, 1000})
  void onlyTheChangedChildAndContainingRootAreExported(int count) {
    Language language = Language.newBuilder().mount("Contracts", Contracts.publication()).build();
    Contracts configuration =
        Contracts.newBuilder().language(language).gasSchedule(GasSchedule.STANDARD).build();
    Map<String, Object> children = new LinkedHashMap<>();
    for (int i = 0; i < count; i++)
      children.put(
          "child-" + i,
          Map.of(
              "count",
              0,
              "contracts",
              Map.of(
                  "input",
                      Map.of("type", "Contracts/External Channel", "event", Map.of("target", i)),
                  "change",
                      Map.of(
                          "type",
                          "Contracts/Sequential Workflow",
                          "channel",
                          "input",
                          "steps",
                          List.of(
                              Map.of(
                                  "type",
                                  "Contracts/Update Document",
                                  "changeset",
                                  List.of(
                                      Map.of("op", "replace", "path", "/count", "val", 1))))))));
    var authored =
        language.readJson(
            ContractWire.write(
                Map.of(
                    "children",
                    children,
                    "contracts",
                    Map.of(
                        "embedded",
                        Map.of(
                            "type",
                            "Contracts/Process Embedded",
                            "collectionPaths",
                            List.of("/children"))))));
    Map<String, Object> checkpoint, preparation;
    try (var creator = Contracts.Execution.using(configuration, null)) {
      var state = creator.initialize(creator.prepare("root", authored)).requireApplied().after();
      checkpoint = state.checkpoint();
      preparation = state.preparation(obj(checkpoint.get("content"))::containsKey);
      assertTrue(state.preparationDelta(state, ignored -> true).isEmpty(), "No protected base yet");
    }
    Map<String, Object> merged, afterCheckpoint;
    var event = language.prepare(Language.Input.yaml("target: " + (count - 1) + "\nnonce: 1"));
    try (var execution =
        Contracts.Execution.using(
            configuration,
            null,
            ignored -> Optional.empty(),
            id ->
                id.equals(checkpoint.get("identity"))
                    ? Optional.of(preparation)
                    : Optional.empty())) {
      var before = execution.restore(checkpoint);
      var after =
          execution.process(before, event, 1, AdmissionEvidence.empty()).requireApplied().after();
      afterCheckpoint = after.checkpoint();
      var delta = after.preparationDelta(before, ignored -> true);
      assertEquals(before.identity(), delta.get("base"));
      assertEquals(after.identity(), delta.get("snapshot"));
      assertEquals(2, obj(delta.get("documents")).size());
      assertTrue(after.preparationDelta(before, ignored -> false).isEmpty());
      Map<String, Object> rows = new TreeMap<>(obj(preparation.get("documents")));
      rows.putAll(obj(delta.get("documents")));
      merged =
          Map.of(
              "snapshot",
              delta.get("snapshot"),
              "environment",
              delta.get("environment"),
              "documents",
              rows);
      assertEquals(
          ContractCanonical.text(after.preparation(ignored -> true)),
          ContractCanonical.text(merged));
    }
    // A later input is the authority check: no mutable runtime or prepared Java objects survive.
    var next = language.prepare(Language.Input.yaml("target: " + (count - 1) + "\nnonce: 2"));
    try (var ordinary = Contracts.Execution.using(configuration, null);
        var restored =
            Contracts.Execution.using(
                configuration, null, ignored -> Optional.empty(), id -> Optional.of(merged))) {
      var expected =
          ordinary
              .process(ordinary.restore(afterCheckpoint), next, 2, AdmissionEvidence.empty())
              .requireApplied();
      var actual =
          restored
              .process(restored.restore(afterCheckpoint), next, 2, AdmissionEvidence.empty())
              .requireApplied();
      assertEquals(expected.after().blueId(), actual.after().blueId());
      assertEquals(expected.transition().observations(), actual.transition().observations());
      assertEquals(expected.transition().receipt(), actual.transition().receipt());
    }
  }
}
