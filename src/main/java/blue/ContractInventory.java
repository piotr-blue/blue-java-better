package blue;

import java.util.*;
import java.util.function.Function;

/** Append-only invocation evidence, ordered and encoded once when each identity is admitted. */
final class ContractInventory extends AbstractSet<String> {
  private final Set<String> identities = new HashSet<>();
  private final NavigableMap<String, ContractCanonical.Fragment> ordered;
  private final Function<String, Object> record;
  private final boolean identityOrder;

  ContractInventory() {
    this(new TreeMap<>(), value -> value, true);
  }

  ContractInventory(Function<String, Object> record) {
    this(new TreeMap<>(), record, false);
  }

  ContractInventory(
      NavigableMap<String, ContractCanonical.Fragment> ordered,
      Function<String, Object> record,
      boolean identityOrder) {
    this.ordered = ordered;
    this.record = record;
    this.identityOrder = identityOrder;
  }

  @Override
  public boolean add(String identity) {
    if (identities.contains(identity)) return false;
    Object value = record.apply(identity);
    var fragment = new ContractCanonical.Fragment(value);
    ordered.put(identityOrder ? identity : ContractWire.write(value), fragment);
    identities.add(identity);
    return true;
  }

  @Override
  public boolean contains(Object identity) {
    return identities.contains(identity);
  }

  @Override
  public int size() {
    return identities.size();
  }

  ContractCanonical.Sequence encoding() {
    return new ContractCanonical.Sequence(ordered.values());
  }

  @Override
  public Iterator<String> iterator() {
    return Collections.unmodifiableSet(identities).iterator();
  }
}
