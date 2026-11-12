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
import org.pcollections.HashTreePMap;
import org.pcollections.PMap;

/** Shared evidence union only; excludes validation, execution, transport and persistence. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(
    value = 1,
    jvmArgsAppend = {"-Xms128m", "-Xmx512m"})
public class LanguageEvidenceBenchmark {
  @Param({"128", "4096", "65536"})
  public int entries;

  private Map<String, Object> base, extension;
  private PMap<String, Object> previousBase, previousExtension;

  @Setup
  public void setup() {
    LanguageMap<String, Object> values = new LanguageMap<>();
    previousBase = HashTreePMap.empty();
    Object body = new Object();
    for (int i = 0; i < entries; i++) {
      String key = "body-" + i;
      values.put(key, body);
      previousBase = previousBase.plus(key, body);
    }
    base = LanguageMap.immutable(values);
    values.put("new-message", body);
    extension = LanguageMap.immutable(values);
    previousExtension = previousBase.plus("new-message", body);
  }

  @Benchmark
  public Object sharedBranches() {
    LanguageMap<String, Object> merged = new LanguageMap<>(base);
    merged.putAll(extension);
    return LanguageMap.immutable(merged);
  }

  @Benchmark
  public Object previousEntryScan() {
    PMap<String, Object> merged = previousBase;
    for (Map.Entry<String, Object> entry : previousExtension.entrySet()) {
      if (merged.get(entry.getKey()) != entry.getValue()) {
        merged = merged.plus(entry.getKey(), entry.getValue());
      }
    }
    return merged;
  }
}
