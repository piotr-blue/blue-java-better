import blue.examples.CanonicalAgreement;
import blue.language.next.WireData;
import java.util.Map;

/** Optional cross-version check; the ordinary build has no sibling-repository dependency. */
public final class CanonicalCompatibility {
  private static int comparisons;

  public static void main(String[] arguments) {
    for (int count : new int[] {1, 10, 20}) {
      blue.contracts.Contracts previous = new blue.contracts.Contracts();
      blue.Contracts current = blue.Contracts.create();
      try (blue.Contracts.Session session = current.openJson(CanonicalAgreement.source("agreement"))) {
        var before = previous.initialize(WireData.json(CanonicalAgreement.source("agreement")));
        compare(before, session.initialize(), "initialize/" + count);
        var state = before.document();
        for (int i = 0; i < count; i++) {
          var fields = Map.<String, Object>of("key", "o" + i, "order", WireData.json(CanonicalAgreement.order("o" + i)));
          var event = previous.typed("Operation Request", Map.of("channel", "input", "operation", "addOrder", "request", fields));
          var result = previous.process(state, event, i + 1);
          compare(result, session.process(event, i + 1), "add/" + count + "/" + i);
          state = result.document();
        }
        var submit = previous.typed("Operation Request", Map.of("channel", "order-o0", "operation", "submit", "request", Map.of("payment", WireData.json(CanonicalAgreement.payment("o0")))));
        var submitted = previous.process(state, submit, count + 1);
        compare(submitted, session.process(submit, count + 1), "submit/" + count);
        var settle = previous.typed("Operation Request", Map.of("channel", "payment-o0", "operation", "settle", "request", Map.of()));
        compare(previous.process(submitted.document(), settle, count + 2), session.process(settle, count + 2), "settle/" + count);
        System.out.println("orders=" + count + " exact observations and receipts matched");
      }
    }
    System.out.println("comparisons=" + comparisons);
  }

  private static void compare(blue.contracts.Contracts.Result expected, blue.Contracts.Transition actual, String label) {
    if (!expected.status().equals("APPLIED") || !actual.applied()
        || !expected.receipt().equals(actual.receipt())
        || !WireData.write(expected.observations()).equals(WireData.write(actual.observations()))) {
      throw new AssertionError(label + " old=" + expected.receipt() + " new=" + actual.receipt()
          + "\n" + WireData.write(expected.observations()) + "\n" + WireData.write(actual.observations()));
    }
    System.out.println("receipt " + label + " " + actual.receipt());
    comparisons++;
  }
}
