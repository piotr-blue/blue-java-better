package blue.language;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.BlueId;
import blue.Language;
import blue.LanguageContent;
import blue.LanguageDocument;
import blue.LanguageException;
import blue.LanguageRecord;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("language")
class ContentExportTest {
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "hello",
        "type: Integer\nvalue: '123456789012345678901234567890'",
        "type: Double\nvalue: 1.25",
        "name: Record\nmessage: Hello\nletters: [A, B, C]",
        "type: Dictionary\nvalueType: Integer\napples: 3\npears: 5",
        "name: Schema\nfield: {type: Text}",
        "type: List\nmergePolicy: append-only\nitems: [{$previousItems: false}, A, B]"
      })
  void admittedContentExportsAnIdentityPreservingCanonicalRepresentation(String source) {
    Language language = Language.create();
    LanguageDocument admitted = language.defineYaml(source);
    String canonical = admitted.canonicalJson();
    Language.Builder receiving = Language.newBuilder();
    admitted.records().forEach(receiving::record);
    LanguageDocument restored =
        receiving.build().read(Language.Input.compactJson(canonical), Language.Goal.DEFINITION);
    assertEquals(admitted.blueId(), restored.blueId());
    assertEquals(canonical, restored.canonicalJson());
  }

  @Test
  void incrementalExportDoesNotOpenAnAlreadyRetainedClosure() {
    LanguageDocument previous = Language.create().readYaml("old: 7");
    AtomicInteger fetches = new AtomicInteger();
    Language sender =
        Language.newBuilder()
            .provider(
                id -> {
                  fetches.incrementAndGet();
                  return Optional.empty();
                })
            .build();
    LanguageContent next =
        sender.prepare(
            Language.Input.exactJson(
                "{\"next\":{\"value\":1},\"previous\":{\"blueId\":\""
                    + previous.blueId()
                    + "\"}}"));
    Map<BlueId, LanguageRecord> delta = next.records(previous.blueId()::equals);
    assertEquals(0, fetches.get());
    assertFalse(delta.containsKey(previous.blueId()));
    assertTrue(delta.containsKey(next.blueId()));
    Language.Builder receiving = Language.newBuilder();
    previous.records().forEach(receiving::record);
    delta.forEach((id, record) -> receiving.record(id, LanguageRecord.fromJson(record.toJson())));
    assertEquals(7, receiving.build().read(next.blueId()).at("/previous/old").integer());
    Language.Builder incomplete = Language.newBuilder();
    delta.forEach(incomplete::record);
    assertTrue(
        assertThrows(LanguageException.class, () -> incomplete.build().read(next.blueId()))
            .incomplete());
    assertTrue(sender.reference(previous.blueId()).records(id -> true).isEmpty());
  }

  @Test
  void inheritedAppendOnlyListsRoundTripWithOnlyNewRecords() {
    LanguageDocument first =
        Language.create()
            .readYaml("type: List\nmergePolicy: append-only\nitems: [{$previousItems: false}, A]");
    LanguageDocument second = first.specializeYaml("items: [{$previousItems: true}, B]");
    Map<BlueId, LanguageRecord> retained = first.records();
    Map<BlueId, LanguageRecord> delta = second.records(retained::containsKey);
    assertTrue(delta.keySet().stream().noneMatch(retained::containsKey));
    Language.Builder receiver = Language.newBuilder();
    retained.forEach(receiver::record);
    delta.forEach((id, record) -> receiver.record(id, LanguageRecord.fromJson(record.toJson())));
    LanguageDocument restored =
        receiver
            .build()
            .read(Language.Input.compactJson(second.canonicalJson()), Language.Goal.VALUE);
    assertEquals(second.blueId(), restored.blueId());
    assertEquals(second.canonicalJson(), restored.canonicalJson());
    assertEquals("A", restored.at("/items/0").text());
    assertEquals("B", restored.at("/items/1").text());
  }

  @Test
  void cyclicProofsAndBodiesSurvivePortableRecordEnvelopes() {
    Language.CyclicSet set =
        Language.create()
            .finalizeSet(
                List.of(
                    Language.Input.exactJson(
                        "{\"name\":\"Loop\",\"self\":{\"blueId\":\"this#0\"}}")),
                Language.Goal.VALUE);
    LanguageDocument member = set.members().getFirst();
    Language.Builder reader = Language.newBuilder();
    member
        .records()
        .forEach((id, record) -> reader.record(id, LanguageRecord.fromJson(record.toJson())));
    LanguageDocument restored = reader.build().read(member.blueId());
    assertEquals(member.blueId(), restored.blueId());
    assertTrue(restored.at("/self/self").exists());
  }

  @Test
  void finalizingLocallyKnownCyclesNeedsNoProviderFetchAndStillRejectsInvalidMembers() {
    var language =
        Language.newBuilder()
            .limits(new Language.Limits(100_000, 256, 100_000, 0, 1_000_000, 1000, 1000))
            .build();
    var cycle =
        List.of(
            Language.Input.exactJson("{\"name\":\"Quay\",\"peer\":{\"blueId\":\"this#1\"}}"),
            Language.Input.exactJson("{\"name\":\"Cove\",\"peer\":{\"blueId\":\"this#0\"}}"));
    var finalized = language.finalizeSet(cycle, Language.Goal.VALUE);
    assertEquals(
        Language.create().finalizeSet(cycle, Language.Goal.VALUE).master(), finalized.master());
    for (var member : finalized.members()) {
      var remote = Language.newBuilder();
      for (var entry : member.records().entrySet()) {
        remote.record(entry.getKey(), entry.getValue());
      }
      assertEquals(member.blueId(), remote.build().read(member.blueId()).blueId());
    }
    String external = Language.create().readJson("42").blueId().toString();
    var incomplete =
        List.of(
            Language.Input.exactJson(
                "{\"name\":\"Open\",\"self\":{\"blueId\":\"this#0\"},\"quantity\":{\"blueId\":\""
                    + external
                    + "\"}}"));
    var missing =
        assertThrows(
            LanguageException.class, () -> language.finalizeSet(incomplete, Language.Goal.VALUE));
    assertEquals("OperationLimitExceeded", missing.category());
  }

  @Test
  void localProofReusePreservesStrictAdmissionAndWholeProofSizeLimits() {
    var language = Language.create();
    var definition =
        Language.Input.exactJson(
            """
            {"name":"Seat","self":{"blueId":"this#0"},"seats":{
              "type":{"blueId":"E2LM6qgzWG9ttagq2xTmiZkgYEAgkYedFCmU9v7NnVEq"},
              "schema":{"required":true}}}
            """);
    var members = List.of(definition);
    assertEquals(1, language.finalizeSet(members, Language.Goal.DEFINITION).members().size());
    assertEquals(
        "SchemaViolation",
        assertThrows(
                LanguageException.class, () -> language.finalizeSet(members, Language.Goal.VALUE))
            .category());

    var first = Language.Input.exactJson("{\"name\":\"First\",\"next\":{\"blueId\":\"this#1\"}}");
    var last = Language.Input.exactJson("{\"name\":\"Last\",\"next\":{\"blueId\":\"this#0\"}}");
    int budget = Math.max(first.text().length(), last.text().length());
    var limited =
        Language.newBuilder()
            .limits(new Language.Limits(100_000, 256, 100_000, 0, budget, 1000, 1000))
            .build();
    assertEquals(
        "OperationLimitExceeded",
        assertThrows(
                LanguageException.class,
                () -> limited.finalizeSet(List.of(first, last), Language.Goal.VALUE))
            .category());
  }

  @Test
  void importedMemberChecksItsCanonicalIndexAndEveryClaimedBody() {
    // Master from the published Language CYCLE-LOOP vector, independently pinned by the corpus.
    String master = "7Ydx3cm8uySLmzNyTcbpM5fufGaLSKnEs9cUcMMxRkeC";
    BlueId first = new BlueId(master + "#0");
    String proof = "{\"members\":[{\"name\":\"Loop\",\"self\":{\"blueId\":\"this#0\"}}]}";
    String body = "{\"name\":\"Loop\",\"self\":{\"blueId\":\"" + first + "\"}}";
    var reader = Language.newBuilder().record(first, LanguageRecord.cyclic(proof, body)).build();
    assertEquals(first, reader.read(first).blueId());
    Map<BlueId, LanguageRecord> corrupt =
        Map.of(
            new BlueId(master + "#1"),
            LanguageRecord.cyclic(proof),
            first,
            LanguageRecord.cyclic(proof, body.replace("Loop", "Changed")),
            new BlueId(Language.create().readJson("19").blueId().value() + "#0"),
            LanguageRecord.cyclic(proof));
    corrupt.forEach(
        (id, record) -> {
          var untrusted = Language.newBuilder().record(id, record).build();
          assertEquals(
              "InvalidCyclicProof",
              assertThrows(LanguageException.class, () -> untrusted.read(id)).category());
        });
  }

  @Test
  void serializationNeverTurnsAnUnverifiedRecordIntoAuthority() {
    BlueId expected = Language.create().readJson("1").blueId();
    LanguageRecord wrong = LanguageRecord.fromJson(LanguageRecord.json("2").toJson());
    Language reader = Language.newBuilder().record(expected, wrong).build();
    assertEquals(
        "ProviderBlueIdMismatch",
        assertThrows(LanguageException.class, () -> reader.read(expected)).category());
    assertThrows(
        LanguageException.class,
        () -> LanguageRecord.fromJson("{\"text\":\"1\",\"mode\":\"source\"}"));
    assertThrows(
        LanguageException.class,
        () -> LanguageRecord.fromJson("{\"text\":\"1\",\"trusted\":true}"));
  }
}
