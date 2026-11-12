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

/** Archive snapshot and selected lookup only; setup creates real canonical document versions. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(
    value = 2,
    jvmArgsAppend = {"-Xms128m", "-Xmx512m"})
public class ContractArchiveBenchmark {
  @Param({"128", "4096"})
  public int versions;

  private ContractGraph.Archive archive;
  private String root;

  @Setup
  public void setup() {
    ContractProcessor processor = new ContractProcessor();
    for (int i = 0; i < versions; i++) root = processor.create(Map.of("count", i)).blueId();
    archive = processor.archive;
  }

  @Benchmark
  public Object fork() {
    ContractGraph.Archive copy = new ContractGraph.Archive();
    archive.copyTo(copy);
    return copy;
  }

  @Benchmark
  public Object select() {
    return archive.get(root);
  }
}
