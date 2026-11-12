package blue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The public Bex configuration surface: builders, compilation entry points and intrinsics. */
@Tag("canonical-bex")
class BexConfigurationTest {
  private static final BlueId DOUBLE_IT =
      new BlueId("4vJ9JU1bJJE96FWSJKvHsmmFADCg4gpZQff4P3bkLKi1");

  private static Object value(Bex.Program program) {
    return program.execute().requireSuccess().value();
  }

  @Nested
  class Builder {
    @Test
    void aNegativeGasLimitIsRejectedAtConfiguration() {
      assertThrows(IllegalArgumentException.class, () -> Bex.newBuilder().gasLimit(-1));
    }

    @Test
    void aZeroGasLimitRejectsTheFirstCharge() {
      Bex.Result result =
          Bex.newBuilder().gasLimit(0).build().compileYaml("expr: {$add: [1, 2]}").execute();
      assertEquals("GAS_LIMIT_EXCEEDED", result.failure());
      assertEquals(0, result.gas());
      assertNotNull(result.rejected());
    }

    @Test
    void nullConfigurationIsRejectedImmediately() {
      assertThrows(NullPointerException.class, () -> Bex.newBuilder().language(null));
      assertThrows(NullPointerException.class, () -> Bex.newBuilder().intrinsic(DOUBLE_IT, null));
      assertThrows(
          NullPointerException.class, () -> Bex.newBuilder().intrinsic(null, (payload, gas) -> 1));
    }

    @Test
    void buildersAreReusableAndBuiltEvaluatorsDoNotObserveLaterChanges() {
      Bex.Builder builder = Bex.newBuilder().gasLimit(1);
      Bex strict = builder.build();
      Bex relaxed = builder.gasLimit(10_000).build();
      String source = "expr: {$add: [1, 2]}";
      assertEquals("GAS_LIMIT_EXCEEDED", strict.compileYaml(source).execute().failure());
      assertEquals(BigInteger.valueOf(3), value(relaxed.compileYaml(source)));
    }

    @Test
    void theSelectedLanguageSuppliesTypeAliases() {
      Language language = Language.create();
      Bex bex = Bex.newBuilder().language(language).build();
      assertEquals(
          true, value(bex.compileYaml("expr: {$is: {node: 42, pattern: {type: Integer}}}")));
    }
  }

  @Nested
  class Compilation {
    @Test
    void yamlJsonAndDataFormsCompileTheSameProgram() {
      Bex bex = Bex.create();
      Object fromYaml = value(bex.compileYaml("expr: {$add: [2, 3]}"));
      Object fromJson = value(bex.compileJson("{\"expr\": {\"$add\": [2, 3]}}"));
      Object fromData = value(bex.compile(Map.of("expr", Map.of("$add", List.of(2, 3)))));
      assertEquals(BigInteger.valueOf(5), fromYaml);
      assertEquals(fromYaml, fromJson);
      assertEquals(fromYaml, fromData);
    }

    @Test
    void sourceDataIsCopiedAtCompilation() {
      List<Object> operands = new ArrayList<>(List.of(2, 3));
      Map<String, Object> source = new HashMap<>(Map.of("expr", Map.of("$add", operands)));
      Bex.Program program = Bex.create().compile(source);
      operands.set(0, 40);
      source.clear();
      assertEquals(BigInteger.valueOf(5), value(program));
    }

    @Test
    void malformedSourceIsACompileFailureNotAnExecutionResult() {
      Bex bex = Bex.create();
      String unknownOperator = "{\"expr\": {\"$nope\": 1}}";
      BlueException failure =
          assertThrows(BlueException.class, () -> bex.compileJson(unknownOperator));
      assertEquals("BEX_COMPILE_ERROR", failure.code());
      assertThrows(BlueException.class, () -> bex.compileJson("{not json"));
    }

    @Test
    void operatorsListsTheStaticInventorySortedWithoutDuplicates() {
      String source = "expr: {$add: [{$add: [1, 2]}, {$multiply: [2, {$event: /n}]}]}";
      Bex.Program program = Bex.create().compileYaml(source);
      assertEquals(List.of("$add", "$event", "$multiply"), program.operators());
      assertThrows(UnsupportedOperationException.class, () -> program.operators().add("$x"));
    }

    @Test
    void sharedDefinitionsSupplyFunctionsAndTheSelectedEntry() {
      Map<String, Object> shared =
          Map.of("functions", Map.of("two", Map.of("expr", 2), "three", Map.of("expr", 3)));
      Bex bex = Bex.create();
      assertEquals(BigInteger.valueOf(2), value(bex.compile(Map.of(), shared, "two")));
      assertEquals(BigInteger.valueOf(3), value(bex.compile(Map.of(), shared, "three")));
    }

    @Test
    void localDefinitionsWinOverSharedOnesAndAreReported() {
      Map<String, Object> shared = Map.of("functions", Map.of("answer", Map.of("expr", 1)));
      Map<String, Object> local =
          Map.of("functions", Map.of("answer", Map.of("expr", 42)), "entry", "answer");
      Bex.Program program = Bex.create().compile(local, shared, null);
      assertEquals(BigInteger.valueOf(42), value(program));
      assertEquals(1, program.warnings().size());
      assertTrue(program.warnings().get(0).contains("answer"), program.warnings().toString());
      assertTrue(Bex.create().compile(Map.of(), shared, "answer").warnings().isEmpty());
    }
  }

  @Nested
  class Intrinsics {
    private Bex doubling(long hostCharge) {
      return Bex.newBuilder()
          .intrinsic(
              DOUBLE_IT,
              (payload, gas) -> {
                gas.charge(hostCharge);
                return ((BigInteger) payload.get("of")).shiftLeft(1);
              })
          .build();
    }

    private String call(String operand) {
      return "expr: {$intrinsic: {type: {blueId: " + DOUBLE_IT + "}, of: " + operand + "}}";
    }

    @Test
    void anInstalledIntrinsicReceivesEvaluatedFieldsWithoutItsType() {
      List<Map<String, Object>> seen = new ArrayList<>();
      Bex bex =
          Bex.newBuilder()
              .intrinsic(
                  DOUBLE_IT,
                  (payload, gas) -> {
                    seen.add(payload);
                    return payload.get("of");
                  })
              .build();
      assertEquals(BigInteger.valueOf(5), value(bex.compileYaml(call("{$add: [2, 3]}"))));
      assertEquals(List.of(Map.of("of", BigInteger.valueOf(5))), seen);
    }

    @Test
    void hostChargesAreAddedToTheInvocationGas() {
      long free = doubling(0).compileYaml(call("21")).execute().requireSuccess().gas();
      Bex.Result charged = doubling(1_000).compileYaml(call("21")).execute().requireSuccess();
      assertEquals(BigInteger.valueOf(42), charged.value());
      assertEquals(free + 1_000, charged.gas());
    }

    @Test
    void aHostChargeBeyondTheLimitFailsBeforeTheWorkIsAdmitted() {
      Bex bex =
          Bex.newBuilder()
              .gasLimit(500)
              .intrinsic(
                  DOUBLE_IT,
                  (payload, gas) -> {
                    gas.charge(1_000);
                    throw new AssertionError("work ran after a rejected charge");
                  })
              .build();
      Bex.Result result = bex.compileYaml(call("21")).execute();
      assertEquals("GAS_LIMIT_EXCEEDED", result.failure());
      assertTrue(result.isUndefined());
      assertNotNull(result.rejected());
      assertTrue(result.gas() <= 500);
    }

    @Test
    void anIntrinsicMayNotReturnJavaNull() {
      Bex bex = Bex.newBuilder().intrinsic(DOUBLE_IT, (payload, gas) -> null).build();
      Bex.Result result = bex.compileYaml(call("1")).execute();
      assertEquals("BEX_RUNTIME_ERROR", result.failure());
      assertNull(result.rejected());
    }

    @Test
    void registeringTheSameTypeTwiceIsRejected() {
      Bex.Builder builder = Bex.newBuilder().intrinsic(DOUBLE_IT, (payload, gas) -> 1);
      assertThrows(
          IllegalArgumentException.class, () -> builder.intrinsic(DOUBLE_IT, (payload, gas) -> 2));
    }

    @Test
    void aProgramNamingAnUninstalledTypeDoesNotCompile() {
      BlueException failure =
          assertThrows(BlueException.class, () -> Bex.create().compileYaml(call("1")));
      assertEquals("BEX_COMPILE_ERROR", failure.code());
    }
  }

  @Nested
  class Inputs {
    private static final String READS =
        """
        expr:
          $list:
            - {$currentContract: /name}
            - {$steps: {step: first, path: /out}}
            - {$binding: {name: account, path: /id}}
            - {$document: {path: /total, view: resolved}}
            - {$document: total}
        """;

    @Test
    void everyDeclaredInputIsReadableByItsOperator() {
      Bex.Input input =
          Bex.inputs()
              .document(Map.of("orders", Map.of("total", 7)))
              .resolvedDocument(Map.of("total", 9))
              .currentContract(Map.of("name", "increment"))
              .steps(Map.of("first", Map.of("out", "done")))
              .bindings(Map.of("account", Map.of("id", "a-1")))
              .scope("/orders")
              .build();
      Bex.Result result = Bex.create().compileYaml(READS).execute(input).requireSuccess();
      assertEquals(
          List.of("increment", "done", "a-1", BigInteger.valueOf(9), BigInteger.valueOf(7)),
          result.value());
    }

    @Test
    void anInputIsASnapshotOfTheBuilderAtBuildTime() {
      Map<String, Object> steps = new HashMap<>(Map.of("first", Map.of("out", "before")));
      Bex.Inputs builder = Bex.inputs().steps(steps);
      Bex.Input input = builder.build();
      steps.put("first", Map.of("out", "after"));
      Bex.Program program = Bex.create().compileYaml("expr: {$steps: {step: first, path: /out}}");
      assertEquals("before", program.execute(input).requireSuccess().value());
      assertThrows(NullPointerException.class, () -> program.execute(null));
      assertThrows(NullPointerException.class, () -> Bex.inputs().scope(null));
    }
  }

  @Test
  void theEvaluatorIdentifiesItsRulesAndPublication() {
    BluePackage publication = Bex.publication();
    assertNotNull(publication);
    assertEquals(Bex.create().rulesId(), Bex.newBuilder().gasLimit(5).build().rulesId());
    assertNotNull(Bex.create().rulesId().value());
  }

  @Test
  void aResultRejectsNegativeGasAndCopiesItsCollections() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new Bex.Result(1, List.of(), List.of(), -1, null, null, null));
    List<Object> events = new ArrayList<>(List.of("first"));
    Bex.Result result = new Bex.Result(1, List.of(), events, 0, null, null, null);
    events.add("second");
    assertEquals(List.of("first"), result.events());
    assertTrue(result.succeeded());
  }
}
