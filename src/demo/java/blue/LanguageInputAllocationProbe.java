package blue;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Small JSON reads do not inherit the field-name vocabulary of unrelated Blue documents. */
public final class LanguageInputAllocationProbe {
  private LanguageInputAllocationProbe() {}

  /**
   * Checks helper, strict Language and generic node parsing in a dedicated bounded JVM.
   *
   * @param args unused
   */
  public static void main(String[] args) {
    if (!(ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean)
        || !bean.isThreadAllocatedMemorySupported()) {
      throw new IllegalStateException("Thread allocation accounting is required for this probe");
    }
    bean.setThreadAllocatedMemoryEnabled(true);
    check(bean, "helper", LanguageCodec::json);
    check(bean, "language", text -> LanguageCodec.parse(text, "json"));
    check(bean, "node", text -> new NodeCodec().readJson(text));
  }

  private static void check(
      com.sun.management.ThreadMXBean bean, String name, Function<String, ?> read) {
    for (int i = 0; i < 2000; i++) {
      read.apply("{\"warm\":1}");
    }
    var unrelated = new LinkedHashMap<String, Object>();
    for (int i = 0; i < 4000; i++) {
      unrelated.put(name + "-field-" + i + "-distinct-domain-attribute", i);
    }
    read.apply(NodeCodec.json(unrelated));
    var samples = new ArrayList<String>();
    for (int i = 0; i < 64; i++) {
      samples.add("{\"" + name + "-fresh-" + i + "\":1}");
    }
    long thread = Thread.currentThread().threadId();
    long before = bean.getThreadAllocatedBytes(thread);
    for (String sample : samples) {
      Objects.requireNonNull(read.apply(sample));
    }
    long allocated = bean.getThreadAllocatedBytes(thread) - before;
    long maximum = samples.size() * 32L * 1024;
    System.out.println(
        NodeCodec.json(
            Map.of(
                "parser", name,
                "unrelatedNames", unrelated.size(),
                "reads", samples.size(),
                "allocatedBytes", allocated,
                "maximumBytes", maximum)));
    if (allocated < 0 || allocated > maximum) {
      throw new AssertionError("Small " + name + " reads exceeded the isolated allocation bound");
    }
  }
}
