package blue;

import static blue.ContractLanguage.LITERALS;
import static blue.ContractLanguage.TYPES;
import static blue.ContractLanguage.identity;
import static blue.ContractModel.m;
import static blue.ContractModel.pointer;
import static blue.ContractModel.prefix;
import static blue.ContractModel.require;
import static blue.ContractModel.segments;
import static blue.LanguageCore.exactItems;

import blue.ContractModel.Meter;
import blue.ExactNode.Ref;
import blue.ExactNode.Value;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

final class Generalization {
  static final Set<String> SEMANTIC =
      Set.of("TypeCompatibilityViolation", "SchemaViolation", "FixedValueConflict");

  record Rule(String path, String mode, String floor) {
    Rule {
      require(
          !segments(path).contains("") && Set.of("reject", "nearest-valid-ancestor").contains(mode),
          "INVALID_GENERALIZATION_POLICY");
    }
  }

  record Policy(List<Rule> rules, String defaultMode, String identity) {
    Policy {
      rules = List.copyOf(rules);
      require(
          Set.of("reject", "nearest-valid-ancestor").contains(defaultMode),
          "INVALID_GENERALIZATION_POLICY");
    }

    Rule select(String path) {
      Rule selected = new Rule("", defaultMode, null);
      int depth = -1;
      for (Rule r : rules)
        if (prefix(r.path, path) && segments(r.path).size() > depth) {
          selected = r;
          depth = segments(r.path).size();
        }
      return selected;
    }
  }

  record Repair(ExactNode body, List<Map<String, Object>> writes) {}

  static ExactNode direct(ContractBridge blue, ExactNode n, List<String> parts) {
    for (String k : parts) {
      Value b = blue.open(n);
      if (b.has("items")) {
        var items = exactItems(b.get("items"));
        if (!k.matches("[0-9]+") || Integer.parseInt(k) >= items.size()) return null;
        n = items.get(Integer.parseInt(k));
      } else {
        Object v = b.get(k);
        if (!(v instanceof ExactNode)) return null;
        n = (ExactNode) v;
      }
    }
    return n;
  }

  static ExactNode replace(ContractBridge blue, ExactNode n, List<String> parts, ExactNode value) {
    if (parts.isEmpty()) return value;
    Value b = blue.open(n);
    var fields = new TreeMap<>(b.fields());
    String k = parts.getFirst();
    if (b.has("items")) {
      var items = new ArrayList<>(exactItems(b.get("items")));
      int i = Integer.parseInt(k);
      items.set(i, replace(blue, items.get(i), parts.subList(1, parts.size()), value));
      fields.put("items", List.copyOf(items));
    } else
      fields.put(
          k, replace(blue, (ExactNode) fields.get(k), parts.subList(1, parts.size()), value));
    return ExactNode.value(fields);
  }

  static void affected(
      ContractBridge blue,
      ExactNode n,
      ExactNode prior,
      List<String> at,
      List<List<String>> paths) {
    if (n == null || prior != null && identity(n).equals(identity(prior))) return;
    if (n instanceof Value b) {
      Value old = prior == null ? null : blue.open(prior);
      for (var e : b.fields().entrySet()) {
        String k = e.getKey();
        Object v = e.getValue();
        if (k.equals("items")) {
          var items = exactItems(v);
          var before =
              old != null && old.get(k) instanceof List
                  ? exactItems(old.get(k))
                  : List.<ExactNode>of();
          for (int i = 0; i < items.size(); i++) {
            var p = new ArrayList<>(at);
            p.add("" + i);
            affected(blue, items.get(i), i < before.size() ? before.get(i) : null, p, paths);
          }
        } else if (!LITERALS.contains(k)
            && !TYPES.contains(k)
            && !Set.of("schema", "mergePolicy", "contracts").contains(k)) {
          var p = new ArrayList<>(at);
          p.add(k);
          affected(blue, (ExactNode) v, old == null ? null : (ExactNode) old.get(k), p, paths);
        }
      }
    }
    paths.add(at);
  }

  static Repair repair(
      ContractBridge blue,
      ExactNode before,
      ExactNode changed,
      String path,
      Policy policy,
      Meter meter,
      Consumer<ExactNode> validate,
      Consumer<ExactNode> establish) {
    ContractModel.Error original;
    try {
      validate.accept(changed);
      return new Repair(changed, List.of());
    } catch (ContractModel.Error e) {
      if (!e.code.startsWith("LANGUAGE_") || !SEMANTIC.contains(e.code.substring(9))) throw e;
      original = e;
    }
    var parts = segments(path);
    var paths = new ArrayList<List<String>>();
    var writes = new ArrayList<Map<String, Object>>();
    affected(blue, direct(blue, changed, parts), direct(blue, before, parts), parts, paths);
    for (int size = parts.size() - 1; size >= 0; size--) paths.add(parts.subList(0, size));
    for (var at : paths) {
      ExactNode n = direct(blue, changed, at);
      if (n == null) continue;
      Value body = blue.open(n);
      if (!(body.get("type") instanceof Ref typ)) continue;
      Rule rule = policy.select(pointer(at));
      if (rule.mode.equals("reject")) continue;
      String candidateId = typ.id();
      Set<String> seen = new HashSet<>();
      ExactNode valid = null;
      String chosen = null;
      while (candidateId != null) {
        require(seen.add(candidateId), "CYCLIC_TYPE_ANCESTRY");
        meter.contracts("subtypeCandidateTested");
        boolean floor =
            rule.floor == null || ContractLanguage.subtype(candidateId, rule.floor, blue.context);
        if (floor) {
          ExactNode candidate = replace(blue, changed, at, body.with("type", new Ref(candidateId)));
          try {
            establish.accept(candidate);
            ContractLanguage.validate(blue.select(candidate, pointer(at)), blue.context, "value");
            for (int size = at.size() - 1; size >= 0; size--) {
              var head = blue.head(blue.select(candidate, pointer(at.subList(0, size))));
              blue.context.validateConstraints(head, size == 0);
            }
            valid = candidate;
            chosen = candidateId;
            break;
          } catch (LanguageFailure e) {
            if (!SEMANTIC.contains(e.category())) throw blue.translate(e);
          } catch (ContractModel.Error e) {
            if (!e.code.startsWith("LANGUAGE_") || !SEMANTIC.contains(e.code.substring(9))) throw e;
          }
        }
        require(floor, "TYPE_GENERALIZATION_FLOOR", "path", pointer(at));
        Object parent = blue.open(new Ref(candidateId)).get("type");
        candidateId = parent instanceof Ref r ? r.id() : null;
        if (candidateId != null) meter.contracts("typeEdgeFollowed");
      }
      require(valid != null, "TYPE_GENERALIZATION_FAILURE", "path", pointer(at));
      if (!chosen.equals(typ.id())) {
        changed = valid;
        var p = new ArrayList<>(at);
        p.add("type");
        writes.add(m("path", pointer(p), "before", typ, "after", new Ref(chosen)));
      }
    }
    if (writes.isEmpty()) throw original;
    validate.accept(changed);
    return new Repair(changed, writes);
  }
}
