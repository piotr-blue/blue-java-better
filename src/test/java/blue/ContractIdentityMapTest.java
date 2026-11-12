package blue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Mutable ownership must never leak into a captured evidence snapshot. */
@Tag("contracts")
class ContractIdentityMapTest {
  @Test
  void branchingSnapshotsRetainIdentityKeysAndNullValuesAcrossUpdatesAndRemovals() {
    Random random = new Random(194);
    Object[] keys = new Object[256];
    keys[0] = null;
    for (int i = 1; i < keys.length; i++) keys[i] = new String("equal");
    List<ContractIdentityMap<Integer>> maps = new ArrayList<>();
    List<IdentityHashMap<Object, Integer>> expected = new ArrayList<>();
    maps.add(new ContractIdentityMap<>());
    expected.add(new IdentityHashMap<>());
    for (int step = 0; step < 4000; step++) {
      int selected = random.nextInt(maps.size());
      ContractIdentityMap<Integer> map = maps.get(selected);
      IdentityHashMap<Object, Integer> model = expected.get(selected);
      Object key = keys[random.nextInt(keys.length)];
      if (step % 100 == 0) {
        maps.add(map.fork());
        expected.add(new IdentityHashMap<>(model));
      } else if (random.nextInt(3) == 0) {
        map.remove(key);
        model.remove(key);
      } else {
        Integer value = random.nextBoolean() ? null : random.nextInt(7);
        map.put(key, value);
        model.put(key, value);
      }
      if (step % 50 == 0) verify(maps, expected, keys);
    }
    verify(maps, expected, keys);
    for (int i = 0; i < maps.size(); i++) {
      ContractIdentityMap<Integer> snapshot = maps.get(i).fork();
      int size = maps.get(i).size();
      int keep = size / 2;
      assertEquals(size - keep, maps.get(i).trim(keep));
      assertEquals(keep, maps.get(i).size());
      for (Object key : keys) {
        assertEquals(expected.get(i).containsKey(key), snapshot.containsKey(key));
        assertEquals(expected.get(i).get(key), snapshot.get(key));
        if (maps.get(i).containsKey(key)) assertTrue(snapshot.containsKey(key));
      }
      assertEquals(keep, maps.get(i).trim(0));
      assertEquals(0, maps.get(i).size());
      assertEquals(size, snapshot.size());
    }
  }

  private static void verify(
      List<ContractIdentityMap<Integer>> maps,
      List<IdentityHashMap<Object, Integer>> expected,
      Object[] keys) {
    for (int i = 0; i < maps.size(); i++) {
      assertEquals(expected.get(i).size(), maps.get(i).size());
      for (Object key : keys) {
        assertEquals(expected.get(i).containsKey(key), maps.get(i).containsKey(key));
        assertEquals(expected.get(i).get(key), maps.get(i).get(key));
      }
    }
  }
}
