package blue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("contracts")
class CanonicalEncodingTest {
  @Test
  void deeplyNestedContractPathsUseTheSameEscapingAsPublicPointers() {
    var segments = new java.util.ArrayList<String>();
    for (int i = 0; i < 2048; i++) {
      segments.add(i % 5 == 0 ? "" : "订单/" + i + "~1");
    }
    String pointer = ContractModel.pointer(segments);
    assertEquals(new Pointer(segments).toString(), pointer);
    assertEquals(segments, Pointer.parse(pointer).segments());
    assertEquals(segments, ContractModel.segments(pointer));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 511, 512, 513, 16383, 16384, 16385, 65536})
  void growingBuffersPreserveUtf8FragmentsNumbersAndExplicitFlushes(int length) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    CanonicalOutput output = new CanonicalOutput(bytes);
    String prefix = "x".repeat(length);
    String unicode = "é\uD83C\uDF0D".repeat(1000);
    output.append(prefix);
    output.append(unicode);
    output.flush();
    output.number(Long.MIN_VALUE);
    output.appendUtf8(unicode.getBytes(StandardCharsets.UTF_8));
    output.number(Long.MAX_VALUE);
    output.flush();
    assertEquals(
        prefix + unicode + Long.MIN_VALUE + unicode + Long.MAX_VALUE,
        bytes.toString(StandardCharsets.UTF_8));
  }

  @Test
  void bufferedEncodingAndFragmentsPreserveUnicodeOrderingAndEscapes() {
    Object data =
        Map.of("\uD83D\uDE00", "\n\t\"\\", "\uE000", List.of(BigInteger.valueOf(-5), "é"));
    String expected = "{\"\uE000\":[-5,\"é\"],\"\uD83D\uDE00\":\"\\n\\t\\\"\\\\\"}";
    assertEquals(expected, ContractCanonical.text(data));
    assertEquals(expected, ContractCanonical.text(new ContractCanonical.Fragment(data)));
    assertEquals(
        "[" + expected + "," + expected + "]",
        ContractCanonical.text(List.of(data, new ContractCanonical.Fragment(data))));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 10, 1000})
  void immutableRecordsPreserveNullPresenceAndDetachedCanonicalValues(int fields) {
    Map<String, Object> source = new java.util.LinkedHashMap<>();
    source.put("nullable", null);
    for (int i = 0; i < fields; i++) {
      source.put("field-" + i, i);
    }
    String expected = ContractCanonical.text(source);
    Map<String, Object> frozen = ContractModel.obj(ContractData.freeze(source));
    Map<String, Object> encoded = new ContractCanonical.EncodedRecord(source);
    source.clear();
    for (Map<String, Object> record : List.of(frozen, encoded)) {
      assertTrue(record.containsKey("nullable"));
      assertNull(record.get("nullable"));
      assertFalse(record.containsKey("missing"));
      assertFalse(record.containsKey(42));
      assertEquals(fields + 1, record.size());
      assertEquals(expected, ContractCanonical.text(record));
      assertThrows(UnsupportedOperationException.class, () -> record.put("new", 1));
    }
  }

  @Test
  void compactRowsPreserveCanonicalBytesAcrossKeyOrdersAndScalarBoundaries() {
    for (List<String> keys :
        List.of(
            List.of("z", "a", "quoted\"\\\n", "é"), List.of("\uD83D\uDE00", "\uE000", "a", "z"))) {
      var layout = new ContractData.RecordLayout(keys.toArray(String[]::new));
      Object[] values = {Long.MIN_VALUE, Long.MAX_VALUE, "é\uD83C\uDF0D\n", null};
      Map<String, Object> plain = new java.util.LinkedHashMap<>();
      for (int i = 0; i < keys.size(); i++) {
        plain.put(keys.get(i), values[i]);
      }
      assertEquals(
          ContractCanonical.text(plain),
          ContractCanonical.text(ContractData.scalarRecord(layout, values)));
    }
    assertEquals(
        "{}",
        ContractCanonical.text(
            ContractData.scalarRecord(
                new ContractData.RecordLayout(new String[0]), new Object[0])));
  }
}
