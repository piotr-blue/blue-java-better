package blue;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.Optional;

/** Verifies in a dedicated small JVM that an exported state cannot keep host readers alive. */
public final class ExecutionRetentionProbe {
  private ExecutionRetentionProbe() {}

  private enum Reader {
    CONTROL,
    COMPLETION,
    CALCULATION
  }

  private static final class Host {
    Contracts.Execution.Retained completion(String key) {
      throw new AssertionError("A scalar document must not demand completion " + key);
    }

    Optional<Map<String, Object>> calculation(String key) {
      return Optional.empty();
    }
  }

  private record Held(Contracts.Execution.State state, WeakReference<Host> host) {}

  private static Held closeWith(Reader reader) {
    var host = new Host();
    try (var execution =
        Contracts.Execution.using(
            Contracts.create(), reader == Reader.COMPLETION ? host::completion : null)) {
      if (reader == Reader.CALCULATION) {
        execution.reuseCalculations(host::calculation);
      }
      var state = execution.prepare("root", Language.create().readYaml("rooms: 32"));
      return new Held(state, new WeakReference<>(host));
    }
  }

  private record Released(
      Contracts.Transaction transaction,
      WeakReference<Contracts.Session> session,
      WeakReference<Contracts.Transition> transition) {}

  private static Released closeTransaction(boolean commit) {
    try (var session = Contracts.create().openYaml("rooms: 32")) {
      session.initialize().requireApplied();
      var transaction = session.begin(Map.of("kind", "Review"), 1);
      var result = commit ? transaction.commit() : transaction.finish();
      transaction.close();
      return new Released(transaction, new WeakReference<>(session), new WeakReference<>(result));
    }
  }

  private static void requireCollected(WeakReference<?> reference, String description)
      throws InterruptedException {
    for (int attempt = 0; attempt < 50 && reference.get() != null; attempt++) {
      System.gc();
      Thread.sleep(50);
    }
    if (reference.get() != null) {
      throw new AssertionError("Closed handle retained " + description);
    }
  }

  public static void main(String[] args) throws InterruptedException {
    for (Reader reader : Reader.values()) {
      var held = closeWith(reader);
      requireCollected(held.host(), reader + " reader");
      System.out.println(reader + " released; retained document " + held.state().blueId());
      Reference.reachabilityFence(held.state());
    }
    for (boolean commit : new boolean[] {false, true}) {
      var held = closeTransaction(commit);
      requireCollected(held.session(), "transaction owner");
      requireCollected(held.transition(), "transaction result");
      System.out.println(
          (commit ? "Committed" : "Discarded") + " transaction released its owner and result");
      Reference.reachabilityFence(held.transaction());
    }
  }
}
