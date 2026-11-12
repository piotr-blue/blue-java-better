package blue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Numeric representation, ordering and charge boundaries are independent of conversion reuse. */
@Tag("canonical-bex")
@Tag("contracts")
class BexNumbersTest {
  @Nested
  class Conversion {
    @ParameterizedTest
    @MethodSource("blue.BexNumbersTest#integers")
    void integerRepresentationsRetainExactMagnitude(Object value) {
      assertEquals(BexValues.number(value).toBigIntegerExact(), BexValues.integer(value));
    }

    @Test
    void canonicalTextAndOtherNumericRepresentationsKeepTheirRules() {
      for (Object value : List.of("0", "-7", new BigDecimal("42.000"), 42.0, -0.0, 42.0f)) {
        assertEquals(BexValues.number(value).toBigIntegerExact(), BexValues.integer(value));
      }
      for (Object invalid : List.of("01", "+1", "1.0", " 1", "1e2", "-0")) {
        assertThrows(BexFailure.class, () -> BexValues.integer(invalid), invalid.toString());
      }
      for (Object invalid :
          List.of(
              new BigDecimal("1.2"),
              1.5,
              true,
              List.of(),
              Map.of(),
              Double.NaN,
              Double.POSITIVE_INFINITY)) {
        assertThrows(BexFailure.class, () -> BexValues.integer(invalid), invalid.toString());
      }
    }
  }

  @Nested
  class ContractOrdering {
    @Test
    void mixedRepresentationsKeepTheirOrderAndMetering() {
      var values = new java.util.ArrayList<Object>(integers().toList());
      values.addAll(List.of(new BigDecimal("1.25"), new BigDecimal("42.000"), -0.0, 1.5, "2"));
      for (Object left : values) {
        for (Object right : values) {
          ContractModel.Meter expected = new ContractModel.Meter(100_000);
          ContractModel.Meter actual = new ContractModel.Meter(100_000);
          int comparison = reference(left, right, expected);
          assertEquals(
              Integer.signum(comparison),
              Integer.signum(new ContractWork(null, actual).compare(left, right)));
          assertEquals(expected.trace, actual.trace);
          assertEquals(expected.used, actual.used);
        }
      }
    }

    @Test
    void sharedAndLocalBudgetsRejectTheSameFirstUnaffordableCharge() {
      Object left = BigInteger.ONE.shiftLeft(130).negate(), right = Long.MAX_VALUE;
      ContractModel.Meter full = new ContractModel.Meter(100_000);
      reference(left, right, full);
      long scalar = ((Number) ContractModel.TARIFF.get("scalarComparison")).longValue();
      var limits =
          new TreeSet<>(
              List.of(
                  0L, scalar - 1, scalar, Math.max(0, full.used - 1), full.used, full.used + 1));
      for (long limit : limits) {
        for (boolean local : List.of(false, true)) {
          for (boolean trace : List.of(false, true)) {
            ContractModel.Meter expected = meter(limit, local, trace),
                actual = meter(limit, local, trace);
            String failure = outcome(() -> reference(left, right, expected));
            assertEquals(
                failure, outcome(() -> new ContractWork(null, actual).compare(left, right)));
            assertEquals(expected.used, actual.used);
            assertEquals(expected.localUsed, actual.localUsed);
            assertEquals(expected.trace, actual.trace);
            assertEquals(expected.rejected, actual.rejected);
          }
        }
      }
    }
  }

  private static Stream<Object> integers() {
    return Stream.of(
        (byte) -128,
        (short) 32767,
        Integer.MIN_VALUE,
        -1,
        0,
        1,
        Integer.MAX_VALUE,
        Long.MIN_VALUE,
        Long.MAX_VALUE,
        BigInteger.ZERO,
        BigInteger.ONE.shiftLeft(53).add(BigInteger.ONE),
        BigInteger.ONE.shiftLeft(1024),
        BigInteger.ONE.shiftLeft(1024).negate());
  }

  private static ContractModel.Meter meter(long limit, boolean local, boolean trace) {
    var meter =
        new ContractModel.Meter(
            local ? 100_000 : limit, local ? Map.of("owner", limit) : Map.of(), null, trace);
    meter.owner = "owner";
    return meter;
  }

  private static String outcome(java.util.function.IntSupplier comparison) {
    try {
      return Integer.toString(Integer.signum(comparison.getAsInt()));
    } catch (BexFailure failure) {
      return failure.code() + ":" + failure.getMessage();
    }
  }

  /** Independent decimal route used before the integer fast path, including its exact charges. */
  private static int reference(Object left, Object right, ContractModel.Meter meter) {
    meter.contracts("scalarComparison");
    if (left instanceof String a && right instanceof String b) {
      return MeteredText.compare(
          a,
          b,
          () -> {
            meter.contracts("textBlockExamined");
            meter.contracts("textBlockExamined");
          });
    }
    if ((left instanceof BigInteger || left instanceof Integer || left instanceof Long)
        && (right instanceof BigInteger || right instanceof Integer || right instanceof Long)) {
      meter.contracts(
          "integerLimbOperation",
          BexValues.limbs(BexValues.number(left).toBigIntegerExact())
              + BexValues.limbs(BexValues.number(right).toBigIntegerExact()));
    }
    return BexValues.number(left).compareTo(BexValues.number(right));
  }
}
