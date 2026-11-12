package blue;

import java.util.LinkedHashMap;
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

/** Presence checks retain null-versus-absent semantics without scanning private immutable rows. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class ContractRecordLookupBenchmark {
  @Param({"10", "1000", "10000"})
  public int fields;

  @Param({"frozen", "encoded"})
  public String representation;

  private Map<String, Object> record;
  private String present;

  @Setup
  public void setup() {
    Map<String, Object> source = new LinkedHashMap<>();
    for (int i = 0; i < fields; i++) {
      source.put("field-" + i, null);
    }
    present = "field-" + (fields - 1);
    record =
        representation.equals("frozen")
            ? ContractModel.obj(ContractData.freeze(source))
            : new ContractCanonical.EncodedRecord(source);
  }

  @Benchmark
  public boolean presentNull() {
    return record.containsKey(present);
  }

  @Benchmark
  public boolean absent() {
    return record.containsKey("absent");
  }
}
