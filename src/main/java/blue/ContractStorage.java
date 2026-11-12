package blue;

import static blue.ContractLanguage.finalId;
import static blue.ContractModel.SAFE;
import static blue.ContractModel.arr;
import static blue.ContractModel.closed;
import static blue.ContractModel.digest;
import static blue.ContractModel.integer;
import static blue.ContractModel.m;
import static blue.ContractModel.obj;
import static blue.ContractModel.require;
import static blue.ContractModel.str;
import static blue.ContractModel.token;

import blue.ContractModel.Document;
import blue.ContractModel.Edge;
import blue.ContractModel.Life;
import blue.ContractModel.Snapshot;
import blue.ExactNode.Ref;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Cold snapshot restoration validates commitments and exact dependencies before publication. */
final class ContractStorage {
  /** In-process maintenance over a completed snapshot; this is never an import boundary. */
  static Snapshot compact(ContractProcessor source, ContractProcessor target, Snapshot selected) {
    source.validate(selected, true);
    var roots = new ArrayList<ExactNode>();
    for (Document document : selected.documents.values()) roots.add(document.body);
    for (Object raw : selected.runtimes)
      for (String field : List.of("type_id", "semantics_id"))
        roots.add(new Ref(str(obj(raw).get(field))));
    source.blue.context.copyReachableTo(target.blue.context, roots);

    // Document.copy() also preserves resolver-backed declaration caches. A maintenance boundary
    // rebuilds only canonical metadata so those views cannot retain the discarded history.
    Snapshot result = new Snapshot(selected.root);
    result.release = selected.release;
    result.gasSchedule = selected.gasSchedule;
    result.generation = selected.generation;
    result.revision = selected.revision;
    result.runtimes = new ArrayList<>(selected.runtimes);
    for (Document document : selected.documents.values()) {
      Document copy =
          new Document(
              document.key,
              new Ref(document.blueId),
              document.blueId,
              document.birth,
              document.channelLives,
              document.channelSerials,
              document.edgeSerials);
      result.documents.put(copy.key, copy);
    }
    result.edges.putAll(selected.edges);
    require(result.identity().equals(selected.identity()), "INVALID_SNAPSHOT_COMMITMENT");
    target.validate(result, true);
    target.blue.refresh();
    target.archive.add(result);
    return result;
  }

  /** A protected host publication supplies prior completion, not portable verification. */
  static String completedMember(
      Map<String, Object> envelope,
      String expected,
      String member,
      ContractsEdition edition,
      GasSchedule schedule) {
    closed(envelope, Set.of("snapshot", "identity", "content"), Set.of());
    require(expected.equals(envelope.get("identity")), "UNTRUSTED_SNAPSHOT_COMMITMENT");
    // Own metadata before hashing and selection. Large portable bodies are deliberately not copied
    // or installed; the caller's retained provider owns exact content and cyclic proof
    // verification.
    var wire = obj(ContractWire.immutable(envelope.get("snapshot")));
    require(
        digest("contracts.snapshot/completion-1", wire).equals(expected),
        "INVALID_SNAPSHOT_COMMITMENT");
    closed(
        wire,
        Set.of("root", "documents", "edges", "generation", "revision", "release", "runtimes"),
        Set.of("gasSchedule"));
    require(edition.specificationBlueId().equals(wire.get("release")), "INCOMPATIBLE_RELEASE");
    require(schedule.id().equals(wire.get("gasSchedule")), "INCOMPATIBLE_GAS_SCHEDULE");
    String root = token(wire.get("root"));
    Set<String> keys = new HashSet<>();
    String selected = null;
    for (Object raw : arr(wire.get("documents"))) {
      var row =
          closed(
              raw,
              Set.of("key", "blueId", "channels", "channelSerials", "edgeSerials", "birth"),
              Set.of());
      String key = token(row.get("key")), identity = finalId(row.get("blueId"));
      require(keys.add(key), "DUPLICATE_DOCUMENT_KEY");
      if (key.equals(member)) selected = identity;
    }
    require(keys.contains(root), "MISSING_ROOT_DOCUMENT");
    require(selected != null, "UNKNOWN_DOCUMENT_KEY");
    return selected;
  }

  static Snapshot restore(ContractProcessor engine, Map<String, Object> wire) {
    return restore(engine, wire, false);
  }

  static Snapshot restore(ContractProcessor engine, Map<String, Object> wire, boolean prepared) {
    closed(
        wire,
        Set.of("root", "documents", "edges", "generation", "revision", "release", "runtimes"),
        Set.of("gasSchedule"));
    require(wire.get("release").equals(engine.registry.specification), "INCOMPATIBLE_RELEASE");
    Snapshot s = new Snapshot(token(wire.get("root")));
    s.release = str(wire.get("release"));
    s.gasSchedule = engine.bindings.gasSchedule();
    require(s.gasSchedule.id().equals(wire.get("gasSchedule")), "INCOMPATIBLE_GAS_SCHEDULE");
    s.generation = integer(wire.get("generation"));
    s.revision = integer(wire.get("revision"));
    s.runtimes = arr(wire.get("runtimes"));
    for (Object raw : arr(wire.get("documents"))) {
      var row =
          closed(
              raw,
              Set.of("key", "blueId", "channels", "channelSerials", "edgeSerials", "birth"),
              Set.of());
      String k = token(row.get("key")), id = finalId(row.get("blueId"));
      require(!s.documents.containsKey(k), "DUPLICATE_DOCUMENT_KEY");
      Ref n = new Ref(id);
      ContractMetrics.count("contracts.restore.members");
      if (!prepared) {
        var fact =
            engine.registry.facts.check("restore-body", k, new Document(k, n, id, null), List.of());
        if (!fact.known()) {
          ContractMetrics.count("contracts.restore.bodyChecks");
          engine.blue.open(n);
          engine.blue.certify(n);
          engine.registry.facts.established(fact);
        }
      }
      boolean reuse =
          !prepared
              && engine.blue.context.nativeReuse()
              && !Boolean.getBoolean("blue.physical.nativeDisabled");
      var cached = reuse ? engine.registry.restoredDocuments.get(row) : null;
      if (cached != null) {
        s.documents.put(k, cached.copy());
        continue;
      }
      Map<String, Life> channels = new TreeMap<>();
      Map<String, Long> channelSerials = new TreeMap<>(), edgeSerials = new TreeMap<>();
      obj(row.get("channels"))
          .forEach(
              (name, v) -> {
                token(name);
                var life = closed(v, Set.of("generation", "signature"), Set.of());
                channels.put(
                    name,
                    new Life(integer(life.get("generation"), 1, SAFE), str(life.get("signature"))));
              });
      obj(row.get("channelSerials"))
          .forEach((name, v) -> channelSerials.put(token(name), integer(v, 1, SAFE)));
      obj(row.get("edgeSerials"))
          .forEach((name, v) -> edgeSerials.put(token(name), integer(v, 1, SAFE)));
      Document d =
          new Document(
              k,
              n,
              id,
              row.get("birth") == null ? null : token(row.get("birth")),
              channels,
              channelSerials,
              edgeSerials);
      s.documents.put(k, d);
      if (reuse) {
        d.metadata();
        var owned = obj(ContractWire.immutable(row));
        engine.registry.restoredDocuments.put(owned, d.copy(), ContractCache.weight(owned) * 2);
      }
    }
    for (Object raw : arr(wire.get("edges"))) {
      var row = closed(raw, Set.of("source", "path", "target", "generation"), Set.of());
      Edge e =
          new Edge(
              str(row.get("source")),
              str(row.get("path")),
              str(row.get("target")),
              integer(row.get("generation"), 1, SAFE));
      require(!s.edges.containsKey(e.key()), "DUPLICATE_EDGE");
      s.edges.put(e.key(), e);
    }
    return s;
  }

  static Map<String, Object> appended(
      ContractProcessor engine,
      Snapshot before,
      Snapshot after,
      java.util.function.Predicate<String> retained) {
    if (before.ownerLocal
        || after.ownerLocal
        || !before.root.equals(after.root)
        || !before.release.equals(after.release)
        || before.gasSchedule != after.gasSchedule
        || !before.runtimes.equals(after.runtimes)
        || after.documents.size() != before.documents.size() + 1) return Map.of();
    var members = after.documents.changedKeys(before.documents);
    var edges = after.edges.changedKeys(before.edges);
    if (members.size() > 8 || edges.isEmpty() || edges.size() > 16) return Map.of();
    var added = new ArrayList<String>();
    var rows = new TreeMap<String, Object>();
    var roots = new ArrayList<ExactNode>();
    for (String key : members) {
      Document d = after.documents.get(key);
      if (d == null) return Map.of();
      if (!before.documents.containsKey(key)) added.add(key);
      rows.put(key, d.metadata());
      roots.add(d.body);
    }
    if (added.size() != 1) return Map.of();
    var links = new ArrayList<Object>();
    for (var key : edges) {
      if (before.edges.containsKey(key) || after.edges.get(key) == null) return Map.of();
      links.add(after.edges.get(key).wire());
    }
    // An identical immutable member in this exact predecessor already has its closure.
    // The ordinary validator still checks the entire resulting graph before any export.
    engine.validate(after, true);
    var header =
        m(
            "root",
            after.root,
            "release",
            after.release,
            "runtimes",
            after.runtimes,
            "revision",
            after.revision,
            "generation",
            after.generation,
            "gasSchedule",
            after.gasSchedule.id());
    return m(
        "format",
        "contracts.appended-checkpoint/1",
        "base",
        before.identity(),
        "identity",
        after.identity(),
        "header",
        header,
        "member",
        added.getFirst(),
        "documents",
        rows,
        "edges",
        links,
        "content",
        engine.blue.context.records(roots, retained));
  }

  static Map<String, Object> export(ContractProcessor engine, Snapshot s) {
    return export(engine, s, true);
  }

  static Map<String, Object> export(ContractProcessor engine, Snapshot s, boolean initialized) {
    return export(engine, s, initialized, Set.of());
  }

  static Map<String, Object> export(
      ContractProcessor engine, Snapshot s, boolean initialized, Set<String> supplied) {
    return export(engine, s, initialized, supplied::contains);
  }

  static Map<String, Object> export(
      ContractProcessor engine,
      Snapshot s,
      boolean initialized,
      java.util.function.Predicate<String> supplied) {
    require(!s.ownerLocal, "LOCAL_CHECKPOINT_IS_NOT_PORTABLE");
    engine.validate(s, initialized);
    var roots = new ArrayList<ExactNode>();
    for (Document d : s.documents.values()) roots.add(d.body);
    for (Object raw : s.runtimes)
      for (String field : List.of("type_id", "semantics_id"))
        roots.add(new Ref(str(obj(raw).get(field))));
    var content = engine.blue.context.records(roots, supplied);
    return m("snapshot", s.wire(), "identity", s.identity(), "content", content);
  }

  static Snapshot restore(
      ContractProcessor engine, Map<String, Object> packageData, String expected) {
    return restore(engine, packageData, expected, true);
  }

  static Snapshot restore(
      ContractProcessor engine,
      Map<String, Object> packageData,
      String expected,
      boolean initialized) {
    closed(packageData, Set.of("snapshot", "identity", "content"), Set.of());
    require(packageData.get("identity").equals(expected), "UNTRUSTED_SNAPSHOT_COMMITMENT");
    var wire = obj(packageData.get("snapshot"));
    require(
        digest("contracts.snapshot/completion-1", wire).equals(expected),
        "INVALID_SNAPSHOT_COMMITMENT");
    ContractLanguage old = engine.blue.context;
    ContractLanguage c = old.fork();
    var prepared =
        initialized && engine.registry.preparations != null
            ? engine.registry.preparations.read(expected)
            : null;
    if (prepared == null) c.addEvidence(obj(packageData.get("content")));
    else {
      Map<String, Object> records = obj(packageData.get("content"));
      c.supplyRetainedEvidence(records);
      // The protected preparation supplies prior semantic validation. Exact response identity
      // remains checked by Language when a member is opened; unused members need no decoding.
    }
    engine.blue.context = c;
    Snapshot s;
    try {
      s = restore(engine, wire, prepared != null);
      if (prepared != null) engine.registry.preparations.install(engine.registry, s, prepared);
      require(s.identity().equals(expected), "INVALID_SNAPSHOT_COMMITMENT");
      engine.validate(s, initialized);
    } catch (RuntimeException e) {
      engine.blue.context = old;
      throw e;
    }
    engine.blue.refresh();
    engine.archive.add(s);
    return s;
  }
}
