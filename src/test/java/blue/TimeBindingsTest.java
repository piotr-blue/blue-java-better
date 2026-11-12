package blue;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Adapter semantics only. Durable host ordering and authority need separate integration tests. */
class TimeBindingsTest {
  private static final BluePackage TIME =
      PackageLoader.create()
          .resource(
              TimeBindingsTest.class.getClassLoader(),
              "time/time.bluepkg",
              TimeBindings.PUBLICATION);
  private static final Language LANGUAGE =
      Language.newBuilder().mount("Contracts", Contracts.publication()).mount("Time", TIME).build();
  private static final String SOURCE =
      """
      count: 0
      observedTime: 0
      contracts:
        input: {type: Contracts/External Channel}
        timer: {type: Time/Timer Channel}
        scheduled:
          type: Contracts/Sequential Workflow Operation
          channel: input
          steps:
            - type: Time/Schedule Timer
              request: {$binding: call/request/request}
        cancelled:
          type: Contracts/Sequential Workflow Operation
          channel: input
          steps:
            - type: Time/Cancel Timer
              request: {$binding: call/request/request}
        tick:
          type: Contracts/Sequential Workflow
          channel: timer
          steps:
            - type: Contracts/Update Document
              changeset:
                - {op: replace, path: /count, val: {$add: [{$document: /count}, 1]}}
      """;

  private static Contracts runtime(TimeBindings.Verifier verifier) {
    return Contracts.newBuilder()
        .language(LANGUAGE)
        .timeBindings(TimeBindings.verifiedBy(TIME, verifier))
        .build();
  }

  @Test
  void requestsAreOrdinaryOrderedEffectsAndNeverFireTheirOwnChannel() {
    AtomicInteger verified = new AtomicInteger();
    Contracts runtime =
        runtime(
            request -> {
              verified.incrementAndGet();
              throw new AssertionError();
            });
    try (var session = runtime.openYaml(SOURCE)) {
      session.initialize().requireApplied();
      var scheduled =
          session
              .call(
                  "input",
                  "scheduled",
                  Map.of(
                      "request",
                      Map.of(
                          "channel",
                          "timer",
                          "timestamp",
                          100,
                          "key",
                          "first",
                          "context",
                          Map.of("n", 42))),
                  1)
              .requireApplied();
      assertEquals(1, scheduled.events().size());
      var event = (Map<?, ?>) scheduled.events().getFirst();
      assertEquals(Map.of("blueId", TIME.export("Timer Request").value()), event.get("type"));
      assertEquals(BigInteger.valueOf(100), event.get("timestamp"));
      assertEquals(BigInteger.ZERO, session.read("/count"));
      var cancelled =
          session
              .call(
                  "input",
                  "cancelled",
                  Map.of("request", Map.of("channel", "timer", "key", "first")),
                  2)
              .requireApplied();
      assertEquals(1, cancelled.events().size());
      assertEquals(
          Map.of("blueId", TIME.export("Cancel Timer Request").value()),
          ((Map<?, ?>) cancelled.events().getFirst()).get("type"));
      assertEquals(0, verified.get());
      try (var restored = runtime.restore(session.snapshot(), session.identity())) {
        var left =
            session
                .call(
                    "input",
                    "scheduled",
                    Map.of("request", Map.of("channel", "timer", "timestamp", 200)),
                    3)
                .requireApplied();
        var right =
            restored
                .call(
                    "input",
                    "scheduled",
                    Map.of("request", Map.of("channel", "timer", "timestamp", 200)),
                    3)
                .requireApplied();
        assertEquals(left.receipt(), right.receipt());
        assertEquals(left.gas(), right.gas());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "negative",
        "decimal",
        "boolean",
        "overflow",
        "emptyChannel",
        "emptyKey",
        "typed",
        "unknown",
        "missing"
      })
  void invalidRequestRollsBackTheWholeOperation(String defect) {
    var request = new LinkedHashMap<String, Object>(Map.of("channel", "timer", "timestamp", 100));
    switch (defect) {
      case "negative" -> request.put("timestamp", -1);
      case "decimal" -> request.put("timestamp", 1.5);
      case "boolean" -> request.put("timestamp", true);
      case "overflow" -> request.put("timestamp", 9_007_199_254_740_992L);
      case "emptyChannel" -> request.put("channel", "");
      case "emptyKey" -> request.put("key", "");
      case "typed" -> request.put("type", Map.of("blueId", TIME.export("Timer Request").value()));
      case "unknown" -> request.put("afterUs", 100);
      case "missing" -> request.remove("timestamp");
      default -> throw new AssertionError(defect);
    }
    try (var session =
        runtime(
                input -> {
                  throw new AssertionError();
                })
            .openYaml(SOURCE)) {
      session.initialize().requireApplied();
      String before = session.identity();
      var rejected = session.call("input", "scheduled", Map.of("request", request), 1);
      assertFalse(rejected.applied());
      String expected =
          switch (defect) {
            case "negative", "decimal", "boolean", "overflow" -> "TIME_INVALID_TIMESTAMP";
            case "emptyChannel", "emptyKey" -> "TIME_INVALID_NAME";
            default -> "TIME_INVALID_REQUEST_FIELDS";
          };
      assertEquals(expected, ((Map<?, ?>) rejected.observations().get("diagnostic")).get("code"));
      assertEquals(before, session.identity());
      assertEquals(BigInteger.ZERO, session.read("/count"));
    }
  }

  @Test
  void packageLoadingAloneAndForeignPublicationCannotInstallExecutableSupport() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            TimeBindings.verifiedBy(
                Contracts.publication(),
                request -> {
                  throw new AssertionError();
                }));
    var profile =
        TimeBindings.verifiedBy(
            TIME,
            request -> {
              throw new AssertionError();
            });
    assertDoesNotThrow(
        () -> Contracts.newBuilder().timeBindings(profile).timeBindings(profile).build());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            Contracts.newBuilder()
                .timeBindings(profile)
                .timeBindings(
                    TimeBindings.verifiedBy(
                        TIME,
                        request -> {
                          throw new AssertionError();
                        })));
    assertEquals(
        "INVALID_CONTRACT_ROLE",
        assertThrows(
                BlueException.class,
                () -> Contracts.newBuilder().language(LANGUAGE).build().openYaml(SOURCE))
            .code());
  }

  @Test
  void onlyInvocationBoundEvidenceSelectsTheTimerReceiverAndReplayIsIdempotent() {
    var event =
        LANGUAGE.readJson(
            ContractWire.write(
                Map.of(
                    "type", Map.of("blueId", TIME.export("Timer Fired").value()),
                    "registrationId", TIME.blueId().value(),
                    "request",
                        Map.of(
                            "type",
                            Map.of("blueId", TIME.export("Timer Request").value()),
                            "channel",
                            "timer",
                            "timestamp",
                            100))));
    var runtime =
        runtime(
            request -> {
              var proof = (Map<?, ?>) request.evidence();
              if (!request.snapshotIdentity().equals(proof.get("snapshot"))
                  || !request.eventId().value().equals(proof.get("event"))
                  || request.timestamp() != 100)
                request.reject("Fixture permit does not match the invocation");
              return new TimeBindings.Firing(request.receivers().getFirst(), TIME.blueId());
            });
    try (var session = runtime.openYaml(SOURCE)) {
      session.initialize().requireApplied();
      session.process(event, 100).requireApplied();
      assertEquals(BigInteger.ZERO, session.read("/count"));
      String before = session.identity();
      var wrong =
          AdmissionEvidence.of(
              TIME.export("Timer Channel"),
              Map.of("snapshot", "wrong", "event", event.blueId().value()));
      assertFalse(session.process(event, 100, wrong).applied());
      assertEquals(before, session.identity());
      var evidence =
          AdmissionEvidence.of(
              TIME.export("Timer Channel"),
              Map.of("snapshot", before, "event", event.blueId().value()));
      session.process(event, 100, evidence).requireApplied();
      assertEquals(BigInteger.ONE, session.read("/count"));
      var replay =
          AdmissionEvidence.of(
              TIME.export("Timer Channel"),
              Map.of("snapshot", session.identity(), "event", event.blueId().value()));
      session.process(event, 100, replay).requireApplied();
      assertEquals(BigInteger.ONE, session.read("/count"));
    }
  }
}
