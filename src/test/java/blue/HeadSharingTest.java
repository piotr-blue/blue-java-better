package blue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HeadSharingTest {
  @Test
  void randomDraftBranchesKeepTheirPublishedValuesAndLexicalOrder() {
    var random = new Random(920174);
    var draft = new HeadMap<Integer>();
    var model = new TreeMap<String, Integer>();
    var retained = new ArrayList<Map<String, Integer>>();
    var expected = new ArrayList<Map<String, Integer>>();
    for (int step = 0; step < 1000; step++) {
      String key = "field-" + random.nextInt(100);
      switch (random.nextInt(5)) {
        case 0 -> {
          draft.put(key, step);
          model.put(key, step);
        }
        case 1 -> {
          draft.remove(key);
          model.remove(key);
        }
        case 2 -> {
          draft.replaceAll((name, value) -> value + 1);
          model.replaceAll((name, value) -> value + 1);
        }
        case 3 -> {
          retained.add(HeadMap.freeze(draft));
          expected.add(new TreeMap<>(model));
          if (random.nextBoolean()) draft = new HeadMap<>(retained.getLast());
        }
        default -> {
          if (random.nextInt(20) == 0) {
            draft.clear();
            model.clear();
          } else {
            draft.putAll(Map.of(key, step));
            model.putAll(Map.of(key, step));
          }
        }
      }
      assertEquals(model, draft);
      assertEquals(List.copyOf(model.keySet()), List.copyOf(draft.keySet()));
    }
    assertEquals(expected, retained);
    for (var frozen : retained) {
      assertThrows(UnsupportedOperationException.class, () -> frozen.put("illegal", 1));
      if (!frozen.isEmpty())
        assertThrows(
            UnsupportedOperationException.class,
            () -> frozen.entrySet().iterator().next().setValue(1));
    }
  }

  @Test
  void headDraftMutationCannotAlterItsParentSiblingOrPublishedDescendant() {
    var draft = new LanguageSemantics.HeadDraft();
    var first = LanguageSemantics.recipe(new ExactNode.Ref(LanguageCore.CORE.get("Text")));
    var second = LanguageSemantics.recipe(new ExactNode.Ref(LanguageCore.CORE.get("Integer")));
    draft.fields.put("a", first);
    draft.labels.put("name", "Original");
    var parent = draft.build();
    var child = parent.copy();
    var sibling = parent.copy();
    child.fields.put("b", second);
    child.labels.put("name", "Child");
    var published = child.build();
    child.fields.clear();
    child.labels.clear();
    sibling.fields.replaceAll((name, value) -> second);
    draft.fields.put("later", second);
    assertEquals(Map.of("a", first), parent.fields);
    assertEquals(Map.of("a", first, "b", second), published.fields);
    assertEquals(Map.of("a", second), sibling.fields);
    assertEquals("Original", parent.labels.get("name"));
    assertEquals("Child", published.labels.get("name"));
  }

  @ParameterizedTest
  @ValueSource(ints = {10, 1000, 10000})
  void unchangedHeadSnapshotsDoNotAllocateProportionallyToFieldCount(int width) {
    var bean = ManagementFactory.getThreadMXBean();
    org.junit.jupiter.api.Assumptions.assumeTrue(bean instanceof com.sun.management.ThreadMXBean);
    var allocation = (com.sun.management.ThreadMXBean) bean;
    org.junit.jupiter.api.Assumptions.assumeTrue(allocation.isThreadAllocatedMemorySupported());
    allocation.setThreadAllocatedMemoryEnabled(true);
    var draft = new LanguageSemantics.HeadDraft();
    var recipe = LanguageSemantics.recipe(new ExactNode.Ref(LanguageCore.CORE.get("Text")));
    for (int i = 0; i < width; i++) draft.fields.put("field-" + i, recipe);
    var head = draft.build();
    for (int i = 0; i < 100; i++) head = head.copy().build();
    long thread = Thread.currentThread().threadId();
    long before = allocation.getThreadAllocatedBytes(thread);
    for (int i = 0; i < 100; i++) head = head.copy().build();
    long bytes = allocation.getThreadAllocatedBytes(thread) - before;
    assertEquals(width, head.fields.size());
    assertTrue(bytes < 2 * 1024 * 1024, "Unchanged head copies allocated " + bytes + " bytes");
  }
}
