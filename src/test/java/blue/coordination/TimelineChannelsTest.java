package blue.coordination;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.AdmissionEvidence;
import blue.BlueException;
import blue.BlueId;
import blue.BluePackage;
import blue.Contracts;
import blue.Language;
import blue.LanguageDocument;
import blue.NativeAdmission;
import blue.NativeChannel;
import blue.PackageLoader;
import blue.ProviderSignatures;
import blue.TimelineChannels;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Public adapter behavior. Real signed history integration is owned by TimelineAccessTest. */
@Tag("coordination")
class TimelineChannelsTest {
  private static final BluePackage PACKAGE =
      PackageLoader.create()
          .resource(
              TimelineChannelsTest.class.getClassLoader(),
              "coordination/coordination.bluepkg",
              ProviderSignatures.COORDINATION_PACKAGE);
  private static final Language LANGUAGE =
      Language.newBuilder()
          .mount("Coordination", PACKAGE)
          .mount("Contracts", Contracts.publication())
          .build();
  private static final String SOURCE =
      """
      count: 0
      contracts:
        first:
          type: Coordination/Public Timeline Channel
          provider:
            type: Coordination/Timeline Provider
            endpoint: https://provider.example
            publicKey: d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a
          url: https://provider.example/history
        second:
          type: Coordination/Public Timeline Channel
          provider:
            type: Coordination/Timeline Provider
            endpoint: https://provider.example
            publicKey: d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a
          url: https://provider.example/other
        countFirst:
          type: Contracts/Sequential Workflow
          channel: first
          steps:
            - type: Contracts/Update Document
              changeset: [{op: replace, path: /count, val: {$add: [{$document: /count}, 1]}}]
        countSecond:
          type: Contracts/Sequential Workflow
          channel: second
          steps:
            - type: Contracts/Update Document
              changeset: [{op: replace, path: /count, val: {$add: [{$document: /count}, 100]}}]
      """;

  @Test
  void exactProfileExecutesAcceptedChannelsAndRestoresWithTheSameBindings() {
    TimelineChannels profile =
        TimelineChannels.verifiedBy(
            PACKAGE,
            request ->
                request.receivers().stream()
                    .map(
                        row ->
                            new TimelineChannels.Assessment(
                                row,
                                row.channel().equals("first")
                                    ? TimelineChannels.Disposition.ELIGIBLE
                                    : TimelineChannels.Disposition.INAPPLICABLE,
                                true,
                                "Test decision; source authentication is separately qualified"))
                    .toList());
    Contracts runtime = Contracts.newBuilder().language(LANGUAGE).timelineChannels(profile).build();
    try (Contracts.Session session = runtime.openYaml(SOURCE)) {
      session.initialize().requireApplied();
      session.process(Map.of("testEvent", 1), 1).requireApplied();
      assertEquals(BigInteger.ONE, session.read("/count"));
      try (Contracts.Session restored = runtime.restore(session.snapshot(), session.identity())) {
        Contracts.Transition next = session.process(Map.of("testEvent", 2), 2).requireApplied();
        Contracts.Transition replay = restored.process(Map.of("testEvent", 2), 2).requireApplied();
        assertEquals(next.receipt(), replay.receipt());
        assertEquals(BigInteger.TWO, restored.read("/count"));
      }
      assertThrows(
          BlueException.class,
          () ->
              Contracts.newBuilder()
                  .language(LANGUAGE)
                  .build()
                  .restore(session.snapshot(), session.identity()));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing", "duplicate", "generation", "audience"})
  void incompleteOrInconsistentHistoryAssessmentsCannotAdvanceTheDocument(String defect) {
    TimelineChannels profile =
        TimelineChannels.verifiedBy(
            PACKAGE,
            request -> {
              List<TimelineChannels.Assessment> rows = new ArrayList<>();
              for (NativeAdmission.Receiver receiver : request.receivers()) {
                NativeAdmission.Receiver selected = receiver;
                if (defect.equals("generation")) {
                  selected =
                      new NativeAdmission.Receiver(
                          receiver.member(),
                          receiver.documentId(),
                          receiver.channel(),
                          receiver.generation() + 1,
                          receiver.declarationId(),
                          receiver.header(),
                          receiver.checkpoint());
                }
                rows.add(
                    new TimelineChannels.Assessment(
                        selected,
                        TimelineChannels.Disposition.ELIGIBLE,
                        !defect.equals("audience") || receiver.channel().equals("first"),
                        "Invalid test assessment"));
              }
              if (defect.equals("missing")) {
                rows.removeLast();
              }
              if (defect.equals("duplicate")) {
                rows.set(1, rows.getFirst());
              }
              return rows;
            });
    Contracts runtime = Contracts.newBuilder().language(LANGUAGE).timelineChannels(profile).build();
    try (Contracts.Session session = runtime.openYaml(SOURCE)) {
      session.initialize().requireApplied();
      Map<String, Object> before = session.snapshot();
      Contracts.Transition result =
          session.process(Map.of("testEvent", 1), 1, AdmissionEvidence.empty());
      assertFalse(result.admissionVerified());
      assertFalse(result.applied());
      assertEquals(before, session.snapshot());
    }
  }

  @Test
  void negativeAssessmentsChangeTheReceiptButTransportDetailsDoNot() {
    TimelineChannels profile =
        TimelineChannels.verifiedBy(
            PACKAGE,
            request -> {
              Map<?, ?> proof = (Map<?, ?>) request.evidence();
              return request.receivers().stream()
                  .map(
                      row -> {
                        boolean accepted = row.channel().equals("first");
                        return new TimelineChannels.Assessment(
                            row,
                            accepted
                                ? TimelineChannels.Disposition.ELIGIBLE
                                : TimelineChannels.Disposition.INAPPLICABLE,
                            true,
                            accepted ? "Test eligible source" : (String) proof.get("reason"));
                      })
                  .toList();
            });
    Contracts runtime = Contracts.newBuilder().language(LANGUAGE).timelineChannels(profile).build();
    List<Contracts.Transition> outcomes = new ArrayList<>();
    List<BlueId> documents = new ArrayList<>();
    for (Map<String, String> evidence :
        List.of(
            Map.of("reason", "Other source", "transport", "page one"),
            Map.of("reason", "Already consumed", "transport", "page one"),
            Map.of("reason", "Other source", "transport", "page two"))) {
      try (Contracts.Session session = runtime.openYaml(SOURCE)) {
        session.initialize().requireApplied();
        outcomes.add(
            session
                .process(
                    Map.of("testEvent", 1),
                    1,
                    AdmissionEvidence.of(PACKAGE.export("Public Timeline Channel"), evidence))
                .requireApplied());
        assertEquals(BigInteger.ONE, session.read("/count"));
        documents.add(session.document().blueId());
      }
    }
    assertEquals(documents.getFirst(), documents.get(1));
    assertEquals(documents.getFirst(), documents.get(2));
    assertEquals(outcomes.getFirst().gas(), outcomes.get(1).gas());
    assertNotEquals(outcomes.getFirst().receipt(), outcomes.get(1).receipt());
    assertEquals(outcomes.getFirst().receipt(), outcomes.get(2).receipt());
  }

  @Test
  void registrationConflictLeavesBothBuildersUnchanged() {
    LanguageDocument rules =
        Language.newBuilder()
            .content(PACKAGE.document())
            .build()
            .define(PACKAGE.protocol().orElseThrow().rules());
    NativeChannel first =
        NativeChannel.newBuilder(PACKAGE.definition("Timeline Channel"), rules, "testfirst")
            .accept(context -> true)
            .build();
    NativeChannel second =
        NativeChannel.newBuilder(PACKAGE.definition("Public Timeline Channel"), rules, "testsecond")
            .accept(context -> true)
            .build();
    TimelineChannels profile = TimelineChannels.verifiedBy(PACKAGE, request -> List.of());
    Contracts.Builder contracts = Contracts.newBuilder().nativeChannel(second);
    assertThrows(IllegalArgumentException.class, () -> contracts.timelineChannels(profile));
    assertDoesNotThrow(() -> contracts.nativeChannel(first));
    Contracts.Builder documents = Contracts.newBuilder().nativeChannel(second);
    assertThrows(IllegalArgumentException.class, () -> documents.timelineChannels(profile));
    assertDoesNotThrow(() -> documents.nativeChannel(first));
  }

  @Test
  void missingOrRejectingHistoryCannotBeReplacedByCallerSuppliedAcceptance() {
    assertThrows(NullPointerException.class, () -> TimelineChannels.verifiedBy(PACKAGE, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> TimelineChannels.verifiedBy(Contracts.publication(), request -> List.of()));
    TimelineChannels profile =
        TimelineChannels.verifiedBy(
            PACKAGE,
            request -> {
              request.reject("Required established history is missing");
              throw new AssertionError();
            });
    try (Contracts.Session session =
        Contracts.newBuilder()
            .language(LANGUAGE)
            .timelineChannels(profile)
            .build()
            .openYaml(SOURCE)) {
      session.initialize().requireApplied();
      String before = session.identity();
      Contracts.Transition result =
          session.process(
              Map.of("ready", true),
              1,
              AdmissionEvidence.of(
                  PACKAGE.export("Public Timeline Channel"), Map.of("accepted", true)));
      assertFalse(result.applied());
      assertEquals(before, session.identity());
      assertTrue(result.events().isEmpty());
    }
  }
}
