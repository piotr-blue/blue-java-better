package blue;

import blue.examples.CanonicalAgreement;
import java.math.BigInteger;
import java.util.Map;

/** Prints scoped physical diagnostics for a fixed business case in a short-lived JVM. */
@SuppressWarnings("try")
public final class ContractCounterProbe {
  private ContractCounterProbe() {}

  public static void main(String[] args) {
    if (!ContractMetrics.enabled()) {
      throw new IllegalStateException("Run with -Dblue.physical.counters=true");
    }
    Contracts engine = Contracts.create();
    try (var root = engine.openJson(CanonicalAgreement.source("agreement"));
        var order = engine.openJson(CanonicalAgreement.order("one"));
        var payment = engine.openJson(CanonicalAgreement.payment("one"))) {
      try (var ignored = ContractMetrics.scope("initialize")) {
        root.initialize().requireApplied();
      }
      try (var ignored = ContractMetrics.scope("order")) {
        root.call("input", "addOrder", Map.of("key", "one", "order", order.read("")), 1)
            .requireApplied();
      }
      try (var ignored = ContractMetrics.scope("payment")) {
        root.call("order-one", "submit", Map.of("payment", payment.read("")), 2).requireApplied();
      }
      try (var ignored = ContractMetrics.scope("settle")) {
        Contracts.Transition settled =
            root.call("payment-one", "settle", Map.of(), 3).requireApplied();
        System.out.println("settled gas=" + settled.gas() + " receipt=" + settled.receipt());
      }
      if (!(root.read("/paidTotal") instanceof BigInteger total) || total.signum() <= 0) {
        throw new AssertionError("The payment did not reach the Agreement");
      }
    }
    Map<String, Map<String, Long>> scopes = ContractMetrics.scopedSnapshot();
    if (!scopes.keySet().equals(java.util.Set.of("initialize", "order", "payment", "settle"))) {
      throw new AssertionError("Missing diagnostic scope: " + scopes.keySet());
    }
    scopes.forEach((name, counters) -> System.out.println(name + " " + counters));
    System.out.println("process " + ContractMetrics.snapshot());
  }
}
