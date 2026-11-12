package blue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Independent list model covers eviction, replacement, disabled bounds and hash collisions. */
@Tag("contracts")
class ContractCacheModelTest {
  private record Key(int value) {
    @Override
    public int hashCode() {
      return value % 3;
    }
  }

  private record Item(Key key, Object value, long weight) {}

  @ParameterizedTest
  @CsvSource({"0,4,4", "4,0,4", "4,4,0", "12,4,6", "30,16,10"})
  void randomizedOperationsAgreeWithAnIndependentRecencyList(
      long bytes, int entries, long entryBytes) {
    var cache = new ContractCache<Key, Object>("model", bytes, entries, entryBytes);
    var model = new ArrayList<Item>();
    var random = new Random(0xcaffeeL);
    for (int step = 0; step < 10_000; step++) {
      Key key = new Key(random.nextInt(24));
      switch (random.nextInt(20)) {
        case 0 -> {
          cache.clear();
          model.clear();
        }
        case 1, 2, 3, 4 -> assertSame(read(model, key), cache.get(key));
        default -> {
          Object value = new Object();
          long weight = random.nextInt(14) - 1L;
          cache.put(key, value, weight);
          if (bytes > 0
              && entries > 0
              && entryBytes > 0
              && weight >= 0
              && weight <= entryBytes
              && weight <= bytes) {
            model.removeIf(item -> item.key().equals(key));
            while (!model.isEmpty() && (model.size() >= entries || weight(model) + weight > bytes))
              model.removeFirst();
            model.add(new Item(key, value, weight));
          }
        }
      }
      assertEquals(model.size(), cache.size(), "entry bound after operation " + step);
      assertEquals(weight(model), cache.bytes(), "weight after operation " + step);
      if (step % 97 == 0)
        for (int n = 0; n < 24; n++) {
          Key candidate = new Key(n);
          assertSame(read(model, candidate), cache.get(candidate), "lookup at operation " + step);
        }
    }
  }

  @Test
  @Tag("concurrency")
  void concurrentCollidingKeysKeepTheirValuesAndExactWeight() throws Exception {
    var cache = new ContractCache<Key, Object>("concurrent", 8, 8, 1);
    var start = new CountDownLatch(1);
    try (var workers = Executors.newFixedThreadPool(8)) {
      var tasks = new ArrayList<Future<?>>();
      for (int worker = 0; worker < 8; worker++) {
        Key key = new Key(worker);
        tasks.add(
            workers.submit(
                () -> {
                  start.await();
                  for (int n = 0; n < 2_000; n++) {
                    Object value = new Object();
                    cache.put(key, value, 1);
                    assertSame(value, cache.get(new Key(key.value())));
                  }
                  return null;
                }));
      }
      start.countDown();
      for (var task : tasks) task.get(30, TimeUnit.SECONDS);
    }
    assertEquals(8, cache.size());
    assertEquals(8, cache.bytes());
    cache.clear();
    assertEquals(0, cache.size());
    assertEquals(0, cache.bytes());
  }

  private static Object read(List<Item> model, Key key) {
    for (int i = 0; i < model.size(); i++) {
      if (model.get(i).key().equals(key)) {
        Item found = model.remove(i);
        model.add(found);
        return found.value();
      }
    }
    return null;
  }

  private static long weight(List<Item> model) {
    return model.stream().mapToLong(Item::weight).sum();
  }
}
