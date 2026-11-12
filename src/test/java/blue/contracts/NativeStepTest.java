package blue.contracts;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.Bex;
import blue.BlueException;
import blue.BlueId;
import blue.Contracts;
import blue.Language;
import blue.LanguageDocument;
import blue.NativeStep;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Native authority is explicit, metered, atomic and scoped to one invocation. */
@Tag("contracts")
class NativeStepTest {
  private static final Language LANGUAGE =
      Language.newBuilder().mount("Contracts", Contracts.publication()).build();
  private static final LanguageDocument TYPE =
      LANGUAGE.defineYaml(
          """
          type: Contracts/Contract
          expression: {}
          """);
  private static final LanguageDocument SEMANTICS =
      LANGUAGE.readYaml(
          """
          description: Test native increment semantics, edition 1.
          """);

  private static NativeStep.Builder step(NativeStep.Handler handler) {
    return NativeStep.newBuilder(TYPE, SEMANTICS, "example")
        .bodyFields("expression")
        .counter("work", 3)
        .execute(handler);
  }

  private static String source(BlueId type) {
    return """
    count: 0
    observed: 0
    audit: 0
    external: {one: 1, two: 2}
    contracts:
      input: {type: Contracts/External Channel}
      changed: {type: Contracts/Triggered Event Channel, event: {kind: Incremented}}
      onChanged:
        type: Contracts/Sequential Workflow
        channel: changed
        steps:
          - type: Contracts/Update Document
            changeset:
              - {op: replace, path: /audit, val: {$event: /amount}}
      increment:
        type: Contracts/Sequential Workflow Operation
        channel: input
        steps:
          - type: {blueId: %s}
            expression: {$add: [{$document: /count}, 1]}
          - type: Contracts/Update Document
            changeset:
              - {op: replace, path: /observed, val: {$document: /count}}
    """
        .formatted(type.value());
  }

  private static void increment(NativeStep.Context context) {
    context.charge("work", 2);
    long before = context.remainingGas();
    Object value = context.evaluate(context.body().get("expression"));
    assertTrue(context.remainingGas() < before, "BEX gas must reduce the shared allowance");
    context.replace("/count", value);
    context.emit(Map.of("kind", "Incremented", "amount", value));
  }

  @Test
  void executesAtomicallyWithBexAndRestoresTheExactBinding() {
    NativeStep nativeStep = step(NativeStepTest::increment).build();
    Contracts contracts = Contracts.newBuilder().nativeStep(nativeStep).build();
    try (Contracts.Session session = contracts.openYaml(source(TYPE.blueId()))) {
      session.initialize().requireApplied();
      Contracts.Transition update =
          session.call("input", "increment", Map.of(), 1).requireApplied();
      assertEquals(BigInteger.ONE, session.read("/count"));
      assertEquals(BigInteger.ONE, session.read("/observed"));
      assertEquals(BigInteger.ONE, session.read("/audit"));
      assertEquals(
          List.of(Map.of("kind", "Incremented", "amount", BigInteger.ONE)), update.events());
      try (Contracts.Session restored = contracts.restore(session.snapshot(), session.identity())) {
        Contracts.Transition warm =
            session.call("input", "increment", Map.of("sequence", 2), 2).requireApplied();
        Contracts.Transition cold =
            restored.call("input", "increment", Map.of("sequence", 2), 2).requireApplied();
        assertEquals(warm.gas(), cold.gas());
        assertEquals(session.identity(), restored.identity());
      }
      assertThrows(
          BlueException.class,
          () -> Contracts.create().restore(session.snapshot(), session.identity()));
      LanguageDocument different = LANGUAGE.readYaml("description: Another native edition.");
      NativeStep changed =
          NativeStep.newBuilder(TYPE, different, "example")
              .bodyFields("expression")
              .counter("work", 3)
              .execute(NativeStepTest::increment)
              .build();
      assertThrows(
          BlueException.class,
          () ->
              Contracts.newBuilder()
                  .nativeStep(changed)
                  .build()
                  .restore(session.snapshot(), session.identity()));
    }
  }

  @Test
  void rejectsExecutionWithoutInstalledCodeAndConflictingRegistration() {
    try (Contracts.Session session =
        Contracts.newBuilder()
            .language(Language.newBuilder().type("Example/Step", TYPE).build())
            .build()
            .openYaml(source(TYPE.blueId()))) {
      session.initialize().requireApplied();
      String before = session.identity();
      assertFalse(session.call("input", "increment", Map.of(), 1).applied());
      assertEquals(before, session.identity());
    }
    NativeStep first = step(context -> {}).build();
    NativeStep second = step(context -> {}).build();
    assertThrows(
        IllegalArgumentException.class,
        () -> Contracts.newBuilder().nativeStep(first).nativeStep(second));
    assertDoesNotThrow(() -> Contracts.newBuilder().nativeStep(first).nativeStep(first).build());
    assertThrows(
        IllegalArgumentException.class, () -> step(context -> {}).counter("invocation", 0));
    assertThrows(BlueException.class, () -> step(context -> {}).headerFields("expression").build());
  }

  @Test
  void handlerAndGasFailuresLeaveNoPartialEffects() {
    for (boolean exhaust : List.of(false, true)) {
      NativeStep nativeStep =
          step(context -> {
                context.replace("/count", 99);
                context.emit(Map.of("kind", "Never published"));
                if (exhaust) {
                  context.charge("work", 10_000_000);
                } else {
                  throw new IllegalStateException("Native bug");
                }
              })
              .build();
      try (Contracts.Session session =
          Contracts.newBuilder().nativeStep(nativeStep).build().openYaml(source(TYPE.blueId()))) {
        session.initialize().requireApplied();
        String before = session.identity();
        Contracts.Transition result = session.call("input", "increment", Map.of(), 1);
        assertFalse(result.applied());
        assertEquals(before, session.identity());
        assertTrue(result.events().isEmpty());
        assertTrue(result.gas() > 0);
      }
    }
  }

  @Test
  void contextCannotEscapeAndEffectsSnapshotCallerData() {
    AtomicReference<NativeStep.Context> retained = new AtomicReference<>();
    NativeStep nativeStep =
        step(context -> {
              retained.set(context);
              CompletableFuture.runAsync(
                      () -> assertThrows(IllegalStateException.class, () -> context.read("/count")))
                  .join();
              assertThrows(UnsupportedOperationException.class, () -> context.body().put("new", 1));
              List<Object> values = new ArrayList<>(List.of("original"));
              context.replace("/count", values);
              Map<String, Object> event = new LinkedHashMap<>(Map.of("values", values));
              context.emit(event);
              values.add("mutated");
              event.put("late", true);
            })
            .build();
    try (Contracts.Session session =
        Contracts.newBuilder().nativeStep(nativeStep).build().openYaml(source(TYPE.blueId()))) {
      session.initialize().requireApplied();
      Contracts.Transition result =
          session.call("input", "increment", Map.of(), 1).requireApplied();
      assertEquals(List.of("original"), session.read("/count"));
      assertEquals(List.of(Map.of("values", List.of("original"))), result.events());
    }
    assertThrows(IllegalStateException.class, () -> retained.get().read("/count"));
    assertThrows(IllegalStateException.class, () -> retained.get().replace("/count", 2));
    assertThrows(IllegalStateException.class, () -> retained.get().charge("work", 1));
  }

  @Test
  void subtypeExecutionRequiresExplicitOptIn() {
    LanguageDocument subtype = TYPE.extendYaml("description: A specialized increment.");
    Language language = Language.newBuilder().type("Example/Subtype", subtype).build();
    for (boolean inherited : List.of(false, true)) {
      NativeStep nativeStep = step(NativeStepTest::increment).inherited(inherited).build();
      try (Contracts.Session session =
          Contracts.newBuilder()
              .language(language)
              .nativeStep(nativeStep)
              .build()
              .openYaml(source(subtype.blueId()))) {
        session.initialize().requireApplied();
        Contracts.Transition result = session.call("input", "increment", Map.of(), 1);
        assertEquals(inherited, result.applied());
        assertEquals(inherited ? BigInteger.ONE : BigInteger.ZERO, session.read("/count"));
      }
    }
  }

  @Test
  void builderMutationCannotChangeAnInstalledTariff() {
    NativeStep.Builder builder = step(NativeStepTest::increment);
    NativeStep original = builder.build();
    NativeStep changed = builder.counter("work", 17).build();
    try (Contracts.Session a =
            Contracts.newBuilder().nativeStep(original).build().openYaml(source(TYPE.blueId()));
        Contracts.Session b =
            Contracts.newBuilder().nativeStep(changed).build().openYaml(source(TYPE.blueId()))) {
      a.initialize().requireApplied();
      b.initialize().requireApplied();
      long oldGas = a.call("input", "increment", Map.of(), 1).requireApplied().gas();
      long newGas = b.call("input", "increment", Map.of(), 1).requireApplied().gas();
      assertEquals(28, newGas - oldGas);
    }
  }

  @Test
  void admissionRejectsANativeTypeOutsideTheContractsHierarchy() {
    NativeStep invalid =
        NativeStep.newBuilder(LANGUAGE.defineYaml("anything: {}"), SEMANTICS, "bad")
            .execute(context -> {})
            .build();
    assertEquals(
        "INVALID_RUNTIME_CATEGORY",
        assertThrows(
                BlueException.class,
                () -> Contracts.newBuilder().nativeStep(invalid).build().openYaml("count: 0"))
            .code());
    assertEquals(
        "INVALID_RUNTIME_CATEGORY",
        assertThrows(BlueException.class, () -> Contracts.newBuilder().nativeStep(invalid).build())
            .code());
  }

  @Test
  void explicitRejectionCarriesItsReasonAndClosesTheContext() {
    AtomicReference<NativeStep.Context> retained = new AtomicReference<>();
    NativeStep nativeStep =
        step(context -> {
              retained.set(context);
              context.replace("/count", 8);
              context.reject("This account cannot increment");
            })
            .build();
    try (Contracts.Session session =
        Contracts.newBuilder().nativeStep(nativeStep).build().openYaml(source(TYPE.blueId()))) {
      session.initialize().requireApplied();
      String before = session.identity();
      Contracts.Transition result = session.call("input", "increment", Map.of(), 1);
      assertEquals(
          Map.of("code", "NATIVE_STEP_REJECTED", "reason", "This account cannot increment"),
          result.observations().get("diagnostic"));
      assertFalse(result.applied());
      assertEquals(before, session.identity());
    }
    assertThrows(IllegalStateException.class, () -> retained.get().evaluate(Map.of("$literal", 1)));
  }

  @Test
  void terminationAppliesQueuedChangesAndStopsSubsequentSteps() {
    NativeStep nativeStep =
        step(context -> {
              context.remove("/external");
              context.add("/added", "ready");
              context.replace("/count", 2);
              context.terminate("Completed", "Requested by native step");
            })
            .build();
    try (Contracts.Session session =
        Contracts.newBuilder().nativeStep(nativeStep).build().openYaml(source(TYPE.blueId()))) {
      session.initialize().requireApplied();
      session.call("input", "increment", Map.of(), 1).requireApplied();
      assertSame(Bex.undefined(), session.read("/external"));
      assertEquals("ready", session.read("/added"));
      assertEquals(BigInteger.TWO, session.read("/count"));
      assertEquals(BigInteger.ZERO, session.read("/observed"));
      assertEquals("Completed", session.read("/contracts/terminated/cause"));
      assertEquals("Requested by native step", session.read("/contracts/terminated/reason"));
    }
  }

  @Test
  void eventsCallsAndEvaluatedObjectsAreDetachedPublicJavaValues() {
    AtomicReference<Object> captured = new AtomicReference<>();
    NativeStep step =
        step(context -> {
              Map<?, ?> event = (Map<?, ?>) context.event();
              assertSame(
                  event, context.event(), "Repeated input access reuses the immutable snapshot");
              Map<?, ?> request = (Map<?, ?>) event.get("request");
              assertEquals(BigInteger.valueOf(7), request.get("amount"));
              assertEquals(List.of(BigInteger.ONE, BigInteger.TWO), request.get("quantities"));
              assertEquals(event, context.call());
              assertEquals(BigInteger.TWO, context.event("/request/quantities/1"));
              assertSame(Bex.undefined(), context.event("/request/quantities/9"));
              assertSame(Bex.undefined(), context.event("/request/missing"));
              assertEquals(event, context.event(""));
              Object selected = context.evaluate(Map.of("$document", "/external"));
              assertEquals(Map.of("one", BigInteger.ONE, "two", BigInteger.TWO), selected);
              assertEquals(selected, context.read("/external"));
              assertSame(Bex.undefined(), context.read("/absent"));
              assertThrows(
                  UnsupportedOperationException.class, () -> ((Map<?, ?>) selected).clear());
              assertThrows(
                  UnsupportedOperationException.class,
                  () -> ((List<?>) request.get("quantities")).clear());
              captured.set(selected);
            })
            .build();
    try (Contracts.Session session =
        Contracts.newBuilder().nativeStep(step).build().openYaml(source(TYPE.blueId()))) {
      session.initialize().requireApplied();
      session
          .call("input", "increment", Map.of("amount", 7, "quantities", List.of(1, 2)), 1)
          .requireApplied();
    }
    assertEquals(Map.of("one", BigInteger.ONE, "two", BigInteger.TWO), captured.get());
  }

  @Test
  void compactionPreservesNativeCodeAndTheExactSubsequentTransition() {
    Contracts runtime =
        Contracts.newBuilder().nativeStep(step(NativeStepTest::increment).build()).build();
    try (Contracts.Session session = runtime.openYaml(source(TYPE.blueId()))) {
      session.initialize().requireApplied();
      session.call("input", "increment", Map.of(), 1).requireApplied();
      String identity = session.identity();
      try (Contracts.Session reference = runtime.restore(session.snapshot(), identity)) {
        session.compact();
        assertEquals(identity, session.identity());
        Contracts.Transition compacted =
            session.call("input", "increment", Map.of("sequence", 2), 2).requireApplied();
        Contracts.Transition expected =
            reference.call("input", "increment", Map.of("sequence", 2), 2).requireApplied();
        assertEquals(expected.gas(), compacted.gas());
        assertEquals(expected.receipt(), compacted.receipt());
        assertEquals(reference.identity(), session.identity());
        assertEquals(BigInteger.TWO, session.read("/count"));
      }
    }
  }
}
