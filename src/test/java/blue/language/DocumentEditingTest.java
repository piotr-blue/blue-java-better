package blue.language;

import static org.junit.jupiter.api.Assertions.*;

import blue.BlueException;
import blue.BlueId;
import blue.Language;
import blue.LanguageDocument;
import blue.LanguageException;
import blue.Node;
import java.math.BigInteger;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("language")
class DocumentEditingTest {
  private final Language language = Language.create();

  @Test
  void editsAreAtomicAndMustRespectInheritedRestrictions() {
    LanguageDocument type =
        language.defineYaml("age: {type: Integer, schema: {minimum: 0}}\ncountry: PL");
    LanguageDocument original = type.specializeYaml("age: 42\nnotes: [A, B, C]");
    LanguageDocument edited =
        original.edit(editor -> editor.set("/age/value", 43).remove("/notes/items/1"));
    assertEquals(43, edited.at("/age").integer());
    assertEquals(2, edited.at("/notes").size());
    assertEquals(42, original.at("/age").integer());
    assertEquals(3, original.at("/notes").size());
    assertThrows(
        LanguageException.class, () -> original.edit(editor -> editor.set("/age/value", -1)));
    assertThrows(
        LanguageException.class, () -> original.edit(editor -> editor.set("/country", "US")));
    assertThrows(
        IllegalStateException.class,
        () ->
            original.edit(
                editor -> {
                  editor.set("/age/value", 99);
                  throw new IllegalStateException("stop");
                }));
    assertEquals(42, original.at("/age").integer());
    assertEquals(
        Language.Goal.DEFINITION, type.edit(editor -> editor.set("/name", "Person")).goal());
  }

  @Test
  void editingCollapsedAncestorsUsesCopyOnWriteAndKeepsOtherBranchesCollapsed() {
    LanguageDocument source = language.readYaml("left: {value: 9}\nright: {value: 9}");
    LanguageDocument collapsed = source.collapse("/left", "/right");
    LanguageDocument edited = collapsed.edit(editor -> editor.set("/left/value", 10));
    assertEquals(10, edited.at("/left").integer());
    assertEquals(9, edited.at("/right").integer());
    assertEquals(9, collapsed.at("/left").integer());
    assertTrue(edited.node().at("/right/blueId").kind() == Node.Kind.TEXT);
    assertEquals(source.blueId(), collapsed.blueId());
    assertNotEquals(source.blueId(), edited.blueId());
    assertEquals(
        edited.blueId(), collapsed.collapse("").edit(e -> e.set("/left/value", 10)).blueId());
    assertEquals(
        edited.blueId(), collapsed.expand("/left").edit(e -> e.set("/left/value", 10)).blueId());
  }

  @Test
  void nativeScalarEditsRetainNumberCategoriesAndLargeIntegers() {
    LanguageDocument original = language.readYaml("number: 1\nflag: false\nlabel: before");
    LanguageDocument edited =
        original.edit(e -> e.set("/number", 0.5).set("/flag", true).set("/label", "after"));
    assertEquals(0.5, edited.at("/number").number());
    assertTrue(edited.at("/flag").bool());
    assertEquals("after", edited.at("/label").text());
    BigInteger large = new BigInteger("9007199254740993");
    LanguageDocument wide = original.edit(e -> e.set("/number", Node.integer(large)));
    assertEquals(large, wide.at("/number").bigInteger());
  }

  @Test
  void invalidPathsAndNewMissingReferencesCannotCreateAnAdmittedDocument() {
    LanguageDocument original = language.readYaml("value: 7");
    assertThrows(BlueException.class, () -> original.edit(e -> e.set("/missing/child", 8)));
    assertThrows(BlueException.class, () -> original.edit(e -> e.remove("/missing")));
    BlueId absent = language.readYaml("unknown: 99").blueId();
    assertTrue(
        assertThrows(
                LanguageException.class,
                () ->
                    original.edit(
                        e -> e.set("", Node.object(Map.of("blueId", Node.text(absent.value()))))))
            .incomplete());
  }

  @Test
  void nativeEditingEnforcesInputLimitsAndKeepsOriginalOnFailure() {
    Language bounded =
        Language.newBuilder()
            .limits(new Language.Limits(100_000, 8, 100, 100, 100_000, 100, 100))
            .build();
    LanguageDocument original = bounded.readYaml("value: 1");
    Node deep = Node.integer(1);
    for (int i = 0; i < 10; i++) {
      deep = Node.object(Map.of("child", deep));
    }
    Node replacement = deep;
    LanguageException failure =
        assertThrows(LanguageException.class, () -> original.edit(e -> e.set("", replacement)));
    assertTrue(failure.incomplete());
    assertEquals(1, original.at("").integer());
  }
}
