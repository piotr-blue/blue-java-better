package blue;

import static blue.ContractModel.require;

import blue.ContractModel.Edge;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;

/**
 * Phase-owned plan. Structural generations rebuild indexes; completion/reopening only adjusts
 * reverse dependencies. SCCs may split, but existing phase groups only merge. No part is exported
 * in a snapshot or copied to a continuation: cold/chunk execution reconstructs from phase facts.
 */
final class ContractSchedule {
  final ContractTopology topology;
  final Map<String, List<String>> owner;
  final Map<List<String>, Integer> positions;
  final Map<List<String>, Set<String>> groups;
  private final Map<List<String>, Set<List<String>>> dependencies, reverse;
  private final Map<List<String>, Integer> remaining = new HashMap<>();
  private final Set<List<String>> pending = new HashSet<>(), queued = new HashSet<>();
  private final PriorityQueue<List<String>> ready =
      new PriorityQueue<>(ContractCanonical::compareSegments);

  /** Immutable topology plan. Completion, reopening and readiness always belong to a phase. */
  record Plan(
      Set<String> allowed,
      List<Set<String>> phaseGroups,
      Map<String, List<String>> owner,
      Map<List<String>, Integer> positions,
      Map<List<String>, Set<String>> groups,
      Map<List<String>, Set<List<String>>> dependencies,
      Map<List<String>, Set<List<String>>> reverse) {}

  ContractSchedule(ContractRun.Phase phase, ContractTopology topology) {
    this(phase, topology, false);
  }

  ContractSchedule(ContractRun.Phase phase, ContractTopology topology, boolean sparse) {
    this.topology = topology;
    phase.done.removeAll(phase.idle);
    phase.idle.clear();
    if (!phase.barrier) phase.allowed.addAll(topology.live);
    Plan plan = topology.schedulePlan;
    if (plan == null
        || !plan.allowed.equals(phase.allowed)
        || !plan.phaseGroups.equals(phase.groups)) {
      plan = compile(phase, topology);
      topology.schedulePlan = plan;
    } else ContractMetrics.count("schedule.planHits");
    owner = plan.owner;
    positions = plan.positions;
    groups = plan.groups;
    dependencies = plan.dependencies;
    reverse = plan.reverse;
    for (var entry : groups.entrySet())
      if (!phase.barrier && entry.getValue().stream().noneMatch(topology.live::contains))
        phase.done.add(entry.getKey());
    if (sparse) completeIdleBranches(phase);
    for (var entry : dependencies.entrySet()) {
      int count = 0;
      for (var dependency : entry.getValue()) if (!phase.done.contains(dependency)) count++;
      remaining.put(entry.getKey(), count);
      if (!phase.done.contains(entry.getKey())) pending.add(entry.getKey());
    }
    phase.doneChanges.clear();
    for (var id : pending) enqueue(id);
    ContractMetrics.count("schedule.builds");
  }

  /**
   * A singleton without inputs, held output, or a working descendant cannot produce a turn.
   * Complete that entire quiet branch together instead of pushing every member through the priority
   * queue. Cyclic units retain their ordinary seals, even when they have no inputs.
   */
  private void completeIdleBranches(ContractRun.Phase phase) {
    Set<List<String>> working = new HashSet<>();
    var queue = new ArrayDeque<List<String>>();
    java.util.function.Consumer<String> include =
        member -> {
          var id = owner.get(member);
          if (id != null && !phase.done.contains(id) && working.add(id)) queue.add(id);
        };
    phase.initialization.keySet().forEach(include);
    phase.pending.forEach(
        (member, inputs) -> {
          if (!inputs.isEmpty()) include.accept(member);
        });
    phase.seeds.forEach(
        (member, inputs) -> {
          if (!inputs.isEmpty()) include.accept(member);
        });
    for (var batch : phase.held) {
      include.accept(batch.source);
      batch.tickets.forEach(ticket -> include.accept(ticket.parent()));
    }
    for (var id : groups.keySet())
      if (id.size() > 1 && !phase.done.contains(id) && working.add(id)) queue.add(id);
    while (!queue.isEmpty())
      for (var parent : reverse.get(queue.remove()))
        if (!phase.done.contains(parent) && working.add(parent)) queue.add(parent);
    for (var id : groups.keySet())
      if (!working.contains(id) && phase.done.add(id)) phase.idle.add(id);
    ContractMetrics.add("schedule.idleBranchesCompleted", phase.idle.size());
  }

  private static Plan compile(ContractRun.Phase phase, ContractTopology topology) {
    var ownership = new HashMap<String, Integer>();
    for (int i = 0; i < phase.groups.size(); i++)
      for (String member : phase.groups.get(i)) ownership.put(member, i);
    for (String member : phase.allowed)
      if (!ownership.containsKey(member)) {
        ownership.put(member, phase.groups.size());
        phase.groups.add(ContractCanonical.keys(Set.of(member)));
      }
    for (var component : topology.components(phase.allowed)) {
      var touched = new HashSet<Integer>();
      for (String member : component) touched.add(ownership.get(member));
      ContractMetrics.add("schedule.promotionMemberVisits", component.size());
      if (touched.size() > 1) {
        var union = new TreeSet<String>(ContractCanonical.CODE_POINTS);
        for (int i : touched) {
          var group = phase.groups.get(i);
          union.addAll(group);
          phase.done.remove(List.copyOf(group));
          phase.groups.set(i, Set.of());
        }
        int position = phase.groups.size();
        phase.groups.add(union);
        for (String member : union) ownership.put(member, position);
      }
    }
    phase.groups.removeIf(Set::isEmpty);
    Map<String, List<String>> owner = new HashMap<>();
    Map<List<String>, Integer> positions = new HashMap<>();
    Map<List<String>, Set<String>> groups = new LinkedHashMap<>();
    Map<List<String>, Set<List<String>>> dependencies = new HashMap<>(), reverse = new HashMap<>();
    for (var group : phase.groups) {
      var id = List.copyOf(ContractCanonical.keys(group));
      positions.put(id, groups.size());
      groups.put(id, Set.copyOf(group));
      dependencies.put(id, new HashSet<>());
      reverse.put(id, new HashSet<>());
      for (String member : group) owner.put(member, id);
    }
    for (Edge edge : topology.edges) {
      var from = owner.get(edge.source());
      var to = owner.get(edge.target());
      ContractMetrics.count("schedule.dependencyEdgeVisits");
      if (from != null && to != null && !from.equals(to)) dependencies.get(from).add(to);
    }
    dependencies.forEach(
        (group, values) -> values.forEach(dependency -> reverse.get(dependency).add(group)));
    dependencies.replaceAll((key, value) -> Set.copyOf(value));
    reverse.replaceAll((key, value) -> Set.copyOf(value));
    ContractMetrics.count("schedule.planBuilds");
    ContractMetrics.count("schedule.ownerMapBuilds");
    ContractMetrics.add("schedule.ownerMapEntries", owner.size());
    return new Plan(
        Set.copyOf(phase.allowed),
        phase.groups.stream().map(Set::copyOf).toList(),
        Map.copyOf(owner),
        Map.copyOf(positions),
        Collections.unmodifiableMap(groups),
        Map.copyOf(dependencies),
        Map.copyOf(reverse));
  }

  private void enqueue(List<String> id) {
    if (pending.contains(id) && remaining.get(id) == 0 && queued.add(id)) {
      ready.add(id);
      ContractMetrics.count("schedule.readyInsertions");
    }
  }

  private void sync(ContractRun.Phase phase) {
    // Historical routes can deliver a batch outside the current dependency graph. If that
    // wakes a skipped member, restore the ordinary queue before selecting any further work.
    // Topology changes reconstruct the plan and independently reconsider all idle branches.
    if (phase.doneChanges.stream()
        .anyMatch(change -> !change.getValue() && phase.idle.contains(change.getKey()))) {
      for (var id : phase.idle) phase.completed(id, false);
      phase.idle.clear();
      ContractMetrics.count("schedule.idleFallbacks");
    }
    for (var change : phase.doneChanges) {
      var id = change.getKey();
      if (!groups.containsKey(id)) continue;
      if (change.getValue()) pending.remove(id);
      else pending.add(id);
      for (var dependent : reverse.get(id)) {
        remaining.compute(dependent, (k, n) -> n + (change.getValue() ? -1 : 1));
        enqueue(dependent);
        ContractMetrics.count("schedule.dependencyUpdates");
      }
      enqueue(id);
    }
    phase.doneChanges.clear();
  }

  Set<String> select(ContractRun.Phase phase) {
    sync(phase);
    while (!ready.isEmpty()) {
      var id = ready.peek();
      if (pending.contains(id) && remaining.get(id) == 0) {
        ContractMetrics.count("schedule.readySelections");
        return ContractCanonical.keys(id);
      }
      ready.remove();
      queued.remove(id);
      ContractMetrics.count("schedule.readyRemovals");
    }
    require(pending.isEmpty(), "UNSEALED_DEPENDENCY");
    return null;
  }

  Set<String> containing(Collection<String> members) {
    List<String> chosen = null;
    for (String member : members) {
      var id = owner.get(member);
      if (id != null && (chosen == null || positions.get(id) < positions.get(chosen))) chosen = id;
    }
    return ContractCanonical.keys(chosen == null ? members : groups.get(chosen));
  }

  Set<List<String>> dependencies(Collection<String> members) {
    var result = new HashSet<List<String>>();
    for (String member : members) {
      var id = owner.get(member);
      if (id != null) result.addAll(dependencies.get(id));
    }
    return result;
  }
}
