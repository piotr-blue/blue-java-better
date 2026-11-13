package blue;

import blue.ContractModel.Snapshot;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Initialization and termination in one invocation. Private turns share these lifecycle decisions.
 */
final class ContractLifecycle {
  record Termination(String cause, String reason) {}

  private final Set<String> initializing = new HashSet<>();
  private final Map<String, Termination> terminations = new HashMap<>();
  private Set<String> candidates;

  ContractLifecycle fork() {
    var result = new ContractLifecycle();
    result.initializing.addAll(initializing);
    result.terminations.putAll(terminations);
    if (candidates != null) {
      result.candidates = new HashSet<>(candidates);
    }
    return result;
  }

  boolean isInitializing(String member) {
    return initializing.contains(member);
  }

  void beginInitialization(String member) {
    initializing.add(member);
  }

  boolean isTerminating(String member) {
    return terminations.containsKey(member);
  }

  Termination termination(String member) {
    return terminations.get(member);
  }

  void terminate(String member, String cause, String reason) {
    terminations.put(member, new Termination(cause, reason));
  }

  Set<String> reachable(ContractGraph graph) {
    return graph.reachable(true, terminations.keySet());
  }

  Set<String> candidates(Snapshot input, ContractRegistry registry, ContractGraph graph) {
    if (candidates == null) {
      candidates = new HashSet<>();
      // Input markers passed validation and a document patch cannot remove them.
      for (var entry : input.documents.entrySet()) {
        if (registry.marker(entry.getValue(), "initialized") == null) {
          candidates.add(entry.getKey());
        }
      }
    }
    // A private turn can initialize or temporarily hide a candidate before its result is merged.
    candidates.addAll(graph.added);
    return candidates;
  }
}
