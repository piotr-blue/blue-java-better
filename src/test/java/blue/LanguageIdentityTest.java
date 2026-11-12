package blue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Optimized identity rendering must match the ordinary canonical helper at every node. */
@Tag("language")
class LanguageIdentityTest {
  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void nestedHelpersKeepCanonicalBytesAcrossFieldKindsAndKeyOrder(int seed) {
    Random random = new Random(seed);
    for (int i = 0; i < 40; i++) verify(value(random, 3), null);
  }

  @Test
  void listFoldsQuoteScopedTokensAndOrderElementBeforePrevious() {
    for (String prefix : List.of("", "é🌍", "this#0", "\"\\\n", "\u0001")) {
      for (String element : List.of("next#1", "é🌍", "\"\\\t")) {
        Object helper =
            Map.of(
                "$listCons",
                Map.of("prev", Map.of("blueId", prefix), "elem", Map.of("blueId", element)));
        assertEquals(LanguageCore.hash(helper), LanguageCore.appendId(prefix, element));
      }
    }
  }

  @Test
  void cyclicHelpersRemainScopedAndDoNotPopulateOrdinaryIdentityMetadata() {
    ExactNode.Value value =
        ExactNode.value(
            Map.of(
                "name",
                "cyclic 🌍",
                "next",
                new ExactNode.Local(0),
                "items",
                List.of(new ExactNode.Local(1), ExactNode.scalar("Text", "quote\""))));
    verify(value, index -> "a\"\\\n#" + index);
    String first = LanguageCore.identity(value, index -> "first#" + index);
    verify(value, index -> "second#" + index);
    assertNotEquals(first, LanguageCore.identity(value, index -> "second#" + index));
    assertNull(value.identity);
    assertEquals(0, value.identityBytes);
  }

  private static void verify(ExactNode node, IntFunction<String> token) {
    if (!(node instanceof ExactNode.Value value)) return;
    for (Object child : value.fields().values()) {
      if (child instanceof ExactNode exact) verify(exact, token);
      else if (child instanceof List<?> children)
        for (Object item : children) verify((ExactNode) item, token);
    }
    String expected;
    if (value.fields().keySet().equals(Set.of("items"))) {
      expected = LanguageCore.hash(Map.of("$list", "empty"));
      for (ExactNode item : LanguageCore.exactItems(value.get("items"))) {
        expected =
            LanguageCore.hash(
                Map.of(
                    "$listCons",
                    Map.of(
                        "elem", Map.of("blueId", LanguageCore.identity(item, token)),
                        "prev", Map.of("blueId", expected))));
      }
    } else {
      byte[] bytes =
          LanguageCodec.write(LanguageCore.helper(value, token)).getBytes(StandardCharsets.UTF_8);
      expected = LanguageCore.hashBytes(bytes);
      assertEquals(expected, LanguageCore.identity(value, token));
      if (token == null) assertEquals(bytes.length, value.identityBytes);
    }
    assertEquals(expected, LanguageCore.identity(value, token));
  }

  private static ExactNode.Value value(Random random, int depth) {
    if (depth == 0)
      return switch (random.nextInt(5)) {
        case 0 -> ExactNode.scalar("Text", "é🌍\"\\\n\u0001".repeat(random.nextInt(20)));
        case 1 -> ExactNode.scalar("Integer", new BigInteger(200, random).negate());
        case 2 ->
            ExactNode.scalar("Double", Math.scalb(random.nextDouble(), random.nextInt(1000) - 500));
        case 3 -> ExactNode.scalar("Boolean", random.nextBoolean());
        default -> ExactNode.value(Collections.singletonMap("value", null));
      };
    Map<String, Object> fields = new LinkedHashMap<>();
    switch (random.nextInt(4)) {
      case 0 -> {
        fields.put("items", List.of(value(random, depth - 1), value(random, depth - 1)));
        fields.put("mergePolicy", "append");
        fields.put("type", new ExactNode.Ref(LanguageCore.CORE.get("List")));
      }
      case 1 -> fields.put("items", List.of(value(random, depth - 1), value(random, depth - 1)));
      case 2 -> {
        fields.put("🌍", value(random, depth - 1));
        fields.put("\uE000", value(random, depth - 1));
        fields.put("name", "not a package name");
        fields.put("description", "escaped\t\"\\");
      }
      default -> {
        fields.put("z", value(random, depth - 1));
        fields.put("a", value(random, depth - 1));
      }
    }
    return ExactNode.value(fields);
  }
}
