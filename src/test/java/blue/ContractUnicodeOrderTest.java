package blue;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Canonical ordering uses Unicode scalars, including where UTF-16 order differs. */
@Tag("contracts")
class ContractUnicodeOrderTest {
  @Test
  void everyBmpScalarSortsBeforeSupplementaryScalarsEvenAtSurrogateBoundaries() {
    for (int value = 0; value <= Character.MAX_VALUE; value++) {
      if (value >= Character.MIN_SURROGATE && value <= Character.MAX_SURROGATE) continue;
      String bmp = "common-prefix/" + (char) value;
      for (String supplementary :
          List.of("common-prefix/\uD800\uDC00", "common-prefix/\uDBFF\uDFFF")) {
        assertEquals(-1, Integer.signum(ContractCanonical.compare(bmp, supplementary)));
        assertEquals(1, Integer.signum(ContractCanonical.compare(supplementary, bmp)));
      }
    }
  }

  @Test
  void commonPrefixesAndSupplementaryCharactersKeepScalarOrder() {
    List<String> values =
        new ArrayList<>(
            List.of(
                "",
                "a",
                "aa",
                "ab",
                "\uD7FF",
                "\uE000",
                "\uFFFF",
                "\uD800\uDC00",
                "\uDBFF\uDFFF",
                "x\uE000",
                "x\uD800\uDC00",
                "x\uDBFF\uDC00",
                "x\uDBFE\uDC00",
                "x\uE000",
                "a".repeat(1100)));
    Random random = new Random(216);
    for (int i = 0; i < 100; i++) {
      StringBuilder text = new StringBuilder("shared-prefix/");
      for (int n = 0; n < 5; n++) {
        int point;
        do {
          point = random.nextInt(Character.MAX_CODE_POINT + 1);
        } while (point >= Character.MIN_SURROGATE && point <= Character.MAX_SURROGATE);
        text.appendCodePoint(point);
      }
      values.add(text.toString());
    }
    for (String left : values)
      for (String right : values)
        assertEquals(
            Integer.signum(
                java.util.Arrays.compare(
                    left.codePoints().toArray(), right.codePoints().toArray())),
            Integer.signum(ContractCanonical.compare(left, right)));
  }
}
