package blue;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/** Creation barriers retain initialization order across ordinary writes and speculative work. */
@Tag("contracts")
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class ContractInitializationTest {
  private static final String ROOT =
      """
      revision: 0
      seen: false
      orders: {}
      contracts:
        input: {type: Contracts/External Channel}
        embedded: {type: Contracts/Process Embedded, collectionPaths: [/orders]}
        add:
          type: Contracts/Sequential Workflow
          channel: input
          steps:
            - type: Contracts/Update Document
              changeset: [{op: replace, path: /revision, val: 1}]
            - type: Contracts/Update Document
              changeset: [{op: add, path: /orders/a, val: {$event: /child}}]
            - type: Contracts/Update Document
              changeset: [{op: replace, path: /seen, val: {$document: /orders/a/ready}}]
            - type: Contracts/Trigger Event
              event: {kind: Added, ready: {$document: /seen}}
      """;

  private static final String CHILD =
      """
      ready: false
      template:
        ready: false
        initializations: 0
        contracts:
          lifecycle: {type: Contracts/Lifecycle Event Channel}
          initialize:
            type: Contracts/Sequential Workflow
            channel: lifecycle
            steps:
              - type: Contracts/Update Document
                changeset:
                  - {op: replace, path: /ready, val: true}
                  - {op: replace, path: /initializations, val: {$add: [{$document: /initializations}, 1]}}
      contracts:
        embedded: {type: Contracts/Process Embedded, paths: [/next]}
        lifecycle: {type: Contracts/Lifecycle Event Channel}
        initialize:
          type: Contracts/Sequential Workflow
          channel: lifecycle
          steps:
            - type: Contracts/Update Document
              changeset: [{op: add, path: /next, val: {$document: /template}}]
            - type: Contracts/Update Document
              changeset: [{op: replace, path: /ready, val: {$document: /next/ready}}]
      """;

  @Test
  void descendantsAddedAfterAnOrdinaryWriteMatchFullScanningAndInitializeBeforeUse() {
    assertEquals(family(false), family(true));
  }

  private static Map<String, Object> family(boolean disableReuse) {
    String property = "blue.physical.nativeDisabled", previous = System.getProperty(property);
    try {
      System.setProperty(property, Boolean.toString(disableReuse));
      try (Contracts.Session session = Contracts.create().openYaml(ROOT)) {
        session.initialize().requireApplied();
        Contracts.Transition result = session.process(event(), 1).requireApplied();
        assertFamily(session);
        assertEquals(java.util.List.of(Map.of("kind", "Added", "ready", true)), result.events());
        return Map.of("snapshot", session.snapshot(), "transition", observation(result));
      }
    } finally {
      if (previous == null) System.clearProperty(property);
      else System.setProperty(property, previous);
    }
  }

  @Test
  void discardedInitializationDoesNotEscapeAndACommittedRetryInitializesExactlyOnce() {
    try (Contracts.Session session = Contracts.create().openYaml(ROOT)) {
      session.initialize().requireApplied();
      String before = session.identity();
      Object event = event();
      Contracts.Transition discarded;
      try (Contracts.Transaction transaction = session.begin(event, 1)) {
        while (transaction.advance()) assertEquals(before, session.identity());
        discarded = transaction.finish().requireApplied();
      }
      assertEquals(before, session.identity());
      assertEquals(BigInteger.ZERO, session.read("/revision"));
      assertEquals(observation(discarded), observation(session.process(event, 1).requireApplied()));
      assertFamily(session);
      BlueId after = session.blueId();
      assertTrue(session.initialize().requireApplied().events().isEmpty());
      assertEquals(after, session.blueId());
      assertFamily(session);
    }
  }

  private static Object event() {
    try (Contracts.Session authored = Contracts.create().openYaml(CHILD)) {
      return Map.of("child", authored.read(""));
    }
  }

  private static Map<String, Object> observation(Contracts.Transition result) {
    return Map.of(
        "status", result.status(),
        "gas", result.gas(),
        "receipt", result.receipt(),
        "events", result.events(),
        "observations", result.observations());
  }

  private static void assertFamily(Contracts.Session session) {
    assertEquals(true, session.read("/seen"));
    assertEquals(true, session.read("/orders/a/ready"));
    assertEquals(true, session.read("/orders/a/next/ready"));
    assertEquals(BigInteger.ONE, session.read("/orders/a/next/initializations"));
    assertEquals(false, session.read("/orders/a/template/ready"));
    assertEquals(BigInteger.ZERO, session.read("/orders/a/template/initializations"));
  }
}
