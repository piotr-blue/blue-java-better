package blue;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Independent carrier spellings: not Blue's hash-helper number/UTF-16 key encoding. */
@Tag("packages")
class PackageIntegrityTest {
  @ParameterizedTest
  @CsvSource({
    "0.0,0.0",
    "-0.0,-0.0",
    "1.0,1.0",
    "0.0001,0.0001",
    "0.00001,1e-05",
    "1e15,1000000000000000.0",
    "1e16,1e+16",
    "1e20,1e+20",
    "1.2345e-10,1.2345e-10",
    "4.9e-324,5e-324",
    "1.7976931348623157e308,1.7976931348623157e+308",
    "1000000000000000128.0,1.0000000000000001e+18"
  })
  void usesCanonicalCarrierFloatSpelling(double value, String expected) {
    assertEquals(expected, PackageIntegrity.repositoryCanonical(value));
  }

  @Test
  void sortsKeysByUnicodeScalarAndPreservesText() {
    assertEquals(
        "{\"é\":\"a\\nb\",\"\ue000\":2,\"😀\":1}",
        PackageIntegrity.repositoryCanonical(Map.of("😀", 1, "\ue000", 2, "é", "a\nb")));
  }
}
