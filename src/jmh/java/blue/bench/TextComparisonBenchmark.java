package blue.bench;

import blue.Bex;
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

/** Public BEX equality: early mismatch, complete equality, Unicode and allocation per execution. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class TextComparisonBenchmark {
  @Param({"16", "4096"})
  public int codePoints;

  @Param({"first", "equal", "last"})
  public String position;

  @Param({"ascii", "astral"})
  public String alphabet;

  private Bex.Program program;
  private Bex.Input input;

  @Setup
  public void setup() {
    String character = alphabet.equals("ascii") ? "a" : "\uD83C\uDF0D";
    String left = character.repeat(codePoints);
    String different = alphabet.equals("ascii") ? "b" : "\uD83C\uDF0E";
    String right =
        switch (position) {
          case "first" -> different + character.repeat(codePoints - 1);
          case "last" -> character.repeat(codePoints - 1) + different;
          case "equal" -> new String(left.toCharArray());
          default -> throw new IllegalArgumentException("Unknown mismatch position");
        };
    program = Bex.create().compileYaml("expr: {$eq: [{$document: /left}, {$document: /right}]}");
    input = Bex.inputs().document(Map.of("left", left, "right", right)).build();
    Bex.Result result = program.execute(input);
    result.requireSuccess();
    if (!Boolean.valueOf(position.equals("equal")).equals(result.value())) {
      throw new IllegalStateException("Unexpected comparison result");
    }
  }

  @Benchmark
  public Bex.Result compare() {
    return program.execute(input);
  }
}
