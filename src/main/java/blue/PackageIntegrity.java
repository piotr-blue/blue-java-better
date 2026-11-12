package blue;

import static blue.LanguageCore.*;

import blue.ExactNode.NumberToken;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Carrier encoding is separate from the Language hash-helper encoding. */
final class PackageIntegrity {
  private PackageIntegrity() {}

  static void require(boolean valid, String category, String detail) {
    if (!valid) {
      throw new PackageException(category, detail);
    }
  }

  static void check(boolean valid, String detail) {
    require(valid, "InvalidPackage", detail);
  }

  static String utf8(byte[] bytes) {
    check(bytes != null, "Missing package file");
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException failure) {
      throw new PackageException("InvalidPackage", "Invalid UTF-8", failure);
    }
  }

  static String sha(byte[] bytes) {
    return HexFormat.of().formatHex(digest().digest(bytes));
  }

  static MessageDigest digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  static Object data(Object value) {
    if (value instanceof NumberToken token) {
      if (!token.decimal()) {
        return new BigInteger(token.text());
      }
      double number = Double.parseDouble(token.text());
      check(Double.isFinite(number), "Nonfinite carrier number");
      return number;
    }
    if (value instanceof List<?> items) {
      return items.stream().map(PackageIntegrity::data).toList();
    }
    if (value instanceof Map<?, ?> fields) {
      Map<String, Object> result = new TreeMap<>();
      fields.forEach((key, child) -> result.put((String) key, data(child)));
      return result;
    }
    return value;
  }

  static String ref(Object value) {
    if (value == null) {
      return null;
    }
    Map<String, Object> fields = map(value);
    check(fields.keySet().equals(Set.of("blueId")), "Invalid reference shape");
    return finalId(fields.get("blueId"));
  }

  static boolean equal(Object left, Object right) {
    if (left instanceof Number x && right instanceof Number y) {
      return new BigDecimal(x.toString()).compareTo(new BigDecimal(y.toString())) == 0;
    }
    return Objects.equals(left, right);
  }

  static String repositoryCanonical(Object value) {
    if (value instanceof Map<?, ?> fields) {
      List<String> keys =
          fields.keySet().stream()
              .map(Object::toString)
              .sorted((a, b) -> Arrays.compare(a.codePoints().toArray(), b.codePoints().toArray()))
              .toList();
      StringJoiner output = new StringJoiner(",", "{", "}");
      for (String key : keys) {
        output.add(LanguageCodec.quote(key) + ":" + repositoryCanonical(fields.get(key)));
      }
      return output.toString();
    }
    if (value instanceof List<?> items) {
      StringJoiner output = new StringJoiner(",", "[", "]");
      items.forEach(item -> output.add(repositoryCanonical(item)));
      return output.toString();
    }
    if (value instanceof Double number) {
      return repositoryFloat(number);
    }
    return LanguageCodec.write(value);
  }

  private static String repositoryFloat(double value) {
    // The carrier follows Python JSON's finite binary64 spelling, not the Blue hash helper.
    if (value == 0) {
      return Double.doubleToRawLongBits(value) < 0 ? "-0.0" : "0.0";
    }
    boolean negative = value < 0;
    BigDecimal decimal = new BigDecimal(LanguageCodec.number(Math.abs(value))).stripTrailingZeros();
    int exponent = decimal.precision() - decimal.scale() - 1;
    String sign = negative ? "-" : "";
    if (exponent < -4 || exponent >= 16) {
      String digits = decimal.unscaledValue().toString();
      String mantissa =
          digits.length() == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
      return sign
          + mantissa
          + "e"
          + (exponent >= 0 ? "+" : "-")
          + String.format(java.util.Locale.ROOT, "%02d", Math.abs(exponent));
    }
    String plain = decimal.toPlainString();
    return sign + plain + (plain.contains(".") ? "" : ".0");
  }

  static void version(Object value) {
    String text = string(value);
    check(
        text.matches(
            "(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?"),
        "Invalid release version");
    int dash = text.indexOf('-');
    if (dash >= 0) {
      for (String part : text.substring(dash + 1).split("\\.")) {
        check(!part.matches("0[0-9]+"), "Numeric prerelease identifier has a leading zero");
      }
    }
  }
}
