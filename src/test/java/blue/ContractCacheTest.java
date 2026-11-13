package blue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Retention bounds and replacement semantics for immutable physical preparation. */
@Tag("contracts")
class ContractCacheTest {
  @Test
  void oversizedContainersAreRejectedBeforeTheirEntriesAreEnumerated() {
    var largeMap =
        new java.util.AbstractMap<String, Object>() {
          @Override
          public int size() {
            return 100_000;
          }

          @Override
          public java.util.Set<Entry<String, Object>> entrySet() {
            throw new AssertionError("The map's shallow weight already exceeds the limit");
          }
        };
    var largeList =
        new java.util.AbstractList<Object>() {
          @Override
          public int size() {
            return 100_000;
          }

          @Override
          public Object get(int index) {
            throw new AssertionError("The list's shallow weight already exceeds the limit");
          }
        };
    assertEquals(Long.MAX_VALUE, ContractCache.weight(largeMap, 64 * 1024));
    assertEquals(Long.MAX_VALUE, ContractCache.weight(largeList, 64 * 1024));
  }

  @Test
  void cachedSubtreeWeightsRemainConservativeAtAndAroundTheLimit() {
    var leaf = ExactNode.value(Map.of("value", "harbor"));
    var pair = List.of(leaf, leaf);
    long single = ContractCache.weight(leaf);
    assertTrue(single > ContractCache.weight("harbor"));
    long shared = ContractCache.weight(pair);
    assertTrue(shared >= single * 2, "both paths retain the same immutable leaf");
    assertEquals(shared, ContractCache.weight(pair, shared));
    assertEquals(Long.MAX_VALUE, ContractCache.weight(pair, shared - 1));
    assertEquals(shared, ContractCache.weight(pair, shared + 1));
    assertEquals(single, ContractCache.weight(leaf, single));
    assertEquals(Long.MAX_VALUE, ContractCache.weight(leaf, single - 1));
    assertThrows(IllegalArgumentException.class, () -> ContractCache.weight(leaf, -1));
    assertEquals(0, ContractCache.weight(null, 0));
    assertEquals(Long.MAX_VALUE, ContractCache.weight("", 0));
  }

  @Test
  void unfinishedEstimateCannotPublishTheWeightOfAPartialSubtree() {
    var body = ExactNode.value(Map.of("value", "x".repeat(4000)));
    assertEquals(Long.MAX_VALUE, ContractCache.weight(body, 512));
    long measured = ContractCache.weight(body);
    assertTrue(measured >= 8000, "the retained text contributes its full character weight");
    assertEquals(measured, ContractCache.weight(body, measured));
    assertEquals(Long.MAX_VALUE, ContractCache.weight(body, measured - 1));
  }

  @Test
  void repeatedSharedBranchesCannotWrapANonnegativeWeight() {
    ExactNode.Value value = ExactNode.value(Map.of("value", "leaf"));
    long previous = ContractCache.weight(value, Long.MAX_VALUE);
    // Warm each immutable subtree once. The DAG has 65 nodes and exponentially many paths.
    for (int depth = 0; depth < 64; depth++) {
      value = ExactNode.value(Map.of("left", value, "right", value));
      long current = ContractCache.weight(value, Long.MAX_VALUE);
      assertTrue(current >= previous, "weight decreased at depth " + depth);
      if (current == Long.MAX_VALUE) {
        return;
      }
      previous = current;
    }
    throw new AssertionError("Expected the expanded graph weight to saturate");
  }

  @Test
  void repeatedFactRefreshesRecencyWithoutChangingItsRetainedWeight() {
    var cache = new ContractCache<String, Boolean>("test", 10, 2, 10);
    cache.put("first", Boolean.TRUE, 4);
    cache.put("second", Boolean.TRUE, 4);
    for (int i = 0; i < 100; i++) {
      cache.put("first", Boolean.TRUE, 4);
    }
    assertEquals(2, cache.size());
    assertEquals(8, cache.bytes());
    cache.put("third", Boolean.TRUE, 4);
    assertNull(cache.get("second"));
    assertSame(Boolean.TRUE, cache.get("first"));
    assertSame(Boolean.TRUE, cache.get("third"));
    assertEquals(8, cache.bytes());
  }

  @Test
  void replacementUsesIdentityAndReaccountsWeight() {
    var cache = new ContractCache<String, String>("test", 10, 4, 10);
    String first = new String("same"), replacement = new String("same");
    cache.put("first", first, 3);
    cache.put("second", "other", 4);
    cache.put("first", replacement, 7);
    assertSame(replacement, cache.get("first"));
    assertNull(cache.get("second"));
    assertEquals(7, cache.bytes());
    cache.put("first", replacement, 2);
    assertEquals(2, cache.bytes());
    cache.clear();
    assertEquals(0, cache.size());
    assertEquals(0, cache.bytes());
  }

  @Test
  void invalidAndOversizedCandidatesCannotEvictAValidEntry() {
    var cache = new ContractCache<String, Boolean>("test", 10, 2, 8);
    cache.put("valid", Boolean.TRUE, 4);
    cache.put("valid", Boolean.FALSE, 9);
    cache.put("negative", Boolean.TRUE, -1);
    cache.put("overflow", Boolean.TRUE, Long.MAX_VALUE);
    assertSame(Boolean.TRUE, cache.get("valid"));
    assertEquals(1, cache.size());
    assertEquals(4, cache.bytes());
    var disabled = new ContractCache<String, Boolean>("test", 0, 0, 0);
    disabled.put("fact", Boolean.TRUE, 0);
    assertEquals(0, disabled.size());
  }

  @Test
  void evictionDoesNotOverflowWhenTheConfiguredCapacityIsLongMaximum() {
    var cache = new ContractCache<String, Boolean>("test", Long.MAX_VALUE, 3, Long.MAX_VALUE);
    cache.put("first", Boolean.TRUE, Long.MAX_VALUE - 1);
    cache.put("second", Boolean.TRUE, 2);
    assertNull(cache.get("first"));
    assertEquals(2, cache.bytes());
  }
}
