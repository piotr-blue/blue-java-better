package blue;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Exact transport preserves representation as well as identity. */
@Tag("language")
class LanguageEncodingTest {
  @ParameterizedTest
  @MethodSource("scalars")
  void scalarWireTokensPreserveCategory(Object value, String token) {
    ExactNode.Value node = ExactNode.value(Collections.singletonMap("value", value));
    assertEquals("{\"value\":" + token + "}", LanguageCore.exactText(node));
    assertEquals(LanguageCodec.wire(LanguageCore.export(node)), LanguageCore.exactText(node));
    assertNull(node.identity);
  }

  static Stream<Arguments> scalars() {
    return Stream.of(
        Arguments.of(null, "null"),
        Arguments.of(true, "true"),
        Arguments.of(BigInteger.valueOf(42), "42"),
        Arguments.of("9007199254740992", "\"9007199254740992\""),
        Arguments.of(42.0, "42.0"),
        Arguments.of(-0.0, "0.0"),
        Arguments.of(new ExactNode.NumberToken("1.20e+3"), "1.20e+3"));
  }

  @Test
  void bareListsUseArraysAndTypedListsRetainTheirFields() {
    ExactNode.Value empty = ExactNode.list(List.of());
    ExactNode.Value typed =
        ExactNode.value(Map.of("items", List.of(empty), "mergePolicy", "append", "name", "list"));
    assertEquals("[]", LanguageCore.exactText(empty));
    assertEquals("[[]]", LanguageCore.exactText(ExactNode.list(List.of(empty))));
    assertEquals(
        "{\"items\":[[]],\"mergePolicy\":\"append\",\"name\":\"list\"}",
        LanguageCore.exactText(typed));
  }

  @Test
  void referencesStayCollapsedEvenWhenTheyOwnACyclicBody() {
    String id = LanguageCore.CORE.get("Text") + "#0";
    ExactNode.Ref ref =
        new ExactNode.Ref(id, ExactNode.value(Map.of("next", new ExactNode.Local(0))));
    assertEquals("{\"blueId\":\"" + id + "\"}", LanguageCore.exactText(ref));
    assertEquals("{\"blueId\":\"this#12\"}", LanguageCore.exactText(new ExactNode.Local(12)));
    assertNull(ref.body().identity);
  }

  @Test
  void fieldOrderAndEscapesAreCanonicalWithoutChangingUnicode() {
    ExactNode.Value empty = ExactNode.value(Map.of());
    ExactNode.Value node =
        ExactNode.value(
            Map.of("\uE000", empty, "🌍", empty, "a\"", empty, "description", "é\n\u0000\\\t"));
    assertEquals(
        "{\"a\\\"\":{},\"description\":\"é\\n\\u0000\\\\\\t\",\"🌍\":{},\"\uE000\":{}}",
        LanguageCore.exactText(node));
  }

  @ParameterizedTest
  @ValueSource(strings = {"\uD800", "\uDC00"})
  void invalidUnicodeIsStillRejectedInKeysValuesAndReferences(String invalid) {
    for (ExactNode node :
        List.of(
            ExactNode.value(Map.of(invalid, ExactNode.list(List.of()))),
            ExactNode.scalar("Text", invalid),
            new ExactNode.Ref(invalid))) {
      LanguageFailure expected =
          assertThrows(LanguageFailure.class, () -> LanguageCodec.wire(LanguageCore.export(node)));
      LanguageFailure actual =
          assertThrows(LanguageFailure.class, () -> LanguageCore.exactText(node));
      assertEquals(expected.getMessage(), actual.getMessage());
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void exactTransportMatchesStructuralExportAcrossNestedRepresentations(int seed) {
    Random random = new Random(seed);
    for (int i = 0; i < 80; i++) {
      ExactNode node = node(random, 4);
      assertEquals(LanguageCodec.wire(LanguageCore.export(node)), LanguageCore.exactText(node));
    }
  }

  private static ExactNode node(Random random, int depth) {
    if (depth == 0) {
      return switch (random.nextInt(7)) {
        case 0 -> ExactNode.scalar("Text", "é🌍\"\\\n\u0001".repeat(random.nextInt(40)));
        case 1 -> ExactNode.scalar("Integer", new BigInteger(200, random).toString());
        case 2 ->
            ExactNode.scalar(
                "Double", Math.scalb(random.nextDouble(), random.nextInt(2000) - 1000));
        case 3 -> ExactNode.scalar("Boolean", random.nextBoolean());
        case 4 -> ExactNode.value(Collections.singletonMap("value", null));
        case 5 -> new ExactNode.Ref(LanguageCore.CORE.get("Text"));
        default -> new ExactNode.Local(random.nextInt(100));
      };
    }
    ExactNode first = node(random, depth - 1);
    ExactNode second = node(random, depth - 1);
    return switch (random.nextInt(5)) {
      case 0 -> ExactNode.list(List.of(first, second));
      case 1 ->
          ExactNode.value(
              Map.of(
                  "items",
                  List.of(first, second),
                  "type",
                  new ExactNode.Ref(LanguageCore.CORE.get("List")),
                  "mergePolicy",
                  "append"));
      case 2 -> ExactNode.value(Map.of("z", first, "a", second, "name", "a label"));
      case 3 -> ExactNode.value(Map.of("🌍", first, "\uE000", second, "description", "literal"));
      default -> ExactNode.value(Map.of());
    };
  }
}
