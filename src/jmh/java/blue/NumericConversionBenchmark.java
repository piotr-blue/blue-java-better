package blue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/**
 * Integer conversion only; excludes expression evaluation, gas, document processing and storage.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class NumericConversionBenchmark {
  @Param({"int", "long", "big", "decimal"})
  public String representation;

  private Object value;

  @Setup
  public void setup() {
    value =
        switch (representation) {
          case "int" -> 123456;
          case "long" -> Long.MAX_VALUE;
          case "big" -> BigInteger.ONE.shiftLeft(1024);
          case "decimal" -> new BigDecimal("123456.000");
          default -> throw new IllegalArgumentException(representation);
        };
    if (!integer().equals(decimalRoundTrip()))
      throw new IllegalStateException("Integer conversion differs");
  }

  @Benchmark
  public BigInteger integer() {
    return BexValues.integer(value);
  }

  @Benchmark
  public BigInteger decimalRoundTrip() {
    return BexValues.number(value).toBigIntegerExact();
  }
}
