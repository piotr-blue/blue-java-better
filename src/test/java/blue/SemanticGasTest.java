package blue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Kernel lowering: primitive outcomes, first-failure prefixes and bounded iterative execution. */
@Tag("bex")
class SemanticGasTest {
  static ContractModel.Meter meter(long limit) {
    var meter = new ContractModel.Meter(limit, Map.of(), null, false);
    meter.schedule = GasSchedule.STANDARD;
    meter.owner = "test";
    return meter;
  }

  static BexEngine.Result run(Object expr, BexGas meter) {
    var context = new BexEngine.Context();
    var processor = new ContractProcessor();
    context.matcher =
        (actual, pattern) ->
            processor.blue.matches(
                processor.blue.source(actual), processor.blue.source(pattern, "definition"), null);
    return new BexEngine().execute(Map.of("expr", expr), context, meter);
  }

  @Test
  void additionHasTheSamePriceInStandaloneBexAndContracts() {
    var expression = Map.of("$add", List.of(2, 3));
    var meter = meter(6);
    assertEquals(BigInteger.valueOf(5), run(expression, meter).value());
    assertEquals(6, meter.used());
    var limited = meter(5);
    assertEquals(
        "GAS_LIMIT_EXCEEDED",
        assertThrows(BexFailure.class, () -> run(expression, limited)).code());
    assertEquals(5, limited.used());
    assertEquals("integerWork", limited.rejected().get("counter"));
  }

  @Test
  void independentSmallExpressionReferenceSelectsChargesAndValues() {
    // The oracle walks expressions independently, rather than summing a supplied charge list.
    Random random = new Random(814);
    for (int sample = 0; sample < 80; sample++) {
      Object expression = tree(random, 4);
      for (int limit = 0; limit < 90; limit++) {
        var expected = new Reference(limit);
        Object value = null;
        try {
          expected.pay("functionCall", 2);
          value = expected.eval(expression);
        } catch (Exhausted ignored) {
        }
        var actual = meter(limit);
        Object result = null;
        try {
          result = run(expression, actual).value();
        } catch (BexFailure failure) {
          assertEquals("GAS_LIMIT_EXCEEDED", failure.code());
        }
        assertEquals(expected.used, actual.used(), "budget " + limit);
        assertEquals(
            expected.rejected, actual.rejected() == null ? null : actual.rejected().get("counter"));
        if (expected.rejected == null) {
          assertEquals(value, result);
        }
      }
    }
  }

  @Test
  void textPredicatesRetainTheFullBexTariffAndFirstFailure() {
    Object expression = Map.of("$startsWith", List.of("abx", "aby"));
    assertEquals(false, run(expression, meter(6)).value());
    var limited = meter(5);
    assertThrows(BexFailure.class, () -> run(expression, limited));
    assertEquals(5, limited.used());
    assertEquals("textWork", limited.rejected().get("counter"));
    assertEquals(6, run(Map.of("$startsWith", List.of("é", "é")), meter(100)).gasUsed());
  }

  @Test
  void matchingAndRejectedPrimitiveObligationsAreBothCharged() {
    for (String type : List.of("Integer", "Text")) {
      var meter = meter(100);
      var result = run(Map.of("$is", Map.of("node", 13, "pattern", Map.of("type", type))), meter);
      assertEquals(type.equals("Integer"), result.value());
      assertEquals(4, meter.used());
    }
  }

  @Test
  void deepOperandsUseHeapContinuationsAndUnselectedBranchesAreLazy() {
    Object expression = true;
    for (int i = 0; i < 20_000; i++) {
      expression = Map.of("$not", expression);
    }
    assertEquals(true, run(expression, meter(20_004)).value());
    Object lazy =
        Map.of(
            "$choose", Map.of("cond", true, "then", 7, "else", Map.of("$divide", List.of(1, 0))));
    assertEquals(BigInteger.valueOf(7), run(lazy, meter(6)).value());
  }

  @Test
  void deepStatementsAndValueComparisonsDoNotUseTheJavaCallStack() {
    Object statement = Map.of("$return", 7);
    for (int i = 0; i < 5000; i++) {
      statement = Map.of("$if", Map.of("cond", true, "then", List.of(statement)));
    }
    assertEquals(
        BigInteger.valueOf(7),
        new BexEngine()
            .execute(Map.of("do", List.of(statement)), new BexEngine.Context(), meter(20_000))
            .value());
    Object a = 1, b = 1;
    for (int i = 0; i < 20_000; i++) {
      a = List.of(a);
      b = List.of(b);
    }
    assertTrue(BexValues.equal(a, b, meter(25_000)));
    Object unequal = 2;
    for (int i = 0; i < 20_000; i++) {
      unequal = List.of(unequal);
    }
    assertFalse(BexValues.equal(a, unequal, meter(25_000)));
  }

  @Test
  void deepPointerWritesUseTheHeapAndLeaveTheirInputUnchanged() {
    Map<String, Object> input = Map.of("untouched", BigInteger.TEN);
    String path = "/x".repeat(12_000);
    var gas = meter(100_000);
    Object output = BexValues.overlay(input, path, BigInteger.ONE, false, gas);
    for (int i = 0; i < 12_000; i++) {
      output = BexValues.get(output, "x");
    }
    assertEquals(BigInteger.ONE, output);
    assertEquals(Map.of("untouched", BigInteger.TEN), input);
  }

  @Test
  void newContractsCountersMustBeClassifiedRatherThanSilentlyFree() {
    assertThrows(
        IllegalArgumentException.class,
        () -> SemanticGas.contracts(meter(100), "futureBusinessAction", 1));
  }

  @Test
  void ceilingAndExactWideCandidateCannotWrap() {
    assertThrows(ContractModel.Error.class, () -> meter(10_000_001));
    var meter = meter(10_000_000);
    BigInteger candidate = BigInteger.ONE.shiftLeft(100);
    assertThrows(BexFailure.class, () -> SemanticGas.integer(meter, candidate));
    assertEquals(candidate, meter.rejected().get("amount"));
    assertEquals(0, meter.used());
  }

  @Test
  void fullLanguageOperatorsAreAvailableWithoutSelectingAnotherPolicy() {
    assertEquals("123", run(Map.of("$text", 123), meter(100)).value());
    assertEquals(1, GasSchedule.values().length);
  }

  @Test
  void selectedReadDoesNotBindUnrelatedDocumentFields() {
    var context = new BexEngine.Context();
    context.document = Map.of("count", BigInteger.ONE, "unrelated", "large body");
    var reads = new ArrayList<BexEngine.InputRead>();
    context.inputObserver =
        (read, action) -> {
          reads.add(read);
          return action.get();
        };
    var result =
        new BexEngine().execute(Map.of("expr", Map.of("$document", "/count")), context, meter(100));
    assertEquals(BigInteger.ONE, result.value());
    assertEquals(List.of("/count"), reads.stream().map(BexEngine.InputRead::path).toList());
    assertEquals(5, result.gasUsed());
  }

  @Test
  void nativeCapabilitiesMustUseTheirDeclaredTariff() {
    var binding =
        new ContractNative.Binding(
            LanguageCore.CORE.get("Text"),
            LanguageCore.CORE.get("Integer"),
            "external-channel",
            List.of(),
            List.of(),
            "declared",
            Map.of("check", 3L),
            false,
            List.of(),
            "live");
    var meter = meter(100);
    meter.semantic = true;
    var runtime = new ContractNative.RuntimeMeter(meter, binding, "test");
    runtime.charge("check", 2);
    assertEquals(6, meter.used());
    assertThrows(ContractModel.Error.class, () -> runtime.charge("unpriced", 1));
  }

  private static Object tree(Random random, int depth) {
    if (depth == 0 || random.nextInt(3) == 0) {
      return random.nextInt(20);
    }
    return Map.of(
        random.nextBoolean() ? "$add" : "$multiply",
        List.of(tree(random, depth - 1), tree(random, depth - 1)));
  }

  @SuppressWarnings("serial")
  private static final class Exhausted extends RuntimeException {}

  private static final class Reference {
    final long limit;
    long used;
    String rejected;

    Reference(long limit) {
      this.limit = limit;
    }

    void pay(String action, long cost) {
      if (cost > limit - used) {
        rejected = action;
        throw new Exhausted();
      }
      used += cost;
    }

    BigInteger eval(Object expression) {
      pay("expressionBase", 1);
      if (expression instanceof Integer number) {
        return BigInteger.valueOf(number);
      }
      Map<?, ?> map = (Map<?, ?>) expression;
      String operator = (String) map.keySet().iterator().next();
      List<?> values = (List<?>) map.get(operator);
      BigInteger left = eval(values.get(0)), right = eval(values.get(1));
      long a = Math.max(1, (left.abs().bitLength() + 31) / 32);
      long b = Math.max(1, (right.abs().bitLength() + 31) / 32);
      pay("integerWork", operator.equals("$add") ? Math.max(a, b) : a * b);
      return operator.equals("$add") ? left.add(right) : left.multiply(right);
    }
  }
}
