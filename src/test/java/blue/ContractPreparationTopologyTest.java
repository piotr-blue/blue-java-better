package blue;

import static blue.ContractModel.obj;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Topology edits publish only changed preparation, including newly active or detached members. */
@Tag("contracts")
class ContractPreparationTopologyTest {
  @ParameterizedTest
  @ValueSource(ints = {8, 128})
  void birthsAndDetachmentsMatchFullPreparationAndColdReplay(int count) {
    Language language = Language.newBuilder().mount("Contracts", Contracts.publication()).build();
    Contracts configuration = Contracts.newBuilder().language(language).build();
    Map<String, Object> children = new LinkedHashMap<>();
    for (int i = 0; i < count; i++) {
      children.put("child-" + i, Map.of("number", i, "count", 0));
    }
    var source =
        language.readYaml(
            """
            children: CHILDREN
            contracts:
              embedded: {type: Contracts/Process Embedded, collectionPaths: [/children]}
              createInput: {type: Contracts/External Channel, event: {kind: Create}}
              detachInput: {type: Contracts/External Channel, event: {kind: Detach}}
              create:
                type: Contracts/Sequential Workflow
                channel: createInput
                steps:
                  - type: Contracts/Update Document
                    changeset: [{op: add, path: /children/new, val: {$literal: {number: -1, count: 0}}}]
              detach:
                type: Contracts/Sequential Workflow
                channel: detachInput
                steps:
                  - type: Contracts/Update Document
                    changeset: [{op: remove, path: /children/child-0}]
            """
                .replace("CHILDREN", ContractWire.write(children)));
    Map<String, Object> checkpoint, preparation;
    try (var creator = Contracts.Execution.using(configuration, null)) {
      var initial = creator.initialize(creator.prepare("root", source)).requireApplied().after();
      checkpoint = initial.checkpoint();
      preparation = initial.preparation(ignored -> true);
    }
    int timestamp = 0;
    for (String kind : List.of("Create", "Detach")) {
      var previous = preparation;
      var event = language.prepare(Language.Input.yaml("kind: " + kind));
      try (var execution =
              Contracts.Execution.using(
                  configuration,
                  null,
                  ignored -> Optional.empty(),
                  ignored -> Optional.of(previous));
          var ordinary = Contracts.Execution.using(configuration, null)) {
        var before = execution.restore(checkpoint);
        var expected =
            ordinary
                .process(
                    ordinary.restore(checkpoint), event, ++timestamp, AdmissionEvidence.empty())
                .requireApplied();
        var result =
            execution.process(before, event, timestamp, AdmissionEvidence.empty()).requireApplied();
        assertEquals(expected.transition().observations(), result.transition().observations());
        var after = result.after();
        var delta = after.preparationDelta(before, ignored -> true);
        assertFalse(delta.isEmpty(), "A small topology edit must have a preparation delta");
        assertEquals(2, obj(delta.get("documents")).size());
        assertEquals(kind.equals("Create") ? 1 : 0, ((List<?>) delta.get("added")).size());
        Map<String, Object> rows = new TreeMap<>(obj(previous.get("documents")));
        rows.putAll(obj(delta.get("documents")));
        preparation =
            Map.of(
                "environment",
                delta.get("environment"),
                "snapshot",
                delta.get("snapshot"),
                "documents",
                rows);
        assertEquals(
            ContractCanonical.text(after.preparation(ignored -> true)),
            ContractCanonical.text(preparation));
        checkpoint = after.checkpoint();
      }
    }
    var prepared = preparation;
    try (var execution =
            Contracts.Execution.using(
                configuration,
                null,
                ignored -> Optional.empty(),
                ignored -> Optional.of(prepared));
        var ordinary = Contracts.Execution.using(configuration, null)) {
      var event = language.prepare(Language.Input.yaml("kind: Quiet"));
      assertEquals(
          ordinary
              .process(ordinary.restore(checkpoint), event, 3, AdmissionEvidence.empty())
              .requireApplied()
              .transition()
              .observations(),
          execution
              .process(execution.restore(checkpoint), event, 3, AdmissionEvidence.empty())
              .requireApplied()
              .transition()
              .observations());
    }
  }
}
