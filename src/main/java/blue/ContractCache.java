package blue;

import blue.LanguageModel.Recipe;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Instance-owned bounded LRU. Weight includes keys and reachable payload; zero means no retention.
 */
final class ContractCache<K, V> {
  private record Item<V>(V value, long weight) {}

  private record Weighed(ExactNode.Value value, long start) {}

  private final LinkedHashMap<K, Item<V>> items = new LinkedHashMap<>(16, .75f, true);
  private final long maximumBytes, maximumEntryBytes;
  private final int maximumEntries;
  private final String counter;
  private long bytes;

  public ContractCache(
      String counter, long maximumBytes, int maximumEntries, long maximumEntryBytes) {
    if (maximumBytes < 0 || maximumEntries < 0 || maximumEntryBytes < 0)
      throw new IllegalArgumentException("Negative cache capacity");
    this.counter = counter;
    this.maximumBytes = maximumBytes;
    this.maximumEntries = maximumEntries;
    this.maximumEntryBytes = maximumEntryBytes;
  }

  public synchronized V get(K key) {
    var found = items.get(key);
    if (ContractMetrics.enabled())
      ContractMetrics.count(counter + (found == null ? ".misses" : ".hits"));
    return found == null ? null : found.value;
  }

  public synchronized void put(K key, V value, long weight) {
    if (maximumBytes == 0
        || maximumEntryBytes == 0
        || maximumEntries == 0
        || weight < 0
        || weight > maximumEntryBytes
        || weight > maximumBytes) return;
    var prior = items.get(key);
    // Re-establishing an immutable fact is common during checkpoint preparation. The
    // access-order lookup refreshes its position without allocating another entry or item.
    if (prior != null && prior.value == value && prior.weight == weight) return;
    if (prior != null) {
      items.remove(key);
      bytes -= prior.weight;
    }
    while (!items.isEmpty() && (items.size() >= maximumEntries || bytes > maximumBytes - weight)) {
      var it = items.entrySet().iterator();
      bytes -= it.next().getValue().weight;
      it.remove();
      if (ContractMetrics.enabled()) ContractMetrics.count(counter + ".evictions");
    }
    items.put(key, new Item<>(value, weight));
    bytes += weight;
  }

  public synchronized int size() {
    return items.size();
  }

  public synchronized long bytes() {
    return bytes;
  }

  public synchronized void clear() {
    items.clear();
    bytes = 0;
  }

  /** Conservative graph-size estimate, deliberately counting shared subtrees repeatedly. */
  public static long weight(Object root) {
    return weight(root, 16L * 1024 * 1024);
  }

  /** Same estimate, bounded by the actual consumer's capacity. */
  static long weight(Object root, long maximumBytes) {
    if (maximumBytes < 0) throw new IllegalArgumentException("Negative weight limit");
    long bytes = 0;
    var pending = new ArrayDeque<Object>();
    if (root != null) pending.push(root);
    while (!pending.isEmpty()) {
      Object value = pending.pop();
      if (value instanceof Weighed end) {
        end.value.physicalWeight = bytes - end.start;
        continue;
      }
      long start = bytes;
      if (value instanceof ExactNode.Value v && v.physicalWeight != 0) {
        bytes += v.physicalWeight;
        if (bytes > maximumBytes) return Long.MAX_VALUE;
        continue;
      }
      bytes += 64;
      if (value instanceof String s) bytes += 2L * s.length();
      else if (value instanceof Recipe r) {
        pending.push(r.parts());
        pending.push(r.requirements());
      } else if (value instanceof ExactNode.Value v) {
        bytes += 128;
        // Memoize only completed subtrees. The explicit stack also supports deep exact values.
        pending.push(new Weighed(v, start));
        pending.push(v.fields());
      } else if (value instanceof ExactNode.Ref r) {
        pending.push(r.id());
        if (r.body() != null) pending.push(r.body());
      } else if (value instanceof Map<?, ?> m) {
        if (value instanceof ContractCanonical.EncodedRecord encoded)
          bytes += encoded.encodedSize();
        if (value instanceof ContractData.ScalarRecord row) bytes += row.layout.encodingWeight;
        bytes += 64L * m.size();
        for (var e : m.entrySet()) {
          pending.push(e.getKey());
          if (e.getValue() != null) pending.push(e.getValue());
        }
      } else if (value instanceof Collection<?> a) {
        bytes += 16L * a.size();
        for (Object x : a) if (x != null) pending.push(x);
      }
      // Oversize candidates cannot enter any of our small caches. Bound estimation work too.
      if (bytes > maximumBytes) return Long.MAX_VALUE;
    }
    return bytes;
  }
}
