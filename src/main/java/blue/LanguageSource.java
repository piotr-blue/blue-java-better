package blue;

import static blue.LanguageCore.CONTROLS;
import static blue.LanguageCore.CORE;
import static blue.LanguageCore.HOLE;
import static blue.LanguageCore.LABELS;
import static blue.LanguageCore.LITERALS;
import static blue.LanguageCore.RESERVED;
import static blue.LanguageCore.TYPES;
import static blue.LanguageCore.encode;
import static blue.LanguageCore.exactItems;
import static blue.LanguageCore.finalId;
import static blue.LanguageCore.hole;
import static blue.LanguageCore.identity;
import static blue.LanguageCore.list;
import static blue.LanguageCore.listId;
import static blue.LanguageCore.map;
import static blue.LanguageCore.normalize;
import static blue.LanguageCore.open;
import static blue.LanguageCore.plainId;
import static blue.LanguageCore.string;
import static blue.LanguageFailure.require;
import static blue.LanguageSemantics.child;
import static blue.LanguageSemantics.exact;
import static blue.LanguageSemantics.head;
import static blue.LanguageSemantics.merge;
import static blue.LanguageSemantics.recipeWith;
import static blue.LanguageSemantics.refine;
import static blue.LanguageSemantics.requirement;
import static blue.LanguageSemantics.single;

import blue.ExactNode.NumberToken;
import blue.ExactNode.Ref;
import blue.ExactNode.Value;
import blue.LanguageModel.Head;
import blue.LanguageModel.Recipe;
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
import java.util.function.Supplier;

/** A08/A09: LanguageSource syntax is compiled once against its explicit environment. */
final class LanguageSource {
  private LanguageSource() {}

  static final Set<String> INFER =
      Set.of(
          "FGYuTXwaoSKfZmpTysLTLsb8WzSqf43384rKZDkXhxD4",
          "49hrWpkoXavNmK8PpZag11zB2vYwzhQZahwioz6vDk2i");
  static final Set<String> ALIAS =
      Set.of(
          "27B7fuxQCS1VAptiCPc2RMkKoutP5qxkh3uDxZ7dr6Eo",
          "53yFLQ3dpuGwa2svHubDyzyhYz9RQNmctiJRdi3gRYr7");
  static final String RENAME = "7kEewGH6vogsgUXw3Gdyi73rtb5oQK1L8LWtHbYcG7pB",
      APPEND = "D2DxVxaddbJT5k2YYj3sG35cSdotw1Mz9GpxrdP43nzN",
      SET = "3uVsScHAE36fm2Knz3P6tWY94nuEA7cVDWxci8bY2zGg";

  static Object walk(
      Object x,
      Map<String, String> bindings,
      boolean infer,
      boolean substitute,
      boolean item,
      LanguageContext c) {
    if (x instanceof ExactNode) {
      return x;
    }
    if (x instanceof Map<?, ?> raw && raw.containsKey("blueId")) {
      require(raw.size() == 1, "InvalidReferenceShape");
      finalId(raw.get("blueId"));
      return x;
    }
    if (x == null) {
      return item ? HOLE : null;
    }
    if (x instanceof List<?> a) {
      var out = new ArrayList<Object>();
      for (Object v : a) {
        int index = out.size();
        out.add(
            LanguageDiagnostics.child(
                c,
                "items",
                () ->
                    LanguageDiagnostics.child(
                        c, index, () -> walk(v, bindings, infer, substitute, true, c))));
      }
      return out;
    }
    if (!(x instanceof Map)) {
      x = Map.of("value", x);
    }
    Map<String, Object> m = new TreeMap<>(map(x));
    require(
        Collections.disjoint(m.keySet(), Set.of("blue", "properties", "constraints")),
        "InvalidNodeShape",
        "reserved key");
    if (item && !Collections.disjoint(m.keySet(), CONTROLS)) {
      if (m.containsKey("$previous") || m.containsKey("$previousItems")) {
        require(m.size() == 1, "ListControlViolation");
        return m;
      }
      require(m.containsKey("$pos"), "ListControlViolation");
      if (m.containsKey("$replace")) {
        require(m.keySet().equals(Set.of("$pos", "$replace")), "ListControlViolation");
        return Map.of(
            "$pos",
            m.get("$pos"),
            "$replace",
            walk(m.get("$replace"), bindings, infer, substitute, false, c));
      }
      Object pos = m.remove("$pos");
      Map<String, Object> out = new TreeMap<>(map(walk(m, bindings, infer, substitute, false, c)));
      out.put("$pos", pos);
      return out;
    }
    require(
        !m.containsKey("$previousItems"),
        "ListControlViolation",
        "prefix assertion outside list items");
    m.values().removeIf(Objects::isNull);
    for (String k : new TreeSet<>(m.keySet())) {
      Object v = m.get(k);
      if (TYPES.contains(k)) {
        if (v instanceof String s) {
          if (substitute) {
            require(bindings.containsKey(s), "UnknownTypeAlias", s);
            m.put(k, Map.of("blueId", bindings.get(s)));
          }
        } else {
          m.put(
              k,
              LanguageDiagnostics.child(
                  c, k, () -> walk(v, bindings, infer, substitute, false, c)));
        }
      } else if (k.equals("specification")) {
        require(
            v instanceof Map || v instanceof ExactNode,
            "InvalidNodeShape",
            "specification requires an explicit node or reference");
        m.put(
            k,
            LanguageDiagnostics.child(c, k, () -> walk(v, bindings, infer, substitute, false, c)));
      } else if (k.equals("items")) {
        var a = new ArrayList<Object>();
        for (Object value : list(v)) {
          int index = a.size();
          a.add(
              LanguageDiagnostics.child(
                  c,
                  "items",
                  () ->
                      LanguageDiagnostics.child(
                          c, index, () -> walk(value, bindings, infer, substitute, true, c))));
        }
        m.put(k, a);
      } else if (!LITERALS.contains(k) && !k.equals("mergePolicy")) {
        m.put(
            k,
            LanguageDiagnostics.child(c, k, () -> walk(v, bindings, infer, substitute, false, c)));
      }
    }
    for (String k : LABELS) {
      if (m.containsKey(k)) {
        LanguageCodec.unicode(string(m.get(k)));
      }
    }
    if (m.containsKey("mergePolicy")) {
      require(
          Set.of("positional", "append-only").contains(m.get("mergePolicy")), "InvalidNodeShape");
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
    if (m.containsKey("value")) {
      Object v = m.get("value");
      require(
          v instanceof String
              || v instanceof Boolean
              || v instanceof Number
              || v instanceof NumberToken,
          "InvalidScalar");
      if (infer && !m.containsKey("type")) {
        m.put("type", Map.of("blueId", encode(null, v, c).type()));
      }
    }
    if (m.containsKey("items")) {
      controls(list(m.get("items")));
    }
    return m;
  }

  static String configText(Object n, LanguageContext c) {
    Value b = open((ExactNode) n, c);
    require(
        b.kind().equals("scalar")
            && Set.of("type", "value").containsAll(b.fields().keySet())
            && new Ref(CORE.get("Text")).equals(b.get("type"))
            && b.get("value") instanceof String,
        "InvalidPreprocessing",
        "Text configuration");
    return (String) b.get("value");
  }

  static Object preprocess(Object root, LanguageContext c) {
    c.step();
    if (!c.verifiedAliases) {
      for (var e : c.aliases.entrySet()) {
        finalId(e.getValue());
        require(
            !CORE.containsKey(e.getKey()) || CORE.get(e.getKey()).equals(e.getValue()),
            "InvalidPreprocessing",
            "core alias rebind");
      }
    }
    if (root instanceof Map<?, ?> m && m.containsKey("blueId")) {
      require(m.size() == 1, "InvalidReferenceShape");
    }
    Value d = ExactNode.value(Map.of());
    if (root instanceof Map<?, ?> m && m.containsKey("blue")) {
      Object directive = m.get("blue");
      require(directive != null, "InvalidPreprocessing", "null directive");
      if (directive instanceof String s) {
        require(
            c.directiveAliases.containsKey(s), "InvalidPreprocessing", "unknown directive alias");
        directive = new Ref(plainId(c.directiveAliases.get(s)));
      }
      d = open(normalize(directive, c, "exact", false), c);
      require(
          Set.of("object", "none").contains(d.kind())
              && Set.of("imports", "transformations", "type", "name", "description")
                  .containsAll(d.fields().keySet()),
          "InvalidPreprocessing",
          "directive fields");
    }
    var bindings = new LanguageMap<>(c.aliases);
    if (d.has("imports")) {
      Value imports = open((ExactNode) d.get("imports"), c);
      require(
          imports.kind().equals("object")
              && Collections.disjoint(imports.fields().keySet(), RESERVED),
          "InvalidPreprocessing",
          "imports");
      for (var e : imports.fields().entrySet()) {
        require(e.getValue() instanceof Ref, "InvalidPreprocessing");
        String id = ((Ref) e.getValue()).id();
        require(
            !bindings.containsKey(e.getKey()) || bindings.get(e.getKey()).equals(id),
            "InvalidPreprocessing",
            "alias collision");
        bindings.put(e.getKey(), id);
      }
    }
    Object s = LanguageValues.copy(root);
    if (s instanceof Map<?, ?>) {
      map(s).remove("blue");
    }
    if (d.has("transformations")) {
      Value ts = open((ExactNode) d.get("transformations"), c);
      require(
          ts.fields().keySet().equals(Set.of("items")),
          "InvalidPreprocessing",
          "transformation sequence");
      for (ExactNode e : exactItems(ts.get("items"))) {
        Value t = open(e, c);
        require(t.get("type") instanceof Ref, "InvalidPreprocessing", "exact transformation type");
        String tid = ((Ref) t.get("type")).id();
        var allowed = new HashSet<>(Set.of("type", "name", "description"));
        if (INFER.contains(tid)) {
          s = walk(s, Map.of(), true, false, false, c);
        } else if (ALIAS.contains(tid)) {
          allowed.add("mappings");
          Value mappings = open((ExactNode) t.get("mappings"), c);
          require(
              mappings.kind().equals("object")
                  && Collections.disjoint(mappings.fields().keySet(), RESERVED),
              "InvalidPreprocessing",
              "mappings");
          var mapping = new HashMap<String, String>();
          for (var m : mappings.fields().entrySet()) {
            String id = finalId(configText(m.getValue(), c));
            require(
                !CORE.containsKey(m.getKey()) || CORE.get(m.getKey()).equals(id),
                "InvalidPreprocessing",
                "core alias rebind");
            mapping.put(m.getKey(), id);
          }
          s = substitute(s, mapping);
        } else if (tid.equals(RENAME)) {
          allowed.addAll(Set.of("from", "to"));
          String from = configText(t.get("from"), c), to = configText(t.get("to"), c);
          require(
              s instanceof Map && map(s).containsKey(from) && !map(s).containsKey(to),
              "InvalidPreprocessing",
              "rename");
          map(s).put(to, map(s).remove(from));
        } else if (tid.equals(APPEND)) {
          allowed.addAll(Set.of("field", "suffix"));
          String field = configText(t.get("field"), c), suffix = configText(t.get("suffix"), c);
          require(
              s instanceof Map && map(s).containsKey(field), "InvalidPreprocessing", "append Text");
          Object v = map(s).get(field);
          if (v instanceof Map) {
            Map<String, Object> vm = map(v);
            require(
                Set.of("type", "value").containsAll(vm.keySet())
                    && vm.get("value") instanceof String,
                "InvalidPreprocessing",
                "Text source");
            require(
                !vm.containsKey("type")
                    || vm.get("type").equals("Text")
                    || vm.get("type").equals(Map.of("blueId", CORE.get("Text"))),
                "InvalidPreprocessing");
            vm.put("value", vm.get("value") + suffix);
          } else {
            require(v instanceof String, "InvalidPreprocessing");
            map(s).put(field, v + suffix);
          }
        } else if (tid.equals(SET)) {
          allowed.addAll(Set.of("field", "expression", "format"));
          require(
              t.has("field") && t.has("expression") && s instanceof Map, "InvalidPreprocessing");
          String target = configText(t.get("field"), c),
              format = t.has("format") ? configText(t.get("format"), c) : "json";
          require(
              !target.equals("blue") && Set.of("json", "yaml").contains(format),
              "InvalidPreprocessing");
          Object expression = LanguageCodec.parse(configText(t.get("expression"), c), format);
          require(!hasBlue(expression), "InvalidPreprocessing");
          map(s).put(target, expression);
        } else {
          throw new LanguageFailure("UnsupportedPreprocessingTransform", tid);
        }
        require(
            allowed.containsAll(t.fields().keySet()),
            "InvalidPreprocessing",
            "transformation fields");
        require(!hasBlue(s), "InvalidPreprocessing", "introduced blue");
      }
    }
    return walk(s, bindings, true, true, false, c);
  }

  static boolean hasBlue(Object x) {
    if (x instanceof Map<?, ?> m) {
      if (m.containsKey("blue")) {
        return true;
      }
      for (var e : m.entrySet()) {
        if (!LITERALS.contains(e.getKey())
            && !Set.of("mergePolicy", "blueId").contains(e.getKey())
            && hasBlue(e.getValue())) {
          return true;
        }
      }
    }
    if (x instanceof List<?> a) {
      for (Object v : a) {
        if (hasBlue(v)) {
          return true;
        }
      }
    }
    return false;
  }

  static Object substitute(Object x, Map<String, String> mappings) {
    if (x instanceof ExactNode) {
      return x;
    }
    if (x instanceof List<?> a) {
      return a.stream().map(v -> substitute(v, mappings)).toList();
    }
    if (!(x instanceof Map) || map(x).containsKey("blueId")) {
      return x;
    }
    var out = new TreeMap<String, Object>();
    map(x)
        .forEach(
            (k, v) ->
                out.put(
                    k,
                    TYPES.contains(k) && v instanceof String s && mappings.containsKey(s)
                        ? Map.of("blueId", mappings.get(s))
                        : LITERALS.contains(k) || Set.of("mergePolicy", "$pos").contains(k)
                            ? v
                            : substitute(v, mappings)));
    return out;
  }

  static Head inherited(Map<String, Object> s, Supplier<Recipe> lazy, LanguageContext c) {
    Recipe selected = lazy == null ? null : lazy.get();
    Head base = selected == null ? new Head() : head(selected, c);
    if (s.containsKey("type")) {
      ExactNode r = normalize(s.get("type"), c, "exact", false);
      base = merge(base, requirement(identity(r), c), c);
    }
    var headers = new TreeMap<String, Object>();
    for (String k : Set.of("itemType", "keyType", "valueType", "schema", "mergePolicy")) {
      if (s.containsKey(k)) {
        headers.put(k, s.get(k));
      }
    }
    if (!headers.isEmpty()) {
      Head h = single(normalize(headers, c, "exact", false), c);
      if (h.kind.equals("none")) {
        var draft = h.copy();
        draft.kind = base.kind;
        h = draft.build();
      }
      base = merge(base, h, c);
    }
    return base;
  }

  static Supplier<Recipe> childContext(
      Map<String, Object> s, Supplier<Recipe> lazy, Object k, LanguageContext c) {
    return () -> {
      Head h = inherited(s, lazy, c);
      if (k.equals("contracts")) {
        return h.contracts;
      }
      Recipe result = child(h, k);
      if (k instanceof Integer && result != null && !"append-only".equals(h.policy)) {
        return recipeWith(
            result.requirements(), result.parts().stream().filter(n -> !hole(n)).toArray());
      }
      return result;
    };
  }

  record Edit(int index, boolean overlay, Object value) {}

  record Controls(String anchor, Boolean previous, List<Edit> edits, List<Object> values) {}

  static String anchor(Object value) {
    try {
      return plainId(value);
    } catch (LanguageFailure f) {
      if (!f.category().equals("InvalidBlueId")) {
        throw f;
      }
      throw new LanguageFailure("ListControlViolation", "anchor requires a plain valid BlueId");
    }
  }

  static Controls controls(List<Object> items) {
    String anchor = null;
    Boolean previous = null;
    var edits = new ArrayList<Edit>();
    var values = new ArrayList<Object>();
    int position = 0;
    var indices = new HashSet<Integer>();
    for (Object x : items) {
      if (x instanceof Map<?, ?> m
          && (m.containsKey("$previous") || m.containsKey("$previousItems"))) {
        require(
            position == 0 && anchor == null && previous == null && m.size() == 1,
            "ListControlViolation",
            "prefix assertion must be first and unique");
        if (m.containsKey("$previousItems")) {
          require(m.get("$previousItems") instanceof Boolean, "ListControlViolation");
          previous = (Boolean) m.get("$previousItems");
        } else {
          require(
              m.get("$previous") instanceof Map<?, ?> a && a.keySet().equals(Set.of("blueId")),
              "ListControlViolation");
          anchor = anchor(map(m.get("$previous")).get("blueId"));
        }
      } else if (x instanceof Map<?, ?> m && m.containsKey("$pos")) {
        Object pos = m.get("$pos");
        require(
            pos instanceof java.math.BigInteger || pos instanceof NumberToken nt && !nt.decimal(),
            "ListControlViolation",
            "Integer index token");
        int i;
        try {
          i = Integer.parseInt(pos instanceof NumberToken nt ? nt.text() : pos.toString());
        } catch (NumberFormatException ex) {
          throw new LanguageFailure("ListControlViolation", "index");
        }
        require(i >= 0 && indices.add(i), "ListControlViolation", "duplicate or invalid index");
        if (m.containsKey("$replace")) {
          require(m.keySet().equals(Set.of("$pos", "$replace")), "ListControlViolation");
          edits.add(new Edit(i, false, m.get("$replace")));
        } else {
          var o = new TreeMap<>(map(x));
          o.remove("$pos");
          require(Collections.disjoint(o.keySet(), CONTROLS), "ListControlViolation");
          edits.add(new Edit(i, !o.containsKey("value"), o));
        }
      } else {
        require(
            !(x instanceof Map<?, ?> m) || Collections.disjoint(m.keySet(), CONTROLS),
            "ListControlViolation",
            "malformed control");
        values.add(x);
      }
      position++;
    }
    require(
        edits.isEmpty() || values.isEmpty() && anchor == null && previous == null,
        "ListControlViolation",
        "mixed edits and elements");
    return new Controls(anchor, previous, edits, values);
  }

  static ExactNode prepare(Object root, LanguageContext c) {
    return LanguageFailure.guard(() -> prepareUnchecked(root, c));
  }

  private static ExactNode prepareUnchecked(Object root, LanguageContext c) {
    Object prepared =
        LanguageDiagnostics.at(c, c.diagnosticPath, "preprocessing", () -> preprocess(root, c));
    return LanguageDiagnostics.at(
        c, c.diagnosticPath, "source-construction", () -> compile(prepared, null, c));
  }

  static ExactNode compile(Object raw, Supplier<Recipe> lazy, LanguageContext c) {
    c.step();
    if (raw instanceof ExactNode e) {
      return e;
    }
    if (raw instanceof Map<?, ?> m && m.containsKey("blueId")) {
      return normalize(raw, c, "exact", false);
    }
    if (raw instanceof List) {
      raw = Map.of("items", raw);
    }
    if (!(raw instanceof Map)) {
      raw = Map.of("value", raw);
    }
    var s = new TreeMap<>(map(raw));
    for (String k : new TreeSet<>(s.keySet())) {
      if (TYPES.contains(k) || k.equals("specification")) {
        ExactNode d = LanguageDiagnostics.child(c, k, () -> compile(s.get(k), null, c));
        c.remember(d);
        s.put(k, new Ref(identity(d)));
      }
    }
    if (c.diagnosticPath.isEmpty()) {
      var metadata = new TreeMap<String, Object>();
      s.forEach(
          (k, v) -> {
            if (LABELS.contains(k) || TYPES.contains(k) && v instanceof Ref) {
              metadata.put(k, v);
            }
          });
      c.diagnosticRoot = ExactNode.value(metadata);
    }
    if (s.containsKey("items")) {
      List<Object> authored = list(s.get("items"));
      Head base = inherited(s, lazy, c);
      require(
          Set.of("none", "list").contains(base.kind),
          "TypeCompatibilityViolation",
          "items on non-list");
      String policy = base.policy == null ? "positional" : base.policy;
      List<ExactNode> elements =
          base.items == null ? List.of() : base.items.stream().map(r -> exact(r, c)).toList();
      Controls control = controls(authored);
      var payload = new ArrayList<ExactNode>();
      if (policy.equals("append-only")) {
        require(control.edits.isEmpty(), "ListControlViolation", "append-only edit");
        require(
            control.anchor != null || control.previous != null,
            "ListControlViolation",
            "append-only items require prefix assertion");
        if (control.previous != null) {
          require(
              control.previous == !elements.isEmpty(),
              "ListControlViolation",
              "previousItems assertion mismatch");
        }
        if (control.anchor != null) {
          require(
              base.items != null && listId(elements, null).equals(control.anchor),
              "ListControlViolation",
              "anchor mismatch");
        }
        payload.addAll(elements);
        for (Object value : control.values) {
          int index = payload.size();
          payload.add(
              LanguageDiagnostics.child(
                  c,
                  "items",
                  () ->
                      LanguageDiagnostics.child(
                          c, index, () -> compile(value, childContext(s, lazy, index, c), c))));
        }
      } else {
        require(
            control.anchor == null && control.previous == null,
            "ListControlViolation",
            "prefix assertions require append-only policy");
        if (!control.edits.isEmpty()) {
          payload.addAll(elements);
          var edits = new ArrayList<>(control.edits);
          edits.sort(Comparator.comparingInt(Edit::index));
          for (Edit edit : edits) {
            require(edit.index < elements.size(), "ListControlViolation", "index");
            ExactNode edited =
                LanguageDiagnostics.child(
                    c,
                    "items",
                    () ->
                        LanguageDiagnostics.child(
                            c,
                            edit.index,
                            () -> {
                              ExactNode operand =
                                  compile(edit.value, childContext(s, lazy, edit.index, c), c);
                              if (edit.overlay) {
                                require(
                                    hole(elements.get(edit.index))
                                        || Set.of("object", "none")
                                                .contains(open(elements.get(edit.index), c).kind())
                                            && Set.of("object", "none")
                                                .contains(open(operand, c).kind()),
                                    "ListControlViolation",
                                    "map overlay needs object operands");
                              }
                              return refine(elements.get(edit.index), operand, c, true);
                            }));
            payload.set(edit.index, edited);
          }
        } else {
          require(
              control.values.size() >= elements.size(),
              "FixedValueConflict",
              "list prefix removed");
          for (int i = 0; i < control.values.size(); i++) {
            int index = i;
            payload.add(
                LanguageDiagnostics.child(
                    c,
                    "items",
                    () ->
                        LanguageDiagnostics.child(
                            c,
                            index,
                            () -> {
                              ExactNode operand =
                                  compile(
                                      control.values.get(index),
                                      childContext(s, lazy, index, c),
                                      c);
                              return index < elements.size()
                                  ? refine(elements.get(index), operand, c, true)
                                  : operand;
                            })));
          }
        }
      }
      s.put("items", payload);
    }
    for (String k : new TreeSet<>(s.keySet())) {
      if (!TYPES.contains(k)
          && !LITERALS.contains(k)
          && !Set.of("mergePolicy", "items").contains(k)) {
        s.put(
            k,
            LanguageDiagnostics.child(
                c, k, () -> compile(s.get(k), childContext(s, lazy, k, c), c)));
      }
    }
    return normalize(s, c, "exact", false);
  }
}
