package blue;

import static blue.BexValues.UNDEFINED;
import static blue.ContractLanguage.ABSENT;
import static blue.ContractLanguage.CORE;
import static blue.ContractLanguage.CORE_NAME;
import static blue.ContractLanguage.LITERALS;
import static blue.ContractLanguage.TYPES;
import static blue.ContractLanguage.ancestry;
import static blue.ContractLanguage.concrete;
import static blue.ContractLanguage.export;
import static blue.ContractLanguage.identity;
import static blue.ContractLanguage.map;
import static blue.ContractLanguage.normalize;
import static blue.ContractLanguage.recipe;
import static blue.ContractLanguage.refine;
import static blue.ContractModel.require;
import static blue.ExactNode.scalar;
import static blue.LanguageCore.exactItems;

import blue.ExactNode.Local;
import blue.ExactNode.Ref;
import blue.ExactNode.Value;
import blue.LanguageModel.Head;
import blue.LanguageModel.Recipe;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Native Language boundary for contract execution. Exact handles never become lossy maps. */
final class ContractBridge {
  final Language language;
  ContractLanguage context;
  private ContractLanguage certifiedContext;
  private final Set<String> certified = new HashSet<>();
  final ContractLanguage.Witnesses witnesses;
  // Every captured view participates in the active invocation's evidence scope.
  // Unmetered host reads and older attempts must not prepay a later invocation.
  final ThreadLocal<ContractLanguage.Witnesses> witnessScope;
  final boolean sharedIdentityEvidence;

  ContractBridge(Language language) {
    this(language, false);
  }

  ContractBridge(LanguageContent content) {
    this(content.owner, false, new ContractLanguage(content));
  }

  ContractBridge(Language language, boolean sharedIdentityEvidence) {
    this(language, sharedIdentityEvidence, new ContractLanguage(language));
  }

  private ContractBridge(
      Language language, boolean sharedIdentityEvidence, ContractLanguage context) {
    this.sharedIdentityEvidence = sharedIdentityEvidence;
    this.language = language;
    this.context = context;
    witnesses = new ContractLanguage.Witnesses();
    witnessScope = new ThreadLocal<>();
  }

  private ContractBridge(ContractBridge parent) {
    language = parent.language;
    witnessScope = parent.witnessScope;
    sharedIdentityEvidence = parent.sharedIdentityEvidence;
    context = parent.context.readSnapshot();
    // Identity evidence is shared by captured views, as in the canonical Bridge.
    // A resolver snapshot freezes content selection, not facts established later
    // about the same immutable handles. Logical reuse is still gated by the
    // invocation's established/carried/bound inventories in LogicalWork.
    witnesses = sharedIdentityEvidence ? parent.witnesses : parent.witnesses.fork();
    ContractMetrics.count("contracts.viewWitnessRootsShared");
  }

  ContractLanguage.Witnesses activeWitnesses() {
    var active = witnessScope.get();
    return active == null ? witnesses : active;
  }

  void note(ExactNode n, String id) {
    if (n instanceof Value) activeWitnesses().put(n, id);
  }

  boolean evidence(ExactNode n) {
    return n instanceof Ref || activeWitnesses().containsKey(n);
  }

  String known(ExactNode n) {
    if (n instanceof Ref r) return r.id();
    if (!activeWitnesses().containsKey(n)) return null;
    String id = activeWitnesses().get(n);
    if (id == null) {
      id = identity(n);
      note(n, id);
    }
    return id;
  }

  void refresh() {
    context = context.refreshed();
    // Verified identity witnesses survive resolver refresh. They are not cache
    // entries or validation proofs; each invocation still owns its reuse ledger.
    // Clearing this shared evidence would invalidate already captured views.
    if (!sharedIdentityEvidence) witnesses.clear();
    certified.clear();
    certifiedContext = context;
  }

  ContractModel.Error translate(LanguageFailure e) {
    if (e.category().equals("ProviderUnavailable")) {
      // Demands include successful reads; only failed dependencies should block recovery.
      var ids = new TreeSet<>(List.of(e.getMessage()));
      return new ContractModel.Error("NEEDS_RESOURCES", "required", List.copyOf(ids));
    }
    if (e.category().equals("OperationLimitExceeded"))
      return new ContractModel.Error("HOST_INCOMPLETE", "detail", e.getMessage());
    return new ContractModel.Error("LANGUAGE_" + e.category(), "detail", e.getMessage());
  }

  static Object compact(Object raw) {
    if (raw instanceof List<?> a) return a.stream().map(ContractBridge::compact).toList();
    if (!(raw instanceof Map<?, ?>)) return raw;
    return compactFields(map(raw), ContractBridge::compact);
  }

  private static Object compactFields(
      Map<String, Object> m, java.util.function.UnaryOperator<Object> child) {
    if (m.keySet().equals(Set.of("type", "value"))
        && m.get("type") instanceof Map<?, ?> t
        && CORE_NAME.containsKey(t.get("blueId"))
        && Set.of("Text", "Integer", "Double", "Boolean").contains(CORE_NAME.get(t.get("blueId"))))
      return CORE.get("Integer").equals(t.get("blueId")) && m.get("value") instanceof String s
          ? new BigInteger(s)
          : m.get("value");
    if ((m.keySet().equals(Set.of("items")) || m.keySet().equals(Set.of("items", "type")))
        && Objects.equals(
            m.getOrDefault("type", Map.of("blueId", CORE.get("List"))),
            Map.of("blueId", CORE.get("List")))) return child.apply(m.get("items"));
    var out = new TreeMap<String, Object>();
    m.forEach((k, v) -> out.put(k, child.apply(v)));
    return out;
  }

  Object input(Object value, boolean aliases) {
    if (value instanceof BigInteger n
        && n.abs().compareTo(BigInteger.valueOf(ContractModel.SAFE)) > 0)
      return Map.of("type", Map.of("blueId", CORE.get("Integer")), "value", n.toString());
    if (value instanceof List<?> a) return a.stream().map(v -> input(v, aliases)).toList();
    if (!(value instanceof Map<?, ?>)) return value;
    var out = new TreeMap<String, Object>();
    map(value)
        .forEach(
            (k, v) -> {
              if (k.equals("value"))
                out.put(
                    k,
                    v instanceof BigInteger n
                            && n.abs().compareTo(BigInteger.valueOf(ContractModel.SAFE)) > 0
                        ? n.toString()
                        : v);
              else if (TYPES.contains(k) && v instanceof String s) {
                require(aliases, "UNRESOLVED_RUNTIME_TYPE", "alias", s);
                String id = context.alias(s);
                require(id != null, "UNKNOWN_ALIAS", "alias", s);
                out.put(k, Map.of("blueId", id));
              } else out.put(k, input(v, aliases));
            });
    return out;
  }

  ExactNode source(Object value) {
    return source(value, "value");
  }

  ExactNode source(Object value, String goal) {
    try {
      // Internal adapters may reuse an immutable exact graph. Admission below
      // still checks its complete dependency closure and canonical representation.
      ExactNode n =
          value instanceof ExactNode exact
              ? exact
              : context.prepare(input(BexValues.copy(value), true));
      ExactNode admitted =
          context.nativeReuse() && !Boolean.getBoolean("blue.physical.nativeDisabled")
              ? context.admitLocal(n, goal)
              : wireAdmission(n, goal);
      String admittedId = identity(admitted);
      refresh();
      Value body = open(new Ref(admittedId));
      return context.admittedHandle(admittedId);
    } catch (LanguageFailure e) {
      throw translate(e);
    }
  }

  private ExactNode wireAdmission(ExactNode n, String goal) {
    LanguageAdmission d = context.admit(n, goal);
    context.retain(List.of(d.exact()));
    return ContractLanguage.exactOf(d);
  }

  ExactNode node(Object value) {
    return node(value, "value", true, null);
  }

  ExactNode node(Object value, String goal, boolean certify, ContractWork logical) {
    if (logical != null) return logical.node(value, goal, certify);
    try {
      if (value instanceof ExactView v) value = v.bridge.exact(v.recipe);
      ExactNode n =
          value instanceof ExactNode x
              ? x
              : normalize(input(BexValues.blueValue(value), false), context, "exact", false);
      if (certify) certify(n, goal);
      remember(n);
      return n;
    } catch (LanguageFailure e) {
      throw translate(e);
    }
  }

  void remember(ExactNode n) {
    context.remember(n);
    if (n instanceof Value) note(n, identity(n));
  }

  void retain(Collection<ExactNode> nodes) {
    // Collect the complete closure before merging alternate encodings. A replacement can
    // invalidate cached owning-set proofs; it must not do so halfway through this export.
    boolean nativeRecords =
        context.nativeReuse() && !Boolean.getBoolean("blue.physical.nativeDisabled");
    if (nativeRecords) context.retain(nodes);
    else context.addEvidence(context.records(nodes, false));
    refresh();
  }

  Value open(ExactNode n) {
    if (ContractMetrics.enabled()) {
      ContractMetrics.count("contracts.bodyOpenRequests");
      try {
        ContractMetrics.distinct("contracts.bodies", identity(n));
      } catch (LanguageFailure unfinalized) {
        ContractMetrics.count("contracts.unfinalizedBodyOpens");
      }
    }
    try {
      Value b = context.open(n);
      if (n instanceof Ref r) note(b, r.id().contains("#") ? null : r.id());
      ContractLanguage.Witnesses active = activeWitnesses();
      if (known(b) != null && !active.childrenKnown(b)) {
        for (var e : b.fields().entrySet())
          if (!LITERALS.contains(e.getKey()) && !e.getKey().equals("mergePolicy")) {
            if (e.getKey().equals("items")) {
              for (ExactNode child : exactItems(e.getValue()))
                if (child instanceof Value && !evidence(child)) note(child, null);
            } else if (e.getValue() instanceof Value child && !evidence(child)) note(child, null);
          }
        // The immutable child inventory belongs to this same witness scope. Forks retain
        // both inventories; eviction and a new invocation discard this shortcut together.
        active.childrenOpened(b);
      }
      return b;
    } catch (LanguageFailure e) {
      throw translate(e);
    }
  }

  void certify(ExactNode n) {
    certify(n, "value");
  }

  void certify(ExactNode n, String goal) {
    try {
      // Within this request, opened content is immutable verified evidence. Reuse only
      // successful proofs; bounded Language requests must retain their exact work limits.
      if (certifiedContext != context) {
        certified.clear();
        certifiedContext = context;
      }
      String key = context.nativeReuse() ? identity(n) + ":" + goal : null;
      if (key != null && certified.contains(key)) return;
      ContractMetrics.count("language.strictValidationCalls");
      ContractLanguage.strict(n, context, goal);
      if (key != null) certified.add(key);
    } catch (LanguageFailure e) {
      throw translate(e);
    }
  }

  Head head(Object r) {
    try {
      Recipe selected = r instanceof Recipe x ? x : recipe(r);
      return Boolean.getBoolean("blue.physical.borrowedHeadsDisabled")
          ? ContractLanguage.head(selected, context)
          : ContractLanguage.readHead(selected, context);
    } catch (LanguageFailure e) {
      throw translate(e);
    }
  }

  ExactNode exact(Object r) {
    if (r instanceof ExactNode n) return n;
    try {
      return ContractLanguage.exact((Recipe) r, context);
    } catch (LanguageFailure e) {
      throw translate(e);
    }
  }

  Recipe selected(Recipe parent, Recipe child) {
    if (child != null && parent.parts().stream().allMatch(this::evidence))
      for (ExactNode n : child.parts()) if (n instanceof Value && !evidence(n)) note(n, null);
    return child;
  }

  Recipe child(Object value, Object key) {
    Recipe r = value instanceof Recipe q ? q : recipe(value);
    Head h = head(r);
    return child(r, h, key);
  }

  /** Canonical selectors include metadata; lists use indices rather than an items wrapper. */
  List<String> childSelectors(Object value) {
    Recipe r = value instanceof Recipe q ? q : recipe(value);
    Head h = head(r);
    var names = new TreeSet<String>();
    if (h.items == null) names.addAll(h.fields.keySet());
    names.addAll(TYPES);
    names.addAll(LITERALS);
    names.addAll(List.of("contracts", "schema", "mergePolicy"));
    var selectors = new ArrayList<String>();
    for (String name : names) {
      Recipe selected = child(r, h, name);
      if (selected == null) continue;
      if (name.equals("value") && identity(exact(selected)).equals(identity(exact(r)))) continue;
      selectors.add(name);
    }
    if (h.items != null)
      for (int i = 0; i < h.items.size(); i++) selectors.add(Integer.toString(i));
    return List.copyOf(selectors);
  }

  private Recipe child(Recipe r, Head h, Object key) {
    if (key.equals("contracts")) return selected(r, h.contracts);
    if (TYPES.contains(key)) {
      String id =
          switch (key.toString()) {
            case "type" -> h.type;
            case "itemType" -> h.itemType;
            case "keyType" -> h.keyType;
            default -> h.valueType;
          };
      return id == null ? null : recipe(new Ref(id));
    }
    if (LITERALS.contains(key)) {
      Object v = key.equals("value") ? h.value : h.labels.getOrDefault(key, ABSENT);
      return v == ABSENT ? null : recipe(normalize(input(v, true), context, "exact", false));
    }
    if (key.equals("schema") || key.equals("mergePolicy")) {
      Object v = open(exact(r)).get(key.toString());
      return v == null ? null : recipe(key.equals("mergePolicy") ? scalar("Text", v) : v);
    }
    if (h.items != null) {
      if (key.equals("-")) return null;
      int i;
      if (key instanceof Integer z) i = z;
      else {
        require(LanguageSyntax.LIST_INDEX.matcher(key.toString()).matches(), "INVALID_LIST_INDEX");
        try {
          i = Integer.parseInt(key.toString());
        } catch (NumberFormatException e) {
          return null;
        }
      }
      return i >= 0 && i < h.items.size() ? selected(r, ContractLanguage.child(h, i)) : null;
    }
    return h.fields.containsKey(key) ? selected(r, ContractLanguage.child(h, key)) : null;
  }

  Recipe select(ExactNode n, String path) {
    Recipe r = recipe(n);
    for (String key : ContractModel.segments(path)) {
      r = child(r, key);
      if (r == null) return null;
    }
    return r;
  }

  Object project(Object value) {
    return project(value, false, Set.of(), false);
  }

  Object project(Object value, boolean definitions, Set<String> seen, boolean preserveCyclic) {
    return projectBranch(value, definitions, new HashSet<>(seen), preserveCyclic, null, 0);
  }

  Object project(Object value, ProjectionLimits limits) {
    return project(value, new ProjectionBudget(limits), 0);
  }

  Object project(Object value, ProjectionBudget budget, int depth) {
    return projectBranch(value, false, new HashSet<>(), false, budget, depth);
  }

  private Object projectBranch(
      Object value,
      boolean definitions,
      Set<String> path,
      boolean preserveCyclic,
      ProjectionBudget budget,
      int depth) {
    if (budget != null) budget.value(depth);
    if (value == null) return UNDEFINED;
    Recipe r = value instanceof Recipe q ? q : recipe(value);
    if (preserveCyclic && exact(r) instanceof Ref ref && ref.id().contains("#"))
      return projectedReference(ref.id(), budget, depth);
    Head h = head(r);
    if (h.value != ABSENT) {
      if (budget != null) budget.text(h.value);
      return h.value;
    }
    String key = r.key();
    if (!path.add(key)) return projectedReference(identity(exact(r)), budget, depth);
    try {
      if (h.items != null) {
        var out = new ArrayList<Object>();
        for (int i = 0; i < h.items.size(); i++)
          out.add(
              projectBranch(
                  ContractLanguage.child(h, i),
                  definitions,
                  path,
                  preserveCyclic,
                  budget,
                  depth + 1));
        return out;
      }
      var out = new TreeMap<String, Object>();
      for (String k : h.fields.keySet()) {
        Recipe ch = ContractLanguage.child(h, k);
        if (definitions || ContractLanguage.present(ch, context)) {
          if (budget != null) budget.entry(k);
          out.put(k, projectBranch(ch, definitions, path, preserveCyclic, budget, depth + 1));
        }
      }
      Value n = r.parts().isEmpty() ? null : open(exact(r));
      for (String k : List.of("name", "description")) {
        if (n != null && n.has(k)) {
          if (budget != null) {
            budget.entry(k);
            budget.scalar(n.get(k), depth + 1);
          }
          out.put(k, n.get(k));
        }
      }
      if (h.contracts != null) {
        if (budget != null) budget.entry("contracts");
        out.put(
            "contracts",
            budget == null
                ? literal(h.contracts)
                : projectedLiteral(open(exact(h.contracts)), budget, depth + 1));
      }
      if (h.type != null && !CORE_NAME.containsKey(h.type)) {
        if (budget != null) {
          budget.entry("type");
          budget.value(depth + 1);
        }
        out.put("type", projectedReference(h.type, budget, depth + 1));
      }
      return !definitions && out.isEmpty() && !concrete(h, context) ? UNDEFINED : out;
    } finally {
      // Only ancestors are cyclic. Equal sibling subtrees must each remain fully projected.
      path.remove(key);
    }
  }

  /** The reference container was counted by the caller before reaching this branch. */
  private static Object projectedReference(String id, ProjectionBudget budget, int depth) {
    if (budget != null) {
      budget.entry("blueId");
      budget.scalar(id, depth + 1);
    }
    return Map.of("blueId", id);
  }

  /** Compact exact literals directly, so a bounded read never first exports their entire tree. */
  private Object projectedLiteral(ExactNode node, ProjectionBudget budget, int depth) {
    return projectedLiteral(node, budget, depth, false, Set.of());
  }

  private Object projectedLiteral(
      ExactNode node,
      ProjectionBudget budget,
      int depth,
      boolean inlineScalars,
      Set<String> referenceFields) {
    budget.value(depth);
    if (inlineScalars && node instanceof Ref ref && !ref.id().contains("#")) {
      Value opened = open(node);
      if (scalarLiteral(opened.fields())) node = opened;
    }
    if (node instanceof Ref ref) return projectedReference(ref.id(), budget, depth);
    if (node instanceof Local local)
      return projectedReference("this#" + local.index(), budget, depth);
    Map<String, Object> fields = ((Value) node).fields();
    String type = fields.get("type") instanceof Ref ref ? ref.id() : null;
    if (scalarLiteral(fields)) {
      Object scalar = fields.get("value");
      if (CORE.get("Integer").equals(type) && scalar instanceof String text)
        scalar = new BigInteger(text);
      budget.text(scalar);
      return scalar;
    }
    if (fields.keySet().equals(Set.of("items"))
        || fields.keySet().equals(Set.of("items", "type")) && CORE.get("List").equals(type))
      return projectedItems(exactItems(fields.get("items")), budget, depth, inlineScalars);
    Map<String, Object> result = new TreeMap<>();
    for (var entry : fields.entrySet()) {
      String key = entry.getKey();
      Object value = entry.getValue();
      budget.entry(key);
      if (LITERALS.contains(key) || key.equals("mergePolicy"))
        result.put(key, budget.scalar(value, depth + 1));
      else if (key.equals("items")) {
        budget.value(depth + 1);
        result.put(key, projectedItems(exactItems(value), budget, depth + 1, inlineScalars));
      } else
        result.put(
            key,
            projectedLiteral(
                (ExactNode) value,
                budget,
                depth + 1,
                inlineScalars && !TYPES.contains(key) && !referenceFields.contains(key),
                Set.of()));
    }
    return result;
  }

  private static boolean scalarLiteral(Map<String, Object> fields) {
    return fields.keySet().equals(Set.of("type", "value"))
        && fields.get("type") instanceof Ref ref
        && Set.of("Text", "Integer", "Double", "Boolean")
            .contains(CORE_NAME.getOrDefault(ref.id(), ""));
  }

  private List<Object> projectedItems(
      List<ExactNode> items, ProjectionBudget budget, int depth, boolean inlineScalars) {
    List<Object> result = new ArrayList<>();
    for (ExactNode child : items)
      result.add(projectedLiteral(child, budget, depth + 1, inlineScalars, Set.of()));
    return result;
  }

  Object view(Object value, ContractWork logical) {
    if (value == null) return UNDEFINED;
    Recipe r = value instanceof Recipe q ? q : recipe(value);
    if (logical != null) for (ExactNode p : r.parts()) logical.open(p, this);
    Head h = head(r);
    if (h.value != ABSENT) return h.value;
    if (h.items != null) return new ExactView(this, r, logical, true, h);
    if (!(h.contracts != null
        || h.type != null && !CORE_NAME.containsKey(h.type)
        || h.labels.containsKey("name")
        || h.labels.containsKey("description")
        || concrete(h, context))) return UNDEFINED;
    return new ExactView(this, r, logical, false, h);
  }

  Object literal(Object r) {
    return compact(export(open(exact(r))));
  }

  Object literal(Object value, Set<String> referenceFields, ProjectionLimits limits) {
    return literal(value, referenceFields, limits, false);
  }

  Object literal(
      Object value, Set<String> referenceFields, ProjectionLimits limits, boolean inlineScalars) {
    Value selected = open(exact(value));
    for (String field : referenceFields) {
      Object child = selected.get(field);
      if (child == null) continue;
      if (!(child instanceof ExactNode node))
        throw new IllegalArgumentException(
            "Only exact child fields can be projected as references");
      selected = selected.with(field, new Ref(identity(node)));
    }
    return projectedLiteral(
        selected, new ProjectionBudget(limits), 0, inlineScalars, referenceFields);
  }

  Object bexData(Object r) {
    return bexData(r, Set.of());
  }

  Object bexData(Object r, Set<String> seen) {
    ExactNode n = exact(r);
    var next = new HashSet<>(seen);
    if (n instanceof Ref ref) {
      if (ref.id().contains("#") || !next.add(ref.id())) return export(ref);
    }
    var out = new TreeMap<String, Object>();
    open(n)
        .fields()
        .forEach(
            (k, v) -> {
              if (LITERALS.contains(k) || k.equals("mergePolicy")) out.put(k, v);
              else if (TYPES.contains(k)) out.put(k, export((ExactNode) v));
              else if (k.equals("items"))
                out.put(k, exactItems(v).stream().map(x -> bexData(x, next)).toList());
              else out.put(k, bexData(v, next));
            });
    // Descendants have already been converted by bexData. Rewalking them here makes
    // conversion proportional to tree size times depth instead of the emitted structure.
    return compactFields(out, java.util.function.UnaryOperator.identity());
  }

  Object readBex(Object value, String path) {
    if (value instanceof BexValues.HostValue) return BexValues.pointerGet(value, path);
    Object at = value;
    for (String k : ContractModel.segments(path.equals("/") ? "" : path)) {
      if (at instanceof Map<?, ?> m && m.keySet().equals(Set.of("blueId")))
        at = view(new Ref((String) m.get("blueId")), null);
      if (BexValues.kind(at).equals("object")) at = BexValues.get(at, k);
      else if (BexValues.kind(at).equals("list")) {
        if (!LanguageSyntax.LIST_INDEX.matcher(k).matches()) return UNDEFINED;
        try {
          at = BexValues.get(at, Integer.parseInt(k));
        } catch (NumberFormatException e) {
          return UNDEFINED;
        }
      } else return UNDEFINED;
    }
    return BexValues.copy(at);
  }

  boolean matches(Object value, Object pattern, ContractWork logical) {
    ExactNode n = node(value instanceof Recipe ? exact(value) : value, "value", true, logical),
        p = node(pattern instanceof Recipe ? exact(pattern) : pattern, "definition", true, logical);
    if (!presentActual(recipe(n), recipe(p), new HashSet<>())) return false;
    try {
      refine(p, n, context, false);
      return true;
    } catch (LanguageFailure e) {
      if (Set.of(
              "TypeCompatibilityViolation",
              "SchemaViolation",
              "FixedValueConflict",
              "CollectionCompatibilityViolation",
              "InvalidCollectionType",
              "IncompatibleTypes")
          .contains(e.category())) return false;
      throw translate(e);
    }
  }

  boolean presentActual(Recipe actual, Recipe wanted, Set<String> seen) {
    String pair = actual.key() + wanted.key();
    if (!seen.add(pair)) return true;
    Head a = head(actual), p = head(wanted);
    if (p.type != null && (a.type == null || !ancestry(a.type, context).contains(p.type)))
      return false;
    Value authored = open(exact(wanted));
    if (p.items != null) {
      if (a.items == null || a.items.size() < p.items.size()) return false;
      for (int i = 0; i < p.items.size(); i++)
        if (!presentActual(
            ContractLanguage.child(a, i), ContractLanguage.child(p, i), new HashSet<>(seen)))
          return false;
    }
    for (String k : p.fields.keySet()) {
      Recipe expected = ContractLanguage.child(p, k);
      if (!authored.has(k) && !ContractLanguage.present(expected, context)) continue;
      Recipe ch = child(actual, k);
      if (ch == null
          || !ContractLanguage.present(ch, context)
          || !presentActual(ch, expected, new HashSet<>(seen))) return false;
    }
    return true;
  }

  ExactNode setAt(ExactNode root, String path, ExactNode value, String operation) {
    var parts = ContractModel.segments(path);
    require(!parts.isEmpty() && !parts.contains(""), "INVALID_PATCH_PATH", "path", path);
    return set(root, recipe(root), parts, value, operation);
  }

  ExactNode set(ExactNode exact, Recipe r, List<String> parts, ExactNode val, String op) {
    Value n = open(exact);
    Head h = head(r);
    String key = parts.getFirst();
    boolean last = parts.size() == 1;
    var fields = new TreeMap<>(n.fields());
    if (h.items != null) {
      require(
          key.equals("-") && op.equals("add") && last
              || LanguageSyntax.LIST_INDEX.matcher(key).matches(),
          "INVALID_LIST_INDEX");
      var values = new ArrayList<ExactNode>();
      for (int i = 0; i < h.items.size(); i++)
        values.add(exact(selected(r, ContractLanguage.child(h, i))));
      int i = key.equals("-") ? values.size() : Integer.parseInt(key);
      require(
          i >= 0 && (op.equals("add") && last ? i <= values.size() : i < values.size()),
          "INVALID_LIST_INDEX");
      if (!last)
        values.set(
            i,
            set(
                values.get(i),
                ContractLanguage.child(h, i),
                parts.subList(1, parts.size()),
                val,
                op));
      else if (op.equals("remove")) values.remove(i);
      else if (op.equals("add")) values.add(i, val);
      else values.set(i, val);
      fields.put("items", List.copyOf(values));
    } else if (LITERALS.contains(key)) {
      require(last, "INVALID_PATCH_PATH");
      if (op.equals("remove")) {
        require(fields.containsKey(key), "MISSING_PATCH_TARGET");
        fields.remove(key);
      } else {
        Object v = head(val).value;
        require(v != ABSENT, "INVALID_LITERAL");
        fields.put(key, v);
      }
    } else {
      Recipe ch = child(r, key);
      if (!last) {
        require(ch != null, "MISSING_PATCH_PARENT");
        fields.put(key, set(exact(ch), ch, parts.subList(1, parts.size()), val, op));
      } else if (op.equals("remove")) {
        require(fields.containsKey(key), "MISSING_PATCH_TARGET");
        fields.remove(key);
      } else if (TYPES.contains(key)) {
        remember(val);
        fields.put(key, new Ref(identity(val)));
      } else fields.put(key, val);
    }
    return ExactNode.value(fields);
  }

  ExactNode substitute(ExactNode root, Map<String, ExactNode> replacements) {
    return substituteAt(root, recipe(root), replacements);
  }

  ExactNode substituteAt(ExactNode n, Recipe r, Map<String, ExactNode> rows) {
    var fields = new TreeMap<>(open(n).fields());
    Head h = head(r);
    var grouped = new TreeMap<String, Map<String, ExactNode>>();
    rows.forEach(
        (p, v) -> {
          var path = ContractModel.segments(p);
          require(!path.isEmpty(), "INVALID_SYMBOLIC_PATH");
          grouped
              .computeIfAbsent(path.getFirst(), k -> new TreeMap<>())
              .put(ContractModel.pointer(path.subList(1, path.size())), v);
        });
    if (h.items != null) {
      var items = new ArrayList<ExactNode>();
      for (int i = 0; i < h.items.size(); i++) items.add(exact(ContractLanguage.child(h, i)));
      for (var e : grouped.entrySet()) {
        require(LanguageSyntax.LIST_INDEX.matcher(e.getKey()).matches(), "INVALID_LIST_INDEX");
        int i = Integer.parseInt(e.getKey());
        require(i < items.size(), "INVALID_LIST_INDEX");
        items.set(i, replacement(items.get(i), ContractLanguage.child(h, i), e.getValue()));
      }
      fields.put("items", List.copyOf(items));
    } else
      for (var e : grouped.entrySet()) {
        Recipe ch = child(r, e.getKey());
        if (e.getValue().containsKey("")) {
          require(e.getValue().size() == 1, "OVERLAPPING_SYMBOLIC_PATH");
          fields.put(e.getKey(), e.getValue().get(""));
        } else {
          require(ch != null, "MISSING_SYMBOLIC_PATH");
          fields.put(e.getKey(), substituteAt(exact(ch), ch, e.getValue()));
        }
      }
    return ExactNode.value(fields);
  }

  ExactNode replacement(ExactNode n, Recipe r, Map<String, ExactNode> rows) {
    if (rows.containsKey("")) {
      require(rows.size() == 1, "OVERLAPPING_SYMBOLIC_PATH");
      return rows.get("");
    }
    return substituteAt(n, r, rows);
  }

  Map<String, ExactNode> finalizeComponent(
      List<String> keys, Map<String, ExactNode> bodies, List<ContractModel.Edge> edges) {
    var raw = new ArrayList<Object>();
    for (String k : keys) {
      var replacements = new TreeMap<String, ExactNode>();
      for (var e : edges)
        if (e.source().equals(k) && keys.contains(e.target()))
          replacements.put(e.path(), new Local(keys.indexOf(e.target())));
      raw.add(export(substitute(bodies.get(k), replacements)));
    }
    var result = context.finalizeComponent(raw);
    var out = new TreeMap<String, ExactNode>();
    for (int i = 0; i < keys.size(); i++) out.put(keys.get(i), result.get(i));
    return out;
  }

  static final class ExactView implements BexValues.HostValue {
    final ContractBridge bridge;
    final Recipe recipe;
    final ContractWork logical;
    final boolean list;
    final boolean establishedValue;
    private Head cachedHead;
    private List<String> cachedKeys;
    private Value cachedOwnBody;

    ExactView(ContractBridge parent, Recipe r, ContractWork logical, boolean list, Head head) {
      establishedValue = parent.context.establishedValue(r);
      bridge = new ContractBridge(parent);
      recipe = r;
      this.logical = logical;
      this.list = list;
      if (bridge.context.nativeReuse()) cachedHead = head;
    }

    Head head() {
      if (logical != null) for (ExactNode p : recipe.parts()) logical.open(p, bridge);
      if (!bridge.context.nativeReuse()) return bridge.head(recipe);
      if (cachedHead == null) cachedHead = bridge.head(recipe);
      return cachedHead;
    }

    public String kind() {
      return list ? "list" : "object";
    }

    public int size() {
      return list ? head().items.size() : keys().size();
    }

    public List<String> keys() {
      if (list) return List.of();
      Head h = head();
      if (cachedKeys != null) return new ArrayList<>(cachedKeys);
      var keys = new ArrayList<String>();
      for (String k : h.fields.keySet())
        if (ContractLanguage.present(ContractLanguage.child(h, k), bridge.context)) keys.add(k);
      Value n = ownBody();
      for (String k : List.of("name", "description")) if (n != null && n.has(k)) keys.add(k);
      if (h.contracts != null) keys.add("contracts");
      if (h.type != null && !CORE_NAME.containsKey(h.type)) keys.add("type");
      if (bridge.context.nativeReuse()) cachedKeys = List.copyOf(keys);
      return keys;
    }

    public Object get(Object key) {
      Head h = head();
      if (list) {
        if (!(key instanceof Integer i) || i < 0 || i >= h.items.size()) return UNDEFINED;
      } else {
        if (key.equals("type") && h.type != null && !CORE_NAME.containsKey(h.type))
          return Map.of("blueId", h.type);
        if (key.equals("contracts") && h.contracts != null)
          return bridge.view(child(h, key), logical);
        if (Set.of("name", "description").contains(key)) {
          Value n = ownBody();
          return n.has(key.toString()) ? n.get(key.toString()) : UNDEFINED;
        }
        if (!h.fields.containsKey(key)) return UNDEFINED;
      }
      if (logical != null) logical.meter.contracts(list ? "listItemRead" : "objectMemberRead");
      return bridge.view(child(h, key), logical);
    }

    private Recipe child(Head head, Object key) {
      return bridge.context.nativeReuse()
          ? bridge.child(recipe, head, key)
          : bridge.child(recipe, key);
    }

    private Value ownBody() {
      if (recipe.parts().isEmpty()) return null;
      if (!bridge.context.nativeReuse()) return bridge.open(bridge.exact(recipe));
      if (cachedOwnBody == null) cachedOwnBody = bridge.open(bridge.exact(recipe));
      return cachedOwnBody;
    }

    public Object blueOutput() {
      return export(bridge.exact(recipe));
    }

    public Object accountingView() {
      ExactNode n = logical == null ? bridge.exact(recipe) : logical.exact(recipe, bridge);
      String id = bridge.known(n);
      if (id == null && logical != null) id = logical.establishCaptured(n, bridge);
      return id == null ? export(n) : Map.of("blueId", id);
    }
  }
}
