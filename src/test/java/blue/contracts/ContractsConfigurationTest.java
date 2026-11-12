package blue.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.BlueException;
import blue.BlueId;
import blue.Contracts;
import blue.GasSchedule;
import blue.LanguageDocument;
import java.math.BigInteger;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The public Contracts configuration and session-lifetime contract, apart from execution rules. */
@Tag("contracts")
class ContractsConfigurationTest {
  private static final String COUNTER =
      """
      count: 0
      contracts:
        input: {type: Contracts/External Channel}
        increment:
          type: Contracts/Sequential Workflow Operation
          channel: input
          steps:
            - type: Contracts/Update Document
              changeset:
                - op: replace
                  path: /count
                  val: {$add: [{$document: /count}, 1]}
      """;

  /** The status of the first step that does not apply, or of the increment itself. */
  private static String increment(Contracts contracts) {
    try (Contracts.Session session = contracts.openYaml(COUNTER)) {
      Contracts.Transition initialized = session.initialize();
      if (!initialized.applied()) {
        return initialized.status();
      }
      return session.call("input", "increment", Map.of(), 1L).status();
    }
  }

  @ParameterizedTest
  @ValueSource(longs = {-1, 10_000_001, Long.MAX_VALUE, Long.MIN_VALUE})
  void aGasLimitOutsideThePublishedRangeIsRejected(long limit) {
    assertThrows(IllegalArgumentException.class, () -> Contracts.newBuilder().gasLimit(limit));
  }

  @ParameterizedTest
  @ValueSource(longs = {0, 1, 10_000_000})
  void theBoundsOfThePublishedRangeAreAccepted(long limit) {
    assertNotNull(Contracts.newBuilder().gasLimit(limit).build());
  }

  @Test
  void nullConfigurationIsRejectedImmediately() {
    Contracts.Builder builder = Contracts.newBuilder();
    assertThrows(NullPointerException.class, () -> builder.language(null));
    assertThrows(NullPointerException.class, () -> builder.gasSchedule(null));
    assertThrows(NullPointerException.class, () -> builder.nativeStep(null));
    assertThrows(NullPointerException.class, () -> builder.nativeChannel(null));
    assertThrows(NullPointerException.class, () -> builder.require(null));
  }

  @Test
  void theDefaultConfigurationUsesTheStandardScheduleAndPublishedRules() {
    Contracts contracts = Contracts.create();
    assertSame(GasSchedule.STANDARD, contracts.gasSchedule());
    assertEquals(contracts.rulesId(), Contracts.newBuilder().gasLimit(7).build().rulesId());
    assertNotNull(Contracts.publication());
  }

  @Test
  void aBudgetTooSmallForInitializationLeavesTheDocumentUnchanged() {
    try (Contracts.Session session = Contracts.newBuilder().gasLimit(1).build().openYaml(COUNTER)) {
      Contracts.Transition starved = session.initialize();
      assertEquals("GAS_LIMIT_EXCEEDED", starved.status());
      assertTrue(starved.gas() <= 1);
      BlueException failure = assertThrows(BlueException.class, starved::requireApplied);
      assertEquals("GAS_LIMIT_EXCEEDED", failure.code());
      assertEquals(BigInteger.ZERO, session.read("/count"));
    }
  }

  @Test
  void toBuilderCopiesTheConfigurationWithoutSharingLaterChanges() {
    Contracts strict = Contracts.newBuilder().gasLimit(1).build();
    Contracts copy = strict.toBuilder().build();
    Contracts relaxed = strict.toBuilder().gasLimit(10_000_000).build();
    assertEquals("GAS_LIMIT_EXCEEDED", increment(copy));
    assertEquals("APPLIED", increment(relaxed));
    assertEquals("GAS_LIMIT_EXCEEDED", increment(strict));
    assertSame(strict.gasSchedule(), copy.gasSchedule());
  }

  @Test
  void aBuilderCanBuildSeveralIndependentConfigurations() {
    Contracts.Builder builder = Contracts.newBuilder().gasLimit(1);
    Contracts strict = builder.build();
    Contracts relaxed = builder.gasLimit(10_000_000).build();
    assertEquals("GAS_LIMIT_EXCEEDED", increment(strict));
    assertEquals("APPLIED", increment(relaxed));
  }

  @Test
  void sessionsOfOneConfigurationDoNotShareDocumentState() {
    Contracts contracts = Contracts.create();
    try (Contracts.Session first = contracts.openYaml(COUNTER);
        Contracts.Session second = contracts.openYaml(COUNTER)) {
      first.initialize().requireApplied();
      second.initialize().requireApplied();
      first.call("input", "increment", Map.of("request", 1), 1L).requireApplied();
      first.call("input", "increment", Map.of("request", 2), 2L).requireApplied();
      assertEquals(BigInteger.TWO, first.read("/count"));
      assertEquals(BigInteger.ZERO, second.read("/count"));
    }
  }

  @Test
  void theRootMemberIsAddressableByNameAndUnknownMembersAreRejected() {
    try (Contracts.Session session = Contracts.create().openYaml(COUNTER)) {
      session.initialize().requireApplied();
      assertEquals(session.read("/count"), session.readMember("root", "/count"));
      assertEquals(session.document().blueId(), session.member("root").blueId());
      assertThrows(IllegalArgumentException.class, () -> session.member("absent"));
      assertThrows(NullPointerException.class, () -> session.member(null));
      assertThrows(NullPointerException.class, () -> session.readMember("root", null));
    }
  }

  @Test
  void aClosedSessionRejectsWorkButReturnedValuesRemainUsable() {
    Contracts.Session session = Contracts.create().openYaml(COUNTER);
    session.initialize().requireApplied();
    LanguageDocument document = session.document();
    BlueId identity = document.blueId();
    Object count = session.read("/count");
    session.close();
    session.close();
    assertThrows(IllegalStateException.class, () -> session.read("/count"));
    assertThrows(IllegalStateException.class, session::document);
    assertThrows(IllegalStateException.class, () -> session.call("input", "increment", null, 1L));
    assertEquals(BigInteger.ZERO, count);
    assertEquals(identity, document.blueId());
    assertTrue(document.canonicalJson().contains("\"count\""));
  }
}
