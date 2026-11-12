package blue;

import java.math.BigDecimal;
import java.math.BigInteger;
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
 * Canonical BEX list navigation and numeric conversion, excluding input preparation and
 * compilation.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class BexReadBenchmark {
  @Param({"paths", "numbers"})
  public String workload;

  private Bex.Program program;
  private Bex.Input input;

  @Setup
  public void setup() {
    var items = new ArrayList<Object>();
    var expressions = new ArrayList<Object>();
    var expected = new ArrayList<Object>();
    boolean numbers = workload.equals("numbers");
    for (int i = 0; i < 16; i++) {
      String decimal = i + ".25";
      Object value = numbers ? decimal : BigInteger.valueOf(i);
      items.add(Map.of("amount", value));
      Object expression = Map.of("$event", "/items/" + i + "/amount");
      expressions.add(numbers ? Map.of("$number", expression) : expression);
      expected.add(numbers ? new BigDecimal(decimal) : value);
    }
    input = Bex.inputs().event(Map.of("items", items)).build();
    program = Bex.create().compileJson(ContractCanonical.text(Map.of("expr", expressions)));
    Bex.Result result = program.execute(input).requireSuccess();
    if (!expected.equals(result.value())
        || result.gas() != (numbers ? 67 : 35)
        || !List.of().equals(result.events())
        || !List.of().equals(result.changeset())) {
      throw new IllegalStateException("Read semantics changed: " + result);
    }
  }

  @Benchmark
  public Bex.Result execute() {
    return program.execute(input);
  }
}
