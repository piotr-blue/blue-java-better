package blue;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Sharing compiled code must not share dynamic definitions, admission decisions or document data.
 */
@Tag("contracts")
class ContractProgramsTest {
  @Test
  @SuppressWarnings("unchecked")
  void definitionValuesOwnAnImmutableSnapshotAndDoNotRetainInvalidShapes() {
    ContractPrograms programs = new ContractPrograms();
    var input = new java.util.HashMap<String, Object>();
    var values = new ArrayList<>(java.util.List.of(1, 2));
    input.put("values", values);
    Object frozen = programs.definitionValue("constants", () -> input);
    values.set(0, 99);
    input.clear();
    assertSame(
        frozen,
        programs.definitionValue(
            "constants",
            () -> {
              throw new AssertionError("Projection should be reused");
            }));
    var snapshot = (Map<String, Object>) frozen;
    assertEquals(java.util.List.of(BigInteger.ONE, BigInteger.TWO), snapshot.get("values"));
    assertThrows(UnsupportedOperationException.class, () -> snapshot.put("new", 0));
    assertThrows(
        UnsupportedOperationException.class,
        () -> ((java.util.List<Object>) snapshot.get("values")).set(0, 0));
    AtomicInteger invalid = new AtomicInteger();
    for (int i = 0; i < 2; i++)
      assertEquals(
          "scalar",
          programs.definitionValue(
              "invalid",
              () -> {
                invalid.incrementAndGet();
                return "scalar";
              }));
    assertEquals(2, invalid.get());
  }

  @Test
  void warmDefinitionsPreserveInvalidFieldDiagnosticsAndExactFailureGas() {
    Contracts runtime = Contracts.create();
    try (Contracts.Session warm = runtime.openYaml(source(0, 1, true))) {
      warm.initialize().requireApplied();
      call(warm);
    }
    String invalid = source(0, 1, true).replace("logic:\n", "logic:\n  constants: [1, 2]\n");
    try (Contracts.Session warm = runtime.openYaml(invalid);
        Contracts.Session cold = Contracts.create().openYaml(invalid)) {
      warm.initialize().requireApplied();
      cold.initialize().requireApplied();
      String before = warm.identity();
      Contracts.Transition actual = warm.call("input", "run", Map.of(), 1);
      Contracts.Transition expected = cold.call("input", "run", Map.of(), 1);
      assertFalse(actual.applied());
      assertEquals(
          "INVALID_COMPUTE_DEFINITION",
          ((Map<?, ?>) actual.observations().get("diagnostic")).get("code"));
      assertEquals(expected.receipt(), actual.receipt());
      assertEquals(expected.gas(), actual.gas());
      assertEquals(before, warm.identity());
    }
  }

  @Test
  void immutablePlansAreReusedOnlyForTheSameStepAndSelectedDefinition() {
    ContractPrograms programs = new ContractPrograms();
    AtomicInteger compilations = new AtomicInteger();
    java.util.function.Supplier<BexPlanCompiler.Program> compiler =
        () -> {
          compilations.incrementAndGet();
          return new BexEngine().compile(Map.of("expr", 1));
        };
    BexPlanCompiler.Program first = programs.compile("step", "definition", compiler);
    assertSame(first, programs.compile("step", "definition", compiler));
    assertNotSame(first, programs.compile("other-step", "definition", compiler));
    assertNotSame(first, programs.compile("step", "other-definition", compiler));
    assertEquals(3, compilations.get());
    assertThrows(UnsupportedOperationException.class, () -> first.source().put("expr", 2));
  }

  @Test
  void distinctDefinitionsEvictOldPlansWithoutInvalidatingAlreadyReturnedCode() {
    ContractPrograms programs = new ContractPrograms();
    java.util.function.Supplier<BexPlanCompiler.Program> compiler =
        () -> new BexEngine().compile(Map.of("expr", 1));
    BexPlanCompiler.Program original = programs.compile("first", "", compiler);
    // One more distinct key than the configured 512-entry bound forces actual eviction.
    for (int i = 0; i < 512; i++) programs.compile("step-" + i, "", compiler);
    assertNotSame(original, programs.compile("first", "", compiler));
    assertEquals(BigInteger.ONE, original.source().get("expr"));
  }

  @Test
  void oversizedProgramsAndFailedCompilationsAreNotRetained() {
    ContractPrograms programs = new ContractPrograms();
    String large = "x".repeat(1024 * 1024 + 1);
    var first = programs.compile("large", "", () -> new BexEngine().compile(Map.of("expr", large)));
    var second =
        programs.compile("large", "", () -> new BexEngine().compile(Map.of("expr", large)));
    assertNotSame(first, second);
    AtomicInteger attempts = new AtomicInteger();
    for (int i = 0; i < 2; i++) {
      assertThrows(
          BexFailure.class,
          () ->
              programs.compile(
                  "invalid",
                  "",
                  () -> {
                    attempts.incrementAndGet();
                    return new BexEngine().compile(Map.of("expr", Map.of("$unknown", 1)));
                  }));
    }
    assertEquals(2, attempts.get());
  }

  @Test
  void sharingPlansNeverBypassesTheCurrentDefinitionOrMissingDefinitionCheck() {
    Contracts runtime = Contracts.create();
    try (Contracts.Session warm = runtime.openYaml(source(0, 1, true))) {
      warm.initialize().requireApplied();
      call(warm);
      assertEquals(BigInteger.ONE, warm.read("/count"));
    }
    try (Contracts.Session different = runtime.openYaml(source(0, 9, true));
        Contracts.Session cold = Contracts.create().openYaml(source(0, 9, true))) {
      different.initialize().requireApplied();
      cold.initialize().requireApplied();
      Contracts.Transition actual = call(different), expected = call(cold);
      assertEquals(expected.receipt(), actual.receipt());
      assertEquals(expected.gas(), actual.gas());
      assertEquals(BigInteger.valueOf(9), different.read("/count"));
    }
    try (Contracts.Session absent = runtime.openYaml(source(0, 0, false))) {
      absent.initialize().requireApplied();
      String before = absent.identity();
      Contracts.Transition failed = absent.call("input", "run", Map.of(), 1);
      assertFalse(failed.applied());
      assertEquals(
          "MISSING_COMPUTE_DEFINITION",
          ((Map<?, ?>) failed.observations().get("diagnostic")).get("code"));
      assertEquals(before, absent.identity());
    }
  }

  @Test
  @Tag("concurrency")
  void independentSessionsExecuteSharedCodeAgainstTheirOwnValues() throws Exception {
    Contracts runtime = Contracts.create();
    try (var threads = Executors.newFixedThreadPool(4)) {
      var futures = new ArrayList<java.util.concurrent.Future<BigInteger>>();
      for (int i = 0; i < 8; i++) {
        int initial = i;
        futures.add(
            threads.submit(
                () -> {
                  try (Contracts.Session session = runtime.openYaml(source(initial, 7, true))) {
                    session.initialize().requireApplied();
                    call(session);
                    return (BigInteger) session.read("/count");
                  }
                }));
      }
      for (int i = 0; i < futures.size(); i++)
        assertEquals(BigInteger.valueOf(i + 7), futures.get(i).get());
    }
  }

  private static Contracts.Transition call(Contracts.Session session) {
    return session.call("input", "run", Map.of(), 1).requireApplied();
  }

  private static String source(int count, int increment, boolean definition) {
    String logic =
        definition
            ? """
            logic:
              functions:
                apply:
                  do:
                    - $appendChange:
                        op: replace
                        path: /count
                        val: {$add: [{$document: /count}, %d]}
            """
                .formatted(increment)
            : "";
    return "count: "
        + count
        + "\n"
        + logic
        + """
        contracts:
          input: {type: Contracts/External Channel}
          run:
            type: Contracts/Sequential Workflow Operation
            channel: input
            steps:
              - type: Contracts/Compute
                definition: /logic
                entry: apply
        """;
  }
}
