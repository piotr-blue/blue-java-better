package blue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Snapshot map views retain ordering, mutation invalidation and private copy ownership. */
class TopologyMapTest {
  @Test
  void exactDifferencesFollowForksReversionsAndBoundedHistory() {
    var original = new TopologyMap<String, Integer>(true);
    original.putAll(Map.of("a", 1, "b", 2));
    var left = new TopologyMap<String, Integer>(true);
    left.copyFrom(original);
    var right = new TopologyMap<String, Integer>(true);
    right.copyFrom(original);
    left.put("a", 3);
    left.put("a", 1);
    left.remove("b");
    right.put("c", 4);
    assertEquals(Set.of("b"), left.changedKeys(original));
    assertEquals(Set.of("b", "c"), left.changedKeys(right));
    for (int i = 0; i < 4200; i++) left.put("counter", i);
    assertEquals(Set.of("b", "counter"), left.changedKeys(original));
    left.clear();
    assertEquals(Set.of("a", "b"), left.changedKeys(original));
  }

  @Test
  void oneChangedMemberDoesNotCompareEveryQuietSibling() {
    var comparisons = new java.util.concurrent.atomic.AtomicInteger();
    Comparator<Integer> comparator =
        (a, b) -> {
          comparisons.incrementAndGet();
          return a.compareTo(b);
        };
    var original = new TopologyMap<Integer, Integer>(true, comparator);
    for (int i = 0; i < 10000; i++) original.put(i, i);
    var changed = new TopologyMap<Integer, Integer>(true, comparator);
    changed.copyFrom(original);
    changed.put(875, -1);
    comparisons.set(0);
    assertEquals(Set.of(875), changed.changedKeys(original));
    assertTrue(comparisons.get() < 100, "A shared root must compare only its changed index paths");
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void liveViewsRemoveFromTheMapAndInvalidateAlreadyWarmedLookups(boolean keys) {
    var map = new TopologyMap<String, Integer>(true);
    map.putAll(Map.of("a", 1, "b", 2));
    Collection<?> view = keys ? map.keySet() : map.values();
    assertEquals(1, map.get("a"));
    Object structure = map.structure();
    var iterator = view.iterator();
    assertEquals(keys ? "a" : 1, iterator.next());
    iterator.remove();
    assertNotSame(structure, map.structure());
    assertNull(map.get("a"));
    assertEquals(2, map.get("b"));
    assertThrows(IllegalStateException.class, iterator::remove);
    map.put("c", 3);
    assertEquals(keys ? List.of("b", "c") : List.of(2, 3), new ArrayList<>(view));
    assertTrue(view.remove(keys ? "b" : 2));
    assertNull(map.get("b"));
    view.clear();
    assertTrue(map.isEmpty());
    assertNull(map.get("c"));
    assertThrows(UnsupportedOperationException.class, () -> map.values().add(4));
  }

  @Test
  void traversalRetainsComparatorOrderAndLiveValueReplacements() {
    var map = new TopologyMap<String, Integer>(true, Comparator.reverseOrder());
    map.putAll(Map.of("a", 1, "b", 2, "c", 3));
    var keys = map.keySet();
    var values = map.values();
    var observed = new ArrayList<String>();
    map.forEach((key, value) -> observed.add(key + value));
    assertEquals(List.of("c3", "b2", "a1"), observed);
    assertEquals(List.of("c", "b", "a"), new ArrayList<>(keys));
    assertEquals(List.of(3, 2, 1), new ArrayList<>(values));
    Object structure = map.structure();
    assertEquals(2, map.get("b"));
    map.put("b", 9);
    assertSame(structure, map.structure());
    assertEquals(List.of(3, 9, 1), new ArrayList<>(values));
    assertEquals(9, map.get("b"));
    assertThrows(NullPointerException.class, () -> map.forEach(null));
  }

  @Test
  void copiedMapsAndEntryWritesKeepTheirOwnViewsAndStructuralGenerations() {
    var original = new TopologyMap<String, Integer>(false);
    original.putAll(Map.of("a", 1, "b", 2));
    var copy = new TopologyMap<String, Integer>(false);
    copy.copyFrom(original);
    Object structure = original.structure();
    assertSame(structure, copy.structure());
    assertEquals(1, copy.get("a"));
    copy.entrySet().iterator().next().setValue(4);
    assertNotSame(structure, copy.structure());
    assertSame(structure, original.structure());
    assertEquals(4, copy.get("a"));
    assertEquals(1, original.get("a"));
    assertTrue(copy.values().removeIf(value -> value == 2));
    assertNull(copy.get("b"));
    assertEquals(List.of(1, 2), new ArrayList<>(original.values()));
    assertEquals(List.of(4), new ArrayList<>(copy.values()));
  }

  @Test
  void anExistingIteratorSeesValueReplacementsWithoutTreatingThemAsKeyChanges() {
    var map = new TopologyMap<String, Integer>(false);
    map.putAll(Map.of("a", 1, "b", 2));
    var iterator = map.values().iterator();
    assertEquals(1, iterator.next());
    map.put("b", 3);
    assertEquals(3, iterator.next());
    assertFalse(iterator.hasNext());
  }
}
