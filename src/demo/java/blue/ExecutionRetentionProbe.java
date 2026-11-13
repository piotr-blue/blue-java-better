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

  public static void main(String[] args) throws InterruptedException {
    for (Reader reader : Reader.values()) {
      var held = closeWith(reader);
      for (int attempt = 0; attempt < 50 && held.host().get() != null; attempt++) {
        System.gc();
        Thread.sleep(50);
      }
      if (held.host().get() != null) {
        throw new AssertionError("Host survived closed execution: " + reader);
      }
      System.out.println(reader + " released; retained document " + held.state().blueId());
      Reference.reachabilityFence(held.state());
    }
  }
}
