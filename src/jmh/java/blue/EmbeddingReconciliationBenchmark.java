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

/** Reconcile an unchanged wide embedding collection; excludes admission and execution. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class EmbeddingReconciliationBenchmark {
  @Param({"16", "256", "1024"})
  public int children;

  private ContractGraph graph;

  @Setup
  public void setup() {
    var members = new LinkedHashMap<String, Object>();
    for (int i = 0; i < children; i++) members.put("child-" + i, Map.of("count", i));
    var source =
        Map.of(
            "children",
            members,
            "count",
            0,
            "contracts",
            Map.of(
                "embedded",
                Map.of(
                    "type",
                    "Contracts/Process Embedded",
                    "collectionPaths",
                    List.of("/children"))));
    ContractProcessor processor = new ContractProcessor();
    ContractProcessor.Document document = processor.create(source);
    graph = new ContractGraph(document.state.copy(), processor.registry, processor.archive);
    String identity = graph.state.identity();
    reconcile();
    if (!identity.equals(graph.state.identity())
        || graph.state.edges.size() != children
        || !graph.changes.isEmpty()) {
      throw new IllegalStateException("Unchanged reconciliation altered the graph");
    }
  }

  @Benchmark
  public Object reconcile() {
    graph.reconcile("unchanged", graph.state.root, List.of("/count"));
    return graph.state;
  }
}
