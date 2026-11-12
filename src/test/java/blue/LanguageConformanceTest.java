package blue;

import static blue.ExactNode.*;
import static blue.LanguageCore.*;
import static blue.LanguageSemantics.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

/** Unmodified published Language vectors, exercised without another checkout or interpreter. */
@Tag("conformance")
@Tag("language")
class LanguageConformanceTest {
  @TestFactory
  Stream<DynamicTest> publishedLanguage() throws Exception {
    var tests = new ArrayList<DynamicTest>();
    for (String resource : List.of("language-cases.json", "language-edge-cases.json")) {
      try (var stream = getClass().getResourceAsStream("/language/" + resource)) {
        assertNotNull(stream);
        var corpus =
            map(LanguageCodec.json(new String(stream.readAllBytes(), StandardCharsets.UTF_8)));
        assertEquals("blue-language-conformance/1", corpus.get("format"));
        for (Object entry : list(corpus.get("cases"))) {
          var example = map(entry);
          assertTrue(
              Set.of("id", "description", "environment", "request", "expected")
                  .containsAll(example.keySet()));
          checkFixture(example);
          tests.add(
              DynamicTest.dynamicTest(
                  string(example.get("id")),
                  () -> {
                    LanguageContext context =
                        environment(map(example.getOrDefault("environment", Map.of())));
                    Map<String, Object> actual =
                        LanguageOperations.outcome(
                            () -> request(map(example.get("request")), context));
                    matches(actual, example.get("expected"), "");
                  }));
        }
      }
    }
    return tests.stream();
  }

  private static LanguageContext environment(Map<String, Object> environment) {
    assertTrue(
        Set.of("limits", "provider", "aliases", "definitions", "directiveAliases")
            .containsAll(environment.keySet()));
    var c = new LanguageContext();
    c.provider.putAll(map(environment.getOrDefault("provider", Map.of())));
    map(environment.getOrDefault("aliases", Map.of()))
        .forEach((k, v) -> c.aliases.put(k, string(v)));
    map(environment.getOrDefault("directiveAliases", Map.of()))
        .forEach((k, v) -> c.directiveAliases.put(k, string(v)));
    for (Object entry : list(environment.getOrDefault("definitions", List.of()))) {
      var definition = map(entry);
      ExactNode node = LanguageOperations.input(map(definition.get("input")), c);
      if (definition.containsKey("blueId")) {
        assertEquals(definition.get("blueId"), identity(node));
      }
      c.remember(node);
      if (definition.containsKey("alias")) {
        c.aliases.put(string(definition.get("alias")), identity(node));
      }
    }
    map(environment.getOrDefault("limits", Map.of()))
        .forEach(
            (k, v) -> {
              if (v != null) {
                c.limits.put(k, ((Number) v).longValue());
              }
            });
    c.work.set(0);
    c.demands.clear();
    return c;
  }

  private static ExactNode input(Map<String, Object> request, String key, LanguageContext c) {
    return LanguageOperations.input(map(request.get(key)), c);
  }

  private static Map<String, Object> established(Object... pairs) {
    var result = new LinkedHashMap<String, Object>();
    result.put("outcome", "Established");
    for (int i = 0; i < pairs.length; i += 2) {
      result.put((String) pairs[i], pairs[i + 1]);
    }
    return result;
  }

  private static Object show(Object value) {
    if (value instanceof ExactNode node) {
      return export(node);
    }
    if (value instanceof List<?> items) {
      return items.stream().map(LanguageConformanceTest::show).toList();
    }
    if (value instanceof Map<?, ?> fields) {
      var result = new TreeMap<String, Object>();
      fields.forEach((k, v) -> result.put((String) k, show(v)));
      return result;
    }
    return value;
  }

  static Map<String, Object> request(Map<String, Object> r, LanguageContext c) {
    String operation = string(r.get("operation"));
    return switch (operation) {
      case "preprocess" -> {
        var i = map(r.get("input"));
        Object raw =
            LanguageOperations.parse(
                string(i.get("text")), string(i.getOrDefault("format", "json")), c);
        yield established("text", LanguageCodec.wire(show(LanguageSource.preprocess(raw, c))));
      }
      case "identify", "canonicalize" -> {
        ExactNode node = input(r, "input", c);
        yield established("blueId", identity(node), "text", exactText(node));
      }
      case "validate" -> {
        ExactNode node = input(r, "input", c);
        certify(node, c, string(r.getOrDefault("goal", "value")));
        yield established("valid", true, "blueId", identity(node));
      }
      case "resolve", "resolveLimited", "resolveComplete" -> {
        ExactNode node = input(r, "input", c);
        if (operation.equals("resolveComplete")) {
          certify(node, c, string(r.getOrDefault("goal", "value")));
        }
        var observations = new ArrayList<Object>();
        for (Object query : list(r.get("queries"))) {
          var q = map(query);
          observations.add(
              LanguageOperations.outcome(
                  () ->
                      LanguageOperations.observe(
                          node, string(q.getOrDefault("path", "")), string(q.get("query")), c)));
        }
        yield established("observations", observations);
      }
      case "subtype", "preserves" -> {
        ExactNode a = input(r, "candidate", c), b = input(r, "parent", c);
        c.remember(a);
        c.remember(b);
        if (operation.equals("subtype")) {
          yield established("subtype", subtype(identity(a), identity(b), c));
        }
        certify(a, c, "definition");
        certify(b, c, "definition");
        yield established("preserves", preserves(head(recipe(a), c), head(recipe(b), c), c, false));
      }
      case "schemaRefine" -> {
        ExactNode a = input(r, "parent", c), b = input(r, "candidate", c);
        LanguageSchema.narrowing(LanguageSchema.read(a, c), LanguageSchema.read(b, c), c);
        var schema = LanguageSchema.conjoin(LanguageSchema.read(a, c), LanguageSchema.read(b, c));
        ExactNode merged = LanguageSemantics.mergeSchema(a, b, c);
        yield established(
            "constraints",
            schema.describe(),
            "blueId",
            identity(merged),
            "text",
            exactText(merged));
      }
      case "schemaConjoin" ->
          established(
              "constraints",
              LanguageSchema.conjoin(
                      LanguageSchema.read(input(r, "left", c), c),
                      LanguageSchema.read(input(r, "right", c), c))
                  .describe());
      case "fold" -> {
        String id = plainId(r.get("prefix"));
        for (Object item : list(r.get("suffix"))) {
          id = appendId(id, identity(LanguageOperations.input(map(item), c)));
        }
        yield established("blueId", id);
      }
      case "verify" -> {
        String id = finalId(r.get("blueId"));
        yield established("blueId", id, "bodyBlueId", identity(open(new Ref(id), c)));
      }
      case "expand", "collapse" -> {
        ExactNode node = input(r, "input", c);
        var paths = list(r.get("paths")).stream().map(LanguageCore::string).toList();
        ExactNode result = expand(node, paths, c, operation.equals("collapse"));
        yield established("blueId", identity(result), "text", LanguageCodec.wire(export(result)));
      }
      case "lookupExact" -> {
        Object value = at(input(r, "input", c), path(string(r.get("path"))), c);
        if (value == ABSENT) {
          yield Map.of("outcome", "Absent");
        }
        if (value instanceof ExactNode node) {
          yield established("blueId", identity(node));
        }
        if (value instanceof List<?> values) {
          yield established("length", values.size());
        }
        yield established("value", LanguageOperations.literal(value, null));
      }
      case "minimize" -> {
        ExactNode node = input(r, "input", c);
        String text = LanguageOperations.minimize(node, c, string(r.getOrDefault("goal", "value")));
        ExactNode again = LanguageSource.prepare(LanguageOperations.parse(text, "json", c), c);
        yield established(
            "text",
            text,
            "sameView",
            LanguageOperations.same(node, again, c),
            "blueId",
            identity(again),
            "originalBlueId",
            identity(node));
      }
      case "finalize" -> {
        var raw = new ArrayList<Object>();
        for (Object item : list(r.get("documents"))) {
          var i = map(item);
          raw.add(
              LanguageOperations.parse(
                  string(i.get("text")), string(i.getOrDefault("format", "json")), c));
        }
        var result = finalizeSet(raw, c);
        yield established(
            "master",
            result.master(),
            "blueIds",
            result.ids(),
            "proofText",
            LanguageCodec.wire(
                Map.of("members", result.canonical().stream().map(LanguageCore::export).toList())),
            "bodyTexts",
            result.bodies().stream().map(LanguageCore::exactText).toList());
      }
      case "verifyMember" -> {
        String id = string(r.get("blueId"));
        var result = verifyMember(id, r.get("proof"), r.get("body"), c);
        yield established("blueId", id, "bodyBlueId", identity(result.body()));
      }
      case "roundtrip" -> {
        ExactNode node = input(r, "input", c);
        ExactNode again =
            normalize(LanguageOperations.parse(exactText(node), "json", c), c, "exact", false);
        yield established(
            "blueId",
            identity(node),
            "reloadedBlueId",
            identity(again),
            "equal",
            identity(node).equals(identity(again)));
      }
      default -> throw new AssertionError("Unknown fixture operation: " + operation);
    };
  }

  private static void checkFixture(Map<String, Object> fixture) {
    fields(fixture, Set.of("id", "request", "expected"), Set.of("description", "environment"));
    assertFalse(string(fixture.get("id")).isEmpty());
    var r = map(fixture.get("request"));
    String op = string(r.get("operation"));
    Set<String> required =
        switch (op) {
          case "preprocess", "identify", "canonicalize", "validate", "minimize", "roundtrip" ->
              Set.of("input");
          case "resolve", "resolveLimited", "resolveComplete" -> Set.of("input", "queries");
          case "subtype", "preserves", "schemaRefine" -> Set.of("candidate", "parent");
          case "schemaConjoin" -> Set.of("left", "right");
          case "fold" -> Set.of("prefix", "suffix");
          case "verify" -> Set.of("blueId");
          case "expand", "collapse" -> Set.of("input", "paths");
          case "lookupExact" -> Set.of("input", "path");
          case "finalize" -> Set.of("documents");
          case "verifyMember" -> Set.of("blueId", "proof");
          default -> throw new AssertionError("Unknown fixture operation: " + op);
        };
    var req = new HashSet<>(required);
    req.add("operation");
    Set<String> optional =
        switch (op) {
          case "validate", "resolveComplete", "minimize" -> Set.of("goal");
          case "verifyMember" -> Set.of("body");
          default -> Set.of();
        };
    fields(r, req, optional);
    for (String key : List.of("input", "candidate", "parent", "left", "right")) {
      if (r.containsKey(key)) {
        checkInput(r.get(key));
      }
    }
    for (String key : List.of("suffix", "documents")) {
      if (r.containsKey(key)) {
        list(r.get(key)).forEach(LanguageConformanceTest::checkInput);
      }
    }
    if (r.containsKey("goal")) {
      assertTrue(Set.of("definition", "value").contains(r.get("goal")));
    }
    if (r.containsKey("queries")) {
      for (Object raw : list(r.get("queries"))) {
        var q = map(raw);
        fields(q, Set.of("query"), Set.of("path"));
        assertTrue(
            Set.of(
                    "exactId",
                    "kind",
                    "type",
                    "value",
                    "constraints",
                    "keys",
                    "length",
                    "present",
                    "name",
                    "description",
                    "specification")
                .contains(q.get("query")));
        if (q.containsKey("path")) {
          assertInstanceOf(String.class, q.get("path"));
        }
      }
    }
    if (r.containsKey("paths")) {
      for (Object path : list(r.get("paths"))) {
        assertInstanceOf(String.class, path);
      }
    }
    for (String key : List.of("path", "proof", "body", "prefix", "blueId")) {
      if (r.containsKey(key)) {
        assertInstanceOf(String.class, r.get(key));
      }
    }
    var environment = map(fixture.getOrDefault("environment", Map.of()));
    fields(
        environment,
        Set.of(),
        Set.of("limits", "provider", "aliases", "definitions", "directiveAliases"));
    for (Object raw : list(environment.getOrDefault("definitions", List.of()))) {
      var d = map(raw);
      fields(d, Set.of("input"), Set.of("alias", "blueId"));
      checkInput(d.get("input"));
    }
    for (Object raw : map(environment.getOrDefault("provider", Map.of())).values()) {
      var response = map(raw);
      if (response.containsKey("outcome")) {
        fields(response, Set.of("outcome"), Set.of());
        assertTrue(
            Set.of("NotFound", "Unavailable", "InvalidEvidence").contains(response.get("outcome")));
      } else if (response.containsKey("proof")) {
        fields(response, Set.of("proof"), Set.of("body"));
      } else if (response.containsKey("partialObject")) {
        fields(response, Set.of("partialObject", "completeDirectManifest"), Set.of());
      } else {
        fields(response, Set.of("text"), Set.of("format", "mode", "environment"));
      }
    }
    var expected = map(fixture.get("expected"));
    fields(
        expected,
        Set.of("outcome"),
        Set.of(
            "category",
            "blueId",
            "bodyBlueId",
            "text",
            "valid",
            "observations",
            "subtype",
            "preserves",
            "constraints",
            "length",
            "value",
            "sameView",
            "originalBlueId",
            "master",
            "blueIds",
            "proofText",
            "bodyTexts",
            "reloadedBlueId",
            "equal"));
    assertTrue(
        Set.of("Established", "Absent", "Incomplete", "Invalid").contains(expected.get("outcome")));
  }

  private static void checkInput(Object raw) {
    var input = map(raw);
    fields(input, Set.of("text"), Set.of("mode", "format"));
    assertInstanceOf(String.class, input.get("text"));
    assertTrue(Set.of("source", "exact", "direct").contains(input.getOrDefault("mode", "source")));
    assertTrue(Set.of("yaml", "json").contains(input.getOrDefault("format", "json")));
  }

  private static void fields(
      Map<String, Object> object, Set<String> required, Set<String> optional) {
    assertTrue(object.keySet().containsAll(required), "Missing fixture fields: " + required);
    var allowed = new HashSet<>(required);
    allowed.addAll(optional);
    assertTrue(
        allowed.containsAll(object.keySet()), "Unexpected fixture fields: " + object.keySet());
  }

  private static void matches(Object actual, Object expected, String path) {
    if (expected instanceof Map<?, ?> fields) {
      assertInstanceOf(Map.class, actual, path);
      fields.forEach(
          (key, value) -> {
            assertTrue(((Map<?, ?>) actual).containsKey(key), path + "/" + key);
            matches(((Map<?, ?>) actual).get(key), value, path + "/" + key);
          });
    } else if (expected instanceof List<?> items) {
      assertInstanceOf(List.class, actual, path);
      var values = (List<?>) actual;
      assertEquals(items.size(), values.size(), path);
      for (int i = 0; i < items.size(); i++) {
        matches(values.get(i), items.get(i), path + "/" + i);
      }
    } else if (expected instanceof Number e && actual instanceof Number a) {
      assertEquals(
          e instanceof java.math.BigDecimal || e instanceof Double,
          a instanceof java.math.BigDecimal || a instanceof Double,
          path + " numeric category");
      assertEquals(
          0,
          new java.math.BigDecimal(e.toString()).compareTo(new java.math.BigDecimal(a.toString())),
          path);
    } else {
      assertEquals(expected, actual, path);
    }
  }
}
