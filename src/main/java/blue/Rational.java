package blue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;

record Rational(BigInteger numerator, BigInteger denominator) implements Comparable<Rational> {
  Rational {
    if (denominator.signum() == 0) {
      throw new ArithmeticException("zero denominator");
    }
    if (denominator.signum() < 0) {
      numerator = numerator.negate();
      denominator = denominator.negate();
    }
    BigInteger gcd = numerator.gcd(denominator);
    numerator = numerator.divide(gcd);
    denominator = denominator.divide(gcd);
  }

  static Rational of(Object n) {
    BigDecimal d =
        n instanceof Double v
            ? new BigDecimal(v)
            : n instanceof BigDecimal v ? v : new BigDecimal(n.toString());
    return d.scale() < 0
        ? new Rational(d.unscaledValue().multiply(BigInteger.TEN.pow(-d.scale())), BigInteger.ONE)
        : new Rational(d.unscaledValue(), BigInteger.TEN.pow(d.scale()));
  }

  Rational divide(Rational other) {
    return new Rational(
        numerator.multiply(other.denominator), denominator.multiply(other.numerator));
  }

  Rational lcm(Rational other) {
    return new Rational(
        numerator.divide(numerator.gcd(other.numerator)).multiply(other.numerator).abs(),
        denominator.gcd(other.denominator));
  }

  boolean integral() {
    return denominator.equals(BigInteger.ONE);
  }

  @Override
  public int compareTo(Rational other) {
    return numerator.multiply(other.denominator).compareTo(other.numerator.multiply(denominator));
  }

  Map<String, Object> describe() {
    return Map.of("numerator", numerator.toString(), "denominator", denominator.toString());
  }
}
