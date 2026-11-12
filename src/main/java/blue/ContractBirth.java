package blue;

import static blue.ContractLanguage.identity;
import static blue.ContractModel.HOST_LIMIT;
import static blue.ContractModel.arr;
import static blue.ContractModel.digest;
import static blue.ContractModel.obj;
import static blue.ContractModel.require;

import blue.ContractModel.Document;
import blue.ContractModel.Edge;
import blue.ContractModel.Snapshot;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Native rules for an owner adding one fresh embedded child. A live lookup is pinned to the
 * predecessor. Shared targets must remain unchanged throughout initialization.
 */
final class ContractBirth {
  private static final String FORMAT = "contracts.single-birth/1";
  // A projected whole-graph receipt has a small, explicitly bounded predecessor proof.
  private static final int MAX_PRIOR_MEMBERS = 8;
  private static final int MAX_RESULT_EDGES = 16;

  private final ContractProcessor engine;
  private final String owner;
  private final java.util.function.Function<BlueId, Map<String, Object>> liveMember;
  private final Map<String, Document> shared = new HashMap<>();
  private String child;

  String child() {
    return child;
  }

  ContractBirth(
      ContractProcessor engine,
      String owner,
      java.util.function.Function<BlueId, Map<String, Object>> liveMember) {
    this.engine = engine;
    this.owner = owner;
    this.liveMember = liveMember;
  }

  String resolve(ContractGraph graph, String source, String path, ExactNode node, String birth) {
    String id = identity(node);
    Map<String, Object> pinned = liveMember.apply(new BlueId(id));
    if (source.equals(owner)) {
      require(
          child == null && pinned == null && graph.archive.get(id) == null,
          "LOCAL_EXECUTION_UNAVAILABLE",
          "reason",
          "Existing or imported creation target");
      child = graph.importCanonicalTarget(source, path, node, birth);
      return child;
    }
    require(
        source.equals(child) && pinned != null,
        "LOCAL_EXECUTION_UNAVAILABLE",
        "reason",
        "New child requires graph resolution");
    Snapshot restored = ContractLocalCheckpoint.restore(engine, pinned);
    Document reference = restored.documents.get(restored.root);
    require(
        !reference.key.equals(owner)
            && !reference.key.equals(child)
            && reference.blueId.equals(id)
            && graph.paths(reference).isEmpty()
            && engine.registry.marker(reference, "initialized") != null,
        "LOCAL_EXECUTION_UNAVAILABLE",
        "reason",
        "Shared target is not an independent initialized leaf");
    shared.put(reference.key, reference);
    if (!graph.state.documents.containsKey(reference.key)) {
      graph.state.documents.put(reference.key, reference.copy());
    }
    return reference.key;
  }

  void finish(ContractRun.LocalResult turn, Snapshot after) {
    require(
        turn.documents().size() == shared.size() + 2 && turn.documents().containsKey(child),
        "LOCAL_EXECUTION_UNAVAILABLE",
        "reason",
        "Additional members changed during birth");
    for (var reference : shared.values()) {
      require(
          reference.metadata().equals(turn.documents().get(reference.key).metadata()),
          "LOCAL_EXECUTION_UNAVAILABLE",
          "reason",
          "Shared predecessor changed during creation");
    }
    var fresh = turn.documents().get(child);
    require(
        engine.registry.marker(fresh, "initialized") != null
            && engine.registry.marker(fresh, "terminated") == null,
        "LOCAL_EXECUTION_UNAVAILABLE",
        "reason",
        "Fresh child did not initialize");
    int incoming = 0;
    Set<String> targets = new HashSet<>();
    for (Edge edge : turn.edges().values()) {
      if (edge.source().equals(owner) && edge.target().equals(child)) {
        incoming++;
      } else if (edge.source().equals(child) && shared.containsKey(edge.target())) {
        targets.add(edge.target());
      } else {
        throw new ContractModel.Error(
            "LOCAL_EXECUTION_UNAVAILABLE", "reason", "Nonlocal birth edge");
      }
    }
    require(incoming == 1 && targets.equals(shared.keySet()), "LOCAL_EXECUTION_UNAVAILABLE");
    after.verifyGraphBindings = true;
    after.documents.putAll(turn.documents());
    after.edges.putAll(turn.edges());
    after.generation = Math.addExact(after.generation, 1);
  }

  static boolean canAdopt(
      Map<String, Object> source, Map<String, Object> observation, String receiver, String child) {
    if (!FORMAT.equals(source.get("kind"))
        || observation.containsKey("executionBoundary")
        || !Objects.equals(
            source.get("receipt"), digest("contracts.result/completion-1", observation))) {
      return false;
    }
    var birth = obj(source.get("birth"));
    var leaf = obj(source.get("leaf"));
    return !child.equals(birth.get("member"))
        && !receiver.equals(birth.get("member"))
        && Objects.equals(birth.get("member"), leaf.get("root"))
        && arr(observation.get("rounds")).isEmpty();
  }

  /** Project an issued whole-graph result for consumption by a containing owner. */
  static Map<String, Object> completion(
      ContractProcessor processor,
      Contracts.Execution.Outcome result,
      Snapshot before,
      Snapshot after) {
    if (requiresWholeGraph(processor, result, before, after)) {
      return Map.of();
    }
    var observations = result.transition().observations();
    if (observations.containsKey("executionBoundary")
        || !((List<?>) observations.get("rounds")).isEmpty()) {
      return Map.of();
    }
    var issued = processor.completions.outcomes.get(result.transition().receipt());
    if (issued == null
        || !issued.beforeIdentity().equals(before.identity())
        || !Objects.equals(issued.event(), result.event())
        || !Objects.equals(issued.timestamp(), result.timestamp())
        || !Objects.equals(observations.get("output"), after.identity())) {
      return Map.of();
    }
    var fresh =
        after.documents.keySet().stream()
            .filter(k -> !before.documents.containsKey(k))
            .findFirst()
            .orElseThrow();
    var prior = new TreeMap<String, Object>();
    for (var row : before.documents.entrySet()) {
      var metadata = row.getValue().metadata();
      prior.put(row.getKey(), metadata);
      if (!before.root.equals(row.getKey())
          && !metadata.equals(after.documents.get(row.getKey()).metadata())) {
        return Map.of();
      }
    }
    for (var row : before.edges.entrySet()) {
      if (!row.getValue().equals(after.edges.get(row.getKey()))) {
        return Map.of();
      }
    }
    var added = new ArrayList<Map<String, Object>>();
    int incoming = 0;
    for (var edge : after.edges.values()) {
      if (before.edges.containsKey(edge.key())) {
        continue;
      }
      if (edge.source().equals(before.root) && edge.target().equals(fresh)) {
        incoming++;
      } else if (!edge.source().equals(fresh)
          || !before.documents.containsKey(edge.target())
          || edge.target().equals(before.root)) {
        return Map.of();
      }
      added.add(edge.wire());
    }
    if (incoming != 1) {
      return Map.of();
    }
    var previous = result.before().localCheckpoint();
    var next = result.after().localCheckpoint();
    var leaf = result.after().rooted(fresh).localCheckpoint();
    if (previous.isEmpty() || next.isEmpty() || leaf.isEmpty()) {
      return Map.of();
    }
    return Map.of(
        "kind",
        FORMAT,
        "before",
        previous,
        "after",
        next,
        "leaf",
        leaf,
        "birth",
        Map.of("member", fresh, "edges", added, "prior", prior),
        "receipt",
        result.transition().receipt(),
        "observations",
        observations,
        "event",
        result.event(),
        "timestamp",
        result.timestamp());
  }

  private static boolean requiresWholeGraph(
      ContractProcessor processor,
      Contracts.Execution.Outcome result,
      Snapshot before,
      Snapshot after) {
    return !result.applied()
        || result.event() == null
        || result.timestamp() == null
        || before.ownerLocal
        || after.ownerLocal
        || before.documents.size() > MAX_PRIOR_MEMBERS
        || after.documents.size() != before.documents.size() + 1
        || after.edges.size() > MAX_RESULT_EDGES
        || !before.root.equals(after.root)
        || !after.documents.keySet().containsAll(before.documents.keySet())
        || processor.gasLimit != HOST_LIMIT
        || !processor.localLimits.isEmpty();
  }
}
