package blue;

import static blue.ContractModel.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("contracts")
@Tag("conformance")
class CanonicalContractsConformanceTest {
  record Case(Map<String, Object> row) {
    @Override
    public String toString() {
      return row.get("name") + "/gas=" + row.get("budget");
    }
  }

  static Stream<Case> cases() throws Exception {
    try (var input =
        CanonicalContractsConformanceTest.class.getResourceAsStream("/canonical/contracts.json")) {
      return arr(
              obj(ContractWire.json(
                      new String(
                          Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8)))
                  .get("cases"))
          .stream()
          .map(value -> new Case(obj(value)))
          .filter(c -> integer(c.row.get("budget")) == 10_000_000);
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void preservesPublishedSuccessfulDocumentsAndOrderedEventPayloads(Case test) {
    Map<String, Object> row = test.row;
    ContractProcessor processor =
        new ContractProcessor(
            Language.create(), ContractBindings.core(), integer(row.get("budget")), Map.of());
    ContractProcessor.Result result;
    if (row.containsKey("event")) {
      ContractProcessor initializer = new ContractProcessor();
      ContractProcessor.Result initialized = initializer.initialize(row.get("source"));
      assertEquals("APPLIED", initialized.status());
      processor.blue.context.addEvidence(initializer.blue.context.evidence());
      result =
          processor.process(
              initialized.document(), row.get("event"), integer(row.get("timestamp")));
    } else result = processor.initialize(row.get("source"));
    Map<String, Object> expected = obj(row.get("observations"));
    assertEquals(expected.get("status"), result.status());
    assertEquals(expected.get("diagnostic"), result.observations().get("diagnostic"));
    // The retired representation-charging tariff has different gas and receipt identities.
    // Its successful examples still independently pin final document IDs and emitted content.
    Map<String, Object> expectedRoots = new TreeMap<>();
    for (Object completion : arr(expected.get("completions"))) {
      obj(obj(completion).get("afterView"))
          .forEach(
              (key, value) -> {
                if (key.equals("root"))
                  expectedRoots.put(key, obj(obj(value).get("document")).get("blueId"));
              });
    }
    assertEquals(expectedRoots.get("root"), result.document().blueId());
    for (String field : List.of("events", "publicEvents"))
      assertEquals(
          arr(expected.get(field)).stream().map(e -> obj(e).get("event")).toList(),
          arr(result.observations().get(field)).stream().map(e -> obj(e).get("event")).toList());
    long gas = result.gas();
    assertTrue(gas > 0);
    assertEquals("contracts-semantic-gas", result.observations().get("gasSchedule"));
  }
}
