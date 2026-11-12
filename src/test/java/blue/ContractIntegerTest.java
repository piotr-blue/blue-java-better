package blue;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Platform integers retain exact bounds, diagnostics and first-rejected gas accounting. */
@Tag("contracts")
class ContractIntegerTest {
  static Stream<Arguments> bounds() {
    return Stream.of(
        Arguments.of(0L, 0L, 0L),
        Arguments.of(10L, 10L, 10L),
        Arguments.of(ContractModel.SAFE, 0L, ContractModel.SAFE),
        Arguments.of(Long.MAX_VALUE, 0L, -1L),
        Arguments.of(Long.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE),
        Arguments.of(Long.MIN_VALUE, Long.MIN_VALUE, -1L));
  }

  @ParameterizedTest
  @MethodSource("bounds")
  void primitiveAndRecordIntegersRespectInclusiveBounds(long value, long minimum, long maximum) {
    assertEquals(value, ContractModel.integer(value, minimum, maximum));
    assertEquals(value, ContractModel.integer((Object) value, minimum, maximum));
    assertEquals(value, ContractModel.integer(BigInteger.valueOf(value), minimum, maximum));
    if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE)
      assertEquals(value, ContractModel.integer((Object) (int) value, minimum, maximum));
  }

  @ParameterizedTest
  @CsvSource({"-1,0,-1", "11,0,10", "5,6,10", "0,1,0"})
  void outOfRangeValuesRetainTheBoundDiagnostics(long value, long minimum, long maximum) {
    ContractModel.Error failure =
        assertThrows(
            ContractModel.Error.class, () -> ContractModel.integer(value, minimum, maximum));
    assertEquals("INVALID_INTEGER", failure.code);
    assertEquals(minimum, failure.context.get("minimum"));
    assertTrue(failure.context.containsKey("maximum"));
    assertEquals(maximum < 0 ? null : maximum, failure.context.get("maximum"));
    for (Object input : new Object[] {value, (int) value, BigInteger.valueOf(value)}) {
      ContractModel.Error recordFailure =
          assertThrows(
              ContractModel.Error.class, () -> ContractModel.integer(input, minimum, maximum));
      assertEquals(failure.context, recordFailure.context);
    }
  }

  @Test
  void nonIntegerRecordsAndOversizedIntegersRetainTheirDistinctFailures() {
    for (Object input : new Object[] {null, "1", 1.0, true}) {
      ContractModel.Error failure =
          assertThrows(ContractModel.Error.class, () -> ContractModel.integer(input, 0, -1));
      assertEquals("INVALID_INTEGER", failure.code);
      assertEquals(Map.of("minimum", 0L, "maximum", -1L), failure.context);
    }
    BigInteger overflow = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE);
    assertThrows(ArithmeticException.class, () -> ContractModel.integer(overflow, 0, -1));
    assertEquals(
        "INVALID_INTEGER",
        assertThrows(
                ContractModel.Error.class,
                () -> ContractModel.integer(overflow, 0, ContractModel.SAFE))
            .code);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void aRejectedChargeDoesNotDebitOrReplaceTheFirstSharedOrLocalFailure(boolean local) {
    ContractModel.Meter meter =
        new ContractModel.Meter(
            local ? 10 : 2, local ? Map.of("doc", 2L) : Map.of(), Set.of("doc"));
    meter.owner = "doc";
    meter.charge("expressionBase");
    assertThrows(BexFailure.class, () -> meter.charge("expressionBase", 2));
    Map<String, Object> rejected = meter.rejected();
    assertEquals(local ? "LOCAL" : "SHARED", rejected.get("cap"));
    assertEquals(1L, rejected.get("remaining"));
    assertEquals(2L, rejected.get("amount"));
    assertEquals(1, meter.used());
    assertEquals(1, meter.traceSize());
    assertThrows(BexFailure.class, () -> meter.charge("textWork", 0));
    assertEquals(rejected, meter.rejected());
    assertEquals(1, meter.used());
  }
}
