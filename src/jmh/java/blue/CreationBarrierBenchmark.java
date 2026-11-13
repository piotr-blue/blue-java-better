package blue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/** Begin an invocation and check eight creation barriers over already initialized documents. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class CreationBarrierBenchmark {
  @Param({"16", "256", "1024"})
  public int children;

  private ContractProcessor processor;
  private ContractProcessor.Document document;
  private ExactNode event;

  @Setup
  public void setup() {
    var members = new LinkedHashMap<String, Object>();
    for (int i = 0; i < children; i++) {
      members.put("child-" + i, Map.of("count", i));
    }
    processor = new ContractProcessor();
    document =
        processor.create(
            Map.of(
                "children",
                members,
                "contracts",
                Map.of(
                    "embedded",
                    Map.of(
                        "type",
                        "Contracts/Process Embedded",
                        "collectionPaths",
                        List.of("/children")))));
    event = processor.blue.node(Map.of("kind", "observe"));
    ContractRun run = invocation();
    long gas = run.meter.used();
    String identity = run.state.identity();
    for (int i = 0; i < 8; i++) {
      run.creationBarrier();
    }
    if (gas != run.meter.used() || !identity.equals(run.state.identity())) {
      throw new IllegalStateException("Empty barriers changed gas or state");
    }
  }

  private ContractRun invocation() {
    // Warmup and measured invocations each have their own physical Language work allowance.
    processor.blue.context.refreshed();
    var run = new ContractRun(processor, document.state, event, 1L, false);
    run.begin();
    return run;
  }

  @Benchmark
  public Object beginAndCheck() {
    ContractRun run = invocation();
    for (int i = 0; i < 8; i++) {
      run.creationBarrier();
    }
    return run;
  }
}
