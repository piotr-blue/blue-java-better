package blue;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("language")
class LanguageTrieTest {
  @Test
  void randomizedBranchesAgreeWithOrdinaryMapUnionIncludingCollisionsAndRemovals() {
    Random random = new Random(0x173cafe);
    record Key(int number) {
      public int hashCode() {
        return number % 3 == 0 ? 7 : Integer.rotateLeft(number, 27);
      }
    }
    List<Key> keys = new ArrayList<>();
    keys.add(null);
    for (int n = 0; n < 160; n++) keys.add(new Key(n));
    LanguageTrie<Key, String> left = LanguageTrie.empty();
    Map<Key, String> expected = new HashMap<>();
    for (int round = 0; round < 400; round++) {
      LanguageTrie<Key, String> right = round % 2 == 0 ? left : LanguageTrie.empty();
      Map<Key, String> other = round % 2 == 0 ? new HashMap<>(expected) : new HashMap<>();
      LanguageTrie<Key, String> snapshot = left;
      Map<Key, String> old = new HashMap<>(expected);
      for (int n = 0; n < 30; n++) {
        Key key = keys.get(random.nextInt(keys.size()));
        if (random.nextBoolean()) {
          String value = n % 4 == 0 ? null : new String("v" + round + ":" + n);
          right = right.with(key, value);
          other.put(key, value);
        } else {
          right = right.without(key);
          other.remove(key);
        }
      }
      if (round % 3 == 0) {
        Key key = keys.get(random.nextInt(keys.size()));
        left = left.without(key);
        expected.remove(key);
      }
      left = left.union(right);
      expected.putAll(other);
      assertEquals(old, snapshot, "snapshot at round " + round);
      assertEquals(expected, left, "union at round " + round);
      assertEquals(left, expected);
      assertEquals(expected.hashCode(), left.hashCode());
      for (Key key : keys) {
        assertEquals(expected.containsKey(key), left.containsKey(key));
        assertSame(expected.get(key), left.get(key));
      }
    }
  }

  @Test
  void unionOfLargeSiblingSnapshotsVisitsOnlyChangedBranches() {
    final class Key {
      static int comparisons;
      final int number;

      Key(int number) {
        this.number = number;
      }

      public int hashCode() {
        return number;
      }

      public boolean equals(Object other) {
        comparisons++;
        return other instanceof Key k && k.number == number;
      }
    }
    LanguageTrie<Key, Object> base = LanguageTrie.empty();
    Object value = new Object();
    for (int i = 0; i < 50_000; i++) base = base.with(new Key(i), value);
    Object replacement = new Object();
    LanguageTrie<Key, Object> left =
        base.with(new Key(0), replacement).with(new Key(50_001), value);
    LanguageTrie<Key, Object> right = base.with(new Key(50_002), value).without(new Key(1));
    Key.comparisons = 0;
    LanguageTrie<Key, Object> merged = left.union(right);
    assertTrue(Key.comparisons < 30, "walked unchanged evidence: " + Key.comparisons);
    assertEquals(50_002, merged.size());
    assertSame(value, merged.get(new Key(0)), "unchanged right value overrides changed left value");
    assertSame(value, merged.get(new Key(1)), "absence on right must not remove a left entry");
    assertSame(replacement, left.get(new Key(0)));
    assertSame(merged, merged.union(merged));
  }

  @Test
  void snapshotsAndTheirEntriesRejectMutation() {
    LanguageTrie<String, String> map = LanguageTrie.<String, String>empty().with("key", "value");
    assertThrows(UnsupportedOperationException.class, () -> map.put("key", "changed"));
    assertThrows(UnsupportedOperationException.class, () -> map.remove("key"));
    assertThrows(
        UnsupportedOperationException.class,
        () -> map.entrySet().iterator().next().setValue("changed"));
    assertSame(map, map.with("key", map.get("key")));
    assertSame(map, map.without("missing"));
    String equalValue = new String("value");
    assertSame(equalValue, map.with("key", equalValue).get("key"));
  }
}
