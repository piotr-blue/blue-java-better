package blue;

import java.util.*;
import java.util.function.BiFunction;

/** Ordered draft fields with immutable snapshots; the first later mutation copies the tree. */
final class HeadMap<V> extends AbstractMap<String, V> {
  private Map<String, V> values;
  private Frozen<V> published;

  HeadMap() {
    values = new TreeMap<>();
  }

  HeadMap(Map<String, V> source) {
    if (source instanceof Frozen<V> frozen) {
      values = frozen.values;
      published = frozen;
    } else {
      values = new TreeMap<>(source);
    }
  }

  static <V> Map<String, V> freeze(Map<String, V> source) {
    if (source.isEmpty()) return Map.of();
    if (source instanceof Frozen<V>) return source;
    return source instanceof HeadMap<V> draft ? draft.freeze() : new HeadMap<>(source).freeze();
  }

  private Frozen<V> freeze() {
    if (published == null) published = new Frozen<>(values);
    return published;
  }

  private Map<String, V> writable() {
    if (published != null) {
      values = new TreeMap<>(values);
      published = null;
    }
    return values;
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
  public int size() {
    return values.size();
  }

  @Override
  public Set<Entry<String, V>> entrySet() {
    return values.isEmpty() ? Set.of() : freeze().entrySet();
  }

  @Override
  public V put(String key, V value) {
    return writable().put(key, value);
  }

  @Override
  public V remove(Object key) {
    return writable().remove(key);
  }

  @Override
  public void putAll(Map<? extends String, ? extends V> source) {
    if (!source.isEmpty() && source != this) writable().putAll(source);
  }

  @Override
  public void clear() {
    values = new TreeMap<>();
    published = null;
  }

  @Override
  public void replaceAll(BiFunction<? super String, ? super V, ? extends V> function) {
    writable().replaceAll(function);
  }

  private static final class Frozen<V> extends AbstractMap<String, V> {
    private final Map<String, V> values;

    Frozen(Map<String, V> owned) {
      values = Collections.unmodifiableMap(owned);
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
    public int size() {
      return values.size();
    }

    @Override
    public Set<Entry<String, V>> entrySet() {
      return values.entrySet();
    }
  }
}
