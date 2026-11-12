package blue;

import static blue.LanguageCore.ABSENT;
import static blue.LanguageCore.CONTROLS;
import static blue.LanguageCore.CORE_NAME;
import static blue.LanguageCore.LITERALS;
import static blue.LanguageCore.TYPES;
import static blue.LanguageCore.at;
import static blue.LanguageCore.exactItems;
import static blue.LanguageCore.exactText;
import static blue.LanguageCore.export;
import static blue.LanguageCore.identity;
import static blue.LanguageCore.list;
import static blue.LanguageCore.listId;
import static blue.LanguageCore.map;
import static blue.LanguageCore.normalize;
import static blue.LanguageCore.open;
import static blue.LanguageCore.path;
import static blue.LanguageCore.plainId;
import static blue.LanguageCore.string;
import static blue.LanguageFailure.require;
import static blue.LanguageSemantics.certify;
import static blue.LanguageSemantics.child;
import static blue.LanguageSemantics.concrete;
import static blue.LanguageSemantics.exact;
import static blue.LanguageSemantics.head;
import static blue.LanguageSemantics.recipe;
import static blue.LanguageSemantics.requirement;
import static blue.LanguageSemantics.resolvedAt;
import static blue.LanguageSemantics.strict;

import blue.ExactNode.NumberToken;
import blue.ExactNode.Ref;
import blue.ExactNode.Value;
import blue.LanguageModel.Head;
import blue.LanguageModel.Recipe;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;

final class LanguageOperations {
  private LanguageOperations() {}

  static Object parse(String text, String format, LanguageContext c) {
    c.bound("text_bytes", text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    Object value = LanguageCodec.parse(text, format, c);
    checkInput(value, c);
    return value;
  }

  static void checkInput(Object value, LanguageContext c) {
    record Item(Object v, int depth) {}
    var todo = new ArrayDeque<Item>();
    todo.push(new Item(value, 1));
    long count = 0;
    while (!todo.isEmpty()) {
      Item item = todo.pop();
      c.bound("depth", item.depth);
      c.bound("values", ++count);
      if (item.v instanceof NumberToken t) {
        c.bound("numeric_chars", t.text().length());
      } else if (item.v instanceof Map<?, ?> m) {
        for (Object v : m.values()) {
          todo.push(new Item(v, item.depth + 1));
        }
      } else if (item.v instanceof List<?> a) {
        for (Object v : a) {
          todo.push(new Item(v, item.depth + 1));
        }
      }
    }
  }

  static Object source(Node node, LanguageContext c) {
    return source(node, c, 1, new long[] {0});
  }

  private static Object source(Node node, LanguageContext c, int depth, long[] count) {
    c.bound("depth", depth);
    c.bound("values", ++count[0]);
    return switch (node.kind()) {
      case TEXT -> node.text();
      case INTEGER -> {
        c.bound("numeric_chars", node.bigInteger().toString().length());
        yield node.bigInteger();
      }
      case DOUBLE -> new NumberToken(Double.toString(node.number()));
      case BOOLEAN -> node.bool();
      case NULL -> null;
      case MISSING ->
          throw new LanguageFailure("InvalidNodeShape", "Missing is not authored source");
      case LIST -> node.items().stream().map(child -> source(child, c, depth + 1, count)).toList();
      case OBJECT -> {
        var fields = new LinkedHashMap<String, Object>();
        node.fields().forEach((key, child) -> fields.put(key, source(child, c, depth + 1, count)));
        yield fields;
      }
    };
  }

  static ExactNode input(Map<String, Object> input, LanguageContext c) {
    return LanguageFailure.guard(() -> inputUnchecked(input, c));
  }

  private static ExactNode inputUnchecked(Map<String, Object> input, LanguageContext c) {
    String mode = string(input.getOrDefault("mode", "source")),
        format = string(input.getOrDefault("format", "json"));
    Object raw = parse(string(input.get("text")), format, c);
    return switch (mode) {
      case "source" -> LanguageSource.prepare(raw, c);
      case "exact", "direct" -> normalize(raw, c, mode, false);
      case "canonical" -> decode(raw, c, null, 0);
      default -> throw new LanguageFailure("InvalidOperation", "input role");
    };
  }

  static Object literal(Object v, String core) {
    if (v == ABSENT) {
      return Map.of("outcome", "Absent");
    }
    if ("Integer".equals(core) || v instanceof BigInteger) {
      return Map.of("kind", "Integer", "decimal", v.toString());
    }
    if ("Double".equals(core) || v instanceof Double) {
      return Map.of(
          "kind",
          "Double",
          "bits",
          String.format("%016x", Double.doubleToLongBits(((Number) v).doubleValue())));
    }
    if (v instanceof Boolean) {
      return Map.of("kind", "Boolean", "value", v);
    }
    if (v instanceof String) {
      return Map.of("kind", "Text", "value", v);
    }
    throw new LanguageFailure("InvalidOperation", "not a literal");
  }

  static Map<String, Object> observe(
      ExactNode exact, String path, String question, LanguageContext c) {
    List<String> parts = path(path);
    if (question.equals("exactId")) {
      Object n = at(exact, parts, c);
      if (n == ABSENT) {
        return Map.of("outcome", "Absent");
      }
      require(n instanceof ExactNode, "InvalidOperation");
      return Map.of("outcome", "Established", "blueId", identity((ExactNode) n));
    }
    Object selected = resolvedAt(recipe(exact), parts, c);
    if (selected == ABSENT) {
      return Map.of("outcome", "Absent");
    }
    Object result;
    if (selected instanceof Head h) {
      result =
          switch (question) {
            case "kind" -> h.kind;
            case "type" -> h.type == null ? ABSENT : h.type;
            case "value" -> h.value == ABSENT ? ABSENT : literal(h.value, h.core);
            case "constraints" -> h.constraints.describe();
            case "keys" -> {
              var keys = new ArrayList<String>();
              for (var e : h.fields.entrySet()) {
                if (concrete(head(e.getValue(), c), c)) {
                  keys.add(e.getKey());
                }
              }
              yield keys;
            }
            case "length" -> {
              require(h.items != null, "InvalidOperation", "not a list");
              yield h.items.size();
            }
            case "present" -> !parts.isEmpty() && !concrete(h, c) ? ABSENT : true;
            case "specification" ->
                h.specification == null ? ABSENT : Map.of("blueId", h.specification.id());
            case "name", "description" -> h.labels.getOrDefault(question, ABSENT);
            default -> throw new LanguageFailure("InvalidOperation", question);
          };
    } else {
      require(question.equals("value"), "InvalidOperation");
      result =
          selected instanceof LanguageSchema s
              ? s.describe()
              : selected instanceof Ref r ? Map.of("blueId", r.id()) : literal(selected, null);
    }
    return result == ABSENT
        ? Map.of("outcome", "Absent")
        : Map.of("outcome", "Established", "value", result);
  }

  static Map<String, Object> outcome(Supplier<Map<String, Object>> fn) {
    try {
      return fn.get();
    } catch (LanguageFailure f) {
      return f.outcome();
    } catch (StackOverflowError e) {
      return Map.of("outcome", "Incomplete", "category", "OperationLimitExceeded");
    }
  }

  static Map<String, Object> view(Recipe r, LanguageContext c, Set<String> active, int depth) {
    c.bound("depth", depth + 1);
    String key = r.key();
    if (active.contains(key)) {
      return Map.of(
          "recursive",
          true,
          "contributionIds",
          r.parts().stream().map(LanguageCore::identity).toList(),
          "requirements",
          r.requirements());
    }
    Head h = head(r, c);
    var out = new TreeMap<String, Object>();
    out.put("kind", h.kind);
    out.put("labels", h.labels);
    out.put("constraints", h.constraints.describe());
    if (h.specification != null) {
      out.put("specification", Map.of("blueId", h.specification.id()));
    }
    if (h.type != null) {
      out.put("effectiveType", h.type);
    }
    if (h.value != ABSENT) {
      out.put("value", literal(h.value, h.core));
    }
    var next = new HashSet<>(active);
    next.add(key);
    if (!h.fields.isEmpty()) {
      var fields = new TreeMap<String, Object>();
      for (String k : h.fields.keySet()) {
        fields.put(k, view(child(h, k), c, next, depth + 1));
      }
      out.put("fields", fields);
    }
    if (h.items != null) {
      var items = new ArrayList<Object>();
      for (int i = 0; i < h.items.size(); i++) {
        items.add(view(child(h, i), c, next, depth + 1));
      }
      out.put("items", items);
    }
    if (h.contracts != null) {
      out.put("contracts", view(h.contracts, c, next, depth + 1));
    }
    return out;
  }

  static boolean same(ExactNode a, ExactNode b, LanguageContext c) {
    return same(recipe(a), recipe(b), c);
  }

  static boolean same(Recipe a, Recipe b, LanguageContext c) {
    record Pair(Recipe a, Recipe b) {}
    var todo = new ArrayDeque<Pair>();
    todo.add(new Pair(a, b));
    var seen = new HashSet<String>();
    while (!todo.isEmpty()) {
      var pair = todo.pop();
      c.step();
      if (pair.a.key().equals(pair.b.key()) || !seen.add(pair.a.key() + ":" + pair.b.key())) {
        continue;
      }
      Head p = head(pair.a, c), q = head(pair.b, c);
      if (!Objects.equals(p.type, q.type)
          || !Objects.equals(p.core, q.core)
          || !p.kind.equals(q.kind)
          || !p.labels.equals(q.labels)
          || !Objects.equals(p.specification, q.specification)
          || !Objects.equals(p.policy, q.policy)
          || !Objects.equals(p.itemType, q.itemType)
          || !Objects.equals(p.keyType, q.keyType)
          || !Objects.equals(p.valueType, q.valueType)
          || !Objects.equals(p.value, q.value)
          || !p.constraints.describe().equals(q.constraints.describe())
          || concrete(p, c) != concrete(q, c)
          || !p.fields.keySet().equals(q.fields.keySet())
          || (p.contracts == null) != (q.contracts == null)
          || (p.items == null) != (q.items == null)) {
        return false;
      }
      if (p.items != null) {
        if (p.items.size() != q.items.size()) {
          return false;
        }
        for (int i = 0; i < p.items.size(); i++) {
          todo.add(new Pair(child(p, i), child(q, i)));
        }
      }
      for (String k : p.fields.keySet()) {
        todo.add(new Pair(child(p, k), child(q, k)));
      }
      if (p.contracts != null) {
        todo.add(new Pair(p.contracts, q.contracts));
      }
    }
    return true;
  }

  record Omitted(ExactNode exact, List<String> removed) {}

  static Omitted omit(ExactNode n, LanguageContext c) {
    if (!(n instanceof Value v) || !v.has("type")) {
      return new Omitted(n, List.of());
    }
    Head inherited = requirement(((Ref) v.get("type")).id(), c);
    var fields = new TreeMap<>(v.fields());
    var removed = new ArrayList<String>();
    for (String k : v.ordinary()) {
      Recipe contribution = inherited.fields.get(k);
      if (contribution != null
          && !contribution.parts().isEmpty()
          && identity((ExactNode) v.get(k)).equals(identity(exact(contribution, c)))) {
        fields.remove(k);
        removed.add("/" + k.replace("~", "~0").replace("/", "~1"));
      }
    }
    ExactNode result = ExactNode.value(fields);
    if (!removed.isEmpty()) {
      require(
          same(n, result, c), "CanonicalizationError", "duplicate removal changed effective view");
      c.remember(result);
    }
    return new Omitted(result, List.copyOf(removed));
  }

  static ExactNode payload(String id, LanguageContext c) {
    plainId(id);
    Value body = open(new Ref(id), c);
    require(
        body.fields().keySet().equals(Set.of("items")),
        "InvalidCanonicalList",
        "prefix must identify a payload-only list");
    return body;
  }

  static Object canonical(ExactNode n, LanguageContext c, Supplier<Recipe> lazy, int depth) {
    c.step();
    c.bound("depth", depth + 1);
    if (n instanceof Ref r) {
      return Map.of("blueId", r.id());
    }
    Value v = (Value) n;
    Object raw = export(n);
    Map<String, Object> source = raw instanceof List ? Map.of("items", raw) : map(raw);
    var out = new TreeMap<String, Object>();
    for (var e : v.fields().entrySet()) {
      String k = e.getKey();
      Object val = e.getValue();
      if (LITERALS.contains(k) || TYPES.contains(k) || k.equals("mergePolicy")) {
        out.put(k, TYPES.contains(k) ? export((ExactNode) val) : val);
        continue;
      }
      if (k.equals("items")) {
        Head base = LanguageSource.inherited(source, lazy, c);
        List<ExactNode> bs =
            base.kind.equals("list") && base.items != null
                ? base.items.stream().map(r -> exact(r, c)).toList()
                : List.of();
        List<ExactNode> xs = exactItems(val);
        boolean shared =
            "append-only".equals(base.policy) && !bs.isEmpty() && xs.size() >= bs.size();
        for (int i = 0; shared && i < bs.size(); i++) {
          shared = identity(bs.get(i)).equals(identity(xs.get(i)));
        }
        var output = new ArrayList<Object>();
        int start = 0;
        if (shared) {
          ExactNode prefix = c.remember(ExactNode.list(bs));
          start = bs.size();
          output.add(Map.of("$previous", Map.of("blueId", identity(prefix))));
        }
        for (int i = start; i < xs.size(); i++) {
          ExactNode item = xs.get(i);
          if (item instanceof Value body
              && !Collections.disjoint(body.fields().keySet(), CONTROLS)) {
            c.remember(item);
            output.add(Map.of("blueId", identity(item)));
          } else {
            output.add(
                canonical(item, c, LanguageSource.childContext(source, lazy, i, c), depth + 1));
          }
        }
        out.put(k, output);
      } else {
        out.put(
            k,
            canonical(
                (ExactNode) val, c, LanguageSource.childContext(source, lazy, k, c), depth + 1));
      }
    }
    return out.keySet().equals(Set.of("items")) ? out.get("items") : out;
  }

  static ExactNode decode(Object raw, LanguageContext c, Supplier<Recipe> lazy, int depth) {
    c.step();
    c.bound("depth", depth + 1);
    if (raw instanceof ExactNode n) {
      return n;
    }
    if (raw instanceof Map<?, ?> m && m.containsKey("blueId")) {
      return normalize(raw, c, "exact", false);
    }
    if (raw instanceof List) {
      raw = Map.of("items", raw);
    }
    if (!(raw instanceof Map)) {
      return normalize(raw, c, "exact", false);
    }
    var m = new TreeMap<>(map(raw));
    for (String k : TYPES) {
      if (m.containsKey(k)) {
        require(!(m.get(k) instanceof String), "UnknownTypeAlias");
        ExactNode t = decode(m.get(k), c, null, depth + 1);
        c.remember(t);
        m.put(k, new Ref(identity(t)));
        open((ExactNode) m.get(k), c);
      }
    }
    for (String k : new TreeSet<>(m.keySet())) {
      if (TYPES.contains(k) || LITERALS.contains(k) || k.equals("mergePolicy")) {
        continue;
      }
      Object v = m.get(k);
      if (k.equals("items")) {
        List<Object> xs = list(v);
        var result = new ArrayList<ExactNode>();
        int start = 0;
        if (!xs.isEmpty()
            && xs.getFirst() instanceof Map<?, ?> marker
            && marker.containsKey("$previous")) {
          require(
              marker.size() == 1
                  && marker.get("$previous") instanceof Map<?, ?> p
                  && p.keySet().equals(Set.of("blueId")),
              "InvalidCanonicalList");
          Head base = LanguageSource.inherited(m, lazy, c);
          require("append-only".equals(base.policy) && base.items != null, "InvalidCanonicalList");
          String id = LanguageSource.anchor(map(marker.get("$previous")).get("blueId"));
          var bs = base.items.stream().map(r -> exact(r, c)).toList();
          result.addAll(exactItems(((Value) payload(id, c)).get("items")));
          require(listId(bs, null).equals(id), "InvalidCanonicalList", "anchor mismatch");
          start = 1;
        }
        for (int i = start; i < xs.size(); i++) {
          Object item = xs.get(i);
          require(
              !(item instanceof Map<?, ?> p) || Collections.disjoint(p.keySet(), CONTROLS),
              "InvalidCanonicalList");
          result.add(
              decode(item, c, LanguageSource.childContext(m, lazy, result.size(), c), depth + 1));
        }
        c.bound("values", result.size());
        m.put(k, result);
      } else {
        m.put(k, decode(v, c, LanguageSource.childContext(m, lazy, k, c), depth + 1));
      }
    }
    return normalize(m, c, "exact", false);
  }

  static String canonicalText(ExactNode n, LanguageContext c) {
    String text = LanguageCodec.wire(canonical(n, c, null, 0));
    require(
        identity(decode(LanguageCodec.parse(text, "json"), c, null, 0)).equals(identity(n)),
        "InternalError",
        "canonical wire changed identity");
    return text;
  }

  static Map<String, Object> records(ExactNode root, LanguageContext c) {
    return records(root, c, Set.of());
  }

  /** Omits closures whose verified records are already supplied by the receiving environment. */
  static Map<String, Object> records(ExactNode root, LanguageContext c, Set<String> supplied) {
    return records(root, c, supplied::contains);
  }

  static Map<String, Object> records(
      ExactNode root, LanguageContext c, java.util.function.Predicate<String> supplied) {
    return records(List.of(root), c, supplied);
  }

  /** Exports overlapping closures in one walk, retaining each root's list-prefix witnesses. */
  static Map<String, Object> records(
      Collection<ExactNode> roots,
      LanguageContext c,
      java.util.function.Predicate<String> supplied) {
    var records = new TreeMap<String, Object>();
    var todo = new ArrayDeque<ExactNode>();
    todo.addAll(roots);
    var seen = new HashSet<String>();
    while (!todo.isEmpty()) {
      ExactNode x = todo.pop();
      String id = identity(x);
      if (!seen.add(id) || supplied.test(id)) {
        continue;
      }
      Value body = open(x, c);
      if (!CORE_NAME.containsKey(id)) {
        if (id.contains("#")) {
          require(c.proofs.containsKey(id), "InvalidCyclicProof");
          records.put(
              id, Map.of("proof", LanguageCodec.wire(c.proofs.get(id)), "body", exactText(body)));
        } else {
          records.put(id, Map.of("text", recordText(body, c), "mode", "exact"));
        }
      }
      for (var e : body.fields().entrySet()) {
        if (e.getKey().equals("items")) {
          var a = exactItems(e.getValue());
          ExactNode p = c.remember(ExactNode.list(a));
          if (!supplied.test(identity(p))) {
            records.put(identity(p), Map.of("text", recordText(p, c), "mode", "exact"));
          }
          todo.addAll(a);
        } else if (!LITERALS.contains(e.getKey()) && !e.getKey().equals("mergePolicy")) {
          todo.add((ExactNode) e.getValue());
        }
      }
    }
    // Native records use exact syntax and enumerate every referenced child above. The
    // canonical rendering's $previous witnesses are unnecessary for this representation.
    // Public Language exports retain their canonical-rendering closure contract.
    if (c.recordTexts != null && !Boolean.getBoolean("blue.canonical.prefixWitnesses")) {
      return records;
    }
    for (ExactNode n : roots) {
      if (supplied.test(identity(n))) continue;
      Object compact = canonical(n, c, null, 0);
      var pending = new ArrayList<Object>();
      pending.add(compact);
      while (!pending.isEmpty()) {
        Object x = pending.removeLast();
        if (x instanceof List<?> a) {
          pending.addAll(a);
        } else if (x instanceof Map<?, ?> m) {
          if (m.keySet().equals(Set.of("$previous"))) {
            String id = string(map(m.get("$previous")).get("blueId"));
            if (!supplied.test(id)) {
              Value body = (Value) payload(id, c);
              records.put(id, Map.of("text", recordText(body, c), "mode", "exact"));
            }
          } else {
            pending.addAll(m.values());
          }
        }
      }
    }

    return records;
  }

  private static String recordText(ExactNode value, LanguageContext context) {
    var cache = context.recordTexts;
    if (cache == null
        || !(value instanceof Value body)
        || Boolean.getBoolean("blue.canonical.recordTextDisabled")) return exactText(value);
    if (!Boolean.getBoolean("blue.canonical.inlineRecords")) {
      // A record owns one node. Descendants have their own records in the same closure;
      // referencing them avoids serializing the same subtree once for each ancestor.
      // Contracts are a literal inspection boundary: preserve their source presentation.
      var fields = new TreeMap<String, Object>();
      for (var field : body.fields().entrySet()) {
        String key = field.getKey();
        Object child = field.getValue();
        fields.put(
            key,
            LITERALS.contains(key) || key.equals("mergePolicy") || key.equals("contracts")
                ? child
                : key.equals("items")
                    ? exactItems(child).stream().map(n -> (ExactNode) new Ref(identity(n))).toList()
                    : new Ref(identity((ExactNode) child)));
      }
      body = ExactNode.value(fields);
    }
    String known = cache.get(body);
    if (known != null) return known;
    String text = exactText(body);
    if (text.length() <= 16_384) {
      long weight = ContractCache.weight(body, 64 * 1024);
      if (weight != Long.MAX_VALUE) cache.put(body, text, weight + text.length() * 2L + 128);
    }
    return text;
  }

  static LanguageAdmission admit(ExactNode n, LanguageContext c, String goal, String mode) {
    return LanguageFailure.guard(() -> admitUnchecked(n, c, goal, mode));
  }

  private static LanguageAdmission admitUnchecked(
      ExactNode n, LanguageContext c, String goal, String mode) {
    c.diagnosticRoot = n;
    c.diagnosticGoal = goal;
    String before = identity(n);
    strict(n, c, goal);
    Omitted omitted = mode.equals("source") ? omit(n, c) : new Omitted(n, List.of());
    n = omitted.exact;
    if (!omitted.removed.isEmpty()) {
      strict(n, c, goal);
    }
    // Canonical export is a separate requested operation. Its round-trip invariant is
    // covered by codec tests; admission must not encode and decode an unused rendering.
    return new LanguageAdmission(n, before, omitted.removed);
  }

  static List<Object> requirements(ExactNode n, LanguageContext c) {
    var result = new ArrayList<Object>();
    var seen = new HashSet<String>();
    while (n != null) {
      String id = identity(n);
      require(seen.add(id), "TypeCycle", id);
      Value body = open(n, c);
      if (body.get("specification") instanceof Ref r) {
        result.add(Map.of("definedOn", id, "specification", r.id()));
      }
      n = body.get("type") instanceof Ref r && !CORE_NAME.containsKey(r.id()) ? r : null;
    }
    return result;
  }

  static Object sourceForExact(ExactNode n, LanguageContext c, Supplier<Recipe> lazy) {
    if (n instanceof Ref) {
      return export(n);
    }
    Value v = (Value) n;
    Object x = export(n);
    var raw = new TreeMap<>(x instanceof List ? Map.of("items", x) : map(x));
    for (String k : new TreeSet<>(raw.keySet())) {
      if (!LITERALS.contains(k)
          && !TYPES.contains(k)
          && !Set.of("mergePolicy", "items").contains(k)) {
        raw.put(
            k,
            sourceForExact((ExactNode) v.get(k), c, LanguageSource.childContext(raw, lazy, k, c)));
      }
    }
    if (v.has("items")) {
      Head base = LanguageSource.inherited(raw, lazy, c);
      List<ExactNode> xs = exactItems(v.get("items")),
          bs = base.items == null ? List.of() : base.items.stream().map(r -> exact(r, c)).toList();
      var out = new ArrayList<Object>();
      int start = 0;
      if ("append-only".equals(base.policy)) {
        require(xs.size() >= bs.size(), "FixedValueConflict");
        for (int i = 0; i < bs.size(); i++) {
          require(identity(xs.get(i)).equals(identity(bs.get(i))), "FixedValueConflict");
        }
        out.add(
            bs.isEmpty()
                ? Map.of("$previousItems", false)
                : Map.of("$previous", Map.of("blueId", listId(bs, null))));
        start = bs.size();
      }
      for (int i = start; i < xs.size(); i++) {
        out.add(sourceForExact(xs.get(i), c, LanguageSource.childContext(raw, lazy, i, c)));
      }
      raw.put("items", out);
    }
    return raw.keySet().equals(Set.of("items")) ? raw.get("items") : raw;
  }

  static List<Object> reductions(Object raw) {
    var out = new ArrayList<Object>();
    if (raw instanceof Map<?, ?>) {
      var m = map(raw);
      if (m.keySet().equals(Set.of("blueId"))) {
        return out;
      }
      for (String k : new TreeSet<>(m.keySet())) {
        if (!CONTROLS.contains(k)) {
          var smaller = new TreeMap<>(m);
          smaller.remove(k);
          out.add(smaller);
        }
      }
      if (m.keySet().equals(Set.of("value"))) {
        out.add(m.get("value"));
      }
      if (m.keySet().equals(Set.of("items"))) {
        out.add(m.get("items"));
      }
      for (String k : new TreeSet<>(m.keySet())) {
        if (!CONTROLS.contains(k) && !TYPES.contains(k) && !k.equals("blueId")) {
          for (Object v : reductions(m.get(k))) {
            var smaller = new TreeMap<>(m);
            smaller.put(k, v);
            out.add(smaller);
          }
        }
      }
    } else if (raw instanceof List<?> a) {
      for (int i = 0; i < a.size(); i++) {
        for (Object v : reductions(a.get(i))) {
          var smaller = new ArrayList<Object>(a);
          smaller.set(i, v);
          out.add(smaller);
        }
      }
    }
    return out;
  }

  static String minimize(ExactNode exact, LanguageContext c, String goal) {
    certify(exact, c, goal);
    c.remember(exact);
    Object raw = sourceForExact(exact, c, null);
    try {
      if (!same(exact, LanguageSource.prepare(raw, c), c)) {
        raw = Map.of("blueId", identity(exact));
      }
    } catch (LanguageFailure f) {
      if (Set.of("ProviderUnavailable", "OperationLimitExceeded", "ProviderBlueIdMismatch")
          .contains(f.category())) {
        throw f;
      }
      raw = Map.of("blueId", identity(exact));
    }
    while (true) {
      int before = LanguageCodec.wire(raw).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
      Object accepted = null;
      for (Object smaller : reductions(raw)) {
        if (LanguageCodec.wire(smaller).getBytes(java.nio.charset.StandardCharsets.UTF_8).length
            >= before) {
          continue;
        }
        try {
          ExactNode n = LanguageSource.prepare(smaller, c);
          certify(n, c, goal);
          if (same(exact, n, c)) {
            accepted = smaller;
            break;
          }
        } catch (LanguageFailure f) {
          if (Set.of("ProviderUnavailable", "OperationLimitExceeded", "ProviderBlueIdMismatch")
              .contains(f.category())) {
            throw f;
          }
        }
      }
      if (accepted == null) {
        break;
      }
      raw = accepted;
    }
    require(same(exact, LanguageSource.prepare(raw, c), c), "InternalError");
    return LanguageCodec.wire(raw);
  }
}
