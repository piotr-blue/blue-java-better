package blue.contracts;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.AdmissionEvidence;
import blue.Bex;
import blue.BlueException;
import blue.BlueId;
import blue.Contracts;
import blue.Language;
import blue.LanguageDocument;
import blue.NativeAdmission;
import blue.NativeChannel;
import blue.ProjectionLimits;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Admission is an invocation-bound gate, never a payload-shaped grant of authority. */
@Tag("contracts")
class NativeAdmissionTest {
  private static final Language LANGUAGE =
      Language.newBuilder().mount("Contracts", Contracts.publication()).build();
  private static final LanguageDocument TYPE = LANGUAGE.defineYaml("type: Contracts/Channel");
  private static final LanguageDocument SEMANTICS =
      LANGUAGE.readYaml("description: Test HMAC admission protocol, edition 1.");
  private static final Map<String, Object> EVENT = Map.of("kind", "Tick");
  private static final BlueId EVENT_ID = LANGUAGE.readYaml("kind: Tick").blueId();
  private static final String SOURCE =
      """
      count: 0
      contracts:
        input: {type: {blueId: %s}}
        tick:
          type: Contracts/Sequential Workflow
          channel: input
          steps:
            - type: Contracts/Update Document
              changeset: [{op: replace, path: /count, val: {$add: [{$document: /count}, 1]}}]
            - type: Contracts/Trigger Event
              event: {kind: Counted}
      """
          .formatted(TYPE.blueId());

  private static NativeChannel.Builder channel(NativeChannel.AdmissionVerifier verifier) {
    return NativeChannel.newBuilder(TYPE, SEMANTICS, "admissiontest")
        .counter("signature", 7)
        .verifyAdmission(verifier)
        .accept(context -> context.admission().get("channel").equals(context.channel()));
  }

  @Test
  void bulkEvidenceCaptureHasAnImmutableTypedTransportViewAndSharesTheRecordDomain() {
    List<Object> mutable = new ArrayList<>(List.of("original"));
    Map<BlueId, Object> supplied = new LinkedHashMap<>();
    supplied.put(TYPE.blueId(), Map.of("items", mutable));
    supplied.put(SEMANTICS.blueId(), Map.of("other", true));
    AdmissionEvidence captured = AdmissionEvidence.copyOf(supplied);
    AdmissionEvidence sequential =
        AdmissionEvidence.of(TYPE.blueId(), Map.of("items", List.of("original")))
            .with(SEMANTICS.blueId(), Map.of("other", true));
    mutable.clear();
    supplied.clear();
    assertEquals(sequential.asMap(), captured.asMap());
    assertThrows(UnsupportedOperationException.class, () -> captured.asMap().clear());
    Map<?, ?> proof = (Map<?, ?>) captured.asMap().get(TYPE.blueId());
    assertThrows(UnsupportedOperationException.class, () -> ((List<?>) proof.get("items")).clear());
    assertEquals(captured.asMap(), AdmissionEvidence.copyOf(captured.asMap()).asMap());
    assertSame(AdmissionEvidence.empty(), AdmissionEvidence.copyOf(Map.of()));
    assertThrows(BlueException.class, () -> AdmissionEvidence.copyOf(Map.of(TYPE.blueId(), 1.25)));
    Map<BlueId, Object> invalid = new LinkedHashMap<>();
    invalid.put(TYPE.blueId(), null);
    assertThrows(NullPointerException.class, () -> AdmissionEvidence.copyOf(invalid));
  }

  @Test
  void nativeProofSnapshotsRetainSortedFieldsNormalizedIntegersAndNestedNulls() {
    var source = new LinkedHashMap<String, Object>();
    var nested = new ArrayList<Object>();
    nested.add(9L);
    nested.add(null);
    source.put("z", nested);
    source.put("a", 3);
    var result = (Map<?, ?>) AdmissionEvidence.of(TYPE.blueId(), source).asMap().get(TYPE.blueId());
    source.clear();
    nested.clear();
    assertEquals(List.of("a", "z"), List.copyOf(result.keySet()));
    assertEquals(java.math.BigInteger.valueOf(3), result.get("a"));
    assertEquals(java.util.Arrays.asList(java.math.BigInteger.valueOf(9), null), result.get("z"));
    assertThrows(UnsupportedOperationException.class, result::clear);
    assertThrows(UnsupportedOperationException.class, ((List<?>) result.get("z"))::clear);
  }

  private static Map<String, Object> verify(NativeAdmission context) {
    assertEquals(TYPE.blueId(), context.channelTypeId());
    assertEquals(SEMANTICS.blueId(), context.semanticsId());
    NativeAdmission.Receiver receiver = context.receivers().getFirst();
    assertNotNull(receiver.documentId());
    assertNotNull(receiver.declarationId());
    assertEquals("input", receiver.channel());
    String text =
        permitText(
            context.snapshotIdentity(),
            context.inputId().value(),
            context.eventId().value(),
            context.timestamp(),
            receiver.generation(),
            receiver.channel());
    context.charge("signature", 1);
    Map<?, ?> evidence = (Map<?, ?>) context.evidence();
    byte[] signature = Base64.getDecoder().decode((String) evidence.get("signature"));
    if (!MessageDigest.isEqual(sign(text), signature)) {
      context.reject("Invalid signed permit");
    }
    return Map.of("channel", receiver.channel());
  }

  private static String permitText(
      String snapshot,
      String input,
      String event,
      long timestamp,
      long generation,
      String channel) {
    return String.join(
        "\n",
        "native-admission-test/1",
        snapshot,
        input,
        event,
        Long.toString(timestamp),
        Long.toString(generation),
        channel);
  }

  private static byte[] sign(String text) {
    // Fixed test-only authority; no external state, clock or I/O enters the verifier.
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(
          new SecretKeySpec(
              "unit-test-key-not-a-production-key".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException failure) {
      throw new AssertionError(failure);
    }
  }

  private static AdmissionEvidence permit(String snapshot, String input, long timestamp) {
    return AdmissionEvidence.of(
        TYPE.blueId(),
        Map.of(
            "signature",
            Base64.getEncoder()
                .encodeToString(
                    sign(permitText(snapshot, input, EVENT_ID.value(), timestamp, 1, "input")))));
  }

  @Test
  void signedPermitBindsSnapshotPayloadInputTimestampAndGeneration() {
    NativeChannel registration = channel(NativeAdmissionTest::verify).build();
    Contracts runtime = Contracts.newBuilder().nativeChannel(registration).build();
    try (Contracts.Session session = runtime.openYaml(SOURCE)) {
      session.initialize().requireApplied();
      String before = session.identity();
      AdmissionEvidence proof = permit(before, EVENT_ID.value(), 7);
      for (String field :
          List.of("snapshot", "input", "event", "timestamp", "generation", "channel")) {
        String text =
            permitText(
                field.equals("snapshot") ? "wrong" : before,
                field.equals("input") ? "wrong" : EVENT_ID.value(),
                field.equals("event") ? "wrong" : EVENT_ID.value(),
                field.equals("timestamp") ? 8 : 7,
                field.equals("generation") ? 2 : 1,
                field.equals("channel") ? "another" : "input");
        AdmissionEvidence wrong =
            AdmissionEvidence.of(
                TYPE.blueId(), Map.of("signature", Base64.getEncoder().encodeToString(sign(text))));
        Contracts.Transition failed = session.process(EVENT, 7, wrong);
        assertFalse(failed.applied(), field);
        assertTrue(failed.events().isEmpty());
        assertEquals(before, session.identity());
      }
      assertFalse(session.process(Map.of("kind", "Changed"), 7, proof).applied());
      assertFalse(session.process(EVENT, 8, proof).applied());
      assertEquals(before, session.identity());
      Contracts.Transition applied = session.process(EVENT, 7, proof).requireApplied();
      assertEquals(List.of(Map.of("kind", "Counted")), applied.events());
      assertEquals(BigInteger.ONE, session.read("/count"));
      String after = session.identity();
      assertFalse(session.process(EVENT, 7, proof).applied());
      assertEquals(after, session.identity());
      // A newly authenticated duplicate still goes through ordinary channel deduplication.
      session.process(EVENT, 7, permit(after, EVENT_ID.value(), 7));
      assertEquals(BigInteger.ONE, session.read("/count"));
    }
  }

  @Test
  void privateCanonicalCandidatesVerifyFreshEvidenceAndRetainNativeRegistration() {
    NativeChannel registration = channel(NativeAdmissionTest::verify).build();
    Contracts runtime = Contracts.newBuilder().nativeChannel(registration).build();
    try (Contracts.Session direct = runtime.openYaml(SOURCE)) {
      direct.initialize().requireApplied();
      try (Contracts.Session candidate = runtime.restore(direct.snapshot(), direct.identity())) {
        AdmissionEvidence proof = permit(direct.identity(), EVENT_ID.value(), 7);
        Contracts.Transition expected = direct.process(EVENT, 7, proof).requireApplied();
        try (Contracts.Transaction transaction = candidate.begin(EVENT, 7, proof)) {
          Contracts.Transition actual = transaction.finish().requireApplied();
          assertEquals(expected.receipt(), actual.receipt());
          assertEquals(expected.gas(), actual.gas());
          assertEquals(direct.snapshot(), transaction.snapshot());
          assertEquals(direct.blueId(), transaction.blueId());
          transaction.commit();
        }
        String committed = candidate.identity();
        try (Contracts.Transaction replay = candidate.begin(EVENT, 7, proof)) {
          assertFalse(replay.finish().applied());
          replay.commit();
        }
        assertEquals(committed, candidate.identity());
        assertEquals(BigInteger.ONE, candidate.read("/count"));
      }
    }
  }

  @Test
  void evidenceIsRequiredExplicitlyAndUnknownTypesCannotBeIgnored() {
    AtomicInteger calls = new AtomicInteger();
    NativeChannel registration =
        channel(
                context -> {
                  calls.incrementAndGet();
                  return Map.of("channel", "input");
                })
            .build();
    try (Contracts.Session session =
        Contracts.newBuilder().nativeChannel(registration).build().openYaml(SOURCE)) {
      session.initialize().requireApplied();
      String before = session.identity();
      assertFalse(session.process(EVENT, 1).applied());
      assertEquals(0, calls.get());
      assertFalse(
          session.process(EVENT, 1, AdmissionEvidence.of(SEMANTICS.blueId(), Map.of())).applied());
      assertEquals(0, calls.get());
      assertEquals(before, session.identity());
      session.process(EVENT, 1, AdmissionEvidence.of(TYPE.blueId(), Map.of())).requireApplied();
      assertEquals(1, calls.get());
    }
  }

  @Test
  void evidenceAndVerifiedProjectionAreIndependentImmutableSnapshots() {
    Map<String, Object> supplied = new LinkedHashMap<>(Map.of("allowed", true));
    AdmissionEvidence first = AdmissionEvidence.of(TYPE.blueId(), supplied);
    AdmissionEvidence second = first.with(TYPE.blueId(), Map.of("allowed", false));
    supplied.put("allowed", false);
    Map<String, Object> returned = new LinkedHashMap<>(Map.of("channel", "input"));
    NativeChannel registration =
        channel(
                context -> {
                  Map<?, ?> evidence = (Map<?, ?>) context.evidence();
                  assertThrows(UnsupportedOperationException.class, evidence::clear);
                  if (!Boolean.TRUE.equals(evidence.get("allowed"))) {
                    context.reject("Denied");
                  }
                  return returned;
                })
            .accept(
                context -> {
                  returned.put("channel", "changed after verification");
                  assertEquals(Map.of("channel", "input"), context.admission());
                  assertThrows(
                      UnsupportedOperationException.class, () -> context.admission().clear());
                  return true;
                })
            .build();
    try (Contracts.Session session =
        Contracts.newBuilder().nativeChannel(registration).build().openYaml(SOURCE)) {
      session.initialize().requireApplied();
      assertFalse(session.process(EVENT, 1, second).applied());
      session.process(EVENT, 1, first).requireApplied();
      assertEquals(BigInteger.ONE, session.read("/count"));
    }
  }

  @Test
  void verifierContextCannotEscapeAndOneVerificationCoversAllReceiversOfItsType() {
    AtomicInteger count = new AtomicInteger();
    AtomicReference<NativeAdmission> retained = new AtomicReference<>();
    NativeChannel registration =
        channel(
                context -> {
                  retained.set(context);
                  count.incrementAndGet();
                  CompletableFuture.runAsync(
                          () -> assertThrows(IllegalStateException.class, context::evidence))
                      .join();
                  assertEquals(
                      List.of("input", "second"),
                      context.receivers().stream().map(NativeAdmission.Receiver::channel).toList());
                  assertSame(context.receivers(), context.receivers());
                  assertThrows(
                      UnsupportedOperationException.class, () -> context.receivers().clear());
                  assertThrows(
                      UnsupportedOperationException.class,
                      () -> context.receivers().getFirst().header().clear());
                  assertTrue(context.receivers().getFirst().checkpoint().isEmpty());
                  return Map.of("channel", "input");
                })
            .build();
    String source =
        SOURCE.replace("  input:", "  second: {type: {blueId: " + TYPE.blueId() + "}}\n  input:");
    try (Contracts.Session session =
        Contracts.newBuilder().nativeChannel(registration).build().openYaml(source)) {
      session.initialize().requireApplied();
      session.process(EVENT, 1, AdmissionEvidence.of(TYPE.blueId(), Map.of())).requireApplied();
      assertEquals(1, count.get());
      assertEquals(BigInteger.ONE, session.read("/count"));
      assertThrows(IllegalStateException.class, retained.get()::snapshotIdentity);
      assertThrows(IllegalStateException.class, retained.get()::receivers);
      assertThrows(IllegalStateException.class, retained.get()::remainingGas);
      assertThrows(IllegalStateException.class, () -> retained.get().reject("late"));
    }
  }

  @Test
  void admissionFailureOrGasExhaustionPrecedesEveryChannelAndRollsBack() {
    for (boolean exhaust : List.of(false, true)) {
      AtomicInteger channelCalls = new AtomicInteger();
      NativeChannel registration =
          channel(
                  context -> {
                    if (exhaust) {
                      context.charge("signature", context.remainingGas() / 7 + 1);
                    }
                    throw new IllegalStateException("Broken verifier");
                  })
              .accept(
                  context -> {
                    channelCalls.incrementAndGet();
                    return true;
                  })
              .build();
      try (Contracts.Session session =
          Contracts.newBuilder().nativeChannel(registration).build().openYaml(SOURCE)) {
        session.initialize().requireApplied();
        String before = session.identity();
        Contracts.Transition result =
            session.process(EVENT, 1, AdmissionEvidence.of(TYPE.blueId(), Map.of()));
        assertFalse(result.applied());
        assertEquals(exhaust ? "GAS_LIMIT_EXCEEDED" : "FAILED", result.status());
        assertTrue(result.events().isEmpty());
        assertEquals(before, session.identity());
        assertEquals(0, channelCalls.get());
      }
    }
  }

  @Test
  void restoreAndCompactionKeepVerifierAndExactReplayOutcome() {
    NativeChannel registration = channel(NativeAdmissionTest::verify).build();
    Contracts runtime = Contracts.newBuilder().nativeChannel(registration).build();
    try (Contracts.Session warm = runtime.openYaml(SOURCE)) {
      warm.initialize().requireApplied();
      String identity = warm.identity();
      try (Contracts.Session cold = runtime.restore(warm.snapshot(), identity)) {
        warm.compact();
        AdmissionEvidence proof = permit(identity, EVENT_ID.value(), 7);
        Contracts.Transition left = warm.process(EVENT, 7, proof).requireApplied();
        Contracts.Transition right = cold.process(EVENT, 7, proof).requireApplied();
        assertEquals(left.gas(), right.gas());
        assertEquals(left.receipt(), right.receipt());
        assertEquals(warm.identity(), cold.identity());
      }
      NativeChannel unverified =
          NativeChannel.newBuilder(TYPE, SEMANTICS, "admissiontest")
              .counter("signature", 7)
              .accept(context -> true)
              .build();
      assertThrows(
          BlueException.class,
          () ->
              Contracts.newBuilder()
                  .nativeChannel(unverified)
                  .build()
                  .restore(warm.snapshot(), warm.identity()));
    }
  }

  @Test
  void boundedEvidenceRejectsOversizedAndCyclicDataBeforeProcessing() {
    List<Object> cycle = new ArrayList<>();
    cycle.add(cycle);
    assertThrows(BlueException.class, () -> AdmissionEvidence.of(TYPE.blueId(), cycle));
    String large = "x".repeat((int) ProjectionLimits.defaults().characters() + 1);
    assertEquals(
        "PROJECTION_LIMIT_EXCEEDED",
        assertThrows(BlueException.class, () -> AdmissionEvidence.of(TYPE.blueId(), large)).code());
    assertThrows(NullPointerException.class, () -> AdmissionEvidence.of(TYPE.blueId(), null));
    for (Object invalid : List.of(Map.of(1, "non-text key"), new Object(), 0.5, Bex.undefined())) {
      assertThrows(BlueException.class, () -> AdmissionEvidence.of(TYPE.blueId(), invalid));
    }
  }

  @Test
  void localPayloadOverloadUsesOrdinaryEventIdentityAndChecksThePreviousCheckpoint() {
    AtomicReference<NativeAdmission.Receiver> seen = new AtomicReference<>();
    NativeChannel registration =
        channel(
                context -> {
                  seen.set(context.receivers().getFirst());
                  assertEquals(context.eventId(), context.inputId());
                  return verify(context);
                })
            .build();
    try (Contracts.Session session =
        Contracts.newBuilder().nativeChannel(registration).build().openYaml(SOURCE)) {
      session.initialize().requireApplied();
      String before = (String) session.snapshot().get("identity");
      session.process(EVENT, 7, permit(before, EVENT_ID.value(), 7)).requireApplied();
      assertTrue(seen.get().checkpoint().isEmpty());
      String after = (String) session.snapshot().get("identity");
      session.process(EVENT, 7, permit(after, EVENT_ID.value(), 7));
      assertEquals(EVENT_ID, seen.get().checkpoint().orElseThrow().eventId());
      assertEquals(7, seen.get().checkpoint().orElseThrow().timestamp());
      assertEquals(BigInteger.ONE, session.read("/count"));
    }
  }

  @Test
  void oversizedVerifierOutputCannotPublishAnyState() {
    NativeChannel registration =
        channel(
                context ->
                    Map.of(
                        "channel",
                        "input",
                        "large",
                        "x".repeat((int) ProjectionLimits.defaults().characters() + 1)))
            .build();
    try (Contracts.Session session =
        Contracts.newBuilder().nativeChannel(registration).build().openYaml(SOURCE)) {
      session.initialize().requireApplied();
      String before = session.identity();
      assertEquals(
          "PROJECTION_LIMIT_EXCEEDED",
          assertThrows(
                  BlueException.class,
                  () -> session.process(EVENT, 1, AdmissionEvidence.of(TYPE.blueId(), Map.of())))
              .code());
      assertEquals(before, session.identity());
      assertEquals(BigInteger.ZERO, session.read("/count"));
    }
  }

  @Test
  void admissionChargesOncePerTypeAndBuildersRetainTheirVerifier() {
    NativeChannel.Builder builder = channel(context -> Map.of("channel", "input"));
    NativeChannel first = builder.build();
    NativeChannel dearer = builder.counter("admission", 17).build();
    NativeChannel denied =
        builder
            .verifyAdmission(
                context -> {
                  context.reject("Closed protocol");
                  return Map.of();
                })
            .build();
    long[] gas = new long[2];
    int i = 0;
    for (NativeChannel registration : List.of(first, dearer, denied)) {
      try (Contracts.Session session =
          Contracts.newBuilder().nativeChannel(registration).build().openYaml(SOURCE)) {
        session.initialize().requireApplied();
        Contracts.Transition result =
            session.process(EVENT, 1, AdmissionEvidence.of(TYPE.blueId(), Map.of()));
        if (registration == denied) {
          assertFalse(result.applied());
        } else {
          gas[i++] = result.requireApplied().gas();
        }
      }
    }
    assertEquals(16, gas[1] - gas[0]);
    assertThrows(IllegalArgumentException.class, () -> builder.counter("admission", 0));
    NativeChannel.Builder ordinary =
        NativeChannel.newBuilder(TYPE, SEMANTICS, "ordinary")
            .counter("admission", 0)
            .accept(context -> true);
    assertDoesNotThrow(ordinary::build); // Existing non-verifying channels retain their counters.
    assertThrows(
        IllegalArgumentException.class, () -> ordinary.verifyAdmission(context -> Map.of()));
  }

  @Test
  void receiptsCommitVerifiedClaimsRatherThanUnusedProofTransportFields() {
    NativeChannel registration =
        channel(
                context ->
                    Map.of(
                        "channel", "input", "claim", ((Map<?, ?>) context.evidence()).get("claim")))
            .build();
    Contracts runtime = Contracts.newBuilder().nativeChannel(registration).build();
    List<String> receipts = new ArrayList<>();
    for (Map<String, Object> evidence :
        List.of(
            Map.<String, Object>of("claim", "one", "transport", "a"),
            Map.<String, Object>of("claim", "one", "transport", "b"),
            Map.<String, Object>of("claim", "two", "transport", "a"))) {
      try (Contracts.Session session = runtime.openYaml(SOURCE)) {
        session.initialize().requireApplied();
        receipts.add(
            session
                .process(EVENT, 1, AdmissionEvidence.of(TYPE.blueId(), evidence))
                .requireApplied()
                .receipt());
        assertEquals(BigInteger.ONE, session.read("/count"));
      }
    }
    assertEquals(receipts.get(0), receipts.get(1));
    assertNotEquals(receipts.get(0), receipts.get(2));
  }

  @Test
  void concurrentSessionsDoNotShareVerifiedAuthorityOrMutableVerificationState() {
    Contracts runtime =
        Contracts.newBuilder().nativeChannel(channel(NativeAdmissionTest::verify).build()).build();
    List<CompletableFuture<BigInteger>> results = new ArrayList<>();
    for (int index = 1; index <= 8; index++) {
      int timestamp = index;
      results.add(
          CompletableFuture.supplyAsync(
              () -> {
                try (Contracts.Session session = runtime.openYaml(SOURCE)) {
                  session.initialize().requireApplied();
                  AdmissionEvidence proof = permit(session.identity(), EVENT_ID.value(), timestamp);
                  session.process(EVENT, timestamp, proof).requireApplied();
                  assertFalse(session.process(EVENT, timestamp + 1, proof).applied());
                  return (BigInteger) session.read("/count");
                }
              }));
    }
    results.forEach(result -> assertEquals(BigInteger.ONE, result.join()));
  }
}
