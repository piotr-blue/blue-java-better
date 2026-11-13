package blue;

import static blue.LanguageCore.LITERALS;
import static blue.LanguageCore.exactItems;
import static blue.LanguageCore.export;
import static blue.LanguageCore.finalId;
import static blue.LanguageCore.helper;
import static blue.LanguageCore.identity;
import static blue.LanguageCore.index;
import static blue.LanguageCore.list;
import static blue.LanguageCore.listId;
import static blue.LanguageCore.map;
import static blue.LanguageCore.normalize;
import static blue.LanguageCore.string;
import static blue.LanguageFailure.require;

import blue.ExactNode.Local;
import blue.ExactNode.Ref;
import blue.ExactNode.Value;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.IntFunction;

/** Canonicalizes symbolic owning sets and authenticates a member against its complete proof. */
final class LanguageOwningSet {
  private LanguageOwningSet() {}

  private static Set<Integer> locals(ExactNode n) {
    var out = new HashSet<Integer>();
    if (n instanceof Local l) {
      out.add(l.index());
    }
    if (n instanceof Value v) {
      for (var e : v.fields().entrySet()) {
        if (LITERALS.contains(e.getKey()) || e.getKey().equals("mergePolicy")) {
          continue;
        }
        if (e.getKey().equals("items")) {
          for (ExactNode a : exactItems(e.getValue())) {
            out.addAll(locals(a));
          }
        } else {
          out.addAll(locals((ExactNode) e.getValue()));
        }
      }
    }
    return out;
  }

  private static ExactNode localMap(ExactNode n, IntFunction<ExactNode> fn) {
    if (n instanceof Local l) {
      return fn.apply(l.index());
    }
    if (n instanceof Ref) {
      return n;
    }
    var m = new TreeMap<String, Object>();
    ((Value) n)
        .fields()
        .forEach(
            (k, v) ->
                m.put(
                    k,
                    LITERALS.contains(k) || k.equals("mergePolicy")
                        ? v
                        : k.equals("items")
                            ? (exactItems(v)).stream().map(a -> localMap(a, fn)).toList()
                            : localMap((ExactNode) v, fn)));
    return ExactNode.value(m);
  }

  record Result(
      String master, List<String> ids, List<ExactNode> canonical, List<ExactNode> bodies) {}

  /** A single owning set must both reach, and be reachable from, its first member. */
  private static boolean stronglyConnected(List<Set<Integer>> outgoing) {
    if (!reachesEveryMember(outgoing)) {
      return false;
    }
    var incoming = new ArrayList<List<Integer>>(outgoing.size());
    for (int i = 0; i < outgoing.size(); i++) {
      incoming.add(new ArrayList<>());
    }
    for (int i = 0; i < outgoing.size(); i++) {
      for (int neighbor : outgoing.get(i)) {
        incoming.get(neighbor).add(i);
      }
    }
    return reachesEveryMember(incoming);
  }

  private static boolean reachesEveryMember(List<? extends Iterable<Integer>> adjacency) {
    boolean[] visited = new boolean[adjacency.size()];
    var pending = new ArrayDeque<Integer>();
    pending.push(0);
    visited[0] = true;
    int seen = 1;
    while (!pending.isEmpty()) {
      for (int neighbor : adjacency.get(pending.pop())) {
        if (!visited[neighbor]) {
          visited[neighbor] = true;
          seen++;
          pending.push(neighbor);
        }
      }
    }
    return seen == adjacency.size();
  }

  static Result finalizeSet(List<?> raw, LanguageContext c) {
    require(!raw.isEmpty(), "CircularSetError");
    var ns = raw.stream().map(x -> normalize(x, c, "exact", true)).toList();
    require(ns.stream().allMatch(x -> x instanceof Value), "CircularSetError", "reference root");
    int size = ns.size();
    var edges = ns.stream().map(LanguageOwningSet::locals).toList();
    require(
        edges.stream().anyMatch(es -> !es.isEmpty())
            && edges.stream().flatMap(Set::stream).allMatch(i -> i >= 0 && i < size),
        "CircularSetError",
        "local target");
    require(stronglyConnected(edges), "CircularSetError", "not strongly connected");
    record Order(String id, String commitment, int index) {}
    var records = new ArrayList<Order>();
    IntFunction<String> zero = i -> "0".repeat(44);
    for (int i = 0; i < size; i++) {
      Value n = (Value) ns.get(i);
      Object commitment =
          n.fields().keySet().equals(Set.of("items"))
              ? Map.of(
                  "kind",
                  "list",
                  "elements",
                  (exactItems(n.get("items"))).stream().map(a -> identity(a, zero)).toList())
              : Map.of("kind", "map", "helper", helper(n, zero));
      records.add(new Order(identity(n, zero), LanguageCodec.write(commitment), i));
    }
    records.sort(Comparator.comparing(Order::id).thenComparing(Order::commitment));
    for (int i = 1; i < size; i++) {
      require(
          !records.get(i).id.equals(records.get(i - 1).id)
              || !records.get(i).commitment.equals(records.get(i - 1).commitment),
          "CircularSetError",
          "ambiguous preliminary members");
    }
    int[] positions = new int[size];
    for (int i = 0; i < size; i++) {
      positions[records.get(i).index] = i;
    }
    List<ExactNode> canonical =
        records.stream().map(r -> localMap(ns.get(r.index), i -> new Local(positions[i]))).toList();
    String master = listId(canonical, i -> "this#" + i);
    var ids = new ArrayList<String>();
    for (int i = 0; i < size; i++) {
      ids.add(master + "#" + positions[i]);
    }
    var bodies =
        canonical.stream()
            .map(
                n ->
                    normalize(
                        export(localMap(n, i -> new Ref(master + "#" + i))), c, "exact", false))
            .toList();
    return new Result(master, ids, canonical, bodies);
  }

  static Ref verifyMember(String expected, Object proof, Object body, LanguageContext c) {
    finalId(expected);
    require(expected.contains("#"), "InvalidCyclicProof");
    String master = expected.substring(0, expected.indexOf('#'));
    int j = index(expected.substring(expected.indexOf('#') + 1));
    if (proof instanceof String s) {
      proof = LanguageOperations.parse(s, "json", c);
    }
    Map<String, Object> pm = map(proof);
    require(pm.keySet().equals(Set.of("members")), "InvalidCyclicProof");
    Result set = finalizeSet(list(pm.get("members")), c);
    require(set.master.equals(master) && j < set.ids.size(), "InvalidCyclicProof");
    for (int i = 0; i < set.ids.size(); i++) {
      require(
          set.ids.get(i).equals(master + "#" + i), "InvalidCyclicProof", "canonical proof order");
    }
    ExactNode derived = set.bodies.get(j);
    if (body != null) {
      if (body instanceof String s) {
        body = LanguageOperations.parse(s, "json", c);
      }
      if (body instanceof Map<?, ?> m && m.containsKey("text")) {
        body =
            LanguageOperations.parse(
                string(m.get("text")),
                string(m.containsKey("format") ? m.get("format") : "json"),
                c);
      }
      ExactNode given = normalize(body, c, "exact", false);
      require(
          given instanceof Value && identity(given).equals(identity(derived)),
          "InvalidCyclicProof",
          "member body");
      derived = given;
    }
    c.bodies.put(expected, (Value) derived);
    // Retain owned immutable evidence so native responses can share it safely.
    c.proofs.put(expected, LanguageValues.freeze(proof));
    return new Ref(expected, (Value) derived);
  }
}
