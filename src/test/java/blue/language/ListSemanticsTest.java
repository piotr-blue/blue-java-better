package blue.language;

import static blue.Language.Goal.DEFINITION;
import static blue.Language.Goal.VALUE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import blue.BlueException;
import blue.Language;
import blue.LanguageDocument;
import blue.LanguageException;
import blue.Node;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("language")
class ListSemanticsTest {
  private final Language language = Language.create();

  @Test
  void positionalListsSupportPresentationEditsAndNestedRefinement() {
    var base =
        language.readYaml(
            """
            name: Items
            type: List
            mergePolicy: positional
            items:
              - {type: Integer}
              - fixed
            """,
            DEFINITION);
    var child = language.specialize(base, "items: [7, fixed, tail]", VALUE);
    assertEquals(3, child.resolve().size());
    assertEquals(7, child.resolve().at("/items/0").integer());
    var edited = language.specialize(base, "items: [{$pos: 0, value: 8}]", VALUE);
    assertEquals(8, edited.resolve().at("/items/0").integer());
    assertEquals("fixed", edited.resolve().at("/items/1").text());
    assertThrows(
        LanguageException.class, () -> language.specialize(base, "items: [7, changed]", VALUE));
  }

  @Test
  void appendOnlyListsCompileOnceAndCompactWireDoesNotAppendAgain() {
    var base =
        language.readYaml(
            """
            name: Log
            type: List
            mergePolicy: append-only
            items: [{$previousItems: false}, A]
            """,
            VALUE);
    var child = language.specialize(base, "items: [{$previousItems: true}, B]", VALUE);
    assertEquals(2, child.resolve().size());
    assertEquals("B", child.resolve().at("/items/1").text());
    var reader = Language.newBuilder().content(child).build();
    var exact = reader.read(Language.Input.exactJson(child.toJson()), VALUE);
    var compact = reader.read(Language.Input.compactJson(language.compactJson(child)), VALUE);
    assertEquals(child.blueId(), exact.blueId());
    assertEquals(child.blueId(), compact.blueId());
    assertEquals(2, compact.resolve().size());
    assertThrows(
        LanguageException.class,
        () -> language.specialize(base, "items: [{$previousItems: false}, B]", VALUE));
  }

  @Test
  void listFoldUsesPayloadIdentityAndWorksWithoutFetchingPrefix() {
    var prefix = language.readJson("[1,2]");
    var three = language.readJson("3");
    assertEquals(
        language.readJson("[1,2,3]").blueId(), language.foldList(prefix.blueId(), List.of(three)));
    assertEquals(prefix.blueId(), language.foldList(prefix.blueId(), List.of()));
  }

  @Test
  void typedListsAndDictionariesRejectIncompatiblePayloads() {
    var integers = language.readYaml("type: List\nitemType: Integer", DEFINITION);
    assertEquals(3, language.specialize(integers, "items: [1, 2, 3]", VALUE).resolve().size());
    assertThrows(
        LanguageException.class, () -> language.specialize(integers, "items: [1, wrong]", VALUE));
    var dictionary =
        language.readYaml("type: Dictionary\nkeyType: Text\nvalueType: Integer", DEFINITION);
    assertEquals(
        7, language.specialize(dictionary, "seven: 7", VALUE).resolve().at("/seven").integer());
    assertThrows(
        LanguageException.class, () -> language.specialize(dictionary, "seven: wrong", VALUE));
  }

  @Test
  void exportedEvidenceCanRebuildInheritedListsInAnIndependentEngine() {
    var base =
        language.readYaml(
            "type: List\nmergePolicy: append-only\nitems: [{$previousItems: false}, first]", VALUE);
    var second = language.specialize(base, "items: [{$previousItems: true}, second]", VALUE);
    var third = language.specialize(second, "items: [{$previousItems: true}, third]", VALUE);
    var builder = Language.newBuilder();
    third.records().forEach(builder::record);
    var restored = builder.build().read(Language.Input.exactYaml(third.toYaml()), VALUE);
    assertEquals(third.blueId(), restored.blueId());
    assertEquals(3, restored.resolve().size());
    assertEquals("third", restored.resolve().at("/items/2").text());
  }

  @Test
  void exactListEditsDoNotReplaySourceConstructionOrChangeUneditedIds() {
    LanguageDocument first =
        language.readYaml(
            "type: List\nmergePolicy: append-only\nitems: [{$previousItems: false}, A]");
    LanguageDocument second = first.specializeYaml("items: [{$previousItems: true}, B]");
    LanguageDocument edited = second.edit(e -> e.set("/description", "two entries"));
    assertEquals(2, edited.at("").size());
    assertEquals(second.blueId(), second.edit(e -> {}).blueId());
    assertThrows(LanguageException.class, () -> second.edit(e -> e.remove("/items/0")));
    Language receiver = Language.newBuilder().content(edited).build();
    LanguageDocument restored =
        receiver.read(Language.Input.compactJson(edited.canonicalJson()), Language.Goal.VALUE);
    assertEquals(edited.blueId(), restored.blueId());
    assertEquals(2, restored.at("").size());
  }

  @Test
  void removingListElementByThreeRoutesHasSameCanonicalForm() {
    LanguageDocument original = language.readYaml("values: [1, 2, 3]");
    LanguageDocument removed = original.edit(e -> e.remove("/values/items/1"));
    LanguageDocument replaced =
        original.edit(
            e -> e.set("/values/items", Node.list(List.of(Node.integer(1), Node.integer(3)))));
    LanguageDocument authored = language.readYaml("values: [1, 3]");
    assertEquals(authored.blueId(), removed.blueId());
    assertEquals(authored.blueId(), replaced.blueId());
    assertEquals(authored.canonicalJson(), removed.canonicalJson());
    assertEquals(authored.canonicalJson(), replaced.canonicalJson());
  }

  @Test
  void collapsedListsKeepTheSameEditingPathsAndRejectInvalidIndices() {
    LanguageDocument original = language.readYaml("values: [{count: 1}, {count: 2}]");
    LanguageDocument collapsed = original.collapse("/values", "/values/items/0");
    LanguageDocument edited = collapsed.edit(e -> e.set("/values/items/0/count/value", 3));
    assertEquals(3, edited.at("/values/items/0/count").integer());
    assertEquals(1, original.at("/values/items/0/count").integer());
    assertEquals(2, edited.node().at("/values/items").size());
    assertThrows(BlueException.class, () -> collapsed.edit(e -> e.remove("/values/items/9")));
    assertThrows(BlueException.class, () -> collapsed.edit(e -> e.set("/values/items/9/count", 3)));
    assertEquals(
        3, edited.edit(e -> e.set("/values/items/-", Node.integer(4))).at("/values").size());
  }
}
