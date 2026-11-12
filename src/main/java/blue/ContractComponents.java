package blue;

import static blue.ContractModel.require;

import blue.ContractModel.Edge;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;

/** Strongly connected embedding components in canonical execution order, with children first. */
final class ContractComponents {
  private final Map<String, Set<String>> children = new HashMap<>();
  private final Map<String, Set<String>> parents = new HashMap<>();

  private ContractComponents(Collection<String> members, Collection<Edge> edges) {
    for (String member : members) {
      children.put(member, new HashSet<>());
      parents.put(member, new HashSet<>());
    }
    for (Edge edge : edges) {
      if (children.containsKey(edge.source()) && children.containsKey(edge.target())) {
        children.get(edge.source()).add(edge.target());
        parents.get(edge.target()).add(edge.source());
      }
    }
  }

  static List<List<String>> ordered(Collection<String> members, Collection<Edge> edges) {
    var graph = new ContractComponents(members, edges);
    return graph.childrenFirst(graph.partition());
  }

  private record Visit(String member, boolean leaving) {}

  /** First pass of Kosaraju's algorithm. An explicit stack also handles deep embedding chains. */
  private List<String> finishingOrder() {
    Set<String> seen = new HashSet<>();
    List<String> finished = new ArrayList<>();
    for (String root : ContractCanonical.keys(children.keySet())) {
      var pending = new ArrayDeque<Visit>();
      pending.push(new Visit(root, false));
      while (!pending.isEmpty()) {
        Visit visit = pending.pop();
        if (visit.leaving()) {
          finished.add(visit.member());
        } else if (seen.add(visit.member())) {
          pending.push(new Visit(visit.member(), true));
          for (String child :
              children.get(visit.member()).stream()
                  .sorted(ContractCanonical.CODE_POINTS.reversed())
                  .toList()) {
            if (!seen.contains(child)) {
              pending.push(new Visit(child, false));
            }
          }
        }
      }
    }
    return finished;
  }

  private List<List<String>> partition() {
    Set<String> seen = new HashSet<>();
    List<List<String>> groups = new ArrayList<>();
    for (String root : finishingOrder().reversed()) {
      if (seen.contains(root)) {
        continue;
      }
      var group = new TreeSet<String>(ContractCanonical.CODE_POINTS);
      var pending = new ArrayDeque<String>();
      pending.push(root);
      while (!pending.isEmpty()) {
        String member = pending.pop();
        if (seen.add(member)) {
          group.add(member);
          pending.addAll(parents.get(member));
        }
      }
      groups.add(List.copyOf(group));
    }
    return groups;
  }

  /**
   * Members are immutable labels. Bookkeeping uses component identity so a large cycle's list does
   * not have to be hashed each time an edge is inspected.
   */
  private static final class Component {
    final List<String> members;
    final Set<Component> dependents = new HashSet<>();
    int pendingChildren;

    Component(List<String> members) {
      this.members = members;
    }
  }

  /** Order the condensation DAG. When independent components are ready, compare member names. */
  private List<List<String>> childrenFirst(List<List<String>> groups) {
    List<Component> components = groups.stream().map(Component::new).toList();
    Map<String, Component> membership = new HashMap<>();
    for (var component : components) {
      for (String member : component.members) {
        membership.put(member, component);
      }
    }
    children.forEach(
        (member, targets) -> {
          Component parent = membership.get(member);
          for (String target : targets) {
            Component child = membership.get(target);
            if (parent != child && child.dependents.add(parent)) {
              parent.pendingChildren++;
            }
          }
        });

    var ready =
        new PriorityQueue<Component>(
            Comparator.comparing(
                component -> component.members, ContractCanonical::compareSegments));
    for (var component : components) {
      if (component.pendingChildren == 0) {
        ready.add(component);
      }
    }
    List<List<String>> ordered = new ArrayList<>(components.size());
    while (!ready.isEmpty()) {
      var child = ready.remove();
      ordered.add(child.members);
      for (var parent : child.dependents) {
        if (--parent.pendingChildren == 0) {
          ready.add(parent);
        }
      }
    }
    require(ordered.size() == components.size(), "INTERNAL_COMPONENT_ORDER");
    return ordered;
  }
}
