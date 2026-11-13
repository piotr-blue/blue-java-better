package blue;

import java.util.ArrayList;
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

/**
 * Finalize a complete owning proof. The measurement includes normalization, ordering and hashing.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class CyclicIdentityBenchmark {
  @Param({"8", "128", "512"})
  public int members;

  @Param({"ring", "chord"})
  public String shape;

  private List<Object> input;
  private String expected;

  @Setup
  public void setup() {
    var rows = new ArrayList<>();
    for (int i = 0; i < members; i++) {
      var next = Map.of("blueId", "this#" + ((i + 1) % members));
      String name = "Member " + i;
      rows.add(
          shape.equals("ring")
              ? Map.of("name", name, "next", next)
              : Map.of(
                  "name",
                  name,
                  "next",
                  next,
                  "chord",
                  Map.of("blueId", "this#" + ((i + members / 2) % members))));
    }
    input = List.copyOf(rows);
    expected = LanguageCore.finalizeSet(input, new LanguageContext()).master();
  }

  @Benchmark
  public String finalizeOwningProof() {
    String actual = LanguageCore.finalizeSet(input, new LanguageContext()).master();
    if (!expected.equals(actual)) {
      throw new IllegalStateException("The same owning proof gave a different master");
    }
    return actual;
  }
}
