package blue.contracts;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.Bex;
import blue.BlueException;
import blue.Contracts;
import blue.Language;
import blue.LanguageDocument;
import blue.NativeChannel;
import blue.NativeStep;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Native event acceptance retains ordinary workflow, checkpoint and invocation boundaries. */
@Tag("contracts")
class NativeChannelTest {
  private static final Language LANGUAGE =
      Language.newBuilder().mount("Contracts", Contracts.publication()).build();
  private static final LanguageDocument TYPE =
      LANGUAGE.defineYaml(
          """
          type: Contracts/Channel
          kind: {type: Text}
          """);
  private static final LanguageDocument SEMANTICS =
      LANGUAGE.readYaml("description: Test channel edition 1.");

  private static NativeChannel.Builder channel(NativeChannel.Handler handler) {
    return NativeChannel.newBuilder(TYPE, SEMANTICS, "channeltest")
        .headerFields("kind")
        .accept(handler);
  }

  private static boolean accepted(NativeChannel.Context context) {
    return java.util.Objects.equals(
        ((Map<?, ?>) context.event()).get("kind"), context.header().get("kind"));
  }

  private static String source() {
    return """
    count: 0
    contracts:
      input: {type: {blueId: %s}, kind: Accepted}
      onEvent:
        type: Contracts/Sequential Workflow
        channel: input
        steps:
          - type: Contracts/Update Document
            changeset: [{op: replace, path: /count, val: {$add: [{$document: /count}, 1]}}]
          - type: Contracts/Trigger Event
            event: {kind: Counted}
    """
        .formatted(TYPE.blueId().value());
  }

  @Test
  void acceptedEventsUseOrdinaryWorkflowsCheckpointsAndExactRestoreBindings() {
    AtomicReference<NativeChannel.Checkpoint> seen = new AtomicReference<>();
    NativeChannel registration =
        channel(
                context -> {
                  context.checkpoint().ifPresent(seen::set);
                  assertEquals("input", context.channel());
                  assertEquals(1, context.generation());
                  assertNotNull(context.documentId());
                  return accepted(context);
                })
            .build();
    Contracts runtime = Contracts.newBuilder().nativeChannel(registration).build();
    Map<String, Object> event = Map.of("kind", "Accepted", "sequence", 1);
    try (Contracts.Session session = runtime.openYaml(source())) {
      session.initialize().requireApplied();
      assertEquals(
          List.of(Map.of("kind", "Counted")), session.process(event, 7).requireApplied().events());
      assertNull(seen.get());
      assertEquals(BigInteger.ONE, session.read("/count"));
      session.process(event, 7);
      assertEquals(BigInteger.ONE, session.read("/count"));
      assertEquals(7, seen.get().timestamp());
      session.process(Map.of("kind", "Ignored", "sequence", 2), 8);
      assertEquals(BigInteger.ONE, session.read("/count"));
      try (Contracts.Session restored = runtime.restore(session.snapshot(), session.identity())) {
        Map<String, Object> next = Map.of("kind", "Accepted", "sequence", 3);
        Contracts.Transition warm = session.process(next, 9).requireApplied();
        Contracts.Transition cold = restored.process(next, 9).requireApplied();
        assertEquals(warm.gas(), cold.gas());
        assertEquals(session.identity(), restored.identity());
        assertEquals(BigInteger.TWO, restored.read("/count"));
      }
      assertThrows(
          BlueException.class,
          () -> Contracts.create().restore(session.snapshot(), session.identity()));
      NativeChannel changed =
          NativeChannel.newBuilder(
                  TYPE, LANGUAGE.readYaml("description: Changed semantics."), "channeltest")
              .headerFields("kind")
              .accept(NativeChannelTest::accepted)
              .build();
      assertThrows(
          BlueException.class,
          () ->
              Contracts.newBuilder()
                  .nativeChannel(changed)
                  .build()
                  .restore(session.snapshot(), session.identity()));
    }
  }

  @Test
  void callbackAndGasFailuresLeaveTheDocumentAndEventsUnchanged() {
    for (boolean exhaust : List.of(false, true)) {
      NativeChannel registration =
          channel(
                  context -> {
                    if (exhaust) {
                      context.charge("invocation", 10_000_000);
                    } else {
                      throw new IllegalStateException("Callback failure");
                    }
                    return true;
                  })
              .build();
      try (Contracts.Session session =
          Contracts.newBuilder().nativeChannel(registration).build().openYaml(source())) {
        session.initialize().requireApplied();
        String before = session.identity();
        Contracts.Transition failed = session.process(Map.of("kind", "Accepted"), 1);
        assertFalse(failed.applied());
        assertTrue(failed.events().isEmpty());
        assertTrue(failed.gas() > 0);
        assertEquals(before, session.identity());
      }
    }
  }

  @Test
  void contextCannotEscapeItsThreadOrInvocationAndValuesAreImmutable() {
    AtomicReference<NativeChannel.Context> retained = new AtomicReference<>();
    NativeChannel registration =
        channel(
                context -> {
                  retained.set(context);
                  CompletableFuture.runAsync(
                          () -> assertThrows(IllegalStateException.class, context::event))
                      .join();
                  assertThrows(
                      UnsupportedOperationException.class,
                      () -> context.header().put("kind", "Changed"));
                  assertThrows(
                      UnsupportedOperationException.class,
                      () -> ((Map<?, ?>) context.event()).clear());
                  long before = context.remainingGas();
                  context.charge("invocation", 3);
                  assertEquals(before - 3, context.remainingGas());
                  return true;
                })
            .build();
    try (Contracts.Session session =
        Contracts.newBuilder().nativeChannel(registration).build().openYaml(source())) {
      session.initialize().requireApplied();
      session.process(Map.of("kind", "Accepted"), 12).requireApplied();
      assertThrows(IllegalStateException.class, retained.get()::header);
      assertThrows(IllegalStateException.class, retained.get()::checkpoint);
      assertThrows(IllegalStateException.class, () -> retained.get().charge("invocation", 1));
    }
  }

  @Test
  void buildersSnapshotTariffsAndRejectConflictingTypeRegistrations() {
    NativeChannel.Builder builder =
        channel(
                context -> {
                  context.charge("work", 2);
                  return true;
                })
            .counter("work", 3);
    NativeChannel first = builder.build();
    NativeChannel second = builder.counter("work", 17).build();
    assertEquals(28, charge(second) - charge(first));
    assertDoesNotThrow(
        () -> Contracts.newBuilder().nativeChannel(first).nativeChannel(first).build());
    assertThrows(
        IllegalArgumentException.class,
        () -> Contracts.newBuilder().nativeChannel(first).nativeChannel(second));
    NativeStep step = NativeStep.newBuilder(TYPE, SEMANTICS, "step").execute(context -> {}).build();
    assertThrows(
        IllegalArgumentException.class,
        () -> Contracts.newBuilder().nativeStep(step).nativeChannel(first));
    assertThrows(
        IllegalArgumentException.class,
        () -> Contracts.newBuilder().nativeChannel(first).nativeStep(step));
    assertThrows(IllegalArgumentException.class, () -> builder.counter("invocation", 0));
    assertThrows(BlueException.class, () -> builder.peerFields("undeclared").build());
  }

  private static long charge(NativeChannel channel) {
    try (Contracts.Session session =
        Contracts.newBuilder().nativeChannel(channel).build().openYaml(source())) {
      session.initialize().requireApplied();
      return session.process(Map.of("kind", "Accepted"), 1).requireApplied().gas();
    }
  }

  @Test
  void peerAccessIsRestrictedToDeclaredNamesAndDistinguishesNonChannels() {
    for (String peer : List.of("other", "absent", "onEvent")) {
      AtomicReference<NativeChannel.Peer> selected = new AtomicReference<>();
      NativeChannel registration =
          channel(
                  context -> {
                    selected.set(
                        context.peer(
                            (String) ((List<?>) context.header().get("friend")).getFirst()));
                    return true;
                  })
              .headerFields("kind", "friend")
              .peerFields("friend")
              .build();
      String source =
          source()
              .replace("kind: Accepted}", "kind: Accepted, friend: [" + peer + "]}")
              .replace(
                  "  onEvent:",
                  "  other: {type: Contracts/External Channel, event: {kind: Never}}\n  onEvent:");
      try (Contracts.Session session =
          Contracts.newBuilder().nativeChannel(registration).build().openYaml(source)) {
        session.initialize().requireApplied();
        session.process(Map.of("kind", "Accepted"), 1).requireApplied();
        NativeChannel.PeerStatus expected =
            switch (peer) {
              case "other" -> NativeChannel.PeerStatus.PRESENT;
              case "absent" -> NativeChannel.PeerStatus.ABSENT;
              default -> NativeChannel.PeerStatus.NON_CHANNEL;
            };
        assertEquals(expected, selected.get().status());
        assertEquals(
            expected != NativeChannel.PeerStatus.PRESENT, selected.get().header().isEmpty());
      }
    }
    NativeChannel undeclared =
        channel(
                context -> {
                  context.peer("other");
                  return true;
                })
            .build();
    try (Contracts.Session session =
        Contracts.newBuilder().nativeChannel(undeclared).build().openYaml(source())) {
      session.initialize().requireApplied();
      String before = session.identity();
      assertFalse(session.process(Map.of("kind", "Accepted"), 1).applied());
      assertEquals(before, session.identity());
    }
  }

  @Test
  void materializingACompactSharedEventHonorsOutputBoundsAndRollsBack() {
    LanguageDocument node = LANGUAGE.readYaml("leaf: 7");
    for (int i = 0; i < 18; i++) {
      Language language = Language.newBuilder().content(node).build();
      String ref = "{blueId: " + node.blueId().value() + "}";
      node = language.readYaml("left: " + ref + "\nright: " + ref);
    }
    NativeChannel registration =
        channel(
                context -> {
                  context.event();
                  return true;
                })
            .build();
    Language language = Language.newBuilder().content(node).build();
    Map<String, Object> large =
        Map.of("kind", "Accepted", "data", Map.of("blueId", node.blueId().value()));
    try (Contracts.Session session =
        Contracts.newBuilder()
            .language(language)
            .nativeChannel(registration)
            .build()
            .openYaml(source())) {
      session.initialize().requireApplied();
      String before = session.identity();
      BlueException error = assertThrows(BlueException.class, () -> session.process(large, 1));
      assertEquals("PROJECTION_LIMIT_EXCEEDED", error.code());
      assertEquals(before, session.identity());
      Contracts.Transition retry = session.process(Map.of("kind", "Accepted"), 1).requireApplied();
      assertEquals(BigInteger.ONE, session.read("/count"));
      assertEquals(List.of(Map.of("kind", "Counted")), retry.events());
    }
    NativeChannel selective =
        channel(
                context ->
                    java.util.Objects.equals(context.event("/kind"), context.header().get("kind")))
            .build();
    try (Contracts.Session session =
        Contracts.newBuilder()
            .language(language)
            .nativeChannel(selective)
            .build()
            .openYaml(source())) {
      session.initialize().requireApplied();
      session.process(large, 1).requireApplied();
      assertEquals(BigInteger.ONE, session.read("/count"));
    }
  }

  @Test
  void selectedEventPointersPreserveEscapesAndMissingValues() {
    NativeChannel registration =
        channel(
                context -> {
                  assertEquals(BigInteger.valueOf(7), context.event("/payload/a~1b~0c/0/amount"));
                  assertSame(Bex.undefined(), context.event("/payload/a~1b~0c/01"));
                  assertSame(Bex.undefined(), context.event("/payload/a~1b~0c/3"));
                  assertSame(Bex.undefined(), context.event("/payload/a~1b~0c/0/amount/missing"));
                  assertThrows(
                      UnsupportedOperationException.class,
                      () -> ((List<?>) context.event("/payload/a~1b~0c")).clear());
                  return true;
                })
            .build();
    try (Contracts.Session session =
        Contracts.newBuilder().nativeChannel(registration).build().openYaml(source())) {
      session.initialize().requireApplied();
      session
          .process(Map.of("payload", Map.of("a/b~c", List.of(Map.of("amount", 7)))), 1)
          .requireApplied();
      assertEquals(BigInteger.ONE, session.read("/count"));
    }
  }

  @Test
  void malformedEventPointersAbortWithoutPublishing() {
    for (String pointer : List.of("kind", "/bad~2escape")) {
      NativeChannel registration =
          channel(
                  context -> {
                    context.event(pointer);
                    return true;
                  })
              .build();
      try (Contracts.Session session =
          Contracts.newBuilder().nativeChannel(registration).build().openYaml(source())) {
        session.initialize().requireApplied();
        String before = session.identity();
        BlueException failure =
            assertThrows(BlueException.class, () -> session.process(Map.of("kind", "Accepted"), 1));
        assertEquals("INVALID_POINTER", failure.code());
        assertEquals(before, session.identity());
      }
    }
  }

  @Test
  void invalidTypeAncestryIsRejectedBeforeAcceptingWork() {
    NativeChannel invalid =
        NativeChannel.newBuilder(
                LANGUAGE.defineYaml("type: Contracts/Contract"), SEMANTICS, "invalid")
            .accept(context -> true)
            .build();
    assertThrows(
        BlueException.class,
        () -> Contracts.newBuilder().nativeChannel(invalid).build().openYaml("count: 0"));
  }

  @Test
  void acceptedOperationEnvelopesUseTheOrdinaryOperationDispatcher() {
    NativeChannel registration = channel(NativeChannelTest::accepted).build();
    String operation =
        source()
            .replace(
                "type: Contracts/Sequential Workflow\n",
                "type: Contracts/Sequential Workflow Operation\n");
    try (Contracts.Session session =
        Contracts.newBuilder().nativeChannel(registration).build().openYaml(operation)) {
      session.initialize().requireApplied();
      Map<String, Object> request =
          Map.of(
              "type",
              "Contracts/Operation Request",
              "channel",
              "input",
              "operation",
              "onEvent",
              "request",
              Map.of("amount", 7));
      Contracts.Transition result =
          session.process(Map.of("kind", "Accepted", "operation", request), 1).requireApplied();
      assertEquals(BigInteger.ONE, session.read("/count"));
      assertEquals(List.of(Map.of("kind", "Counted")), result.events());
    }
  }

  @Test
  void subtypeAcceptanceRequiresAnExplicitInheritedBinding() {
    LanguageDocument child = TYPE.extendYaml("description: Specialized channel.");
    Language language = Language.newBuilder().type("Example/Child", child).build();
    String source = source().replace(TYPE.blueId().value(), child.blueId().value());
    NativeChannel exact = channel(NativeChannelTest::accepted).build();
    assertThrows(
        BlueException.class,
        () -> {
          try (Contracts.Session session =
              Contracts.newBuilder()
                  .language(language)
                  .nativeChannel(exact)
                  .build()
                  .openYaml(source)) {
            session.initialize().requireApplied();
            session.process(Map.of("kind", "Accepted"), 1).requireApplied();
          }
        });
    NativeChannel inherited = channel(NativeChannelTest::accepted).inherited(true).build();
    try (Contracts.Session session =
        Contracts.newBuilder()
            .language(language)
            .nativeChannel(inherited)
            .build()
            .openYaml(source)) {
      session.initialize().requireApplied();
      session.process(Map.of("kind", "Accepted"), 1).requireApplied();
      assertEquals(BigInteger.ONE, session.read("/count"));
    }
  }

  @Test
  void compactionPreservesNativeAcceptanceAndDuplicateSuppression() {
    Contracts runtime =
        Contracts.newBuilder().nativeChannel(channel(NativeChannelTest::accepted).build()).build();
    Map<String, Object> event = Map.of("kind", "Accepted", "sequence", 1);
    try (Contracts.Session session = runtime.openYaml(source())) {
      session.initialize().requireApplied();
      session.process(event, 7).requireApplied();
      String identity = session.identity();
      session.compact();
      assertEquals(identity, session.identity());
      session.process(event, 7);
      assertEquals(BigInteger.ONE, session.read("/count"));
      session.process(Map.of("kind", "Accepted", "sequence", 2), 8).requireApplied();
      assertEquals(BigInteger.TWO, session.read("/count"));
    }
  }
}
