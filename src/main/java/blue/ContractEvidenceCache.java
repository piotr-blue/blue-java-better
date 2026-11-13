package blue;

import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Bounded reuse of independently verified exact JSON responses. A cache key binds the complete
 * response text, claimed identity and host limits; an identity alone never authenticates a
 * response. Source environments and cyclic proofs deliberately use the ordinary verifier.
 */
@NullMarked
final class ContractEvidenceCache {
  record Key(String identity, String text, Map<String, Long> limits) {}

  private static final long MAX_ENTRY_BYTES = 1024L * 1024;
  private static final ContractCache<Key, Map<String, ExactNode.Value>> VERIFIED =
      new ContractCache<>("contracts.verifiedResponses", 32L * 1024 * 1024, 2048, MAX_ENTRY_BYTES);

  private ContractEvidenceCache() {}

  static @Nullable Key key(
      String id, @Nullable Object response, Map<String, Long> limits, boolean reuse) {
    if (!reuse || DiagnosticSwitches.nativeReuseDisabled() || id.contains("#")) {
      return null;
    }
    if (!(response instanceof Map<?, ?> map)
        || !Set.of("text", "mode", "format").containsAll(map.keySet())
        || !(map.get("text") instanceof String text)
        || map.containsKey("mode") && !"exact".equals(map.get("mode"))
        || map.containsKey("format") && !"json".equals(map.get("format"))
        || text.length() > MAX_ENTRY_BYTES / 2) {
      return null;
    }
    return new Key(id, text, limits);
  }

  static @Nullable Map<String, ExactNode.Value> get(@Nullable Key key) {
    return key == null ? null : VERIFIED.get(key);
  }

  static void put(@Nullable Key key, Map<String, ExactNode.Value> bodies) {
    if (key == null) {
      return;
    }
    long weight = 1024 + 2L * (key.text().length() + key.identity().length());
    long bodyWeight = ContractCache.weight(bodies, MAX_ENTRY_BYTES);
    if (bodyWeight == Long.MAX_VALUE || bodyWeight > MAX_ENTRY_BYTES - weight) {
      return;
    }
    VERIFIED.put(key, Map.copyOf(bodies), weight + bodyWeight);
  }
}
