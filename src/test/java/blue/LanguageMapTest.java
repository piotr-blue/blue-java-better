package blue;

import static blue.Language.Goal.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("language")
class LanguageMapTest {
  @Test
  void persistentEvidenceSnapshotsShareWithoutLeakingLaterMutations() {
    var map = new LanguageMap<String, String>();
    map.put("first", "one");
    Map<String, String> snapshot = LanguageMap.immutable(map);
    assertSame(snapshot, LanguageMap.immutable(snapshot));
    var child = new LanguageMap<>(snapshot);
    child.put("second", "two");
    assertFalse(snapshot.containsKey("second"));
    assertEquals("one", child.get("first"));
    assertThrows(UnsupportedOperationException.class, () -> snapshot.put("third", "three"));
  }

  @Test
  void registeringTheSameEvidenceRetainsItsSnapshotButDistinctInstancesReplaceIt() {
    var map = new LanguageMap<String, String>();
    String first = new String("same content");
    map.put("body", first);
    Map<String, String> snapshot = LanguageMap.immutable(map);
    assertSame(first, map.put("body", first));
    map.putAll(Map.of("body", first));
    map.putAll(map);
    map.putAll(snapshot);
    assertSame(snapshot, LanguageMap.immutable(map));

    String second = new String("same content");
    assertSame(first, map.put("body", second));
    assertSame(second, map.get("body"));
    assertSame(first, snapshot.get("body"));
    map.putAll(Map.of("other", "new content"));
    assertEquals(2, map.size());
    assertFalse(snapshot.containsKey("other"));
  }

  @Test
  void nullValuesRemainPresentAndCanBeReplacedOrRemoved() {
    var map = new LanguageMap<String, String>();
    assertNull(map.put(null, null));
    assertNull(map.put("body", null));
    Map<String, String> snapshot = LanguageMap.immutable(map);
    assertTrue(snapshot.containsKey(null));
    assertTrue(snapshot.containsKey("body"));
    map.putAll(snapshot);
    assertSame(snapshot, LanguageMap.immutable(map));
    assertNull(map.put("body", "content"));
    assertEquals("content", map.put("body", null));
    assertTrue(map.containsKey("body"));
    map.remove("body");
    assertFalse(map.containsKey("body"));
    assertTrue(snapshot.containsKey("body"));
  }
}
