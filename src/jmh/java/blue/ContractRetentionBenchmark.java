package blue;

import static blue.ExactNode.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

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
