package blue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Retained composition preserves shared owners and the observable event identity of whole
 * execution.
 */
@Tag("contracts")
class ContractRetainedCompositionTest {
  @Test
  void adoptingAChildBodyPreservesUnchangedAttachmentTopology() {
    var destination = new ContractModel.Snapshot("root");
    for (int i = 0; i < 1000; i++) {
      String key = i == 0 ? "root" : "member-" + i;
      destination.documents.put(
          key, new ContractModel.Document(key, ExactNode.value(Map.of()), "body-" + i, null));
      if (i != 0) {
        var edge = new ContractModel.Edge("root", "/children/" + i, key, 1);
        destination.edges.put(edge.key(), edge);
      }
    }
    var attachment = new ContractModel.Edge("member-875", "/payment", "member-876", 1);
    destination.edges.put(attachment.key(), attachment);
    var topology = ContractTopology.of(destination);
    var source = new ContractModel.Snapshot("member-875");
    source.documents.put(
        "member-875", destination.documents.get("member-875").withBlueId("updated"));
    source.edges.put(attachment.key(), attachment);
    ContractCompletions.installMember(destination, source, "member-875");
    assertSame(topology, ContractTopology.of(destination));
    assertEquals("updated", destination.documents.get("member-875").blueId);
    assertEquals(1000, destination.edges.size());

    var changed = new ContractModel.Edge("member-875", "/payment", "member-877", 2);
    source.edges.put(changed.key(), changed);
    ContractCompletions.installMember(destination, source, "member-875");
    assertNotSame(topology, ContractTopology.of(destination));
    assertEquals(changed, destination.edges.get(changed.key()));
    source.edges.clear();
    ContractCompletions.installMember(destination, source, "member-875");
    assertNull(destination.edges.get(changed.key()));
    assertEquals(999, destination.edges.size());
  }

  private static final Language LANGUAGE =
      Language.newBuilder().mount("Contracts", Contracts.publication()).build();
  private static final Contracts CONFIGURATION = Contracts.newBuilder().language(LANGUAGE).build();

  @Test
  void twoImportedParentsRetainTheSameAlreadyInitializedDescendant() {
    Map<String, Object> source;
    try (Contracts.Execution creator = Contracts.Execution.using(CONFIGURATION, null)) {
      source =
          creator
              .initialize(creator.prepare("source", LANGUAGE.defineYaml("count: 1\ncontracts: {}")))
              .requireApplied()
              .after()
              .checkpoint();
    }
    List<Map<String, Object>> parents = new ArrayList<>();
    List<String> identities = new ArrayList<>();
    for (String role : List.of("buyer", "seller")) {
      try (Contracts.Execution creator = Contracts.Execution.using(CONFIGURATION, null)) {
        var child = creator.restore(source);
        Language author = LANGUAGE.toBuilder().content(child.document()).build();
        var initialized =
            creator
                .initialize(
                    creator.prepare(
                        role,
                        author.defineYaml(
                            """
                            role: ROLE
                            child: {blueId: CHILD}
                            contracts:
                              embedded: {type: Contracts/Process Embedded, paths: [/child]}
                            """
                                .replace("ROLE", role)
                                .replace("CHILD", child.blueId()))))
                .requireApplied()
                .after();
        parents.add(initialized.checkpoint());
        identities.add(initialized.blueId());
      }
    }
    try (Contracts.Execution creator = Contracts.Execution.using(CONFIGURATION, null)) {
      Language.Builder authorBuilder = LANGUAGE.toBuilder();
      parents.forEach(checkpoint -> authorBuilder.content(creator.restore(checkpoint).document()));
      Language author = authorBuilder.build();
      var root =
          creator
              .initialize(
                  creator.prepare(
                      "root",
                      author.defineYaml(
                          """
                          buyer: {blueId: BUYER}
                          seller: {blueId: SELLER}
                          contracts:
                            embedded: {type: Contracts/Process Embedded, paths: [/buyer, /seller]}
                          """
                              .replace("BUYER", identities.get(0))
                              .replace("SELLER", identities.get(1)))))
              .requireApplied()
              .after();
      assertEquals(
          Set.of("root", "buyer", "seller", "source"),
          new HashSet<>(root.members().stream().map(Contracts.Member::key).toList()));
      for (var edge : root.embeddings())
        if (edge.path().equals("/child")) assertEquals("source", edge.target());
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(GasSchedule.class)
  void receivingMultiRoundSourceKeepsEventOccurrencesEqualToWholeExecution(GasSchedule schedule) {
    Contracts configuration =
        Contracts.newBuilder().language(LANGUAGE).gasSchedule(schedule).build();
    Map<String, Contracts.Execution.Retained> retained = new HashMap<>();
    try (Contracts.Execution engine = Contracts.Execution.using(configuration, retained::get)) {
      Map<String, Object> members = new LinkedHashMap<>();
      List<String> ids = List.of("a", "b", "c", "d");
      for (int i = 0; i < ids.size(); i++) {
        String id = ids.get(i), next = ids.get((i + 1) % ids.size());
        Map<String, Object> member = receiver(next, id.equals("a") ? "done" : id);
        member.put("child", Contracts.Execution.link(next));
        if (id.equals("a")) {
          @SuppressWarnings("unchecked")
          var contracts = (Map<String, Object>) member.get("contracts");
          contracts.put(
              "input",
              Map.of("type", "Contracts/External Channel", "event", Map.of("kind", "begin")));
          contracts.put(
              "begin",
              Map.of(
                  "type",
                  "Contracts/Sequential Workflow",
                  "channel",
                  "input",
                  "steps",
                  List.of(
                      Map.of("type", "Contracts/Trigger Event", "event", Map.of("kind", "a")))));
        }
        members.put(id, member);
      }
      Map<String, Object> root = receiver("done", "received");
      root.put("child", Contracts.Execution.link("a"));
      members.put("root", root);
      var initial = engine.initialize(engine.prepare(members, "root")).requireApplied().after();
      var event = LANGUAGE.prepare(Language.Input.yaml("kind: begin"));
      var source =
          engine.process(initial.rooted("a"), event, 1, AdmissionEvidence.empty()).requireApplied();
      var receipt = source.transition().receipt();
      retained.put(
          receipt,
          new Contracts.Execution.Retained(
              source.before().checkpoint(),
              source.after().checkpoint(),
              source.transition().observations(),
              receipt,
              source.event(),
              source.timestamp()));
      var actual =
          engine
              .receive(
                  initial,
                  List.of(new Contracts.Execution.Source(receipt, ids)),
                  true,
                  AdmissionEvidence.empty())
              .requireApplied();
      var expected = engine.process(initial, event, 1, AdmissionEvidence.empty()).requireApplied();
      assertEquals(expected.after().blueId(), actual.after().blueId());
      assertEquals(rootEvents(expected), rootEvents(actual));
      if (schedule == GasSchedule.STANDARD)
        assertEquals(expected.transition().gas(), actual.transition().gas());
    }
  }

  private static List<?> rootEvents(Contracts.Execution.Outcome result) {
    return ((List<?>) result.transition().observations().get("events"))
        .stream()
            .map(event -> (Map<?, ?>) event)
            .filter(event -> event.get("document").equals("root"))
            .map(event -> event.get("identity"))
            .toList();
  }

  private static Map<String, Object> receiver(String accepted, String emitted) {
    return new org.yaml.snakeyaml.Yaml()
        .load(
            """
            count: 0
            contracts:
              embedded: {type: Contracts/Process Embedded, paths: [/child]}
              events: {type: Contracts/Embedded Node Channel, event: {kind: ACCEPTED}}
              react:
                type: Contracts/Sequential Workflow
                channel: events
                steps:
                  - type: Contracts/Update Document
                    changeset: [{op: replace, path: /count, val: {$add: [{$document: /count}, 1]}}]
                  - type: Contracts/Trigger Event
                    event: {kind: EMITTED}
            """
                .replace("ACCEPTED", accepted)
                .replace("EMITTED", emitted));
  }
}
