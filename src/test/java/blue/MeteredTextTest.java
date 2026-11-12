package blue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Scalar order, prefix boundaries and the first unaffordable logical charge are independent. */
@Tag("canonical-bex")
@Tag("contracts")
class MeteredTextTest {
  @ParameterizedTest
  @ValueSource(ints = {0, 1, 63, 64, 65, 127, 128, 129})
  void comparisonsStopAtTheFirstDifferentBlock(int prefix) {
    String common = "\uD83C\uDF0D".repeat(prefix);
    String left = common + "a" + "tail".repeat(1000);
    String right = common + "b" + "tail".repeat(1000);
    AtomicInteger blocks = new AtomicInteger();
    assertEquals(
        Integer.signum(Arrays.compare(left.codePoints().toArray(), right.codePoints().toArray())),
        Integer.signum(MeteredText.compare(left, right, blocks::incrementAndGet)));
    assertEquals(prefix / 64 + 1, blocks.get());
    blocks.set(0);
    assertTrue(MeteredText.compare(left, common, blocks::incrementAndGet) > 0);
    assertEquals((prefix + 63) / 64, blocks.get());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 63, 64, 65, 127, 128, 129})
  void equalityCountsUnicodeScalarsAndPreservesLengthShortCircuit(int length) {
    String text = "\uD83C\uDF0D".repeat(length);
    BexGas gas = new BexGas(1000);
    assertTrue(BexValues.equal(text, text, gas));
    assertEquals(1 + 2 * ((length + 63) / 64), gas.used());
    BexGas unequal = new BexGas(1000);
    assertFalse(BexValues.equal(text, text + "x", unequal));
    assertEquals(1, unequal.used());
  }

  @Test
  void surrogatePairsAcrossTheInitialWindowKeepScalarBlockBoundaries() {
    for (int prefix : new int[] {62, 63, 64}) {
      String common = "a".repeat(prefix) + "\uD83C\uDF0D";
      AtomicInteger blocks = new AtomicInteger();
      assertTrue(MeteredText.compare(common + "a", common + "b", blocks::incrementAndGet) < 0);
      assertEquals((prefix + 1) / 64 + 1, blocks.get());
    }
  }

  @Test
  void scalarOrderingIsNotUtf16CodeUnitOrdering() {
    assertTrue(MeteredText.compare("\uD800\uDC00", "\uFFFF", () -> {}) > 0);
    assertTrue(MeteredText.compare("\uFFFF", "\uD800\uDC00", () -> {}) < 0);
    assertEquals(0, MeteredText.compare("", "", () -> fail("Empty text has no blocks")));
  }

  @Test
  void bexRejectsTheSameTwoUnitBlockWithoutPartialAdmission() {
    String text = "x".repeat(130);
    for (int limit = 0; limit < 7; limit++) {
      BexGas gas = new BexGas(limit);
      assertThrows(BexFailure.class, () -> BexValues.equal(text, text, gas));
      assertEquals(limit == 0 ? 0 : 1 + 2 * ((limit - 1) / 2), gas.used());
      assertEquals(limit == 0 ? "valueMember" : "textWork", gas.rejected().get("counter"));
    }
  }

  @Test
  void representationComparisonDoesNotConsumeSemanticGas() {
    long scalar = ((Number) ContractModel.TARIFF.get("scalarComparison")).longValue();
    long side = ((Number) ContractModel.TARIFF.get("textBlockExamined")).longValue();
    ContractModel.Meter gas = new ContractModel.Meter(scalar + side);
    ContractWork work = new ContractWork(new ContractBridge(Language.create()), gas);
    assertEquals(0, work.compare("same", "same"));
    assertEquals(0, gas.used, "Representation comparison is not a BEX semantic operation");
    assertTrue(gas.trace.isEmpty());
    assertNull(gas.rejected);
  }
}
