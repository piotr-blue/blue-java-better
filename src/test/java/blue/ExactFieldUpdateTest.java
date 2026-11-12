package blue;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;

class ExactFieldUpdateTest {
  @Test
  void randomizedEditsPreserveImmutableValuesAndHashes() {
    var random = new Random(0x714ba510L);
    var expected = new TreeMap<String, Object>();
    var actual = new ExactNode.Value(expected);
    List<Object> values =
        List.of(
            "text",
            true,
            false,
            BigInteger.ZERO,
            BigInteger.valueOf(-17),
            2.5d,
            new ExactNode.NumberToken("1.25"),
            new ExactNode.Ref("test-reference"),
            ExactNode.value(Map.of("value", "child")));
    for (int i = 0; i < 2000; i++) {
      String key = "field-" + random.nextInt(256);
      Object next = random.nextInt(5) == 0 ? null : values.get(random.nextInt(values.size()));
      var before = actual;
      var frozen = new TreeMap<>(actual.fields());
      if (next == null) expected.remove(key);
      else expected.put(key, next);
      actual = actual.with(key, next);
      var rebuilt = new ExactNode.Value(expected);
      assertEquals(rebuilt, actual);
      assertEquals(rebuilt.hashCode(), actual.hashCode());
      assertEquals(frozen, before.fields());
      assertThrows(
          UnsupportedOperationException.class, () -> before.fields().put("bad", "mutation"));
    }
  }

  @Test
  void replacementDoesNotCarryAStaleIdentityAndPreservesNullRemoval() {
    var initial = new TreeMap<String, Object>();
    initial.put("x", null);
    var value = new ExactNode.Value(initial);
    value.identity = "old-cached-identity";
    var removed = value.with("x", null);
    assertFalse(removed.has("x"));
    assertEquals(0, removed.hashCode());
    assertNull(removed.identity);
    var changed = removed.with("value", "new");
    assertNull(changed.identity);
    assertEquals(new ExactNode.Value(Map.of("value", "new")), changed);
    assertSame(changed, changed.with("value", "new"));
    assertSame(changed, changed.with("missing", null));
  }

  @Test
  void itemsAreDefensivelyCopiedAndMalformedValuesRemainRejected() {
    var items = new ArrayList<ExactNode>();
    items.add(ExactNode.value(Map.of("value", "one")));
    var value = new ExactNode.Value(Map.of()).with("items", items);
    var snapshot = new ExactNode.Value(Map.of("items", List.copyOf(items)));
    items.clear();
    assertEquals(snapshot, value);
    assertEquals(snapshot.hashCode(), value.hashCode());
    assertThrows(IllegalArgumentException.class, () -> value.with("items", List.of("invalid")));
    assertThrows(
        IllegalArgumentException.class,
        () -> value.with("other", List.of(ExactNode.value(Map.of()))));
    assertThrows(IllegalArgumentException.class, () -> value.with("other", new Object()));
    assertThrows(NullPointerException.class, () -> value.with(null, "value"));
  }

  @Test
  void javaHashCollisionsDoNotCollapseDifferentFields() {
    assertEquals("Aa".hashCode(), "BB".hashCode());
    var a = new ExactNode.Value(Map.of("Aa", "left", "BB", "right"));
    var b = a.with("Aa", "right");
    assertNotEquals(a, b);
    assertEquals(
        new ExactNode.Value(Map.of("Aa", "right", "BB", "right")).hashCode(), b.hashCode());
    assertEquals(new ExactNode.Value(Map.of("BB", "right")), b.with("Aa", null));
  }
}
