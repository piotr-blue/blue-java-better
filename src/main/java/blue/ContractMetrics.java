package blue;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Optional process-wide diagnostic counters. Never used for gas or acceptance decisions. */
final class ContractMetrics {
  private static final boolean ENABLED = Boolean.getBoolean("blue.physical.counters");
  private static final Map<String, LongAdder> COUNTS = new ConcurrentHashMap<>();
  private static final Map<String, Map<String, LongAdder>> SCOPED = new ConcurrentHashMap<>();
  private static final ThreadLocal<Scope> ACTIVE = new ThreadLocal<>();
  private static final ThreadLocal<Span> ACTIVE_SPAN = new ThreadLocal<>();
  private static final java.lang.management.ThreadMXBean CPU =
      java.lang.management.ManagementFactory.getThreadMXBean();
  private static final boolean CPU_ENABLED =
      ENABLED
          && Boolean.getBoolean("blue.physical.cpu")
          && CPU.isCurrentThreadCpuTimeSupported()
          && CPU.isThreadCpuTimeEnabled();

  /** Diagnostic ownership only; never used by gas, scheduling or validity. */
  public static final class Scope implements AutoCloseable {
    private final String name;
    private final Scope previous;
    private final Map<String, Set<String>> distinct = new HashMap<>();

    private Scope(String name) {
      this.name = name;
      previous = ACTIVE.get();
      if (ENABLED) {
        ACTIVE.set(this);
      }
    }

    @Override
    public void close() {
      if (!ENABLED) {
        return;
      }
      distinct.forEach((k, v) -> add(k + ".distinctPerClaim", v.size()));
      ACTIVE.set(previous);
    }
  }

  public static Scope scope(String name) {
    return new Scope(name);
  }

  public static void distinct(String name, String identity) {
    if (ENABLED && ACTIVE.get() != null) {
      ACTIVE.get().distinct.computeIfAbsent(name, k -> new HashSet<>()).add(identity);
    }
  }

  public static final class Span implements AutoCloseable {
    private final String name;
    private final long start;
    private final long cpuStart;
    private final Span parent;
    private long children, childCpu;
    private boolean closed;

    private Span(String name) {
      this.name = name;
      parent = ENABLED ? ACTIVE_SPAN.get() : null;
      start = ENABLED ? System.nanoTime() : 0;
      cpuStart = CPU_ENABLED ? CPU.getCurrentThreadCpuTime() : 0;
      if (ENABLED) {
        ACTIVE_SPAN.set(this);
      }
    }

    @Override
    public void close() {
      if (!ENABLED || closed) {
        return;
      }
      closed = true;
      long elapsed = System.nanoTime() - start;
      long cpu = CPU_ENABLED ? CPU.getCurrentThreadCpuTime() - cpuStart : 0;
      count("phase." + name + ".calls");
      add("phase." + name + ".nanos", elapsed);
      add("phase." + name + ".selfNanos", elapsed - children);
      if (CPU_ENABLED) {
        add("phase." + name + ".cpuNanos", cpu);
        add("phase." + name + ".selfCpuNanos", cpu - childCpu);
      }
      if (parent == null) {
        ACTIVE_SPAN.remove();
      } else {
        parent.children += elapsed;
        parent.childCpu += cpu;
        ACTIVE_SPAN.set(parent);
      }
    }
  }

  public static Span span(String name) {
    return new Span(name);
  }

  @SuppressWarnings("try") // The span only brackets the measured region.
  public static <T> T measured(String name, java.util.function.Supplier<T> work) {
    try (var ignored = span(name)) {
      return work.get();
    }
  }

  /** Diagnostic sizes are characters; large-root labels contain identities, never payloads. */
  public static void materialized(String kind, String id, long characters) {
    if (!ENABLED) {
      return;
    }
    count(kind + ".calls");
    add(kind + ".characters", characters);
    distinct(kind, id);
    if (characters >= 100_000) {
      count(kind + ".largeRoot." + id + ".calls");
      add(kind + ".largeRoot." + id + ".characters", characters);
    }
  }

  private ContractMetrics() {}

  public static boolean enabled() {
    return ENABLED;
  }

  public static void add(String name, long amount) {
    if (ENABLED) {
      COUNTS.computeIfAbsent(name, k -> new LongAdder()).add(amount);
    }
    if (ENABLED && ACTIVE.get() != null) {
      SCOPED
          .computeIfAbsent(ACTIVE.get().name, k -> new ConcurrentHashMap<>())
          .computeIfAbsent(name, k -> new LongAdder())
          .add(amount);
    }
  }

  public static void count(String name) {
    add(name, 1);
  }

  public static Map<String, Long> snapshot() {
    var result = new TreeMap<String, Long>();
    COUNTS.forEach((k, v) -> result.put(k, v.sum()));
    return Collections.unmodifiableMap(result);
  }

  public static Map<String, Map<String, Long>> scopedSnapshot() {
    var out = new TreeMap<String, Map<String, Long>>();
    SCOPED.forEach(
        (scope, counts) -> {
          var row = new TreeMap<String, Long>();
          counts.forEach((k, v) -> row.put(k, v.sum()));
          out.put(scope, Collections.unmodifiableMap(row));
        });
    return Collections.unmodifiableMap(out);
  }
}
