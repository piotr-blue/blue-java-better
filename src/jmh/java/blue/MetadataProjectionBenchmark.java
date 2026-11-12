package blue;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/** One metadata projection after a scalar revision; excludes hashing, execution and storage. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class MetadataProjectionBenchmark {
  @Param({"10", "1000", "10000"})
  public int entries;

  private ContractModel.Document source;

  @Setup
  public void prepare() {
    var edges = new TreeMap<String, Long>();
    for (int i = 0; i < entries; i++) edges.put("/children/child-" + i, 1L);
    source =
        new ContractModel.Document(
            "root",
            ExactNode.value(Map.of()),
            "before",
            "birth",
            Map.of("input", new ContractModel.Life(1, "signature")),
            Map.of("input", 1L),
            edges);
    source.metadata();
    if (!previousDeepCopy().equals(sharedCollections()))
      throw new AssertionError("Metadata differs");
  }

  @Benchmark
  public String previousCopyAndHash() {
    return ContractModel.digest("metadata", previousDeepCopy());
  }

  @Benchmark
  public String sharedCollectionsAndHash() {
    return ContractModel.digest("metadata", sharedCollections());
  }

  @Benchmark
  public Map<String, Object> previousDeepCopy() {
    var lives = new TreeMap<String, Object>();
    source.channelLives.forEach((k, v) -> lives.put(k, v.wire()));
    return ContractModel.obj(
        ContractWire.immutable(
            ContractModel.m(
                "key",
                source.key,
                "blueId",
                "after",
                "channels",
                lives,
                "channelSerials",
                source.channelSerials,
                "edgeSerials",
                source.edgeSerials,
                "birth",
                source.birth)));
  }

  @Benchmark
  public Map<String, Object> sharedCollections() {
    return source.withBlueId("after").metadata();
  }
}
