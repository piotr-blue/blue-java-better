package blue;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiPredicate;
import org.pcollections.TreePMap;

/** Snapshot-owned index of immutable values. Forks share a root; updates copy a tree path. */
final class TopologyMap<K extends Comparable<? super K>, V> extends AbstractMap<K, V> {
  private TreePMap<K, V> values;
  private final boolean membershipOnly;
  private final BiPredicate<V, V> sameContent;
  private long revision;
  private long structuralChanges;
  private Object structure = new Object(), content = new Object();

  // Bounded, attempt-local edit ancestry contains keys only, never old values or snapshots.
  // A disconnected history uses an ordinary comparison; it cannot omit a changed record.
  private record Edit<K>(K key, Edit<K> previous, int depth) {}

  private Edit<K> edits = new Edit<>(null, null, 0);

  private void edited(K key) {
    if (edits.depth == 4096) edits = new Edit<>(null, null, 0);
    edits = new Edit<>(key, edits, edits.depth + 1);
  }

  /** Exact committed differences; related forks inspect only their intervening edits. */
  Set<K> changedKeys(TopologyMap<K, V> before) {
    return changedKeys(before, false);
  }

  /** Physical handle changes include equal metadata backed by a newly constructed value. */
  Set<K> changedValueKeys(TopologyMap<K, V> before) {
    return changedKeys(before, true);
  }

  private Set<K> changedKeys(TopologyMap<K, V> before, boolean physical) {
    if (!values.comparator().equals(before.values.comparator()))
      throw new IllegalArgumentException("Comparison requires the same ordering");
    if (physical ? values == before.values : content == before.content) return Set.of();
    var keys = new TreeSet<K>(values.comparator());
    Edit<K> left = edits, right = before.edits;
    while (left != right && (left.previous != null || right.previous != null)) {
      if (left.depth >= right.depth && left.previous != null) {
        keys.add(left.key);
        left = left.previous;
        ContractMetrics.count("contracts.indexDiff.edits");
      } else if (right.previous != null) {
        keys.add(right.key);
        right = right.previous;
        ContractMetrics.count("contracts.indexDiff.edits");
      }
    }
    if (left != right) {
      keys.addAll(values.keySet());
      keys.addAll(before.values.keySet());
      ContractMetrics.add("contracts.indexDiff.fallbackKeys", keys.size());
    }
    keys.removeIf(
        key -> {
          V old = before.values.get(key), now = values.get(key);
          return old == now
              || !physical && old != null && now != null && sameContent.test(old, now);
        });
    return Collections.unmodifiableSet(keys);
  }

  TopologyMap(boolean membershipOnly) {
    this(membershipOnly, Comparator.naturalOrder());
  }

  TopologyMap(boolean membershipOnly, Comparator<? super K> comparator) {
    this(membershipOnly, comparator, Objects::equals);
  }

  TopologyMap(
      boolean membershipOnly, Comparator<? super K> comparator, BiPredicate<V, V> sameContent) {
    this.membershipOnly = membershipOnly;
    this.sameContent = sameContent;
    values = TreePMap.empty(comparator);
  }

  long revision() {
    return revision;
  }

  Object structure() {
    return structure;
  }

  /** Identity of the committed values, independent of topology membership. */
  Object content() {
    return content;
  }

  /** Copies one contiguous key range without visiting unrelated entries. */
  List<V> valuesFrom(K first, java.util.function.Predicate<? super K> included) {
    List<V> result = new ArrayList<>();
    for (var entry : values.tailMap(first, true).entrySet()) {
      if (!included.test(entry.getKey())) break;
      result.add(entry.getValue());
    }
    return result;
  }

  /** The values are immutable, so a snapshot copy does not visit or clone any entries. */
  void copyFrom(TopologyMap<K, V> source) {
    if (!values.isEmpty()) throw new IllegalStateException("Topology copy requires an empty map");
    if (!values.comparator().equals(source.values.comparator()))
      throw new IllegalArgumentException("Topology copies require the same ordering");
    values = source.values;
    revision = source.revision;
    structuralChanges = source.structuralChanges;
    structure = source.structure;
    content = source.content;
    edits = source.edits;
  }

  private void changed() {
    revision++;
    structure = new Object();
  }

  @Override
  public int size() {
    return values.size();
  }

  @Override
  public V get(Object key) {
    return values.get(key);
  }

  @Override
  public boolean containsKey(Object key) {
    return values.containsKey(key);
  }

  @Override
  public V put(K key, V value) {
    Objects.requireNonNull(value);
    V old = values.get(key);
    if (old == value) return old;
    values = values.plus(key, value);
    if (old == null) structuralChanges++;
    edited(key);
    if (old == null || !sameContent.test(old, value)) content = new Object();
    if (old == null || !membershipOnly && !Objects.equals(old, value)) changed();
    return old;
  }

  @Override
  public V remove(Object key) {
    V old = values.get(key);
    if (old == null) return null;
    values = values.minus(key);
    @SuppressWarnings("unchecked")
    K removed = (K) key;
    edited(removed);
    structuralChanges++;
    content = new Object();
    changed();
    return old;
  }

  @Override
  public void clear() {
    if (!values.isEmpty()) {
      values = TreePMap.empty(values.comparator());
      structuralChanges++;
      content = new Object();
      edits = new Edit<>(null, null, 0);
      changed();
    }
  }

  @Override
  public Set<Entry<K, V>> entrySet() {
    return new AbstractSet<>() {
      @Override
      public int size() {
        return values.size();
      }

      @Override
      public void clear() {
        TopologyMap.this.clear();
      }

      @Override
      public Iterator<Entry<K, V>> iterator() {
        var selected = values;
        var iterator = selected.entrySet().iterator();
        return new Iterator<>() {
          K current;
          long expectedRevision = structuralChanges;

          private void check() {
            if (expectedRevision != structuralChanges) throw new ConcurrentModificationException();
          }

          @Override
          public boolean hasNext() {
            return iterator.hasNext();
          }

          @Override
          public Entry<K, V> next() {
            check();
            var entry = iterator.next();
            current = entry.getKey();
            V value = selected == values ? entry.getValue() : values.get(current);
            return new SimpleEntry<>(current, value) {
              @Override
              public V setValue(V updated) {
                check();
                V old = TopologyMap.this.put(getKey(), updated);
                expectedRevision = structuralChanges;
                super.setValue(updated);
                return old;
              }
            };
          }

          @Override
          public void remove() {
            check();
            if (current == null) throw new IllegalStateException();
            TopologyMap.this.remove(current);
            expectedRevision = structuralChanges;
            current = null;
          }
        };
      }
    };
  }
}
