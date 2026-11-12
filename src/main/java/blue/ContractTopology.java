package blue;

import static blue.ContractModel.segments;

import blue.ContractModel.Edge;
import blue.ContractModel.Snapshot;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Physical structure only. Never caches active/lifecycle eligibility or logical charges. */
final class ContractTopology {
  volatile ContractSchedule.Plan schedulePlan;
  final List<Edge> edges;
  final String root;
  final Object edgesStructure, membersStructure;
  final Map<String, List<String>> outgoing;
  final Set<String> live;
  private final Map<String, List<Edge>> outgoingEdges = new HashMap<>(),
      incomingEdges = new HashMap<>();
  private final Map<String, List<Edge>> orderedOutgoing =
      new java.util.concurrent.ConcurrentHashMap<>();
  private final Set<String> selfLinks = new HashSet<>();

  static ContractTopology of(Snapshot state) {
    if (state.topology == null || !state.topology.matches(state)) {
      ContractMetrics.count("schedule.topologyInvalidations");
      state.topology = new ContractTopology(state);
    }
    return state.topology;
  }

  List<Edge> outgoingEdges(String member) {
    return outgoingEdges.getOrDefault(member, List.of());
  }

  /** Containing-reference updates use decoded pointer order, not encoded map-key order. */
  List<Edge> orderedOutgoingEdges(String member) {
    List<Edge> edges = outgoingEdges(member);
    if (edges.size() < 2) return edges;
    return orderedOutgoing.computeIfAbsent(
        member,
        key ->
            edges.stream()
                .sorted(
                    (a, b) ->
                        ContractCanonical.compareSegments(segments(a.path()), segments(b.path())))
                .toList());
  }

  List<Edge> incomingEdges(String member) {
    return incomingEdges.getOrDefault(member, List.of());
  }

  boolean selfLinked(String member) {
    return selfLinks.contains(member);
  }

  List<Edge> groupEdges(Collection<String> members) {
    var result = new ArrayList<Edge>();
    for (String member : ContractCanonical.keys(members)) result.addAll(outgoingEdges(member));
    return result;
  }

  private final LinkedHashMap<Set<String>, List<List<String>>> partitions =
      new LinkedHashMap<>(8, .75f, true);

  ContractTopology(Snapshot state) {
    edges = List.copyOf(state.edges.values());
    root = state.root;
    edgesStructure = state.edges.structure();
    membersStructure = state.documents.structure();
    // Preserve map order for preflight charges; route order uses decoded pointer segments.
    for (Edge edge : state.edges.values()) {
      outgoingEdges.computeIfAbsent(edge.source(), k -> new ArrayList<>()).add(edge);
      incomingEdges.computeIfAbsent(edge.target(), k -> new ArrayList<>()).add(edge);
      if (edge.source().equals(edge.target())) selfLinks.add(edge.source());
    }
    incomingEdges.replaceAll(
        (member, edges) -> {
          ContractMetrics.add("graph.routeIndexSortEdges", edges.size());
          edges.sort(
              (a, b) -> {
                int c = ContractCanonical.compare(a.source(), b.source());
                if (c == 0)
                  c = ContractCanonical.compareSegments(segments(a.path()), segments(b.path()));
                return c == 0 ? Long.compare(a.generation(), b.generation()) : c;
              });
          return List.copyOf(edges);
        });
    outgoingEdges.replaceAll((member, edges) -> List.copyOf(edges));
    outgoing = new HashMap<>();
    outgoingEdges.forEach(
        (member, edges) -> outgoing.put(member, edges.stream().map(Edge::target).toList()));
    live = Collections.unmodifiableSet(ContractGraph.reachable(root, outgoing, k -> false));
    ContractMetrics.count("schedule.topologyBuilds");
    ContractMetrics.add("schedule.topologyEdgeVisits", state.edges.size());
  }

  boolean matches(Snapshot value) {
    return root.equals(value.root)
        && edgesStructure == value.edges.structure()
        && membersStructure == value.documents.structure();
  }

  synchronized List<List<String>> components(Collection<String> members) {
    var key = Set.copyOf(members);
    var found = partitions.get(key);
    if (found != null) {
      ContractMetrics.count("schedule.sccHits");
      return found;
    }
    ContractMetrics.count("schedule.sccBuilds");
    found = List.copyOf(ContractComponents.ordered(members, edges));
    if (partitions.size() == 8) partitions.remove(partitions.keySet().iterator().next());
    partitions.put(key, found);
    return found;
  }
}
