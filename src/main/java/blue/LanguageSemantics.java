package blue;

import static blue.ExactNode.scalar;
import static blue.LanguageCore.ABSENT;
import static blue.LanguageCore.CORE;
import static blue.LanguageCore.CORE_NAME;
import static blue.LanguageCore.LABELS;
import static blue.LanguageCore.LITERALS;
import static blue.LanguageCore.TYPES;
import static blue.LanguageCore.decimal;
import static blue.LanguageCore.exactItems;
import static blue.LanguageCore.hole;
import static blue.LanguageCore.identity;
import static blue.LanguageCore.index;
import static blue.LanguageCore.normalize;
import static blue.LanguageCore.open;
import static blue.LanguageFailure.require;

import blue.ExactNode.Ref;
import blue.ExactNode.Value;
import blue.LanguageModel.Head;
import blue.LanguageModel.Recipe;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** A06/A07: declaration recipes, contextual interpretation and admission. */
final class LanguageSemantics {
  private LanguageSemantics() {}

  static Recipe recipe(Object... parts) {
    return recipeWith(List.of(), parts);
  }

  static Recipe recipeWith(List<String> requirements, Object... parts) {
    if (parts.length == 1) {
      if (parts[0] instanceof Recipe r) {
        if (requirements.isEmpty()) {
          return r;
        }
        List<String> merged = recipeRequirements(requirements, r.requirements);
        // A Recipe already owns normalized immutable parts. Adding requirements does not
        // require flattening, rehashing or copying those parts again.
        return merged.equals(r.requirements) ? r : new Recipe(r.parts, merged);
      }
      if (parts[0] instanceof ExactNode n) {
        return new Recipe(List.of(n), recipeRequirements(requirements, List.of()));
      }
    }
    var flat = new ArrayList<ExactNode>();
    var req = new LinkedHashSet<>(requirements);
    for (Object p : parts) {
      if (p instanceof Recipe r) {
        flat.addAll(r.parts);
        req.addAll(r.requirements);
      } else if (p instanceof ExactNode n) {
        flat.add(n);
      } else if (p != null) {
        throw new IllegalArgumentException("recipe");
      }
    }
    var seen = new HashSet<String>();
    var keep = new ArrayList<ExactNode>();
    for (int i = flat.size() - 1; i >= 0; i--) {
      if (seen.add(identity(flat.get(i)))) {
        keep.add(flat.get(i));
      }
    }
    Collections.reverse(keep);
    return new Recipe(List.copyOf(keep), List.copyOf(req));
  }

  private static List<String> recipeRequirements(List<String> first, List<String> rest) {
    if (rest.isEmpty() && first.size() < 2) {
      return first;
    }
    var merged = new LinkedHashSet<>(first);
    merged.addAll(rest);
    return List.copyOf(merged);
  }

  static final class HeadDraft {
    String type, core, kind = "none", policy, itemType, keyType, valueType;
    Map<String, Object> labels;
    Ref specification;
    LanguageSchema constraints, authoredSchema;
    Object value = ABSENT;
    Map<String, Recipe> fields;
    List<Recipe> items;
    Recipe contracts;
    boolean emptyObject;
    ExactNode origin;
    List<ExactNode> origins = List.of();

    HeadDraft() {
      this(true);
    }

    HeadDraft(boolean initialize) {
      if (initialize) {
        labels = new HeadMap<>();
        constraints = LanguageSchema.EMPTY;
        fields = new HeadMap<>();
      }
    }

    Head build() {
      return new Head(this);
    }
  }

  static String kind(String core) {
    return core == null
        ? "none"
        : core.equals("List") ? "list" : core.equals("Dictionary") ? "object" : "scalar";
  }

  static List<String> ancestry(String t, LanguageContext c) {
    var out = new ArrayList<String>();
    var seen = new HashSet<String>();
    while (t != null) {
      require(seen.add(t), "TypeCycle", t);
      out.add(t);
      if (CORE_NAME.containsKey(t)) {
        break;
      }
      Object r = open(new Ref(t), c).get("type");
      t = r instanceof Ref ref ? ref.id() : null;
    }
    return out;
  }

  static String domain(String t, LanguageContext c) {
    if (t == null) {
      return null;
    }
    var a = ancestry(t, c);
    String last = a.getLast();
    if (CORE_NAME.containsKey(last)) {
      return CORE_NAME.get(last);
    }
    return switch (open(new Ref(last), c).kind()) {
      case "object" -> "Dictionary";
      case "list" -> "List";
      default -> null;
    };
  }

  static Head plan(String t, LanguageContext c) {
    var chain = new ArrayList<String>();
    var seen = new HashSet<String>();
    String current = t;
    while (!c.heads.containsKey("type:" + current)) {
      require(
          seen.add(current) && !c.activeHeads.contains("type:" + current), "TypeCycle", current);
      c.bound("depth", chain.size() + 1);
      chain.add(current);
      if (CORE_NAME.containsKey(current)) {
        break;
      }
      Object p = open(new Ref(current), c).get("type");
      if (!(p instanceof Ref r)) {
        break;
      }
      current = r.id();
    }
    for (int i = chain.size() - 1; i >= 0; i--) {
      String id = chain.get(i), key = "type:" + id;
      require(c.activeHeads.add(key), "TypeCycle");
      try {
        Head h;
        if (CORE_NAME.containsKey(id)) {
          var draft = new HeadDraft();
          draft.core = CORE_NAME.get(id);
          draft.kind = kind(draft.core);
          h = draft.build();
        } else {
          h = single(open(new Ref(id), c), c);
        }
        c.heads.put(key, h);
        if (h.constraints.kinds() != null) {
          h.constraints.validate(h, true, "definition", true, c);
        }
      } catch (RuntimeException e) {
        c.heads.remove(key);
        throw e;
      } finally {
        c.activeHeads.remove(key);
      }
    }
    c.step();
    return c.heads.get("type:" + t);
  }

  static Head requirement(String t, LanguageContext c) {
    HeadDraft h = plan(t, c).copy(false);
    h.labels.clear();
    h.specification = null;
    h.type = t;
    h.authoredSchema = null;
    return h.build();
  }

  static Object effective(Object v, String core) {
    if (v == ABSENT) {
      return v;
    }
    if ("Integer".equals(core)) {
      if (v instanceof String s) {
        require(
            LanguageSyntax.INTEGER.matcher(s).matches(),
            "TypeCompatibilityViolation",
            "Integer text");
        return new BigInteger(s);
      }
      if (v instanceof Double d) {
        require(
            Double.isFinite(d) && d == Math.rint(d), "TypeCompatibilityViolation", "Integer value");
        return new BigDecimal(d).toBigIntegerExact();
      }
      require(v instanceof BigInteger, "TypeCompatibilityViolation", "Integer value");
    } else if ("Double".equals(core)) {
      require(
          v instanceof BigInteger || v instanceof Double,
          "TypeCompatibilityViolation",
          "Double value");
      double d = ((Number) v).doubleValue();
      require(Double.isFinite(d), "InvalidScalar");
      return d;
    } else if ("Text".equals(core)) {
      require(v instanceof String, "TypeCompatibilityViolation", "Text value");
    } else if ("Boolean".equals(core)) {
      require(v instanceof Boolean, "TypeCompatibilityViolation", "Boolean value");
    }
    return v;
  }

  static String choose(String p, String q, Object v, LanguageContext c) {
    if (p == null) {
      return q;
    }
    if (q == null || q.equals(p)) {
      return p;
    }
    String pc = domain(p, c), qc = domain(q, c);
    if (v != ABSENT && CORE_NAME.containsKey(q)) {
      if (Objects.equals(pc, qc)) {
        return p;
      }
      if ("Integer".equals(pc)
          && "Text".equals(qc)
          && v instanceof String s
          && LanguageSyntax.INTEGER.matcher(s).matches()) {
        return p;
      }
    }
    LanguageDiagnostics.require(
        subtype(q, p, c),
        "TypeCompatibilityViolation",
        "not a subtype",
        LanguageDiagnostics.value(ABSENT, p, c, null),
        LanguageDiagnostics.value(v, q, c, null),
        null);
    return q;
  }

  static String narrow(String p, String q, LanguageContext c) {
    if (q == null) {
      return p;
    }
    if (p == null || p.equals(q)) {
      return q;
    }
    LanguageDiagnostics.require(
        subtype(q, p, c),
        "TypeCompatibilityViolation",
        "collection type widening",
        LanguageDiagnostics.value(ABSENT, p, c, null),
        LanguageDiagnostics.value(ABSENT, q, c, null),
        null);
    return q;
  }

  static Head single(ExactNode exact, LanguageContext c) {
    c.step();
    if (!c.establishedValues.isEmpty()) {
      Head established = c.establishedValues.get(identity(exact));
      if (established != null) {
        return established;
      }
    }
    Value n = open(exact, c);
    String tid = n.get("type") instanceof Ref r ? r.id() : null;
    Head inherited = tid == null ? new Head() : requirement(tid, c);
    HeadDraft h = new HeadDraft();
    h.type = tid;
    h.core =
        tid != null
            ? domain(tid, c)
            : n.kind().equals("object") ? "Dictionary" : n.kind().equals("list") ? "List" : null;
    h.kind = n.kind();
    h.origin = n;
    h.origins = List.of(n);
    for (String k : LABELS) {
      if (n.has(k)) {
        h.labels.put(k, n.get(k));
      }
    }
    h.specification = (Ref) n.get("specification");
    h.policy = (String) n.get("mergePolicy");
    h.emptyObject = n.kind().equals("object") && n.ordinary().isEmpty();
    if (n.get("itemType") instanceof Ref r) {
      h.itemType = r.id();
    }
    if (n.get("keyType") instanceof Ref r) {
      h.keyType = r.id();
    }
    if (n.get("valueType") instanceof Ref r) {
      h.valueType = r.id();
    }
    if (n.has("schema")) {
      h.authoredSchema =
          LanguageDiagnostics.child(
              c, "schema", () -> LanguageSchema.read((ExactNode) n.get("schema"), c));
      h.constraints = h.authoredSchema;
    }
    if (n.has("value")) {
      try {
        h.value = effective(n.get("value"), h.core);
      } catch (LanguageFailure e) {
        e.data.putIfAbsent("expected", LanguageDiagnostics.value(ABSENT, tid, c, null));
        e.data.putIfAbsent("actual", LanguageDiagnostics.value(n.get("value"), null, c, null));
        throw e;
      }
    }
    for (String k : n.ordinary()) {
      h.fields.put(k, recipe(n.get(k)));
    }
    if (n.has("items")) {
      h.items = (exactItems(n.get("items"))).stream().map(LanguageSemantics::recipe).toList();
    }
    if (n.has("contracts")) {
      h.contracts = recipe(n.get("contracts"));
    }
    return merge(inherited, h.build(), c);
  }

  static Head head(Recipe r, LanguageContext c) {
    return readHead(r, c);
  }

  /** Borrowed internal read result. All mutation uses copy()/plan(), never this method. */
  static Head readHead(Recipe r, LanguageContext c) {
    c.step();
    String key = r.headKey();
    boolean cacheable = c.activeHeads.isEmpty();
    if (cacheable) {
      Head cached = c.heads.get(key);
      if (cached != null) {
        return cached;
      }
    }
    Head h = new Head();
    for (String t : r.requirements) {
      h = merge(h, requirement(t, c), c);
    }
    for (ExactNode n : r.parts) {
      h = merge(h, single(n, c), c);
    }
    if (cacheable) {
      c.heads.put(key, h);
    }
    return h;
  }

  static boolean concreteNode(ExactNode root, LanguageContext c) {
    if (!c.establishedValues.isEmpty() && c.establishedValues.containsKey(identity(root))) {
      return true;
    }
    boolean reusable = c.cacheVerifiedClosures && (c.limits.isEmpty() || c.reuseWithinHostBudget);
    String key = null;
    if (reusable) {
      key = identity(root);
      Boolean cached = c.concreteFact(key);
      if (cached != null) {

        return cached;
      }
    }

    boolean result = concreteNodeUncached(root, c, reusable);
    if (reusable) {
      rememberConcrete(c, key, result);
    }
    return result;
  }

  private static void rememberConcrete(LanguageContext c, String key, boolean value) {
    if (c.concreteFacts.size() >= 8192 && !c.concreteFacts.containsKey(key)) {
      c.concreteFacts.clear();
    }
    c.concreteFacts.put(key, value);
  }

  private static boolean concreteNodeUncached(ExactNode root, LanguageContext c, boolean reusable) {
    // Most reads select a literal or list. Resolve that first node before allocating traversal
    // state, preserving the same visit/open host work for inline values and references alike.
    c.step();
    Value first = open(root, c);
    if (first.has("value") || first.has("items") || first.fields().isEmpty()) {
      return true;
    }
    var pending = new ArrayDeque<ExactNode>();
    pending.addLast(root);
    var seen = new HashSet<String>();
    var ordinary = new HashMap<String, List<String>>();
    while (!pending.isEmpty()) {
      ExactNode n = pending.removeLast();
      String id = identity(n);
      if (!seen.add(id)) {
        continue;
      }
      if (n != root) {
        c.step();
      }
      if (reusable) {
        Boolean known = c.concreteFact(id);
        if (Boolean.TRUE.equals(known)) {
          return true;
        }
        // A complete negative closure has no concrete descendant or ordinary cycle.
        if (Boolean.FALSE.equals(known)) {
          continue;
        }
      }
      Value b = n == root ? first : open(n, c);
      if (b.has("value") || b.has("items") || b.fields().isEmpty()) {
        return true;
      }
      var children = new ArrayList<ExactNode>();
      for (String k : b.ordinary()) {
        children.add((ExactNode) b.get(k));
      }
      ordinary.put(id, children.stream().map(LanguageCore::identity).toList());
      pending.addAll(children);
      if (b.get("type") instanceof Ref r && !CORE_NAME.containsKey(r.id())) {
        pending.addLast(r);
      }
    }
    var degree = new HashMap<String, Integer>();
    seen.forEach(k -> degree.put(k, 0));
    ordinary.values().forEach(xs -> xs.forEach(k -> degree.merge(k, 1, Integer::sum)));
    var queue = new ArrayDeque<String>();
    degree.forEach(
        (k, v) -> {
          if (v == 0) {
            queue.add(k);
          }
        });
    int removed = 0;
    while (!queue.isEmpty()) {
      String k = queue.pop();
      removed++;
      for (String target : ordinary.getOrDefault(k, List.of())) {
        if (degree.merge(target, -1, Integer::sum) == 0) {
          queue.add(target);
        }
      }
    }
    boolean cyclic = removed < seen.size();
    // Only a complete negative traversal establishes every visited node. A positive sibling
    // says nothing about another branch; failed or incomplete traversals establish nothing.
    if (!cyclic && reusable) {
      for (String id : seen) {
        rememberConcrete(c, id, false);
      }
    }
    return cyclic;
  }

  static boolean concrete(Head h, LanguageContext c) {
    for (ExactNode n : h.origins) {
      if (concreteNode(n, c)) {
        return true;
      }
    }
    return false;
  }

  static boolean present(Recipe r, LanguageContext c) {
    return r != null && concrete(head(r, c), c);
  }

  static boolean isHole(Recipe r) {
    return r.parts.size() == 1 && r.requirements.isEmpty() && hole(r.parts.getFirst());
  }

  static Head merge(Head p, Head q, LanguageContext c) {
    c.step();
    HeadDraft out = p.copy(false);
    out.type = choose(p.type, q.type, q.value, c);
    out.core = out.type != null ? domain(out.type, c) : p.core != null ? p.core : q.core;
    if (p.core != null && q.core != null && !p.core.equals(q.core)) {
      require(
          q.value != ABSENT && p.core.equals("Integer") && q.core.equals("Text"),
          "TypeCompatibilityViolation",
          "nominal core mismatch");
    }
    out.itemType = narrow(p.itemType, q.itemType, c);
    out.keyType = narrow(p.keyType, q.keyType, c);
    out.valueType = narrow(p.valueType, q.valueType, c);
    if (q.authoredSchema != null) {
      LanguageSchema.narrowing(p.constraints, q.authoredSchema, c);
    }
    out.constraints = LanguageSchema.conjoin(p.constraints, q.constraints);
    out.authoredSchema = q.authoredSchema;
    if (p.policy != null && q.policy != null) {
      require(p.policy.equals(q.policy), "TypeCompatibilityViolation", "mergePolicy");
    }
    out.policy = q.policy != null ? q.policy : p.policy;
    for (var e : q.labels.entrySet()) {
      if (p.labels.containsKey(e.getKey()) && !p.labels.get(e.getKey()).equals(e.getValue())) {
        require(!concrete(p, c), "FixedValueConflict", e.getKey());
      }
      out.labels.put(e.getKey(), e.getValue());
    }
    if (q.specification != null) {
      if (p.specification != null && !p.specification.equals(q.specification)) {
        require(!concrete(p, c), "FixedValueConflict", "specification");
      }
      out.specification = q.specification;
    }
    if (q.kind.equals("none")) {
    } else if (p.kind.equals("none")) {
      out.kind = q.kind;
      out.value = q.value;
      out.fields = new HeadMap<>(q.fields);
      out.items = q.items;
      out.emptyObject = q.emptyObject;
    } else if (!p.kind.equals(q.kind)) {
      throw new LanguageFailure("TypeCompatibilityViolation", "payload kind");
    } else if (q.kind.equals("scalar")) {
      Object pv = effective(p.value, out.core), qv = effective(q.value, out.core);
      if (pv != ABSENT && qv != ABSENT) {
        LanguageDiagnostics.require(
            Objects.equals(pv, qv),
            "FixedValueConflict",
            "scalar",
            LanguageDiagnostics.value(pv, p.type != null ? p.type : CORE.get(p.core), c, null),
            LanguageDiagnostics.value(qv, q.type != null ? q.type : CORE.get(q.core), c, null),
            null);
      }
      out.value = qv != ABSENT ? qv : pv;
    } else if (q.kind.equals("object")) {
      var keys = new TreeSet<>(p.fields.keySet());
      keys.addAll(q.fields.keySet());
      out.fields = new HeadMap<>();
      for (String k : keys) {
        out.fields.put(k, recipe(p.fields.get(k), q.fields.get(k)));
      }
      out.emptyObject = p.emptyObject || q.emptyObject;
    } else if (q.kind.equals("list") && q.items != null) {
      if (p.items == null) {
        out.items = q.items;
      } else {
        require(q.items.size() >= p.items.size(), "FixedValueConflict", "list prefix removed");
        var items = new ArrayList<Recipe>();
        for (int i = 0; i < q.items.size(); i++) {
          Recipe r = q.items.get(i);
          if (i >= p.items.size()) {
            items.add(r);
          } else if ("append-only".equals(out.policy)) {
            require(
                identity(exact(p.items.get(i), c)).equals(identity(exact(r, c))),
                "FixedValueConflict",
                "append-only prefix");
            items.add(r);
          } else {
            items.add(isHole(p.items.get(i)) ? r : recipe(p.items.get(i), r));
          }
        }
        out.items = List.copyOf(items);
      }
    }
    out.contracts =
        p.contracts != null || q.contracts != null ? recipe(p.contracts, q.contracts) : null;
    out.origin = q.origin != null ? q.origin : p.origin;
    var origins = new ArrayList<ExactNode>(p.origins);
    origins.addAll(q.origins);
    out.origins = recipe(origins.toArray()).parts;
    if (out.core != null) {
      String kind = kind(out.core);
      require(
          out.kind.equals("none") || out.kind.equals(kind),
          "TypeCompatibilityViolation",
          "core payload kind");
      if (out.kind.equals("none")) {
        out.kind = kind;
      }
    }
    if (out.itemType != null) {
      require(
          Set.of("none", "list").contains(out.kind),
          "TypeCompatibilityViolation",
          "itemType on non-list");
      if (out.kind.equals("none")) {
        out.kind = "list";
      }
    }
    if (out.keyType != null || out.valueType != null) {
      require(
          Set.of("none", "object").contains(out.kind),
          "TypeCompatibilityViolation",
          "dictionary metadata on non-object");
      if (out.kind.equals("none")) {
        out.kind = "object";
      }
    }
    return out.build();
  }

  static boolean subtype(String child, String parent, LanguageContext c) {
    if (child.equals(parent) || ancestry(child, c).contains(parent)) {
      return true;
    }
    if (CORE_NAME.containsKey(child) && CORE_NAME.containsKey(parent)) {
      return false;
    }
    HeadDraft ch = plan(child, c).copy(false), ph = plan(parent, c).copy(false);
    ch.labels.clear();
    ph.labels.clear();
    ch.specification = null;
    ph.specification = null;
    if (ph.core != null && !ph.core.equals(ch.core)) {
      return false;
    }
    String pair = "subtype:" + child + ":" + parent;
    if (!c.activeHeads.add(pair)) {
      return true;
    }
    try {
      return preserves(ch.build(), ph.build(), c, false);
    } finally {
      c.activeHeads.remove(pair);
    }
  }

  static boolean guarantees(Head h, LanguageSchema required, LanguageContext c) {
    Set<String> applicable = required.kinds();
    String known =
        h.core != null
            ? h.core
            : h.kind.equals("object") ? "Dictionary" : h.kind.equals("list") ? "List" : null;
    if (applicable != null) {
      if (known != null && !applicable.contains(known)) {
        return false;
      }
      if (known == null) {
        Set<String> own = h.constraints.kinds();
        if (own == null || !applicable.containsAll(own)) {
          return false;
        }
      }
    }
    LanguageSchema established = h.constraints.copy();
    if (concrete(h, c)) {
      established.required = true;
    }
    if (h.items != null) {
      established.counts.merge("minItems", BigInteger.valueOf(h.items.size()), BigInteger::max);
    }
    if (h.kind.equals("object")) {
      int count = 0;
      for (Recipe r : h.fields.values()) {
        Head ch = head(r, c);
        if (Boolean.TRUE.equals(ch.constraints.required) || concrete(ch, c)) {
          count++;
        }
      }
      established.counts.merge("minFields", BigInteger.valueOf(count), BigInteger::max);
    }
    if (established.implies(required, c)) {
      return true;
    }
    if (h.kind.equals("scalar") && h.value != ABSENT) {
      try {
        required.validate(h, true, "value", true, c);
        return true;
      } catch (LanguageFailure f) {
        if (f.category().equals("SchemaViolation")) {
          return false;
        }
        throw f;
      }
    }
    if (h.kind.equals("scalar") && !h.constraints.enums.isEmpty()) {
      if (Boolean.TRUE.equals(required.required) && !Boolean.TRUE.equals(established.required)
          || !LanguageSchema.enumImplies(h.constraints, required, c)) {
        return false;
      }
      Set<String> candidates = null;
      var values = new HashMap<String, LanguageSchema.Scalar>();
      for (var g : h.constraints.enums) {
        var keys = new HashSet<String>();
        for (String key : g) {
          LanguageSchema.Scalar v = h.constraints.enumValues.get(key);
          keys.add(v.primitive());
          values.put(v.primitive(), v);
        }
        if (candidates == null) {
          candidates = keys;
        } else {
          candidates.retainAll(keys);
        }
      }
      int viable = 0;
      for (String key : candidates) {
        LanguageSchema.Scalar v = values.get(key);
        if (h.core != null && !h.core.equals(v.core())) {
          continue;
        }
        HeadDraft own = h.copy();
        own.core = v.core();
        own.value = v.value();
        own.constraints.enums.clear();
        own.constraints.enumValues.clear();
        try {
          own.constraints.validate(own.build(), true, "value", true, c);
        } catch (LanguageFailure f) {
          if (f.category().equals("SchemaViolation")) {
            continue;
          }
          throw f;
        }
        viable++;
        LanguageSchema test = required.copy();
        test.enums.clear();
        test.enumValues.clear();
        try {
          test.validate(own.build(), true, "value", true, c);
        } catch (LanguageFailure f) {
          if (f.category().equals("SchemaViolation")) {
            return false;
          }
          throw f;
        }
      }
      return viable > 0;
    }
    return false;
  }

  static boolean preserves(Head candidate, Head parent, LanguageContext c, boolean checkType) {
    record Pair(Head a, Head b, boolean check) {}
    var work = new ArrayDeque<Pair>();
    work.push(new Pair(candidate, parent, checkType));
    var seen = new HashSet<String>();
    while (!work.isEmpty()) {
      Pair pair = work.pop();
      Head a = pair.a, b = pair.b;
      c.step();
      if (b.core != null && !b.core.equals(a.core)
          || !b.kind.equals("none") && !a.kind.equals(b.kind)
          || pair.check && b.type != null && (a.type == null || !subtype(a.type, b.type, c))) {
        return false;
      }
      if (!guarantees(a, b.constraints, c)) {
        return false;
      }
      for (String[] ts :
          new String[][] {
            {a.itemType, b.itemType}, {a.keyType, b.keyType}, {a.valueType, b.valueType}
          }) {
        if (ts[1] != null && (ts[0] == null || !subtype(ts[0], ts[1], c))) {
          return false;
        }
      }
      if (b.policy != null && !b.policy.equals(a.policy)
          || b.value != ABSENT && !Objects.equals(a.value, b.value)) {
        return false;
      }
      if (concrete(b, c)) {
        for (var e : b.labels.entrySet()) {
          if (!Objects.equals(a.labels.get(e.getKey()), e.getValue())) {
            return false;
          }
        }
        if (b.specification != null && !b.specification.equals(a.specification)) {
          return false;
        }
      }
      var pairs = new ArrayList<Recipe[]>();
      if (b.kind.equals("object")) {
        if (!a.fields.keySet().containsAll(b.fields.keySet())) {
          return false;
        }
        for (String k : b.fields.keySet()) {
          pairs.add(new Recipe[] {a.fields.get(k), b.fields.get(k)});
        }
      }
      if (b.items != null) {
        if (a.items == null || a.items.size() < b.items.size()) {
          return false;
        }
        for (int i = 0; i < b.items.size(); i++) {
          Recipe r = b.items.get(i);
          if ("append-only".equals(b.policy)) {
            if (!identity(exact(a.items.get(i), c)).equals(identity(exact(r, c)))) {
              return false;
            }
          } else if (!isHole(r)) {
            pairs.add(new Recipe[] {a.items.get(i), r});
          }
        }
      }
      if (b.contracts != null) {
        if (a.contracts == null) {
          return false;
        }
        pairs.add(new Recipe[] {a.contracts, b.contracts});
      }
      for (Recipe[] p : pairs) {
        if (seen.add(p[0].key() + ":" + p[1].key())) {
          work.push(new Pair(head(p[0], c), head(p[1], c), true));
        }
      }
    }
    return true;
  }

  static Recipe child(Head h, Object k) {
    Recipe part;
    String tid;
    if (k instanceof Integer i) {
      part = h.items != null && i < h.items.size() ? h.items.get(i) : null;
      tid = h.itemType;
    } else {
      part = h.fields.get(k);
      tid = h.valueType;
    }
    return part != null || tid != null
        ? recipeWith(tid == null ? List.of() : List.of(tid), part)
        : null;
  }

  static void checkKey(String key, String tid, LanguageContext c) {
    // An admitted object key is already Text. The exact builtin has no additional schema;
    // constructing and hashing a scalar for each key adds no validation. Public Language
    // operations retain their physical-budget path; derived key types still validate below.
    if (c.reuseWithinHostBudget && tid.equals(CORE.get("Text"))) {
      return;
    }
    String core = domain(tid, c);
    require(
        core != null && Set.of("Text", "Integer", "Double", "Boolean").contains(core),
        "TypeCompatibilityViolation",
        "keyType");
    Object value;
    String canonical;
    switch (core) {
      case "Text" -> {
        value = key;
        canonical = key;
      }
      case "Integer" -> {
        require(
            LanguageSyntax.INTEGER.matcher(key).matches(),
            "SchemaViolation",
            "noncanonical Integer key");
        value = new BigInteger(key);
        canonical = key;
      }
      case "Boolean" -> {
        require(key.equals("true") || key.equals("false"), "SchemaViolation", "Boolean key");
        value = key.equals("true");
        canonical = key;
      }
      default -> {
        value = decimal(key, c).doubleValue();
        canonical = LanguageCodec.number((Double) value);
      }
    }
    require(canonical.equals(key), "SchemaViolation", "noncanonical key");
    validate(recipeWith(List.of(tid), scalar(core, LanguageSchema.literal(value))), c, "value");
  }

  static Head validate(Recipe root, LanguageContext c, String goal) {
    return validate(root, c, goal, true);
  }

  static Head validate(Recipe root, LanguageContext c, String goal, boolean rootOccurrence) {
    var known = new LanguageContext.ValidationKey(root.key(), goal, rootOccurrence);
    if (c.validated(known)) {
      return LanguageDiagnostics.at(
          c,
          c.diagnosticPath,
          "validation",
          () -> {
            c.step();
            return head(root, c);
          });
    }
    record Task(Recipe recipe, boolean root, LanguageDiagnostics.Path path) {}
    LanguageDiagnostics.Path rootPath = c.diagnosticPath;
    var work = new ArrayDeque<Task>();
    work.push(new Task(root, rootOccurrence, rootPath));
    var seen = new HashSet<LanguageContext.ValidationKey>();
    while (!work.isEmpty()) {
      Task task = work.pop();
      LanguageDiagnostics.at(
          c,
          task.path,
          "validation",
          () -> {
            c.step();
            var key = new LanguageContext.ValidationKey(task.recipe.key(), goal, task.root);
            if (!seen.add(key) || c.validated(key)) {
              return null;
            }
            Head h = head(task.recipe, c);
            if (established(task.recipe, c)) {
              return null;
            }
            boolean present = task.root || concrete(h, c);
            h.constraints.validate(h, present, goal, task.root, c);
            if (goal.equals("value") && !present) {
              return null;
            }
            var fields = new ArrayList<>(h.fields.keySet());
            Collections.reverse(fields);
            for (String k : fields) {
              if ("Dictionary".equals(h.core) || h.keyType != null) {
                LanguageDiagnostics.child(
                    c,
                    k,
                    () -> {
                      checkKey(k, h.keyType == null ? CORE.get("Text") : h.keyType, c);
                      return null;
                    });
              }
              work.push(new Task(child(h, k), false, LanguageDiagnostics.pointer(task.path, k)));
            }
            if (h.contracts != null) {
              LanguageDiagnostics.child(
                  c,
                  "contracts",
                  () -> {
                    Head ch = head(h.contracts, c);
                    require(
                        Set.of("object", "none").contains(ch.kind),
                        "InvalidNodeShape",
                        "contracts map");
                    return null;
                  });
              work.push(
                  new Task(
                      h.contracts, false, LanguageDiagnostics.pointer(task.path, "contracts")));
            }
            if (h.items != null) {
              for (int i = h.items.size() - 1; i >= 0; i--) {
                if (!goal.equals("definition") || !isHole(h.items.get(i))) {
                  work.push(
                      new Task(
                          child(h, i),
                          true,
                          LanguageDiagnostics.pointer(
                              LanguageDiagnostics.pointer(task.path, "items"), i)));
                }
              }
            }
            return null;
          });
    }
    c.validation.addAll(seen);
    return LanguageDiagnostics.at(c, rootPath, "validation", () -> head(root, c));
  }

  static Head certify(ExactNode n, LanguageContext c, String goal) {
    require(Set.of("value", "definition").contains(goal), "InvalidOperation");
    validate(recipe(n), c, "definition");
    return goal.equals("value") ? validate(recipe(n), c, "value") : head(recipe(n), c);
  }

  private static boolean established(Recipe recipe, LanguageContext c) {
    if (c.establishedValues.isEmpty()) {
      return false;
    }
    Head value = null;
    for (ExactNode part : recipe.parts) {
      Head known = c.establishedValues.get(identity(part));
      if (known != null) {
        if (value != null) {
          return false;
        }
        value = known;
      } else if (!(part instanceof Value metadata)
          || metadata.fields().isEmpty()
          || !LABELS.containsAll(metadata.fields().keySet())) {
        return false;
      }
    }
    if (value == null) {
      return false;
    }
    for (String type : recipe.requirements) {
      if (value.type == null || !ancestry(value.type, c).contains(type)) {
        return false;
      }
    }
    // head() has already checked metadata conflicts. No new schema or field refinement is
    // discharged by this certificate; those still follow ordinary contextual validation.
    return true;
  }

  /**
   * A prior completed root validation entails its unchanged contextual child obligations. Reuse
   * only equal recipes with equal root-occurrence roles, not merely equal BlueIds. Changed
   * ancestors and new obligations still pass through the ordinary strict validator.
   */
  static void inheritUnchangedValidation(ExactNode next, ExactNode previous, LanguageContext c) {
    if (previous == null || c.durableClosures.isEmpty()) {
      return;
    }
    inheritChangedConcreteness(next, previous, c);
    Recipe oldRoot = recipe(previous), newRoot = recipe(next);
    record Pair(Recipe next, Recipe previous, boolean root) {}
    for (String goal : List.of("definition", "value")) {
      if (!c.validated(new LanguageContext.ValidationKey(oldRoot.key(), goal, true))) {
        continue;
      }
      var work = new ArrayDeque<Pair>();
      work.push(new Pair(newRoot, oldRoot, true));
      var seen = new HashSet<String>();
      while (!work.isEmpty()) {
        Pair pair = work.pop();
        // Equal contextual obligations are already entailed by the completed old root.
        // Checking external facts first performs a storage lookup for every unchanged sibling.
        if (pair.next.key().equals(pair.previous.key())) {
          var inherited = new LanguageContext.ValidationKey(pair.next.key(), goal, pair.root);
          c.validation.add(inherited);
          if (c.facts != null) {
            c.facts.restored(inherited);
          }
          continue;
        }
        // Stop at facts or scoped values before pairing different immutable entry heads.
        if (c.validated(new LanguageContext.ValidationKey(pair.next.key(), goal, pair.root))
            || established(pair.next, c)) {
          continue;
        }
        if (!seen.add(pair.next.key() + ":" + pair.previous.key() + ":" + pair.root)) {
          continue;
        }
        Head old = head(pair.previous, c), now = head(pair.next, c);
        if (goal.equals("value") && !pair.root && !concrete(old, c)) {
          continue;
        }
        for (String name : now.fields.keySet()) {
          Recipe before = child(old, name), after = child(now, name);
          if (before != null && after != null) {
            work.push(new Pair(after, before, false));
          }
        }
        if (now.contracts != null && old.contracts != null) {
          work.push(new Pair(now.contracts, old.contracts, false));
        }
        if (now.items != null && old.items != null) {
          for (int i = 0; i < Math.min(now.items.size(), old.items.size()); i++) {
            if (!goal.equals("definition") || !isHole(old.items.get(i))) {
              work.push(new Pair(child(now, i), child(old, i), true));
            }
          }
        }
      }
    }
  }

  /** A concrete changed ordinary child proves its containing object concrete. No sibling lookup. */
  private static boolean inheritChangedConcreteness(
      ExactNode next, ExactNode previous, LanguageContext c) {
    Boolean known = c.concreteFacts.get(identity(next));
    if (known != null) {
      return known;
    }
    if (identity(next).equals(identity(previous))) {
      return false;
    }
    if (!(next instanceof Value value)) {
      return false;
    }
    if (value.has("value") || value.has("items") || value.fields().isEmpty()) {
      rememberConcrete(c, identity(next), true);
      return true;
    }
    Value old = open(previous, c);
    for (String key : value.ordinary()) {
      ExactNode child = (ExactNode) value.get(key);
      ExactNode prior = (ExactNode) old.get(key);
      if (prior != null
          && !identity(child).equals(identity(prior))
          && inheritChangedConcreteness(child, prior, c)) {
        rememberConcrete(c, identity(next), true);
        return true;
      }
    }
    return false; // No inference; the ordinary algorithm may still establish concreteness.
  }

  static void strict(ExactNode exact, LanguageContext c, String goal) {
    if (c.cacheVerifiedClosures
        && (c.limits.isEmpty() || c.reuseWithinHostBudget)
        && c.verifiedClosure(identity(exact))) {
      LanguageDiagnostics.at(
          c,
          c.diagnosticPath,
          "dependency-verification",
          () -> {
            c.step();
            return null;
          });
      LanguageDiagnostics.at(c, c.diagnosticPath, "validation", () -> certify(exact, c, goal));
      return;
    }
    record Task(ExactNode node, LanguageDiagnostics.Path path) {}
    LanguageDiagnostics.Path rootPath = c.diagnosticPath;
    var todo = new ArrayDeque<Task>();
    todo.push(new Task(exact, rootPath));
    var seen = new HashSet<String>();
    var types = new TreeMap<String, LanguageDiagnostics.Path>();
    var specs = new TreeMap<String, LanguageDiagnostics.Path>();
    var keyTypes = new TreeMap<String, LanguageDiagnostics.Path>();
    while (!todo.isEmpty()) {
      var task = todo.pop();
      LanguageDiagnostics.at(
          c,
          task.path,
          "dependency-verification",
          () -> {
            ExactNode n = task.node;
            c.step();
            String id = identity(n);
            if (!seen.add(id)
                || c.establishedValues.containsKey(id)
                || c.cacheVerifiedClosures
                    && (c.limits.isEmpty() || c.reuseWithinHostBudget)
                    && c.verifiedClosure(id)) {
              return null;
            }
            Value body = open(n, c);
            if (task.path.equals(rootPath) && c.diagnosticRoot == exact) {
              c.diagnosticRoot = body;
            }
            var children = new ArrayList<Task>();
            for (var e : body.fields().entrySet()) {
              String k = e.getKey();
              Object v = e.getValue();
              LanguageDiagnostics.Path path = LanguageDiagnostics.pointer(task.path, k);
              if (TYPES.contains(k)) {
                require(v instanceof Ref, "InvalidNodeShape");
                types.putIfAbsent(((Ref) v).id(), path);
                if (k.equals("keyType")) {
                  keyTypes.putIfAbsent(((Ref) v).id(), path);
                }
                children.add(new Task((ExactNode) v, path));
              } else if (k.equals("specification")) {
                require(v instanceof Ref, "InvalidNodeShape");
                specs.putIfAbsent(((Ref) v).id(), path);
                children.add(new Task((ExactNode) v, path));
              } else if (k.equals("items")) {
                var items = exactItems(v);
                for (int i = 0; i < items.size(); i++) {
                  children.add(new Task(items.get(i), LanguageDiagnostics.pointer(path, i)));
                }
              } else if (!LITERALS.contains(k) && !k.equals("mergePolicy")) {
                children.add(new Task((ExactNode) v, path));
              }
            }
            for (int i = children.size() - 1; i >= 0; i--) {
              todo.push(children.get(i));
            }
            return null;
          });
    }
    types.forEach(
        (t, path) -> {
          if (!CORE_NAME.containsKey(t)) {
            LanguageDiagnostics.at(
                c, path, "type-definition", () -> certify(new Ref(t), c, "definition"));
          }
        });
    specs.forEach(
        (id, path) ->
            LanguageDiagnostics.at(
                c, path, "specification-content", () -> certify(new Ref(id), c, "definition")));
    keyTypes.forEach(
        (t, path) ->
            LanguageDiagnostics.at(
                c,
                path,
                "type-definition",
                () -> {
                  String core = domain(t, c);
                  require(
                      core != null && Set.of("Text", "Integer", "Double", "Boolean").contains(core),
                      "TypeCompatibilityViolation",
                      "keyType");
                  return null;
                }));
    LanguageDiagnostics.at(c, rootPath, "validation", () -> certify(exact, c, goal));
    if (c.cacheVerifiedClosures && (c.limits.isEmpty() || c.reuseWithinHostBudget)) {
      c.verifiedClosures.addAll(seen);
    }
  }

  static ExactNode mergeSchema(ExactNode a, ExactNode b, LanguageContext c) {
    if (a == null) {
      return b;
    }
    if (b == null || identity(a).equals(identity(b))) {
      return a;
    }
    LanguageSchema.narrowing(LanguageSchema.read(a, c), LanguageSchema.read(b, c), c);
    LanguageSchema.conjoin(LanguageSchema.read(a, c), LanguageSchema.read(b, c));
    var fields = new TreeMap<>(open(a, c).fields());
    fields.putAll(open(b, c).fields());
    return ExactNode.value(fields);
  }

  static ExactNode refine(ExactNode base, ExactNode overlay, LanguageContext c, boolean allowHole) {
    if (identity(base).equals(identity(overlay))) {
      return base;
    }
    if (allowHole && hole(base)) {
      return overlay;
    }
    Value b = open(base, c), o = open(overlay, c);
    require(
        b.kind().equals("none") || o.kind().equals("none") || b.kind().equals(o.kind()),
        "TypeCompatibilityViolation",
        "refinement payload kind");
    var m = new TreeMap<>(b.fields());
    Head bh = single(b, c);
    for (var e : o.fields().entrySet()) {
      String k = e.getKey();
      Object v = e.getValue();
      if (LABELS.contains(k) || k.equals("specification")) {
        if (m.containsKey(k) && !m.get(k).equals(v)) {
          require(!concrete(bh, c), "FixedValueConflict", k);
        }
        m.put(k, v);
      } else if (TYPES.contains(k)) {
        if (m.containsKey(k)) {
          String p = ((Ref) m.get(k)).id(), q = ((Ref) v).id();
          m.put(
              k,
              new Ref(
                  k.equals("type")
                      ? choose(p, q, o.has("value") ? o.get("value") : ABSENT, c)
                      : narrow(p, q, c)));
        } else {
          m.put(k, v);
        }
      } else if (k.equals("schema")) {
        m.put(k, mergeSchema((ExactNode) m.get(k), (ExactNode) v, c));
      } else if (k.equals("mergePolicy")) {
        require(
            !m.containsKey(k) || m.get(k).equals(v), "TypeCompatibilityViolation", "mergePolicy");
        m.put(k, v);
      } else if (k.equals("value")) {
        String core = m.get("type") instanceof Ref r ? domain(r.id(), c) : null;
        Object nv = effective(v, core);
        if (m.containsKey(k)) {
          LanguageDiagnostics.require(
              Objects.equals(effective(m.get(k), core), nv),
              "FixedValueConflict",
              "scalar",
              LanguageDiagnostics.value(
                  effective(m.get(k), core),
                  m.get("type") instanceof Ref r ? r.id() : null,
                  c,
                  null),
              LanguageDiagnostics.value(
                  nv, o.get("type") instanceof Ref r ? r.id() : null, c, null),
              null);
        }
        m.put(k, v);
      } else if (k.equals("items") && m.containsKey(k)) {
        List<ExactNode> p = exactItems(m.get(k)), q = exactItems(v);
        require(q.size() >= p.size(), "FixedValueConflict", "list prefix removed");
        var out = new ArrayList<ExactNode>();
        for (int i = 0; i < q.size(); i++) {
          if (i >= p.size()) {
            out.add(q.get(i));
          } else if ("append-only".equals(m.get("mergePolicy"))) {
            require(
                identity(p.get(i)).equals(identity(q.get(i))),
                "FixedValueConflict",
                "append-only prefix");
            out.add(q.get(i));
          } else {
            out.add(refine(p.get(i), q.get(i), c, true));
          }
        }
        m.put(k, List.copyOf(out));
      } else if (!m.containsKey(k)) {
        m.put(k, v);
      } else {
        m.put(
            k,
            LanguageDiagnostics.child(
                c, k, () -> refine((ExactNode) m.get(k), (ExactNode) v, c, false)));
      }
    }
    ExactNode result = normalize(m, c, "exact", false);
    require(
        b.kind().equals("none") || ((Value) result).kind().equals(b.kind()),
        "TypeCompatibilityViolation");
    validate(recipe(result), c, "definition");
    return c.remember(result);
  }

  static ExactNode exact(Recipe r, LanguageContext c) {
    require(!r.parts.isEmpty(), "InvalidOperation", "a declaration does not supply an exact value");
    ExactNode result = r.parts.getFirst();
    for (int i = 1; i < r.parts.size(); i++) {
      result = refine(result, r.parts.get(i), c, false);
    }
    return result;
  }

  static Object resolvedAt(Recipe r, List<String> p, LanguageContext c) {
    if (p.isEmpty()) {
      return head(r, c);
    }
    Head h = head(r, c);
    String k = p.getFirst();
    List<String> tail = p.subList(1, p.size());
    if (k.equals("blueId")) {
      return ABSENT;
    }
    if (k.equals("items")) {
      if (h.items == null) {
        return ABSENT;
      }
      if (p.size() == 1) {
        return h.items;
      }
      int i = index(p.get(1));
      return i >= h.items.size()
          ? ABSENT
          : LanguageDiagnostics.child(
              c,
              "items",
              () ->
                  LanguageDiagnostics.child(
                      c, i, () -> resolvedAt(child(h, i), p.subList(2, p.size()), c)));
    }
    if (LABELS.contains(k)) {
      return p.size() == 1 ? h.labels.getOrDefault(k, ABSENT) : ABSENT;
    }
    if (k.equals("specification")) {
      return h.specification == null
          ? ABSENT
          : p.size() == 1
              ? h.specification
              : LanguageDiagnostics.child(c, k, () -> resolvedAt(recipe(h.specification), tail, c));
    }
    if (k.equals("value")) {
      return p.size() == 1 ? h.value : ABSENT;
    }
    if (k.equals("schema")) {
      return p.size() == 1 ? h.constraints : ABSENT;
    }
    if (TYPES.contains(k)) {
      String t =
          switch (k) {
            case "type" -> h.type;
            case "itemType" -> h.itemType;
            case "keyType" -> h.keyType;
            default -> h.valueType;
          };
      return t != null && p.size() == 1 ? new Ref(t) : ABSENT;
    }
    if (k.equals("mergePolicy")) {
      return h.kind.equals("list") && p.size() == 1
          ? (h.policy == null ? "positional" : h.policy)
          : ABSENT;
    }
    if (k.equals("contracts")) {
      return h.contracts == null
          ? ABSENT
          : LanguageDiagnostics.child(c, k, () -> resolvedAt(h.contracts, tail, c));
    }
    if (!h.fields.containsKey(k)) {
      return ABSENT;
    }
    return LanguageDiagnostics.child(c, k, () -> resolvedAt(child(h, k), tail, c));
  }
}
