package blue;

import static blue.ContractLanguage.ABSENT;
import static blue.ContractLanguage.identity;
import static blue.ContractModel.arr;
import static blue.ContractModel.digest;
import static blue.ContractModel.integer;
import static blue.ContractModel.m;
import static blue.ContractModel.obj;
import static blue.ContractModel.pointer;
import static blue.ContractModel.prefix;
import static blue.ContractModel.require;
import static blue.ContractModel.segments;
import static blue.ContractModel.str;
import static blue.ContractModel.token;

import blue.ContractModel.Document;
import blue.ContractModel.Edge;
import blue.ContractModel.EdgeKey;
import blue.ContractModel.Meter;
import blue.ContractModel.Snapshot;
import blue.ExactNode.Local;
import blue.ExactNode.Ref;
import blue.ExactNode.Value;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

final class ContractGraph {
  record Link(String document) {}

  /** Exact immutable inputs to a previously completed containing-reference check. */
  record FinalizedLinks(ExactNode body, String blueId, List<Edge> edges, List<String> targets) {
    boolean matches(Document document, List<Edge> current, Snapshot state) {
      if (body != document.body || !blueId.equals(document.blueId) || !edges.equals(current)) {
        return false;
      }
      for (int i = 0; i < current.size(); i++) {
        if (!targets.get(i).equals(state.documents.get(current.get(i).target()).blueId)) {
          return false;
        }
      }
      return true;
    }
  }

  static final class Archive {
    final LanguageMap<String, Snapshot> versions = new LanguageMap<>();
    final LanguageMap<String, LanguageTrie<String, Boolean>> roots = new LanguageMap<>();
    final LanguageMap<String, String> choices = new LanguageMap<>();

    void add(Snapshot s) {
      String id = s.identity(), root = s.documents.get(s.root).blueId;
      require(
          !versions.containsKey(id)
              || ContractWire.write(versions.get(id).wire()).equals(ContractWire.write(s.wire())),
          "CONFLICTING_PROCESS_VIEW");
      versions.put(id, s.copy());
      include(root, id);
    }

    private void include(String root, String id) {
      roots.put(root, roots.getOrDefault(root, LanguageTrie.empty()).with(id, Boolean.TRUE));
    }

    /** Share persistent indices and privately frozen records; subsequent writes stay private. */
    void copyTo(Archive target) {
      target.versions.putAll(versions);
      target.roots.putAll(roots);
      target.choices.putAll(choices);
    }

    Map<String, String> bindings() {
      var out = new TreeMap<String, String>(ContractCanonical.CODE_POINTS);
      roots.forEach(
          (k, ids) -> {
            String selected = choices.get(k);
            if (selected == null && ids.size() == 1) {
              selected = ids.keySet().iterator().next();
            }
            if (selected != null) {
              out.put(k, selected);
            }
          });
      return out;
    }

    Archive restrict(Map<String, String> bindings) {
      var a = new Archive();
      bindings.forEach(
          (k, id) -> {
            require(
                roots.getOrDefault(k, LanguageTrie.empty()).containsKey(id),
                "UNKNOWN_PROCESS_VIEW");
            // Archive values are privately frozen on insertion. A restricted view borrows
            // them; get() still returns an independently mutable copy to its caller.
            a.versions.put(id, versions.get(id));
            a.include(k, id);
            a.choices.put(k, id);
          });
      return a;
    }

    Snapshot get(String id) {
      var candidates = roots.get(id);
      if (candidates == null) {
        return null;
      }
      String selected = choices.get(id);
      if (selected == null && candidates.size() == 1) {
        selected = candidates.keySet().iterator().next();
      }
      require(selected != null, "AMBIGUOUS_PROCESS_VIEW", "blueId", id);
      return versions.get(selected).copy();
    }
  }

  Snapshot state;
  final ContractRegistry registry;
  final ContractBridge blue;
  final Archive archive;
  final Meter meter;
  String cause;
  Aliases aliases;
  Set<EdgeKey> resets = new HashSet<>();
  List<String> added = new ArrayList<>();
  List<Object> changes = new ArrayList<>();
  boolean topologyChanged;
  int allocation;
  ContractBirth append;
  Set<String> dirty = new HashSet<>();
  Finalized finalized;
  List<List<String>> partition;
  Set<String> pathHeaders = new HashSet<>();
  Set<List<Object>> verifiedBindings = new HashSet<>();
  ContractWork logical;

  ContractGraph(Snapshot s, ContractRegistry r, Archive a) {
    this(s, r, a, null, null);
  }

  ContractGraph(Snapshot s, ContractRegistry r, Archive a, Meter m, String cause) {
    this(s, r, a, m, cause, null);
  }

  /** Local turns keep the invocation ledger; constructing a disposable replacement has no value. */
  ContractGraph(
      Snapshot s, ContractRegistry r, Archive a, Meter m, String cause, ContractWork inherited) {
    state = s;
    registry = r;
    blue = r.blue;
    archive = a;
    meter = m;
    this.cause = cause;
    refreshAliases();
    finalized = new Finalized(s.documents);
    if (m != null) {
      logical = inherited == null ? new ContractWork(blue, m) : inherited;
      logical.noteRoots(s.documents);
      if (inherited == null) {
        for (Document d : s.documents.values()) {
          if (!d.blueId.isEmpty()) {
            logical.carriedRoots.add(d.blueId);
          }
        }
      }
      if (inherited == null) {
        logical.boundIdentities.addAll(r.types.values());
        logical.boundIdentities.add(r.specification);
      }
    }
  }

  /** The starting immutable roots are a shared baseline; only finalized edits need an overlay. */
  static final class Finalized {
    private final TopologyMap<String, Document> baseline;
    private final Map<String, ExactNode> changes = new HashMap<>();

    Finalized(TopologyMap<String, Document> documents) {
      baseline = new TopologyMap<>(true, ContractCanonical.CODE_POINTS, Document::sameMetadata);
      baseline.copyFrom(documents);
    }

    ExactNode get(String key) {
      if (changes.containsKey(key)) {
        return changes.get(key);
      }
      Document document = baseline.get(key);
      return document == null || document.blueId.isEmpty() ? null : document.body;
    }

    void put(String key, ExactNode value) {
      changes.put(key, value);
    }

    Finalized copy() {
      var copy = new Finalized(baseline);
      copy.changes.putAll(changes);
      return copy;
    }
  }

  void charge(String c) {
    if (meter != null) {
      meter.contracts(c);
    }
  }

  boolean terminated(String k) {
    return registry.marker(state.documents.get(k), "terminated") != null;
  }

  Set<String> reachable() {
    return reachable(false, Set.of());
  }

  Set<String> reachable(boolean active, Set<String> terminating) {
    return reachable(state.root, active, terminating);
  }

  Set<String> reachable(String root, boolean active, Set<String> terminating) {
    var index = topology();
    if (!active && root.equals(state.root)) {
      return index.live;
    }
    return reachable(
        root, index.outgoing, k -> active && (terminating.contains(k) || terminated(k)));
  }

  ContractTopology topology() {
    return ContractTopology.of(state);
  }

  static Map<String, List<String>> outgoing(Snapshot snapshot) {
    var outgoing = new HashMap<String, List<String>>();
    for (Edge edge : snapshot.edges.values()) {
      outgoing.computeIfAbsent(edge.source(), k -> new ArrayList<>()).add(edge.target());
    }
    return outgoing;
  }

  static Set<String> reachable(
      String root,
      Map<String, List<String>> outgoing,
      java.util.function.Predicate<String> excluded) {
    var seen = new TreeSet<String>(ContractCanonical.CODE_POINTS);
    var pending = new ArrayDeque<String>();
    pending.push(root);
    while (!pending.isEmpty()) {
      String k = pending.pop();
      if (seen.contains(k) || excluded.test(k)) {
        continue;
      }
      seen.add(k);
      for (String target : outgoing.getOrDefault(k, List.of())) {
        pending.push(target);
      }
    }
    return seen;
  }

  void refreshAliases() {
    aliases = new Aliases(state);
  }

  /**
   * A frozen attachment lookup, opened only when an authored reference needs an owner. Ordinary
   * value changes replace this cheap snapshot without rebuilding every member's alias set.
   */
  static final class Aliases {
    private final TopologyMap<String, Document> documents;
    private final Set<String> live;
    private Map<String, Set<String>> values;

    Aliases(Snapshot state) {
      documents = new TopologyMap<>(true, ContractCanonical.CODE_POINTS, Document::sameMetadata);
      documents.copyFrom(state.documents);
      live = ContractTopology.of(state).live;
    }

    private Aliases(Aliases source) {
      documents = source.documents;
      live = source.live;
      if (source.values != null) {
        values = new HashMap<>();
        source.values.forEach((id, members) -> values.put(id, new HashSet<>(members)));
      }
    }

    Aliases copy() {
      return new Aliases(this);
    }

    Set<String> get(String id) {
      return values().getOrDefault(id, Set.of());
    }

    void add(String id, String member) {
      values().computeIfAbsent(id, unused -> new HashSet<>()).add(member);
    }

    private Map<String, Set<String>> values() {
      if (values == null) {
        values = new HashMap<>();
        documents.forEach(
            (key, document) -> {
              if (live.contains(key) && !document.blueId.isEmpty()) {
                values.computeIfAbsent(document.blueId, unused -> new HashSet<>()).add(key);
              }
            });
        ContractMetrics.add("graph.aliasIndex.memberVisits", documents.size());
      }
      return values;
    }
  }

  List<String> paths(Document document) {
    return declaredPaths(document.body, registry.contracts(document).get("embedded"));
  }

  List<String> paths(ExactNode body) {
    return declaredPaths(body);
  }

  List<String> declaredPaths(ExactNode body) {
    return declaredPaths(body, registry.contracts(body).get("embedded"));
  }

  private List<String> declaredPaths(ExactNode body, Map<String, Object> e) {
    if (e == null) {
      return List.of();
    }
    require(e.get("_role").equals("embedding"), "INVALID_EMBEDDING");
    var paths = new ArrayList<String>();
    boolean first = pathHeaders.add(identity(blue.exact(e.get("_recipe"))));
    for (Object raw : arr(e.getOrDefault("paths", List.of()))) {
      String p = str(raw);
      if (first) {
        charge("embeddedPathEntryRead");
      }
      var parts = segments(p);
      if (first) {
        for (String ignored : parts) {
          charge("embeddedPathSegmentValidated");
        }
      }
      require(
          !parts.isEmpty() && !parts.contains("") && !parts.getFirst().equals("contracts"),
          "INVALID_EMBEDDED_PATH");
      if (blue.select(body, p) != null) {
        paths.add(p);
      }
    }
    for (Object raw : arr(e.getOrDefault("collectionPaths", List.of()))) {
      String base = str(raw);
      if (first) {
        charge("embeddedPathEntryRead");
      }
      var parts = segments(base);
      if (first) {
        for (String ignored : parts) {
          charge("embeddedPathSegmentValidated");
        }
      }
      require(!parts.isEmpty() && !parts.getFirst().equals("contracts"), "INVALID_EMBEDDED_PATH");
      var r = blue.select(body, base);
      if (r == null) {
        continue;
      }
      var h = blue.head(r);
      require(
          Set.of("object", "none").contains(h.kind) && h.items == null && h.value == ABSENT,
          "EMBEDDED_COLLECTION_NOT_OBJECT");
      for (String name : h.fields.keySet()) {
        paths.add(base + pointer(List.of(name)));
      }
    }
    return orderPaths(paths);
  }

  static List<String> orderPaths(List<String> paths) {
    require(new HashSet<>(paths).size() == paths.size(), "DUPLICATE_EMBEDDED_PATH");
    record Path(String text, List<String> parts) {}
    var ordered = new ArrayList<Path>(paths.size());
    for (String path : paths) {
      ordered.add(new Path(path, segments(path)));
    }
    ordered.sort((a, b) -> ContractCanonical.compareSegments(a.parts, b.parts));
    // In segment-lexicographic order, an ancestor immediately precedes its first
    // descendant. Adjacent checks are sufficient; unrelated sibling paths never
    // need pairwise comparison or repeated JSON-pointer parsing.
    for (int i = 1; i < ordered.size(); i++) {
      var previous = ordered.get(i - 1).parts;
      var next = ordered.get(i).parts;
      require(
          next.size() < previous.size() || !next.subList(0, previous.size()).equals(previous),
          "OVERLAPPING_EMBEDDED_PATH");
    }
    return new ArrayList<>(ordered.stream().map(Path::text).toList());
  }

  String freshKey(String source, String path, String id) {
    if (meter != null && meter.schedule == GasSchedule.STANDARD) {
      // A birth belongs to the creating occurrence, not the root which happened to drive it.
      // Retired paths retain their serial, so remove/reinsert cannot reuse an old lifetime.
      long generation =
          Math.addExact(state.documents.get(source).edgeSerials.getOrDefault(path, 0L), 1);
      return digest(
          "contracts.vertex/semantic-1",
          m("source", source, "path", path, "generation", generation, "value", id));
    }
    return digest(
        "contracts.vertex/1",
        m(
            "input",
            cause == null ? state.identity() : cause,
            "source",
            source,
            "path",
            path,
            "ordinal",
            ++allocation,
            "value",
            id));
  }

  String importTarget(String source, String path, ExactNode n, String birth) {
    return append == null
        ? importCanonicalTarget(source, path, n, birth)
        : append.resolve(this, source, path, n, birth);
  }

  String importCanonicalTarget(String source, String path, ExactNode n, String birth) {
    String id = identity(n);
    var known = aliases.get(id);
    require(known.size() <= 1, "AMBIGUOUS_LIVE_REFERENCE", "blueId", id);

    if (!known.isEmpty()) {
      return known.iterator().next();
    }

    Snapshot imported = archive.get(id);
    if (imported != null) {
      var mapping = new TreeMap<String, String>(ContractCanonical.CODE_POINTS);
      imported.documents.forEach(
          (k, d) -> {
            var candidates =
                aliases.get(d.blueId).stream()
                    .filter(s -> state.documents.get(s).channelLives.equals(d.channelLives))
                    .toList();
            require(candidates.size() <= 1, "AMBIGUOUS_LIVE_REFERENCE", "blueId", d.blueId);
            // A prior imported branch may not yet be reachable while its parent edge is
            // being installed. Preserve its exact retained owner even before alias refresh.
            mapping.put(
                k,
                !candidates.isEmpty()
                    ? candidates.getFirst()
                    : !state.documents.containsKey(k)
                            || state.documents.get(k).metadata().equals(d.metadata())
                        ? k
                        : freshKey(source, path + "/" + k, d.blueId));
          });
      imported.documents.forEach(
          (k, d) -> {
            String target = mapping.get(k);
            if (!state.documents.containsKey(target)) {
              state.documents.put(target, d.withKey(target));
              added.add(target);
            }
          });
      for (Edge e : imported.edges.values()) {
        Edge mapped =
            new Edge(mapping.get(e.source()), e.path(), mapping.get(e.target()), e.generation());
        require(
            !state.edges.containsKey(mapped.key()) || state.edges.get(mapped.key()).equals(mapped),
            "IMPORTED_GRAPH_CONFLICT");
        state.edges.put(mapped.key(), mapped);
      }
      if (registry.observeAttachment != null) {
        var predecessors = new TreeMap<String, String>(ContractCanonical.CODE_POINTS);
        imported.documents.forEach((k, d) -> predecessors.put(mapping.get(k), d.blueId));
        registry.observeAttachment.accept(predecessors);
      }
      refreshAliases();
      return mapping.get(imported.root);
    }
    Value body = blue.open(n);
    var h = blue.head(body);
    require(
        Set.of("object", "none").contains(h.kind) && h.items == null && h.value == ABSENT,
        "EMBEDDED_DOCUMENT_NOT_OBJECT");
    for (String reserved : ContractRegistry.MARKERS) {
      if (registry.marker(body, reserved) != null) {
        throw new ContractModel.Error(
            "NEEDS_RESOURCES", "required", List.of(id), "requiredHistory", List.of(id));
      }
    }
    String key = freshKey(source, path, id);
    state.documents.put(key, new Document(key, body, id, birth));
    aliases.add(id, key);
    added.add(key);
    return key;
  }

  /**
   * For an absent dictionary key inserted one level under a declared collection, previously
   * validated sibling selections cannot change. Refuse intersecting declarations: an explicit
   * descendant that used to be absent may now become a second managed occurrence.
   */
  boolean collectionAppend(Document previous, Document next, String path) {
    if (meter == null
        || meter.schedule != GasSchedule.STANDARD
        || Boolean.getBoolean("blue.physical.fullCollectionReconciliation")) {
      return false;
    }
    var parts = segments(path);
    if (parts.size() < 2 || parts.stream().anyMatch(LanguageCore.RESERVED::contains)) {
      return false;
    }
    var old = registry.contracts(previous).get("embedded");
    var current = registry.contracts(next).get("embedded");
    if (old == null
        || current == null
        || !identity(blue.exact(old.get("_recipe")))
            .equals(identity(blue.exact(current.get("_recipe"))))) {
      return false;
    }
    String base = pointer(parts.subList(0, parts.size() - 1));
    int matches = 0;
    for (String field : List.of("collectionPaths", "paths")) {
      for (Object raw : arr(current.getOrDefault(field, List.of()))) {
        String declaration = str(raw);
        if (field.equals("collectionPaths") && base.equals(declaration)) {
          matches++;
        } else if (prefix(declaration, path) || prefix(path, declaration)) {
          return false;
        }
      }
    }
    if (matches != 1) {
      return false;
    }
    var parent = blue.select(previous.body, base);
    if (parent == null) {
      return false;
    }
    var head = blue.head(parent);
    return Set.of("object", "none").contains(head.kind)
        && head.items == null
        && head.value == ABSENT;
  }

  void reconcile(String birth, String owner, List<String> replaced) {
    reconcile(birth, owner, replaced, false);
  }

  void reconcile(String birth, String owner, List<String> replaced, boolean append) {
    var original = new HashSet<>(state.documents.keySet());
    var visited = new HashSet<String>();
    var todo = new ArrayDeque<String>();
    todo.add(owner == null ? state.root : owner);
    while (!todo.isEmpty()) {
      String key = todo.remove();
      if (!visited.add(key)) {
        continue;
      }
      Document d = state.documents.get(key);
      // Preserve canonical traversal order without scanning the path list for every edge.
      boolean point = append && key.equals(owner);
      var required = point ? new LinkedHashSet<>(replaced) : new LinkedHashSet<>(paths(d));
      if (point) {
        ContractMetrics.count("contracts.collectionAppend.pointReconciliations");
      }
      if (!point) {
        for (Edge e : state.outgoingEdges(key)) {
          if (!required.contains(e.path())) {
            state.edges.remove(e.key());
            record("REMOVE", e, null);
          }
        }
      }
      for (String path : required) {

        var r = blue.select(d.body, path);
        require(r != null, "MISSING_EMBEDDED_PATH");
        ExactNode n = blue.exact(r);
        String id = identity(n);
        Edge e = state.edges.get(new EdgeKey(key, path));
        String target =
            e != null && id.equals(state.documents.get(e.target()).blueId)
                ? e.target()
                : importTarget(key, path, n, birth);
        if (e == null
            || !target.equals(e.target())
            || replaced.stream().anyMatch(p -> prefix(p, path))) {
          if (e != null) {
            record("REMOVE", e, null);
          }
          long generation = integer(d.edgeSerials.getOrDefault(path, 0L) + 1);
          d = d.withEdgeSerial(path, generation);
          state.documents.put(key, d);
          Edge next = new Edge(key, path, target, generation);
          state.edges.put(next.key(), next);
          record("ADD", null, next);
        }
        if (owner == null || !original.contains(target)) {
          todo.add(target);
        }
      }
    }
    refreshAliases();
  }

  void record(String kind, Edge before, Edge after) {
    topologyChanged = true;
    changes.add(
        m(
            "ordinal",
            changes.size(),
            "kind",
            kind,
            "before",
            before == null ? null : before.wire(),
            "after",
            after == null ? null : after.wire()));
    Edge e = after == null ? before : after;
    dirty.add(e.source());
    dirty.add(e.target());
    if (meter != null) {
      meter.owner = e.source();
    }
    charge("processEmbeddedEdgeExamined");
    if (kind.equals("ADD") && added.contains(e.target())) {
      charge("closureExpanded");
    }
  }

  List<List<String>> observePartition() {
    var relevant = reachable();
    var p = topology().components(relevant);
    var previous = logical == null ? partition : logical.partition();
    if (!Objects.equals(previous, p)) {
      // Partition membership is a set lookup; canonical iteration and charging order stay in p.
      // A single added child must not compare every current component to every previous one.
      Set<List<String>> established = previous == null ? Set.of() : new HashSet<>(previous);
      for (var group : p) {
        if (established.contains(group)) {
          continue;
        }
        for (String k : group) {
          if (meter != null) {
            meter.owner = k;
          }
          charge("componentMemberPartitioned");
        }
        // groupEdges preserves the canonical source/path order without scanning unrelated edges.
        for (Edge e : topology().groupEdges(group)) {
          if (meter != null) {
            meter.owner = e.source();
          }
          charge("componentEdgePartitioned");
        }
        if (previous != null && !previous.isEmpty()) {
          charge("componentPartitionChanged");
        }
      }
      partition = p;
      if (logical != null) {
        logical.partition(p);
      }
    }
    return p;
  }

  void finalizeGraph() {
    finalizeGraph(true, state.documents.keySet());
  }

  void finalizeGraph(boolean validate, Collection<String> selected) {
    observePartition();
    for (var group : topology().components(selected)) {
      var index = topology();
      if (!Boolean.getBoolean("blue.canonical.finalizationReuseDisabled")
          && group.stream().allMatch(k -> referencesUnchanged(k, index))) {
        ContractMetrics.add("graph.finalize.reusedMembers", group.size());
        continue;
      }
      var edges = index.groupEdges(group);
      var groupSet = new HashSet<>(group);
      var positions = new HashMap<String, Integer>();
      for (int i = 0; i < group.size(); i++) {
        positions.put(group.get(i), i);
      }
      ContractMetrics.add("graph.finalize.edgeVisits", edges.size());
      var bodies = new TreeMap<String, ExactNode>(ContractCanonical.CODE_POINTS);
      for (String k : group) {
        bodies.put(k, state.documents.get(k).body);
      }
      for (String k : group) {
        var replacements = new TreeMap<String, ExactNode>(ContractCanonical.CODE_POINTS);
        for (Edge e : index.outgoingEdges(k)) {
          if (!groupSet.contains(e.target())
              && !identity(blue.exact(blue.select(bodies.get(k), e.path())))
                  .equals(state.documents.get(e.target()).blueId)) {
            if (meter != null) {
              meter.owner = k;
            }
            charge("containingReferenceUpdated");
            replacements.put(e.path(), new Ref(state.documents.get(e.target()).blueId));
          }
        }
        if (!replacements.isEmpty()) {
          ExactNode n = blue.substitute(bodies.get(k), replacements);
          if (logical != null) {
            logical.establish(n, bodies.get(k));
          }
          bodies.put(k, n);
        }
      }
      boolean changed =
          group.stream()
              .anyMatch(
                  k ->
                      dirty.contains(k)
                          || !identity(bodies.get(k)).equals(state.documents.get(k).blueId));
      if (!changed) {
        rememberReferences(group, index);
        continue;
      }
      boolean cyclic =
          group.size() > 1 || edges.stream().anyMatch(e -> e.source().equals(e.target()));
      if (cyclic) {
        if (meter != null) {
          meter.owner = "";
        }
        charge("tentativeComponentFinalization");
      }
      for (String k : group) {
        if (meter != null) {
          meter.owner = k;
        }
        ExactNode n = bodies.get(k);
        if (cyclic) {
          var replacement = new TreeMap<String, ExactNode>(ContractCanonical.CODE_POINTS);
          for (Edge e : index.outgoingEdges(k)) {
            if (groupSet.contains(e.target())) {
              replacement.put(e.path(), new Local(positions.get(e.target())));
            }
          }
          n = blue.substitute(n, replacement);
        }
        if (logical != null) {
          logical.establish(
              n,
              cyclic ? null : finalized.get(k),
              cyclic ? i -> "this#" + i : null,
              cyclic
                  ? group.stream()
                      .map(member -> List.of(member, state.documents.get(member).blueId))
                      .toList()
                  : null);
        }
      }
      if (cyclic) {
        for (String k : group) {
          if (meter != null) {
            meter.owner = k;
          }
          charge("cyclicMemberFinalized");
        }
        for (var e : blue.finalizeComponent(group, bodies, edges).entrySet()) {
          Document d = state.documents.get(e.getKey());
          state.documents.put(e.getKey(), d.finalized(e.getValue()));
        }
      } else {
        String k = group.getFirst();
        ExactNode body = bodies.get(k);
        blue.remember(body);
        state.documents.put(k, state.documents.get(k).finalized(body));
      }
      for (String k : group) {
        if (validate) {
          if (meter != null) {
            meter.owner = k;
          }
          if (logical != null) {
            logical.validate(state.documents.get(k).body, finalized.get(k));
          } else {
            blue.certify(state.documents.get(k).body);
          }
        }
        finalized.put(k, state.documents.get(k).body);
      }
      rememberReferences(group, index);
    }
    dirty.removeAll(selected);
    refreshAliases();
  }

  private boolean referencesUnchanged(String key, ContractTopology index) {
    Document document = state.documents.get(key);
    return !dirty.contains(key)
        && document.finalizedLinks != null
        && document.finalizedLinks.matches(document, index.outgoingEdges(key), state);
  }

  private void rememberReferences(Collection<String> members, ContractTopology index) {
    for (String key : members) {
      Document document = state.documents.get(key);
      List<Edge> edges = index.outgoingEdges(key);
      document.finalizedLinks =
          new FinalizedLinks(
              document.body,
              document.blueId,
              edges,
              edges.stream().map(edge -> state.documents.get(edge.target()).blueId).toList());
    }
  }

  void finalizeLocal(String k) {
    Document d = state.documents.get(k);
    blue.remember(d.body);
    if (logical != null) {
      logical.validate(d.body, finalized.get(k));
    } else {
      blue.certify(d.body);
    }
    finalized.put(k, d.body);
  }

  void verifyEdges() {
    for (var row : state.documents.entrySet()) {
      String k = row.getKey();
      Document d = row.getValue();
      var dependencies =
          topology().outgoingEdges(k).stream()
              .map(
                  e ->
                      List.of(
                          e.source(),
                          e.path(),
                          e.target(),
                          e.generation(),
                          state.documents.containsKey(e.target())
                              ? state.documents.get(e.target()).blueId
                              : "<missing>"))
              .toList();
      var fact = registry.facts.check("edges", k, d, dependencies);
      if (fact.known()) {
        continue;
      }
      ContractMetrics.count("contracts.edges.bodyChecks");
      require(
          k.equals(d.key) && (identity(d.body).equals(d.blueId) || d.blueId.contains("#")),
          "INVALID_DOCUMENT_RECORD");
      if (d.blueId.contains("#")) {
        require(
            identity(blue.open(new Ref(d.blueId))).equals(identity(blue.open(d.body))),
            "INVALID_CYCLIC_BODY");
      }
      blue.certify(d.body);
      var paths = ContractCanonical.keys(paths(d));
      var actual = new TreeSet<String>(ContractCanonical.CODE_POINTS);
      for (Edge e : topology().outgoingEdges(k)) {
        ContractMetrics.count("graph.verify.edgeVisits");
        actual.add(e.path());
      }
      require(paths.equals(actual), "INVALID_GRAPH_BINDINGS");
      for (String p : paths) {
        Edge e = state.edges.get(new EdgeKey(k, p));
        require(state.documents.containsKey(e.target()), "MISSING_GRAPH_VERTEX");
        require(
            identity(blue.exact(blue.select(d.body, p)))
                .equals(state.documents.get(e.target()).blueId),
            "INVALID_GRAPH_BINDING",
            "path",
            p);
        require(e.generation() == d.edgeSerials.get(p), "INVALID_EDGE_GENERATION");
      }
      registry.facts.established(fact);
    }
  }

  boolean routeLive(List<Edge> route) {
    var active = reachable(true, Set.of());
    return route.stream()
        .allMatch(
            e ->
                e.equals(state.edges.get(e.key()))
                    && active.contains(e.source())
                    && active.contains(e.target()));
  }

  static Snapshot prepare(
      ContractRegistry reg,
      Map<String, Object> members,
      String root,
      Map<EdgeKey, String> bindings,
      Archive archive) {
    ContractBridge blue = reg.blue;
    token(root);
    require(members.containsKey(root), "MISSING_ROOT");
    var explicit = new TreeMap<EdgeKey, String>(bindings);
    Snapshot s = new Snapshot(root);
    s.release = reg.specification;
    s.gasSchedule = reg.bindings.gasSchedule();
    s.runtimes = reg.runtimeBindings();
    for (var entry : ContractCanonical.map(members).entrySet()) {
      String k = token(entry.getKey());
      ExactNode n =
          blue.context.prepare(blue.input(unpack(k, entry.getValue(), List.of(), explicit), true));
      var h = blue.head(n);
      require(
          Set.of("object", "none").contains(h.kind) && h.items == null && h.value == ABSENT,
          "PROCESSING_DOCUMENT_NOT_OBJECT");
      for (String reserved : ContractRegistry.MARKERS) {
        require(reg.marker(n, reserved) == null, "AUTHORED_PROCESSOR_STATE", "key", reserved);
      }
      s.documents.put(k, new Document(k, n));
    }
    for (var e : explicit.entrySet()) {
      require(
          s.documents.containsKey(e.getKey().source()) && s.documents.containsKey(e.getValue()),
          "MISSING_GRAPH_VERTEX");
      String owner = e.getKey().source();
      s.documents.put(owner, s.documents.get(owner).withEdgeSerial(e.getKey().path(), 1L));
      s.edges.put(e.getKey(), new Edge(e.getKey().source(), e.getKey().path(), e.getValue(), 1));
    }
    var g = new ContractGraph(s, reg, archive);
    g.finalizeGraph(false, s.documents.keySet());
    g.reconcile(null, null, List.of());
    g.finalizeGraph();
    g.verifyEdges();
    require(g.reachable().containsAll(members.keySet()), "DISCONNECTED_AUTHORING_BUNDLE");
    blue.retain(s.documents.values().stream().map(d -> d.body).toList());
    return s;
  }

  static Object unpack(String key, Object raw, List<String> path, Map<EdgeKey, String> explicit) {
    if (raw instanceof Link l) {
      explicit.put(new EdgeKey(key, pointer(path)), l.document);
      return Map.of();
    }
    if (raw instanceof Map<?, ?>) {
      var out = new TreeMap<String, Object>(ContractCanonical.CODE_POINTS);
      obj(raw)
          .forEach(
              (k, v) -> {
                var p = new ArrayList<>(path);
                p.add(k);
                out.put(k, unpack(key, v, p, explicit));
              });
      return out;
    }
    if (raw instanceof List<?> a) {
      var out = new ArrayList<Object>();
      for (int i = 0; i < a.size(); i++) {
        var p = new ArrayList<>(path);
        p.add("" + i);
        out.add(unpack(key, a.get(i), p, explicit));
      }
      return out;
    }
    return BexValues.copy(raw);
  }
}
