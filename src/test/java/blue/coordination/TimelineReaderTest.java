package blue.coordination;

import static org.junit.jupiter.api.Assertions.*;

import blue.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("coordination")
class TimelineReaderTest {
  private static final class Published {
    static final BluePackage PACKAGE =
        PackageLoader.create()
            .resource(
                TimelineReaderTest.class.getClassLoader(),
                "coordination/coordination.bluepkg",
                ProviderSignatures.COORDINATION_PACKAGE);
  }

  @Nested
  class CompletedRangeReuse {
    @Test
    void successivePagesReuseOnlyPreviouslyCheckedExactSubtrees() {
      Fixture f = new Fixture();
      StringBuilder source = new StringBuilder();
      Set<BlueId> children = new HashSet<>();
      for (int i = 0; i < 16; i++) {
        LanguageContent child = f.language.prepare(Language.Input.yaml("amount: " + i));
        children.add(child.blueId());
        f.records.put(child.blueId(), LanguageRecord.json(child.toJson()));
        source
            .append("child")
            .append(i)
            .append(": {blueId: '")
            .append(child.blueId())
            .append("'}\n");
      }
      LanguageContent shared = f.language.prepare(Language.Input.yaml(source.toString()));
      f.records.put(shared.blueId(), LanguageRecord.json(shared.toJson()));
      LanguageContent first =
          f.typed(
              "Timeline Entry",
              "timeline: {blueId: '"
                  + f.timeline.blueId()
                  + "'}\ntimestamp: 1\nactor: {type: Coordination/Agent Actor, service: demo}"
                  + "\nmessage: {shared: {blueId: '"
                  + shared.blueId()
                  + "'}, revision: 1}");
      LanguageContent request = f.request("limit: 10"),
          checkpoint = f.checkpoint(first.blueId(), 2);
      TimelineReader.Page one =
          f.reader
              .begin(f.reader.initial(), request)
              .accept(
                  f.response(
                      request, checkpoint, f.page(checkpoint, null, List.of(first), false, null)));
      assertTrue(f.demands.containsAll(children));
      f.demands.clear();
      LanguageContent second =
          f.typed(
              "Timeline Entry",
              "timeline: {blueId: '"
                  + f.timeline.blueId()
                  + "'}\ntimestamp: 2\nactor: {type: Coordination/Agent Actor, service: demo}"
                  + "\nprevEntry: {blueId: '"
                  + first.blueId()
                  + "'}\nmessage: {shared: {blueId: '"
                  + shared.blueId()
                  + "'}, revision: 2}");
      LanguageContent next = f.request("limit: 10\nafterEntry: {blueId: '" + first.blueId() + "'}");
      LanguageContent latest = f.checkpoint(second.blueId(), 3);
      LanguageContent response =
          f.response(next, latest, f.page(latest, first, List.of(second), false, null));
      TimelineReader.Page two =
          f.reader.begin(one.completedPrefix().orElseThrow(), next).accept(response);
      assertEquals(
          List.of(second.blueId()),
          two.entries().stream().map(TimelineVerifier.Entry::blueId).toList());
      assertTrue(
          f.demands.stream().filter(children::contains).count() < 3,
          "Header inspection may read a present child, but must not walk unchanged descendants");

      // A separate verifier receives no facts or content from this reader's cache.
      Fixture independent = new Fixture();
      assertThrows(
          LanguageException.class,
          () ->
              independent
                  .reader
                  .begin(independent.reader.initial(), independent.request("limit: 10"))
                  .accept(
                      independent.language.prepare(
                          Language.Input.exactJson(
                              f.response(
                                      request,
                                      checkpoint,
                                      f.page(checkpoint, null, List.of(first), false, null))
                                  .toJson()))));
    }

    @Test
    void aCompletedReadReusesOnlyItsExactRangeAndOriginalPrefix() {
      Fixture f = new Fixture();
      TimelineVerifier verifier = TimelineVerifier.adopt(Published.PACKAGE, f.provider, f.timeline);
      TimelineReader reader =
          TimelineReader.adopt(
              verifier, f.descriptor("https://provider.example", "2048", f.timeline));
      TimelineVerifier.Prefix initial = verifier.initial();
      LanguageContent entry = f.entry(null, 1);
      LanguageContent checkpoint = f.checkpoint(entry.blueId(), 2);
      LanguageContent request = f.request("limit: 10");
      TimelineReader.Page page =
          reader
              .begin(initial, request)
              .accept(
                  f.response(
                      request, checkpoint, f.page(checkpoint, null, List.of(entry), false, null)));
      LanguageContent range =
          f.typed(
              "Timeline Range Evidence",
              "checkpoint: {blueId: '"
                  + checkpoint.blueId()
                  + "'}\nentries: [{blueId: '"
                  + entry.blueId()
                  + "'}]");
      TimelineVerifier independent =
          TimelineVerifier.adopt(Published.PACKAGE, f.provider, f.timeline);
      LanguageContent complete =
          f.typed(
              "Timeline Range Evidence",
              "checkpoint: " + checkpoint.toJson() + "\nentries: [" + entry.toJson() + "]");
      TimelineVerifier.Result replayed = independent.advance(independent.initial(), complete);
      assertEquals(complete.blueId(), range.blueId());
      f.records.clear();
      f.demands.clear();
      TimelineVerifier.Result result = verifier.advance(initial, range);
      assertEquals(replayed.prefix().checkpoint(), result.prefix().checkpoint());
      assertSame(page.completedPrefix().orElseThrow(), result.prefix());
      assertEquals(
          List.of(entry.blueId()),
          result.entries().stream().map(TimelineVerifier.Entry::blueId).toList());
      assertTrue(f.demands.isEmpty());
      // Equal-looking but separately issued progress, another verifier, and changed content
      // all require the ordinary evidence path, whose required bodies were not supplied.
      assertThrows(LanguageException.class, () -> verifier.advance(verifier.initial(), range));
      TimelineVerifier foreign = TimelineVerifier.adopt(Published.PACKAGE, f.provider, f.timeline);
      assertThrows(LanguageException.class, () -> foreign.advance(foreign.initial(), range));
      LanguageContent changed =
          f.typed(
              "Timeline Range Evidence",
              "checkpoint: {blueId: '" + checkpoint.blueId() + "'}\nentries: []");
      assertThrows(LanguageException.class, () -> verifier.advance(initial, changed));
      LanguageContent ordinary = f.language.prepare(Language.Input.yaml("amount: 1"));
      try (Contracts.Session session = Contracts.newBuilder().build().openYaml("ready: true")) {
        session.initialize().requireApplied();
        assertTrue(session.operationRequest(result.entries().getFirst(), ordinary).isEmpty());
        assertThrows(
            IllegalArgumentException.class,
            () -> session.operationRequest(result.entries().getFirst(), changed));
      }
    }

    @Test
    void anIncompleteReadDoesNotSeedACompletedRange() {
      Fixture f = new Fixture();
      TimelineVerifier verifier = TimelineVerifier.adopt(Published.PACKAGE, f.provider, f.timeline);
      TimelineReader reader =
          TimelineReader.adopt(
              verifier, f.descriptor("https://provider.example", "2048", f.timeline));
      TimelineVerifier.Prefix initial = verifier.initial();
      LanguageContent first = f.entry(null, 1), second = f.entry(first.blueId(), 2);
      LanguageContent checkpoint = f.checkpoint(second.blueId(), 3);
      LanguageContent request = f.request("limit: 1");
      TimelineReader.Page page =
          reader
              .begin(initial, request)
              .accept(
                  f.response(
                      request,
                      checkpoint,
                      f.page(
                          checkpoint,
                          null,
                          List.of(first),
                          true,
                          f.cursor(checkpoint, first.blueId(), ""))));
      assertTrue(page.completedPrefix().isEmpty());
      LanguageContent range =
          f.typed(
              "Timeline Range Evidence",
              "checkpoint: {blueId: '"
                  + checkpoint.blueId()
                  + "'}\nentries: [{blueId: '"
                  + first.blueId()
                  + "'}]");
      f.records.clear();
      assertThrows(LanguageException.class, () -> verifier.advance(initial, range));
    }
  }

  @Nested
  class ReferencedContent {
    @Test
    void authenticatesDefinitionsWithoutResolvingTheirDependenciesOrCertifyingValues() {
      Fixture f = new Fixture();
      LanguageContent missing = f.language.prepare(Language.Input.yaml("name: External type"));
      LanguageContent definition =
          f.language.prepare(
              Language.Input.yaml(
                  "name: Clause\nfield: {type: {blueId: '" + missing.blueId() + "'}}"));
      LanguageContent request = request(f, definition);
      TimelineReader.ContentRead read = f.reader.content(request);
      f.demands.clear();
      LanguageContent content = read.accept(response(f, request, definition));
      assertEquals(request.blueId(), read.request().blueId());
      assertEquals(definition.blueId(), content.blueId());
      assertEquals(definition.toJson(), content.toJson());
      assertFalse(f.demands.contains(missing.blueId()));
      assertEquals(
          "ProviderUnavailable",
          assertThrows(
                  LanguageException.class,
                  () -> Language.create().admit(content, Language.Goal.DEFINITION))
              .category());
      assertTrue(f.reader.initial().headId().isEmpty());
    }

    @Test
    void requiresExactSelectedSourceReferenceAndReadOnlyRequest() {
      Fixture f = new Fixture();
      LanguageContent value = f.language.prepare(Language.Input.yaml("value: 42"));
      rejected(
          "TIMELINE_WIRE_REFERENCE",
          () ->
              f.reader.content(
                  f.typed(
                      "Resolve Timeline Content Request",
                      "timeline: " + f.timeline.toJson() + "\ncontent: " + value.toJson())));
      rejected(
          "TIMELINE_SOURCE_MISMATCH",
          () ->
              f.reader.content(
                  f.typed(
                      "Resolve Timeline Content Request",
                      "timeline: {blueId: '"
                          + value.blueId()
                          + "'}\ncontent: {blueId: '"
                          + value.blueId()
                          + "'}")));
      rejected(
          "TIMELINE_REQUEST_FIELDS",
          () ->
              f.reader.content(
                  f.typed(
                      "Resolve Timeline Content Request",
                      "timeline: {blueId: '"
                          + f.timeline.blueId()
                          + "'}\ncontent: {blueId: '"
                          + value.blueId()
                          + "'}\nnonce: forbidden")));
    }

    @Test
    void rejectsWrongContentCorrelationProviderSignatureAndReferenceOnlyBodies() {
      Fixture f = new Fixture();
      LanguageContent one = f.language.prepare(Language.Input.yaml("value: 1"));
      LanguageContent two = f.language.prepare(Language.Input.yaml("value: 2"));
      LanguageContent request = request(f, one);
      TimelineReader.ContentRead read = f.reader.content(request);
      rejected("TIMELINE_WRONG_CONTENT", () -> read.accept(response(f, request, two)));
      rejected("TIMELINE_WRONG_REQUEST", () -> read.accept(response(f, request(f, two), one)));
      rejected(
          "TIMELINE_WIRE_INLINE",
          () ->
              read.accept(
                  f.attest(
                      f.typed(
                          "Timeline Content Response",
                          "request: {blueId: '"
                              + request.blueId()
                              + "'}\ncontent: {blueId: '"
                              + one.blueId()
                              + "'}"))));
      LanguageContent result =
          f.typed(
              "Timeline Content Response",
              "request: {blueId: '" + request.blueId() + "'}\ncontent: " + one.toJson());
      rejected(
          "TIMELINE_PROVIDER_MISMATCH",
          () ->
              read.accept(
                  f.typed(
                      "Provider Attestation",
                      "provider: {blueId: '"
                          + two.blueId()
                          + "'}\nobject: "
                          + result.toJson()
                          + "\nsignature: '"
                          + f.signObject(result)
                          + "'")));
      assertThrows(
          BlueException.class,
          () ->
              read.accept(
                  f.typed(
                      "Provider Attestation",
                      "provider: {blueId: '"
                          + f.provider.blueId()
                          + "'}\nobject: "
                          + result.toJson()
                          + "\nsignature: '"
                          + "0".repeat(128)
                          + "'")));
    }

    @Test
    void correlatedFailuresAndInvalidExtrasDoNotConsumeTheRead() {
      Fixture f = new Fixture();
      LanguageContent value = f.language.prepare(Language.Input.yaml("value: 42"));
      LanguageContent request = request(f, value);
      TimelineReader.ContentRead read = f.reader.content(request);
      LanguageContent failure =
          f.attest(
              f.typed(
                  "Provider Failure",
                  "request: {blueId: '"
                      + request.blueId()
                      + "'}\ncode: NOT_FOUND\nretryable: false"));
      assertEquals(
          "NOT_FOUND",
          assertThrows(TimelineReader.Failure.class, () -> read.accept(failure)).code());
      LanguageContent invalid =
          f.attest(
              f.typed(
                  "Timeline Content Response",
                  "request: {blueId: '"
                      + request.blueId()
                      + "'}\ncontent: "
                      + value.toJson()
                      + "\nextra: {type: Integer, schema: {minimum: 10}, value: 1}"));
      assertThrows(LanguageException.class, () -> read.accept(invalid));
      assertEquals(value.blueId(), read.accept(response(f, request, value)).blueId());
    }

    private LanguageContent request(Fixture f, LanguageContent content) {
      return f.typed(
          "Resolve Timeline Content Request",
          "timeline: {blueId: '"
              + f.timeline.blueId()
              + "'}\ncontent: {blueId: '"
              + content.blueId()
              + "'}");
    }

    private LanguageContent response(Fixture f, LanguageContent request, LanguageContent content) {
      return f.attest(
          f.typed(
              "Timeline Content Response",
              "request: {blueId: '" + request.blueId() + "'}\ncontent: " + content.toJson()));
    }
  }

  @Nested
  class AcceptedEntries {
    @Test
    void acceptedLastEntryEnablesOrdinarySuccessorEvidenceWithoutClosingTheUnreadCheckpoint() {
      Fixture f = new Fixture();
      LanguageContent first = f.entry(null, 1), second = f.entry(first.blueId(), 2);
      LanguageContent last = f.entry(second.blueId(), 3);
      TimelineReader.Page page = partial(f, List.of(first, second), last);
      LanguageContent request = fetch(f, second);
      TimelineReader.EntryRead read = page.fetchLastEntry(request);
      assertEquals(request.blueId(), read.request().blueId());
      f.demands.clear();
      LanguageContent acceptance = read.accept(entryResponse(f, request, second, f.attest(second)));
      assertEquals(f.attest(second).blueId(), acceptance.blueId());
      assertFalse(
          f.demands.contains(first.blueId()), "The verified entry header avoids reopening history");
      assertFalse(
          f.demands.contains(last.blueId()), "The unread checkpoint head grants no authority");
      assertTrue(page.completedPrefix().isEmpty());
      TimelineVerifier verifier = TimelineVerifier.adopt(Published.PACKAGE, f.provider, f.timeline);
      LanguageContent evidence =
          f.typed(
              "Timeline Successor Evidence",
              "entries: ["
                  + first.toJson()
                  + ","
                  + second.toJson()
                  + "]\nsuccessor: "
                  + acceptance.toJson());
      TimelineVerifier.Result result = verifier.advance(verifier.initial(), evidence);
      assertEquals(2, result.prefix().completeBefore());
      assertEquals(Optional.of(first.blueId()), result.prefix().headId());
      assertEquals(Optional.of(second.blueId()), result.prefix().successorId());
      assertEquals(
          List.of(first.blueId()),
          result.entries().stream().map(TimelineVerifier.Entry::blueId).toList());
    }

    @Test
    void requestAndBothAttestationsMustBindTheExactVerifiedEntry() {
      Fixture f = new Fixture();
      LanguageContent first = f.entry(null, 1), last = f.entry(first.blueId(), 2);
      TimelineReader.Page page = partial(f, List.of(first), last);
      rejected(
          "TIMELINE_WIRE_REFERENCE",
          () ->
              page.fetchLastEntry(
                  f.typed(
                      "Fetch Timeline Entry Request",
                      "timeline: {blueId: '"
                          + f.timeline.blueId()
                          + "'}\nentry: "
                          + first.toJson())));
      rejected("TIMELINE_WRONG_ENTRY", () -> page.fetchLastEntry(fetch(f, last)));
      LanguageContent request = fetch(f, first);
      TimelineReader.EntryRead read = page.fetchLastEntry(request);
      rejected(
          "TIMELINE_WRONG_REQUEST",
          () -> read.accept(entryResponse(f, fetch(f, last), first, f.attest(first))));
      rejected(
          "TIMELINE_WRONG_ENTRY",
          () -> read.accept(entryResponse(f, request, last, f.attest(last))));
      rejected(
          "TIMELINE_WRONG_ENTRY",
          () -> read.accept(entryResponse(f, request, first, f.attest(last))));
      LanguageContent bad =
          f.typed(
              "Provider Attestation",
              "provider: {blueId: '"
                  + f.provider.blueId()
                  + "'}\nobject: "
                  + first.toJson()
                  + "\nsignature: '"
                  + "0".repeat(128)
                  + "'");
      assertThrows(BlueException.class, () -> read.accept(entryResponse(f, request, first, bad)));
    }

    @Test
    void exactAcceptanceCanReferenceTheInlineResponseEntryAndFailuresDoNotConsumeTheRead() {
      Fixture f = new Fixture();
      LanguageContent first = f.entry(null, 1), last = f.entry(first.blueId(), 2);
      TimelineReader.EntryRead read =
          partial(f, List.of(first), last).fetchLastEntry(fetch(f, first));
      LanguageContent failure =
          f.attest(
              f.typed(
                  "Provider Failure",
                  "request: {blueId: '"
                      + read.request().blueId()
                      + "'}\ncode: UNAVAILABLE\nretryable: true\ndetail: Try later"));
      TimelineReader.Failure denied =
          assertThrows(TimelineReader.Failure.class, () -> read.accept(failure));
      assertEquals("UNAVAILABLE", denied.code());
      assertTrue(denied.retryable());
      assertEquals("Try later", denied.getMessage());
      LanguageContent acceptance =
          f.typed(
              "Provider Attestation",
              "provider: {blueId: '"
                  + f.provider.blueId()
                  + "'}\nobject: {blueId: '"
                  + first.blueId()
                  + "'}\nsignature: '"
                  + f.signObject(first)
                  + "'");
      assertEquals(
          acceptance.blueId(),
          read.accept(entryResponse(f, read.request(), first, acceptance)).blueId());
    }

    @Test
    void anEmptyPageCannotRequestAcceptanceAndInvalidExtraFieldsRemainInvalid() {
      Fixture f = new Fixture();
      LanguageContent request = f.request("limit: 1"), checkpoint = f.checkpoint(null, 1);
      TimelineReader.Page empty =
          f.reader
              .begin(f.reader.initial(), request)
              .accept(
                  f.response(
                      request, checkpoint, f.page(checkpoint, null, List.of(), false, null)));
      rejected("TIMELINE_ENTRY_REQUIRED", () -> empty.fetchLastEntry(fetch(f, f.entry(null, 1))));
      LanguageContent first = f.entry(null, 1), last = f.entry(first.blueId(), 2);
      TimelineReader.EntryRead read =
          partial(f, List.of(first), last).fetchLastEntry(fetch(f, first));
      LanguageContent response =
          f.attest(
              f.typed(
                  "Timeline Entry Response",
                  "request: {blueId: '"
                      + read.request().blueId()
                      + "'}\nentry: "
                      + first.toJson()
                      + "\nentryAttestation: "
                      + f.attest(first).toJson()
                      + "\nextra: {type: Integer, schema: {minimum: 10}, value: 1}"));
      assertThrows(LanguageException.class, () -> read.accept(response));
    }

    private TimelineReader.Page partial(
        Fixture f, List<LanguageContent> entries, LanguageContent last) {
      LanguageContent checkpoint = f.checkpoint(last.blueId(), last.at("/timestamp").integer() + 1);
      LanguageContent request = f.request("limit: " + entries.size());
      return f.reader
          .begin(f.reader.initial(), request)
          .accept(
              f.response(
                  request,
                  checkpoint,
                  f.page(
                      checkpoint,
                      null,
                      entries,
                      true,
                      f.cursor(checkpoint, entries.getLast().blueId(), ""))));
    }

    private LanguageContent fetch(Fixture f, LanguageContent entry) {
      return f.typed(
          "Fetch Timeline Entry Request",
          "timeline: {blueId: '"
              + f.timeline.blueId()
              + "'}\nentry: {blueId: '"
              + entry.blueId()
              + "'}");
    }

    private LanguageContent entryResponse(
        Fixture f, LanguageContent request, LanguageContent entry, LanguageContent acceptance) {
      return f.attest(
          f.typed(
              "Timeline Entry Response",
              "request: {blueId: '"
                  + request.blueId()
                  + "'}\nentry: "
                  + entry.toJson()
                  + "\nentryAttestation: "
                  + acceptance.toJson()));
    }
  }

  @Nested
  class DiscoveryAndRequests {
    @ParameterizedTest
    @ValueSource(strings = {"requestId", "provider", "createdAt", "expiresAt", "nonce"})
    void requestsDoNotInventClientLabelsClocksOrRepeatedProviderIdentities(String field) {
      Fixture f = new Fixture();
      rejected(
          "TIMELINE_REQUEST_FIELDS",
          () -> f.reader.begin(f.reader.initial(), f.request("limit: 0\n" + field + ": 1")));
    }

    @Test
    void signedDiscoveryPinsProviderEndpointPublicControlAndAdvertisedLimit() {
      Fixture f = new Fixture();
      assertEquals(BigInteger.valueOf(2048), f.reader.maximumPageSize());
      assertEquals(f.timeline.blueId(), f.reader.controlTimelineId());
      f.reader.begin(f.reader.initial(), f.request("limit: 1500"));
      rejected(
          "TIMELINE_PAGE_LIMIT",
          () -> f.reader.begin(f.reader.initial(), f.request("limit: 2049")));
      LanguageContent descriptor = f.descriptor("https://wrong.example", "2048", f.timeline);
      rejected(
          "TIMELINE_PROVIDER_MISMATCH",
          () -> TimelineReader.adopt(Published.PACKAGE, f.provider, f.timeline, descriptor));
      LanguageDocument privateControl = f.timeline.edit(e -> e.set("/publicRead/value", false));
      rejected(
          "TIMELINE_PRIVATE_CONTROL",
          () ->
              TimelineReader.adopt(
                  Published.PACKAGE,
                  f.provider,
                  f.timeline,
                  f.descriptor("https://provider.example", "2048", privateControl)));
      LanguageContent huge =
          f.descriptor("https://provider.example", "18446744073709551616", f.timeline);
      assertEquals(
          new BigInteger("18446744073709551616"),
          TimelineReader.adopt(Published.PACKAGE, f.provider, f.timeline, huge).maximumPageSize());
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
          "limit: 0\nafterTimestamp: -1",
          "limit: 1\nafterTimestamp: -2",
          "limit: 1\nafterTimestamp: 9007199254740991",
          "limit: 1\nminimumCompleteBefore: -1",
          "limit: 1\nminimumCompleteBefore: 9007199254740992"
        })
    void invalidSelectorAndTimestampCombinationsFailBeforeTransport(String fields) {
      Fixture f = new Fixture();
      assertThrows(
          RuntimeException.class, () -> f.reader.begin(f.reader.initial(), f.request(fields)));
    }

    @Test
    void cursorCannotHaveAnotherSelectorOrAMinimum() {
      Fixture f = new Fixture();
      LanguageContent checkpoint = f.checkpoint(null, 10);
      LanguageContent cursor = f.cursor(checkpoint, null, "afterTimestamp: -1");
      rejected(
          "TIMELINE_SELECTORS",
          () ->
              f.reader.begin(
                  f.reader.initial(),
                  f.request(
                      "limit: 1\ncursor: " + cursor.toJson() + "\nminimumCompleteBefore: 1")));
      rejected(
          "TIMELINE_SELECTORS",
          () ->
              f.reader.begin(
                  f.reader.initial(),
                  f.request("limit: 1\ncursor: " + cursor.toJson() + "\nafterTimestamp: -1")));
    }

    @Test
    void adoptedTimelineUrlsCannotHaveTrailingSlashes() {
      Fixture f = new Fixture();
      LanguageDocument invalid =
          f.timeline.edit(e -> e.set("/url/value", "https://provider.example/history/"));
      rejected(
          "TIMELINE_URL", () -> TimelineVerifier.adopt(Published.PACKAGE, f.provider, invalid));
    }

    @Test
    void discoveryMayAdvertiseNoOptionalOperationsButMustSupplyBootstrapBodies() {
      Fixture f = new Fixture();
      LanguageContent descriptor =
          f.descriptor("https://provider.example", "1", f.timeline, List.of());
      assertEquals(
          BigInteger.ONE,
          TimelineReader.adopt(Published.PACKAGE, f.provider, f.timeline, descriptor)
              .maximumPageSize());
      for (String body : List.of(f.provider.toJson(), f.timeline.toJson())) {
        String reference =
            "{\"blueId\":\""
                + (body.equals(f.provider.toJson()) ? f.provider.blueId() : f.timeline.blueId())
                + "\"}";
        LanguageContent collapsed =
            f.language.prepare(
                Language.Input.exactJson(descriptor.toJson().replace(body, reference)));
        // Expansion preserves identity, so the original signature remains valid.
        assertEquals(descriptor.blueId(), collapsed.blueId());
        rejected(
            "TIMELINE_WIRE_INLINE",
            () -> TimelineReader.adopt(Published.PACKAGE, f.provider, f.timeline, collapsed));
      }
    }
  }

  @Nested
  class CheckpointAndFailureClaims {
    @Test
    void checkpointOnlyClaimsCannotContradictEstablishedHistoryOrTimestampZero() {
      Fixture f = new Fixture();
      LanguageContent entry = f.entry(null, 1);
      LanguageContent request = f.request("limit: 1");
      LanguageContent checkpoint = f.checkpoint(entry.blueId(), 2);
      TimelineVerifier.Prefix prefix =
          f.reader
              .begin(f.reader.initial(), request)
              .accept(
                  f.response(
                      request, checkpoint, f.page(checkpoint, null, List.of(entry), false, null)))
              .completedPrefix()
              .orElseThrow();
      LanguageContent refresh = f.request("limit: 0");
      rejected(
          "TIMELINE_EQUIVOCATION",
          () ->
              f.reader
                  .begin(prefix, refresh)
                  .accept(f.response(refresh, f.checkpoint(null, 2), null)));
      rejected(
          "TIMELINE_BOUND",
          () ->
              f.reader
                  .begin(f.reader.initial(), refresh)
                  .accept(f.response(refresh, f.checkpoint(entry.blueId(), 0), null)));
      assertEquals(
          3,
          f.reader
              .begin(prefix, refresh)
              .accept(f.response(refresh, f.checkpoint(entry.blueId(), 3), null))
              .completedPrefix()
              .orElseThrow()
              .completeBefore());
    }

    @Test
    void checkpointOnlyDoesNotFetchOrCertifyAnUnknownHead() {
      Fixture f = new Fixture();
      LanguageContent entry = f.entry(null, 3);
      LanguageContent request = f.request("limit: 0");
      TimelineReader.Page result =
          f.reader
              .begin(f.reader.initial(), request)
              .accept(f.response(request, f.checkpoint(entry.blueId(), 4), null));
      assertFalse(result.includesEntries());
      assertTrue(result.entries().isEmpty());
      assertTrue(result.completedPrefix().isEmpty());
      assertEquals(4, result.claimedCompleteBefore());
      assertEquals(Optional.of(entry.blueId()), result.claimedHeadId());
      assertFalse(f.demands.contains(entry.blueId()));
    }

    @Test
    void signedEmptyCheckpointCanAdvanceAnEmptyPrefixAndMinimumMustBeMet() {
      Fixture f = new Fixture();
      LanguageContent request = f.request("limit: 0\nminimumCompleteBefore: 10");
      TimelineReader.Read read = f.reader.begin(f.reader.initial(), request);
      rejected(
          "TIMELINE_BOUND", () -> read.accept(f.response(request, f.checkpoint(null, 9), null)));
      TimelineReader.Page result = read.accept(f.response(request, f.checkpoint(null, 10), null));
      assertEquals(10, result.completedPrefix().orElseThrow().completeBefore());
      assertTrue(result.completedPrefix().orElseThrow().headId().isEmpty());
      assertTrue(result.claimedHeadId().isEmpty());
    }

    @Test
    void pagePresenceExactlyMatchesTheRequestedMode() {
      Fixture f = new Fixture();
      LanguageContent checkpoint = f.checkpoint(null, 0);
      LanguageContent positive = f.request("limit: 1");
      rejected(
          "TIMELINE_PAGE_REQUIRED",
          () ->
              f.reader
                  .begin(f.reader.initial(), positive)
                  .accept(f.response(positive, checkpoint, null)));
      LanguageContent zero = f.request("limit: 0");
      rejected(
          "TIMELINE_UNEXPECTED_PAGE",
          () ->
              f.reader
                  .begin(f.reader.initial(), zero)
                  .accept(
                      f.response(
                          zero, checkpoint, f.page(checkpoint, null, List.of(), false, null))));
    }

    @Test
    void correlatedSignedFailureIsNotAnEmptyPageAndDoesNotConsumeTheRead() {
      Fixture f = new Fixture();
      LanguageContent request = f.request("limit: 1");
      TimelineReader.Read read = f.reader.begin(f.reader.initial(), request);
      LanguageContent failure =
          f.typed(
              "Provider Failure",
              "request: {blueId: '"
                  + request.blueId()
                  + "'}\ncode: UNAVAILABLE\nretryable: true\ndetail: Retry later");
      TimelineReader.Failure result =
          assertThrows(TimelineReader.Failure.class, () -> read.accept(f.attest(failure)));
      assertEquals("UNAVAILABLE", result.code());
      assertTrue(result.retryable());
      assertEquals(f.attest(failure).blueId(), result.responseId());
      LanguageContent checkpoint = f.checkpoint(null, 10);
      assertEquals(
          10,
          read.accept(
                  f.response(request, checkpoint, f.page(checkpoint, null, List.of(), false, null)))
              .completedPrefix()
              .orElseThrow()
              .completeBefore());
    }

    @Test
    void responseCorrelationAndOuterSignatureAreRequiredForSuccessAndFailure() {
      Fixture f = new Fixture();
      LanguageContent request = f.request("limit: 0");
      LanguageContent other = f.request("limit: 0\nminimumCompleteBefore: 1");
      TimelineReader.Read read = f.reader.begin(f.reader.initial(), request);
      rejected(
          "TIMELINE_WRONG_REQUEST",
          () -> read.accept(f.response(other, f.checkpoint(null, 2), null)));
      LanguageContent failure =
          f.typed(
              "Provider Failure",
              "request: {blueId: '" + other.blueId() + "'}\ncode: NOT_FOUND\nretryable: false");
      rejected("TIMELINE_WRONG_REQUEST", () -> read.accept(f.attest(failure)));
      LanguageContent response = f.response(request, f.checkpoint(null, 2), null);
      Node signature = f.language.lookupExact(response, "/signature/value");
      LanguageContent forged =
          f.language.prepare(
              Language.Input.exactJson(
                  response.toJson().replace(signature.text(), "0".repeat(128))));
      rejected("INVALID_PROVIDER_SIGNATURE", () -> read.accept(forged));
    }
  }

  @Nested
  class PaginationContinuity {
    @Test
    void timestampSeekMustReconcileTheKnownPredecessorAndCannotCertifySkippedHistory() {
      Fixture f = new Fixture();
      LanguageContent first = f.entry(null, 10), second = f.entry(first.blueId(), 20);
      LanguageContent request = f.request("limit: 1");
      LanguageContent initial = f.checkpoint(first.blueId(), 11);
      TimelineVerifier.Prefix prefix =
          f.reader
              .begin(f.reader.initial(), request)
              .accept(
                  f.response(request, initial, f.page(initial, null, List.of(first), false, null)))
              .completedPrefix()
              .orElseThrow();
      LanguageContent checkpoint = f.checkpoint(second.blueId(), 21);
      LanguageContent seek = f.request("limit: 1\nafterTimestamp: 15");
      assertEquals(
          second.blueId(),
          f.reader
              .begin(prefix, seek)
              .accept(
                  f.response(
                      seek, checkpoint, f.page(checkpoint, first, List.of(second), false, null)))
              .completedPrefix()
              .orElseThrow()
              .headId()
              .orElseThrow());
      LanguageContent beyond = f.request("limit: 1\nafterTimestamp: 100");
      rejected(
          "TIMELINE_ANCHOR",
          () ->
              f.reader
                  .begin(prefix, beyond)
                  .accept(
                      f.response(
                          beyond, checkpoint, f.page(checkpoint, second, List.of(), false, null))));
      assertEquals(
          11,
          f.reader
              .begin(prefix, beyond)
              .accept(f.response(beyond, initial, f.page(initial, first, List.of(), false, null)))
              .completedPrefix()
              .orElseThrow()
              .completeBefore());
      LanguageContent tooEarly = f.request("limit: 1\nafterTimestamp: 9");
      rejected(
          "TIMELINE_SEEK",
          () ->
              f.reader
                  .begin(prefix, tooEarly)
                  .accept(
                      f.response(
                          tooEarly,
                          checkpoint,
                          f.page(checkpoint, first, List.of(second), false, null))));
    }

    @Test
    void successorEvidenceAndPagedReadsShareTheSameIssuedPrefixAuthority() {
      Fixture f = new Fixture();
      TimelineVerifier verifier = TimelineVerifier.adopt(Published.PACKAGE, f.provider, f.timeline);
      TimelineReader reader =
          TimelineReader.adopt(
              verifier, f.descriptor("https://provider.example", "2048", f.timeline));
      LanguageContent first = f.entry(null, 1), successor = f.entry(first.blueId(), 3);
      LanguageContent evidence =
          f.typed(
              "Timeline Successor Evidence",
              "entries: ["
                  + first.toJson()
                  + ","
                  + successor.toJson()
                  + "]\nsuccessor: "
                  + f.attest(successor).toJson());
      TimelineVerifier.Prefix prefix = verifier.advance(verifier.initial(), evidence).prefix();
      LanguageContent request =
          f.request("limit: 1\nafterEntry: {blueId: '" + first.blueId() + "'}");
      LanguageContent checkpoint = f.checkpoint(successor.blueId(), 4);
      TimelineReader.Read read = reader.begin(prefix, request);
      LanguageContent fork =
          f.language.prepare(
              Language.Input.exactJson(successor.toJson().replace("\"value\":1", "\"value\":2")));
      assertNotEquals(successor.blueId(), fork.blueId());
      rejected(
          "TIMELINE_EQUIVOCATION",
          () ->
              read.accept(
                  f.response(
                      request, checkpoint, f.page(checkpoint, first, List.of(fork), false, null))));
      assertEquals(
          successor.blueId(),
          read.accept(
                  f.response(
                      request,
                      checkpoint,
                      f.page(checkpoint, first, List.of(successor), false, null)))
              .completedPrefix()
              .orElseThrow()
              .headId()
              .orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 128, 1024})
    void finalPageDoesNotDependOnBodiesFromEarlierPages(int length) {
      Fixture f = new Fixture();
      List<LanguageContent> entries = new ArrayList<>();
      BlueId previous = null;
      for (int i = 0; i <= length; i++) {
        LanguageContent entry = f.entry(previous, i);
        entries.add(entry);
        previous = entry.blueId();
      }
      LanguageContent checkpoint = f.checkpoint(previous, length + 1);
      LanguageContent request = f.request("limit: 1");
      TimelineReader.Read read = f.reader.begin(f.reader.initial(), request);
      for (int i = 0; i < length; i++) {
        LanguageContent cursor = f.cursor(checkpoint, entries.get(i).blueId(), "");
        TimelineReader.Page page =
            read.accept(
                f.response(
                    request,
                    checkpoint,
                    f.page(
                        checkpoint,
                        i == 0 ? null : entries.get(i - 1),
                        List.of(entries.get(i)),
                        true,
                        cursor)));
        assertTrue(page.completedPrefix().isEmpty());
        request = f.request("limit: 1\ncursor: " + page.nextCursor().orElseThrow().toJson());
        read = page.continueWith(request);
      }
      f.records.clear();
      f.demands.clear();
      TimelineReader.Page last =
          read.accept(
              f.response(
                  request,
                  checkpoint,
                  f.page(
                      checkpoint,
                      entries.get(length - 1),
                      List.of(entries.get(length)),
                      false,
                      null)));
      assertEquals(length + 1, last.completedPrefix().orElseThrow().completeBefore());
      assertEquals(
          entries.getLast().blueId(), last.completedPrefix().orElseThrow().headId().orElseThrow());
      assertTrue(f.demands.isEmpty(), "No old or future history body may be fetched");
    }

    @Test
    void pagesAdvanceOnlyAtTheFinalHeadWithoutFetchingOldPagesOrFutureBodies() {
      Fixture f = new Fixture();
      LanguageContent first = f.entry(null, 10);
      LanguageContent second = f.entry(first.blueId(), 20);
      LanguageContent checkpoint = f.checkpoint(second.blueId(), 21);
      LanguageContent request = f.request("limit: 1");
      LanguageContent cursor = f.cursor(checkpoint, first.blueId(), "");
      TimelineReader.Page one =
          f.reader
              .begin(f.reader.initial(), request)
              .accept(
                  f.response(
                      request, checkpoint, f.page(checkpoint, null, List.of(first), true, cursor)));
      assertTrue(one.completedPrefix().isEmpty());
      assertEquals(first.blueId(), one.entries().getFirst().blueId());
      assertEquals(cursor.toJson(), one.nextCursor().orElseThrow().toJson());
      assertFalse(f.demands.contains(second.blueId()));
      f.records.clear();
      f.demands.clear();
      LanguageContent next =
          f.request("limit: 2\ncursor: " + one.nextCursor().orElseThrow().toJson());
      TimelineReader.Page two =
          one.continueWith(next)
              .accept(
                  f.response(
                      next, checkpoint, f.page(checkpoint, first, List.of(second), false, null)));
      assertEquals(21, two.completedPrefix().orElseThrow().completeBefore());
      assertEquals(second.blueId(), two.completedPrefix().orElseThrow().headId().orElseThrow());
      assertEquals(1, two.entries().size());
      assertFalse(f.demands.contains(first.blueId()));
      assertTrue(two.nextCursor().isEmpty());
      rejected("TIMELINE_NO_CONTINUATION", () -> two.continueWith(next));
    }

    @Test
    void exactContinuationCannotSwitchCheckpointOrPosition() {
      Fixture f = new Fixture();
      LanguageContent first = f.entry(null, 1), second = f.entry(first.blueId(), 2);
      LanguageContent checkpoint = f.checkpoint(second.blueId(), 3);
      LanguageContent request = f.request("limit: 1");
      LanguageContent cursor = f.cursor(checkpoint, first.blueId(), "");
      TimelineReader.Page page =
          f.reader
              .begin(f.reader.initial(), request)
              .accept(
                  f.response(
                      request, checkpoint, f.page(checkpoint, null, List.of(first), true, cursor)));
      rejected(
          "TIMELINE_CURSOR",
          () ->
              page.continueWith(
                  f.request("limit: 1\nafterEntry: {blueId: '" + first.blueId() + "'}")));
      LanguageContent next = f.request("limit: 1\ncursor: " + cursor.toJson());
      LanguageContent later = f.checkpoint(second.blueId(), 4);
      rejected(
          "TIMELINE_CHECKPOINT_CHANGED",
          () ->
              page.continueWith(next)
                  .accept(
                      f.response(next, later, f.page(later, first, List.of(second), false, null))));
    }

    @Test
    void emptyNonterminalPagesFalseHeadsAndUnannouncedCursorsFail() {
      Fixture f = new Fixture();
      LanguageContent first = f.entry(null, 1), second = f.entry(first.blueId(), 2);
      LanguageContent checkpoint = f.checkpoint(second.blueId(), 3);
      LanguageContent cursor = f.cursor(checkpoint, first.blueId(), "");
      LanguageContent request = f.request("limit: 2");
      TimelineReader.Read read = f.reader.begin(f.reader.initial(), request);
      rejected(
          "TIMELINE_NONADVANCING_PAGE",
          () ->
              read.accept(
                  f.response(
                      request, checkpoint, f.page(checkpoint, null, List.of(), true, cursor))));
      rejected(
          "TIMELINE_FINAL_HEAD",
          () ->
              read.accept(
                  f.response(
                      request, checkpoint, f.page(checkpoint, null, List.of(first), false, null))));
      rejected(
          "TIMELINE_CURSOR",
          () ->
              read.accept(
                  f.response(
                      request,
                      checkpoint,
                      f.page(checkpoint, null, List.of(first), false, cursor))));
      rejected(
          "TIMELINE_CURSOR",
          () ->
              read.accept(
                  f.response(
                      request, checkpoint, f.page(checkpoint, null, List.of(first), true, null))));
    }

    @Test
    void pageLimitsGapsAndDuplicateEntriesFailWithoutAPrefix() {
      Fixture f = new Fixture();
      LanguageContent first = f.entry(null, 1), second = f.entry(first.blueId(), 2);
      LanguageContent checkpoint = f.checkpoint(second.blueId(), 3);
      LanguageContent request = f.request("limit: 1");
      rejected(
          "TIMELINE_PAGE_LIMIT",
          () ->
              f.reader
                  .begin(f.reader.initial(), request)
                  .accept(
                      f.response(
                          request,
                          checkpoint,
                          f.page(checkpoint, null, List.of(first, second), false, null))));
      LanguageContent two = f.request("limit: 2");
      rejected(
          "TIMELINE_CHAIN",
          () ->
              f.reader
                  .begin(f.reader.initial(), two)
                  .accept(
                      f.response(
                          two,
                          checkpoint,
                          f.page(checkpoint, null, List.of(first, first), false, null))));
      LanguageContent unrelatedGenesis = f.entry(null, 2);
      rejected(
          "TIMELINE_CHAIN",
          () ->
              f.reader
                  .begin(f.reader.initial(), two)
                  .accept(
                      f.response(
                          two,
                          checkpoint,
                          f.page(
                              checkpoint, null, List.of(first, unrelatedGenesis), false, null))));
    }
  }

  @Nested
  class WireAndSelectedContent {
    @ParameterizedTest
    @ValueSource(
        strings = {
          "schema: {maxItems: 0}",
          "itemType: {type: Coordination/Timeline Entry, timestamp: {schema: {minimum: 2}}}"
        })
    void nonterminalPagesValidateListAndItemRefinementsBeforeDiscardingThePage(String refinement) {
      Fixture f = new Fixture();
      if (refinement.startsWith("itemType")) {
        LanguageDocument itemType =
            f.language.defineYaml(
                "type: Coordination/Timeline Entry\ntimestamp: {schema: {minimum: 2}}");
        f.records.put(itemType.blueId(), LanguageRecord.json(itemType.toJson()));
        refinement = "itemType: {blueId: '" + itemType.blueId() + "'}";
      }
      LanguageContent first = f.entry(null, 1), last = f.entry(first.blueId(), 2);
      LanguageContent checkpoint = f.checkpoint(last.blueId(), 3);
      LanguageContent request = f.request("limit: 1");
      LanguageContent page =
          f.typed(
              "Timeline Entries Page",
              "timeline: {blueId: '"
                  + f.timeline.blueId()
                  + "'}\ncheckpoint: {blueId: '"
                  + checkpoint.blueId()
                  + "'}\nentries: {"
                  + refinement
                  + ", items: ["
                  + first.toJson()
                  + "]}\nhasMore: true\nnextCursor: "
                  + f.cursor(checkpoint, first.blueId(), "").toJson());
      LanguageException invalid =
          assertThrows(
              LanguageException.class,
              () ->
                  f.reader
                      .begin(f.reader.initial(), request)
                      .accept(f.response(request, checkpoint, page)));
      assertFalse(invalid.incomplete(), invalid::toString);
    }

    @Test
    void aCollapsedResponseObjectCannotReplaceTheRequiredInlineWireBody() {
      Fixture f = new Fixture();
      LanguageContent request = f.request("limit: 0");
      LanguageContent response =
          f.typed(
              "Timeline Read Response",
              "request: {blueId: '"
                  + request.blueId()
                  + "'}\ncheckpoint: "
                  + f.checkpoint(null, 1).toJson());
      f.records.put(response.blueId(), LanguageRecord.json(response.toJson()));
      LanguageContent envelope =
          f.typed(
              "Provider Attestation",
              "provider: {blueId: '"
                  + f.provider.blueId()
                  + "'}\nobject: {blueId: '"
                  + response.blueId()
                  + "'}\nsignature: '"
                  + f.signObject(response)
                  + "'");
      rejected(
          "TIMELINE_WIRE_INLINE",
          () -> f.reader.begin(f.reader.initial(), request).accept(envelope));
    }

    @Test
    void unfilledOptionalDeclarationsRemainAbsentButInvalidExtraContentIsNotSkipped() {
      Fixture f = new Fixture();
      LanguageContent request = f.request("limit: 0");
      LanguageContent response =
          f.typed(
              "Timeline Read Response",
              "request: {blueId: '"
                  + request.blueId()
                  + "'}\ncheckpoint: "
                  + f.checkpoint(null, 1).toJson()
                  + "\nextra: {type: Integer, schema: {minimum: 10}, value: 1}");
      LanguageException invalid =
          assertThrows(
              LanguageException.class,
              () -> f.reader.begin(f.reader.initial(), request).accept(f.attest(response)));
      assertFalse(invalid.incomplete());
    }
  }

  private static void rejected(String code, Runnable action) {
    assertEquals(code, assertThrows(BlueException.class, action::run).code());
  }

  private static final class Fixture {
    final Map<BlueId, LanguageRecord> records = new HashMap<>();
    final List<BlueId> demands = new ArrayList<>();
    final LanguageDocument provider, timeline;
    final Language language;
    final TimelineReader reader;

    Fixture() {
      Language base = Language.newBuilder().mount("Coordination", Published.PACKAGE).build();
      provider =
          base.readYaml(
              """
              type: Coordination/Timeline Provider
              endpoint: https://provider.example
              publicKey: d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a
              """);
      timeline =
          Language.newBuilder()
              .mount("Coordination", Published.PACKAGE)
              .content(provider)
              .build()
              .readYaml(
                  "type: Coordination/Signed Timeline\nprovider: {blueId: '"
                      + provider.blueId()
                      + "'}\nurl: https://provider.example/history\npublicRead: true");
      language =
          Language.newBuilder()
              .mount("Coordination", Published.PACKAGE)
              .content(timeline)
              .provider(
                  id -> {
                    demands.add(id);
                    return Optional.ofNullable(records.get(id));
                  })
              .build();
      reader =
          TimelineReader.adopt(
              Published.PACKAGE,
              provider,
              timeline,
              descriptor("https://provider.example", "2048", timeline));
      demands.clear();
    }

    LanguageContent typed(String type, String fields) {
      return language.prepare(Language.Input.yaml("type: Coordination/" + type + "\n" + fields));
    }

    LanguageContent request(String fields) {
      return typed(
          "Read Timeline Request", "timeline: {blueId: '" + timeline.blueId() + "'}\n" + fields);
    }

    LanguageContent entry(BlueId previous, long timestamp) {
      return typed(
          "Timeline Entry",
          "timeline: {blueId: '"
              + timeline.blueId()
              + "'}\ntimestamp: "
              + timestamp
              + "\nactor: {type: Coordination/Agent Actor, service: demo}\nmessage: {amount: 1}"
              + (previous == null ? "" : "\nprevEntry: {blueId: '" + previous + "'}"));
    }

    LanguageContent checkpoint(BlueId head, long before) {
      LanguageContent guarantee =
          typed(
              "Timeline Completeness Guarantee",
              "timeline: {blueId: '"
                  + timeline.blueId()
                  + "'}\ncompleteBefore: "
                  + before
                  + (head == null ? "" : "\nlastEntry: {blueId: '" + head + "'}"));
      return typed(
          "Timeline Attestation",
          "timeline: {blueId: '"
              + timeline.blueId()
              + "'}\nguarantee: "
              + guarantee.toJson()
              + "\nsignature: '"
              + sign("blue.coordination.guarantee\n" + guarantee.blueId())
              + "'");
    }

    LanguageContent cursor(LanguageContent checkpoint, BlueId after, String extra) {
      return typed(
          "Timeline Read Cursor",
          "timeline: {blueId: '"
              + timeline.blueId()
              + "'}\ncheckpoint: "
              + checkpoint.toJson()
              + (after == null ? "" : "\nafterEntry: {blueId: '" + after + "'}")
              + "\n"
              + extra);
    }

    LanguageContent page(
        LanguageContent checkpoint,
        LanguageContent predecessor,
        List<LanguageContent> entries,
        boolean more,
        LanguageContent cursor) {
      return typed(
          "Timeline Entries Page",
          "timeline: {blueId: '"
              + timeline.blueId()
              + "'}\ncheckpoint: {blueId: '"
              + checkpoint.blueId()
              + "'}\nentries: ["
              + String.join(",", entries.stream().map(LanguageContent::toJson).toList())
              + "]\nhasMore: "
              + more
              + (predecessor == null ? "" : "\npredecessor: " + predecessor.toJson())
              + (cursor == null ? "" : "\nnextCursor: " + cursor.toJson()));
    }

    LanguageContent response(
        LanguageContent request, LanguageContent checkpoint, LanguageContent page) {
      return attest(
          typed(
              "Timeline Read Response",
              "request: {blueId: '"
                  + request.blueId()
                  + "'}\ncheckpoint: "
                  + checkpoint.toJson()
                  + (page == null ? "" : "\npage: " + page.toJson())));
    }

    LanguageContent descriptor(String endpoint, String limit, LanguageContent control) {
      List<String> operations =
          List.of(
              "Read Timeline Request",
              "Fetch Timeline Entry Request",
              "Resolve Timeline Content Request",
              "Create Timeline Access Request",
              "Update Timeline Access Request",
              "Get Timeline Access Request");
      return descriptor(endpoint, limit, control, operations);
    }

    LanguageContent descriptor(
        String endpoint, String limit, LanguageContent control, List<String> operations) {
      return attest(
          typed(
              "Provider Descriptor",
              "provider: "
                  + provider.toJson()
                  + "\nendpoint: "
                  + endpoint
                  + "\ncontrolTimeline: "
                  + control.toJson()
                  + "\nmaxPageSize: {type: Integer, value: '"
                  + limit
                  + "'}\noperations: ["
                  + String.join(
                      ",",
                      operations.stream()
                          .map(name -> "'" + Published.PACKAGE.exports().get(name) + "'")
                          .toList())
                  + "]"));
    }

    LanguageContent attest(LanguageContent content) {
      return typed(
          "Provider Attestation",
          "provider: {blueId: '"
              + provider.blueId()
              + "'}\nobject: "
              + content.toJson()
              + "\nsignature: '"
              + signObject(content)
              + "'");
    }

    String signObject(LanguageContent content) {
      return sign("blue.coordination.object\n" + provider.blueId() + "\n" + content.blueId());
    }
  }

  private static String sign(String text) {
    try {
      // Public RFC 8032 test seed; never a deployment credential.
      byte[] seed =
          HexFormat.of()
              .parseHex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60");
      PrivateKey key =
          KeyFactory.getInstance("Ed25519")
              .generatePrivate(new EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed));
      Signature signer = Signature.getInstance("Ed25519");
      signer.initSign(key);
      signer.update(text.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(signer.sign());
    } catch (GeneralSecurityException invalid) {
      throw new AssertionError(invalid);
    }
  }
}
