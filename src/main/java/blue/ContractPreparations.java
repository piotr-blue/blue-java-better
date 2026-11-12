package blue;

import static blue.ContractLanguage.*;
import static blue.ContractModel.*;
import static blue.ExactNode.*;

import blue.LanguageModel.Recipe;
import java.util.*;
import java.util.function.Predicate;

/** Attempt-scoped reuse of protected native preparation, never portable admission authority. */
@SuppressWarnings("unchecked")
final class ContractPreparations {
  private Contracts.Execution.PreparationReader reader;
  private final String environment;
  private final Map<String, Prepared> documents = new HashMap<>();
  private final Set<String> initializedMarkers = new HashSet<>();
  private final Set<String> installedSnapshots = new HashSet<>();

  record Prepared(
      Map<String, Map<String, Object>> declarations,
      Map<String, Optional<ExactNode>> markers,
      List<Object> headers,
      List<String> languageFacts,
      Map<String, Object> markerFacts,
      Map<String, Object> headerRecords) {}

  ContractPreparations(ContractRegistry registry, Contracts.Execution.PreparationReader reader) {
    this.reader = reader;
    environment = environment(registry);
  }

  static String environment(ContractRegistry registry) {
    return digest(
        "contracts.preparation/1",
        List.of(
            Language.EDITION,
            registry.specification,
            registry.runtimeBindings(),
            registry.interpretedTypes));
  }

  void close() {
    reader = null;
    documents.clear();
    initializedMarkers.clear();
    installedSnapshots.clear();
  }

  boolean verifiedInitialization(ExactNode marker) {
    return initializedMarkers.contains(identity(marker));
  }

  Map<String, Object> read(String identity) {
    if (reader == null) return null;
    var value = reader.find(identity).orElse(null);
    if (value == null || !environment.equals(value.get("environment"))) return null;
    require(identity.equals(value.get("snapshot")), "WRONG_PREPARATION_SNAPSHOT");
    return value;
  }

  Prepared get(Document document) {
    return get(document.body);
  }

  Prepared get(ExactNode body) {
    return documents.get(identity(body));
  }

  void install(ContractRegistry registry, Snapshot state, Map<String, Object> record) {
    Map<String, Object> rows = obj(record.get("documents"));
    require(rows.keySet().equals(state.documents.keySet()), "INVALID_PREPARATION_MEMBERS");
    for (var document : state.documents.values())
      installMember(registry, document, obj(rows.get(document.key)));
    ContractTopology topology = ContractTopology.of(state);
    for (var document : state.documents.values()) {
      var edges = topology.outgoingEdges(document.key);
      document.finalizedLinks =
          new ContractGraph.FinalizedLinks(
              document.body,
              document.blueId,
              edges,
              edges.stream().map(edge -> state.documents.get(edge.target()).blueId).toList());
      var dependencies =
          topology.outgoingEdges(document.key).stream()
              .map(
                  e ->
                      List.of(
                          e.source(),
                          e.path(),
                          e.target(),
                          e.generation(),
                          state.documents.get(e.target()).blueId))
              .toList();
      registry.facts.established(
          registry.facts.check("edges", document.key, document, dependencies));
    }
    installedSnapshots.add(state.identity());
  }

  void installMember(ContractRegistry registry, Document document, Map<String, Object> row) {
    require(document.blueId.equals(row.get("blueId")), "INVALID_PREPARATION_MEMBER");
    if (row.containsKey("languageFacts"))
      registry.blue.context.restoreSemanticPreparation(
          document.body, arr(row.get("languageFacts")));
    Map<String, Object> headerRecords =
        row.get("headerRecords") == null
            ? null
            : obj(ContractWire.immutable(row.get("headerRecords")));
    if (headerRecords != null) registry.blue.context.supplyRetainedEvidence(headerRecords);
    var declarations = (Map<String, Map<String, Object>>) decode(row.get("declarations"), registry);
    Map<String, Optional<ExactNode>> markers = new HashMap<>();
    obj(row.get("markers"))
        .forEach(
            (key, id) ->
                markers.put(key, id == null ? Optional.empty() : Optional.of(new Ref(str(id)))));
    markers.get("initialized").ifPresent(marker -> initializedMarkers.add(identity(marker)));
    Map<String, Object> markerFacts =
        row.get("markerFacts") == null ? null : obj(ContractWire.immutable(row.get("markerFacts")));
    if (markerFacts != null)
      markerFacts.forEach(
          (name, facts) -> {
            var marker = markers.get(name);
            require(marker != null && marker.isPresent(), "INVALID_PREPARATION_MARKER");
            registry.blue.context.restoreSemanticPreparation(marker.orElseThrow(), arr(facts));
          });
    documents.put(
        document.blueId,
        new Prepared(
            declarations,
            Map.copyOf(markers),
            row.get("headers") == null ? null : arr(ContractWire.immutable(row.get("headers"))),
            row.containsKey("languageFacts")
                ? arr(row.get("languageFacts")).stream().map(ContractModel::str).toList()
                : null,
            markerFacts,
            headerRecords));
    registry.facts.established(registry.facts.check("markers", document.key, document, List.of()));
    if (Boolean.TRUE.equals(row.get("lifetimes"))) {
      registry.facts.established(registry.facts.check("lifetimes", document.key, document, false));
      registry.facts.established(registry.facts.check("lifetimes", document.key, document, true));
    }
  }

  static Map<String, Object> export(
      ContractProcessor processor, Snapshot state, Predicate<String> retained) {
    // Only the exact validated initialized checkpoint may issue this protected artifact.
    processor.validate(state, true);
    Map<String, Object> rows = new TreeMap<>();
    Set<String> active =
        new ContractGraph(state, processor.registry, processor.archive).reachable(true, Set.of());
    for (var document : state.documents.values()) {
      if (!retained.test(document.blueId)) return Map.of();
      rows.put(document.key, exportRow(processor, document, active.contains(document.key)));
    }
    return m(
        "environment",
        environment(processor.registry),
        "snapshot",
        state.identity(),
        "documents",
        rows);
  }

  /** A private publication patch; its base must remain atomically retained by the host. */
  static Map<String, Object> delta(
      ContractProcessor processor, Snapshot before, Snapshot after, Predicate<String> retained) {
    var preparation = processor.registry.preparations;
    if (preparation == null || !preparation.installedSnapshots.contains(before.identity()))
      return Map.of();
    require(before.root.equals(after.root), "PREPARATION_ROOT_CHANGED");
    processor.validate(after, true);
    Set<String> changed = new TreeSet<>(after.documents.changedKeys(before.documents));
    // Retired members remain in canonical history. An actual removal needs a full export.
    if (changed.stream().anyMatch(key -> !after.documents.containsKey(key))) return Map.of();
    List<String> added =
        changed.stream().filter(key -> !before.documents.containsKey(key)).toList();
    Map<String, Object> rows = new TreeMap<>();
    var graph = new ContractGraph(after, processor.registry, processor.archive);
    Set<String> active = graph.reachable(true, Set.of());
    if (before.documents.structure() != after.documents.structure()
        || !after.edges.changedKeys(before.edges).isEmpty()) {
      // Attaching or detaching can change declarations/lifetime preparation without changing
      // the member body. Include that exact reachability difference, not every sibling.
      Set<String> previous =
          new ContractGraph(before, processor.registry, processor.archive)
              .reachable(true, Set.of());
      for (String key : active) if (!previous.contains(key)) changed.add(key);
      for (String key : previous) if (!active.contains(key)) changed.add(key);
    }
    for (String key : changed) {
      var document = after.documents.get(key);
      var previous = before.documents.get(key);
      var oldTermination =
          previous == null ? null : processor.registry.marker(previous, "terminated");
      var newTermination = processor.registry.marker(document, "terminated");
      if (!Objects.equals(
          oldTermination == null ? null : identity(oldTermination),
          newTermination == null ? null : identity(newTermination))) return Map.of();
      if (!retained.test(document.blueId)) return Map.of();
      rows.put(key, exportRow(processor, document, active.contains(key)));
    }
    return m(
        "format",
        "contracts.preparation-delta/1",
        "base",
        before.identity(),
        "environment",
        environment(processor.registry),
        "snapshot",
        after.identity(),
        "added",
        added,
        "documents",
        rows);
  }

  static Map<String, Object> exportRow(
      ContractProcessor processor, Document document, boolean active) {
    ContractMetrics.count("contracts.preparation.exportedMembers");
    Map<String, Object> markers = new TreeMap<>();
    for (String key : ContractRegistry.MARKERS) {
      ExactNode marker = processor.registry.marker(document, key);
      markers.put(key, marker == null ? null : identity(marker));
    }
    var prior =
        processor.registry.preparations == null
            ? null
            : processor.registry.preparations.get(document);
    return m(
        "blueId",
        document.blueId,
        "headerRecords",
        prior != null && prior.headerRecords() != null
            ? prior.headerRecords()
            : headerRecords(processor.registry, document, active),
        "languageFacts",
        prior != null && prior.languageFacts() != null
            ? prior.languageFacts()
            : processor.blue.context.semanticPreparation(document.body),
        "markerFacts",
        prior != null && prior.markerFacts() != null
            ? prior.markerFacts()
            : markerFacts(processor.registry, document),
        "declarations",
        encode(active ? processor.registry.contracts(document) : null),
        "markers",
        markers,
        "headers",
        active ? headerWalk(processor.registry, document) : null,
        "lifetimes",
        active);
  }

  /** Lifecycle markers bind historical documents; unchanged markers need no history traversal. */
  private static Map<String, Object> markerFacts(ContractRegistry registry, Document document) {
    Map<String, Object> facts = new LinkedHashMap<>();
    for (String name : ContractRegistry.MARKERS) {
      ExactNode marker = registry.marker(document, name);
      if (marker != null) facts.put(name, registry.blue.context.semanticPreparation(marker));
    }
    return Collections.unmodifiableMap(facts);
  }

  /** Small exact responses needed by declaration inspection, without a member's business state. */
  private static Map<String, Object> headerRecords(
      ContractRegistry registry, Document document, boolean active) {
    Map<String, Object> records = new LinkedHashMap<>();
    for (String key : ContractRegistry.MARKERS) {
      ExactNode marker = registry.marker(document, key);
      if (marker != null && !headerRecord(registry, marker, records)) return null;
    }
    if (active)
      for (var declaration : registry.contracts(document).values()) {
        if (!ContractRegistry.EXTERNAL.contains(declaration.get("_role"))) continue;
        Recipe recipe = LanguageSemantics.recipe(declaration.get("_recipe"));
        for (ExactNode part : recipe.parts())
          if (!headerRecord(registry, part, records)) return null;
        if (!headerRecord(registry, registry.blue.exact(recipe), records)) return null;
      }
    return Collections.unmodifiableMap(records);
  }

  private static boolean headerRecord(
      ContractRegistry registry, ExactNode node, Map<String, Object> records) {
    String id = identity(node);
    // Owning-set proofs retain their ordinary member-opening boundary.
    if (id.contains("#")) return false;
    records.computeIfAbsent(
        id,
        key ->
            Map.of(
                "mode",
                "exact",
                "text",
                ContractLanguage.exactText(registry.blue.context.open(node))));
    return true;
  }

  private static List<Object> headerWalk(ContractRegistry registry, Document document) {
    var previous = registry.preparations == null ? null : registry.preparations.get(document);
    if (previous != null && previous.headers() != null) return previous.headers();
    List<Object> walk = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    ExactNode node = document.body;
    while (node != null && seen.add(identity(node))) {
      Value body = registry.blue.open(node);
      ExactNode contracts = (ExactNode) body.get("contracts");
      ExactNode type = (ExactNode) body.get("type");
      walk.add(
          Arrays.asList(
              identity(node),
              contracts == null ? null : identity(contracts),
              contracts == null ? 0 : registry.blue.open(contracts).fields().size(),
              type != null));
      node = type;
    }
    return List.copyOf(walk);
  }

  // Tag every container, so user-authored pattern keys cannot collide with internal encodings.
  private static Object encode(Object value) {
    if (value instanceof Recipe recipe) return List.of("recipe", recipe.key());
    if (value instanceof ExactNode node) return List.of("node", identity(node));
    if (value instanceof ContractNative.Installed nativeType)
      return List.of("extension", nativeType.binding.typeId());
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> fields = new TreeMap<>();
      map.forEach((key, item) -> fields.put((String) key, encode(item)));
      return List.of("map", fields);
    }
    if (value instanceof List<?> list)
      return List.of("list", list.stream().map(ContractPreparations::encode).toList());
    return Arrays.asList("scalar", value);
  }

  private static Object decode(Object value, ContractRegistry registry) {
    List<Object> tagged = arr(value);
    Object body = tagged.get(1);
    return switch (str(tagged.get(0))) {
      case "recipe" -> {
        List<Object> recipe = arr(ContractWire.json(str(body)));
        yield new Recipe(
            arr(recipe.getFirst()).stream().map(id -> (ExactNode) new Ref(str(id))).toList(),
            arr(recipe.get(1)).stream().map(ContractModel::str).toList());
      }
      case "node" -> new Ref(str(body));
      case "extension" -> Objects.requireNonNull(registry.extensions.get(str(body)));
      case "map" -> {
        Map<String, Object> map = new TreeMap<>(ContractCanonical.CODE_POINTS);
        obj(body).forEach((key, item) -> map.put(key, decode(item, registry)));
        yield Collections.unmodifiableMap(map);
      }
      case "list" -> arr(body).stream().map(item -> decode(item, registry)).toList();
      case "scalar" -> body;
      default -> throw new IllegalArgumentException("Invalid native preparation");
    };
  }
}
