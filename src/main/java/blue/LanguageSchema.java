package blue;

import static blue.ExactNode.*;
import static blue.LanguageCore.*;
import static blue.LanguageFailure.require;

import java.math.*;
import java.util.*;

/** A05: finite schema vocabulary and exact rational predicates. */
final class LanguageSchema {
  static final Set<String> KEYS =
      Set.of(
          "required",
          "minItems",
          "maxItems",
          "uniqueItems",
          "minFields",
          "maxFields",
          "minimum",
          "maximum",
          "exclusiveMinimum",
          "exclusiveMaximum",
          "multipleOf",
          "minLength",
          "maxLength",
          "enum");
  static final List<String> MIN = List.of("minItems", "minFields", "minLength"),
      MAX = List.of("maxItems", "maxFields", "maxLength");

  record Bound(Rational value, boolean exclusive) {}

  record Scalar(String type, String core, Object value) {
    String key() {
      return LanguageCodec.write(Map.of("type", Map.of("blueId", type), "value", literal(value)));
    }

    String primitive() {
      return LanguageCodec.write(Map.of("core", core, "value", literal(value)));
    }
  }

  LanguageSchema() {
    counts = new TreeMap<>();
    lowerFacets = new ArrayList<>();
    upperFacets = new ArrayList<>();
    enums = new ArrayList<>();
    enumValues = new TreeMap<>();
  }

  private LanguageSchema(LanguageSchema source) {
    required = source.required;
    unique = source.unique;
    lower = source.lower;
    upper = source.upper;
    multiple = source.multiple;
    counts =
        source.counts.isEmpty()
            ? Map.of()
            : Collections.unmodifiableMap(new TreeMap<>(source.counts));
    lowerFacets = List.copyOf(source.lowerFacets);
    upperFacets = List.copyOf(source.upperFacets);
    enums = source.enums.stream().map(g -> Collections.unmodifiableSet(new TreeSet<>(g))).toList();
    enumValues =
        source.enumValues.isEmpty()
            ? Map.of()
            : Collections.unmodifiableMap(new TreeMap<>(source.enumValues));
    frozen = true;
  }

  Boolean required, unique;
  Map<String, BigInteger> counts;
  Bound lower, upper;
  List<Bound> lowerFacets, upperFacets;
  Rational multiple;
  List<Set<String>> enums;
  Map<String, Scalar> enumValues;

  // Immutable collection views at head publication; the draft never escapes.
  private boolean frozen;

  static final LanguageSchema EMPTY = new LanguageSchema(new LanguageSchema());

  LanguageSchema frozen() {
    if (frozen) return this;
    // Absence is distinct from explicit false/zero. Authored facets remain observable.
    if (required == null
        && unique == null
        && lower == null
        && upper == null
        && multiple == null
        && counts.isEmpty()
        && lowerFacets.isEmpty()
        && upperFacets.isEmpty()
        && enums.isEmpty()
        && enumValues.isEmpty()) return EMPTY;
    return new LanguageSchema(this);
  }

  static Object literal(Object v) {
    return v instanceof BigInteger b && b.abs().compareTo(LanguageCodec.SAFE) > 0
        ? b.toString()
        : v;
  }

  static LanguageSchema read(ExactNode exact, LanguageContext c) {
    var s = new LanguageSchema();
    if (exact == null) {
      return s;
    }
    Value n = open(exact, c);
    require(
        n.kind().equals("object") && KEYS.containsAll(n.fields().keySet()),
        "SchemaVocabularyError",
        "schema map");
    for (var e : n.fields().entrySet()) {
      String k = e.getKey();
      ExactNode v = (ExactNode) e.getValue();
      if (k.equals("enum")) {
        Value seq = open(v, c);
        require(
            seq.fields().keySet().equals(Set.of("items")),
            "SchemaVocabularyError",
            "enum sequence");
        var group = new TreeSet<String>();
        for (ExactNode item : exactItems(seq.get("items"))) {
          Scalar sc = describeScalar(item, c);
          String key = sc.key();
          group.add(key);
          s.enumValues.put(key, sc);
        }
        require(!group.isEmpty(), "SchemaViolation", "empty enum");
        s.enums.add(group);
        continue;
      }
      Scalar sc = describeScalar(v, c);
      Object value = sc.value;
      if (k.equals("required") || k.equals("uniqueItems")) {
        require(sc.core.equals("Boolean") && value instanceof Boolean, "SchemaVocabularyError", k);
        if (k.equals("required")) {
          s.required = (Boolean) value;
        } else {
          s.unique = (Boolean) value;
        }
      } else if (MIN.contains(k) || MAX.contains(k)) {
        require(
            sc.core.equals("Integer")
                && value instanceof BigInteger b
                && b.signum() >= 0
                && b.compareTo(LanguageCodec.SAFE) <= 0,
            "SchemaVocabularyError",
            k);
        s.counts.put(k, (BigInteger) value);
      } else {
        require(sc.core.equals("Integer") || sc.core.equals("Double"), "SchemaVocabularyError", k);
        Rational q = Rational.of(value);
        if (k.equals("multipleOf")) {
          require(q.numerator().signum() > 0, "SchemaVocabularyError");
          s.multiple = q;
        } else {
          Bound bound = new Bound(q, k.startsWith("exclusive"));
          if (k.equals("minimum") || k.equals("exclusiveMinimum")) {
            s.lowerFacets.add(bound);
            s.lower = lower(s.lower, bound);
          } else {
            s.upperFacets.add(bound);
            s.upper = upper(s.upper, bound);
          }
        }
      }
    }
    s.consistent();
    return s;
  }

  static Scalar describeScalar(ExactNode n, LanguageContext c) {
    String key = "operand:" + identity(n);
    require(c.activeHeads.add(key), "SchemaVocabularyError", "recursive schema operand");
    try {
      Value b = open(n, c);
      require(
          Set.of("scalar", "none").contains(b.kind()), "SchemaVocabularyError", "scalar operand");
      require(b.get("type") instanceof Ref, "SchemaVocabularyError", "scalar type required");
      String tid = ((Ref) b.get("type")).id(), core = LanguageSemantics.domain(tid, c);
      require(
          core != null && Set.of("Text", "Integer", "Double", "Boolean").contains(core),
          "SchemaVocabularyError",
          "scalar domain");
      var values = new ArrayList<Object>();
      if (b.has("value")) {
        values.add(LanguageSemantics.effective(b.get("value"), core));
      }
      for (String t : LanguageSemantics.ancestry(tid, c)) {
        if (CORE_NAME.containsKey(t)) {
          break;
        }
        Value p = open(new Ref(t), c);
        require(
            Set.of("scalar", "none").contains(p.kind()),
            "SchemaVocabularyError",
            "scalar ancestry");
        if (p.has("value")) {
          values.add(LanguageSemantics.effective(p.get("value"), core));
        }
      }
      require(!values.isEmpty(), "SchemaVocabularyError", "scalar payload required");
      require(
          values.stream().allMatch(v -> Objects.equals(v, values.getFirst())),
          "FixedValueConflict");
      LanguageSemantics.certify(n, c, "value");
      return new Scalar(tid, core, values.getFirst());
    } finally {
      c.activeHeads.remove(key);
    }
  }

  static Bound lower(Bound a, Bound b) {
    if (a == null) {
      return b;
    }
    if (b == null) {
      return a;
    }
    int cmp = a.value.compareTo(b.value);
    return cmp > 0 || cmp == 0 && a.exclusive ? a : b;
  }

  static Bound upper(Bound a, Bound b) {
    if (a == null) {
      return b;
    }
    if (b == null) {
      return a;
    }
    int cmp = a.value.compareTo(b.value);
    return cmp < 0 || cmp == 0 && a.exclusive ? a : b;
  }

  static boolean lowerImplies(Bound a, Bound b) {
    return b == null
        || a != null
            && (a.value.compareTo(b.value) > 0
                || a.value.equals(b.value) && (a.exclusive || !b.exclusive));
  }

  static boolean upperImplies(Bound a, Bound b) {
    return b == null
        || a != null
            && (a.value.compareTo(b.value) < 0
                || a.value.equals(b.value) && (a.exclusive || !b.exclusive));
  }

  void consistent() {
    for (int i = 0; i < 3; i++) {
      require(
          !counts.containsKey(MIN.get(i))
              || !counts.containsKey(MAX.get(i))
              || counts.get(MIN.get(i)).compareTo(counts.get(MAX.get(i))) <= 0,
          "SchemaViolation",
          "empty count interval");
    }
    if (lower != null && upper != null) {
      require(
          lower.value.compareTo(upper.value) < 0
              || lower.value.equals(upper.value) && !lower.exclusive && !upper.exclusive,
          "SchemaViolation",
          "empty interval");
    }
  }

  static boolean accepts(Scalar parent, Scalar child, LanguageContext c) {
    return parent.primitive().equals(child.primitive())
        && LanguageSemantics.subtype(child.type, parent.type, c);
  }

  static boolean enumImplies(LanguageSchema a, LanguageSchema b, LanguageContext c) {
    for (Set<String> pg : b.enums) {
      boolean covered = false;
      for (Set<String> cg : a.enums) {
        if (cg.stream()
            .allMatch(
                q ->
                    pg.stream()
                        .anyMatch(p -> accepts(b.enumValues.get(p), a.enumValues.get(q), c)))) {
          covered = true;
          break;
        }
      }
      if (!covered) {
        return false;
      }
    }
    return true;
  }

  static void narrowing(LanguageSchema p, LanguageSchema q, LanguageContext c) {
    if (Boolean.TRUE.equals(p.required) && q.required != null) {
      LanguageDiagnostics.require(
          q.required,
          "SchemaWeakening",
          "required",
          Map.of("value", true),
          Map.of("value", q.required),
          "required");
    }
    if (Boolean.TRUE.equals(p.unique) && q.unique != null) {
      LanguageDiagnostics.require(
          q.unique,
          "SchemaWeakening",
          "uniqueItems",
          Map.of("value", true),
          Map.of("value", q.unique),
          "uniqueItems");
    }
    for (int i = 0; i < 3; i++) {
      String lo = MIN.get(i), hi = MAX.get(i);
      if (p.counts.containsKey(lo) && q.counts.containsKey(lo)) {
        LanguageDiagnostics.require(
            q.counts.get(lo).compareTo(p.counts.get(lo)) >= 0,
            "SchemaWeakening",
            lo,
            Map.of("value", p.counts.get(lo)),
            Map.of("value", q.counts.get(lo)),
            lo);
      }
      if (p.counts.containsKey(hi) && q.counts.containsKey(hi)) {
        LanguageDiagnostics.require(
            q.counts.get(hi).compareTo(p.counts.get(hi)) <= 0,
            "SchemaWeakening",
            hi,
            Map.of("value", p.counts.get(hi)),
            Map.of("value", q.counts.get(hi)),
            hi);
      }
    }
    for (Bound b :
        q.lowerFacets.isEmpty()
            ? (q.lower == null ? List.<Bound>of() : List.of(q.lower))
            : q.lowerFacets) {
      LanguageDiagnostics.require(
          lowerImplies(b, p.lower),
          "SchemaWeakening",
          "lower bound",
          p.lower == null
              ? Map.of()
              : Map.of(
                  "value",
                  LanguageDiagnostics.literal(p.lower.value),
                  "exclusive",
                  p.lower.exclusive),
          Map.of("value", LanguageDiagnostics.literal(b.value), "exclusive", b.exclusive),
          b.exclusive ? "exclusiveMinimum" : "minimum");
    }
    for (Bound b :
        q.upperFacets.isEmpty()
            ? (q.upper == null ? List.<Bound>of() : List.of(q.upper))
            : q.upperFacets) {
      LanguageDiagnostics.require(
          upperImplies(b, p.upper),
          "SchemaWeakening",
          "upper bound",
          p.upper == null
              ? Map.of()
              : Map.of(
                  "value",
                  LanguageDiagnostics.literal(p.upper.value),
                  "exclusive",
                  p.upper.exclusive),
          Map.of("value", LanguageDiagnostics.literal(b.value), "exclusive", b.exclusive),
          b.exclusive ? "exclusiveMaximum" : "maximum");
    }
    if (q.multiple != null && p.multiple != null) {
      LanguageDiagnostics.require(
          q.multiple.divide(p.multiple).integral(),
          "SchemaWeakening",
          "multipleOf",
          Map.of("value", LanguageDiagnostics.literal(p.multiple)),
          Map.of("value", LanguageDiagnostics.literal(q.multiple)),
          "multipleOf");
    }
    if (!q.enums.isEmpty() && !p.enums.isEmpty()) {
      require(enumImplies(q, p, c), "SchemaWeakening", "enum");
    }
  }

  static LanguageSchema conjoin(LanguageSchema a, LanguageSchema b) {
    var s = new LanguageSchema();
    s.required =
        a.required == null && b.required == null
            ? null
            : Boolean.TRUE.equals(a.required) || Boolean.TRUE.equals(b.required);
    s.unique =
        a.unique == null && b.unique == null
            ? null
            : Boolean.TRUE.equals(a.unique) || Boolean.TRUE.equals(b.unique);
    s.counts.putAll(a.counts);
    b.counts.forEach(
        (k, v) -> s.counts.merge(k, v, MIN.contains(k) ? BigInteger::max : BigInteger::min));
    s.lower = lower(a.lower, b.lower);
    s.upper = upper(a.upper, b.upper);
    s.multiple =
        a.multiple != null && b.multiple != null
            ? a.multiple.lcm(b.multiple)
            : a.multiple != null ? a.multiple : b.multiple;
    s.enums.addAll(a.enums);
    for (var g : b.enums) {
      if (!s.enums.contains(g)) {
        s.enums.add(g);
      }
    }
    s.enumValues.putAll(a.enumValues);
    s.enumValues.putAll(b.enumValues);
    Set<String> common = null;
    for (var g : s.enums) {
      var payloads = new HashSet<String>();
      for (String k : g) {
        payloads.add(s.enumValues.get(k).primitive());
      }
      if (common == null) {
        common = payloads;
      } else {
        common.retainAll(payloads);
      }
    }
    require(
        common == null || !common.isEmpty(),
        "SchemaViolation",
        "enum payload intersection is empty");
    s.consistent();
    return s;
  }

  // conjoin never mutates either input. This singleton is never exposed or modified.
  private static final LanguageSchema COPY_IDENTITY = new LanguageSchema();

  LanguageSchema copy() {
    return conjoin(this, COPY_IDENTITY);
  }

  boolean implies(LanguageSchema p, LanguageContext c) {
    if (Boolean.TRUE.equals(p.required) && !Boolean.TRUE.equals(required)
        || Boolean.TRUE.equals(p.unique) && !Boolean.TRUE.equals(unique)) {
      return false;
    }
    for (var e : p.counts.entrySet()) {
      BigInteger own = counts.get(e.getKey());
      if (own == null) {
        if (MIN.contains(e.getKey()) && e.getValue().signum() == 0) {
          continue;
        }
        return false;
      }
      if (MIN.contains(e.getKey())
          ? own.compareTo(e.getValue()) < 0
          : own.compareTo(e.getValue()) > 0) {
        return false;
      }
    }
    return lowerImplies(lower, p.lower)
        && upperImplies(upper, p.upper)
        && (p.multiple == null || multiple != null && multiple.divide(p.multiple).integral())
        && enumImplies(this, p, c);
  }

  Set<String> kinds() {
    var groups = new ArrayList<Set<String>>();
    if (lower != null || upper != null || multiple != null) {
      groups.add(Set.of("Integer", "Double"));
    }
    if (counts.containsKey("minLength") || counts.containsKey("maxLength")) {
      groups.add(Set.of("Text"));
    }
    if (counts.containsKey("minItems") || counts.containsKey("maxItems") || unique != null) {
      groups.add(Set.of("List"));
    }
    if (counts.containsKey("minFields") || counts.containsKey("maxFields")) {
      groups.add(Set.of("Dictionary"));
    }
    for (var g : enums) {
      var kinds = new HashSet<String>();
      for (String k : g) {
        kinds.add(enumValues.get(k).core);
      }
      groups.add(kinds);
    }
    if (groups.isEmpty()) {
      return null;
    }
    var out = new HashSet<>(groups.getFirst());
    groups.forEach(out::retainAll);
    return out;
  }

  void checkCount(String lo, String hi, int n) {
    BigInteger count = BigInteger.valueOf(n);
    require(!counts.containsKey(lo) || count.compareTo(counts.get(lo)) >= 0, "SchemaViolation", lo);
    require(!counts.containsKey(hi) || count.compareTo(counts.get(hi)) <= 0, "SchemaViolation", hi);
  }

  void validate(
      LanguageModel.Head h, boolean present, String goal, boolean root, LanguageContext c) {
    try {
      validateInner(h, present, goal, root, c);
    } catch (LanguageFailure e) {
      String detail = e.getMessage();
      Object expected = null, actual = null;
      String keyword = null;
      if (e.category().equals("SchemaViolation")) {
        if (detail.equals("required")) {
          keyword = "required";
          expected = Map.of("present", true);
          actual = Map.of("present", false);
        } else if (Set.of("scalar payload required", "list payload required", "payload required")
            .contains(detail)) {
          var ex =
              LanguageDiagnostics.value(
                  ABSENT,
                  h.type != null ? h.type : CORE.get(h.core),
                  c,
                  detail.equals("list payload required") ? "List" : h.kind);
          ex.put("present", true);
          expected = ex;
          actual = LanguageDiagnostics.value(ABSENT, null, c, null);
        } else if (detail.equals("lower bound")
            || detail.equals("upper bound")
            || detail.equals("multipleOf")) {
          Rational bound =
              detail.equals("lower bound")
                  ? lower.value
                  : detail.equals("upper bound") ? upper.value : multiple;
          keyword =
              detail.equals("lower bound")
                  ? (lower.exclusive ? "exclusiveMinimum" : "minimum")
                  : detail.equals("upper bound")
                      ? (upper.exclusive ? "exclusiveMaximum" : "maximum")
                      : "multipleOf";
          expected = Map.of("value", LanguageDiagnostics.literal(bound));
          actual = LanguageDiagnostics.value(h.value, h.type, c, null);
        } else if (counts.containsKey(detail)) {
          keyword = detail;
          int count =
              detail.endsWith("Length")
                  ? ((String) h.value).codePointCount(0, ((String) h.value).length())
                  : detail.endsWith("Items")
                      ? h.items.size()
                      : (int)
                          h.fields.values().stream()
                              .filter(
                                  r -> LanguageSemantics.concrete(LanguageSemantics.head(r, c), c))
                              .count();
          expected = Map.of("value", counts.get(detail));
          actual = Map.of("value", count);
        } else if (detail.equals("uniqueItems")) {
          keyword = detail;
          expected = Map.of("value", true);
          actual = Map.of("value", false);
        }
      }
      if (expected != null) {
        e.data.putIfAbsent("expected", expected);
      }
      if (actual != null) {
        e.data.putIfAbsent("actual", actual);
      }
      if (keyword != null) {
        e.data.putIfAbsent("keyword", keyword);
      }
      throw e;
    }
  }

  void validateInner(
      LanguageModel.Head h, boolean present, String goal, boolean root, LanguageContext c) {
    consistent();
    Set<String> applicable = kinds();
    String known =
        h.core != null
            ? h.core
            : h.kind.equals("object") ? "Dictionary" : h.kind.equals("list") ? "List" : null;
    if (known == null && h.value != ABSENT) {
      known =
          h.value instanceof Boolean
              ? "Boolean"
              : h.value instanceof BigInteger
                  ? "Integer"
                  : h.value instanceof Double
                      ? "Double"
                      : h.value instanceof String ? "Text" : null;
    }
    if (applicable != null) {
      require(
          !applicable.isEmpty() && (known == null || applicable.contains(known)),
          "SchemaViolation",
          "wrong payload kind");
    }
    if (goal.equals("definition") && (applicable == null || !LanguageSemantics.concrete(h, c))) {
      return;
    }
    require(root || !Boolean.TRUE.equals(required) || present, "SchemaViolation", "required");
    if (!present) {
      return;
    }
    if (goal.equals("value")) {
      require(
          !h.kind.equals("scalar") || h.value != ABSENT,
          "SchemaViolation",
          "scalar payload required");
      require(
          !h.kind.equals("list") || h.items != null, "SchemaViolation", "list payload required");
    }
    if (applicable != null) {
      require(
          !h.kind.equals("none") && (!h.kind.equals("scalar") || h.value != ABSENT),
          "SchemaViolation",
          "payload required");
    }
    if (h.kind.equals("scalar") && h.value != ABSENT) {
      Object v = h.value;
      if ("Integer".equals(known) || "Double".equals(known)) {
        Rational q = Rational.of(v);
        if (lower != null) {
          require(
              q.compareTo(lower.value) > 0 || q.equals(lower.value) && !lower.exclusive,
              "SchemaViolation",
              "lower bound");
        }
        if (upper != null) {
          require(
              q.compareTo(upper.value) < 0 || q.equals(upper.value) && !upper.exclusive,
              "SchemaViolation",
              "upper bound");
        }
        if (multiple != null) {
          require(q.divide(multiple).integral(), "SchemaViolation", "multipleOf");
        }
      }
      if ("Text".equals(known)) {
        checkCount("minLength", "maxLength", ((String) v).codePointCount(0, ((String) v).length()));
      }
      final String core = known;
      for (var g : enums) {
        require(
            g.stream()
                .anyMatch(
                    k ->
                        accepts(
                            enumValues.get(k),
                            new Scalar(h.type != null ? h.type : CORE.get(core), core, v),
                            c)),
            "SchemaViolation",
            "enum");
      }
    }
    if (h.kind.equals("list") && h.items != null) {
      checkCount("minItems", "maxItems", h.items.size());
      if (Boolean.TRUE.equals(unique)) {
        var ids = new HashSet<String>();
        for (var r : h.items) {
          require(
              ids.add(identity(LanguageSemantics.exact(r, c))), "SchemaViolation", "uniqueItems");
        }
      }
    }
    // Counting concrete fields can resolve every child. It is only a schema predicate when
    // a field-count bound exists; ordinary child validation is performed by the caller.
    if (h.kind.equals("object")
        && (counts.containsKey("minFields") || counts.containsKey("maxFields"))) {
      int count = 0;
      for (var entry : h.fields.entrySet()) {
        if (LanguageDiagnostics.child(
            c,
            entry.getKey(),
            () -> LanguageSemantics.concrete(LanguageSemantics.head(entry.getValue(), c), c))) {
          count++;
        }
      }
      checkCount("minFields", "maxFields", count);
    }
  }

  Map<String, Object> describe() {
    var out = new TreeMap<String, Object>();
    if (required != null) {
      out.put("required", required);
    }
    if (unique != null) {
      out.put("uniqueItems", unique);
    }
    out.putAll(counts);
    if (lower != null) {
      var v = new TreeMap<>(lower.value.describe());
      v.put("exclusive", lower.exclusive);
      out.put("lower", v);
    }
    if (upper != null) {
      var v = new TreeMap<>(upper.value.describe());
      v.put("exclusive", upper.exclusive);
      out.put("upper", v);
    }
    if (multiple != null) {
      out.put("multipleOf", multiple.describe());
    }
    if (!enums.isEmpty()) {
      var groups = new ArrayList<>(enums);
      groups.sort(Comparator.comparing(g -> String.join("\0", new TreeSet<>(g))));
      out.put(
          "enumAll",
          groups.stream()
              .map(g -> new TreeSet<>(g).stream().map(LanguageCodec::json).toList())
              .toList());
    }
    return out;
  }
}
