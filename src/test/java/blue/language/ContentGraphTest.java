package blue.language;

import static blue.Language.Goal.VALUE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.BlueException;
import blue.BlueId;
import blue.Language;
import blue.LanguageContent;
import blue.LanguageDocument;
import blue.LanguageException;
import blue.LanguageRecord;
import blue.Node;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("language")
class ContentGraphTest {
  private final Language language = Language.create();

  @Test
  void preparingAnotherOwnersMaterializationDoesNotImportItsPrivateProviderEvidence() {
    var child = language.readYaml("privateValue: 42");
    Language producer = Language.newBuilder().content(child).build();
    LanguageContent message =
        producer.prepare(
            Language.Input.exactJson("{\"child\":{\"blueId\":\"" + child.blueId() + "\"}}"));
    assertEquals(42, message.at("/child/privateValue").integer());
    LanguageContent copied = language.prepare(message);
    assertEquals(message.toJson(), copied.toJson());
    assertThrows(LanguageException.class, () -> copied.at("/child/privateValue").integer());
    LanguageContent inline =
        producer.prepare(Language.Input.exactJson("{\"child\":" + child.toJson() + "}"));
    assertEquals(42, language.prepare(inline).at("/child/privateValue").integer());
    LanguageContent collapsed = producer.collapse(inline, "/child");
    assertThrows(
        LanguageException.class,
        () -> language.prepare(collapsed).at("/child/privateValue").integer());
  }

  @Test
  void importingMaterializedContentRetainsInlineChildrenForIndependentReferenceReads() {
    LanguageDocument child = language.readYaml("answer: 42");
    LanguageContent original =
        language.prepare(
            Language.Input.exactJson("{\"rows\":[" + child.toJson() + "," + child.toJson() + "]}"));
    LanguageContent imported = Language.create().prepare(original);
    LanguageContent wire = Language.create().prepare(Language.Input.exactJson(original.toJson()));
    Language directReader = Language.newBuilder().content(imported).build();
    Language wireReader = Language.newBuilder().content(wire).build();
    assertEquals(
        wireReader.read(child.blueId()).toJson(), directReader.read(child.blueId()).toJson());
    assertEquals(42, directReader.read(child.blueId()).at("/answer").integer());
    assertEquals(wire.records().keySet(), imported.records().keySet());
    wire.records()
        .forEach(
            (id, record) -> assertEquals(record.toJson(), imported.records().get(id).toJson()));
    assertEquals(original.blueId(), imported.blueId());
  }

  @Test
  void importingACyclicReferenceDoesNotImportAnAttachedHiddenBody() {
    var set =
        language.finalizeSet(
            List.of(
                Language.Input.exactJson("{\"name\":\"Loop\",\"self\":{\"blueId\":\"this#0\"}}")),
            VALUE);
    var member = set.members().getFirst();
    var imported = Language.create().prepare(member);
    assertEquals(member.toJson(), imported.toJson());
    assertEquals(
        "ProviderUnavailable",
        assertThrows(LanguageException.class, () -> imported.at("/name").text()).category());
    var receiver =
        Language.newBuilder()
            .record(member.blueId(), LanguageRecord.cyclic(set.proofJson()))
            .build();
    assertEquals("Loop", receiver.prepare(member).at("/name").text());
  }

  @Test
  void preparedContentUsesTheReceiversProvidersWithoutChangingTheOriginal() {
    var child = language.readYaml("answer: 42");
    LanguageContent original =
        language.prepare(
            Language.Input.exactJson("{\"child\":{\"blueId\":\"" + child.blueId() + "\"}}"));
    assertThrows(LanguageException.class, () -> original.at("/child/answer").integer());
    Language receiver = Language.newBuilder().content(child).build();
    LanguageContent adopted = receiver.prepare(original);
    assertEquals(original.blueId(), adopted.blueId());
    assertEquals(original.toJson(), adopted.toJson());
    assertEquals(42, adopted.at("/child/answer").integer());
    assertFalse(adopted instanceof LanguageDocument);
    assertThrows(LanguageException.class, () -> original.at("/child/answer").integer());
  }

  @Test
  void expansionCollapseAndSplittingPreserveIdentity() {
    var original = language.readYaml("left: {amount: 42}\nright: hello");
    var collapsed = language.collapse(original, "/left");
    assertEquals(original.blueId(), collapsed.blueId());
    assertTrue(collapsed.node().get("left").get("blueId").kind() == Node.Kind.TEXT);
    var expanded = language.expand(collapsed, "/left");
    assertEquals(original.blueId(), expanded.blueId());
    assertEquals(original.toJson(), expanded.toJson());
    var fragments = language.split(original, "/left/amount");
    var builder = Language.newBuilder();
    fragments.records().forEach(builder::record);
    assertEquals(
        original.blueId(),
        builder.build().read(Language.Input.exactJson(fragments.rootJson()), VALUE).blueId());
    assertThrows(LanguageException.class, () -> language.collapse(original, "/left/amount/value"));
  }

  @Test
  void cyclicMembersKeepTheirOwningIdentityAndVerifyProofs() {
    var set =
        language.finalizeSet(
            List.of(
                Language.Input.exactJson("{\"name\":\"Loop\",\"self\":{\"blueId\":\"this#0\"}}")),
            VALUE);
    var member = set.members().getFirst();
    assertEquals("7Ydx3cm8uySLmzNyTcbpM5fufGaLSKnEs9cUcMMxRkeC#0", member.blueId().value());
    assertEquals(member.blueId(), language.expand(member, "").blueId());
    var reader =
        Language.newBuilder()
            .record(member.blueId(), LanguageRecord.cyclic(set.proofJson()))
            .build();
    var reopened = reader.read(Language.Input.exactJson(member.toJson()), VALUE);
    assertEquals(member.blueId(), reopened.blueId());
    assertTrue(reopened.resolve().at("/self/self").exists());
    var invalid =
        Language.newBuilder()
            .record(member.blueId(), LanguageRecord.cyclic(set.proofJson(), "{\"name\":\"Wrong\"}"))
            .build();
    assertThrows(
        LanguageException.class,
        () -> invalid.read(Language.Input.exactJson(member.toJson()), VALUE));
  }

  @Test
  void blueIdsValidateCanonicalMemberSuffixes() {
    var id = language.readJson("1").blueId();
    assertEquals(id + "#0", new BlueId(id + "#0").value());
    for (String suffix : List.of("#", "#-1", "#01", "#1#2", "#a")) {
      assertThrows(BlueException.class, () -> new BlueId(id + suffix));
    }
  }

  @Test
  void expandedTypeAndSpecificationKeepMeaningAcrossOperationsAndBuilders() {
    LanguageDocument type =
        language.defineYaml(
            "name: Person\nspecification: {name: Rules}\ncountry: PL\nage: {type: Integer}");
    LanguageDocument original =
        type.specializeYaml("age: 42\nspecification: {name: Instance rules}");
    LanguageDocument expanded =
        original.expand("/type", "/type/specification", "/specification", "/age/type");
    assertEquals("Person", expanded.node().at("/type/name").text());
    assertEquals("Instance rules", expanded.node().at("/specification/name").text());
    assertEquals("PL", expanded.at("/country").text());
    assertEquals("PL", language.prepare(expanded).at("/country").text());
    assertEquals(expanded.blueId(), language.prepare(expanded).blueId());
    assertEquals(42, expanded.at("/age").integer());
    assertEquals(original.resolve().specification(), expanded.resolve().specification());
    assertTrue(language.sameView(original, expanded));
    assertTrue(language.isSubtype(expanded, type));
    assertEquals(language.preserves(original, type), language.preserves(expanded, type));
    assertEquals(language.inspect(original), language.inspect(expanded));
    assertEquals(original.blueId(), language.admit(expanded, Language.Goal.VALUE).blueId());
    assertEquals(original.canonicalJson(), expanded.canonicalJson());
    assertEquals(original.blueId(), expanded.edit(e -> {}).blueId());
    Language receiver = Language.newBuilder().content(expanded).build();
    assertEquals("PL", receiver.read(expanded.blueId()).at("/country").text());
    assertEquals(43, expanded.edit(e -> e.set("/age/value", 43)).at("/age").integer());
    assertEquals(
        "PL", type.expand("/specification").specializeYaml("age: 42").at("/country").text());
  }

  @Test
  void preparedExpansionAlsoKeepsMeaningAndDoesNotImplyAdmission() {
    LanguageContent prepared =
        language.prepare(Language.Input.yaml("type: {country: PL}\nage: 42"));
    LanguageContent expanded = language.expand(prepared, "/type");
    assertFalse(expanded instanceof LanguageDocument);
    assertEquals("PL", expanded.at("/country").text());
    assertEquals(prepared.blueId(), expanded.blueId());
  }

  @Test
  void cyclicMembersRequireOwningSetEditsButCanRemainUntouched() {
    Language.CyclicSet loop =
        language.finalizeSet(
            List.of(Language.Input.exactJson("{\"self\":{\"blueId\":\"this#0\"}}")),
            Language.Goal.VALUE);
    LanguageDocument member = loop.members().getFirst();
    assertEquals(
        "InvalidOperation",
        assertThrows(LanguageException.class, () -> member.edit(e -> e.set("/name", "changed")))
            .category());
    Language reader = Language.newBuilder().content(member).build();
    LanguageDocument root =
        reader.readYaml("loop: {blueId: " + member.blueId() + "}\nnote: original");
    assertTrue(root.edit(e -> e.set("/note", "changed")).at("/loop/self/self").exists());
    assertEquals(
        "InvalidOperation",
        assertThrows(LanguageException.class, () -> root.edit(e -> e.set("/loop/name", "changed")))
            .category());
  }
}
