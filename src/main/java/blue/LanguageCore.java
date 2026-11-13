package blue;

import static blue.ExactNode.scalar;
import static blue.LanguageFailure.require;

import blue.ExactNode.Local;
import blue.ExactNode.NumberToken;
import blue.ExactNode.Ref;
import blue.ExactNode.Value;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.IntFunction;
import java.util.function.UnaryOperator;

/**
 * Exact identities, verified graph access and exact document transformations (Language A01–A04,
 * A10).
 */
final class LanguageCore {
  private LanguageCore() {}

  static final Map<String, String> CORE =
      Map.of(
          "Text",
          "GX7CFUmSDrE2MzptunLCCdZwnuwwrenRQqEnHL4x3uoC",
          "Integer",
          "E2LM6qgzWG9ttagq2xTmiZkgYEAgkYedFCmU9v7NnVEq",
          "Double",
          "9eWaHYz2vKrFofdHTHAizNNu8xP6QE3WQ5y7DGrGZvyJ",
          "Boolean",
          "AwvXD961fmnmqcSQhjMA7r15HpVh39cefb6ZTyUz2Fm2",
          "List",
          "85ip88snCGrgUNdi1rUFqqAxcxwVGKV2g4LjsKoyKmXK",
          "Dictionary",
          "5WQ4tVb4gUUdZa7EfaiUa2XKQwgAurvfYY3ALPauxcAF");
  static final Map<String, String> CORE_NAME = new HashMap<>();

  static {
    CORE.forEach((k, v) -> CORE_NAME.put(v, k));
  }

  static final Set<String> TYPES = Set.of("type", "itemType", "keyType", "valueType");
  static final Set<String> LABELS = Set.of("name", "description");
  static final Set<String> LITERALS = Set.of("name", "description", "value");
  static final Set<String> RESERVED =
      Set.of(
          "name",
          "description",
          "specification",
          "type",
          "itemType",
          "keyType",
          "valueType",
          "schema",
          "mergePolicy",
          "contracts",
          "value",
          "items",
          "blueId",
          "blue",
          "properties",
          "constraints");
  static final Set<String> CONTROLS = Set.of("$pos", "$replace", "$previous", "$previousItems");
  static final Value HOLE = ExactNode.value(Map.of("$empty", scalar("Boolean", true)));
  static final String HOLE_ID = identity(HOLE);
  static final Object ABSENT = new Object();

  static boolean hole(ExactNode x) {
    return identity(x).equals(HOLE_ID);
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> map(Object x) {
    require(x instanceof Map, "InvalidNodeShape", "object expected");
    return (Map<String, Object>) x;
  }

  @SuppressWarnings("unchecked")
  static List<Object> list(Object x) {
    require(x instanceof List, "InvalidNodeShape", "list expected");
    return (List<Object>) x;
  }

  /** Lists in exact value fields are checked and copied by the ExactNode.Value constructor. */
  @SuppressWarnings("unchecked")
  static List<ExactNode> exactItems(Object value) {
    if (!(value instanceof List<?>)) {
      throw new IllegalArgumentException("Expected exact items");
    }
    return (List<ExactNode>) value;
  }

  static String string(Object x) {
    require(x instanceof String, "InvalidSyntax", "string expected");
    return (String) x;
  }

  static String plainId(Object raw) {
    require(raw instanceof String, "InvalidBlueId");
    String value = (String) raw;
    require(BlueIdSyntax.digest(value), "InvalidBlueId");
    return value;
  }

  static String finalId(Object raw) {
    require(raw instanceof String, "InvalidBlueId");
    String s = (String) raw;
    int hash = s.indexOf('#');
    if (hash < 0) {
      return plainId(s);
    }
    plainId(s.substring(0, hash));
    require(BlueIdSyntax.member(s, hash + 1), "InvalidBlueId");
    return s;
  }

  static String hash(Object helper) {
    return hashBytes(LanguageCodec.write(helper).getBytes(StandardCharsets.UTF_8));
  }

  static String hashBytes(byte[] canonical) {
    ContractMetrics.add("language.identity.hashedBytes", canonical.length);
    ContractMetrics.count("language.identity.hashCalls");
    try {
      return BlueIdSyntax.encode(MessageDigest.getInstance("SHA-256").digest(canonical));
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  static String appendId(String prefix, String element) {
    var out = new StringBuilder(160);
    out.append("{\"$listCons\":{\"elem\":{\"blueId\":");
    LanguageCodec.append(element, out);
    out.append("},\"prev\":{\"blueId\":");
    LanguageCodec.append(prefix, out);
    out.append("}}}");
    return hashBytes(out.toString().getBytes(StandardCharsets.UTF_8));
  }

  static String listId(List<ExactNode> items, IntFunction<String> token) {
    String id = hash(Map.of("$list", "empty"));
    for (ExactNode item : items) {
      id = appendId(id, identity(item, token));
    }
    return id;
  }

  static String identity(ExactNode n) {
    return identity(n, null);
  }

  static String identity(ExactNode n, IntFunction<String> token) {
    if (n instanceof Ref r) {
      return r.id();
    }
    if (n instanceof Local l) {
      require(token != null, "InvalidBlueId", "local edge outside cyclic operation");
      return token.apply(l.index());
    }
    Value v = (Value) n;
    if (token == null && v.identity != null) {
      return v.identity;
    }
    String id;
    if (v.fields().keySet().equals(Set.of("items"))) {
      id = listId(exactItems(v.get("items")), token);
    } else {
      byte[] canonical = helperBytes(v, token);
      id = hashBytes(canonical);
      if (token == null) {
        v.identityBytes = canonical.length;
      }
    }
    if (token == null) {
      v.identity = id;
    }
    return id;
  }

  /** Exact fields already have canonical key order; no temporary helper maps are needed. */
  private static byte[] helperBytes(Value node, IntFunction<String> token) {
    var out = new StringBuilder();
    out.append('{');
    boolean first = true;
    for (var field : node.fields().entrySet()) {
      if (!first) {
        out.append(',');
      }
      first = false;
      String key = field.getKey();
      Object value = field.getValue();
      LanguageCodec.append(key, out);
      out.append(':');
      if (LITERALS.contains(key)) {
        LanguageCodec.append(value, out);
      } else {
        String child =
            key.equals("items")
                ? listId(exactItems(value), token)
                : key.equals("mergePolicy")
                    ? identity(scalar("Text", value), token)
                    : identity((ExactNode) value, token);
        out.append("{\"blueId\":");
        LanguageCodec.append(child, out);
        out.append('}');
      }
    }
    out.append('}');
    return out.toString().getBytes(StandardCharsets.UTF_8);
  }

  static Object helper(Value n, IntFunction<String> token) {
    var out = new TreeMap<String, Object>();
    n.fields()
        .forEach(
            (k, v) -> {
              if (LITERALS.contains(k)) {
                out.put(k, v);
              } else {
                out.put(
                    k,
                    Map.of(
                        "blueId",
                        k.equals("items")
                            ? listId(exactItems(v), token)
                            : k.equals("mergePolicy")
                                ? identity(scalar("Text", v), token)
                                : identity((ExactNode) v, token)));
              }
            });
    return out;
  }

  record Encoded(String type, Object value) {}

  static Encoded encode(String type, Object x, LanguageContext c) {
    if (type == null) {
      type =
          CORE.get(
              x instanceof String
                  ? "Text"
                  : x instanceof Boolean
                      ? "Boolean"
                      : x instanceof NumberToken t
                          ? (t.decimal() ? "Double" : "Integer")
                          : x instanceof Double || x instanceof Float ? "Double" : "Integer");
    }
    String core = CORE_NAME.get(type);
    Object value = x;
    try {
      if ("Integer".equals(core)) {
        BigInteger v;
        if (x instanceof String s) {
          require(
              LanguageSyntax.INTEGER.matcher(s).matches(),
              "InvalidScalar",
              "canonical Integer text");
          c.bound("numeric_chars", s.length());
          v = new BigInteger(s);
        } else if (x instanceof NumberToken t) {
          if (!t.decimal()) {
            require(
                new BigInteger(t.text()).abs().compareTo(LanguageCodec.SAFE) <= 0,
                "InvalidScalar",
                "unsafe integer token");
          }
          v = decimal(t.text(), c).toBigIntegerExact();
        } else if (x instanceof BigInteger b) {
          v = b;
        } else if (x instanceof Byte
            || x instanceof Short
            || x instanceof Integer
            || x instanceof Long) {
          v = BigInteger.valueOf(((Number) x).longValue());
        } else {
          throw new LanguageFailure("InvalidScalar", "Integer payload");
        }
        c.bound("numeric_chars", v.toString().length());
        value = v.abs().compareTo(LanguageCodec.SAFE) <= 0 ? v : v.toString();
      } else if ("Double".equals(core)) {
        double v;
        if (x instanceof NumberToken t) {
          v = decimal(t.text(), c).doubleValue();
        } else if (x instanceof String s) {
          v = decimal(s, c).doubleValue();
        } else if (x instanceof Number n) {
          v = n.doubleValue();
        } else {
          throw new LanguageFailure("InvalidScalar", "Double payload");
        }
        require(Double.isFinite(v), "InvalidScalar", "nonfinite");
        value = v == 0 ? 0.0 : v;
      } else if ("Text".equals(core)) {
        require(x instanceof String, "InvalidScalar", "Text payload");
        value = LanguageCodec.unicode((String) x);
      } else if ("Boolean".equals(core)) {
        require(x instanceof Boolean, "InvalidScalar", "Boolean payload");
      } else {
        if (x instanceof NumberToken t) {
          if (!t.decimal()) {
            value = new BigInteger(t.text());
            require(((BigInteger) value).abs().compareTo(LanguageCodec.SAFE) <= 0, "InvalidScalar");
          } else {
            value = decimal(t.text(), c).doubleValue();
          }
        }
        require(
            value instanceof String || value instanceof Boolean || value instanceof Number,
            "InvalidScalar");
        if (value instanceof BigInteger b) {
          require(
              b.abs().compareTo(LanguageCodec.SAFE) <= 0, "InvalidScalar", "unsafe custom integer");
        }
        if (value instanceof Double d) {
          require(Double.isFinite(d), "InvalidScalar");
          if (d == Math.rint(d) && Math.abs(d) <= LanguageCodec.SAFE.doubleValue()) {
            value = BigDecimal.valueOf(d).toBigIntegerExact();
          }
        }
      }
    } catch (NumberFormatException | ArithmeticException e) {
      throw new LanguageFailure("InvalidScalar", e.getMessage());
    }
    return new Encoded(type, value);
  }

  static BigDecimal decimal(String s, LanguageContext c) {
    require(LanguageSyntax.NUMBER.matcher(s).matches(), "InvalidScalar", "decimal syntax");
    c.bound("numeric_chars", s.length());
    BigDecimal d = new BigDecimal(s);
    c.bound("decimal_scale", Math.abs((long) d.scale()));
    return d;
  }

  static boolean containsLocal(ExactNode n) {
    if (n instanceof Local) {
      return true;
    }
    if (!(n instanceof Value v)) {
      return false;
    }
    byte known = v.localPresence;
    if (known != 0) {
      return known == 2;
    }
    boolean found = false;
    for (var e : v.fields().entrySet()) {
      if (LITERALS.contains(e.getKey()) || e.getKey().equals("mergePolicy")) {
        continue;
      }
      if (e.getKey().equals("items")) {
        for (ExactNode child : exactItems(e.getValue())) {
          if (containsLocal(child)) {
            found = true;
            break;
          }
        }
      } else {
        found = containsLocal((ExactNode) e.getValue());
      }
      if (found) {
        break;
      }
    }
    v.localPresence = (byte) (found ? 2 : 1);
    return found;
  }

  static ExactNode normalize(Object input, LanguageContext c, String mode, boolean symbolic) {
    c.step();
    if (input instanceof ExactNode n) {
      require(symbolic || !containsLocal(n), "InvalidBlueId");
      return n;
    }
    require(input != null, "InvalidExactNode");
    Object x = input;
    if (x instanceof Map<?, ?> mm && mm.containsKey("blueId")) {
      require(mm.size() == 1, "InvalidReferenceShape");
      Object id = mm.get("blueId");
      if (symbolic
          && id instanceof String s
          && LanguageSyntax.LOCAL_REFERENCE.matcher(s).matches()) {
        return new Local(Integer.parseInt(s.substring(5)));
      }
      return new Ref(finalId(id));
    }
    if (x instanceof List) {
      x = Map.of("items", x);
    } else if (!(x instanceof Map)) {
      x = Map.of("value", x);
    }
    Map<String, Object> raw = map(x);
    require(
        Collections.disjoint(raw.keySet(), Set.of("blue", "properties", "constraints")),
        "InvalidNodeShape",
        "reserved key");
    var m = new TreeMap<String, Object>();
    raw.forEach(
        (k, v) -> {
          LanguageCodec.unicode(k);
          if (v != null) {
            m.put(k, v);
          }
        });
    for (String k : LABELS) {
      if (m.containsKey(k)) {
        LanguageCodec.unicode(string(m.get(k)));
      }
    }
    if (m.containsKey("mergePolicy")) {
      require(
          Set.of("positional", "append-only").contains(m.get("mergePolicy")),
          "InvalidNodeShape",
          "mergePolicy");
    }
    var ordinary = new HashSet<>(m.keySet());
    ordinary.removeAll(RESERVED);
    require(
        (m.containsKey("value") ? 1 : 0)
                + (m.containsKey("items") ? 1 : 0)
                + (ordinary.isEmpty() ? 0 : 1)
            <= 1,
        "InvalidNodeShape",
        "payload kinds");
    for (String k : new TreeSet<>(m.keySet())) {
      if (TYPES.contains(k) || k.equals("specification")) {
        Object v = m.get(k);
        if (mode.equals("direct")) {
          require(
              v instanceof Ref || v instanceof Map<?, ?> mm && mm.keySet().equals(Set.of("blueId")),
              "InvalidBlueIdInput",
              "type position");
        }
        require(!(v instanceof String), "UnknownTypeAlias");
        ExactNode n = LanguageDiagnostics.child(c, k, () -> normalize(v, c, "exact", symbolic));
        if (containsLocal(n)) {
          m.put(k, n);
        } else {
          c.remember(n);
          m.put(k, new Ref(identity(n)));
        }
      }
    }
    if (m.containsKey("value")) {
      Object t = m.get("type");
      String tid = t instanceof Ref r ? r.id() : t != null ? "symbolic" : null;
      try {
        var encoded = encode(tid, m.get("value"), c);
        if (t == null) {
          m.put("type", new Ref(encoded.type()));
        }
        m.put("value", encoded.value());
      } catch (LanguageFailure e) {
        e.data.putIfAbsent("expected", LanguageDiagnostics.value(ABSENT, tid, c, "scalar"));
        e.data.putIfAbsent("actual", LanguageDiagnostics.value(m.get("value"), null, c, null));
        throw e;
      }
    }
    for (String k : new TreeSet<>(m.keySet())) {
      if (TYPES.contains(k) || LITERALS.contains(k) || k.equals("mergePolicy")) {
        continue;
      }
      if (k.equals("items")) {
        var a = new ArrayList<ExactNode>();
        for (Object v : list(m.get(k))) {
          int index = a.size();
          a.add(
              LanguageDiagnostics.child(
                  c,
                  "items",
                  () ->
                      LanguageDiagnostics.child(c, index, () -> normalize(v, c, mode, symbolic))));
        }
        m.put(k, List.copyOf(a));
      } else {
        m.put(k, LanguageDiagnostics.child(c, k, () -> normalize(m.get(k), c, mode, symbolic)));
      }
    }
    var n = ExactNode.value(m);
    if (!containsLocal(n)) {
      return c.remember(n);
    }
    return n;
  }

  static Object export(ExactNode n) {
    if (n instanceof Ref r) {
      return Map.of("blueId", r.id());
    }
    if (n instanceof Local l) {
      return Map.of("blueId", "this#" + l.index());
    }
    var m = new TreeMap<String, Object>();
    ((Value) n)
        .fields()
        .forEach(
            (k, v) -> {
              if (LITERALS.contains(k) || k.equals("mergePolicy")) {
                m.put(k, v);
              } else if (k.equals("items")) {
                m.put(k, (exactItems(v)).stream().map(LanguageCore::export).toList());
              } else {
                m.put(k, export((ExactNode) v));
              }
            });
    return m.keySet().equals(Set.of("items")) ? m.get("items") : m;
  }

  static String exactText(ExactNode value) {
    return LanguageCodec.exact(value);
  }

  static Value open(ExactNode n, LanguageContext c) {
    c.step();
    if (n instanceof Value v) {
      return c.retainedBody(v);
    }
    require(n instanceof Ref, "InvalidExactNode");
    Ref r = (Ref) n;
    finalId(r.id());
    if (r.body() != null) {
      return c.retainedBody(r.body());
    }
    Value retained = c.bodies.get(r.id());
    if (retained != null) {
      return c.retainedBody(retained);
    }
    c.bound("fetches", c.demands.size() + 1);
    c.demands.add(r.id());
    boolean fetched = !c.provider.containsKey(r.id());
    if (fetched) {
      Object supplied;
      try {
        supplied = c.lookup.apply(r.id());
      } catch (RuntimeException cause) {
        var failure = new LanguageFailure("ProviderUnavailable", r.id());
        failure.initCause(cause);
        throw failure;
      }
      require(supplied != null, "ProviderUnavailable", r.id());
      c.provider.put(r.id(), supplied);
    }
    c.providerDepth++;
    try {
      return c.retainedBody(providerBody(r, c));
    } catch (RuntimeException failure) {
      // A captured view owns its raw response map. A failed lookup in that view must
      // leave room for fresh evidence from a later provider.
      if (fetched) {
        c.provider.remove(r.id());
      }
      throw failure;
    } finally {
      c.providerDepth--;
    }
  }

  private static Value providerBody(Ref r, LanguageContext c) {
    Object response = c.provider.get(r.id());
    if (response instanceof Map<?, ?> rm && rm.containsKey("partialObject")) {
      require(
          rm.keySet().equals(Set.of("partialObject", "completeDirectManifest"))
              && rm.get("completeDirectManifest") instanceof Boolean,
          "InvalidProviderMode");
      require(Boolean.TRUE.equals(rm.get("completeDirectManifest")), "ProviderUnavailable", r.id());
      response = Map.of("text", LanguageCodec.wire(rm.get("partialObject")), "mode", "exact");
    }
    if (response instanceof Map<?, ?> rm && rm.containsKey("outcome")) {
      require(rm.size() == 1, "InvalidProviderMode");
      String outcome = string(rm.get("outcome"));
      throw new LanguageFailure(
          outcome.equals("NotFound") || outcome.equals("Unavailable")
              ? "ProviderUnavailable"
              : outcome.equals("InvalidEvidence")
                  ? "ProviderBlueIdMismatch"
                  : "InvalidProviderMode",
          r.id());
    }
    if (r.id().contains("#")) {
      Map<String, Object> rm = map(response);
      require(
          rm.containsKey("proof") && Set.of("proof", "body").containsAll(rm.keySet()),
          "InvalidCyclicProof");
      var result =
          LanguageOwningSet.verifyMember(r.id(), rm.get("proof"), rm.get("body"), c).body();
      return result;
    }
    ExactNode body;
    if (response instanceof Value v) {
      body = v;
    } else {
      Map<String, Object> rm = map(response);
      require(
          rm.containsKey("text")
              && Set.of("text", "format", "mode", "environment").containsAll(rm.keySet()),
          "InvalidProviderMode");
      String mode = string(rm.getOrDefault("mode", "exact"));
      require(Set.of("exact", "source").contains(mode), "InvalidProviderMode");
      if (mode.equals("source")) {
        require(
            Objects.equals(
                rm.get("environment"),
                Map.of(
                    "specification",
                    "1.0",
                    "aliases",
                    c.aliases,
                    "directiveAliases",
                    c.directiveAliases)),
            "InvalidProviderMode",
            "unbound LanguageSource environment");
      } else {
        require(!rm.containsKey("environment"), "InvalidProviderMode");
      }
      try {
        Object raw =
            LanguageOperations.parse(
                string(rm.get("text")), string(rm.getOrDefault("format", "json")), c);
        body =
            mode.equals("source")
                ? LanguageSource.prepare(raw, c)
                : normalize(raw, c, "exact", false);
      } catch (LanguageFailure f) {
        if (Set.of("ProviderUnavailable", "OperationLimitExceeded").contains(f.category())) {
          throw f;
        }
        throw new LanguageFailure("ProviderBlueIdMismatch", r.id());
      }
    }
    require(
        body instanceof Value && identity(body).equals(r.id()), "ProviderBlueIdMismatch", r.id());
    c.bodies.put(r.id(), (Value) body);
    return (Value) body;
  }

  static List<String> path(String p) {
    require(p.isEmpty() || p.startsWith("/"), "InvalidPath");
    if (p.isEmpty()) {
      return List.of();
    }
    var parts = new ArrayList<String>();
    for (String s : p.substring(1).split("/", -1)) {
      require(!LanguageSyntax.INVALID_POINTER_ESCAPE.matcher(s).matches(), "InvalidPath");
      parts.add(s.replace("~1", "/").replace("~0", "~"));
    }
    return parts;
  }

  static int index(String p) {
    require(LanguageSyntax.LIST_INDEX.matcher(p).matches(), "InvalidPath");
    try {
      return Integer.parseInt(p);
    } catch (NumberFormatException e) {
      return Integer.MAX_VALUE;
    }
  }

  static Object at(ExactNode n, List<String> parts, LanguageContext c) {
    if (parts.isEmpty()) {
      return n;
    }
    Value b = open(n, c);
    String k = parts.getFirst();
    if (k.equals("blueId") || !b.has(k)) {
      return ABSENT;
    }
    Object v = b.get(k);
    if (k.equals("items")) {
      List<ExactNode> xs = exactItems(v);
      if (parts.size() == 1) {
        return xs;
      }
      int i = index(parts.get(1));
      return i >= xs.size() ? ABSENT : at(xs.get(i), parts.subList(2, parts.size()), c);
    }
    if (LITERALS.contains(k) || k.equals("mergePolicy")) {
      return parts.size() == 1 ? v : ABSENT;
    }
    return at((ExactNode) v, parts.subList(1, parts.size()), c);
  }

  static ExactNode mapAt(
      ExactNode n, List<String> p, LanguageContext c, UnaryOperator<ExactNode> fn) {
    if (p.isEmpty()) {
      return fn.apply(n);
    }
    Value b = open(n, c);
    String k = p.getFirst();
    require(b.has(k) && !k.equals("blueId"), "PathAbsent");
    if (k.equals("items")) {
      require(p.size() >= 2, "InvalidPath");
      int i = index(p.get(1));
      var a = new ArrayList<>(exactItems(b.get(k)));
      require(i < a.size(), "PathAbsent");
      a.set(i, mapAt(a.get(i), p.subList(2, p.size()), c, fn));
      b = b.with(k, List.copyOf(a));
    } else {
      require(!LITERALS.contains(k) && !k.equals("mergePolicy"), "InvalidPath");
      b = b.with(k, mapAt((ExactNode) b.get(k), p.subList(1, p.size()), c, fn));
    }
    return n instanceof Ref r && r.id().contains("#") ? new Ref(r.id(), b) : b;
  }

  static ExactNode expand(ExactNode n, List<String> paths, LanguageContext c, boolean collapse) {
    var sorted =
        new TreeSet<String>(
            Comparator.comparingInt((String p) -> path(p).size() * (collapse ? -1 : 1))
                .thenComparing(p -> p));
    sorted.addAll(paths);
    ExactNode out = n;
    for (String p : sorted) {
      out =
          mapAt(
              out,
              path(p),
              c,
              x ->
                  collapse
                      ? new Ref(identity(x))
                      : x instanceof Ref r && r.id().contains("#")
                          ? new Ref(r.id(), open(x, c))
                          : open(x, c));
    }
    require(identity(out).equals(identity(n)), "InternalError");
    return out;
  }

  static LanguageFragments split(ExactNode exact, List<String> paths, LanguageContext c) {
    var selected =
        new TreeSet<List<String>>(
            Comparator.<List<String>>comparingInt(p -> -p.size())
                .thenComparing(
                    p -> p,
                    (a, b) -> {
                      for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
                        int n = a.get(i).compareTo(b.get(i));
                        if (n != 0) {
                          return n;
                        }
                      }
                      return Integer.compare(a.size(), b.size());
                    }));
    selected.add(List.of());
    for (String text : paths) {
      var parts = path(text);
      require(at(exact, parts, c) instanceof ExactNode, "InvalidPath");
      for (int i = 1; i <= parts.size(); i++) {
        var prefix = List.copyOf(parts.subList(0, i));
        if (at(exact, prefix, c) instanceof ExactNode) {
          selected.add(prefix);
        }
      }
    }
    ExactNode out = exact;
    var fragments = new TreeMap<String, String>();
    var proofs = new TreeMap<String, String>();
    for (var parts : selected) {
      Object found = at(out, parts, c);
      require(found instanceof ExactNode, "InvalidPath");
      ExactNode value = (ExactNode) found;
      String id = identity(value);
      fragments.put(id, exactText(open(value, c)));
      if (id.contains("#") && c.proofs.containsKey(id)) {
        proofs.put(id, LanguageCodec.wire(c.proofs.get(id)));
      }
      if (!parts.isEmpty()) {
        out = mapAt(out, parts, c, n -> new Ref(identity(n)));
      }
    }
    require(identity(out).equals(identity(exact)), "InternalError");
    return new LanguageFragments(identity(exact), exactText(out), fragments, proofs);
  }
}
