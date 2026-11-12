package blue.contracts;

import static org.junit.jupiter.api.Assertions.*;

import blue.*;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("contracts")
class InspectionTest {
  @Test
  void boundedReadsPreserveLiteralsAndDoNotConsumeTheNextReadsAllowance() {
    LanguageDocument document =
        Language.create().readYaml("name: Read me\ntext: abcdef\nvalues: [1, 2]");
    String original = document.toJson();
    ContractInspection view = Contracts.inspect(document);
    assertEquals(List.of("name", "text", "values"), view.children());
    assertEquals("Read me", view.at("/name").orElseThrow().literal());
    assertEquals(java.math.BigInteger.TWO, view.at("/values/1").orElseThrow().literal());
    assertTrue(view.at("/missing").isEmpty());
    assertEquals(
        "INVALID_POINTER", assertThrows(BlueException.class, () -> view.at("text")).code());
    assertEquals(
        "PROJECTION_LIMIT_EXCEEDED",
        assertThrows(
                BlueException.class, () -> view.literal(Set.of(), new ProjectionLimits(100, 2, 5)))
            .code());
    assertEquals("abcdef", view.at("/text").orElseThrow().literal());
    assertEquals(original, document.toJson());
  }

  @Test
  void cyclesAndExistingReferencesAreNotRecursivelyExpanded() {
    Language language = Language.create();
    var set =
        language.finalizeSet(
            List.of(
                Language.Input.exactJson("{\"number\":7,\"peer\":{\"blueId\":\"this#1\"}}"),
                Language.Input.exactJson("{\"number\":11,\"peer\":{\"blueId\":\"this#0\"}}")),
            Language.Goal.VALUE);
    var root = set.members().getFirst();
    var view = Contracts.inspect(root);
    assertEquals(
        Map.of("blueId", set.members().getLast().blueId().value()),
        ((Map<?, ?>) view.literal()).get("peer"));
    assertEquals(
        java.math.BigInteger.valueOf(7), view.at("/peer/peer/number").orElseThrow().literal());
    assertEquals(List.of("number", "peer"), view.children());
  }

  @Test
  void projectedValuesMatchSessionReadsWithoutFetchingUnrelatedContent() {
    LanguageDocument child = Language.create().readYaml("count: 7");
    LanguageDocument unrelated = Language.create().readYaml("secret: 99");
    LanguageDocument document =
        Language.newBuilder()
            .content(child)
            .content(unrelated)
            .build()
            .readYaml(
                """
                stats:
                  total: {type: Integer, value: "9007199254740993"}
                  nullable: null
                  flags: [true, false]
                child: {blueId: "%s"}
                unrelated: {blueId: "%s"}
                """
                    .formatted(child.blueId(), unrelated.blueId()));
    var selectedRecords = new HashMap<>(document.records());
    Set<BlueId> requested = new HashSet<>();
    Language reader =
        Language.newBuilder()
            .provider(
                id -> {
                  requested.add(id);
                  if (id.equals(unrelated.blueId()))
                    throw new AssertionError("Fetched unrelated content");
                  return Optional.ofNullable(selectedRecords.get(id));
                })
            .build();
    var view = Contracts.inspect(reader.reference(document.blueId()));
    try (var session = Contracts.create().open(document)) {
      assertEquals(session.read("/stats"), view.at("/stats").orElseThrow().value());
      assertEquals(session.read("/child/count"), view.at("/child/count").orElseThrow().value());
      assertTrue(view.at("/stats/nullable").isEmpty());
      assertSame(Bex.undefined(), session.read("/stats/nullable"));
      assertTrue(view.at("/missing").isEmpty());
      assertEquals(
          "PROJECTION_LIMIT_EXCEEDED",
          assertThrows(
                  BlueException.class,
                  () -> view.at("/stats").orElseThrow().value(new ProjectionLimits(1, 100, 10)))
              .code());
      assertEquals(session.read("/stats"), view.at("/stats").orElseThrow().value());
    }
    assertFalse(requested.contains(unrelated.blueId()));
  }

  @Test
  void readableLiteralsInlineOnlyVerifiedScalarLeavesAndKeepReferenceBoundaries() {
    Language author = Language.create();
    LanguageDocument number = author.readYaml("type: Integer\nvalue: \"9007199254740993\"");
    LanguageDocument text = author.readYaml("hello");
    LanguageDocument linked = author.readYaml("title: Linked document");
    LanguageDocument handlers = author.readYaml("workflow: {body: Hidden}");
    LanguageDocument root =
        author.toBuilder()
            .content(number)
            .content(text)
            .content(linked)
            .content(handlers)
            .build()
            .readYaml(
                """
                count: {blueId: "%s"}
                list: [{blueId: "%s"}, {blueId: "%s"}]
                linked: {blueId: "%s"}
                contracts: {blueId: "%s"}
                """
                    .formatted(
                        number.blueId(),
                        number.blueId(),
                        text.blueId(),
                        linked.blueId(),
                        handlers.blueId()));
    var records = root.records();
    Set<BlueId> requested = new HashSet<>();
    Language reader =
        Language.newBuilder()
            .provider(
                id -> {
                  requested.add(id);
                  if (id.equals(handlers.blueId()))
                    throw new AssertionError("Opened excluded contracts");
                  return Optional.ofNullable(records.get(id));
                })
            .build();
    var view = Contracts.inspect(reader.reference(root.blueId()));
    Object expected =
        Map.of(
            "count", new java.math.BigInteger("9007199254740993"),
            "list", List.of(new java.math.BigInteger("9007199254740993"), "hello"),
            "linked", Map.of("blueId", linked.blueId().value()),
            "contracts", Map.of("blueId", handlers.blueId().value()));
    assertEquals(expected, view.readableLiteral(Set.of("contracts"), ProjectionLimits.defaults()));
    assertFalse(requested.contains(handlers.blueId()));
    assertEquals(
        Map.of("blueId", number.blueId().value()), ((Map<?, ?>) view.literal()).get("count"));
    assertEquals(
        "PROJECTION_LIMIT_EXCEEDED",
        assertThrows(
                BlueException.class,
                () -> view.readableLiteral(Set.of("contracts"), new ProjectionLimits(1, 1000, 10)))
            .code());
    assertEquals(expected, view.readableLiteral(Set.of("contracts"), ProjectionLimits.defaults()));
    Language forged =
        Language.newBuilder()
            .provider(
                id ->
                    Optional.ofNullable(
                        id.equals(number.blueId()) ? LanguageRecord.json("99") : records.get(id)))
            .build();
    assertThrows(
        BlueException.class,
        () ->
            Contracts.inspect(forged.reference(root.blueId()))
                .readableLiteral(Set.of("contracts"), ProjectionLimits.defaults()));
  }

  @Test
  void readableLiteralsKeepCyclicEdgesAndScalarMetadata() {
    Language language = Language.create();
    var set =
        language.finalizeSet(
            List.of(
                Language.Input.exactJson("{\"number\":7,\"peer\":{\"blueId\":\"this#1\"}}"),
                Language.Input.exactJson("{\"number\":11,\"peer\":{\"blueId\":\"this#0\"}}")),
            Language.Goal.VALUE);
    var root = Contracts.inspect(set.members().getFirst());
    assertEquals(root.literal(), root.readableLiteral(Set.of(), ProjectionLimits.defaults()));
    var described = language.readYaml("type: Integer\nvalue: 7\ndescription: Count");
    var typed = Contracts.inspect(described);
    assertEquals(typed.literal(), typed.readableLiteral(Set.of(), ProjectionLimits.defaults()));
    assertEquals(
        "Count",
        ((Map<?, ?>) typed.readableLiteral(Set.of(), ProjectionLimits.defaults()))
            .get("description"));
  }
}
