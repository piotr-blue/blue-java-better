package blue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Identity syntax agrees with an independent integer decoder at byte and member boundaries. */
class BlueIdTest {
  private static final String ALPHABET =
      "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

  @Test
  void everyLeadingZeroCountAndDigestBitBoundaryIsAccepted() {
    Random random = new Random(151);
    for (int zeros = 0; zeros <= 32; zeros++) {
      for (int sample = 0; sample < 16; sample++) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        java.util.Arrays.fill(bytes, 0, zeros, (byte) 0);
        if (zeros < 32) bytes[zeros] = (byte) (1 << (sample % 8));
        String value = encode(bytes);
        assertEquals(value, new BlueId(value).value());
        assertEquals(value, LanguageCore.plainId(value));
      }
    }
  }

  @Test
  void mutationsMatchAnIndependentDecoderIncludingOverflowAndShortValues() {
    Random random = new Random(89211);
    for (int sample = 0; sample < 6000; sample++) {
      StringBuilder value = new StringBuilder();
      int length = 24 + random.nextInt(25);
      for (int i = 0; i < length; i++) value.append(ALPHABET.charAt(random.nextInt(58)));
      if (sample % 3 == 0) value.insert(0, "1".repeat(sample % 33));
      if (sample % 17 == 0) value.insert(random.nextInt(value.length()), "0IOl#é");
      String text = value.toString();
      boolean expected = valid(text);
      assertEquals(expected, BlueIdSyntax.digest(text), text);
      if (expected) assertEquals(text, new BlueId(text).value());
      else
        assertEquals(
            "INVALID_BLUE_ID", assertThrows(BlueException.class, () -> new BlueId(text)).code());
    }
  }

  @Test
  void exactByteWidthLimitsAndTheirNeighboursMatchTheIndependentDecoder() {
    for (int zeros = 0; zeros < 32; zeros++) {
      for (int width : new int[] {31 - zeros, 32 - zeros}) {
        BigInteger boundary = BigInteger.ONE.shiftLeft(8 * width);
        for (int delta = -1; delta <= 1; delta++) {
          BigInteger number = boundary.add(BigInteger.valueOf(delta));
          byte[] raw = number.toByteArray();
          if (raw[0] == 0) raw = java.util.Arrays.copyOfRange(raw, 1, raw.length);
          String value = "1".repeat(zeros) + encode(raw);
          assertEquals(valid(value), BlueIdSyntax.digest(value), value);
        }
      }
    }
    for (int length : new int[] {0, 31, 32, 33, 44}) {
      String zeros = "1".repeat(length);
      assertEquals(length == 32, BlueIdSyntax.digest(zeros));
    }
  }

  @Test
  void memberIndicesRetainCanonicalDecimalRulesAndNeverHideAnInvalidDigest() {
    String id = "1".repeat(32);
    for (String suffix : new String[] {"0", "1", "19", "9".repeat(100)}) {
      assertEquals(id + "#" + suffix, new BlueId(id + "#" + suffix).value());
      assertEquals(id + "#" + suffix, LanguageCore.finalId(id + "#" + suffix));
    }
    for (String suffix : new String[] {"", "00", "01", "-1", "+1", "1.0", "1#2", "１"})
      assertThrows(BlueException.class, () -> new BlueId(id + "#" + suffix));
    assertThrows(BlueException.class, () -> new BlueId("1".repeat(31) + "#1"));
  }

  private static boolean valid(String value) {
    BigInteger number = BigInteger.ZERO;
    int zeros = 0;
    while (zeros < value.length() && value.charAt(zeros) == '1') zeros++;
    for (int i = 0; i < value.length(); i++) {
      int digit = ALPHABET.indexOf(value.charAt(i));
      if (digit < 0) return false;
      number = number.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(digit));
    }
    return zeros + (number.bitLength() + 7) / 8 == 32;
  }

  private static String encode(byte[] bytes) {
    BigInteger value = new BigInteger(1, bytes);
    StringBuilder text = new StringBuilder();
    while (value.signum() != 0) {
      BigInteger[] step = value.divideAndRemainder(BigInteger.valueOf(58));
      text.append(ALPHABET.charAt(step[1].intValue()));
      value = step[0];
    }
    for (byte element : bytes) {
      if (element != 0) break;
      text.append('1');
    }
    return text.reverse().toString();
  }
}
