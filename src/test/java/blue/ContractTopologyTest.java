package blue;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("contracts")
class ContractTopologyTest {
  @Test
  void deferredAliasesFreezeReachabilityAndIdentityAndKeepSpeculationPrivate() {
    var source = new ContractModel.Snapshot("root");
    for (String key : List.of("root", "a", "b", "detached"))
      source.documents.put(
          key, new ContractModel.Document(key, ExactNode.value(Map.of()), key, null));
    var a = new ContractModel.Edge("root", "/a", "a", 1);
    source.edges.put(a.key(), a);
    var aliases = new ContractGraph.Aliases(source);
    var unopenedCopy = aliases.copy();
    source.documents.put("a", source.documents.get("a").withBlueId("changed"));
    source.edges.remove(a.key());
    var b = new ContractModel.Edge("root", "/b", "b", 1);
    source.edges.put(b.key(), b);
    assertEquals(Set.of("a"), aliases.get("a"));
    assertTrue(aliases.get("changed").isEmpty());
    assertTrue(aliases.get("b").isEmpty());
    assertTrue(aliases.get("detached").isEmpty());
    aliases.add("a", "newly-imported");
    assertEquals(Set.of("a"), unopenedCopy.get("a"));
    var openedCopy = aliases.copy();
    openedCopy.add("a", "speculative");
    assertEquals(Set.of("a", "newly-imported"), aliases.get("a"));
    assertEquals(Set.of("a", "newly-imported", "speculative"), openedCopy.get("a"));
    var refreshed = new ContractGraph.Aliases(source);
    assertTrue(refreshed.get("a").isEmpty());
    assertEquals(Set.of("b"), refreshed.get("b"));
  }

  @Test
  void componentOrderIsStableAcrossInputPermutationsDuplicateLinksAndLongCycles() {
    var vertices =
        new java.util.ArrayList<>(
            List.of("root", "a", "b", "c", "d", "leaf", "\uE000", "\uD83D\uDE00"));
    var edges = new java.util.ArrayList<ContractModel.Edge>();
    for (String[] pair :
        List.of(
            new String[] {"root", "a"},
            new String[] {"a", "b"},
            new String[] {"a", "b"},
            new String[] {"b", "c"},
            new String[] {"c", "d"},
            new String[] {"d", "a"},
            new String[] {"d", "leaf"},
            new String[] {"\uE000", "\uD83D\uDE00"},
            new String[] {"\uD83D\uDE00", "\uE000"}))
      edges.add(new ContractModel.Edge(pair[0], "/edge-" + edges.size(), pair[1], 1));
    var expected =
        List.of(
            List.of("leaf"),
            List.of("a", "b", "c", "d"),
            List.of("root"),
            List.of("\uE000", "\uD83D\uDE00"));
    var random = new java.util.Random(731);
    for (int i = 0; i < 30; i++) {
      java.util.Collections.shuffle(vertices, random);
      java.util.Collections.shuffle(edges, random);
      assertEquals(expected, ContractComponents.ordered(vertices, edges));
    }
  }

  @Test
  void outgoingRangeIsOrderedAndStableAcrossRemovalWithoutScanningSiblingEdges() {
    var state = new ContractModel.Snapshot("root");
    for (String member : List.of("a", "root", "root-extra", "z"))
      for (String path : List.of("/b", "/a", "/a~1")) {
        var edge = new ContractModel.Edge(member, path, "child", 1);
        state.edges.put(edge.key(), edge);
      }
    var selected = state.outgoingEdges("root");
    assertEquals(
        List.of("/a", "/a~1", "/b"), selected.stream().map(ContractModel.Edge::path).toList());
    for (var edge : selected) state.edges.remove(edge.key());
    assertEquals(3, selected.size());
    assertEquals(9, state.edges.size());
    assertTrue(state.outgoingEdges("root").isEmpty());
    assertTrue(state.outgoingEdges("missing").isEmpty());
    var visited = new java.util.concurrent.atomic.AtomicInteger();
    state.edges.valuesFrom(
        new ContractModel.EdgeKey("root-extra", ""),
        key -> {
          visited.incrementAndGet();
          return key.source().equals("root-extra");
        });
    assertEquals(4, visited.get(), "Only the selected range and its first successor are inspected");
  }

  @Test
  void containingReferenceOrderIsDecodedAndUnaffectedByOtherMembers() {
    var state = new ContractModel.Snapshot("root");
    for (String key : List.of("root", "child", "other"))
      state.documents.put(key, new ContractModel.Document(key, ExactNode.value(Map.of())));
    for (String path : List.of("/a~0", "/a0", "/a~1", "/a/b", "/a")) {
      var edge = new ContractModel.Edge("root", path, "child", 1);
      state.edges.put(edge.key(), edge);
    }
    var unrelated = new ContractModel.Edge("other", "/first", "child", 1);
    state.edges.put(unrelated.key(), unrelated);
    var topology = ContractTopology.of(state);
    var ordered = topology.orderedOutgoingEdges("root");
    assertEquals(
        List.of("/a", "/a/b", "/a~1", "/a0", "/a~0"),
        ordered.stream().map(ContractModel.Edge::path).toList());
    assertSame(ordered, topology.orderedOutgoingEdges("root"));
    assertThrows(UnsupportedOperationException.class, () -> ordered.clear());
    assertTrue(topology.orderedOutgoingEdges("child").isEmpty());
    var fork = state.copy();
    var changed = new ContractModel.Edge("root", "/a!", "child", 1);
    fork.edges.put(changed.key(), changed);
    assertEquals(6, ContractTopology.of(fork).orderedOutgoingEdges("root").size());
    assertSame(ordered, ContractTopology.of(state).orderedOutgoingEdges("root"));
  }

  @Test
  void snapshotCopiesShareTopologyButKeepDocumentsAndEdgesIndependent() {
    var source = new ContractModel.Snapshot("a");
    source.documents.put("a", new ContractModel.Document("a", ExactNode.value(Map.of())));
    source.documents.put("b", new ContractModel.Document("b", ExactNode.value(Map.of())));
    source.edges.put(
        new ContractModel.EdgeKey("a", "/peer"), new ContractModel.Edge("a", "/peer", "b", 1));
    source.edges.put(
        new ContractModel.EdgeKey("b", "/peer"), new ContractModel.Edge("b", "/peer", "a", 1));
    var topology = ContractTopology.of(source);
    assertEquals(List.of(List.of("a", "b")), topology.components(Set.of("a", "b")));
    var fork = source.copy();
    assertSame(topology, ContractTopology.of(fork));
    assertSame(source.documents.get("a"), fork.documents.get("a"));
    fork.documents.put("a", fork.documents.get("a").withChannelSerial("input", 1L));
    assertTrue(source.documents.get("a").channelSerials.isEmpty());
    fork.edges.remove(new ContractModel.EdgeKey("b", "/peer"));
    assertNotSame(topology, ContractTopology.of(fork));
    assertSame(topology, ContractTopology.of(source));
    assertEquals(List.of(List.of("a", "b")), topology.components(Set.of("a", "b")));
    assertEquals(2, source.edges.size());
    var changed = ContractTopology.of(fork);
    fork.documents.put("c", new ContractModel.Document("c", ExactNode.value(Map.of())));
    assertNotSame(changed, ContractTopology.of(fork));
  }

  @Test
  void localTurnsIsolateTheirWriterAndGraphStructureWhileBorrowingSealedDependencies() {
    var source = new ContractModel.Snapshot("a");
    for (String key : List.of("a", "b"))
      source.documents.put(key, new ContractModel.Document(key, ExactNode.value(Map.of())));
    var edge = new ContractModel.Edge("a", "/peer", "b", 1);
    source.edges.put(edge.key(), edge);
    var a = source.copyForLocalTurn("a");
    var b = source.copyForLocalTurn("b");
    assertSame(source.documents.get("b"), a.documents.get("b"));
    assertSame(source.documents.get("a"), b.documents.get("a"));
    a.documents.put("a", a.documents.get("a").withChannelSerial("a-only", 1L));
    b.documents.put("b", b.documents.get("b").withChannelSerial("b-only", 1L));
    a.edges.remove(edge.key());
    assertTrue(source.documents.get("a").channelSerials.isEmpty());
    assertTrue(source.documents.get("b").channelSerials.isEmpty());
    assertTrue(a.documents.get("b").channelSerials.isEmpty());
    assertTrue(b.documents.get("a").channelSerials.isEmpty());
    assertEquals(1, source.edges.size());
    assertEquals(1, b.edges.size());
    var archived = a.copy();
    archived.documents.put("b", archived.documents.get("b").withChannelSerial("archive-only", 1L));
    assertTrue(source.documents.get("b").channelSerials.isEmpty());
  }

  @Test
  void schedulePlansAreSharedButCompletionAndCyclicPromotionRemainPrivate() {
    var source = new ContractModel.Snapshot("a");
    for (String key : List.of("a", "b", "c"))
      source.documents.put(key, new ContractModel.Document(key, ExactNode.value(Map.of())));
    for (String[] edge :
        List.of(new String[] {"a", "b"}, new String[] {"b", "c"}, new String[] {"c", "b"})) {
      var value = new ContractModel.Edge(edge[0], "/peer", edge[1], 1);
      source.edges.put(value.key(), value);
    }
    var topology = ContractTopology.of(source);
    var first = phase(topology);
    var second = phase(topology);
    var one = new ContractSchedule(first, topology);
    var two = new ContractSchedule(second, topology);
    assertSame(one.owner, two.owner);
    assertEquals(Set.of("b", "c"), one.select(first));
    first.completed(List.of("b", "c"), true);
    assertEquals(Set.of("a"), one.select(first));
    assertEquals(Set.of("b", "c"), two.select(second));
    var closing = new ContractModel.Edge("c", "/root", "a", 1);
    source.edges.put(closing.key(), closing);
    var promoted = new ContractSchedule(first, ContractTopology.of(source));
    assertEquals(Set.of("a", "b", "c"), promoted.select(first));
    assertEquals(Set.of("b", "c"), two.select(second));
  }

  private static ContractRun.Phase phase(ContractTopology topology) {
    var phase = new ContractRun.Phase();
    phase.allowed = ContractCanonical.keys(topology.live);
    for (var group : topology.components(topology.live))
      phase.groups.add(ContractCanonical.keys(group));
    return phase;
  }

  @Test
  void sparseScheduleKeepsWorkingAncestorsButDoesNotQueueQuietBranches() {
    var source = schedulingTree(1000);
    var topology = ContractTopology.of(source);
    var phase = phase(topology);
    phase.initialization.put("leaf-875", "initial");
    var schedule = new ContractSchedule(phase, topology, true);
    assertEquals(Set.of("leaf-875"), schedule.select(phase));
    phase.completed(List.of("leaf-875"), true);
    assertEquals(Set.of("branch-875"), schedule.select(phase));
    phase.completed(List.of("branch-875"), true);
    assertEquals(Set.of("root"), schedule.select(phase));
    phase.completed(List.of("root"), true);
    assertNull(schedule.select(phase));
  }

  @Test
  void changedTopologyReconsidersPreviouslyQuietAncestors() {
    var source = schedulingTree(2);
    var phase = phase(ContractTopology.of(source));
    phase.initialization.put("leaf-0", "initial");
    new ContractSchedule(phase, ContractTopology.of(source), true);
    var link = new ContractModel.Edge("branch-1", "/shared", "leaf-0", 1);
    source.edges.put(link.key(), link);
    var schedule = new ContractSchedule(phase, ContractTopology.of(source), true);
    assertEquals(Set.of("leaf-0"), schedule.select(phase));
    phase.completed(List.of("leaf-0"), true);
    assertEquals(Set.of("branch-0"), schedule.select(phase));
    phase.completed(List.of("branch-0"), true);
    assertEquals(Set.of("branch-1"), schedule.select(phase));
    phase.completed(List.of("branch-1"), true);
    assertEquals(Set.of("root"), schedule.select(phase));
  }

  @Test
  void historicalDeliveryToAQuietBranchRestoresOrdinaryDependencyOrdering() {
    var source = schedulingTree(2);
    var phase = phase(ContractTopology.of(source));
    var schedule = new ContractSchedule(phase, ContractTopology.of(source), true);
    assertNull(schedule.select(phase));
    // A captured route may still deliver after its physical embedding edge changed.
    phase.completed(List.of("leaf-1"), false);
    for (String expected : List.of("leaf-0", "branch-0", "leaf-1", "branch-1", "root")) {
      assertEquals(Set.of(expected), schedule.select(phase));
      phase.completed(List.of(expected), true);
    }
    assertNull(schedule.select(phase));
  }

  @Test
  void sparseScheduleRetainsCyclicUnitsAndTheirWaitingAncestors() {
    var source = schedulingTree(1);
    var closing = new ContractModel.Edge("leaf-0", "/back", "branch-0", 1);
    source.edges.put(closing.key(), closing);
    var phase = phase(ContractTopology.of(source));
    var schedule = new ContractSchedule(phase, ContractTopology.of(source), true);
    assertEquals(Set.of("branch-0", "leaf-0"), schedule.select(phase));
    phase.completed(List.of("branch-0", "leaf-0"), true);
    assertEquals(Set.of("root"), schedule.select(phase));
  }

  private static ContractModel.Snapshot schedulingTree(int branches) {
    var state = new ContractModel.Snapshot("root");
    state.documents.put("root", new ContractModel.Document("root", ExactNode.value(Map.of())));
    for (int i = 0; i < branches; i++) {
      String branch = "branch-" + i, leaf = "leaf-" + i;
      for (String key : List.of(branch, leaf))
        state.documents.put(key, new ContractModel.Document(key, ExactNode.value(Map.of())));
      for (var edge :
          List.of(
              new ContractModel.Edge("root", "/" + branch, branch, 1),
              new ContractModel.Edge(branch, "/leaf", leaf, 1))) state.edges.put(edge.key(), edge);
    }
    return state;
  }

  @Test
  void lookupIndexTracksAllMutationPathsAndComparatorAliases() {
    var values = new TopologyMap<String, Integer>(false, String.CASE_INSENSITIVE_ORDER);
    values.put("A", 1);
    assertEquals(1, values.get("A"));
    values.put("a", 2);
    assertEquals(2, values.get("A"));
    assertEquals(2, values.get("a"));
    values.entrySet().iterator().next().setValue(3);
    assertEquals(3, values.get("A"));
    values.remove("a");
    assertNull(values.get("A"));
    values.put("b", 4);
    assertEquals(4, values.get("b"));
    var iterator = values.entrySet().iterator();
    iterator.next();
    iterator.remove();
    assertNull(values.get("b"));
    values.put("c", 5);
    assertEquals(5, values.get("c"));
    values.clear();
    assertNull(values.get("c"));
  }

  @Test
  void entryMutationAndIteratorRemovalInvalidateOnlyTheAffectedFork() {
    var source = new TopologyMap<String, Integer>(false);
    source.put("a", 1);
    var copy = new TopologyMap<String, Integer>(false);
    copy.copyFrom(source);
    Object original = source.structure();
    assertSame(original, copy.structure());
    copy.entrySet().iterator().next().setValue(2);
    assertNotSame(original, copy.structure());
    assertEquals(1, source.get("a"));
    Object changed = copy.structure();
    var iterator = copy.entrySet().iterator();
    iterator.next();
    iterator.remove();
    assertNotSame(changed, copy.structure());
    assertEquals(Set.of("a"), source.keySet());
  }
}
