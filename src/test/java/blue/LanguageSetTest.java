package blue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("language")
class LanguageSetTest {
  private record Key(int value) {
    @Override
    public int hashCode() {
      return value % 4;
    }
  }

  @Test
  void snapshotsAndUnionsMatchSetsAcrossCollisionsAndRemoval() {
    Set<Key> expected = new HashSet<>();
    LanguageSet<Key> actual = new LanguageSet<>(Set.of());
    Random random = new Random(190);
    for (int i = 0; i < 2000; i++) {
      Key key = random.nextInt(10) == 0 ? null : new Key(random.nextInt(100));
      if (random.nextBoolean()) assertEquals(expected.add(key), actual.add(key));
      else assertEquals(expected.remove(key), actual.remove(key));
      assertEquals(expected, actual);
      if (i % 50 == 0) {
        LanguageSet<Key> snapshot = new LanguageSet<>(actual);
        Set<Key> frozen = new HashSet<>(expected);
        Key privateKey = new Key(1000 + i);
        snapshot.add(privateKey);
        assertFalse(actual.contains(privateKey));
        frozen.add(privateKey);
        assertEquals(frozen, snapshot);
        assertEquals(expected.addAll(frozen), actual.addAll(snapshot));
        assertFalse(actual.addAll(actual));
        snapshot.clear();
        assertEquals(expected, actual);
      }
    }
  }
}
