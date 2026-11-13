package blue;

import static blue.ContractLanguage.identity;
import static blue.ContractModel.m;
import static blue.ContractModel.pointer;
import static blue.ContractModel.require;
import static blue.ContractModel.segments;
import static blue.ContractRegistry.MARKERS;

import blue.ContractModel.Document;
import blue.ContractModel.EdgeKey;
import blue.ContractModel.Meter;
import blue.ContractModel.Snapshot;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Applies one validated patch to the current frame and delivers its ordered update reactions. */
final class ContractPatches {
  private ContractPatches() {}

  private record ProtectedState(String policy, Map<String, String> markers) {}

  private static ProtectedState protectedState(ContractRegistry registry, ExactNode body) {
    var markers = new TreeMap<String, String>(ContractCanonical.CODE_POINTS);
    for (String k : MARKERS) {
      ExactNode v = registry.marker(body, k);
      markers.put(k, v == null ? null : identity(v));
    }
    return new ProtectedState(registry.policy(body).identity(), markers);
  }

  static void apply(ContractRun run, String key, ContractEffects.Patch patch) {
    var blue = run.blue;
    var graph = run.graph;
    var state = run.state;
    var meter = run.meter;
    run.effectCount++;
    meter.owner = key;
    String path = patch.path(), op = patch.operation();
    Document doc = state.documents.get(key);
    var parts = patch.segments();

    var parent = blue.select(doc.body, pointer(parts.subList(0, parts.size() - 1)));
    boolean listParent = parent != null && blue.head(parent).items != null;
    if (listParent && parts.getLast().equals("-") && op.equals("add")) {
      parts = new ArrayList<>(parts);
      parts.set(parts.size() - 1, "" + blue.head(parent).items.size());
      path = pointer(parts);
    }
    boolean localWrite = SemanticGas.localWrite(run, path, op, patch.value());
    boolean localAppend =
        state.ownerLocal
            && !localWrite
            && graph.append != null
            && graph.append.child() == null
            && key.equals(state.root)
            && !listParent
            && op.equals("add")
            && blue.select(doc.body, path) == null
            && graph.collectionAppend(doc, doc, path);
    boolean initializingChild = graph.append != null && key.equals(graph.append.child());
    if (state.ownerLocal && !localWrite && !localAppend && !initializingChild) {
      throw new ContractModel.Error(
          "LOCAL_EXECUTION_UNAVAILABLE", "reason", "PATCH_REQUIRES_GRAPH", "path", path);
    }
    chargePatchBoundary(meter, parts, op.equals("remove"));
    checkManagedAncestors(state, key, path, parts);
    var beforeR = listParent && op.equals("add") ? null : blue.select(doc.body, path);
    ExactNode before = beforeR == null ? null : blue.exact(beforeR);
    ProtectedState protection = protectedState(run.reg, doc.body);
    ExactNode value = op.equals("remove") ? null : run.runtimeNode(patch.value(), false);
    ExactNode next = blue.setAt(doc.body, path, value, op);
    graph.logical.establish(next, doc.body);
    ExactNode requested = next;
    var repaired =
        Generalization.repair(
            blue,
            doc.body,
            next,
            path,
            run.reg.policy(doc.body),
            meter,
            n -> graph.logical.validate(n, doc.body),
            n -> graph.logical.establish(n, doc.body));
    next = repaired.body();
    require(
        protectedState(run.reg, next).equals(protection), "PROTECTED_STATE_MUTATION", "path", path);
    for (var write : repaired.writes()) {
      chargePatchBoundary(meter, segments(write.path()), false);
    }
    if (!repaired.writes().isEmpty()) {
      graph.logical.establish(next, requested);
      localWrite = false;
      if (state.ownerLocal && !initializingChild) {
        throw new ContractModel.Error(
            "LOCAL_EXECUTION_UNAVAILABLE", "reason", "GENERALIZATION_REPAIR", "path", path);
      }
    }
    blue.remember(doc.body);
    blue.remember(next);
    run.frame.writes.add(
        m(
            "writer",
            run.frame.writer,
            "op",
            op,
            "path",
            path,
            "before",
            identity(doc.body),
            "after",
            identity(next)));
    if (localWrite && !DiagnosticSwitches.fullPatch()) {
      // The patch is disjoint from every declared embedding and cannot change declarations.
      // Full Language validation and Document Update reactions still run.
      state.documents.put(key, doc.withBody(next));
      graph.finalizeLocal(key);
      ContractMetrics.count("contracts.dataTopologyReconciliationSkipped");
    } else {
      boolean freshEntry =
          before == null && !listParent && op.equals("add") && repaired.writes().isEmpty();
      reconcile(run, key, doc, next, path, freshEntry, localAppend);
    }
    emitPatchUpdates(run, key, path, op, before, repaired.writes());
  }

  /**
   * A change involving declarations or embeddings must refresh routes before child initialization.
   */
  private static void reconcile(
      ContractRun run,
      String key,
      Document doc,
      ExactNode next,
      String path,
      boolean freshEntry,
      boolean requiresAppend) {
    var graph = run.graph;
    var reachable = graph.reachable();
    var changed = doc.withBody(next);
    boolean append = freshEntry && graph.collectionAppend(doc, changed, path);
    require(!requiresAppend || append, "LOCAL_EXECUTION_UNAVAILABLE");
    run.state.documents.put(key, changed);
    graph.reconcile(run.eventId, key, List.of(path), append);
    var changedLifetimes = new HashSet<>(reachable);
    for (String member : graph.reachable()) {
      if (!changedLifetimes.add(member)) {
        changedLifetimes.remove(member);
      }
    }
    for (String member : changedLifetimes) {
      run.activation.merge(member, 1, Integer::sum);
    }
    run.allocation = Math.max(run.allocation, graph.allocation);
    var members = ContractCanonical.keys(graph.added);
    members.add(key);
    ContractChannels.refresh(graph, members);
    graph.finalizeLocal(key);
    run.creationBarrier();
    run.graph.finalizeLocal(key);
  }

  private static void chargePatchBoundary(Meter meter, List<String> parts, boolean remove) {
    meter.contracts("patchBoundaryChecked");
    for (String ignored : parts) {
      meter.contracts("pointerSegmentTraversed");
    }
    meter.contracts(remove ? "patchRemove" : "patchAddOrReplace");
  }

  /** Only strict ancestors can be managed boundaries. Work follows the pointer's depth. */
  private static void checkManagedAncestors(
      Snapshot state, String key, String path, List<String> parts) {
    for (int length = 0; length < parts.size(); length++) {
      require(
          !state.edges.containsKey(new EdgeKey(key, pointer(parts.subList(0, length)))),
          "MANAGED_BOUNDARY_VIOLATION",
          "path",
          path);
    }
  }

  /** Repairs emit in their recorded order before the requested update. A no-op emits neither. */
  private static void emitPatchUpdates(
      ContractRun run,
      String key,
      String path,
      String op,
      ExactNode before,
      List<Generalization.TypeChange> repairs) {
    var blue = run.blue;
    var afterR = op.equals("remove") ? null : blue.select(run.state.documents.get(key).body, path);
    ExactNode after = afterR == null ? null : blue.exact(afterR);
    String beforeId = before == null ? null : identity(before),
        afterId = after == null ? null : identity(after);
    if (Objects.equals(beforeId, afterId)) {
      return;
    }
    if (before != null) {
      blue.remember(before);
    }
    if (after != null) {
      blue.remember(after);
    }
    var payload =
        m(
            "op",
            before == null ? "add" : after == null ? "remove" : "replace",
            "path",
            path,
            "beforePresent",
            before != null,
            "afterPresent",
            after != null);
    if (before != null) {
      payload.put("before", m("blueId", beforeId));
    }
    if (after != null) {
      payload.put("after", m("blueId", afterId));
    }
    for (var write : repairs) {
      run.update(
          key,
          m(
              "op",
              "replace",
              "path",
              write.path(),
              "beforePresent",
              true,
              "afterPresent",
              true,
              "before",
              m("blueId", identity(write.previous())),
              "after",
              m("blueId", identity(write.replacement()))));
    }
    run.update(key, payload);
  }
}
