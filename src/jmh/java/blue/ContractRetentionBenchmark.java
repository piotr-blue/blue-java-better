package blue;

import static blue.ExactNode.list;
import static blue.ExactNode.value;

import java.util.ArrayList;
import java.util.List;
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

/** Transaction snapshot and retention only; excludes admission, execution and persistence. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(
    value = 2,
    jvmArgsAppend = {"-Xms128m", "-Xmx512m"})
public class ContractRetentionBenchmark {
  @Param({"128", "4096"})
  public int entries;

  private ContractLanguage source;
  private ExactNode changed;

  @Setup
  public void setup() {
    source = new ContractLanguage(Language.create());
    List<ExactNode> children = new ArrayList<>();
    for (int i = 0; i < entries; i++) children.add(value(Map.of("value", "item-" + i)));
    ExactNode shared = list(children);
    source.retain(
        List.of(value(Map.of("data", shared, "revision", value(Map.of("value", "old"))))));
    changed = value(Map.of("data", shared, "revision", value(Map.of("value", "new"))));
    LanguageCore.identity(changed);
  }

  @Benchmark
  public Object retainChangedSnapshot() {
    ContractLanguage fork = source.fork();
    fork.retain(List.of(changed));
    return fork;
  }
}
