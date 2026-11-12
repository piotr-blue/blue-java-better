package blue.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.BlueException;
import blue.BlueId;
import blue.BluePackage;
import blue.Contracts;
import blue.Language;
import blue.LanguageContent;
import blue.LanguageDocument;
import blue.LanguageException;
import blue.LanguageProvider;
import blue.LanguageRecord;
import blue.Node;
import blue.PackageLoader;
import blue.ProviderSignatures;
import blue.TimelineAccess;
import blue.TimelineAccessProgram;
import blue.TimelinePublisher;
import blue.TimelineReader;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.NamedParameterSpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("coordination")
class TimelineAccessTest {
  private static final String READER = "11".repeat(32);

  private static final class Published {
    static final BluePackage PACKAGE =
        PackageLoader.create()
            .resource(
                TimelineAccessTest.class.getClassLoader(),
                "coordination/coordination.bluepkg",
                ProviderSignatures.COORDINATION_PACKAGE);
  }

  @Nested
  class Discovery {
    @Test
    void signedDiscoveryPreservesClaimsWithoutInventingAConfirmedPermissionState() {
      Fixture f = new Fixture();
      var reader = TimelineReader.adopt(Published.PACKAGE, f.provider, f.control, f.descriptor());
      var request = request(f);
      var publication = f.entry(null, 1, f.initial, false);
      var claimed = f.initial.edit(e -> e.set("/note", "Unreplayed provider claim"));
      var read = reader.access(request);
      var description =
          read.accept(
              response(
                  f, request, f.initial, claimed, f.attest(publication), f.attest(publication)));
      assertSame(request, read.request());
      assertEquals(f.initial.blueId(), description.initial().blueId());
      assertEquals(claimed.blueId(), description.claimedCurrent().blueId());
      assertEquals(f.attest(publication).blueId(), description.publication().blueId());
      try (var tracking = f.track(f.initial)) {
        assertTrue(tracking.view().isEmpty());
        var confirmed =
            tracking.advance(f.range(null, publication.blueId(), 2, List.of(publication)));
        assertNotEquals(
            description.claimedCurrent().blueId(),
            confirmed.view().orElseThrow().document().blueId());
      }
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
          "request",
          "access",
          "publication-message",
          "publication-timeline",
          "publication-signature",
          "last-timeline",
          "last-signature",
          "last-before-publication",
          "outer-signature"
        })
    void rejectsMismatchedOrUnauthenticatedDiscovery(String mutation) {
      Fixture f = new Fixture();
      var reader = TimelineReader.adopt(Published.PACKAGE, f.provider, f.control, f.descriptor());
      var request = request(f);
      LanguageContent initial = f.initial;
      LanguageContent other = f.initial.edit(e -> e.set("/note", "different initial document"));
      LanguageContent sentRequest = request;
      if (mutation.equals("request")) {
        sentRequest =
            f.typed("Get Timeline Access Request", "access: {blueId: '" + other.blueId() + "'}");
      }
      if (mutation.equals("access")) {
        initial = other;
      }
      var publication =
          f.entry(null, 2, mutation.equals("publication-message") ? other : f.initial, false);
      LanguageContent first = f.attest(publication), last = first;
      var wrongTimeline =
          f.typed(
              "Timeline Entry",
              "timeline: {blueId: '"
                  + f.privateTimeline.blueId()
                  + "'}\n"
                  + "timestamp: 3\n"
                  + "actor: {type: Coordination/Agent Actor, service: provider}\n"
                  + "message: "
                  + f.initial.toJson());
      if (mutation.equals("publication-timeline")) {
        first = f.attest(wrongTimeline);
      }
      if (mutation.equals("last-timeline")) {
        last = f.attest(wrongTimeline);
      }
      if (mutation.equals("last-before-publication")) {
        last = f.attest(f.entry(null, 1, f.initial, false));
      }
      if (mutation.equals("publication-signature")) {
        first = badSignature(f, publication);
      }
      if (mutation.equals("last-signature")) {
        last = badSignature(f, publication);
      }
      var response = response(f, sentRequest, initial, f.initial, first, last);
      if (mutation.equals("outer-signature")) {
        response = badSignature(f, f.initial);
      }
      var candidate = response;
      assertThrows(BlueException.class, () -> reader.access(request).accept(candidate));
    }

    @Test
    void signedCorrelatedFailureIsTypedAndNeverBecomesDiscoveryData() {
      Fixture f = new Fixture();
      var reader = TimelineReader.adopt(Published.PACKAGE, f.provider, f.control, f.descriptor());
      var request = request(f);
      var failure =
          f.attest(
              f.typed(
                  "Provider Failure",
                  "request: {blueId: '"
                      + request.blueId()
                      + "'}\ncode: NOT_FOUND\nretryable: false"));
      var rejected =
          assertThrows(TimelineReader.Failure.class, () -> reader.access(request).accept(failure));
      assertEquals("NOT_FOUND", rejected.code());
    }

    @Test
    void discoveryRequiresAnExactAccessReferenceAndAnInlineResponseDocument() {
      Fixture f = new Fixture();
      var reader = TimelineReader.adopt(Published.PACKAGE, f.provider, f.control, f.descriptor());
      assertThrows(
          BlueException.class,
          () ->
              reader.access(
                  f.typed("Get Timeline Access Request", "access: " + f.initial.toJson())));
      var request = request(f);
      var publication = f.attest(f.entry(null, 1, f.initial, false));
      var response =
          f.attest(
              f.typed(
                  "Timeline Access Response",
                  "request: {blueId: '"
                      + request.blueId()
                      + "'}\naccess: {blueId: '"
                      + f.initial.blueId()
                      + "'}\ncurrent: "
                      + f.initial.toJson()
                      + "\npublication: "
                      + publication.toJson()
                      + "\nlastEntry: "
                      + publication.toJson()));
      assertThrows(BlueException.class, () -> reader.access(request).accept(response));
    }

    private LanguageContent request(Fixture f) {
      return f.typed(
          "Get Timeline Access Request", "access: {blueId: '" + f.initial.blueId() + "'}");
    }

    private LanguageContent badSignature(Fixture f, LanguageContent object) {
      return f.typed(
          "Provider Attestation",
          "provider: {blueId: '"
              + f.provider.blueId()
              + "'}\nobject: "
              + object.toJson()
              + "\nsignature: '"
              + "00".repeat(64)
              + "'");
    }

    private LanguageContent response(
        Fixture f,
        LanguageContent request,
        LanguageContent initial,
        LanguageContent current,
        LanguageContent publication,
        LanguageContent latest) {
      return f.attest(
          f.typed(
              "Timeline Access Response",
              "request: {blueId: '"
                  + request.blueId()
                  + "'}\naccess: "
                  + initial.toJson()
                  + "\ncurrent: "
                  + current.toJson()
                  + "\npublication: "
                  + publication.toJson()
                  + "\nlastEntry: "
                  + latest.toJson()));
    }
  }

  @Nested
  class Confirmation {
    @ParameterizedTest
    @ValueSource(strings = {"keys", "timelines", "uppercase"})
    void invalidInitialPermissionSetsCannotBecomeAnAuthority(String kind) {
      Fixture f = new Fixture();
      LanguageDocument initial =
          switch (kind) {
            case "keys" ->
                f.initial.edit(
                    e ->
                        e.set(
                            "/allowedKeys",
                            Node.list(List.of(Node.text(READER), Node.text(READER)))));
            case "timelines" ->
                f.initial.edit(
                    e ->
                        e.set(
                            "/allowedTimelines",
                            Node.list(
                                List.of(
                                    Node.reference(f.privateTimeline.blueId()),
                                    Node.reference(f.privateTimeline.blueId())))));
            default ->
                f.initial.edit(
                    e -> e.set("/allowedKeys", Node.list(List.of(Node.text("AA".repeat(32))))));
          };
      rejected(
          kind.equals("uppercase") ? "ACCESS_READER_KEY" : "ACCESS_DUPLICATE_VALUE",
          () -> f.track(initial));
    }

    @Test
    void authoredListsGrantNothingUntilTheirExactPublicationIsClosed() {
      Fixture f = new Fixture();
      try (TimelineAccess access = f.track(f.initial)) {
        assertTrue(access.view().isEmpty());
        TimelineAccess.Update empty = access.advance(f.range(null, null, 1, List.of()));
        assertTrue(empty.view().isEmpty());
        LanguageContent entry = f.entry(null, 1, f.initial, false);
        TimelineAccess.Update published =
            access.advance(f.range(null, entry.blueId(), 2, List.of(entry)));
        assertEquals(List.of(TimelineAccess.Kind.PUBLISHED), kinds(published));
        TimelineAccess.View view = published.view().orElseThrow();
        assertEquals(Set.of(READER), view.allowedKeys());
        assertEquals(Set.of(f.privateTimeline.blueId()), view.allowedTimelines());
        assertNotEquals(
            f.initial.blueId(),
            view.document().blueId(),
            "Contracts adds its initialization state");
        assertEquals(1, view.changedAt());
        assertEquals(2, view.completeBefore());
        assertTrue(published.dispositions().getFirst().transition().orElseThrow().applied());
      }
    }

    @Test
    void unrelatedPublicationsAndMessagesDoNotCreatePermissions() {
      Fixture f = new Fixture();
      LanguageContent first = f.entry(null, 1, f.typed("Agent Actor", "service: unrelated"), false);
      LanguageContent other = f.initial.edit(e -> e.set("/note", "another document"));
      LanguageContent second = f.entry(first.blueId(), 2, other, false);
      try (TimelineAccess access = f.track(f.initial)) {
        TimelineAccess.Update result =
            access.advance(f.range(null, second.blueId(), 3, List.of(first, second)));
        assertTrue(result.view().isEmpty());
        assertEquals(
            List.of(TimelineAccess.Kind.INAPPLICABLE, TimelineAccess.Kind.INAPPLICABLE),
            kinds(result));
        assertTrue(result.dispositions().stream().allMatch(d -> d.transition().isEmpty()));
        assertTrue(result.dispositions().stream().allMatch(d -> d.document().isEmpty()));
      }
    }

    @Test
    void initialDefinitionCannotReplaceTheProgramOrProviderControlSource() {
      Fixture f = new Fixture();
      LanguageDocument changed =
          f.initial.edit(
              e ->
                  e.set(
                      "/contracts/update",
                      f.typed("Update Timeline Access", "description: changed").node()));
      rejected("ACCESS_CHANGED_PROGRAM", () -> f.track(changed));
      LanguageDocument wrongControl =
          f.initial.edit(
              e -> e.set("/contracts/provider/url/value", "https://provider.example/customer"));
      rejected("ACCESS_CONTROL_TIMELINE", () -> f.track(wrongControl));
      LanguageContent channel =
          f.typed(
              "Public Timeline Channel",
              "provider: {blueId: '"
                  + f.provider.blueId()
                  + "'}\nurl: https://provider.example/control");
      LanguageDocument extra = f.initial.edit(e -> e.set("/contracts/additional", channel.node()));
      rejected("ACCESS_CONTRACTS", () -> f.track(extra));
    }
  }

  @Nested
  class ExecutedReplacements {
    @ParameterizedTest
    @ValueSource(ints = {8, 64})
    void batchAndIndividualReplayHaveIdenticalGasReceiptsEventsAndHistoricalDocuments(int updates) {
      Fixture f = new Fixture();
      List<LanguageContent> entries = new ArrayList<>();
      entries.add(f.entry(null, 1, f.initial, false));
      for (int i = 0; i < updates; i++) {
        List<String> keys = i % 3 == 0 ? List.of() : List.of(READER);
        LanguageContent request =
            f.update(f.initial.blueId(), keys, List.of(f.privateTimeline.blueId()));
        entries.add(f.entry(entries.getLast().blueId(), i + 2, request, true));
      }
      List<TimelineAccess.Disposition> individually = new ArrayList<>();
      TimelineAccess.Update together;
      try (TimelineAccess single = f.track(f.initial);
          TimelineAccess batch = f.track(f.initial)) {
        BlueId previous = null;
        for (int i = 0; i < entries.size(); i++) {
          LanguageContent entry = entries.get(i);
          individually.addAll(
              single
                  .advance(f.range(previous, entry.blueId(), i + 2, List.of(entry)))
                  .dispositions());
          previous = entry.blueId();
        }
        together = batch.advance(f.range(null, entries.getLast().blueId(), updates + 2, entries));
        assertEquals(
            single.view().orElseThrow().document().blueId(),
            together.view().orElseThrow().document().blueId());
      }
      // Returned exact states and observations remain usable after both replay lifetimes close.
      for (int i = 0; i < entries.size(); i++) {
        var first = individually.get(i);
        var second = together.dispositions().get(i);
        assertEquals(first.entry(), second.entry());
        assertEquals(first.kind(), second.kind());
        Contracts.Transition a = first.transition().orElseThrow(),
            b = second.transition().orElseThrow();
        assertEquals(a.gas(), b.gas());
        assertEquals(a.receipt(), b.receipt());
        assertEquals(a.events(), b.events());
        assertEquals(a.observations(), b.observations());
        assertEquals(
            first.document().orElseThrow().blueId(), second.document().orElseThrow().blueId());
        assertEquals(
            first.document().orElseThrow().toJson(), second.document().orElseThrow().toJson());
      }
    }

    @Test
    void publishedBexAtomicallyReplacesBothListsAndEmitsItsRealEvent() {
      Fixture f = new Fixture();
      LanguageContent publication = f.entry(null, 1, f.initial, false);
      try (TimelineAccess access = f.track(f.initial)) {
        TimelineAccess.View before =
            access
                .advance(f.range(null, publication.blueId(), 2, List.of(publication)))
                .view()
                .orElseThrow();
        LanguageContent request = f.update(f.initial.blueId(), List.of("22".repeat(32)), List.of());
        LanguageContent entry = f.entry(publication.blueId(), 2, request, true);
        TimelineAccess.Update changed =
            access.advance(f.range(publication.blueId(), entry.blueId(), 3, List.of(entry)));
        TimelineAccess.View after = changed.view().orElseThrow();
        assertEquals(Set.of(READER), before.allowedKeys());
        assertEquals(Set.of("22".repeat(32)), after.allowedKeys());
        assertTrue(after.allowedTimelines().isEmpty());
        assertEquals(2, after.changedAt());
        assertEquals(List.of(TimelineAccess.Kind.UPDATED), kinds(changed));
        Contracts.Transition result = changed.dispositions().getFirst().transition().orElseThrow();
        assertTrue(result.gas() > 0);
        assertEquals(1, result.events().size());
        assertEquals(
            Map.of(
                "type",
                Map.of(
                    "blueId", Published.PACKAGE.exports().get("Timeline Access Updated").value())),
            result.events().getFirst());
        assertNotEquals(before.document().blueId(), after.document().blueId());
        assertEquals(
            Set.of(READER), before.allowedKeys(), "Previously returned views are immutable");
      }
    }

    @Test
    void unchangedListsStillEmitAndEmptyListsRevokeWithoutInventedGrantIds() {
      Fixture f = new Fixture();
      LanguageContent publication = f.entry(null, 1, f.initial, false);
      LanguageContent unchanged =
          f.entry(
              publication.blueId(),
              2,
              f.update(f.initial.blueId(), List.of(READER), List.of(f.privateTimeline.blueId())),
              true);
      LanguageContent revoke =
          f.entry(unchanged.blueId(), 3, f.update(f.initial.blueId(), List.of(), List.of()), true);
      try (TimelineAccess access = f.track(f.initial)) {
        TimelineAccess.Update result =
            access.advance(
                f.range(null, revoke.blueId(), 4, List.of(publication, unchanged, revoke)));
        assertEquals(
            List.of(
                TimelineAccess.Kind.PUBLISHED,
                TimelineAccess.Kind.UPDATED,
                TimelineAccess.Kind.UPDATED),
            kinds(result));
        assertEquals(1, result.dispositions().get(1).transition().orElseThrow().events().size());
        assertEquals(1, result.dispositions().get(2).transition().orElseThrow().events().size());
        assertTrue(result.view().orElseThrow().allowedKeys().isEmpty());
        assertTrue(result.view().orElseThrow().allowedTimelines().isEmpty());
        LanguageDocument beforeRevocation = result.dispositions().get(1).document().orElseThrow();
        assertEquals(READER, beforeRevocation.at("/allowedKeys/items/0").text());
        assertEquals(
            result.view().orElseThrow().document().blueId(),
            result.dispositions().get(2).document().orElseThrow().blueId());
      }
    }

    @Test
    void otherAccessDocumentsAreDispositionedWithoutRunningTheirPrograms() {
      Fixture f = new Fixture();
      LanguageContent publication = f.entry(null, 1, f.initial, false);
      LanguageDocument other =
          f.initial.edit(e -> e.set("/description", "Another access document"));
      f.records.put(other.blueId(), LanguageRecord.json(other.toJson()));
      LanguageContent unrelated =
          f.entry(publication.blueId(), 2, f.update(other.blueId(), List.of(), List.of()), true);
      try (TimelineAccess access = f.track(f.initial)) {
        TimelineAccess.Update result =
            access.advance(f.range(null, unrelated.blueId(), 3, List.of(publication, unrelated)));
        assertEquals(
            List.of(TimelineAccess.Kind.PUBLISHED, TimelineAccess.Kind.INAPPLICABLE),
            kinds(result));
        assertEquals(Set.of(READER), result.view().orElseThrow().allowedKeys());
        assertEquals(1, result.view().orElseThrow().changedAt());
        assertEquals(3, result.view().orElseThrow().completeBefore());
      }
    }
  }

  @Nested
  class IntegrityAndAtomicity {
    @Test
    void aLaterReplacementCannotHideAnInvalidIntermediatePermissionState() {
      Fixture f = new Fixture();
      LanguageDocument invalidTimeline =
          f.privateTimeline.edit(e -> e.set("/url/value", "https://provider.example/private/"));
      f.records.put(invalidTimeline.blueId(), LanguageRecord.json(invalidTimeline.toJson()));
      LanguageContent publication = f.entry(null, 1, f.initial, false);
      LanguageContent invalid =
          f.entry(
              publication.blueId(),
              2,
              f.update(f.initial.blueId(), List.of(READER), List.of(invalidTimeline.blueId())),
              true);
      LanguageContent later =
          f.entry(invalid.blueId(), 3, f.update(f.initial.blueId(), List.of(), List.of()), true);
      try (TimelineAccess access = f.track(f.initial)) {
        TimelineAccess.View before =
            access
                .advance(f.range(null, publication.blueId(), 2, List.of(publication)))
                .view()
                .orElseThrow();
        rejected(
            "TIMELINE_URL",
            () ->
                access.advance(
                    f.range(publication.blueId(), later.blueId(), 4, List.of(invalid, later))));
        assertSame(before, access.view().orElseThrow());
      }
    }

    @Test
    void verifiedHeadFactsRemainPrivateToReplayAndDoNotAdmitOrdinaryIncompleteContent() {
      Fixture f = new Fixture();
      List<LanguageContent> history = new ArrayList<>();
      LanguageContent publication = f.entry(null, 1, f.initial, false);
      history.add(publication);
      BlueId head = publication.blueId();
      for (int time = 2; time <= 8; time++) {
        LanguageContent entry =
            f.entry(head, time, f.typed("Agent Actor", "service: unrelated"), false);
        history.add(entry);
        head = entry.blueId();
      }
      try (TimelineAccess access = f.track(f.initial)) {
        access.advance(f.range(null, head, 9, history));
        f.records.clear();
        f.records.put(f.initial.blueId(), LanguageRecord.json(f.initial.toJson()));
        LanguageContent next =
            f.entry(head, 9, f.update(f.initial.blueId(), List.of(), List.of()), true);
        assertTrue(
            assertThrows(LanguageException.class, () -> f.language.admit(next, Language.Goal.VALUE))
                .incomplete());
        TimelineAccess.Update update =
            access.advance(f.range(head, next.blueId(), 10, List.of(next)));
        assertTrue(update.view().orElseThrow().allowedKeys().isEmpty());
        assertTrue(
            assertThrows(LanguageException.class, () -> f.language.admit(next, Language.Goal.VALUE))
                .incomplete());
      }
    }

    @Test
    void aForeignProviderReplacementFailsInThePublishedProgram() {
      Fixture f = new Fixture();
      LanguageDocument foreignProvider =
          f.provider.edit(e -> e.set("/endpoint/value", "https://foreign.example"));
      LanguageDocument foreignTimeline =
          Language.newBuilder()
              .mount("Coordination", Published.PACKAGE)
              .content(foreignProvider)
              .build()
              .readYaml(
                  "type: Coordination/Signed Timeline\nprovider: {blueId: '"
                      + foreignProvider.blueId()
                      + "'}\nurl: https://foreign.example/private\npublicRead: false");
      f.records.put(foreignProvider.blueId(), LanguageRecord.json(foreignProvider.toJson()));
      f.records.put(foreignTimeline.blueId(), LanguageRecord.json(foreignTimeline.toJson()));
      LanguageContent publication = f.entry(null, 1, f.initial, false);
      LanguageContent update =
          f.entry(
              publication.blueId(),
              2,
              f.update(f.initial.blueId(), List.of(READER), List.of(foreignTimeline.blueId())),
              true);
      try (TimelineAccess access = f.track(f.initial)) {
        TimelineAccess.View before =
            access
                .advance(f.range(null, publication.blueId(), 2, List.of(publication)))
                .view()
                .orElseThrow();
        rejected(
            "ACCESS_HISTORY_CONTRADICTION",
            () ->
                access.advance(f.range(publication.blueId(), update.blueId(), 3, List.of(update))));
        assertSame(before, access.view().orElseThrow());
      }
    }

    @Test
    void verifiedHistoryNeverExcusesInvalidNewEntryContent() {
      Fixture f = new Fixture();
      LanguageContent publication = f.entry(null, 1, f.initial, false);
      try (TimelineAccess access = f.track(f.initial)) {
        TimelineAccess.View before =
            access
                .advance(f.range(null, publication.blueId(), 2, List.of(publication)))
                .view()
                .orElseThrow();
        LanguageContent valid =
            f.entry(
                publication.blueId(), 2, f.update(f.initial.blueId(), List.of(), List.of()), true);
        LanguageContent malformed =
            f.language.prepare(
                Language.Input.yaml(
                    "type: Coordination/Timeline Entry\n"
                        + "timeline: {blueId: '"
                        + f.control.blueId()
                        + "'}\n"
                        + "timestamp: {value: 2, schema: {minimum: 3}}\n"
                        + "actor: {type: Coordination/Agent Actor}\n"
                        + "prevEntry: {blueId: '"
                        + publication.blueId()
                        + "'}\n"
                        + "message: "
                        + f.update(f.initial.blueId(), List.of(), List.of()).toJson()
                        + "\noperation: "
                        + f.update(f.initial.blueId(), List.of(), List.of()).toJson()));
        assertThrows(
            LanguageException.class,
            () ->
                access.advance(
                    f.range(publication.blueId(), malformed.blueId(), 3, List.of(malformed))));
        assertSame(before, access.view().orElseThrow());
        assertTrue(
            access
                .advance(f.range(publication.blueId(), valid.blueId(), 3, List.of(valid)))
                .view()
                .orElseThrow()
                .allowedKeys()
                .isEmpty());
      }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aHumanActorCannotPublishOrUpdateAccessEvenOnTheSignedControlTimeline(boolean updating) {
      Fixture f = new Fixture();
      try (TimelineAccess access = f.track(f.initial)) {
        BlueId previous = null;
        TimelineAccess.View before = null;
        if (updating) {
          LanguageContent publication = f.entry(null, 1, f.initial, false);
          previous = publication.blueId();
          before =
              access.advance(f.range(null, previous, 2, List.of(publication))).view().orElseThrow();
        }
        LanguageContent message =
            updating ? f.update(f.initial.blueId(), List.of(), List.of()) : f.initial;
        LanguageContent human =
            f.entryWithActor(previous, updating ? 2 : 1, message, updating, "Principal Actor");
        LanguageContent evidence =
            f.range(previous, human.blueId(), updating ? 3 : 2, List.of(human));
        rejected("TIMELINE_TYPE", () -> access.advance(evidence));
        assertEquals(Optional.ofNullable(before), access.view());
      }
    }

    @Test
    void aForgedCheckpointCannotInitializeTheAccessDocument() {
      Fixture f = new Fixture();
      LanguageContent entry = f.entry(null, 1, f.initial, false);
      LanguageContent evidence = f.range(null, entry.blueId(), 2, List.of(entry));
      String signature = f.language.lookupExact(evidence, "/checkpoint/signature/value").text();
      LanguageContent forged =
          f.language.prepare(
              Language.Input.exactJson(evidence.toJson().replace(signature, "0".repeat(128))));
      try (TimelineAccess access = f.track(f.initial)) {
        rejected("INVALID_PROVIDER_SIGNATURE", () -> access.advance(forged));
        assertTrue(access.view().isEmpty());
        assertTrue(
            access.advance(evidence).view().isPresent(),
            "A failed proof does not poison a later verified attempt");
      }
    }

    @Test
    void repeatedCoverageDoesNotExecuteUpdatesAgainAndRepublishingTheInitialDocumentFails() {
      Fixture f = new Fixture();
      LanguageContent entry = f.entry(null, 1, f.initial, false);
      try (TimelineAccess access = f.track(f.initial)) {
        TimelineAccess.View first =
            access.advance(f.range(null, entry.blueId(), 2, List.of(entry))).view().orElseThrow();
        TimelineAccess.Update refresh =
            access.advance(f.range(entry.blueId(), entry.blueId(), 3, List.of()));
        assertTrue(refresh.dispositions().isEmpty());
        assertEquals(first.document().blueId(), refresh.view().orElseThrow().document().blueId());
        assertEquals(3, refresh.completeBefore());
        LanguageContent duplicate = f.entry(entry.blueId(), 3, f.initial, false);
        rejected(
            "ACCESS_DUPLICATE_PUBLICATION",
            () ->
                access.advance(f.range(entry.blueId(), duplicate.blueId(), 4, List.of(duplicate))));
        assertEquals(3, access.view().orElseThrow().completeBefore());
      }
    }

    @Test
    void anAcceptedInvalidReplacementIsAnIntegrityFailureAndCannotPublishAPartialBatch() {
      Fixture f = new Fixture();
      LanguageContent publication = f.entry(null, 1, f.initial, false);
      try (TimelineAccess access = f.track(f.initial)) {
        TimelineAccess.View before =
            access
                .advance(f.range(null, publication.blueId(), 2, List.of(publication)))
                .view()
                .orElseThrow();
        LanguageContent valid =
            f.entry(
                publication.blueId(), 2, f.update(f.initial.blueId(), List.of(), List.of()), true);
        LanguageContent invalid =
            f.entry(
                valid.blueId(),
                3,
                f.update(f.initial.blueId(), List.of(READER, READER), List.of()),
                true);
        LanguageContent proof =
            f.range(publication.blueId(), invalid.blueId(), 4, List.of(valid, invalid));
        rejected("ACCESS_HISTORY_CONTRADICTION", () -> access.advance(proof));
        assertSame(before, access.view().orElseThrow());
        rejected("ACCESS_HISTORY_CONTRADICTION", () -> access.advance(proof));
        assertSame(before, access.view().orElseThrow());
      }
    }

    @Test
    void updateBeforePublicationAndMissingStableTargetDoNotGrantAccess() {
      Fixture f = new Fixture();
      LanguageContent entry =
          f.entry(null, 1, f.update(f.initial.blueId(), List.of(), List.of()), true);
      try (TimelineAccess access = f.track(f.initial)) {
        rejected(
            "ACCESS_UPDATE_BEFORE_PUBLICATION",
            () -> access.advance(f.range(null, entry.blueId(), 2, List.of(entry))));
        assertTrue(access.view().isEmpty());
      }
      LanguageContent request =
          f.language.prepare(
              Language.Input.yaml(
                  """
                  type: Contracts/Operation Request
                  channel: provider
                  operation: update
                  request:
                    type: Coordination/Timeline Access Update
                    allowedKeys: []
                    allowedTimelines: []
                  """));
      LanguageContent missing = f.entry(null, 1, request, true);
      try (TimelineAccess access = f.track(f.initial)) {
        rejected(
            "ACCESS_UPDATE_TARGET_REQUIRED",
            () -> access.advance(f.range(null, missing.blueId(), 2, List.of(missing))));
        assertTrue(access.view().isEmpty());
      }
    }

    @Test
    void closeReleasesTheReplayWithoutInvalidatingPriorViews() {
      Fixture f = new Fixture();
      TimelineAccess access = f.track(f.initial);
      LanguageContent entry = f.entry(null, 1, f.initial, false);
      TimelineAccess.View view =
          access.advance(f.range(null, entry.blueId(), 2, List.of(entry))).view().orElseThrow();
      access.close();
      access.close();
      assertEquals(Set.of(READER), view.allowedKeys());
      assertThrows(IllegalStateException.class, access::view);
    }
  }

  @Nested
  class ProviderPreparation {
    @Test
    void protectedColdRecoveryMatchesConsumerReplayWithoutHistoricalBodies() throws Exception {
      Fixture f = new Fixture();
      Map<BlueId, LanguageRecord> retained = new HashMap<>(Published.PACKAGE.records());
      Set<BlueId> removed = new HashSet<>();
      LanguageProvider resolver =
          id -> {
            assertFalse(removed.contains(id), "Reopened historical entry " + id);
            return Optional.ofNullable(retained.get(id));
          };
      Language durable =
          Language.newBuilder()
              .mount("Coordination", Published.PACKAGE)
              .content(f.provider)
              .content(f.control)
              .content(f.privateTimeline)
              .provider(resolver)
              .build();
      LanguageDocument initial =
          durable.read(Language.Input.exactJson(f.initial.toJson()), Language.Goal.VALUE);
      TimelineAccessProgram program =
          TimelineAccessProgram.create(Published.PACKAGE, f.provider, f.descriptor(), initial);
      TimelinePublisher publisher =
          TimelinePublisher.forTimeline(Published.PACKAGE, f.provider, f.control);
      TimelinePublisher.Prepared local = publisher.initial();
      LanguageContent publication = f.entry(null, 1, f.initial, false);
      TimelinePublisher.Prepared global =
          publisher.prepare(
              publisher.initial(), durable.prepare(Language.Input.exactJson(publication.toJson())));
      Map<String, Object> snapshot;
      String identity;
      try (TimelineAccessProgram.Session session = program.open()) {
        session.initialize();
        snapshot = session.snapshot(retained::containsKey);
        identity = session.identity();
      }
      var json = new com.fasterxml.jackson.databind.ObjectMapper();
      BlueId previous = publication.blueId();
      removed.add(previous);
      try (TimelineAccess consumer = f.track(f.initial)) {
        consumer.advance(f.range(null, publication.blueId(), 2, List.of(publication)));
        for (int i = 0; i < 5; i++) {
          for (var item : ((Map<?, ?>) snapshot.get("content")).entrySet()) {
            retained.put(
                new BlueId((String) item.getKey()),
                LanguageRecord.fromJson(json.writeValueAsString(item.getValue())));
          }
          Map<String, Object> thin = new LinkedHashMap<>(snapshot);
          thin.put("content", Map.of());
          TimelinePublisher restarted =
              TimelinePublisher.forTimeline(Published.PACKAGE, f.provider, f.control);
          TimelinePublisher.Prepared restoredGlobal =
              restarted.restore(global.checkpoint(), global.identity(), resolver);
          TimelinePublisher.Prepared restoredLocal =
              restarted.restore(local.checkpoint(), local.identity(), resolver);
          LanguageContent input =
              f.entry(
                  previous,
                  i + 2,
                  f.update(
                      initial.blueId(),
                      List.of((i % 2 == 0 ? "11" : "22").repeat(32)),
                      List.of(f.privateTimeline.blueId())),
                  true);
          LanguageContent entry = durable.prepare(Language.Input.exactJson(input.toJson()));
          TimelinePublisher.Prepared candidate = restarted.prepare(restoredGlobal, entry);
          try (TimelineAccessProgram.Session session =
              program.restore(thin, identity, resolver, restoredLocal)) {
            Contracts.Transition result = session.process(entry, candidate);
            TimelineAccess.Update expected =
                consumer.advance(f.range(previous, entry.blueId(), i + 3, List.of(input)));
            Contracts.Transition replay =
                expected.dispositions().getFirst().transition().orElseThrow();
            assertTrue(
                result.applied(),
                "i="
                    + i
                    + " initial="
                    + initial.blueId()
                    + " previous="
                    + previous
                    + " entry="
                    + entry.blueId()
                    + " "
                    + result.status()
                    + " "
                    + result.observations());
            assertEquals(replay.receipt(), result.receipt());
            assertEquals(replay.gas(), result.gas());
            assertEquals(replay.events(), result.events());
            assertEquals(
                expected.view().orElseThrow().document().blueId(), session.document().blueId());
            // The index retains earlier identities, but no earlier entry bodies need be fetched.
            snapshot = session.snapshot(id -> retained.containsKey(id) || removed.contains(id));
            identity = session.identity();
          }
          removed.add(previous);
          retained.remove(previous);
          local = candidate;
          global = candidate;
          previous = entry.blueId();
        }
        // A snapshot cannot silently be paired with a different consumed-input checkpoint.
        String expectedIdentity = identity;
        Map<String, Object> finalSnapshot = snapshot;
        rejected(
            "ACCESS_SNAPSHOT_CHECKPOINT",
            () ->
                program.restore(
                    finalSnapshot,
                    expectedIdentity,
                    id -> Optional.ofNullable(retained.getOrDefault(id, f.records.get(id))),
                    publisher.initial()));
      }
    }

    @Test
    void tentativePublicationAndRestoredUpdatesMatchIndependentConsumerReplay() {
      Fixture f = new Fixture();
      TimelineAccessProgram program =
          TimelineAccessProgram.create(Published.PACKAGE, f.provider, f.descriptor(), f.initial);
      LanguageContent publication = f.entry(null, 1, f.initial, false);
      try (TimelineAccess consumer = f.track(f.initial);
          TimelineAccessProgram.Session producer = program.open()) {
        Contracts.Transition initialized = producer.initialize();
        TimelineAccess.Update replayed =
            consumer.advance(f.range(null, publication.blueId(), 2, List.of(publication)));
        assertEquals(
            initialized.receipt(),
            replayed.dispositions().getFirst().transition().orElseThrow().receipt());
        assertEquals(
            producer.document().blueId(), replayed.view().orElseThrow().document().blueId());
        Map<String, Object> snapshot = producer.snapshot();
        String identity = producer.identity();
        BlueId previous = publication.blueId();
        for (int i = 0; i < 3; i++) {
          LanguageContent entry =
              f.entry(
                  previous,
                  i + 2,
                  f.update(
                      f.initial.blueId(),
                      i == 1 ? List.of() : List.of("22".repeat(32)),
                      List.of(f.privateTimeline.blueId())),
                  true);
          try (TimelineAccessProgram.Session restored = program.restore(snapshot, identity)) {
            Contracts.Transition result = restored.process(entry);
            assertTrue(result.applied(), result.observations().toString());
            TimelineAccess.Update observed =
                consumer.advance(f.range(previous, entry.blueId(), i + 3, List.of(entry)));
            Contracts.Transition expected =
                observed.dispositions().getFirst().transition().orElseThrow();
            assertEquals(expected.receipt(), result.receipt());
            assertEquals(expected.gas(), result.gas());
            assertEquals(expected.events(), result.events());
            assertEquals(
                observed.view().orElseThrow().document().blueId(), restored.document().blueId());
            snapshot = restored.snapshot();
            identity = restored.identity();
          }
          previous = entry.blueId();
        }
        assertTrue(initialized.applied());
      }
    }

    @Test
    void incrementalSnapshotsRestoreFromRetainedEvidenceWithoutChangingCommitments()
        throws Exception {
      Fixture f = new Fixture();
      Map<BlueId, LanguageRecord> retained = new HashMap<>(Published.PACKAGE.records());
      Language durable =
          Language.newBuilder()
              .mount("Coordination", Published.PACKAGE)
              .content(f.provider)
              .content(f.control)
              .content(f.privateTimeline)
              .provider(id -> Optional.ofNullable(retained.get(id)))
              .build();
      LanguageDocument initial =
          durable.read(Language.Input.exactJson(f.initial.toJson()), Language.Goal.VALUE);
      TimelineAccessProgram program =
          TimelineAccessProgram.create(Published.PACKAGE, f.provider, f.descriptor(), initial);
      Map<String, Object> snapshot;
      String identity;
      var json = new com.fasterxml.jackson.databind.ObjectMapper();
      try (TimelineAccessProgram.Session session = program.open()) {
        session.initialize();
        snapshot = session.snapshot(retained::containsKey);
        assertEquals(session.snapshot().get("identity"), snapshot.get("identity"));
        identity = session.identity();
      }
      LanguageContent publication = f.entry(null, 1, f.initial, false);
      retained.put(publication.blueId(), LanguageRecord.json(publication.toJson()));
      BlueId previous = publication.blueId();
      for (int i = 0; i < 4; i++) {
        Map<?, ?> evidence = (Map<?, ?>) snapshot.get("content");
        for (var item : evidence.entrySet()) {
          BlueId id = new BlueId((String) item.getKey());
          assertFalse(retained.containsKey(id), "Export must omit already retained closures");
          retained.put(id, LanguageRecord.fromJson(json.writeValueAsString(item.getValue())));
        }
        Map<String, Object> thin = new LinkedHashMap<>(snapshot);
        thin.put("content", Map.of());
        String expectedIdentity = identity;
        // Content retains its lazy provider. Recovery may use it without an extra reader.
        try (TimelineAccessProgram.Session recovered = program.restore(thin, expectedIdentity)) {
          assertEquals(expectedIdentity, recovered.identity());
        }
        TimelineAccessProgram disconnected =
            TimelineAccessProgram.create(Published.PACKAGE, f.provider, f.descriptor(), f.initial);
        assertThrows(BlueException.class, () -> disconnected.restore(thin, expectedIdentity));
        try (TimelineAccessProgram.Session restored =
            program.restore(thin, identity, id -> Optional.ofNullable(retained.get(id)))) {
          assertEquals(identity, restored.identity());
          LanguageContent entry =
              f.entry(
                  previous,
                  i + 2,
                  f.update(
                      initial.blueId(),
                      List.of((i % 2 == 0 ? "11" : "22").repeat(32)),
                      List.of(f.privateTimeline.blueId())),
                  true);
          assertTrue(restored.process(entry).applied());
          Map<String, Object> full = restored.snapshot();
          snapshot = restored.snapshot(retained::containsKey);
          assertEquals(full.get("identity"), snapshot.get("identity"));
          assertEquals(full.get("snapshot"), snapshot.get("snapshot"));
          assertTrue(
              ((Map<?, ?>) snapshot.get("content")).size()
                  < ((Map<?, ?>) full.get("content")).size());
          identity = restored.identity();
          previous = entry.blueId();
        }
      }
    }

    @Test
    void providerPreparationReportsItsMissingPredecessorEvidence() {
      Fixture f = new Fixture();
      TimelineAccessProgram program =
          TimelineAccessProgram.create(Published.PACKAGE, f.provider, f.descriptor(), f.initial);
      try (TimelineAccessProgram.Session session = program.open()) {
        session.initialize();
        BlueId unknown =
            Language.create().prepare(Language.Input.yaml("notRetained: true")).blueId();
        LanguageContent entry =
            f.entry(unknown, 10, f.update(f.initial.blueId(), List.of(), List.of()), true);
        String before = session.identity();
        rejected("NEEDS_RESOURCES", () -> session.process(entry));
        assertEquals(before, session.identity());
      }
    }

    @Test
    void failedReplacementCannotChangePreparedState() {
      Fixture f = new Fixture();
      TimelineAccessProgram program =
          TimelineAccessProgram.create(Published.PACKAGE, f.provider, f.descriptor(), f.initial);
      try (TimelineAccessProgram.Session session = program.open()) {
        session.initialize();
        String before = session.identity();
        LanguageContent duplicate =
            f.entry(
                null,
                1,
                f.update(
                    f.initial.blueId(),
                    List.of(READER, READER),
                    List.of(f.privateTimeline.blueId())),
                true);
        assertFalse(session.process(duplicate).applied());
        assertEquals(before, session.identity());
        LanguageContent valid =
            f.entry(null, 1, f.update(f.initial.blueId(), List.of(), List.of()), true);
        assertTrue(session.process(valid).applied());
      }
    }

    @ParameterizedTest
    @ValueSource(strings = {"actor", "target", "operation"})
    void invalidCandidateCannotEnterTheScopedNativeGate(String kind) {
      Fixture f = new Fixture();
      TimelineAccessProgram program =
          TimelineAccessProgram.create(Published.PACKAGE, f.provider, f.descriptor(), f.initial);
      try (TimelineAccessProgram.Session session = program.open()) {
        session.initialize();
        String before = session.identity();
        LanguageContent request =
            f.update(
                kind.equals("target") ? f.privateTimeline.blueId() : f.initial.blueId(),
                List.of(),
                List.of());
        LanguageContent entry =
            f.entryWithActor(
                null,
                1,
                request,
                !kind.equals("operation"),
                kind.equals("actor") ? "Principal Actor" : "Agent Actor");
        assertThrows(BlueException.class, () -> session.process(entry));
        assertEquals(before, session.identity());
      }
    }

    @Test
    void restoreRequiresTheTrustedCommitmentAndOriginalAccessTarget() {
      Fixture f = new Fixture();
      TimelineAccessProgram program =
          TimelineAccessProgram.create(Published.PACKAGE, f.provider, f.descriptor(), f.initial);
      try (TimelineAccessProgram.Session session = program.open()) {
        session.initialize();
        Map<String, Object> snapshot = session.snapshot();
        assertThrows(BlueException.class, () -> program.restore(snapshot, "untrusted"));
        LanguageDocument other = f.initial.edit(e -> e.set("/note", "another access"));
        TimelineAccessProgram different =
            TimelineAccessProgram.create(Published.PACKAGE, f.provider, f.descriptor(), other);
        rejected("ACCESS_SNAPSHOT_TARGET", () -> different.restore(snapshot, session.identity()));
      }
    }
  }

  private static List<TimelineAccess.Kind> kinds(TimelineAccess.Update result) {
    return result.dispositions().stream().map(TimelineAccess.Disposition::kind).toList();
  }

  private static void rejected(String code, Runnable action) {
    assertEquals(code, assertThrows(BlueException.class, action::run).code());
  }

  private static final class Fixture {
    final Map<BlueId, LanguageRecord> records = new HashMap<>();
    final LanguageDocument provider, control, privateTimeline, initial;
    final Language language;

    Fixture() {
      Language base = Language.newBuilder().mount("Coordination", Published.PACKAGE).build();
      provider =
          base.readYaml(
              """
              type: Coordination/Timeline Provider
              endpoint: https://provider.example
              publicKey: d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a
              """);
      Language sources =
          Language.newBuilder().mount("Coordination", Published.PACKAGE).content(provider).build();
      control =
          sources.readYaml(
              "type: Coordination/Signed Timeline\nprovider: {blueId: '"
                  + provider.blueId()
                  + "'}\nurl: https://provider.example/control\npublicRead: true");
      privateTimeline =
          sources.readYaml(
              "type: Coordination/Signed Timeline\nprovider: {blueId: '"
                  + provider.blueId()
                  + "'}\nurl: https://provider.example/private\npublicRead: false");
      language =
          Language.newBuilder()
              .mount("Coordination", Published.PACKAGE)
              .mount("Contracts", Contracts.publication())
              .content(provider)
              .content(control)
              .content(privateTimeline)
              .provider(id -> Optional.ofNullable(records.get(id)))
              .build();
      initial =
          language.readYaml(
              "type: Coordination/Timeline Access\nallowedKeys: ['"
                  + READER
                  + "']\nallowedTimelines: [{blueId: '"
                  + privateTimeline.blueId()
                  + "'}]\ncontracts:\n  provider:\n    provider: {blueId: '"
                  + provider.blueId()
                  + "'}\n    url: https://provider.example/control");
      records.put(initial.blueId(), LanguageRecord.json(initial.toJson()));
    }

    TimelineAccess track(LanguageDocument document) {
      return TimelineAccess.track(Published.PACKAGE, provider, descriptor(), document);
    }

    LanguageContent typed(String type, String fields) {
      return language.prepare(Language.Input.yaml("type: Coordination/" + type + "\n" + fields));
    }

    LanguageContent attest(LanguageContent content) {
      return typed(
          "Provider Attestation",
          "provider: {blueId: '"
              + provider.blueId()
              + "'}\nobject: "
              + content.toJson()
              + "\nsignature: '"
              + sign("blue.coordination.object\n" + provider.blueId() + "\n" + content.blueId())
              + "'");
    }

    LanguageContent descriptor() {
      LanguageContent descriptor =
          typed(
              "Provider Descriptor",
              "provider: "
                  + provider.toJson()
                  + "\nendpoint: https://provider.example\ncontrolTimeline: "
                  + control.toJson()
                  + "\nmaxPageSize: 128\noperations: []");
      return typed(
          "Provider Attestation",
          "provider: {blueId: '"
              + provider.blueId()
              + "'}\nobject: "
              + descriptor.toJson()
              + "\nsignature: '"
              + sign("blue.coordination.object\n" + provider.blueId() + "\n" + descriptor.blueId())
              + "'");
    }

    LanguageContent entry(BlueId previous, long time, LanguageContent message, boolean operation) {
      return entryWithActor(previous, time, message, operation, "Agent Actor");
    }

    LanguageContent entryWithActor(
        BlueId previous, long time, LanguageContent message, boolean operation, String actor) {
      LanguageContent entry =
          typed(
              "Timeline Entry",
              "timeline: {blueId: '"
                  + control.blueId()
                  + "'}\ntimestamp: "
                  + time
                  + "\nactor: {type: Coordination/"
                  + actor
                  + ", service: provider}\nmessage: "
                  + message.toJson()
                  + (operation ? "\noperation: " + message.toJson() : "")
                  + (previous == null ? "" : "\nprevEntry: {blueId: '" + previous + "'}"));
      records.put(entry.blueId(), LanguageRecord.json(entry.toJson()));
      return entry;
    }

    LanguageContent update(BlueId target, List<String> keys, List<BlueId> timelines) {
      return language.prepare(
          Language.Input.yaml(
              "type: Contracts/Operation Request\ndocument: {blueId: '"
                  + target
                  + "'}\n"
                  + "channel: provider\n"
                  + "operation: update\n"
                  + "request:\n"
                  + "  type: Coordination/Timeline Access Update\n"
                  + "  allowedKeys: ["
                  + String.join(",", keys.stream().map(k -> "'" + k + "'").toList())
                  + "]\n  allowedTimelines: ["
                  + String.join(",", timelines.stream().map(t -> "{blueId: '" + t + "'}").toList())
                  + "]"));
    }

    LanguageContent range(BlueId after, BlueId last, long before, List<LanguageContent> entries) {
      LanguageContent guarantee =
          typed(
              "Timeline Completeness Guarantee",
              "timeline: {blueId: '"
                  + control.blueId()
                  + "'}\ncompleteBefore: "
                  + before
                  + (last == null ? "" : "\nlastEntry: {blueId: '" + last + "'}"));
      LanguageContent checkpoint =
          typed(
              "Timeline Attestation",
              "timeline: {blueId: '"
                  + control.blueId()
                  + "'}\nguarantee: "
                  + guarantee.toJson()
                  + "\nsignature: '"
                  + sign("blue.coordination.guarantee\n" + guarantee.blueId())
                  + "'");
      return typed(
          "Timeline Range Evidence",
          "checkpoint: "
              + checkpoint.toJson()
              + "\nentries: ["
              + String.join(",", entries.stream().map(LanguageContent::toJson).toList())
              + "]"
              + (after == null ? "" : "\nafter: {blueId: '" + after + "'}"));
    }
  }

  private static String sign(String text) {
    try {
      // Public RFC 8032 test seed, never a deployment credential.
      byte[] seed =
          HexFormat.of()
              .parseHex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60");
      PrivateKey key =
          KeyFactory.getInstance("Ed25519")
              .generatePrivate(new EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed));
      Signature signature = Signature.getInstance("Ed25519");
      signature.initSign(key);
      signature.update(text.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(signature.sign());
    } catch (GeneralSecurityException failure) {
      throw new AssertionError(failure);
    }
  }
}
