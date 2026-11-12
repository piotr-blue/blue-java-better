package blue;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Physical identity metadata must never prepay logical construction or cross cyclic scopes. */
@Tag("contracts")
class ContractIdentityTest {
  private record Result(
      String identity, long gas, List<Map<String, Object>> trace, Set<String> established) {}

  @Nested
  class OrdinaryConstruction {
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 55, 56, 57, 63, 64, 65, 128})
    void utf8SizeAndWarmDigestPreserveEveryChargeAndTheGasLimit(int length) {
      String text = "é🌍\n\"\\".repeat(length);
      ExactNode.Value cold = ExactNode.scalar("Text", text);
      Result expected = establish(cold, 10_000_000);
      ExactNode.Value warm = ExactNode.scalar("Text", text);
      assertEquals(expected.identity(), LanguageCore.identity(warm));
      assertEquals(
          LanguageCodec.write(LanguageCore.helper(warm, null))
              .getBytes(StandardCharsets.UTF_8)
              .length,
          warm.identityBytes);
      Result actual = establish(warm, 10_000_000);
      assertEquals(expected, actual);

      assertEquals(0, expected.gas(), "Content addressing is representation work");
      assertEquals(expected, establish(warm, 0));
    }

    @Test
    void typedListsObjectsAndMergePolicyUseTheirOrdinaryHelperSize() {
      List<ExactNode.Value> values =
          List.of(
              ExactNode.value(
                  Map.of(
                      "left",
                      ExactNode.scalar("Integer", java.math.BigInteger.TEN),
                      "right",
                      ExactNode.scalar("Text", "é🌍"))),
              ExactNode.value(
                  Map.of(
                      "type",
                      new ExactNode.Ref(LanguageCore.CORE.get("List")),
                      "items",
                      List.of(ExactNode.scalar("Text", "one"), ExactNode.scalar("Text", "two")))),
              ExactNode.value(
                  Map.of(
                      "mergePolicy",
                      "append",
                      "items",
                      List.of(ExactNode.scalar("Text", "three")))));
      for (ExactNode.Value cold : values) {
        Result expected = establish(cold, 10_000_000);
        ExactNode.Value warm = new ExactNode.Value(cold.fields());
        assertEquals(expected.identity(), LanguageCore.identity(warm));
        assertEquals(expected, establish(warm, 10_000_000));
        assertTrue(warm.identityBytes > 0);
      }
      ExactNode.Value list = ExactNode.list(List.of(ExactNode.scalar("Text", "one")));
      String id = LanguageCore.identity(list);
      assertEquals(0, list.identityBytes, "Pure lists use folding, not an ordinary helper hash");
      assertEquals(id, establish(list, 10_000_000).identity());
    }
  }

  @Nested
  class CyclicConstruction {
    @Test
    void tokenSpecificHashesAndLengthsNeverBecomeAnOrdinaryMemo() {
      ExactNode.Value body =
          ExactNode.value(
              Map.of("next", new ExactNode.Local(0), "label", ExactNode.scalar("Text", "ring")));
      String first = LanguageCore.identity(body, i -> "this#" + i);
      String second = LanguageCore.identity(body, i -> "that#" + i);
      assertNotEquals(first, second);
      for (String token : List.of("this#", "that#")) {
        ContractBridge bridge = new ContractBridge(Language.create());
        ContractModel.Meter meter = new ContractModel.Meter(10_000_000);
        String actual =
            new ContractWork(bridge, meter).establish(body, null, i -> token + i, List.of(token));
        assertEquals(token.equals("this#") ? first : second, actual);
        assertNull(body.identity);
        assertEquals(0, body.identityBytes);
      }
      assertThrows(LanguageFailure.class, () -> LanguageCore.identity(body));
    }
  }

  private static Result establish(ExactNode.Value value, long limit) {
    ContractModel.Meter meter = new ContractModel.Meter(limit);
    ContractWork work = new ContractWork(new ContractBridge(Language.create()), meter);
    return new Result(
        work.establish(value), meter.used, meter.trace(), Set.copyOf(work.established));
  }
}
