package blue.language;

import static blue.Language.Goal.DEFINITION;
import static blue.Language.Goal.VALUE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.Contracts;
import blue.Language;
import blue.LanguageDocument;
import blue.LanguageException;
import blue.Node;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Completed-value evidence changes physical work, never the accepted content or caller authority.
 */
@Tag("language")
class AdmissionEvidenceTest {
  @Test
  void admissionUsesCompletedSubtreesWithinABoundedCallWithoutRetainingTheirAuthority() {
    Language issuer = Language.create();
    LanguageDocument target =
        issuer.read(
            Node.object(
                Map.of(
                    "itemsToProcess",
                    Node.list(
                        java.util.stream.IntStream.range(0, 128)
                            .mapToObj(Node::integer)
                            .toList()))),
            VALUE);
    Language limited = Language.newBuilder().limits(limits(600)).build();
    Language.Input input =
        Language.Input.yaml("document: {blueId: '" + target.blueId() + "'}\nrequest: hello");
    LanguageDocument actual = limited.read(input, DEFINITION, target);
    LanguageDocument expected = issuer.toBuilder().content(target).build().read(input, DEFINITION);
    assertEquals(expected.blueId(), actual.blueId());
    assertEquals(expected.toJson(), actual.toJson());
    assertEquals(expected.removedFields(), actual.removedFields());
    assertEquals(127, actual.at("/document/itemsToProcess/items/127").integer());
    assertTrue(
        assertThrows(LanguageException.class, () -> limited.read(input, DEFINITION)).incomplete());
    Language complete = limited.toBuilder().content(actual).build();
    assertEquals(
        "OperationLimitExceeded",
        assertThrows(LanguageException.class, () -> complete.read(input, DEFINITION)).category(),
        "Exported content must not export validation authority");
    assertEquals(
        "OperationLimitExceeded",
        assertThrows(
                LanguageException.class,
                () ->
                    Language.newBuilder().limits(limits(0)).build().read(input, DEFINITION, target))
            .category());
  }

  @Test
  void newContentAndContextualRestrictionsStillFailNormally() {
    Language language = Language.create();
    LanguageDocument target = language.readYaml("status: ready");
    LanguageDocument type = language.defineYaml("document:\n  status: closed");
    Language complete = language.toBuilder().content(type).content(target).build();
    Language.Input conflict =
        Language.Input.yaml(
            "type: {blueId: '"
                + type.blueId()
                + "'}\ndocument: {blueId: '"
                + target.blueId()
                + "'}");
    assertEquals(
        assertThrows(LanguageException.class, () -> complete.read(conflict, DEFINITION)).category(),
        assertThrows(LanguageException.class, () -> complete.read(conflict, DEFINITION, target))
            .category());
    Language.Input bad =
        Language.Input.yaml(
            "document: {blueId: '"
                + target.blueId()
                + "'}\nrequest: {type: Integer, value: wrong}");
    assertThrows(LanguageException.class, () -> complete.read(bad, DEFINITION, target));
    LanguageDocument changed = language.readYaml("status: changed");
    assertTrue(
        assertThrows(
                LanguageException.class,
                () ->
                    language.read(
                        Language.Input.yaml("document: {blueId: '" + changed.blueId() + "'}"),
                        DEFINITION,
                        target))
            .incomplete());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            language.read(
                Language.Input.yaml("request: hello"),
                DEFINITION,
                language.defineYaml("type: Integer")));
  }

  @Test
  void sourceOmissionExactPresentationAndParsedNodesAgreeWithOrdinaryAdmission() {
    Language language = Language.create();
    LanguageDocument target = language.readYaml("status: ready");
    LanguageDocument type = language.defineYaml("channel: input\nrequest: {type: Text}");
    Language complete = language.toBuilder().content(type).content(target).build();
    String source =
        "type: {blueId: '"
            + type.blueId()
            + "'}\nchannel: input\nrequest: hello\ndocument: {blueId: '"
            + target.blueId()
            + "'}";
    LanguageDocument ordinary = complete.read(Language.Input.yaml(source), DEFINITION);
    LanguageDocument reused = complete.read(Language.Input.yaml(source), DEFINITION, target);
    assertEquals(ordinary.toJson(), reused.toJson());
    assertEquals(ordinary.blueId(), reused.blueId());
    assertEquals(ordinary.removedFields(), reused.removedFields());
    assertFalse(reused.removedFields().isEmpty());
    Node parsed =
        Node.object(
            Map.of(
                "type",
                Node.object(Map.of("blueId", Node.text(type.blueId().value()))),
                "channel",
                Node.text("input"),
                "request",
                Node.text("hello"),
                "document",
                Node.object(Map.of("blueId", Node.text(target.blueId().value())))));
    assertEquals(reused.toJson(), complete.read(parsed, DEFINITION, target).toJson());
    Language.Input exact =
        Language.Input.exactJson(complete.prepare(Language.Input.yaml(source)).toJson());
    assertEquals(
        complete.read(exact, DEFINITION).toJson(),
        complete.read(exact, DEFINITION, target).toJson());
  }

  @Test
  void cyclicValuesRetainPortableEvidenceWithoutGrantingAnotherCallAProof() {
    Language language = Language.create();
    Language.CyclicSet cycle =
        language.finalizeSet(
            List.of(
                Language.Input.exactJson("{\"label\":\"cycle\",\"peer\":{\"blueId\":\"this#0\"}}")),
            VALUE);
    LanguageDocument target = cycle.members().getFirst();
    Language.Input input = Language.Input.yaml("document: {blueId: '" + target.blueId() + "'}");
    LanguageDocument result = language.read(input, DEFINITION, target);
    Language ordinary = language.toBuilder().content(target).build();
    assertEquals(ordinary.read(input, DEFINITION).toJson(), result.toJson());
    assertEquals("cycle", result.at("/document/peer/label").text());
    assertTrue(result.records().containsKey(target.blueId()));
    assertTrue(
        assertThrows(LanguageException.class, () -> language.read(input, DEFINITION)).incomplete());
    Language.Builder restored = Language.newBuilder();
    result.records().forEach(restored::record);
    assertEquals(
        result.blueId(),
        restored.build().read(Language.Input.exactJson(result.toJson()), DEFINITION).blueId());
  }

  @Test
  void aCommittedNativeVersionRemainsValidEvidenceAfterItsSessionCloses() {
    Language language = Language.newBuilder().mount("Contracts", Contracts.publication()).build();
    LanguageDocument target;
    try (Contracts.Session session =
        Contracts.newBuilder()
            .language(language)
            .build()
            .openYaml(
                """
                count: 0
                contracts:
                  input: {type: Contracts/External Channel}
                  change:
                    type: Contracts/Sequential Workflow
                    channel: input
                    steps:
                      - type: Contracts/Update Document
                        changeset: [{op: replace, path: /count, val: 1}]
                """)) {
      session.initialize().requireApplied();
      session.process(Map.of("run", 1), 1).requireApplied();
      target = session.document();
    }
    Language.Input request =
        Language.Input.yaml(
            """
            type: Contracts/Operation Request
            document: {blueId: '%s'}
            channel: input
            operation: change
            request: {}
            """
                .formatted(target.blueId()));
    LanguageDocument ordinary =
        language.toBuilder().content(target).build().read(request, DEFINITION);
    LanguageDocument reused = language.read(request, DEFINITION, target);
    assertEquals(ordinary.toJson(), reused.toJson());
    assertEquals(ordinary.blueId(), reused.blueId());
    assertEquals(1, reused.at("/document/count").integer());
  }

  private static Language.Limits limits(long steps) {
    return new Language.Limits(steps, 256, 100_000, 1000, 1024 * 1024, 1000, 1000);
  }
}
