package blue;

import java.util.AbstractMap;
import java.util.Map;
import java.util.Set;

/** Operation-local mutations with constant-time, immutable snapshots. */
final class LanguageMap<K, V> extends AbstractMap<K, V> {
  private LanguageTrie<K, V> values;

  LanguageMap() {
    values = LanguageTrie.empty();
  }

  LanguageMap(Map<K, V> source) {
    values = immutable(source);
  }

  static <K, V> LanguageTrie<K, V> immutable(Map<K, V> source) {
    if (source instanceof LanguageMap<K, V> local) {
      return local.values;
    }
    if (source instanceof LanguageTrie<K, V> persistent) {
      return persistent;
    }
    LanguageTrie<K, V> result = LanguageTrie.empty();
    for (var entry : source.entrySet()) {
      result = result.with(entry.getKey(), entry.getValue());
    }
    return result;
  }

  void reset(Map<K, V> source) {
    values = immutable(source);
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
  public Set<Entry<K, V>> entrySet() {
    return values.entrySet();
  }

  @Override
  public V put(K key, V value) {
    V previous = values.get(key);
    // Re-registering retained evidence must not rebuild the immutable map path.
    // Identity is sufficient; distinct values still replace the prior instance.
    if (previous != value || value == null && !values.containsKey(key)) {
      values = values.with(key, value);
    }
    return previous;
  }

  @Override
  @SuppressWarnings("unchecked")
  public void putAll(Map<? extends K, ? extends V> source) {
    if (source.isEmpty() || source == this || source == values) {
      return;
    }
    if (source instanceof LanguageMap<?, ?> local) {
      source = (Map<? extends K, ? extends V>) local.values;
    }
    if (source instanceof LanguageTrie<?, ?> persistent) {
      values = values.union((LanguageTrie<K, V>) persistent);
      return;
    }
    for (Entry<? extends K, ? extends V> entry : source.entrySet()) {
      put(entry.getKey(), entry.getValue());
    }
  }

  @Override
  public V remove(Object key) {
    V previous = values.get(key);
    values = values.without(key);
    return previous;
  }

  @Override
  public void clear() {
    values = LanguageTrie.empty();
  }
}
