import blue.examples.CanonicalAgreement;
import blue.language.next.WireData;
import java.util.Map;

/** Optional cross-version check; the ordinary build has no sibling-repository dependency. */
public final class CanonicalCompatibility {
  private static boolean exactReceipts;
  private static int comparisons;

  public static void main(String[] arguments) {
    if (arguments.length > 1 || arguments.length == 1 && !arguments[0].equals("--exact-receipts")) {
      throw new IllegalArgumentException("Usage: CanonicalCompatibility [--exact-receipts]");
    }
    exactReceipts = arguments.length == 1;
    for (int count : new int[] {1, 10, 20}) {
      blue.contracts.Contracts previous = new blue.contracts.Contracts();
      blue.Contracts current = blue.Contracts.create();
      try (blue.Contracts.Session session = current.openJson(CanonicalAgreement.source("agreement"))) {
        var before = previous.initialize(WireData.json(CanonicalAgreement.source("agreement")));
        compare(previous, before, session, session.initialize(), "initialize/" + count);
        var state = before.document();
        for (int i = 0; i < count; i++) {
          var fields = Map.<String, Object>of("key", "o" + i, "order", WireData.json(CanonicalAgreement.order("o" + i)));
          var event = previous.typed("Operation Request", Map.of("channel", "input", "operation", "addOrder", "request", fields));
          var result = previous.process(state, event, i + 1);
          compare(previous, result, session, session.process(event, i + 1), "add/" + count + "/" + i);
          state = result.document();
        }
        var submit = previous.typed("Operation Request", Map.of("channel", "order-o0", "operation", "submit", "request", Map.of("payment", WireData.json(CanonicalAgreement.payment("o0")))));
        var submitted = previous.process(state, submit, count + 1);
        compare(previous, submitted, session, session.process(submit, count + 1), "submit/" + count);
        var settle = previous.typed("Operation Request", Map.of("channel", "payment-o0", "operation", "settle", "request", Map.of()));
        compare(previous, previous.process(submitted.document(), settle, count + 2), session, session.process(settle, count + 2), "settle/" + count);
        System.out.println("orders=" + count + " document states and public events matched");
      }
    }
    System.out.println("comparisons=" + comparisons);
  }

  private static void compare(
      blue.contracts.Contracts previous, blue.contracts.Contracts.Result expected,
      blue.Contracts.Session session, blue.Contracts.Transition actual, String label) {
    actual.requireApplied();
    if (!expected.commits()) throw new AssertionError(label + ": original engine refused the input");
    same(label + " root BlueId", expected.document().blueId(), session.document().blueId().toString());
    same(label + " root values", WireData.write(previous.read(expected.document(), "")),
        WireData.write(session.read("")));
    same(label + " projected events", WireData.write(previous.emitted(expected)),
        WireData.write(actual.events()));
    // A completion commitment includes the gas edition. Compare its observable routing and order.
    same(label + " event routes", eventRoutes(expected.publicEvents()),
        eventRoutes((java.util.List<?>) actual.observations().get("publicEvents")));
    if (exactReceipts) {
      same(label + " receipt", expected.receipt(), actual.receipt());
      same(label + " observations", WireData.write(expected.observations()), WireData.write(actual.observations()));
    }
    System.out.println("transition " + label + " originalGas=" + expected.gas()
        + " betterGas=" + actual.gas() + " root=" + expected.document().blueId());
    comparisons++;
  }

  private static String eventRoutes(java.util.List<?> occurrences) {
    return WireData.write(occurrences.stream().map(raw -> {
      var event = (Map<?, ?>) raw;
      return java.util.List.of(event.get("document"), event.get("event"),
          event.get("ordinal"), event.get("sourceOrdinal"));
    }).toList());
  }

  private static void same(String label, Object previous, Object current) {
    if (!previous.equals(current)) {
      throw new AssertionError(label + " original=" + previous + " better=" + current);
    }
  }
}
