package blue;

import static org.junit.jupiter.api.Assertions.*;

import blue.examples.CanonicalAgreement;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("contracts")
@Tag("scenario")
class CanonicalAgreementTest {
  @Test
  void opensAnAdmittedLanguageDocumentWithItsVerifiedDependencies() {
    Language language = Language.newBuilder().mount("Contracts", Contracts.publication()).build();
    LanguageDocument source = language.readJson(CanonicalAgreement.source("agreement"));
    try (Contracts.Session session = Contracts.create().open(source)) {
      session.initialize().requireApplied();
      session
          .call("input", "addOrder", Map.of("key", "0", "order", order("0")), 1)
          .requireApplied();
      assertEquals(BigInteger.ONE, session.read("/initializedOrders"));
    }
  }

  static Object order(String key) {
    return ContractWire.data(ContractWire.json(CanonicalAgreement.order(key)));
  }

  static Object payment(String key) {
    return ContractWire.data(ContractWire.json(CanonicalAgreement.payment(key)));
  }

  static Contracts.Session open(Contracts contracts, int count) {
    Contracts.Session session = contracts.openJson(CanonicalAgreement.source("agreement"));
    session.initialize().requireApplied();
    for (int i = 0; i < count; i++) {
      String key = "" + i;
      session
          .call("input", "addOrder", Map.of("key", key, "order", order(key)), i + 1)
          .requireApplied();
    }
    return session;
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 5, 16})
  void childInitializationSubmissionAndNestedPaymentUpdateOnlyTheSelectedBranches(int count) {
    try (Contracts.Session session = open(Contracts.create(), count)) {
      assertEquals(BigInteger.valueOf(count), session.read("/initializedOrders"));
      assertEquals(BigInteger.valueOf(count), session.read("/activityCount"));
      assertEquals(true, session.read("/orders/0/ready"));
      Contracts.Transition submitted =
          session.call("order-0", "submit", Map.of("payment", payment("0")), 100);
      submitted.requireApplied();
      assertEquals(
          "Agreement Order Submitted", ((Map<?, ?>) submitted.events().getFirst()).get("kind"));
      Contracts.Transition settled = session.call("payment-0", "settle", Map.of(), 101);
      settled.requireApplied();
      assertEquals(
          List.of(Map.of("kind", "Agreement Payment Received", "amount", BigInteger.valueOf(42))),
          settled.events());
      assertEquals(BigInteger.valueOf(42), session.read("/paidTotal"));
      assertEquals(BigInteger.ONE, session.read("/submittedOrders"));
      assertEquals(BigInteger.valueOf(count + 2), session.read("/activityCount"));
      assertEquals("paid", session.read("/orders/0/state"));
      assertEquals("paid", session.read("/orders/0/payment/state"));
      for (int i = 1; i < count; i++) {
        assertEquals("draft", session.read("/orders/" + i + "/state"));
        assertSame(Bex.undefined(), session.read("/orders/" + i + "/payment"));
      }
    }
  }

  @Test
  void failedBusinessValidationRollsBackTheWholeInvocationAndPublishesNoEvents() {
    try (Contracts.Session session = open(Contracts.create(), 1)) {
      String before = session.identity();
      Contracts.Transition result =
          session.call(
              "input", "addOrder", Map.of("key", "0", "order", order("0"), "attempt", 2), 2);
      assertFalse(result.applied());
      assertEquals(before, session.identity());
      assertTrue(result.events().isEmpty());
      assertTrue(result.gas() > 0, result.observations().toString());
      assertThrows(BlueException.class, result::requireApplied);
    }
  }

  @Test
  void coldRestoreAndWarmExecutionHaveIdenticalReceiptsAndGas() {
    Contracts contracts = Contracts.create();
    try (Contracts.Session warm = open(contracts, 2);
        Contracts.Session cold = contracts.restore(warm.snapshot(), warm.identity())) {
      Map<String, Object> request = Map.of("payment", payment("1"));
      Contracts.Transition a = warm.call("order-1", "submit", request, 30);
      Contracts.Transition b = cold.call("order-1", "submit", request, 30);
      a.requireApplied();
      b.requireApplied();
      assertEquals(a.gas(), b.gas());
      assertEquals(a.receipt(), b.receipt());
      assertEquals(a.observations(), b.observations());
      assertEquals(warm.blueId(), cold.blueId());
    }
  }

  @Test
  @Tag("concurrency")
  void independentSessionsShareOnlyImmutableConfiguration() throws Exception {
    Contracts contracts = Contracts.create();
    try (var threads = Executors.newFixedThreadPool(4)) {
      var futures = new ArrayList<java.util.concurrent.Future<String>>();
      for (int i = 0; i < 8; i++)
        futures.add(
            threads.submit(
                () -> {
                  try (Contracts.Session session = open(contracts, 1)) {
                    return session
                        .call("order-0", "submit", Map.of("payment", payment("0")), 2)
                        .requireApplied()
                        .receipt();
                  }
                }));
      String receipt = futures.getFirst().get();
      for (var future : futures) assertEquals(receipt, future.get());
    }
  }

  @Test
  void closedSessionsRejectFurtherWorkButPreviouslyReturnedDocumentsRemainUsable() {
    Contracts.Session session = open(Contracts.create(), 1);
    LanguageDocument selected = session.document();
    session.close();
    assertThrows(IllegalStateException.class, session::initialize);
    assertFalse(selected.canonicalJson().isEmpty());
  }
}
