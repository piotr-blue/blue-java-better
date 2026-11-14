package blue;

import java.math.BigInteger;
import java.util.Map;

/** Oversized numeric constants remain usable without accumulating in preparation caches. */
public final class NumericCacheRetentionProbe {
  private NumericCacheRetentionProbe() {}

  /**
   * Projects more numeric payload than this dedicated JVM can retain at once.
   *
   * @param args unused
   */
  public static void main(String[] args) {
    long heap = Runtime.getRuntime().maxMemory();
    if (heap > 128L * 1024 * 1024) {
      throw new IllegalStateException("Run with at most 128 MiB heap");
    }
    var programs = new ContractPrograms();
    int definitions = 64, bits = 3 * 1024 * 1024 * 8;
    for (int i = 0; i < definitions; i++) {
      BigInteger number = BigInteger.ONE.shiftLeft(bits + i);
      Map<String, Object> source = Map.of("constant", number);
      Object projected = programs.definitionValue("numeric-definition-" + i, () -> source);
      if (!source.equals(projected)) {
        throw new AssertionError("A cache retention policy changed a numeric value");
      }
    }
    System.out.println(
        NodeCodec.json(
            Map.of(
                "definitions",
                definitions,
                "numericPayloadBytesPerDefinition",
                bits / 8,
                "maximumHeapBytes",
                heap,
                "passed",
                true)));
  }
}
