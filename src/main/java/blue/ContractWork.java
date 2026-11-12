package blue;

import static blue.ContractLanguage.*;
import static blue.ContractModel.*;
import static blue.ExactNode.*;

import blue.LanguageModel.*;
import java.math.*;
import java.util.*;
import java.util.function.IntFunction;

/** Lazy exact-content operations; representation work does not consume semantic gas. */
@SuppressWarnings({"unchecked", "serial", "try"})
final class ContractWork {
  final ContractBridge blue;
  final Meter meter;
  private final NavigableMap<String, ContractCanonical.Fragment> establishedRows = new TreeMap<>();
  final ContractInventory manifests = new ContractInventory(),
      established = new ContractInventory(establishedRows, k -> List.of("ordinary", k), false),
      cyclicEstablished = new ContractInventory(establishedRows, ContractWire::json, false),
      proofs =
          new ContractInventory(
              k -> List.of(ContractWire.json(k.substring(0, k.length() - 6)), "value")),
      carriedRoots = new ContractInventory(),
      boundIdentities = new ContractInventory();

  record ExactEvidence(ExactNode node, String identity) {}

  private final Map<String, ExactEvidence> exactValues = new HashMap<>();
  private final NavigableMap<String, ContractCanonical.Fragment> exactRows = new TreeMap<>();

  ContractCanonical.Sequence exactEncoding() {
    return new ContractCanonical.Sequence(exactRows.values());
  }

  void copyExactValues(ContractWork source) {
    exactValues.putAll(source.exactValues);
    exactRows.putAll(source.exactRows);
  }

  private ContractLanguage.Witnesses rootWitnessScope;
  private TopologyMap<String, Document> rootWitnesses;

  /** Returns physical roots visited; immutable snapshots let later local turns note only edits. */
  int noteRoots(TopologyMap<String, Document> documents) {
    var scope = blue.activeWitnesses();
    Collection<String> selected =
        rootWitnesses == null
                || rootWitnessScope != scope
                || Boolean.getBoolean("blue.physical.nativeDisabled")
            ? documents.keySet()
            : documents.changedValueKeys(rootWitnesses);
    int visited = 0;
    for (String key : selected) {
      Document document = documents.get(key);
      if (document != null && !document.blueId.isEmpty()) {
        blue.note(document.body, document.blueId);
        visited++;
      }
    }
    rootWitnessScope = scope;
    rootWitnesses = new TopologyMap<>(true, ContractCanonical.CODE_POINTS, Document::sameMetadata);
    rootWitnesses.copyFrom(documents);
    ContractMetrics.add("contracts.rootWitness.visits", visited);
    return visited;
  }

  private List<List<String>> partition;
  private ContractCanonical.Fragment partitionEncoding = new ContractCanonical.Fragment(null);

  List<List<String>> partition() {
    return partition;
  }

  void partition(List<List<String>> value) {
    if (partition == value) return;
    partition = value == null ? null : value.stream().map(List::copyOf).toList();
    partitionEncoding = new ContractCanonical.Fragment(partition);
  }

  ContractCanonical.Fragment partitionEncoding() {
    return partitionEncoding;
  }

  ContractWork(ContractBridge blue, Meter meter) {
    this.blue = blue;
    this.meter = meter;
  }

  ContractLanguage captured(ContractBridge source) {
    // Composed values retain the selected-child witnesses of their captured view.
    // The logical ledger is shared; physical resolver caches are not evidence.
    if (!meter.deterministic)
      return source.context.captured(n -> establishCaptured(n, source), source::note);
    var pending = new ArrayList<Ref>();
    return source.context.captured(
        n -> {
          if (n instanceof Ref ref) {
            pending.add(ref);
            return ref.id();
          }
          // Every constructor has already built inline slots and passes exact
          // reference tokens. Preserve duplicate occurrences in this inventory.
          pending.sort(Comparator.comparing(Ref::id));
          for (Ref ref : pending) establishCaptured(ref, source);
          pending.clear();
          return establishCaptured(n, source);
        },
        (n, id) -> {
          source.note(n, id);
          // Python's copied construction context shares its body map. Retain the
          // same prepaid body here so a following type-slot Ref can resolve it.
          source.context.remember(n);
        });
  }

  ExactNode exact(Recipe r, ContractBridge b) {
    if (r.parts().size() == 1) return r.parts().getFirst();
    var evidence =
        exactValues.computeIfAbsent(
            r.key(),
            k -> {
              ExactNode node = ContractLanguage.exact(r, captured(b));
              var value = new ExactEvidence(node, b.known(node));
              var row = Arrays.asList(ContractWire.json(k), value.identity());
              exactRows.put(ContractWire.write(row), new ContractCanonical.Fragment(row));
              return value;
            });
    // A new ExactView has its own witness snapshot. Carry the prepaid construction
    // identity into that view on every reuse, as required by the pinned tariff.
    if (evidence.node() instanceof Value) b.note(evidence.node(), evidence.identity());
    return evidence.node();
  }

  Value open(ExactNode n) {
    return open(n, blue);
  }

  Value open(ExactNode n, ContractBridge source) {
    String id = identity(n);
    if (meter.calculation != null) meter.calculation.manifest(id);
    if (manifests.add(id)) meter.contracts("nodeManifestOpened");
    // Charge once per logical operation, but keep read evidence in the view's
    // captured scope. Reading an old view must not warm the current root scope.
    return source.open(n);
  }

  int compare(Object left, Object right) {
    meter.contracts("scalarComparison");
    if (left instanceof Boolean a && right instanceof Boolean b) return Boolean.compare(a, b);
    if (left instanceof String a && right instanceof String b) {
      return MeteredText.compare(
          a,
          b,
          () -> {
            meter.contracts("textBlockExamined");
            meter.contracts("textBlockExamined");
          });
    }
    if (left instanceof BigInteger || left instanceof Integer || left instanceof Long) {
      if (right instanceof BigInteger || right instanceof Integer || right instanceof Long)
        meter.contracts(
            "integerLimbOperation",
            BexValues.limbs(BexValues.integer(left)) + BexValues.limbs(BexValues.integer(right)));
    }
    return BexValues.number(left).compareTo(BexValues.number(right));
  }

  List<Map.Entry<String, Map<String, Object>>> ordered(Map<String, Map<String, Object>> contracts) {
    var rows = new ArrayList<>(ContractCanonical.map(contracts).entrySet());
    for (int width = 1; width < rows.size(); width *= 2) {
      var out = new ArrayList<Map.Entry<String, Map<String, Object>>>();
      for (int start = 0; start < rows.size(); start += 2 * width) {
        int i = start,
            j = Math.min(start + width, rows.size()),
            a = j,
            b = Math.min(start + 2 * width, rows.size());
        while (i < a && j < b) {
          var left = rows.get(i);
          var right = rows.get(j);
          meter.contracts("sortComparison");
          int cmp =
              compare(
                  left.getValue().getOrDefault("order", 0),
                  right.getValue().getOrDefault("order", 0));
          if (cmp == 0) cmp = compare(left.getKey(), right.getKey());
          if (cmp == 0)
            cmp =
                compare(
                    obj(left.getValue().get("type")).get("blueId"),
                    obj(right.getValue().get("type")).get("blueId"));
          if (cmp <= 0) out.add(rows.get(i++));
          else out.add(rows.get(j++));
        }
        while (i < a) out.add(rows.get(i++));
        while (j < b) out.add(rows.get(j++));
      }
      rows = out;
    }
    return rows;
  }

  void headers(ExactNode body, ContractPreparations preparations) {
    var prepared = preparations == null ? null : preparations.get(body);
    if (prepared == null || prepared.headers() == null) {
      headers(body);
      return;
    }
    for (Object raw : prepared.headers()) {
      var row = arr(raw);
      if (manifests.add(str(row.getFirst()))) meter.contracts("nodeManifestOpened");
      if (row.get(1) != null) {
        if (manifests.add(str(row.get(1)))) meter.contracts("nodeManifestOpened");
        meter.contracts("objectMemberRead", integer(row.get(2)));
      }
      if (Boolean.TRUE.equals(row.get(3))) meter.contracts("typeEdgeFollowed");
    }
  }

  void headers(ExactNode body) {
    var seen = new HashSet<String>();
    ExactNode n = body;
    while (n != null && seen.add(identity(n))) {
      Value b = open(n);
      if (b.has("contracts"))
        meter.contracts("objectMemberRead", open((ExactNode) b.get("contracts")).fields().size());
      n = (ExactNode) b.get("type");
      if (n != null) meter.contracts("typeEdgeFollowed");
    }
  }

  Object read(ExactNode n, String path) {
    Recipe r = recipe(n);
    for (String key : segments(path)) {
      for (ExactNode p : r.parts()) open(p);
      Head h = blue.head(r);
      meter.contracts(h.items != null ? "listItemRead" : "objectMemberRead");
      r = blue.child(r, key);
      if (r == null) break;
    }
    return blue.view(r, this);
  }

  String establish(ExactNode n) {
    return establish(n, null, null, null);
  }

  String establish(ExactNode n, ExactNode previous) {
    return establish(n, previous, null, null);
  }

  String establishCaptured(ExactNode n, ContractBridge source) {
    return establish(n, null, null, null, source);
  }

  String establish(ExactNode n, ExactNode previous, IntFunction<String> token, Object scope) {
    return establish(n, previous, token, scope, blue);
  }

  String establish(
      ExactNode n,
      ExactNode previous,
      IntFunction<String> token,
      Object scope,
      ContractBridge source) {
    String id = token == null ? identity(n) : identity(n, token);
    if (n instanceof Value && token == null) source.note(n, id);
    return id;
  }

  String nodeBlueId(Object v, BexGas gas) {
    require(gas == meter, "IDENTITY_METER_MISMATCH");
    if (v instanceof ContractBridge.ExactView view) {
      ExactNode n = exact(view.recipe, view.bridge);
      String known = view.bridge.known(n);
      return known == null ? establishCaptured(n, view.bridge) : known;
    }
    return identity(node(identityValue(v), "value", true));
  }

  Object identityValue(Object value) {
    if (value instanceof ContractBridge.ExactView) return value;
    if (value instanceof Map<?, ?>) {
      var result = new TreeMap<String, Object>();
      for (String key : BexValues.keys(value)) {
        Object child = BexValues.get(value, key);
        if (child != BexValues.UNDEFINED) result.put(key, identityValue(child));
      }
      return result;
    }
    if (value instanceof List<?> list) return list.stream().map(this::identityValue).toList();
    return BexValues.blueValue(value);
  }

  Object literal(Object value) {
    if (value instanceof String s) meter.contracts("identityInputTextBlock", BexValues.chunks(s));
    else if (value instanceof BigInteger || value instanceof Integer || value instanceof Long) {
      long limbs = BexValues.limbs(BexValues.integer(value));
      meter.contracts("integerLimbOperation", limbs * limbs);
    }
    return value;
  }

  ExactNode build(Object value, ContractLanguage c) {
    meter.contracts("identityInputMember");
    if (value instanceof ContractBridge.ExactView v) {
      ExactNode node = exact(v.recipe, v.bridge);
      // ExactView owns a captured witness map. Transport its admission evidence
      // with the value; the destination must not rediscover it from cache warmth.
      String id = v.bridge.known(node);
      if (id != null) blue.note(node, id);
      value = node;
    }
    if (value instanceof ExactNode n) {
      establish(n);
      return n;
    }
    if (value instanceof BexValues.HostValue h) value = h.blueOutput();
    String kind = BexValues.kind(value);
    Object raw;
    if (kind.equals("object")) {
      int count = BexValues.size(value),
          rounds = count == 0 ? 0 : 32 - Integer.numberOfLeadingZeros(count - 1);
      meter.contracts("identityInputMember", count);
      var keys = BexValues.keys(value);
      meter.contracts("identityInputMember", (long) count * rounds);
      meter.contracts(
          "identityInputTextBlock",
          keys.stream().mapToLong(BexValues::chunks).sum() * (rounds + 1));
      keys.sort(String::compareTo);
      var out = new TreeMap<String, Object>();
      for (String key : keys) {
        Object part = BexValues.get(value, key);
        if (part == BexValues.UNDEFINED) continue;
        if (LITERALS.contains(key) || key.equals("mergePolicy") || key.equals("blueId")) {
          out.put(key, literal(part));
          if (key.equals("value"))
            out.put(key, map(blue.input(Map.of("value", out.get(key)), false)).get(key));
        } else if (key.equals("items")) {
          meter.contracts("identityInputMember", BexValues.size(part));
          var items = new ArrayList<ExactNode>();
          for (int i = 0; i < BexValues.size(part); i++)
            items.add(build(BexValues.get(part, i), c));
          out.put(key, items);
        } else out.put(key, part == null ? null : build(part, c));
      }
      for (String key : List.of("itemType", "keyType", "type", "valueType"))
        if (BexValues.get(value, key) instanceof String s)
          require(false, "UNRESOLVED_RUNTIME_TYPE", "alias", s);
      raw = out;
    } else if (kind.equals("list")) {
      meter.contracts("identityInputMember", BexValues.size(value));
      var items = new ArrayList<ExactNode>();
      for (int i = 0; i < BexValues.size(value); i++) items.add(build(BexValues.get(value, i), c));
      raw = Map.of("items", items);
    } else raw = blue.input(literal(value), false);
    if (meter.deterministic && raw instanceof Map<?, ?>) {
      var normalized = new TreeMap<>(map(raw));
      for (String key : List.of("itemType", "keyType", "specification", "type", "valueType")) {
        if (normalized.get(key) instanceof Value valueNode) {
          String id = blue.known(valueNode);
          require(id != null, "MISSING_CONSTRUCTION_EVIDENCE");
          normalized.put(key, new Ref(id));
        }
      }
      raw = normalized;
    }
    return normalize(raw, c, "exact", false);
  }

  ExactNode node(Object value, String goal, boolean certify) {
    try {
      ExactNode n = build(value, captured(blue));
      if (certify) {
        if (goal.equals("value")) validate(n, null);
        else blue.certify(n, goal);
      }
      blue.remember(n);
      return n;
    } catch (LanguageFailure e) {
      throw blue.translate(e);
    }
  }

  void validate(ExactNode n, ExactNode previous) {
    blue.context.inheritUnchangedValidation(n, previous);
    blue.certify(n);
  }
}
