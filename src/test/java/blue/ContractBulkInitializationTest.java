package blue;

import static org.junit.jupiter.api.Assertions.assertEquals;

import blue.examples.CanonicalAgreement;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Bulk admission must preserve child activation, parent reactions and canonical receipts. */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class ContractBulkInitializationTest {
  @ParameterizedTest
  @ValueSource(ints = {1, 10, 100})
  void initializationAndFollowingPaymentMatchFullScanning(int count) {
    assertEquals(observe(count, true), observe(count, false));
  }

  private static Map<String, Object> observe(int count, boolean disabled) {
    String property = "blue.physical.nativeDisabled", previous = System.getProperty(property);
    try {
      System.setProperty(property, Boolean.toString(disabled));
      var source =
          new LinkedHashMap<>(
              ContractModel.obj(ContractWire.json(CanonicalAgreement.source("agreement"))));
      var orders = new LinkedHashMap<String, Object>();
      for (int i = 0; i < count; i++) orders.put("" + i, CanonicalAgreementTest.order("" + i));
      source.put("orders", orders);
      try (var session = Contracts.create().openJson(ContractWire.write(source))) {
        var initialized = session.initialize().requireApplied();
        assertEquals(BigInteger.valueOf(count), session.read("/initializedOrders"));
        for (int i = 0; i < count; i++) assertEquals(true, session.read("/orders/" + i + "/ready"));
        var submitted =
            session
                .call(
                    "order-0", "submit", Map.of("payment", CanonicalAgreementTest.payment("0")), 1)
                .requireApplied();
        var paid = session.call("payment-0", "settle", Map.of(), 2).requireApplied();
        assertEquals(BigInteger.valueOf(42), session.read("/paidTotal"));
        return Map.of(
            "initialize",
            evidence(initialized),
            "submit",
            evidence(submitted),
            "pay",
            evidence(paid),
            "snapshot",
            session.snapshot());
      }
    } finally {
      if (previous == null) System.clearProperty(property);
      else System.setProperty(property, previous);
    }
  }

  private static Map<String, Object> evidence(Contracts.Transition transition) {
    return Map.of(
        "gas",
        transition.gas(),
        "receipt",
        transition.receipt(),
        "events",
        transition.events(),
        "observations",
        transition.observations());
  }
}
