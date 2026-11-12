package blue;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/** Isolates public callback materialization; it is not a whole-workflow latency benchmark. */
@Fork(
    value = 2,
    jvmArgsAppend = {"-Xms128m", "-Xmx512m"})
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class NativeValuesBenchmark {
  @Param({"16", "1024"})
  public int rows;

  private ContractBridge.ExactView event;

  @Setup
  public void setup() {
    Language language = Language.create();
    LanguageDocument document =
        language.readYaml(
            "kind: Accepted\npayload:\n"
                + "  - {amount: 42, message: Sample payment}\n".repeat(rows));
    ContractBridge bridge = new ContractBridge(language);
    bridge.context.install(document);
    event = (ContractBridge.ExactView) bridge.view(document.exact, null);
    Object selected = new NativeValues().select(event, "/kind");
    Object complete = ((Map<?, ?>) new NativeValues().freeze(event)).get("kind");
    if (!"Accepted".equals(selected) || !selected.equals(complete))
      throw new IllegalStateException("Different selected values");
  }

  @Benchmark
  public Object completeEvent() {
    // A real processor resets physical work for each invocation.
    event.bridge.context.refreshed();
    return ((Map<?, ?>) new NativeValues().freeze(event)).get("kind");
  }

  @Benchmark
  public Object selectedField() {
    event.bridge.context.refreshed();
    return new NativeValues().select(event, "/kind");
  }
}
