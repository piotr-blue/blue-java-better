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

/** Open a processing phase after a broadcast has selected one external channel per child. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class ChannelSchedulingBenchmark {
  @Param({"16", "256", "1024"})
  public int children;

  private ContractRun invocation;

  @Setup
  public void setup() {
    var members = new LinkedHashMap<String, Object>();
    var channels = Map.of("input", Map.of("type", "Contracts/External Channel"));
    for (int i = 0; i < children; i++) {
      members.put("child-" + i, Map.of("count", i, "contracts", channels));
    }
    var processor = new ContractProcessor();
    var document =
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
    processor.blue.context.refreshed();
    invocation =
        new ContractRun(
            processor, document.state, processor.blue.node(Map.of("kind", "observe")), 1L, false);
    invocation.begin();
    var phase = invocation.newPhase("PROCESS", null, false);
    var selected = phase.seeds.values().stream().flatMap(List::stream).toList();
    if (phase.allowed.size() != children + 1
        || selected.size() != children
        || !new java.util.HashSet<>(selected).equals(new java.util.HashSet<>(invocation.seeds))) {
      throw new IllegalStateException(
          "The real broadcast must reach exactly one channel per child");
    }
  }

  @Benchmark
  public Object prepareProcessPhase() {
    invocation.blue.context.refreshed();
    return invocation.newPhase("PROCESS", null, false);
  }
}
