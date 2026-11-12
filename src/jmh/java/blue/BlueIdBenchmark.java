package blue;

import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** Repeated and larger-than-cache identity working sets, with source generation outside timing. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class BlueIdBenchmark {
  @Param({"64", "16384"})
  public int distinct;

  private String[] identities;
  private int position;

  @Setup
  public void setup() {
    identities = new String[distinct];
    Random random = new Random(1245);
    byte[] digest = new byte[32];
    for (int i = 0; i < distinct; i++) {
      random.nextBytes(digest);
      identities[i] = encode(digest);
    }
  }

  private static String encode(byte[] bytes) {
    java.math.BigInteger value = new java.math.BigInteger(1, bytes);
    String alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    StringBuilder text = new StringBuilder();
    while (value.signum() != 0) {
      var step = value.divideAndRemainder(java.math.BigInteger.valueOf(58));
      text.append(alphabet.charAt(step[1].intValue()));
      value = step[0];
    }
    for (byte element : bytes) {
      if (element != 0) break;
      text.append('1');
    }
    return text.reverse().toString();
  }

  @Benchmark
  public boolean validate() {
    return BlueIdSyntax.digest(identities[position++ & (distinct - 1)]);
  }
}
