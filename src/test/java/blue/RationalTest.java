package blue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Exact rational arithmetic behind schema bounds and multipleOf checks. */
@Tag("language")
class RationalTest {
  private static Rational ratio(long numerator, long denominator) {
    return new Rational(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator));
  }

  @ParameterizedTest
  @CsvSource({"6,4,3,2", "-6,4,-3,2", "6,-4,-3,2", "-6,-4,3,2", "0,-7,0,1", "5,1,5,1"})
  void valuesAreReducedWithAPositiveDenominator(
      long numerator, long denominator, long reduced, long positive) {
    Rational value = ratio(numerator, denominator);
    assertEquals(BigInteger.valueOf(reduced), value.numerator());
    assertEquals(BigInteger.valueOf(positive), value.denominator());
  }

  @Test
  void equalRatiosAreEqualRegardlessOfRepresentation() {
    assertEquals(ratio(1, 2), ratio(-3, -6));
    assertEquals(ratio(1, 2).hashCode(), ratio(50, 100).hashCode());
  }

  @Test
  void aZeroDenominatorIsRejected() {
    assertThrows(ArithmeticException.class, () -> ratio(1, 0));
    assertThrows(ArithmeticException.class, () -> ratio(1, 2).divide(ratio(0, 5)));
  }

  @Test
  void decimalsConvertExactly() {
    assertEquals(ratio(1, 10), Rational.of(new BigDecimal("0.1")));
    assertEquals(ratio(1, 10), Rational.of(new BigDecimal("0.100")));
    assertEquals(ratio(-5, 4), Rational.of(new BigDecimal("-1.25")));
  }

  @Test
  void aNegativeScaleIsAnInteger() {
    Rational value = Rational.of(new BigDecimal("12E+3"));
    assertEquals(ratio(12_000, 1), value);
    assertTrue(value.integral());
  }

  @Test
  void integersAndTextConvertThroughTheirDecimalForm() {
    assertEquals(ratio(42, 1), Rational.of(42));
    assertEquals(ratio(42, 1), Rational.of(42L));
    assertEquals(ratio(42, 1), Rational.of(BigInteger.valueOf(42)));
    assertEquals(ratio(3, 2), Rational.of("1.5"));
  }

  @Test
  void aDoubleKeepsItsBinaryValueRatherThanItsDecimalPrint() {
    assertEquals(ratio(1, 2), Rational.of(0.5));
    // 0.1 is not representable in binary; the exact value is a dyadic fraction, not 1/10.
    Rational tenth = Rational.of(0.1);
    assertNotEquals(ratio(1, 10), tenth);
    assertEquals(1, tenth.denominator().bitCount());
  }

  @Test
  void divisionIsExact() {
    assertEquals(ratio(3, 1), ratio(3, 4).divide(ratio(1, 4)));
    assertEquals(ratio(-2, 3), ratio(1, 3).divide(ratio(-1, 2)));
    assertTrue(ratio(3, 4).divide(ratio(1, 4)).integral());
    assertFalse(ratio(1, 3).divide(ratio(1, 2)).integral());
  }

  @Test
  void leastCommonMultipleIsTheSmallestPositiveRationalBothDivide() {
    Rational common = ratio(1, 4).lcm(ratio(1, 6));
    assertEquals(ratio(1, 2), common);
    assertTrue(common.divide(ratio(1, 4)).integral());
    assertTrue(common.divide(ratio(1, 6)).integral());
    assertEquals(ratio(6, 1), ratio(2, 1).lcm(ratio(-3, 1)));
    assertEquals(ratio(3, 2), ratio(3, 4).lcm(ratio(1, 2)));
  }

  @Test
  void orderingComparesValuesNotRepresentations() {
    assertTrue(ratio(1, 3).compareTo(ratio(1, 2)) < 0);
    assertTrue(ratio(-1, 2).compareTo(ratio(-1, 3)) < 0);
    assertEquals(0, ratio(2, 4).compareTo(ratio(1, 2)));
    assertTrue(ratio(7, 2).compareTo(ratio(3, 1)) > 0);
  }

  @Test
  void descriptionExposesTheReducedTermsAsText() {
    assertEquals(Map.of("numerator", "-3", "denominator", "2"), ratio(6, -4).describe());
  }
}
