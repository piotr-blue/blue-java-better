package blue;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The shared escape grammar is independent of each language's root and normalization policy. */
class PointerGrammarTest {
  @ParameterizedTest
  @ValueSource(strings = {"\n", "\r", "\r\n", "\u0085", "\u2028", "\u2029"})
  void lineTerminatorsCannotHideInvalidTildeEscapes(String separator) {
    for (String path :
        List.of(
            "/before" + separator + "~2",
            "/~x" + separator + "after",
            "/before" + separator + "~",
            "/~" + separator)) {
      assertAll(
          () ->
              assertEquals(
                  "INVALID_POINTER",
                  assertThrows(BlueException.class, () -> Pointer.parse(path)).code()),
          () ->
              assertEquals(
                  "INVALID_POINTER",
                  assertThrows(ContractModel.Error.class, () -> ContractModel.segments(path)).code),
          () ->
              assertEquals(
                  "InvalidPath",
                  assertThrows(LanguageFailure.class, () -> LanguageCore.path(path)).category()),
          () ->
              assertEquals(
                  "BEX_RUNTIME_ERROR",
                  assertThrows(BexFailure.class, () -> BexValues.segments(path)).code()));
    }
  }

  @Test
  void validLiteralNamesRoundTripThroughEveryParserIncludingLineTerminators() {
    var random = new Random(0x6901);
    String alphabet = "a~/01\n\r\u0085\u2028\u2029\u0000é订单";
    for (int sample = 0; sample < 2048; sample++) {
      var name = new StringBuilder();
      for (int i = 0, length = random.nextInt(1, 33); i < length; i++) {
        name.append(alphabet.charAt(random.nextInt(alphabet.length())));
      }
      var expected = List.of("prefix", name.toString(), "");
      String encoded = new Pointer(expected).toString();
      assertEquals(expected, Pointer.parse(encoded).segments());
      assertEquals(expected, ContractModel.segments(encoded));
      assertEquals(expected, LanguageCore.path(encoded));
      assertEquals(expected, BexValues.segments(encoded));
    }
  }

  @Test
  void rootAliasesAndContractsNormalizationKeepTheirExistingPolicies() {
    assertEquals(List.of(""), Pointer.parse("/").segments());
    assertEquals(List.of(""), ContractModel.segments("/"));
    assertEquals(List.of(""), LanguageCore.path("/"));
    assertEquals(List.of(), BexValues.segments("/"));
    String decomposed = "/e\u0301";
    assertEquals(List.of("e\u0301"), Pointer.parse(decomposed).segments());
    assertEquals(List.of("e\u0301"), LanguageCore.path(decomposed));
    assertEquals(List.of("e\u0301"), BexValues.segments(decomposed));
    assertThrows(ContractModel.Error.class, () -> ContractModel.segments(decomposed));
  }
}
