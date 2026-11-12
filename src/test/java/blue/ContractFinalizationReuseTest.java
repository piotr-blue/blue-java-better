package blue;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** A cached reference check must still propagate child changes and survive independent forks. */
@Tag("contracts")
class ContractFinalizationReuseTest {
  @Test
  void changedChildPropagatesExactlyAsFullFinalizationWithoutTouchingTheOriginal() {
    ContractProcessor processor = new ContractProcessor();
    var original =
        processor.create(
                Map.of(
                    "children", Map.of("a", Map.of("count", 1), "b", Map.of("count", 2)),
                    "contracts",
                        Map.of(
                            "embedded",
                            Map.of(
                                "type",
                                "Contracts/Process Embedded",
                                "collectionPaths",
                                List.of("/children")))))
            .state;
    var before = original.identity();
    var cached = original.copy();
    var full = original.copy();
    full.documents.replaceAll(
        (key, document) ->
            new ContractModel.Document(
                key,
                document.body,
                document.blueId,
                document.birth,
                document.channelLives,
                document.channelSerials,
                document.edgeSerials));
    assertNotSame(full.documents.get(full.root), cached.documents.get(cached.root));
    assertNull(full.documents.get(full.root).finalizedLinks);
    String child =
        original.edges.values().stream()
            .filter(edge -> edge.path().equals("/children/a"))
            .findFirst()
            .orElseThrow()
            .target();
    for (var snapshot : List.of(cached, full)) {
      var graph = new ContractGraph(snapshot, processor.registry, new ContractGraph.Archive());
      var member = snapshot.documents.get(child);
      snapshot.documents.put(
          child,
          member.withBody(
              processor
                  .blue
                  .open(member.body)
                  .with("count", new ExactNode.Value(Map.of("value", BigInteger.TEN)))));
      graph.dirty.add(child);
      graph.finalizeGraph();
    }
    assertEquals(full.wire(), cached.wire());
    assertNotEquals(before, cached.identity());
    assertEquals(before, original.identity());
    assertNotEquals(
        original.documents.get(original.root).blueId, cached.documents.get(cached.root).blueId);
  }

  @Test
  void referenceReuseRequiresTheExactBodyIdentityTopologyAndTargetIdentity() {
    var state = new ContractModel.Snapshot("parent");
    var parent = new ContractModel.Document("parent", ExactNode.value(Map.of()), "parent-id", null);
    var child = new ContractModel.Document("child", ExactNode.value(Map.of()), "child-id", null);
    state.documents.put("parent", parent);
    state.documents.put("child", child);
    var edge = new ContractModel.Edge("parent", "/child", "child", 1);
    var links =
        new ContractGraph.FinalizedLinks(
            parent.body, parent.blueId, List.of(edge), List.of(child.blueId));
    assertTrue(links.matches(parent.copy(), List.of(edge), state));
    assertFalse(links.matches(parent, List.of(), state));
    assertFalse(
        links.matches(
            parent, List.of(new ContractModel.Edge("parent", "/child", "child", 2)), state));
    state.documents.put("child", child.withBlueId("changed-child"));
    assertFalse(links.matches(parent, List.of(edge), state));
    state.documents.put("child", child);
    parent =
        parent.withBody(
            ExactNode.value(Map.of("count", ExactNode.scalar("Integer", BigInteger.ONE))));
    assertFalse(links.matches(parent, List.of(edge), state));
  }
}
