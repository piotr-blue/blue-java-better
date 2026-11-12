package blue;

import java.util.Set;
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

/** One small emitted value with a growing, unrelated initial and post-execution catalogue. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(
    value = 1,
    jvmArgsAppend = {"-Xms128m", "-Xmx1g"})
public class EmissionProjectionBenchmark {
  @Param({"10", "1000", "10000"})
  public int entries;

  private Contracts.Emission emission;

  @Setup
  public void setup() {
    Language fresh = Language.create();
    var builder = Language.newBuilder();
    for (int i = 0; i < entries; i++) builder.content(fresh.readYaml("old: " + i));
    var context = new ContractLanguage(builder.build());
    for (int i = 0; i < entries; i++) context.install(fresh.readYaml("new: " + i));
    var event = fresh.readYaml("kind: SmallEvent\namount: 7");
    context.install(event);
    emission = new Contracts.Emission("member", context.document(event.semantic));
    if (!sharedSnapshot().equals(previousCatalogueMerge()))
      throw new AssertionError("Projection changed");
  }

  @Benchmark
  public Object sharedSnapshot() {
    return emission.value();
  }

  @Benchmark
  public Object previousCatalogueMerge() {
    var event = emission.event();
    var bridge = new ContractBridge(event.owner.toBuilder().content(event).build());
    return BexValues.freeze(bridge.project(event.semantic, false, Set.of(), true));
  }
}
