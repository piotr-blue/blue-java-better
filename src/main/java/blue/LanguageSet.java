package blue;

import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.Set;

/** Caller-confined additions with constant-time snapshots of completed semantic facts. */
final class LanguageSet<E> extends AbstractSet<E> {
  private LanguageTrie<E, Boolean> values;

  LanguageSet(Set<E> source) {
    if (source instanceof LanguageSet<E> local) {
      values = local.values;
    } else {
      values = LanguageTrie.empty();
      addAll(source);
    }
  }

  @Override
  public boolean contains(Object value) {
    return values.containsKey(value);
  }

  @Override
  public boolean add(E value) {
    LanguageTrie<E, Boolean> before = values;
    values = values.with(value, Boolean.TRUE);
    return values != before;
  }

  @Override
  public boolean addAll(Collection<? extends E> source) {
    if (source instanceof LanguageSet<?> local) {
      @SuppressWarnings("unchecked")
      LanguageTrie<E, Boolean> added = (LanguageTrie<E, Boolean>) local.values;
      int before = values.size();
      values = values.union(added);
      return values.size() != before;
    }
    return super.addAll(source);
  }

  @Override
  public boolean remove(Object value) {
    LanguageTrie<E, Boolean> before = values;
    values = values.without(value);
    return values != before;
  }

  @Override
  public int size() {
    return values.size();
  }

  @Override
  public Iterator<E> iterator() {
    return values.keySet().iterator();
  }

  @Override
  public void clear() {
    values = LanguageTrie.empty();
  }
}
