package blue;

import static blue.ContractLanguage.ancestry;
import static blue.ContractLanguage.export;
import static blue.ContractLanguage.identity;
import static blue.ContractLanguage.present;
import static blue.ContractLanguage.recipe;
import static blue.ContractModel.arr;
import static blue.ContractModel.integer;
import static blue.ContractModel.m;
import static blue.ContractModel.obj;
import static blue.ContractModel.require;
import static blue.ContractModel.segments;
import static blue.ContractModel.str;
import static blue.ContractModel.token;

import blue.ContractModel.Document;
import blue.ExactNode.Ref;
import blue.ExactNode.Value;
import blue.LanguageModel.Head;
import blue.LanguageModel.Recipe;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

final class ContractRegistry {
  // Host-approved ordinary programs carrying an additional protocol specification.
  // Trust is granted to the exact definition/rules pair, never to arbitrary subtypes.
  final Map<String, String> interpretedTypes = new HashMap<>();

  Map<String, Object> idleUnit(Document document, Map<String, Object> view) {
    // The document already owns this immutable proof. A second bounded LRU would repeatedly
    // hash and weigh unchanged views when the live graph exceeds its capacity.
    return document.idleUnit(view, bindings.contractsEdition().finalGas());
  }

  // Parsed immutable metadata only: body verification is performed separately on every restore.
  final ContractCache<Map<String, Object>, Document> restoredDocuments =
      new ContractCache<>("contracts.restoredMetadata", 2L * 1024 * 1024, 512, 64 * 1024);

  java.util.function.Consumer<Map<String, String>> observeAttachment;
  static final Map<String, String> ROLES = new LinkedHashMap<>();
  static final Set<String> EXTERNAL = Set.of("external", "composite", "all", "extension_external"),
      INTERNAL = Set.of("triggered", "embedded_event", "update", "lifecycle", "extension_channel"),
      EXECUTABLE = Set.of("workflow", "operation", "extension_handler"),
      MARKERS = Set.of("initialized", "terminated", "checkpoint"),
      LITERAL = Set.of("do", "expr", "constants", "functions", "event", "changeset", "request");
  static final Map<String, Set<String>> ALLOWED = new HashMap<>(), BODY = new HashMap<>();

  static {
    String[][] roles = {
      {"Contract", "abstract"},
      {"Marker", "abstract"},
      {"Channel", "abstract"},
      {"Handler", "abstract"},
      {"Operation", "abstract"},
      {"External Channel", "external"},
      {"Composite External Channels", "composite"},
      {"All External Channels", "all"},
      {"Triggered Event Channel", "triggered"},
      {"Embedded Node Channel", "embedded_event"},
      {"Document Update Channel", "update"},
      {"Lifecycle Event Channel", "lifecycle"},
      {"Sequential Workflow", "workflow"},
      {"Sequential Workflow Operation", "operation"},
      {"Compute", "compute"},
      {"Compute Definition", "compute_definition"},
      {"Update Document", "patch_step"},
      {"Trigger Event", "emit_step"},
      {"Terminate Processing", "terminate_step"},
      {"Process Embedded", "embedding"},
      {"Processing Initialized Marker", "initialized"},
      {"Processing Terminated Marker", "terminated"},
      {"Channel Event Checkpoint", "checkpoint"},
      {"Operation Request", "request"},
      {"Document Processing Initiated", "initiated"},
      {"Document Processing Terminated", "ended"},
      {"Document Update", "updated"},
      {"Embedded Event Delivery", "embedded_delivery"},
      {"Json Patch Entry", "patch_record"},
      {"Contract Execution Result", "execution_result"},
      {"Type Generalization Rule", "generalization_rule"},
      {"Type Generalization Policy", "generalization"}
    };
    for (var r : roles) ROLES.put(r[0], r[1]);
    String[][] allowed = {
      {"external", "event"},
      {"composite", "channels"},
      {"all", ""},
      {"triggered", "event"},
      {"embedded_event", "sourcePath event"},
      {"update", "path event"},
      {"lifecycle", "event"},
      {"embedding", "paths collectionPaths"},
      {"workflow", "channel event steps"},
      {"operation", "channel request steps"},
      {"compute", "expr do constants functions entry definition"},
      {"compute_definition", "constants functions"},
      {"patch_step", "changeset"},
      {"emit_step", "event"},
      {"terminate_step", "cause reason"},
      {"generalization", "defaultMode rules"}
    };
    for (var r : allowed) ALLOWED.put(r[0], words(r[1]));
    for (String role :
        List.of(
            "workflow",
            "operation",
            "compute",
            "compute_definition",
            "patch_step",
            "emit_step",
            "terminate_step"))
      BODY.put(
          role,
          role.equals("workflow") || role.equals("operation")
              ? Set.of("steps")
              : ALLOWED.get(role));
  }

  static Set<String> words(String s) {
    return s.isEmpty() ? Set.of() : Set.of(s.split(" "));
  }

  final ContractBridge blue;
  final Map<String, String> types = new LinkedHashMap<>(), byId = new HashMap<>();
  final String specification;
  final ContractBindings bindings;
  final Map<String, ContractNative.Installed> extensions = new TreeMap<>();

  // A saved snapshot must have exactly the selected active binding sequence.
  boolean compatibleBindings(List<Object> saved, List<Object> other) {
    return ContractCanonical.text(saved).equals(ContractCanonical.text(other));
  }

  ContractRegistry(ContractBridge bridge, ContractBindings bindings, ContractPrograms programs) {
    blue = bridge;
    this.programs = Objects.requireNonNull(programs);
    facts = new ContractFacts(blue);
    this.bindings = bindings;
    BluePackage publication = ProtocolPublications.contracts();
    specification = publication.protocol().orElseThrow().rules().value();
    if (!bindings.contractsEdition().specificationBlueId().equals(specification)) {
      throw new IllegalStateException("Installed Contracts code and publication rules differ");
    }
    blue.context.install(publication);
    publication.exports().forEach((name, id) -> types.put(name, id.value()));
    if (!types.keySet().equals(ROLES.keySet())) {
      throw new IllegalStateException("The Contracts catalogue differs from the installed roles");
    }
    var aliases = new TreeMap<String, String>(ContractCanonical.CODE_POINTS);
    types.forEach(
        (name, id) -> {
          byId.put(id, ROLES.get(name));
          aliases.put(name, id);
          aliases.put("Contracts/" + name, id);
        });
    blue.context.installAliases(aliases);
    blue.refresh();
  }

  Map<String, Object> typed(String type, Object... fields) {
    var out = m("type", m("blueId", types.get(type)));
    out.putAll(m(fields));
    return out;
  }

  List<Object> runtimeBindings() {
    return extensions.values().stream().map(e -> (Object) e.binding.wire()).toList();
  }

  void install(ContractNative.Binding b, ContractNative.Implementation implementation) {
    if (bindings.contractsEdition().deterministic())
      b =
          new ContractNative.Binding(
              b.typeId(),
              b.semanticsId(),
              b.role(),
              b.headerFields(),
              b.bodyFields(),
              b.namespace(),
              ContractCanonical.map(b.tariff()),
              b.inheritsToSubtypes(),
              b.peerFields(),
              b.meterMode());
    blue.certify(new Ref(b.typeId()), "definition");
    blue.certify(new Ref(b.semanticsId()));
    require(!byId.containsKey(b.typeId()), "STANDARD_RUNTIME_REBIND");
    String parent =
        switch (b.role()) {
          case "external-channel", "processor-channel" -> "Channel";
          case "handler" -> "Handler";
          case "marker" -> "Marker";
          case "dispatch-policy" -> "Compute";
          default -> "Contract";
        };
    require(
        ancestry(b.typeId(), blue.context).contains(types.get(parent)),
        "INVALID_RUNTIME_CATEGORY",
        "category",
        parent);
    var previous = extensions.get(b.typeId());
    if (previous != null) {
      require(
          previous.binding.equals(b) && previous.implementation == implementation,
          "RUNTIME_BINDING_CONFLICT");
      return;
    }
    extensions.put(b.typeId(), new ContractNative.Installed(b, implementation));
    clearDeclarations();
    facts.clear();
  }

  Object binding(Object r) {
    Head h = blue.head(r);
    if (h.type == null) return null;
    for (String ancestor : ancestry(h.type, blue.context)) {
      var ext = extensions.get(ancestor);
      if (ext != null && (h.type.equals(ancestor) || ext.binding.inheritsToSubtypes())) return ext;
      if (byId.containsKey(ancestor)) return byId.get(ancestor);
    }
    return null;
  }

  String role(Object r) {
    Object binding = binding(r);
    return binding instanceof ContractNative.Installed e ? e.role : (String) binding;
  }

  boolean configured(Object value, String key) {
    Recipe r = value instanceof Recipe q ? q : recipe(value);
    for (ExactNode part : r.parts()) {
      var seen = new HashSet<String>();
      ExactNode n = part;
      while (n != null && !byId.containsKey(identity(n)) && seen.add(identity(n))) {
        Value b = blue.open(n);
        if (b.has(key)) return true;
        n = b.get("type") instanceof Ref ref ? ref : null;
      }
    }
    return false;
  }

  Map<String, Object> declaration(Object r) {
    return declaration(r, true);
  }

  Map<String, Object> declaration(Object r, boolean demand) {
    Head h = blue.head(r);
    String role = role(r);
    require(role != null, "UNSUPPORTED_CONTRACT_TYPE", "type", h.type);
    var out = m("type", m("blueId", h.type), "_role", role, "_recipe", r);
    var extension = binding(r) instanceof ContractNative.Installed e ? e : null;
    if (extension == null && !role.equals("abstract")) {
      // Only active executable declarations are checked; quoted data and unused exports are not.
      var nodes = new ArrayList<ExactNode>((r instanceof Recipe q ? q : recipe(r)).parts());
      if (h.type != null) ancestry(h.type, blue.context).forEach(id -> nodes.add(new Ref(id)));
      for (ExactNode node : nodes) {
        Object spec = blue.open(node).get("specification");
        if (spec instanceof ExactNode exact
            && !identity(exact).equals(specification)
            && !identity(exact).equals(interpretedTypes.get(identity(node))))
          throw new ContractBindings.Unsupported(
              identity(exact), List.of("Contracts/" + role, identity(node), "specification"));
      }
    }
    var bodies =
        extension == null
            ? BODY.getOrDefault(role, Set.of())
            : new HashSet<>(extension.binding.bodyFields());
    Set<String> allowed = ALLOWED.get(role);
    if (extension != null) {
      allowed = new HashSet<>(extension.binding.headerFields());
      allowed.addAll(bodies);
      out.put("_extension", extension);
    }
    if (allowed != null) {
      var invalid = ContractCanonical.keys(h.fields.keySet());
      invalid.removeAll(allowed);
      invalid.removeAll(Set.of("specification", "order"));
      require(
          invalid.isEmpty(), "UNKNOWN_RUNTIME_FIELD", "role", role, "fields", List.copyOf(invalid));
    }
    for (String k : h.fields.keySet()) {
      if (k.equals("specification")
          || k.equals("steps") && Set.of("workflow", "operation").contains(role)
          || !demand && bodies.contains(k)) continue;
      Recipe ch = ContractLanguage.child(h, k);
      if (k.equals("definition")) {
        if (configured(r, k)) out.put(k, ch);
      } else if (LITERAL.contains(k) || bodies.contains(k)) {
        if (configured(r, k)) out.put(k, blue.bexData(ch));
      } else if (present(ch, blue.context)) out.put(k, blue.project(ch));
    }
    Value n = blue.open(blue.exact(r));
    for (String label : List.of("name", "description"))
      if (n.has(label)) out.put(label, n.get(label));
    if (out.containsKey("order")) integer(out.get("order"), -(1L << 31), (1L << 31) - 1);
    if (role.equals("embedding"))
      for (String k : List.of("paths", "collectionPaths")) {
        var paths = arr(out.getOrDefault(k, List.of()));
        require(paths.stream().allMatch(x -> x instanceof String), "INVALID_EMBEDDED_PATHS");
        require(new HashSet<>(paths).size() == paths.size(), "DUPLICATE_EMBEDDED_PATH");
        for (Object p : paths) {
          var s = segments((String) p);
          require(
              !s.isEmpty() && !s.contains("") && !s.getFirst().equals("contracts"),
              "INVALID_EMBEDDED_PATH");
        }
      }
    if (role.equals("embedded_event") || role.equals("update")) {
      String f = role.equals("embedded_event") ? "sourcePath" : "path";
      if (out.containsKey(f)) segments(str(out.get(f)));
    }
    if (role.equals("terminate_step") && demand) {
      require(out.get("cause") instanceof String, "INVALID_TERMINATION_CAUSE");
      require(
          !out.containsKey("reason") || out.get("reason") instanceof String,
          "INVALID_TERMINATION_REASON");
    }
    if (EXECUTABLE.contains(role)) {
      token(out.get("channel"));
      if (!role.equals("extension_handler")) {
        require(configured(r, "steps"), "MISSING_STEPS");
        if (demand) out.put("steps", steps(out).stream().map(this::declaration).toList());
      }
    }
    return out;
  }

  List<Recipe> steps(Map<String, Object> handler) {
    Head h = blue.head(handler.get("_recipe"));
    Head sh = blue.head(ContractLanguage.child(h, "steps"));
    require(sh.items != null, "INVALID_STEPS");
    var out = new ArrayList<Recipe>();
    for (int i = 0; i < sh.items.size(); i++) out.add(ContractLanguage.child(sh, i));
    return out;
  }

  private final ContractPrograms programs;

  Object definitionValue(Recipe field) {
    return programs.definitionValue(identity(blue.exact(field)), () -> blue.bexData(field));
  }

  BexPlanCompiler.Program compiled(
      BexEngine engine,
      Map<String, Object> step,
      Map<String, Object> program,
      ContractRun.Definition definition) {
    // Definition selection, validation and its logical reads have already happened.
    // Only unmetered static compilation is reused. Exact identities bind every dependency.
    return programs.compile(
        identity(blue.exact(step.get("_recipe"))),
        definition == null ? "" : definition.identity(),
        () ->
            engine.compile(
                program,
                definition == null ? null : definition.program(),
                (String) step.get("entry")));
  }

  final ContractFacts facts;
  ContractPreparations preparations;
  private ContractLanguage.EvidenceScope declarationScope;
  private final ContractCache<String, Map<String, Map<String, Object>>> declarations =
      new ContractCache<>(
          "contracts.declarations",
          Long.getLong("blue.physical.contractFactsBytes", 8L * 1024 * 1024),
          4096,
          256 * 1024);

  private final ContractCache<String, Map<String, Object>> declarationHeaders =
      new ContractCache<>("contracts.headers", 8L * 1024 * 1024, 4096, 256 * 1024);

  void clearDeclarations() {
    declarationOwner = new Object();
    declarations.clear();
    declarationHeaders.clear();
  }

  private Map<String, Object> header(Recipe recipe) {
    boolean reuse =
        blue.context.nativeReuse() && !Boolean.getBoolean("blue.physical.nativeDisabled");
    String key = recipe.key();
    Map<String, Object> cached = reuse ? declarationHeaders.get(key) : null;
    if (cached != null) return cached;
    Map<String, Object> header = declaration(recipe, false);
    if (reuse) {
      header = obj(ContractWire.immutable(header));
      declarationHeaders.put(key, header, ContractCache.weight(List.of(key, header)));
    }
    return header;
  }

  record Declarations(
      Object registry, Object evidence, String recipe, Map<String, Map<String, Object>> values) {}

  private Object declarationOwner = new Object();

  void inherit(ContractRegistry template) {
    // Installed bindings and the verified evidence scope are copied by the processor constructor.
    // The token carries no mutable registry or provider; subsequent configuration changes revoke
    // it.
    declarationOwner = template.declarationOwner;
    declarationScope = blue.context.evidenceScope();
    facts.inherit(template.facts);
    preparations = template.preparations;
  }

  /** A live document keeps its own immutable plan, independent of catalogue cache pressure. */
  Map<String, Map<String, Object>> contracts(Document document) {
    var prepared = preparations == null ? null : preparations.get(document);
    if (prepared != null && prepared.declarations() != null) {
      // Earlier preparation formats depend on inline records inside the member response.
      if (prepared.headerRecords() == null) blue.context.openRetainedMember(document.body);
      return prepared.declarations();
    }
    boolean reusable =
        blue.context.nativeReuse() && !Boolean.getBoolean("blue.physical.nativeDisabled");
    if (!reusable) return contracts(document.body);
    var scope = blue.context.evidenceScope();
    Recipe recipe = blue.head(document.body).contracts;
    String key = recipe == null ? "" : recipe.key();
    var previous = document.declarations;
    if (previous != null
        && previous.registry == declarationOwner
        && previous.evidence == scope
        && previous.recipe.equals(key)) {
      ContractMetrics.count("contracts.documentDeclarations.reused");
      return previous.values;
    }
    var values = contracts(document.body);
    if (scope == blue.context.evidenceScope())
      document.declarations = new Declarations(declarationOwner, scope, key, values);
    return values;
  }

  Map<String, Map<String, Object>> contracts(ExactNode body) {
    var scope = blue.context.evidenceScope();
    if (declarationScope != scope) {
      clearDeclarations();
      declarationScope = scope;
    }
    boolean reusable =
        blue.context.nativeReuse() && !Boolean.getBoolean("blue.physical.nativeDisabled");
    // State-only writes leave the effective contract recipe unchanged. Declarations depend
    // on this recipe and its evidence scope, not on unrelated document fields.
    Recipe contracts = blue.head(body).contracts;
    String id = contracts == null ? "" : contracts.key();
    var retained = reusable ? declarations.get(id) : null;
    if (retained != null) return retained;
    ContractMetrics.count("contracts.declarations.opened");
    var result = readContracts(body);
    if (reusable && scope == blue.context.evidenceScope()) {
      // The recognition order is logical: immutable transport copies need not retain
      // iteration order, so keep the original sorted contract-name order explicitly.
      // header() already owns every immutable declaration, including its nested data.
      // Copy only the name index; re-freezing each header would revisit all program bodies.
      result = Collections.unmodifiableSortedMap(ContractCanonical.map(result));
      declarations.put(id, result, ContractCache.weight(List.of(id, result)));
    }
    return result;
  }

  private Map<String, Map<String, Object>> readContracts(ExactNode body) {
    Head h = blue.head(body);
    var out = new TreeMap<String, Map<String, Object>>(ContractCanonical.CODE_POINTS);
    if (h.contracts == null) return out;
    Head ch = blue.head(h.contracts);
    require(Set.of("object", "none").contains(ch.kind), "INVALID_CONTRACT_MAP");
    for (String k : ch.fields.keySet()) {
      token(k);
      Recipe r = ContractLanguage.child(ch, k);
      String role = role(r);
      if (MARKERS.contains(k)) {
        require(k.equals(role), "INVALID_PROCESSOR_STATE", "key", k);
        continue;
      }
      require(!MARKERS.contains(role == null ? "" : role), "MISPLACED_PROCESSOR_STATE", "key", k);
      var d = header(r);
      require(
          EXTERNAL.contains(role)
              || INTERNAL.contains(role)
              || EXECUTABLE.contains(role)
              || Set.of(
                      "embedding",
                      "generalization",
                      "extension_marker",
                      "compute_definition",
                      "extension_dispatch_policy")
                  .contains(role),
          "INVALID_CONTRACT_ROLE",
          "role",
          role);
      if (role.equals("embedding")) require(k.equals("embedded"), "EMBEDDING_KEY");
      if (role.equals("generalization")) {
        require(k.equals("generalization"), "GENERALIZATION_KEY");
        policy(body);
      }
      out.put(k, d);
    }
    out.forEach(
        (k, d) -> {
          if (EXECUTABLE.contains(d.get("_role"))) {
            var c = out.get(d.get("channel"));
            require(
                c != null
                    && (EXTERNAL.contains(c.get("_role")) || INTERNAL.contains(c.get("_role"))),
                "INVALID_HANDLER_CHANNEL",
                "handler",
                k);
          }
        });
    return out;
  }

  record Markers(
      Object registry, Object evidence, ExactNode body, Map<String, Optional<ExactNode>> values) {}

  ExactNode marker(Document document, String key) {
    var prepared = preparations == null ? null : preparations.get(document);
    if (prepared != null) return prepared.markers().get(key).orElse(null);
    if (!blue.context.nativeReuse() || Boolean.getBoolean("blue.physical.nativeDisabled"))
      return marker(document.body, key);
    var scope = blue.context.evidenceScope();
    var previous = document.markers;
    boolean same =
        previous != null
            && previous.registry == declarationOwner
            && previous.evidence == scope
            && previous.body == document.body;
    if (same && previous.values.containsKey(key)) return previous.values.get(key).orElse(null);
    ExactNode value = marker(document.body, key);
    Map<String, Optional<ExactNode>> values =
        same ? new HashMap<>(previous.values) : new HashMap<>();
    values.put(key, Optional.ofNullable(value));
    document.markers = new Markers(declarationOwner, scope, document.body, Map.copyOf(values));
    return value;
  }

  ExactNode marker(ExactNode body, String key) {
    Object cs = blue.open(body).get("contracts");
    if (cs == null) return null;
    Object value = blue.open((ExactNode) cs).get(key);
    if (value == null) return null;
    require(key.equals(role(value)), "INVALID_PROCESSOR_STATE", "key", key);
    return (ExactNode) value;
  }

  Map<String, Object> markerRecord(ExactNode marker, String key) {
    var raw = blue.open(marker);
    // These protected fields carry identities. Equivalent inline evidence must
    // not turn them into expanded maps when a warm runtime restores a snapshot.
    if (key.equals("initialized")) raw = referenceField(raw, "document");
    else if (key.equals("terminated")) {
      raw = openField(raw, "cause");
      raw = openField(raw, "reason");
    } else if (key.equals("checkpoint") && raw.get("entries") instanceof ExactNode value) {
      var entries = blue.open(value);
      for (String channel : entries.ordinary()) {
        var row = blue.open((ExactNode) entries.get(channel));
        entries = entries.with(channel, referenceField(openField(row, "timestamp"), "event"));
      }
      raw = raw.with("entries", entries);
    }
    return obj(ContractBridge.compact(export(raw)));
  }

  private Value openField(Value record, String field) {
    return record.get(field) instanceof ExactNode value
        ? record.with(field, blue.open(value))
        : record;
  }

  private static Value referenceField(Value record, String field) {
    Object value = record.get(field);
    return value instanceof ExactNode exact && !(exact instanceof Ref)
        ? record.with(field, new Ref(identity(exact)))
        : record;
  }

  Generalization.Policy policy(ExactNode body) {
    Head h = blue.head(body);
    Recipe r = h.contracts == null ? null : blue.child(h.contracts, "generalization");
    if (r == null) return new Generalization.Policy(List.of(), "reject", null);
    require("generalization".equals(role(r)), "INVALID_GENERALIZATION_POLICY");
    var d = declaration(r);
    var rules = new ArrayList<Generalization.Rule>();
    Recipe sequence = blue.child(r, "rules");
    if (sequence != null) {
      Head sh = blue.head(sequence);
      require(sh.items != null || !configured(r, "rules"), "INVALID_GENERALIZATION_RULES");
      if (sh.items != null)
        for (int i = 0; i < sh.items.size(); i++) {
          Recipe item = ContractLanguage.child(sh, i);
          Head ih = blue.head(item);
          require(
              Set.of("specification", "path", "mode", "mustRemainSubtypeOf")
                  .containsAll(ih.fields.keySet()),
              "INVALID_GENERALIZATION_RULE");
          Recipe floor = blue.child(item, "mustRemainSubtypeOf");
          String tid = floor == null ? null : identity(blue.exact(floor));
          if (tid != null) blue.certify(new Ref(tid), "definition");
          rules.add(
              new Generalization.Rule(
                  str(blue.project(blue.child(item, "path"))),
                  str(blue.project(blue.child(item, "mode"))),
                  tid));
        }
    }
    return new Generalization.Policy(
        rules, str(d.getOrDefault("defaultMode", "reject")), identity(blue.exact(r)));
  }
}
