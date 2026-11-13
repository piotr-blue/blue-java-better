package blue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Compare ordered edits with a list model and independently restored execution at every input. */
class ContractListPatchTest {
  private static final String SOURCE =
      """
      amounts: [10, 20, 30]
      contracts:
        input: {type: Contracts/External Channel}
        edit:
          type: Contracts/Sequential Workflow Operation
          channel: input
          steps:
            - type: Contracts/Update Document
              changeset: {$event: /request/changes}
      """;

  @ParameterizedTest
  @ValueSource(longs = {17, 592, 871223})
  void liveAndRestoredSessionsMatchAnIndependentListModel(long seed) {
    run(seed, 32);
  }

  static void run(long seed, int steps) {
    var engine = Contracts.create();
    var expected =
        new ArrayList<>(List.of(BigInteger.TEN, BigInteger.valueOf(20), BigInteger.valueOf(30)));
    var random = new Random(seed);
    try (var session = engine.openYaml(SOURCE)) {
      session.initialize().requireApplied();
      for (int step = 1; step <= steps; step++) {
        var before = session.document().blueId();
        var candidate = new ArrayList<>(expected);
        var changes = new ArrayList<Map<String, Object>>();
        int edits = 1 + random.nextInt(3);
        for (int i = 0; i < edits; i++) changes.add(edit(candidate, random));
        boolean invalid = step % 4 == 0;
        if (invalid) {
          String index =
              List.of("01", "-1", "2147483648", "99999999999999999999", "x").get((step / 4) % 5);
          changes.add(Map.of("op", "replace", "path", "/amounts/" + index, "val", 0));
        }
        var request = Map.of("changes", changes);
        try (var restored = engine.restore(session.snapshot(), session.identity())) {
          var result = session.call("input", "edit", request, step);
          var replay = restored.call("input", "edit", request, step);
          String context = "seed=" + seed + ", step=" + step + ", changes=" + changes;
          assertEquals(result.receipt(), replay.receipt(), context);
          assertEquals(result.gas(), replay.gas(), context);
          assertEquals(session.document().blueId(), restored.document().blueId(), context);
          if (invalid) {
            assertFalse(result.applied(), context);
            assertEquals(
                "INVALID_LIST_INDEX",
                ((Map<?, ?>) result.observations().get("diagnostic")).get("code"),
                context);
            assertEquals(before, session.document().blueId(), context);
          } else {
            result.requireApplied();
            expected = candidate;
          }
          assertEquals(expected, session.read("/amounts"), context);
        }
      }
    }
  }

  private static Map<String, Object> edit(List<BigInteger> model, Random random) {
    int choice = model.isEmpty() ? 0 : random.nextInt(3);
    int index = random.nextInt(model.size() + (choice == 0 ? 1 : 0));
    String path = "/amounts/" + index;
    if (choice == 2) {
      model.remove(index);
      return Map.of("op", "remove", "path", path);
    }
    BigInteger amount = BigInteger.valueOf(random.nextInt(10000));
    if (choice == 0) {
      if (index == model.size() && random.nextBoolean()) path = "/amounts/-";
      model.add(index, amount);
      return Map.of("op", "add", "path", path, "val", amount);
    }
    model.set(index, amount);
    return Map.of("op", "replace", "path", path, "val", amount);
  }
}
