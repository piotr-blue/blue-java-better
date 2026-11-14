package blue.language;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.BlueException;
import blue.BlueId;
import blue.Language;
import blue.LanguageException;
import blue.LanguageRecord;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Named selection preserves contextual paths, lazy evidence and inherited values. */
@Tag("language")
class ViewSelectionTest {
  private final Language language = Language.create();

  @Test
  void fieldNamesAreLiteralAndEmptyNamesDoNotSelectTheRoot() {
    var document = language.readJson("{\"a/b\":{\"~1\":7},\"\":9}");
    var view = document.resolve();
    assertEquals(7, view.field("a/b").field("~1").integer());
    assertEquals(document.at("/a~1b/~01").integer(), view.field("a/b").field("~1").integer());
    assertEquals(9, view.field("").integer());
    assertFalse(view.field("a").exists());
    assertThrows(NullPointerException.class, () -> view.field(null));
    assertEquals(document.blueId(), view.content().orElseThrow().blueId());
  }

  @Test
  void itemsUseContextualBluePathsAndPreserveInheritedElements() {
    var slots =
        language.defineYaml("type: List\nmergePolicy: positional\nitems: [{type: Integer}, fixed]");
    var completed = slots.specializeYaml("items: [{$pos: 0, value: 7}]");
    var view = completed.resolve();
    assertEquals(7, view.item(0).integer());
    assertEquals("fixed", view.item(1).text());
    assertEquals(
        completed.at("/items/1").content().orElseThrow().blueId(),
        view.item(1).content().orElseThrow().blueId());
    assertFalse(view.item(2).exists());
    assertFalse(view.item(Integer.MAX_VALUE).exists());
    assertThrows(IllegalArgumentException.class, () -> view.item(-1));
    assertThrows(BlueException.class, () -> view.item(2).integer());
  }

  @Test
  void namedSelectionDoesNotFetchUntilObservedOrConfuseUnavailableWithAbsent() {
    var offered = language.readYaml("orders: [{total: 42}]");
    Map<BlueId, LanguageRecord> records = new ConcurrentHashMap<>();
    var reads = new AtomicInteger();
    var reader =
        Language.newBuilder()
            .provider(
                id -> {
                  reads.incrementAndGet();
                  return Optional.ofNullable(records.get(id));
                })
            .build();
    var root = reader.reference(offered.blueId()).resolve();
    var total = root.field("orders").item(0).field("total");
    assertEquals(0, reads.get());
    assertThrows(IllegalArgumentException.class, () -> root.item(-1));
    assertEquals(0, reads.get());
    assertTrue(assertThrows(LanguageException.class, total::exists).incomplete());
    records.putAll(offered.records());
    assertEquals(42, total.integer());
    assertFalse(root.field("orders").item(1).exists());
  }

  @Test
  void selectingSiblingsDoesNotChangeTheParentView() {
    var document = language.readYaml("orders: [{total: 10}, {total: 20}]\nstatus: open");
    var root = document.resolve();
    var orders = root.field("orders");
    assertEquals(10, orders.item(0).field("total").integer());
    assertEquals(20, orders.item(1).field("total").integer());
    assertEquals(2, orders.size());
    assertEquals("open", root.field("status").text());
    assertEquals(document.blueId(), root.content().orElseThrow().blueId());
  }
}
