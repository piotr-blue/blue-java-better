package blue;

import static org.junit.jupiter.api.Assertions.*;

import blue.examples.CanonicalAgreement;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Business results pinned by the previous release; gas/receipt identities intentionally changed.
 */
@Tag("contracts")
@Tag("conformance")
class CanonicalCompatibilityTest {
  @ParameterizedTest
  @ValueSource(ints = {1, 10, 20})
  void dynamicallyCreatedOrdersAndPaymentsKeepCanonicalIdentity(int count) throws IOException {
    Map<String, String> expected = receipts();
    try (Contracts.Session session =
        Contracts.create().openJson(CanonicalAgreement.source("agreement"))) {
      compare(expected, "initialize/" + count, session, session.initialize());
      for (int i = 0; i < count; i++) {
        Object order = ContractWire.data(ContractWire.json(CanonicalAgreement.order("o" + i)));
        compare(
            expected,
            "add/" + count + "/" + i,
            session,
            session.call("input", "addOrder", Map.of("key", "o" + i, "order", order), i + 1));
      }
      Object payment = ContractWire.data(ContractWire.json(CanonicalAgreement.payment("o0")));
      compare(
          expected,
          "submit/" + count,
          session,
          session.call("order-o0", "submit", Map.of("payment", payment), count + 1));
      compare(
          expected,
          "settle/" + count,
          session,
          session.call("payment-o0", "settle", Map.of(), count + 2));
    }
  }

  private static void compare(
      Map<String, String> expected,
      String key,
      Contracts.Session session,
      Contracts.Transition result) {
    result.requireApplied();
    assertEquals(
        expected.get(key), session.blueId() + " " + ContractWire.write(result.events()), key);
  }

  private static Map<String, String> receipts() throws IOException {
    try (var input =
        CanonicalCompatibilityTest.class.getResourceAsStream(
            "/canonical/compatibility-documents.txt")) {
      assertNotNull(input);
      Map<String, String> result = new LinkedHashMap<>();
      for (String line :
          new String(input.readAllBytes(), StandardCharsets.UTF_8).lines().toList()) {
        String[] fields = line.split(" ", 2);
        result.put(fields[0], fields[1]);
      }
      return result;
    }
  }
}
