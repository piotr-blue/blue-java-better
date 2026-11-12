package blue;

import java.math.BigInteger;
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

/** Definition data conversion, excluding admission, compilation, evaluation and host storage. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class BexDataBenchmark {
  @Param({"4", "16", "64"})
  public int depth;

  private ContractBridge bridge;
  private ExactNode value;

  @Setup
  public void setup() {
    Object expected = Map.of("amount", BigInteger.valueOf(42), "currency", "EUR");
    for (int i = 0; i < depth; i++) {
      expected = Map.of("next", List.of(expected));
    }
    Language language = Language.create();
    LanguageDocument document = language.readJson(ContractCanonical.text(expected));
    bridge = new ContractBridge(language);
    bridge.context.install(document);
    value = document.exact;
    if (!expected.equals(bridge.bexData(value))) {
      throw new IllegalStateException("Definition data changed during conversion");
    }
  }

  @Benchmark
  public Object convert() {
    bridge.context.refreshed(); // Reset physical work as a processor does for each invocation.
    return bridge.bexData(value);
  }
}
