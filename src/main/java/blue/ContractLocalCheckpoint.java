package blue;

import static blue.ContractLanguage.identity;
import static blue.ContractModel.arr;
import static blue.ContractModel.digest;
import static blue.ContractModel.m;
import static blue.ContractModel.obj;
import static blue.ContractModel.require;
import static blue.ContractModel.str;

import blue.ContractModel.Document;
import blue.ContractModel.Edge;
import blue.ContractModel.Snapshot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Writes and restores the native owner's protected checkpoint, including its embedding commitments.
 */
final class ContractLocalCheckpoint {
  private ContractLocalCheckpoint() {}

  private static final String FORMAT = "contracts.local-state/1";

  private static final List<String> SNAPSHOT_FIELDS =
      List.of("root", "release", "gasSchedule", "generation", "revision", "runtimes");

  /** Only exact inputs to one protected owner's attachment commitment are retained. */
  record BindingInputs(String declaration, List<String> selections) {}

  record BindingMemo(Map<String, Long> generations, BindingInputs inputs, String commitment) {}

  private static BindingInputs bindingInputs(ContractProcessor engine, Document document) {
    var declaration = engine.registry.contracts(document).get("embedded");
    if (declaration == null) return new BindingInputs(null, List.of());
    var selected = new ArrayList<String>();
    for (String field : List.of("paths", "collectionPaths")) {
      for (Object raw : arr(declaration.getOrDefault(field, List.of()))) {
        var value = engine.blue.select(document.body, str(raw));
        selected.add(value == null ? "" : identity(engine.blue.exact(value)));
      }
    }
    return new BindingInputs(
        identity(engine.blue.exact(declaration.get("_recipe"))), List.copyOf(selected));
  }

  private static String bindingCommitment(
      ContractProcessor engine, Snapshot state, Document document) {
    if (!state.ownerLocal)
      return digest("contracts.local-bindings/1", bindings(engine, state, document));
    BindingInputs inputs = bindingInputs(engine, document);
    BindingMemo previous = document.bindingMemo;
    if (previous != null
        && previous.generations() == document.edgeSerials
        && previous.inputs().equals(inputs)) {
      ContractMetrics.count("contracts.local.bindingCommitmentReused");
      return previous.commitment();
    }
    String commitment = digest("contracts.local-bindings/1", bindings(engine, state, document));
    document.bindingMemo = new BindingMemo(document.edgeSerials, inputs, commitment);
    return commitment;
  }

  static Map<String, Object> checkpoint(ContractProcessor engine, Snapshot state) {
    if (!state.ownerLocal) {
      var topology = ContractTopology.of(state);
      if (topology.components(state.documents.keySet()).stream().anyMatch(g -> g.size() > 1)
          || state.documents.keySet().stream().anyMatch(topology::selfLinked)) return Map.of();
      // A false predicate on an otherwise idle source still consumes semantic gas. Such graphs
      // require the ordinary selection prefix until a priced selection summary is available.
      for (var document : state.documents.values())
        for (var declaration : engine.registry.contracts(document).values())
          if ("external".equals(declaration.get("_role")) && declaration.containsKey("event"))
            return Map.of();
    }
    Document document = state.documents.get(state.root);
    if (engine.registry.marker(document, "initialized") == null
        || engine.registry.marker(document, "terminated") != null) return Map.of();
    var record =
        m(
            "format",
            FORMAT,
            "root",
            state.root,
            "release",
            state.release,
            "gasSchedule",
            state.gasSchedule.id(),
            "generation",
            state.generation,
            "revision",
            state.revision,
            "runtimes",
            state.runtimes,
            "member",
            document.metadata(),
            "concrete",
            engine.blue.context.concrete(document.body),
            "environment",
            ContractPreparations.environment(engine.registry),
            "preparation",
            ContractPreparations.exportRow(engine, document, true));
    // A local collection birth seals the exact body and generation map in member metadata.
    // Full recovery validates every declared path against the pinned graph with verifyEdges.
    if (state.verifyGraphBindings) record.put("verifyGraphBindings", true);
    else record.put("bindings", bindingCommitment(engine, state, document));
    record.put("identity", digest(FORMAT, record));
    return obj(ContractWire.immutable(record));
  }

  /** Exact references established by the native owner, not a caller-supplied attachment cut. */
  private static Map<String, Object> bindings(
      ContractProcessor engine, Snapshot state, Document document) {
    var result = new TreeMap<String, Object>(ContractCanonical.CODE_POINTS);
    if (!state.ownerLocal) {
      for (var edge : ContractTopology.of(state).outgoingEdges(document.key))
        result.put(
            edge.path(), List.of(edge.generation(), state.documents.get(edge.target()).blueId));
    } else {
      // A partial owner has no sibling graph. Its committed body still contains exact references.
      for (String path : new ContractGraph(state, engine.registry, engine.archive).paths(document))
        result.put(
            path,
            List.of(
                document.edgeSerials.get(path),
                identity(engine.blue.exact(engine.blue.select(document.body, path)))));
    }
    return result;
  }

  static Snapshot restore(ContractProcessor engine, Map<String, Object> checkpoint) {
    var record = new LinkedHashMap<>(checkpoint);
    var id = record.remove("identity");
    require(
        FORMAT.equals(record.get("format")) && digest(FORMAT, record).equals(id),
        "INVALID_LOCAL_CHECKPOINT");
    require(
        ContractPreparations.environment(engine.registry).equals(record.get("environment")),
        "INCOMPATIBLE_RUNTIME_BINDINGS");
    var wire = new LinkedHashMap<String, Object>();
    for (String key : SNAPSHOT_FIELDS) wire.put(key, record.get(key));
    wire.put("documents", List.of(record.get("member")));
    wire.put("edges", List.of());
    var state = ContractStorage.restore(engine, wire, true);
    state.ownerLocal = true;
    state.verifyGraphBindings = Boolean.TRUE.equals(record.get("verifyGraphBindings"));
    require(
        state.documents.size() == 1 && state.documents.containsKey(state.root),
        "INVALID_LOCAL_CHECKPOINT");
    if (engine.registry.preparations == null)
      engine.registry.preparations =
          new ContractPreparations(engine.registry, key -> Optional.empty());
    var document = state.documents.get(state.root);
    engine.blue.context.retainLocalClosure(document.blueId);
    require(record.get("concrete") instanceof Boolean, "INVALID_LOCAL_CHECKPOINT");
    engine.blue.context.restoreConcrete(document.blueId, (boolean) record.get("concrete"));
    engine.registry.preparations.installMember(
        engine.registry, document, obj(record.get("preparation")));
    // This is the protected local recovery boundary, not an untrusted proof import. Reuse
    // the committed binding digest only while every exact selector input is unchanged.
    if (record.get("bindings") instanceof String commitment)
      document.bindingMemo =
          new BindingMemo(document.edgeSerials, bindingInputs(engine, document), commitment);
    ContractMetrics.count("contracts.local.restoredOwners");
    return state;
  }

  static Map<String, Object> materialize(
      Map<String, Object> root,
      List<Map<String, Object>> members,
      List<Contracts.Embedding> edges) {
    var wire = new LinkedHashMap<String, Object>();
    for (String key : SNAPSHOT_FIELDS) wire.put(key, root.get(key));
    var documents = new TreeMap<String, Object>(ContractCanonical.CODE_POINTS);
    for (var member : members) {
      var copy = new LinkedHashMap<>(member);
      var id = copy.remove("identity");
      require(digest(FORMAT, copy).equals(id), "INVALID_LOCAL_CHECKPOINT");
      var metadata = obj(member.get("member"));
      var previous = documents.putIfAbsent(str(metadata.get("key")), metadata);
      require(previous == null || previous.equals(metadata), "INCOMPATIBLE_HISTORICAL_ATTACHMENTS");
    }
    wire.put("documents", new ArrayList<>(documents.values()));
    wire.put(
        "edges",
        edges.stream()
            .sorted(
                Comparator.comparing(Contracts.Embedding::source)
                    .thenComparing(Contracts.Embedding::path))
            .map(e -> new Edge(e.source(), e.path(), e.target(), e.generation()).wire())
            .toList());
    return m(
        "snapshot",
        wire,
        "identity",
        digest("contracts.snapshot/completion-1", wire),
        "content",
        Map.of());
  }

  /** Reuse each protected owner's admission when a structural edit needs the complete graph. */
  static Snapshot restoreGraph(
      ContractProcessor engine,
      Map<String, Object> root,
      List<Map<String, Object>> members,
      List<Contracts.Embedding> edges) {
    Map<String, Object> envelope = materialize(root, members, edges);
    String environment = ContractPreparations.environment(engine.registry);
    boolean foundRoot = false;
    for (var member : members) {
      require(FORMAT.equals(member.get("format")), "INVALID_LOCAL_CHECKPOINT");
      require(environment.equals(member.get("environment")), "INCOMPATIBLE_RUNTIME_BINDINGS");
      require(member.get("concrete") instanceof Boolean, "INVALID_LOCAL_CHECKPOINT");
      require(
          Objects.equals(member.get("root"), obj(member.get("member")).get("key")),
          "INVALID_LOCAL_CHECKPOINT");
      for (String field : List.of("release", "gasSchedule", "runtimes"))
        require(Objects.equals(root.get(field), member.get(field)), "INCOMPATIBLE_LOCAL_METADATA");
      if (Objects.equals(root.get("root"), member.get("root"))) {
        require(root.equals(member), "INCOMPATIBLE_LOCAL_METADATA");
        foundRoot = true;
      }
    }
    require(foundRoot, "MISSING_ROOT_DOCUMENT");
    Snapshot state = ContractStorage.restore(engine, obj(envelope.get("snapshot")), true);
    if (engine.registry.preparations == null)
      engine.registry.preparations =
          new ContractPreparations(engine.registry, key -> Optional.empty());
    for (var member : members) {
      Document document = state.documents.get(str(member.get("root")));
      engine.blue.context.retainLocalClosure(document.blueId);
      engine.blue.context.restoreConcrete(document.blueId, (boolean) member.get("concrete"));
      engine.registry.preparations.installMember(
          engine.registry, document, obj(member.get("preparation")));
      if (member.containsKey("bindings")) restoreBindings(engine, state, document, member);
    }
    // Legacy checkpoints re-open bodies. New checkpoints check the supplied cut against exact
    // references retained by native publication; neither invents a full preparation predecessor.
    engine.validate(state, true);
    engine.blue.refresh();
    engine.archive.add(state);
    return state;
  }

  private static void restoreBindings(
      ContractProcessor engine, Snapshot state, Document document, Map<String, Object> member) {
    var edges = ContractTopology.of(state).outgoingEdges(document.key);
    var expected = member.get("bindings");
    var actual = new TreeMap<String, Object>(ContractCanonical.CODE_POINTS);
    for (var edge : edges) {
      require(state.documents.containsKey(edge.target()), "MISSING_GRAPH_VERTEX");
      require(
          Objects.equals(document.edgeSerials.get(edge.path()), edge.generation()),
          "INVALID_EDGE_GENERATION");
      actual.put(
          edge.path(), List.of(edge.generation(), state.documents.get(edge.target()).blueId));
    }
    require(
        Objects.equals(expected, digest("contracts.local-bindings/1", actual)),
        "INVALID_GRAPH_BINDINGS");
    var dependencies =
        edges.stream()
            .map(
                edge ->
                    List.of(
                        edge.source(),
                        edge.path(),
                        edge.target(),
                        edge.generation(),
                        state.documents.get(edge.target()).blueId))
            .toList();
    engine.registry.facts.established(
        engine.registry.facts.check("edges", document.key, document, dependencies));
    document.finalizedLinks =
        new ContractGraph.FinalizedLinks(
            document.body,
            document.blueId,
            edges,
            edges.stream().map(edge -> state.documents.get(edge.target()).blueId).toList());
  }

  static Map<String, Object> materializeMetadata(
      Map<String, Object> checkpoint, Map<String, Object> metadata) {
    var copy = new LinkedHashMap<>(checkpoint);
    var id = copy.remove("identity");
    require(
        FORMAT.equals(copy.get("format")) && digest(FORMAT, copy).equals(id),
        "INVALID_LOCAL_CHECKPOINT");
    for (String field : SNAPSHOT_FIELDS)
      require(
          Objects.equals(checkpoint.get(field), metadata.get(field)),
          "INCOMPATIBLE_LOCAL_METADATA");
    var own =
        arr(metadata.get("documents")).stream()
            .map(ContractModel::obj)
            .filter(d -> checkpoint.get("root").equals(d.get("key")))
            .toList();
    require(
        own.size() == 1 && own.getFirst().equals(checkpoint.get("member")),
        "INCOMPATIBLE_LOCAL_METADATA");
    return m(
        "snapshot",
        metadata,
        "identity",
        digest("contracts.snapshot/completion-1", metadata),
        "content",
        Map.of());
  }
}
