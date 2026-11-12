package blue.coordination;

import static org.junit.jupiter.api.Assertions.*;

import blue.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("coordination")
class TimelineRequestsTest {
  static final BluePackage PACKAGE =
      PackageLoader.create()
          .resource(
              TimelineRequestsTest.class.getClassLoader(),
              "coordination/coordination.bluepkg",
              ProviderSignatures.COORDINATION_PACKAGE);
  final LanguageDocument provider =
      Language.newBuilder()
          .mount("Coordination", PACKAGE)
          .build()
          .readYaml(
              """
              type: Coordination/Timeline Provider
              endpoint: https://requests.example
              publicKey: d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a
              """);
  final LanguageDocument timeline =
      Language.newBuilder()
          .mount("Coordination", PACKAGE)
          .content(provider)
          .build()
          .readYaml(
              """
              type: Coordination/Signed Timeline
              provider: {blueId: '%s'}
              url: https://requests.example/private
              publicRead: false
              """
                  .formatted(provider.blueId()));
  final Language language =
      Language.newBuilder()
          .mount("Coordination", PACKAGE)
          .content(provider)
          .content(timeline)
          .build();
  final TimelineRequests requests = TimelineRequests.forTimeline(PACKAGE, provider, timeline, 10);
  final BlueId unknown = Language.create().readYaml("unavailable: true").blueId();

  LanguageContent read(String fields) {
    return language.prepare(
        Language.Input.yaml(
            "type: Coordination/Read Timeline Request\ntimeline: {blueId: '"
                + timeline.blueId()
                + "'}\n"
                + fields));
  }

  @Nested
  class TargetAndValues {
    @Test
    void inheritedReadValuesAreParsedWithoutConfusingDeclarationsWithSelectors() {
      LanguageDocument type =
          language.defineYaml(
              "type: Coordination/Read Timeline Request\ntimeline: {blueId: '"
                  + timeline.blueId()
                  + "'}\nlimit: 3\nafterTimestamp: -1");
      LanguageDocument query = type.extendYaml("note: inherited request");
      TimelineRequests.Read parsed = requests.read(query);
      assertEquals(query.blueId(), parsed.request().blueId());
      assertEquals(3, parsed.limit());
      assertEquals(-1, parsed.afterTimestamp().orElseThrow());
      assertTrue(parsed.afterEntry().isEmpty());
      assertTrue(parsed.checkpoint().isEmpty());
      assertEquals(0, parsed.minimumCompleteBefore());
    }

    @Test
    void selectorIdentitiesAreDataPendingStoreMembershipChecks() {
      TimelineRequests.Read anchor =
          requests.read(read("limit: 2\nafterEntry: {blueId: '" + unknown + "'}"));
      assertEquals(unknown, anchor.afterEntry().orElseThrow());
      TimelineRequests.Read cursor =
          requests.read(
              read(
                  "limit: 2\n"
                      + "cursor:\n"
                      + "  type: Coordination/Timeline Read Cursor\n"
                      + "  timeline: {blueId: '"
                      + timeline.blueId()
                      + "'}\n  checkpoint: {blueId: '"
                      + unknown
                      + "'}\n  afterTimestamp: 19"));
      assertEquals(unknown, cursor.checkpoint().orElseThrow());
      assertEquals(19, cursor.afterTimestamp().orElseThrow());
    }

    @Test
    void mismatchedTargetAndUnknownCursorBodyCannotBecomeGenesis() {
      LanguageContent wrong =
          language.prepare(
              Language.Input.yaml(
                  "type: Coordination/Read Timeline Request\ntimeline: {blueId: '"
                      + unknown
                      + "'}\nlimit: 1"));
      assertEquals(
          "TIMELINE_SOURCE_MISMATCH",
          assertThrows(BlueException.class, () -> requests.read(wrong)).code());
      LanguageException failure =
          assertThrows(
              LanguageException.class,
              () -> requests.read(read("limit: 1\ncursor: {blueId: '" + unknown + "'}")));
      assertTrue(failure.incomplete());
    }
  }

  @Nested
  class Grammar {
    @ParameterizedTest
    @ValueSource(
        strings = {
          "limit: -1",
          "limit: 11",
          "limit: 0\nafterTimestamp: -1",
          "limit: 2\nafterTimestamp: -2",
          "limit: 2\nafterTimestamp: 9007199254740991",
          "limit: 1\nminimumCompleteBefore: 9007199254740992",
          "limit: 2\nnonce: x",
          "limit: 2\nrequestId: x"
        })
    void invalidBoundsAndInventedTransportFieldsAreRejected(String fields) {
      assertThrows(RuntimeException.class, () -> requests.read(read(fields)));
    }

    @Test
    void selectorsAreExclusiveAndCannotChangeAnExistingCheckpointBound() {
      assertThrows(
          BlueException.class,
          () ->
              requests.read(
                  read("limit: 2\nafterTimestamp: 0\nafterEntry: {blueId: '" + unknown + "'}")));
      String cursor =
          "cursor:\n  type: Coordination/Timeline Read Cursor\n  timeline: {blueId: '"
              + timeline.blueId()
              + "'}\n  checkpoint: {blueId: '"
              + unknown
              + "'}\n";
      assertThrows(
          BlueException.class,
          () -> requests.read(read("limit: 2\nminimumCompleteBefore: 0\n" + cursor)));
      assertThrows(
          BlueException.class,
          () ->
              requests.read(
                  read(
                      "limit: 2\n"
                          + cursor
                          + "  afterTimestamp: 0\n  afterEntry: {blueId: '"
                          + unknown
                          + "'}")));
    }
  }

  @Nested
  class ReaderAuthentication {
    @Test
    void unsignedAndSignedRequestsKeepTheExactInnerCorrelation() {
      LanguageContent query = read("limit: 2");
      TimelineRequests.Reader bare = requests.reader(query);
      assertEquals(query.blueId(), bare.request().blueId());
      assertTrue(bare.publicKey().isEmpty());
      TimelineRequests.Reader signed = requests.reader(envelope(query, sign(query)));
      assertEquals(query.blueId(), signed.request().blueId());
      assertEquals(TimelineRequests.Operation.READ, signed.operation());
      assertEquals(provider.at("/publicKey").text(), signed.publicKey().orElseThrow());
      assertEquals(signed.publicKey(), requests.reader(envelope(query, sign(query))).publicKey());
    }

    @Test
    void aSuppliedInvalidSignatureNeverFallsBackToPublicAccess() {
      LanguageDocument publicTimeline = timeline.edit(e -> e.set("/publicRead", true));
      TimelineRequests publicRequests =
          TimelineRequests.forTimeline(PACKAGE, provider, publicTimeline, 10);
      LanguageContent query =
          language.prepare(
              Language.Input.yaml(
                  "type: Coordination/Read Timeline Request\ntimeline: {blueId: '"
                      + publicTimeline.blueId()
                      + "'}\nlimit: 1"));
      assertTrue(publicRequests.reader(query).publicKey().isEmpty());
      assertThrows(
          BlueException.class, () -> publicRequests.reader(envelope(query, "00".repeat(64))));
      assertEquals(
          query.blueId(), publicRequests.reader(envelope(query, sign(query))).request().blueId());
    }

    @Test
    void changedParametersWrongSourceAndReferencedRequestBodiesAreRejected() {
      LanguageContent query = read("limit: 1"), changed = read("limit: 2");
      assertThrows(BlueException.class, () -> requests.reader(envelope(changed, sign(query))));
      LanguageContent unknownTarget =
          language.prepare(
              Language.Input.yaml(
                  "type: Coordination/Read Timeline Request\ntimeline: {blueId: '"
                      + unknown
                      + "'}\nlimit: 1"));
      assertThrows(
          BlueException.class, () -> requests.reader(envelope(unknownTarget, sign(unknownTarget))));
      LanguageContent referenced =
          language.prepare(
              Language.Input.yaml(
                  "type: Coordination/Signed Reader Request\nrequest: {blueId: '"
                      + query.blueId()
                      + "'}\npublicKey: '"
                      + provider.at("/publicKey").text()
                      + "'\nsignature: '"
                      + sign(query)
                      + "'"));
      assertEquals(
          "TIMELINE_WIRE_INLINE",
          assertThrows(BlueException.class, () -> requests.reader(referenced)).code());
    }

    @Test
    void signaturesCannotTurnOwnerMutationsIntoReadOperations() {
      LanguageContent owner =
          language.prepare(
              Language.Input.yaml("type: Coordination/Create Timeline Access Request"));
      assertEquals(
          "TIMELINE_READER_OPERATION",
          assertThrows(BlueException.class, () -> requests.reader(envelope(owner, sign(owner))))
              .code());
    }

    @Test
    void entryAndContentTargetsAreExactReferencesPendingAuthorizedMembership() {
      for (String type :
          List.of("Fetch Timeline Entry Request", "Resolve Timeline Content Request")) {
        boolean entry = type.startsWith("Fetch");
        LanguageContent query =
            language.prepare(
                Language.Input.yaml(
                    "type: Coordination/"
                        + type
                        + "\ntimeline: {blueId: '"
                        + timeline.blueId()
                        + "'}\n"
                        + (entry ? "entry" : "content")
                        + ": {blueId: '"
                        + unknown
                        + "'}"));
        TimelineRequests.Reader result = requests.reader(envelope(query, sign(query)));
        assertEquals(
            entry ? TimelineRequests.Operation.ENTRY : TimelineRequests.Operation.CONTENT,
            result.operation());
        assertEquals(
            unknown, entry ? requests.entry(result.request()) : requests.content(result.request()));
      }
      LanguageContent inline =
          language.prepare(
              Language.Input.yaml(
                  "type: Coordination/Resolve Timeline Content Request"
                      + "\ntimeline: {blueId: '"
                      + timeline.blueId()
                      + "'}\ncontent: {value: secret}"));
      assertThrows(BlueException.class, () -> requests.reader(envelope(inline, sign(inline))));
    }

    @Test
    void publicAccessDiscoveryAuthenticatesWithoutOpeningTheUnconfirmedTarget() {
      LanguageContent request =
          language.prepare(
              Language.Input.yaml(
                  "type: Coordination/Get Timeline Access Request\naccess: {blueId: '"
                      + unknown
                      + "'}"));
      TimelineRequests.Access bare = requests.access(request);
      TimelineRequests.Access signed = requests.access(envelope(request, sign(request)));
      assertEquals(unknown, bare.id());
      assertEquals(unknown, signed.id());
      assertEquals(request.blueId(), signed.request().blueId());
      assertThrows(BlueException.class, () -> requests.access(envelope(request, "00".repeat(64))));
      assertThrows(BlueException.class, () -> requests.reader(envelope(request, sign(request))));
    }

    @Test
    void publicDiscoveryCannotAcceptAnInlineTargetOrAnOwnerMutation() {
      LanguageContent inline =
          language.prepare(
              Language.Input.yaml(
                  "type: Coordination/Get Timeline Access Request\naccess: {value: fake}"));
      assertEquals(
          "TIMELINE_WIRE_REFERENCE",
          assertThrows(BlueException.class, () -> requests.access(inline)).code());
      LanguageContent owner =
          language.prepare(
              Language.Input.yaml(
                  "type: Coordination/Create Timeline Access Request\n"
                      + "idempotencyKey: x\n"
                      + "allowedKeys: []\n"
                      + "allowedTimelines: []"));
      assertThrows(BlueException.class, () -> requests.access(envelope(owner, sign(owner))));
      LanguageContent invented =
          language.prepare(
              Language.Input.yaml(
                  "type: Coordination/Get Timeline Access Request\naccess: {blueId: '"
                      + unknown
                      + "'}\nnonce: x"));
      assertThrows(BlueException.class, () -> requests.access(invented));
    }

    private LanguageContent envelope(LanguageContent request, String signature) {
      return language.prepare(
          Language.Input.yaml(
              "type: Coordination/Signed Reader Request\nrequest: "
                  + request.toJson()
                  + "\npublicKey: '"
                  + provider.at("/publicKey").text()
                  + "'\nsignature: '"
                  + signature
                  + "'"));
    }

    private String sign(LanguageContent request) {
      try {
        var key =
            java.security.KeyFactory.getInstance("Ed25519")
                .generatePrivate(
                    new java.security.spec.EdECPrivateKeySpec(
                        java.security.spec.NamedParameterSpec.ED25519,
                        HexFormat.of()
                            .parseHex(
                                "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")));
        var signer = java.security.Signature.getInstance("Ed25519");
        signer.initSign(key);
        signer.update(
            ("blue.coordination.reader-request\n" + request.blueId())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(signer.sign());
      } catch (java.security.GeneralSecurityException failure) {
        throw new AssertionError(failure);
      }
    }
  }

  @Nested
  class EntryRequests {
    @Test
    void exactEntryReferenceCanBeLookedUpWithoutOpeningItsHistory() {
      LanguageContent query =
          language.prepare(
              Language.Input.yaml(
                  "type: Coordination/Fetch Timeline Entry Request\ntimeline: {blueId: '"
                      + timeline.blueId()
                      + "'}\nentry: {blueId: '"
                      + unknown
                      + "'}"));
      assertEquals(unknown, requests.entry(query));
      assertThrows(BlueException.class, () -> requests.read(query));
      assertThrows(BlueException.class, () -> requests.entry(read("limit: 1")));
    }
  }
}
