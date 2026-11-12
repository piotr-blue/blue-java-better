package blue.examples;

import blue.Contracts;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Runnable canonical Contracts agreement with orders, nested payments and parent reactions. */
public final class CanonicalAgreement {
  private CanonicalAgreement() {}

  public static String source(String name) {
    try (var input = CanonicalAgreement.class.getResourceAsStream("/canonical/" + name + ".json")) {
      if (input == null) throw new IllegalArgumentException(name);
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException error) {
      throw new IllegalStateException(error);
    }
  }

  public static String order(String key) {
    return source("order").replace("ORDER_KEY", key).replace("ORDER_CHANNEL", "order-" + key);
  }

  public static String payment(String key) {
    return source("payment").replace("PAYMENT_CHANNEL", "payment-" + key);
  }

  public static void main(String[] args) {
    Contracts contracts = Contracts.create();
    try (Contracts.Session agreement = contracts.openJson(source("agreement"));
        Contracts.Session order = contracts.openJson(order("one"));
        Contracts.Session payment = contracts.openJson(payment("one"))) {
      show("initialize", agreement.initialize());
      show(
          "add order",
          agreement.call("input", "addOrder", Map.of("key", "one", "order", order.read("")), 1));
      show("submit", agreement.call("order-one", "submit", Map.of("payment", payment.read("")), 2));
      show("settle", agreement.call("payment-one", "settle", Map.of(), 3));
      System.out.println("paidTotal=" + agreement.read("/paidTotal"));
      System.out.println("order.state=" + agreement.read("/orders/one/state"));
      System.out.println("payment.state=" + agreement.read("/orders/one/payment/state"));
    }
  }

  private static void show(String label, Contracts.Transition result) {
    result.requireApplied();
    System.out.println(label + ": gas=" + result.gas() + " events=" + result.events());
  }
}
