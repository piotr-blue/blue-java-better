package blue;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

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

    Node open(Node node) {
      return node;
    }
  }

  private record Held(Contracts.Execution.State state, WeakReference<Host> host) {}

  private record Edited(
      Node.Editor editor, Node result, WeakReference<Node> source, WeakReference<Host> host) {}

  private static Edited finishEditing(boolean fail) {
    var host = new Host();
    var source = Node.object(Map.of("count", Node.integer(1)));
    var editor = new AtomicReference<Node.Editor>();
    var expected = new IllegalArgumentException("Editing callback failed");
    Node result = null;
    try {
      result =
          source.edit(
              cursor -> {
                editor.set(cursor);
                cursor.set("/count", 2);
                if (fail) {
                  throw expected;
                }
              },
              host::open);
    } catch (IllegalArgumentException failure) {
      if (failure != expected) {
        throw failure;
      }
    }
    return new Edited(editor.get(), result, new WeakReference<>(source), new WeakReference<>(host));
  }

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

  private record AcceptedChunk(
      ContractAttempt.ChunkToken token,
      WeakReference<ContractAttempt> attempt,
      WeakReference<ContractProcessor> processor) {}

  private static AcceptedChunk acceptChunk() {
    var processor = new ContractProcessor(ContractBindings.current());
    var source =
        Map.of(
            "rooms",
            32,
            "contracts",
            Map.of("input", Map.of("type", "Contracts/External Channel")));
    var state = processor.create(source);
    var attempt = processor.begin(state, Map.of("kind", "Review"), 1);
    var token = attempt.nextChunk();
    java.util.Objects.requireNonNull(token);
    attempt.accept(token, attempt.execute(token));
    return new AcceptedChunk(token, new WeakReference<>(attempt), new WeakReference<>(processor));
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
    for (boolean fail : new boolean[] {false, true}) {
      var edited = finishEditing(fail);
      requireCollected(edited.source(), "editor source");
      requireCollected(edited.host(), "editor provider");
      if (!fail && edited.result().get("count").integer() != 2) {
        throw new AssertionError("Released editor changed its result");
      }
      System.out.println(
          (fail ? "Failed" : "Completed") + " editor released its source and provider");
      Reference.reachabilityFence(edited);
    }
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
    var chunk = acceptChunk();
    requireCollected(chunk.attempt(), "accepted chunk's issuing attempt");
    requireCollected(chunk.processor(), "accepted chunk's processor");
    if (!chunk.token().identity().equals(chunk.token().descriptor().get("identity"))) {
      throw new AssertionError("Accepted chunk descriptor changed");
    }
    Reference.reachabilityFence(chunk.token());
  }
}
