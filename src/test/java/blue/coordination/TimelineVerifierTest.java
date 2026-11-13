package blue.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.AdmissionEvidence;
import blue.BlueException;
import blue.BlueId;
import blue.BluePackage;
import blue.Contracts;
import blue.Language;
import blue.LanguageContent;
import blue.LanguageDocument;
import blue.LanguageException;
import blue.LanguageRecord;
import blue.NativeChannel;
import blue.Node;
import blue.PackageLoader;
import blue.ProviderSignatures;
import blue.TimelinePublisher;
import blue.TimelineVerifier;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.NamedParameterSpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("coordination")
class TimelineVerifierTest {
  private static final String KEY =
      "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a";

  private static final class Published {
    static final BluePackage PACKAGE =
        PackageLoader.create()
            .resource(
                TimelineVerifierTest.class.getClassLoader(),
                "coordination/coordination.bluepkg",
                ProviderSignatures.COORDINATION_PACKAGE);
  }

  @Nested
  class PrefixAdvancement {
    @Test
    void explicitGuaranteesAdvanceAndRepeatWithoutReopeningEstablishedEntries() {
      Fixture f = new Fixture();
      LanguageContent first = f.entry(null, 10, "{value: hello}");
      TimelineVerifier.Result one =
          f.verify(f.verifier.initial(), 11, first.blueId(), null, List.of(first));
      assertEquals(
          List.of(first.blueId()),
          one.entries().stream().map(TimelineVerifier.Entry::blueId).toList());
      assertFalse(one.entries().getFirst().mayInvokeOperation());
      assertEquals(
          f.language.prepare(Language.Input.yaml("{value: hello}")).blueId(),
          one.entries().getFirst().messageId());
      f.records.clear();
      f.demands.clear();
      TimelineVerifier.Result repeated =
          f.verify(one.prefix(), 11, first.blueId(), first.blueId(), List.of());
      assertEquals(first.blueId(), repeated.prefix().headId().orElseThrow());
      assertTrue(repeated.entries().isEmpty());
      assertFalse(f.demands.contains(first.blueId()));
      LanguageContent second = f.entry(first.blueId(), 20, "{amount: 7}");
      TimelineVerifier.Result two =
          f.verify(repeated.prefix(), 21, second.blueId(), first.blueId(), List.of(second));
      assertEquals(21, two.prefix().completeBefore());
      assertEquals(second.blueId(), two.prefix().headId().orElseThrow());
      assertEquals(11, one.prefix().completeBefore());
      assertFalse(f.demands.contains(first.blueId()));
    }

    @Test
    void headlessGuaranteeIsPositiveEvidenceAndCannotEraseAnEstablishedHead() {
      Fixture f = new Fixture();
      TimelineVerifier.Prefix empty =
          f.verify(f.verifier.initial(), 20, null, null, List.of()).prefix();
      assertTrue(empty.headId().isEmpty());
      LanguageContent first = f.entry(null, 20, "{}");
      TimelineVerifier.Prefix one =
          f.verify(empty, 21, first.blueId(), null, List.of(first)).prefix();
      rejected("TIMELINE_FINAL_HEAD", () -> f.verify(one, 22, null, first.blueId(), List.of()));
      assertEquals(21, one.completeBefore());
    }

    @Test
    void successorAndGuaranteeShareOneExclusivePrefixAndRetainTheKnownNextEntry() {
      Fixture f = new Fixture();
      LanguageContent first = f.entry(null, 10, "{value: first}");
      LanguageContent second = f.entry(first.blueId(), 20, "{value: second}");
      TimelineVerifier.Result result =
          f.successor(f.verifier.initial(), null, List.of(first, second), second);
      assertEquals(20, result.prefix().completeBefore());
      assertEquals(first.blueId(), result.prefix().headId().orElseThrow());
      assertEquals(second.blueId(), result.prefix().successorId().orElseThrow());
      assertEquals(1, result.entries().size());
      TimelineVerifier.Prefix repeated =
          f.verify(result.prefix(), 20, first.blueId(), first.blueId(), List.of()).prefix();
      assertEquals(second.blueId(), repeated.successorId().orElseThrow());
      rejected(
          "TIMELINE_EQUIVOCATION",
          () -> f.verify(repeated, 21, first.blueId(), first.blueId(), List.of()));
      LanguageContent fork = f.entry(first.blueId(), 20, "{value: fork}");
      rejected(
          "TIMELINE_EQUIVOCATION",
          () -> f.verify(repeated, 21, fork.blueId(), first.blueId(), List.of(fork)));
      TimelineVerifier.Result closed =
          f.verify(repeated, 21, second.blueId(), first.blueId(), List.of(second));
      assertEquals(
          List.of(second.blueId()),
          closed.entries().stream().map(TimelineVerifier.Entry::blueId).toList());
      assertTrue(closed.prefix().successorId().isEmpty());
    }

    @Test
    void genesisSuccessorClosesOnlyTheEmptyEarlierPrefix() {
      Fixture f = new Fixture();
      LanguageContent genesis = f.entry(null, 0, "{}");
      TimelineVerifier.Result result =
          f.successor(f.verifier.initial(), null, List.of(genesis), genesis);
      assertEquals(0, result.prefix().completeBefore());
      assertTrue(result.prefix().headId().isEmpty());
      assertEquals(genesis.blueId(), result.prefix().successorId().orElseThrow());
      assertTrue(result.entries().isEmpty());
    }

    @Test
    void maximumTimestampCanBeClosedByTheFinalExclusiveBound() {
      Fixture f = new Fixture();
      LanguageContent last = f.entry(null, 9_007_199_254_740_990L, "{}");
      assertEquals(
          9_007_199_254_740_991L,
          f.verify(f.verifier.initial(), 9_007_199_254_740_991L, last.blueId(), null, List.of(last))
              .prefix()
              .completeBefore());
      LanguageContent invalid = f.entry(null, 9_007_199_254_740_991L, "{}");
      assertThrows(
          BlueException.class,
          () ->
              f.verify(
                  f.verifier.initial(),
                  9_007_199_254_740_991L,
                  invalid.blueId(),
                  null,
                  List.of(invalid)));
    }
  }

  @Nested
  class IntegrityBoundaries {
    @Test
    @Tag("concurrency")
    void independentAdvancementsShareNoMutableVerificationContext() throws Exception {
      Fixture f = new Fixture();
      LanguageContent first = f.entry(null, 1, "{}");
      TimelineVerifier.Prefix prefix =
          f.verify(f.verifier.initial(), 2, first.blueId(), null, List.of(first)).prefix();
      LanguageContent second = f.entry(first.blueId(), 2, "{}");
      LanguageContent range = f.range(3, second.blueId(), first.blueId(), List.of(second.blueId()));
      LanguageContent evidence =
          Language.newBuilder().content(range).content(second).build().reference(range.blueId());
      List<java.util.concurrent.Callable<BlueId>> work = new ArrayList<>();
      for (int i = 0; i < 32; i++) {
        work.add(() -> f.verifier.advance(prefix, evidence).prefix().headId().orElseThrow());
      }
      try (var pool = java.util.concurrent.Executors.newFixedThreadPool(4)) {
        for (var result : pool.invokeAll(work)) {
          assertEquals(second.blueId(), result.get());
        }
      }
      assertEquals(first.blueId(), prefix.headId().orElseThrow());
      assertEquals(2, prefix.completeBefore());
    }

    @Test
    void changedEnvelopeProviderAndUnrelatedSignedObjectsAreRejected() {
      Fixture f = new Fixture();
      LanguageContent first = f.entry(null, 1, "{}");
      f.keep(first);
      LanguageDocument foreign =
          f.provider.edit(e -> e.set("/endpoint/value", "https://foreign.example"));
      Language source =
          Language.newBuilder()
              .mount("Coordination", Published.PACKAGE)
              .content(f.timeline)
              .content(foreign)
              .content(first)
              .build();
      LanguageContent evidence =
          source.prepare(
              Language.Input.yaml(
                  """
                  type: Coordination/Timeline Successor Evidence
                  entries: [{blueId: '%s'}]
                  successor:
                    type: Coordination/Provider Attestation
                    provider: {blueId: '%s'}
                    object: {blueId: '%s'}
                    signature: '%s'
                  """
                      .formatted(
                          first.blueId(),
                          foreign.blueId(),
                          first.blueId(),
                          sign(
                              "blue.coordination.object\n"
                                  + f.provider.blueId()
                                  + "\n"
                                  + first.blueId()))));
      rejected(
          "TIMELINE_PROVIDER_MISMATCH", () -> f.verifier.advance(f.verifier.initial(), evidence));
      LanguageContent unrelated =
          f.language.prepare(Language.Input.yaml("{value: 'not an entry'}"));
      rejected(
          "TIMELINE_TYPE",
          () -> f.successor(f.verifier.initial(), null, List.of(unrelated), unrelated));
    }

    @Test
    void anAuthenticSuccessorMustBeTheExactLastProvidedEntry() {
      Fixture f = new Fixture();
      var first = f.entry(null, 1, "{amount: 20}");
      var second = f.entry(first.blueId(), 3, "{amount: 50}");
      f.keep(second);
      var prefix = f.verifier.initial();
      rejected("TIMELINE_FINAL_HEAD", () -> f.successor(prefix, null, List.of(first), second));
      var corrected = f.successor(prefix, null, List.of(first, second), second);
      assertEquals(
          List.of(first.blueId()),
          corrected.entries().stream().map(TimelineVerifier.Entry::blueId).toList());
    }

    @Test
    void aSuccessorCannotBorrowAGuaranteeSignatureForTheExactSameObject() {
      Fixture f = new Fixture();
      var first = f.entry(null, 1, "{amount: 20}");
      f.keep(first);
      String wire =
          """
          type: Coordination/Timeline Successor Evidence
          entries: [{blueId: '%s'}]
          successor:
            type: Coordination/Provider Attestation
            provider: {blueId: '%s'}
            object: {blueId: '%s'}
            signature: '%s'
          """
              .formatted(
                  first.blueId(),
                  f.provider.blueId(),
                  first.blueId(),
                  sign("blue.coordination.guarantee\n" + first.blueId()));
      var evidence = f.language.prepare(Language.Input.yaml(wire));
      var prefix = f.verifier.initial();
      rejected("INVALID_PROVIDER_SIGNATURE", () -> f.verifier.advance(prefix, evidence));
      assertTrue(prefix.headId().isEmpty());
      assertEquals(1L, f.successor(prefix, null, List.of(first), first).prefix().completeBefore());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aValidSignatureCannotMixTwoTimelinesInTheSameAttestation(boolean wrongEnvelope) {
      Fixture f = new Fixture();
      var other =
          f.timeline.edit(e -> e.set("/url/value", Node.text("https://provider.example/second")));
      var scope = f.language.toBuilder().content(other).build();
      var guarantee =
          scope.prepare(
              Language.Input.yaml(
                  """
                  type: Coordination/Timeline Completeness Guarantee
                  timeline: {blueId: '%s'}
                  completeBefore: 2
                  """
                      .formatted((wrongEnvelope ? f.timeline : other).blueId())));
      var range =
          scope.prepare(
              Language.Input.yaml(
                  """
                  type: Coordination/Timeline Range Evidence
                  entries: []
                  checkpoint:
                    type: Coordination/Timeline Attestation
                    timeline: {blueId: '%s'}
                    guarantee: %s
                    signature: '%s'
                  """
                      .formatted(
                          (wrongEnvelope ? other : f.timeline).blueId(),
                          guarantee.toJson(),
                          sign("blue.coordination.guarantee\n" + guarantee.blueId()))));
      var prefix = f.verifier.initial();
      rejected("TIMELINE_SOURCE_MISMATCH", () -> f.verifier.advance(prefix, range));
      assertEquals(2L, f.verify(prefix, 2, null, null, List.of()).prefix().completeBefore());
    }

    @Test
    void scopedPrefixFactsCannotLeakIntoOrdinaryLanguageAdmission() {
      Fixture f = new Fixture();
      LanguageContent first = f.entry(null, 1, "{}");
      TimelineVerifier.Prefix prefix =
          f.verify(f.verifier.initial(), 2, first.blueId(), null, List.of(first)).prefix();
      f.records.clear();
      LanguageContent second = f.entry(first.blueId(), 2, "{}");
      f.verify(prefix, 3, second.blueId(), first.blueId(), List.of(second));
      LanguageException missing =
          assertThrows(
              LanguageException.class, () -> f.language.admit(second, Language.Goal.VALUE));
      assertTrue(missing.incomplete());
      assertEquals("ProviderUnavailable", missing.category());
    }

    @Test
    void rejectsWrongAnchorGapNonIncreasingTimeAndEntriesOutsideTheBound() {
      Fixture f = new Fixture();
      LanguageContent first = f.entry(null, 10, "{}");
      TimelineVerifier.Prefix previous =
          f.verify(f.verifier.initial(), 15, first.blueId(), null, List.of(first)).prefix();
      rejected("TIMELINE_ANCHOR", () -> f.verify(previous, 15, first.blueId(), null, List.of()));
      rejected(
          "TIMELINE_BOUND",
          () -> f.verify(previous, 14, first.blueId(), first.blueId(), List.of()));
      for (long time : new long[] {9, 10, 14}) {
        LanguageContent second = f.entry(first.blueId(), time, "{}");
        rejected(
            "TIMELINE_TIME",
            () -> f.verify(previous, 20, second.blueId(), first.blueId(), List.of(second)));
      }
      LanguageContent atBound = f.entry(first.blueId(), 20, "{}");
      rejected(
          "TIMELINE_BOUND",
          () -> f.verify(previous, 20, atBound.blueId(), first.blueId(), List.of(atBound)));
      LanguageContent genesis = f.entry(null, 16, "{}");
      rejected(
          "TIMELINE_CHAIN",
          () -> f.verify(previous, 20, genesis.blueId(), first.blueId(), List.of(genesis)));
      rejected(
          "TIMELINE_FINAL_HEAD",
          () -> f.verify(previous, 21, first.blueId(), first.blueId(), List.of(atBound)));
    }

    @Test
    void missingRequiredContentIsNotAnEmptyPrefixAndIncorrectBodiesFailIdentityVerification() {
      Fixture f = new Fixture();
      LanguageContent first = f.entry(null, 1, "{}");
      LanguageContent evidence = f.range(2, first.blueId(), null, List.of(first.blueId()));
      LanguageException missing =
          assertThrows(
              LanguageException.class, () -> f.verifier.advance(f.verifier.initial(), evidence));
      assertEquals("ProviderUnavailable", missing.category());
      f.records.put(
          first.blueId(), LanguageRecord.json(f.entry(null, 1, "{value: changed}").toJson()));
      assertThrows(
          LanguageException.class, () -> f.verifier.advance(f.verifier.initial(), evidence));
    }

    @Test
    void prefixCannotBeTransferredEvenToAnotherAdopterOfTheSameTimeline() {
      Fixture f = new Fixture();
      TimelineVerifier other = TimelineVerifier.adopt(Published.PACKAGE, f.provider, f.timeline);
      rejected(
          "TIMELINE_PREFIX_OWNER",
          () -> other.advance(f.verifier.initial(), f.range(0, null, null, List.of())));
    }

    @Test
    void rejectsChangedSignatureAndWrongAttestationOrEntrySources() {
      Fixture f = new Fixture();
      LanguageContent first = f.entry(null, 1, "{}");
      f.keep(first);
      LanguageContent range = f.range(2, first.blueId(), null, List.of(first.blueId()));
      String wire = range.toJson();
      Node signature = f.language.lookupExact(range, "/checkpoint/signature/value");
      String changed = "0".repeat(128);
      rejected(
          "INVALID_PROVIDER_SIGNATURE",
          () ->
              f.verifier.advance(
                  f.verifier.initial(),
                  f.language.prepare(
                      Language.Input.exactJson(wire.replace(signature.text(), changed)))));
      LanguageDocument other =
          f.timeline.edit(e -> e.set("/url/value", Node.text("https://provider.example/other")));
      Language alternate =
          Language.newBuilder().mount("Coordination", Published.PACKAGE).content(other).build();
      LanguageContent wrongEntry =
          alternate.prepare(
              Language.Input.yaml(
                  "type: Coordination/Timeline Entry\ntimeline: {blueId: '%s'}\nactor: {type: Coordination/Agent Actor, service: x}\ntimestamp: 1\nmessage: {}"
                      .formatted(other.blueId())));
      rejected(
          "TIMELINE_SOURCE_MISMATCH",
          () -> f.verify(f.verifier.initial(), 2, wrongEntry.blueId(), null, List.of(wrongEntry)));
    }
  }

  @Nested
  class OriginalContent {
    @Test
    void repeatedRequestGrammarNeverSubstitutesForEntryOrMarkerValidation() {
      Fixture f = new Fixture();
      LanguageContent request =
          f.language.prepare(
              Language.Input.yaml(
                  "type: Contracts/Operation Request\nchannel: external\noperation: pay"));
      f.keep(request);
      LanguageContent first = f.entryWith(request, request, "");
      LanguageContent second = f.operationEntry(first.blueId(), 2, request, request);
      assertEquals(
          2,
          f.verify(f.verifier.initial(), 3, second.blueId(), null, List.of(first, second))
              .entries()
              .size());
      LanguageContent missing = f.operationEntry(first.blueId(), 2, request, null);
      rejected(
          "TIMELINE_OPERATION",
          () -> f.verify(f.verifier.initial(), 3, missing.blueId(), null, List.of(first, missing)));
      LanguageContent duplicateTime = f.operationEntry(first.blueId(), 1, request, request);
      rejected(
          "TIMELINE_TIME",
          () ->
              f.verify(
                  f.verifier.initial(),
                  3,
                  duplicateTime.blueId(),
                  null,
                  List.of(first, duplicateTime)));
    }

    @Test
    void validRequestsDoNotHideDistinctMalformedRequestsOrPublishAFailedRange() {
      Fixture f = new Fixture();
      LanguageContent good =
          f.language.prepare(
              Language.Input.yaml(
                  "type: Contracts/Operation Request\nchannel: external\noperation: pay"));
      LanguageContent bad =
          f.language.prepare(
              Language.Input.yaml(
                  "type: Contracts/Operation Request\nchannel: external\noperation: ''"));
      f.keep(good);
      f.keep(bad);
      TimelineVerifier.Prefix previous = f.verifier.initial();
      LanguageContent first = f.operationEntry(null, 1, good, good);
      LanguageContent invalid = f.operationEntry(first.blueId(), 2, bad, bad);
      rejected(
          "INVALID_OPERATION_REQUEST",
          () -> f.verify(previous, 3, invalid.blueId(), null, List.of(first, invalid)));
      assertTrue(previous.headId().isEmpty());
      LanguageContent corrected = f.operationEntry(first.blueId(), 2, good, good);
      assertEquals(
          2,
          f.verify(previous, 3, corrected.blueId(), null, List.of(first, corrected))
              .entries()
              .size());
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
          "channel: ''\noperation: pay",
          "channel: external\noperation: ''",
          "channel: external\noperation: pay\nrequireExactDocumentVersion: true"
        })
    void recognizedOperationsUseTheExistingContractsTokenAndDocumentRules(String body) {
      Fixture f = new Fixture();
      LanguageContent request =
          f.language.prepare(Language.Input.yaml("type: Contracts/Operation Request\n" + body));
      rejected(
          "INVALID_OPERATION_REQUEST",
          () -> f.verifyInline(f.verifier.initial(), 2, f.entryWith(request, request, "")));
    }

    @Test
    void actorCategoriesAndOptionalSourceAreValidated() {
      Fixture f = new Fixture();
      LanguageContent message = f.language.prepare(Language.Input.yaml("{}"));
      LanguageContent wrongSource = f.entryWith(message, null, "source: {value: invalid}");
      rejected("TIMELINE_TYPE", () -> f.verifyInline(f.verifier.initial(), 2, wrongSource));
      LanguageContent original = f.entry(null, 1, "{}");
      String principal = Published.PACKAGE.exports().get("Principal Actor").value();
      String actor = Published.PACKAGE.exports().get("Actor").value();
      LanguageContent abstractActor =
          f.language.prepare(Language.Input.exactJson(original.toJson().replace(principal, actor)));
      rejected("TIMELINE_ACTOR", () -> f.verifyInline(f.verifier.initial(), 2, abstractActor));
      LanguageContent validSource =
          f.entryWith(message, null, "source: {type: Coordination/Source, context: {batch: 1}}");
      assertEquals(1, f.verifyInline(f.verifier.initial(), 2, validSource).entries().size());
    }

    @Test
    void unvaluedMessageIsSuppliedWhileThePublishedPlaceholderIsNot() {
      Fixture f = new Fixture();
      LanguageContent definition = f.entry(null, 1, "{type: Text}");
      TimelineVerifier.Result result =
          f.verify(f.verifier.initial(), 2, definition.blueId(), null, List.of(definition));
      assertEquals(
          f.language.prepare(Language.Input.yaml("{type: Text}")).blueId(),
          result.entries().getFirst().messageId());
      LanguageContent absent = f.entry(null, 1, null);
      rejected(
          "TIMELINE_FIELD_REQUIRED",
          () -> f.verify(f.verifier.initial(), 2, absent.blueId(), null, List.of(absent)));
    }

    @Test
    void inheritedDomainMessageKeepsItsIdentityAndContextualConstraintsStillApply() {
      Fixture f = new Fixture();
      LanguageDocument entryType =
          f.language.defineYaml(
              """
              type: Coordination/Timeline Entry
              message: {type: Text}
              timestamp: {schema: {minimum: 5}}
              """);
      Language inherited =
          Language.newBuilder()
              .mount("Coordination", Published.PACKAGE)
              .content(f.timeline)
              .content(entryType)
              .build();
      LanguageContent entry =
          inherited.prepare(
              Language.Input.yaml(
                  """
                  type: {blueId: '%s'}
                  timeline: {blueId: '%s'}
                  actor: {type: Coordination/Agent Actor, service: x}
                  timestamp: 5
                  """
                      .formatted(entryType.blueId(), f.timeline.blueId())));
      TimelineVerifier.Result result = f.verifyInline(f.verifier.initial(), 6, entry);
      assertEquals(
          f.language.prepare(Language.Input.yaml("{type: Text}")).blueId(),
          result.entries().getFirst().messageId());
      LanguageContent invalid =
          inherited.prepare(Language.Input.yaml(entry.toYaml().replace("value: 5", "value: 4")));
      assertThrows(LanguageException.class, () -> f.verifyInline(f.verifier.initial(), 6, invalid));
    }

    @Test
    void operationMustBeTheSameCompleteRecognizedRequestAndUseContractsValidation() {
      Fixture f = new Fixture();
      LanguageContent request =
          f.language.prepare(
              Language.Input.yaml(
                  "type: Contracts/Operation Request\nchannel: external\noperation: pay"));
      LanguageContent valid = f.entryWith(request, request, "");
      assertEquals(
          request.blueId(),
          f.verifyInline(f.verifier.initial(), 2, valid).entries().getFirst().messageId());
      rejected(
          "TIMELINE_OPERATION",
          () -> f.verifyInline(f.verifier.initial(), 2, f.entryWith(request, null, "")));
      LanguageContent malformed =
          f.language.prepare(
              Language.Input.yaml(
                  "type: Contracts/Operation Request\n"
                      + "channel: external\n"
                      + "operation: pay\n"
                      + "extra: 1"));
      rejected(
          "INVALID_OPERATION_REQUEST",
          () -> f.verifyInline(f.verifier.initial(), 2, f.entryWith(malformed, malformed, "")));
      LanguageContent ordinary = f.language.prepare(Language.Input.yaml("{operation: pay}"));
      rejected(
          "TIMELINE_OPERATION",
          () -> f.verifyInline(f.verifier.initial(), 2, f.entryWith(ordinary, request, "")));
    }

    @Test
    void certifiedPredecessorDoesNotDischargeAnAdditionalContextualConstraint() {
      Fixture f = new Fixture();
      LanguageContent first = f.entry(null, 1, "{}");
      TimelineVerifier.Prefix previous =
          f.verify(f.verifier.initial(), 2, first.blueId(), null, List.of(first)).prefix();
      LanguageDocument refinement =
          f.language.defineYaml(
              "type: Coordination/Timeline Entry\nprevEntry: {timestamp: {schema: {minimum: 10}}}");
      Language context =
          Language.newBuilder()
              .mount("Coordination", Published.PACKAGE)
              .content(f.timeline)
              .content(refinement)
              .content(first)
              .build();
      LanguageContent next =
          context.prepare(
              Language.Input.yaml(
                  """
                  type: {blueId: '%s'}
                  prevEntry: {blueId: '%s'}
                  timeline: {blueId: '%s'}
                  timestamp: 3
                  actor: {type: Coordination/Agent Actor, service: x}
                  message: {}
                  """
                      .formatted(refinement.blueId(), first.blueId(), f.timeline.blueId())));
      LanguageException invalid =
          assertThrows(LanguageException.class, () -> f.verifyInline(previous, 4, next));
      LanguageException canonical =
          assertThrows(LanguageException.class, () -> context.admit(next, Language.Goal.VALUE));
      assertEquals("SchemaWeakening", invalid.category());
      assertEquals(canonical.category(), invalid.category());
      assertFalse(invalid.incomplete());
    }
  }

  @Nested
  class DocumentExecution {
    @ParameterizedTest
    @ValueSource(ints = {2, 128})
    void verifiedOriginalEntriesPreserveCanonicalSnapshotRetention(int length) {
      Fixture f = new Fixture();
      List<LanguageContent> history = new ArrayList<>();
      BlueId previous = null;
      for (int i = 1; i <= length; i++) {
        LanguageContent entry = f.entry(previous, i, "{amount: 7}");
        history.add(entry);
        previous = entry.blueId();
      }
      TimelineVerifier.Entry original =
          f.verify(f.verifier.initial(), length + 1, previous, null, history).entries().getLast();
      LanguageDocument type = f.language.defineYaml("type: Contracts/Channel");
      LanguageDocument semantics = Language.create().readYaml("description: Test-only admission.");
      NativeChannel channel =
          NativeChannel.newBuilder(type, semantics, "testverifiedentry")
              .verifyAdmission(
                  context -> {
                    if (!Boolean.TRUE.equals(context.evidence())) {
                      context.reject("Test permit missing");
                    }
                    assertEquals(original.blueId(), context.eventId());
                    assertEquals(original.timestamp(), context.timestamp());
                    return Map.of();
                  })
              .accept(context -> true)
              .build();
      Contracts runtime =
          Contracts.newBuilder().language(f.language).nativeChannel(channel).build();
      String source =
          """
          total: 0
          contracts:
            input: {type: {blueId: %s}}
            add:
              type: Contracts/Sequential Workflow
              channel: input
              steps:
                - type: Contracts/Update Document
                  changeset: [{op: replace, path: /total, val: {$event: /message/amount}}]
          """
              .formatted(type.blueId());
      AdmissionEvidence permit = AdmissionEvidence.of(type.blueId(), true);
      Map<String, Object> initial, expected;
      long gas;
      try (Contracts.Session baseline = runtime.openYaml(source)) {
        baseline.initialize().requireApplied();
        initial = baseline.snapshot();
        Contracts.Transition result =
            baseline.process(
                Map.of("blueId", original.blueId().value()), original.timestamp(), permit);
        result.requireApplied();
        expected = baseline.snapshot();
        gas = result.gas();
      }
      BlueId predecessor = history.get(length - 2).blueId();
      Map<BlueId, LanguageRecord> current = history.getLast().records(predecessor::equals);
      Map<BlueId, LanguageRecord> retainedHistory = new HashMap<>(f.records);
      f.records.clear();
      f.records.putAll(current);
      f.demands.clear();
      try (Contracts.Session fresh = runtime.restore(initial, (String) initial.get("identity"))) {
        Contracts.Transition denied = fresh.process(original, AdmissionEvidence.empty());
        assertFalse(denied.admissionVerified());
        assertFalse(denied.applied());
        assertEquals(initial, fresh.snapshot());
        Contracts.Transition missing = fresh.process(original, permit);
        assertEquals("NEEDS_RESOURCES", missing.status());
        assertEquals(initial, fresh.snapshot());
        f.records.putAll(retainedHistory);
        Contracts.Transition result = fresh.process(original, permit);
        result.requireApplied();
        assertTrue(result.admissionVerified());
        assertEquals(gas, result.gas());
        assertEquals(expected, fresh.snapshot());
        assertEquals(java.math.BigInteger.valueOf(7), fresh.read("/total"));
      }
      // Portable canonical snapshots require their retained closure. Durable owner execution
      // has a separate protected-record boundary tested in ContractExecutionTest.
      f.records.clear();
      f.records.putAll(current);
      // Opaque validation reuse is invocation-local, not an admission grant for ordinary Language.
      assertThrows(
          LanguageException.class,
          () -> f.language.admit(f.language.reference(original.blueId()), Language.Goal.VALUE));
    }
  }

  @Nested
  class ProtectedRecovery {
    @Test
    void recoveryNeedsOnlyTheLastEntryAndDoesNotChangeOrdinaryAdmission() {
      Fixture f = new Fixture();
      TimelineVerifier.Prefix prefix = f.verifier.initial();
      LanguageContent last = null;
      for (int i = 1; i <= 32; i++) {
        last = f.entry(prefix.headId().orElse(null), i, "{}");
        prefix =
            f.verify(prefix, i + 1, last.blueId(), prefix.headId().orElse(null), List.of(last))
                .prefix();
      }
      f.records.clear();
      f.keep(last);
      List<BlueId> read = new ArrayList<>();
      TimelineVerifier restarted =
          TimelineVerifier.adopt(Published.PACKAGE, f.provider, f.timeline);
      TimelineVerifier.Prefix recovered =
          restarted.restore(
              prefix.checkpoint(),
              prefix.checkpointIdentity(),
              id -> {
                read.add(id);
                return Optional.ofNullable(f.records.get(id));
              });
      assertEquals(List.of(last.blueId()), read);
      assertEquals(prefix.checkpoint(), recovered.checkpoint());
      assertEquals(last.blueId(), recovered.headEntry().orElseThrow().blueId());
      LanguageContent next = f.entry(last.blueId(), 33, "{}");
      f.keep(next);
      TimelineVerifier.Result result =
          restarted.advance(
              recovered, f.range(34, next.blueId(), last.blueId(), List.of(next.blueId())));
      assertEquals(next.blueId(), result.prefix().headId().orElseThrow());
      assertThrows(LanguageException.class, () -> f.language.admit(next, Language.Goal.VALUE));
    }

    @Test
    void recoveryPreservesEmptyBoundsAndUnclosedSuccessors() {
      Fixture f = new Fixture();
      LanguageContent future = f.entry(null, 5, "{}");
      TimelineVerifier.Prefix prefix =
          f.successor(f.verifier.initial(), null, List.of(future), future).prefix();
      TimelineVerifier restarted =
          TimelineVerifier.adopt(Published.PACKAGE, f.provider, f.timeline);
      TimelineVerifier.Prefix recovered =
          restarted.restore(
              prefix.checkpoint(),
              prefix.checkpointIdentity(),
              id -> Optional.ofNullable(f.records.get(id)));
      assertTrue(recovered.headEntry().isEmpty());
      assertEquals(5, recovered.completeBefore());
      assertEquals(future.blueId(), recovered.successorId().orElseThrow());
      assertEquals(
          1,
          restarted
              .advance(recovered, f.range(6, future.blueId(), null, List.of(future.blueId())))
              .entries()
              .size());
    }

    @Test
    void protectedMigrationRequiresClosedHeadsAndExactSuccessorAnchors() {
      Fixture f = new Fixture();
      var first = f.entry(null, 1, "{}");
      var next = f.entry(first.blueId(), 2, "{}");
      var fork = f.entry(null, 2, "{}");
      List.of(first, next, fork).forEach(f::keep);
      blue.LanguageProvider retained = id -> Optional.ofNullable(f.records.get(id));

      var recovered = f.verifier.restoreTrusted(2, first.blueId(), next.blueId(), retained);
      assertEquals(first.blueId(), recovered.headId().orElseThrow());
      assertEquals(next.blueId(), recovered.successorId().orElseThrow());
      rejected(
          "TIMELINE_CHECKPOINT_BOUND",
          () -> f.verifier.restoreTrusted(1, first.blueId(), null, retained));
      rejected(
          "TIMELINE_CHECKPOINT_BOUND",
          () -> f.verifier.restoreTrusted(3, first.blueId(), next.blueId(), retained));
      rejected(
          "TIMELINE_CHECKPOINT_BOUND",
          () -> f.verifier.restoreTrusted(2, first.blueId(), fork.blueId(), retained));
      rejected(
          "TIMELINE_CHECKPOINT_BOUND",
          () -> f.verifier.restoreTrusted(2, null, next.blueId(), retained));

      LanguageDocument different =
          f.timeline.edit(editor -> editor.set("/url/value", "https://provider.example/different"));
      var wrong = TimelineVerifier.adopt(Published.PACKAGE, f.provider, different);
      rejected(
          "TIMELINE_SOURCE_MISMATCH",
          () -> wrong.restoreTrusted(2, first.blueId(), null, retained));
    }

    @Test
    void finalTimeBoundCanCloseAPrefixButCannotOccurAsAnEntryTimestamp() {
      Fixture f = new Fixture();
      long end = 9_007_199_254_740_991L;
      blue.LanguageProvider retained = id -> Optional.ofNullable(f.records.get(id));
      assertEquals(0, f.verifier.restoreTrusted(0, null, null, retained).completeBefore());
      assertEquals(end, f.verifier.restoreTrusted(end, null, null, retained).completeBefore());
      rejected(
          "TIMELINE_CHECKPOINT_BOUND", () -> f.verifier.restoreTrusted(-1, null, null, retained));
      rejected(
          "TIMELINE_CHECKPOINT_BOUND",
          () -> f.verifier.restoreTrusted(end + 1, null, null, retained));
      var last = f.entry(null, end - 1, "{}");
      var outside = f.entry(null, end, "{}");
      var negative = f.entry(null, -1, "{}");
      List.of(last, outside, negative).forEach(f::keep);
      assertEquals(
          last.blueId(),
          f.verifier.restoreTrusted(end, last.blueId(), null, retained).headId().orElseThrow());
      rejected(
          "TIMELINE_TIME", () -> f.verifier.restoreTrusted(end, outside.blueId(), null, retained));
      rejected(
          "TIMELINE_TIME", () -> f.verifier.restoreTrusted(0, negative.blueId(), null, retained));
    }

    @Test
    void changedMetadataWrongCommitmentsAndSourcesAreRejected() {
      Fixture f = new Fixture();
      TimelineVerifier.Prefix prefix =
          f.verify(f.verifier.initial(), 7, null, null, List.of()).prefix();
      rejected(
          "UNTRUSTED_TIMELINE_CHECKPOINT",
          () -> f.verifier.restore(prefix.checkpoint(), "wrong", id -> Optional.empty()));
      Map<String, Object> changed = new HashMap<>(prefix.checkpoint());
      changed.put("before", 8);
      rejected(
          "UNTRUSTED_TIMELINE_CHECKPOINT",
          () -> f.verifier.restore(changed, prefix.checkpointIdentity(), id -> Optional.empty()));
      LanguageDocument other =
          f.timeline.edit(e -> e.set("/url/value", "https://provider.example/other"));
      TimelineVerifier wrong = TimelineVerifier.adopt(Published.PACKAGE, f.provider, other);
      rejected(
          "TIMELINE_CHECKPOINT_SOURCE",
          () ->
              wrong.restore(
                  prefix.checkpoint(), prefix.checkpointIdentity(), id -> Optional.empty()));
      TimelineVerifier.Prefix recovered =
          f.verifier.restore(
              prefix.checkpoint(), prefix.checkpointIdentity(), id -> Optional.empty());
      assertEquals(7, recovered.completeBefore());
    }
  }

  @Nested
  class OperationTargets {
    private LanguageContent request(Fixture f, LanguageContent target, int value) {
      return f.language.prepare(
          Language.Input.yaml(
              "type: Contracts/Operation Request\n"
                  + "document: {blueId: '"
                  + target.blueId()
                  + "'}\n"
                  + "channel: input\noperation: add\nrequest: {value: "
                  + value
                  + "}"));
    }

    @Test
    void protectedRoutingInspectionDoesNotReadUnrelatedTargetBodiesOrGrantAdmission() {
      Fixture f = new Fixture();
      var payload = f.language.readYaml("large: {unrelated: [1, 2, 3]}");
      f.keep(payload);
      var target = f.language.readYaml("payload: {blueId: '" + payload.blueId() + "'}");
      f.keep(target);
      var request = request(f, target, 1);
      f.keep(request);
      var entry = f.operationEntry(null, 1, request, request);
      f.verify(f.verifier.initial(), 2, entry.blueId(), null, List.of(entry));
      f.records.remove(payload.blueId());
      f.demands.clear();
      var cold = TimelineVerifier.adopt(Published.PACKAGE, f.provider, f.timeline);
      assertTrue(cold.hasOperationMarker(f.language.reference(entry.blueId())));
      assertEquals(
          Optional.of(target.blueId()),
          cold.protectedOperationTarget(f.language.reference(entry.blueId())));
      assertFalse(f.demands.contains(payload.blueId()));
      assertThrows(
          LanguageException.class,
          () -> f.language.admit(f.language.reference(request.blueId()), Language.Goal.VALUE));
      assertFalse(cold.hasOperationMarker(f.entry(null, 3, "{event: ordinary}")));
    }

    @Test
    void repeatedTargetsReuseSchemaButNotSourceAuthorityOrOrdinaryAdmission() {
      Fixture f = new Fixture();
      LanguageDocument target = f.language.readYaml("balance: 0\nsettings: {enabled: true}");
      f.keep(target);
      LanguageContent firstRequest = request(f, target, 1);
      f.keep(firstRequest);
      LanguageContent first = f.operationEntry(null, 1, firstRequest, firstRequest);
      TimelineVerifier.Prefix prefix =
          f.verify(f.verifier.initial(), 2, first.blueId(), null, List.of(first)).prefix();
      f.records.clear();
      f.demands.clear();
      LanguageContent nextRequest = request(f, target, 2);
      f.keep(nextRequest);
      LanguageContent next = f.operationEntry(first.blueId(), 2, nextRequest, nextRequest);
      f.keep(next);
      LanguageContent evidence = f.range(3, next.blueId(), first.blueId(), List.of(next.blueId()));
      assertEquals(
          next.blueId(), f.verifier.advance(prefix, evidence).prefix().headId().orElseThrow());
      assertFalse(f.demands.contains(target.blueId()));
      String signature = evidence.at("/checkpoint/signature").text();
      LanguageContent forged =
          f.language.prepare(
              Language.Input.exactJson(evidence.toJson().replace(signature, "0".repeat(128))));
      assertThrows(BlueException.class, () -> f.verifier.advance(prefix, forged));
      assertThrows(
          LanguageException.class, () -> f.language.admit(nextRequest, Language.Goal.VALUE));
      TimelineVerifier cold = TimelineVerifier.adopt(Published.PACKAGE, f.provider, f.timeline);
      LanguageContent standalone = f.operationEntry(null, 3, nextRequest, nextRequest);
      f.keep(standalone);
      assertThrows(
          LanguageException.class,
          () ->
              cold.advance(
                  cold.initial(),
                  f.range(4, standalone.blueId(), null, List.of(standalone.blueId()))));
    }

    @Test
    void publisherAdmittedValueReusesOnlyTheExactTargetWithoutGrantingSourceAuthority() {
      Fixture f = new Fixture();
      LanguageDocument target = f.language.readYaml("balance: 0\nsettings: {enabled: true}");
      f.keep(target);
      LanguageContent request = request(f, target, 1);
      f.keep(request);
      TimelinePublisher publisher =
          TimelinePublisher.forTimeline(Published.PACKAGE, f.provider, f.timeline);
      assertTrue(publisher.retainValue(target));
      publisher.shareValidationWith(f.verifier);
      f.records.remove(target.blueId());
      f.demands.clear();
      LanguageContent entry = f.operationEntry(null, 1, request, request);
      f.keep(entry);
      LanguageContent evidence = f.range(2, entry.blueId(), null, List.of(entry.blueId()));
      assertEquals(
          entry.blueId(),
          f.verifier.advance(f.verifier.initial(), evidence).prefix().headId().orElseThrow());
      assertFalse(f.demands.contains(target.blueId()), "Admitted target is not reopened");
      String signature = evidence.at("/checkpoint/signature").text();
      assertThrows(
          BlueException.class,
          () ->
              f.verifier.advance(
                  f.verifier.initial(),
                  f.language.prepare(
                      Language.Input.exactJson(
                          evidence.toJson().replace(signature, "0".repeat(128))))));
      assertThrows(LanguageException.class, () -> f.language.admit(request, Language.Goal.VALUE));
    }

    @Test
    void publisherSchemaReuseStillRequiresSignaturesAndTheExactOperationMarker() {
      Fixture f = new Fixture();
      LanguageDocument target = f.language.readYaml("balance: 0");
      f.keep(target);
      LanguageContent request = request(f, target, 1);
      f.keep(request);
      TimelinePublisher publisher =
          TimelinePublisher.forTimeline(Published.PACKAGE, f.provider, f.timeline);
      assertTrue(publisher.validateMessage(request));
      publisher.shareValidationWith(f.verifier);
      LanguageContent entry = f.operationEntry(null, 1, request, request);
      f.keep(entry);
      LanguageContent evidence = f.range(2, entry.blueId(), null, List.of(entry.blueId()));
      String signature = evidence.at("/checkpoint/signature").text();
      LanguageContent forged =
          f.language.prepare(
              Language.Input.exactJson(evidence.toJson().replace(signature, "0".repeat(128))));
      assertThrows(BlueException.class, () -> f.verifier.advance(f.verifier.initial(), forged));
      LanguageContent other = request(f, target, 2);
      f.keep(other);
      LanguageContent wrong = f.operationEntry(null, 1, request, other);
      assertThrows(
          BlueException.class,
          () -> f.verify(f.verifier.initial(), 2, wrong.blueId(), null, List.of(wrong)));
      assertEquals(
          entry.blueId(),
          f.verifier.advance(f.verifier.initial(), evidence).prefix().headId().orElseThrow());
    }

    @Test
    void operationInspectionReusesOnlyTheExactOccurrencesTemporaryValueFacts() {
      Fixture f = new Fixture();
      LanguageDocument target = f.language.readYaml("balance: 0\nsettings: {enabled: true}");
      f.keep(target);
      LanguageContent request = request(f, target, 1);
      f.keep(request);
      LanguageContent event = f.operationEntry(null, 1, request, request);
      TimelineVerifier.Entry verified =
          f.verify(f.verifier.initial(), 2, event.blueId(), null, List.of(event))
              .entries()
              .getFirst();
      f.records.clear();
      assertEquals(target.blueId(), verified.operationTarget().orElseThrow());
      assertTrue(verified.mayInvokeOperation());
      f.records.putAll(request.records(target.blueId()::equals));
      f.demands.clear();
      Contracts runtime = Contracts.newBuilder().language(f.language).build();
      LanguageDocument initial = f.language.readYaml("ready: true\ncontracts: {}");
      try (var execution =
          Contracts.Execution.using(runtime, null, key -> java.util.Optional.empty())) {
        var state =
            execution.initialize(execution.prepare("root", initial)).requireApplied().after();
        assertEquals(
            target.blueId(),
            state.operationRequest(verified, request).orElseThrow().document().orElseThrow());
        assertFalse(f.demands.contains(target.blueId()));
        assertThrows(BlueException.class, () -> state.operationRequest(request));
        assertThrows(
            IllegalArgumentException.class,
            () -> state.operationRequest(verified, request(f, target, 2)));
      }
      Contracts.Execution.State borrowed;
      try (var execution =
          Contracts.Execution.using(runtime, null, key -> java.util.Optional.empty())) {
        borrowed =
            execution.initialize(execution.prepare("root", initial)).requireApplied().after();
        execution.withTimelineEntry(verified).withTimelineEntry(verified);
        assertEquals(
            target.blueId(),
            borrowed.operationRequest(request).orElseThrow().document().orElseThrow());
      }
      assertThrows(
          BlueException.class,
          () -> borrowed.operationRequest(request),
          "Closing an attempt revokes borrowed values and every derived semantic memo");
      f.demands.clear();
      try (Contracts.Session session = runtime.openYaml("ready: true")) {
        session.initialize().requireApplied();
        assertEquals(
            target.blueId(),
            session.operationRequest(verified, request).orElseThrow().document().orElseThrow());
        assertFalse(f.demands.contains(target.blueId()));
        assertThrows(BlueException.class, () -> session.operationRequest(request));
        LanguageContent changed = request(f, target, 2);
        assertThrows(
            IllegalArgumentException.class, () -> session.operationRequest(verified, changed));
      }
    }

    @Test
    void anotherTargetDoesNotBorrowThePriorTargetsValidation() {
      Fixture f = new Fixture();
      LanguageDocument target = f.language.readYaml("balance: 0");
      f.keep(target);
      LanguageContent request = request(f, target, 1);
      f.keep(request);
      LanguageContent first = f.operationEntry(null, 1, request, request);
      TimelineVerifier.Prefix prefix =
          f.verify(f.verifier.initial(), 2, first.blueId(), null, List.of(first)).prefix();
      LanguageContent invalid = f.language.prepare(Language.Input.yaml("other: 99"));
      LanguageContent changed = request(f, invalid, 2);
      f.keep(changed);
      LanguageContent next = f.operationEntry(first.blueId(), 2, changed, changed);
      f.keep(next);
      for (int attempt = 0; attempt < 2; attempt++) {
        assertThrows(
            LanguageException.class,
            () ->
                f.verifier.advance(
                    prefix, f.range(3, next.blueId(), first.blueId(), List.of(next.blueId()))));
      }
    }
  }

  @Nested
  class Locality {
    @ParameterizedTest
    @ValueSource(ints = {1, 128, 1024})
    void advancingOneEntryNeedsNoPreviouslyVerifiedHistory(int length) {
      Fixture f = new Fixture();
      List<LanguageContent> history = new ArrayList<>();
      BlueId previous = null;
      for (int i = 0; i < length; i++) {
        LanguageContent entry = f.entry(previous, i, "{}");
        previous = entry.blueId();
        history.add(entry);
      }
      TimelineVerifier.Prefix established =
          f.verify(f.verifier.initial(), length, previous, null, history).prefix();
      f.records.clear();
      f.demands.clear();
      LanguageContent next = f.entry(previous, length, "{}");
      TimelineVerifier.Result result =
          f.verify(established, length + 1, next.blueId(), previous, List.of(next));
      assertEquals(next.blueId(), result.prefix().headId().orElseThrow());
      assertEquals(List.of(next.blueId()), f.demands);
      assertEquals(1, result.entries().size());
    }
  }

  private static void rejected(String code, Runnable action) {
    assertEquals(code, assertThrows(BlueException.class, action::run).code());
  }

  private static final class Fixture {
    final Map<BlueId, LanguageRecord> records = new HashMap<>();
    final List<BlueId> demands = new ArrayList<>();
    final LanguageDocument provider;
    final LanguageDocument timeline;
    final Language language;
    final TimelineVerifier verifier;

    Fixture() {
      Language base =
          Language.newBuilder()
              .mount("Coordination", Published.PACKAGE)
              .mount("Contracts", Contracts.publication())
              .build();
      provider =
          base.readYaml(
              "type: Coordination/Timeline Provider\n"
                  + "endpoint: https://provider.example\n"
                  + "publicKey: "
                  + KEY);
      timeline =
          Language.newBuilder()
              .mount("Coordination", Published.PACKAGE)
              .content(provider)
              .build()
              .readYaml(
                  "type: Coordination/Signed Timeline\nprovider: {blueId: '%s'}\nurl: https://provider.example/history\npublicRead: true"
                      .formatted(provider.blueId()));
      language =
          Language.newBuilder()
              .mount("Coordination", Published.PACKAGE)
              .mount("Contracts", Contracts.publication())
              .content(timeline)
              .provider(
                  id -> {
                    demands.add(id);
                    return Optional.ofNullable(records.get(id));
                  })
              .build();
      verifier = TimelineVerifier.adopt(Published.PACKAGE, provider, timeline);
    }

    LanguageContent entry(BlueId previous, long timestamp, String message) {
      return language.prepare(
          Language.Input.yaml(
              """
              type: Coordination/Timeline Entry
              timeline: {blueId: '%s'}
              actor: {type: Coordination/Principal Actor, givenName: Alice}
              timestamp: %s
              %s
              %s
              """
                  .formatted(
                      timeline.blueId(),
                      timestamp,
                      message == null ? "" : "message: " + message,
                      previous == null ? "" : "prevEntry: {blueId: '" + previous + "'}")));
    }

    LanguageContent entryWith(LanguageContent message, LanguageContent operation, String extra) {
      Language source =
          Language.newBuilder()
              .mount("Coordination", Published.PACKAGE)
              .content(timeline)
              .content(message)
              .content(operation == null ? message : operation)
              .build();
      return source.prepare(
          Language.Input.yaml(
              """
              type: Coordination/Timeline Entry
              timeline: {blueId: '%s'}
              timestamp: 1
              actor: {type: Coordination/Agent Actor, service: x}
              message: {blueId: '%s'}
              %s
              %s
              """
                  .formatted(
                      timeline.blueId(),
                      message.blueId(),
                      operation == null ? "" : "operation: {blueId: '" + operation.blueId() + "'}",
                      extra)));
    }

    LanguageContent operationEntry(
        BlueId previous, long timestamp, LanguageContent message, LanguageContent operation) {
      return language.prepare(
          Language.Input.yaml(
              """
              type: Coordination/Timeline Entry
              timeline: {blueId: '%s'}
              timestamp: %s
              actor: {type: Coordination/Agent Actor, service: x}
              message: {blueId: '%s'}
              %s
              %s
              """
                  .formatted(
                      timeline.blueId(),
                      timestamp,
                      message.blueId(),
                      operation == null ? "" : "operation: {blueId: '" + operation.blueId() + "'}",
                      previous == null ? "" : "prevEntry: {blueId: '" + previous + "'}")));
    }

    void keep(LanguageContent content) {
      records.put(content.blueId(), LanguageRecord.json(content.toJson()));
    }

    TimelineVerifier.Result verify(
        TimelineVerifier.Prefix previous,
        long before,
        BlueId head,
        BlueId after,
        List<LanguageContent> entries) {
      entries.forEach(this::keep);
      return verifier.advance(
          previous,
          range(before, head, after, entries.stream().map(LanguageContent::blueId).toList()));
    }

    TimelineVerifier.Result verifyInline(
        TimelineVerifier.Prefix previous, long before, LanguageContent entry) {
      LanguageContent range =
          range(before, entry.blueId(), previous.headId().orElse(null), List.of(entry.blueId()));
      Language retained = Language.newBuilder().content(range).content(entry).build();
      return verifier.advance(previous, retained.reference(range.blueId()));
    }

    LanguageContent range(long before, BlueId head, BlueId after, List<BlueId> entries) {
      LanguageContent guarantee =
          language.prepare(
              Language.Input.yaml(
                  """
                  type: Coordination/Timeline Completeness Guarantee
                  timeline: {blueId: '%s'}
                  completeBefore: %s
                  %s
                  """
                      .formatted(
                          timeline.blueId(),
                          before,
                          head == null ? "" : "lastEntry: {blueId: '" + head + "'}")));
      return language.prepare(
          Language.Input.yaml(
              """
              type: Coordination/Timeline Range Evidence
              %s
              entries: [%s]
              checkpoint:
                type: Coordination/Timeline Attestation
                timeline: {blueId: '%s'}
                guarantee: %s
                signature: '%s'
              """
                  .formatted(
                      after == null ? "" : "after: {blueId: '" + after + "'}",
                      String.join(
                          ",", entries.stream().map(id -> "{blueId: '" + id + "'}").toList()),
                      timeline.blueId(),
                      guarantee.toJson(),
                      sign("blue.coordination.guarantee\n" + guarantee.blueId()))));
    }

    TimelineVerifier.Result successor(
        TimelineVerifier.Prefix previous,
        BlueId after,
        List<LanguageContent> entries,
        LanguageContent successor) {
      entries.forEach(this::keep);
      LanguageContent evidence =
          language.prepare(
              Language.Input.yaml(
                  """
                  type: Coordination/Timeline Successor Evidence
                  %s
                  entries: [%s]
                  successor:
                    type: Coordination/Provider Attestation
                    provider: {blueId: '%s'}
                    object: {blueId: '%s'}
                    signature: '%s'
                  """
                      .formatted(
                          after == null ? "" : "after: {blueId: '" + after + "'}",
                          String.join(
                              ",",
                              entries.stream().map(e -> "{blueId: '" + e.blueId() + "'}").toList()),
                          provider.blueId(),
                          successor.blueId(),
                          sign(
                              "blue.coordination.object\n"
                                  + provider.blueId()
                                  + "\n"
                                  + successor.blueId()))));
      return verifier.advance(previous, evidence);
    }
  }

  private static String sign(String text) {
    try {
      // Public RFC 8032 test material, never a deployment key.
      byte[] seed =
          HexFormat.of()
              .parseHex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60");
      var key =
          KeyFactory.getInstance("Ed25519")
              .generatePrivate(new EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed));
      Signature signature = Signature.getInstance("Ed25519");
      signature.initSign(key);
      signature.update(text.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(signature.sign());
    } catch (java.security.GeneralSecurityException failure) {
      throw new AssertionError(failure);
    }
  }
}
