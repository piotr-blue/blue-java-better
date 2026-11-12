package blue;

import static blue.LanguageCore.ABSENT;
import static blue.LanguageCore.CORE_NAME;
import static blue.LanguageCore.identity;

import blue.ExactNode.NumberToken;
import blue.ExactNode.Ref;
import blue.ExactNode.Value;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Diagnostic context is observational: it never changes content or fetches new dependencies. */
final class LanguageDiagnostics {
  private LanguageDiagnostics() {}

  static final Path ROOT = new Path(null, null);

  /** Validation shares prefixes; only a rejection renders the complete pointer. */
  static final class Path {
    private final Path parent;
    private final Object part;

    private Path(Path parent, Object part) {
      this.parent = parent;
      this.part = part;
    }

    boolean isEmpty() {
      return parent == null;
    }

    @Override
    public String toString() {
      var parts = new ArrayDeque<Path>();
      for (Path at = this; !at.isEmpty(); at = at.parent) parts.push(at);
      var text = new StringBuilder();
      while (!parts.isEmpty()) text.append('/').append(Pointer.escape(parts.pop().part.toString()));
      return text.toString();
    }
  }

  static Path pointer(Path path, Object part) {
    return new Path(path, part);
  }

  static <T> T at(LanguageContext c, Path path, String phase, Supplier<T> operation) {
    Path previous = c.diagnosticPath;
    String oldPhase = c.diagnosticPhase;
    c.diagnosticPath = path;
    if (phase != null) {
      c.diagnosticPhase = phase;
    }
    try {
      return operation.get();
    } catch (LanguageFailure e) {
      enrich(e, c);
      throw e;
    } finally {
      c.diagnosticPath = previous;
      c.diagnosticPhase = oldPhase;
    }
  }

  static <T> T child(LanguageContext c, Object field, Supplier<T> operation) {
    return at(c, pointer(c.diagnosticPath, field), null, operation);
  }

  static void enrich(LanguageFailure e, LanguageContext c) {
    e.data.computeIfAbsent("path", ignored -> c.diagnosticPath.toString());
    e.data.putIfAbsent("phase", c.diagnosticPhase);
    if (c.diagnosticGoal != null) {
      e.data.putIfAbsent("goal", c.diagnosticGoal);
    }
    if (c.diagnosticRoot instanceof Value root) {
      if (root.get("type") instanceof Ref r) {
        e.data.putIfAbsent("documentType", type(r.id(), c));
      }
      if (root.has("name")) {
        e.data.putIfAbsent("nodeName", literal(root.get("name")));
      }
    }
    if ("value".equals(c.diagnosticGoal)
        && e.category().equals("SchemaViolation")
        && Set.of("scalar payload required", "list payload required", "payload required")
            .contains(e.getMessage())) {
      e.data.putIfAbsent(
          "hint",
          "Supply a payload for a completed value. To declare a type without a payload, explicitly"
              + " validate as Definition instead.");
    }
  }

  static Map<String, Object> type(String id, LanguageContext c) {
    if (id == null) {
      return null;
    }
    var out = new LinkedHashMap<String, Object>();
    out.put("blueId", id);
    out.put("core", CORE_NAME.containsKey(id));
    if (CORE_NAME.containsKey(id)) {
      out.put("name", CORE_NAME.get(id));
    } else if (c != null && c.bodies.containsKey(id) && c.bodies.get(id).has("name")) {
      out.put("name", literal(c.bodies.get(id).get("name")));
    }
    return out;
  }

  static Object literal(Object v) {
    if (v == ABSENT) {
      return null;
    }
    if (v instanceof NumberToken t) {
      return Map.of("numberToken", t.text().substring(0, Math.min(256, t.text().length())));
    }
    if (v instanceof Ref r) {
      return Map.of("blueId", r.id());
    }
    if (v instanceof Value n) {
      return Map.of("blueId", identity(n), "kind", n.kind());
    }
    if (v instanceof Rational r) {
      return r.describe();
    }
    if (v instanceof BigInteger n && n.abs().compareTo(LanguageCodec.SAFE) > 0) {
      return Map.of("integer", n.toString());
    }
    if (v instanceof String s && s.codePointCount(0, s.length()) > 256) {
      return Map.of(
          "preview",
          s.substring(0, s.offsetByCodePoints(0, 256)),
          "truncated",
          true,
          "length",
          s.codePointCount(0, s.length()));
    }
    if (v instanceof List<?> a) {
      return Map.of(
          "length",
          a.size(),
          "preview",
          a.subList(0, Math.min(8, a.size())).stream().map(LanguageDiagnostics::literal).toList());
    }
    return v;
  }

  static Map<String, Object> value(Object v, String id, LanguageContext c, String kind) {
    var out = new LinkedHashMap<String, Object>();
    out.put("present", v != ABSENT);
    if (id != null) {
      out.put("type", type(id, c));
    }
    if (kind != null) {
      out.put("kind", kind);
    }
    if (v != ABSENT) {
      out.put("value", literal(v));
    }
    return out;
  }

  static LanguageFailure failure(
      String category, String detail, Object expected, Object actual, String keyword) {
    var e = new LanguageFailure(category, detail);
    if (expected != null) {
      e.data.put("expected", expected);
    }
    if (actual != null) {
      e.data.put("actual", actual);
    }
    if (keyword != null) {
      e.data.put("keyword", keyword);
    }
    return e;
  }

  static void require(
      boolean condition,
      String category,
      String detail,
      Object expected,
      Object actual,
      String keyword) {
    if (!condition) {
      throw failure(category, detail, expected, actual, keyword);
    }
  }
}
