package blue;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Retention bounds and replacement semantics for immutable physical preparation. */
@Tag("contracts")
class ContractCacheTest {
  @Test
  void repeatedFactRefreshesRecencyWithoutChangingItsRetainedWeight() {
    var cache = new ContractCache<String, Boolean>("test", 10, 2, 10);
    cache.put("first", Boolean.TRUE, 4);
    cache.put("second", Boolean.TRUE, 4);
    for (int i = 0; i < 100; i++) cache.put("first", Boolean.TRUE, 4);
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
