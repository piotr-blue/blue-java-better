package blue;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.Set;

/** Bounded syntax checks, independent of content availability, permissions and execution gas. */
final class BlueIdSyntax {
  private static final String ALPHABET =
      "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
  private static final boolean[] DIGITS = new boolean[128];
  private static final String[] MINIMUM = new String[32];
  private static final String[] MAXIMUM = new String[32];

  static {
    for (int i = 0; i < ALPHABET.length(); i++) {
      DIGITS[ALPHABET.charAt(i)] = true;
    }
    for (int zeros = 0; zeros < 32; zeros++) {
      MINIMUM[zeros] = bound(zeros, BigInteger.ONE.shiftLeft(8 * (31 - zeros)));
      MAXIMUM[zeros] =
          bound(zeros, BigInteger.ONE.shiftLeft(8 * (32 - zeros)).subtract(BigInteger.ONE));
    }
  }

  private static final ThreadLocal<Set<String>> VALID = ThreadLocal.withInitial(HashSet::new);

  private BlueIdSyntax() {}

  static boolean digest(String value) {
    if (value.length() < 32 || value.length() > 44) {
      return false;
    }
    Set<String> valid = VALID.get();
    if (valid.contains(value)) {
      return true;
    }
    int zeros = 0;
    while (zeros < value.length() && value.charAt(zeros) == '1') {
      zeros++;
    }
    if (zeros == value.length()) {
      return zeros == 32;
    }
    if (zeros >= 32) {
      return false;
    }
    for (int i = zeros; i < value.length(); i++) {
      char digit = value.charAt(i);
      if (digit >= DIGITS.length || !DIGITS[digit]) {
        return false;
      }
    }
    // Valid base58 digits sort in numeric order. Comparing with the encoded byte-width
    // bounds avoids decoding every new digest into eight integer limbs. Leading zero
    // bytes remain part of the bounds, so short numbers cannot masquerade as digests.
    if (compare(value, MINIMUM[zeros]) < 0 || compare(value, MAXIMUM[zeros]) > 0) {
      return false;
    }
    if (valid.size() >= 8192) {
      valid.clear();
    }
    valid.add(value);
    return true;
  }

  private static int compare(String value, String bound) {
    int lengths = Integer.compare(value.length(), bound.length());
    return lengths == 0 ? value.compareTo(bound) : lengths;
  }

  private static String bound(int zeros, BigInteger value) {
    StringBuilder digits = new StringBuilder();
    while (value.signum() != 0) {
      var step = value.divideAndRemainder(BigInteger.valueOf(58));
      digits.append(ALPHABET.charAt(step[1].intValue()));
      value = step[0];
    }
    return "1".repeat(zeros) + digits.reverse();
  }

  static boolean member(String value, int start) {
    if (start >= value.length()) {
      return false;
    }
    if (value.charAt(start) == '0') {
      return start + 1 == value.length();
    }
    for (int i = start; i < value.length(); i++) {
      char digit = value.charAt(i);
      if (digit < '0' || digit > '9') {
        return false;
      }
    }
    return true;
  }
}
