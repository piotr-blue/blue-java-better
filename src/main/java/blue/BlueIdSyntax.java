package blue;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.Set;

/** Base58 representation and syntax of a Blue digest, independent of content and execution gas. */
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

  /** Encode exactly one SHA-256 digest, preserving its leading zero bytes. */
  static String encode(byte[] bytes) {
    if (bytes.length != 32) {
      throw new IllegalArgumentException("A BlueId digest requires 32 bytes");
    }
    // Eight unsigned words divided by 58^5 emit five digits per pass. The largest
    // intermediate is (58^5 - 1) * 2^32 + (2^32 - 1), safely below Long.MAX_VALUE.
    final long radix = 656356768L;
    int[] words = new int[8];
    for (int i = 0; i < words.length; i++) {
      int p = i * 4;
      words[i] =
          (bytes[p] & 255) << 24
              | (bytes[p + 1] & 255) << 16
              | (bytes[p + 2] & 255) << 8
              | bytes[p + 3] & 255;
    }
    int zeros = 0;
    while (zeros < bytes.length && bytes[zeros] == 0) {
      zeros++;
    }
    char[] out = new char[64];
    int position = out.length, start = 0;
    while (start < words.length && words[start] == 0) {
      start++;
    }
    while (start < words.length) {
      long remainder = 0;
      for (int i = start; i < words.length; i++) {
        long value = (remainder << 32) | Integer.toUnsignedLong(words[i]);
        words[i] = (int) (value / radix);
        remainder = value % radix;
      }
      while (start < words.length && words[start] == 0) {
        start++;
      }
      for (int digit = 0; digit < 5 && (start < words.length || remainder != 0); digit++) {
        out[--position] = ALPHABET.charAt((int) (remainder % 58));
        remainder /= 58;
      }
    }
    while (zeros-- > 0) {
      out[--position] = '1';
    }
    return new String(out, position, out.length - position);
  }

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
