package blue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("canonical-bex")
class CanonicalBexApiTest {
  @Test
  void compileOnceRunWithImmutableInputsAndSeparateEffects() {
    Bex.Program program =
        Bex.create()
            .compileYaml(
                """
                do:
                  - $appendChange:
                      op: replace
                      path: /total
                      val: {$add: [{$document: /total}, {$event: /amount}]}
                  - $appendEvent: {kind: Added, amount: {$event: /amount}}
                  - $return: {$event: /amount}
                """);
    Map<String, Object> document = new HashMap<>(Map.of("total", 3));
    Bex.Input input = Bex.inputs().document(document).event(Map.of("amount", 4)).build();
    document.put("total", 100);
    Bex.Result first = program.execute(input).requireSuccess();
    Bex.Result second = program.execute(input).requireSuccess();
    assertEquals(first, second);
    assertEquals(BigInteger.valueOf(4), first.value());
    assertEquals(
        List.of(Map.of("op", "replace", "path", "/total", "val", BigInteger.valueOf(7))),
        first.changeset());
    assertEquals(1, first.events().size());
    assertThrows(UnsupportedOperationException.class, () -> first.events().add("x"));
  }

  @Test
  void failuresDiscardPartialEffectsAndRetainTheFirstRejectedCharge() {
    Bex.Result result =
        Bex.newBuilder().gasLimit(1).build().compileYaml("expr: {$add: [1, 2]}").execute();
    assertEquals("GAS_LIMIT_EXCEEDED", result.failure());
    assertNotNull(result.rejected());
    assertTrue(result.events().isEmpty());
    assertTrue(result.changeset().isEmpty());
    assertThrows(BlueException.class, result::requireSuccess);
  }

  @Test
  void staticErrorsAreCaughtAtCompileTime() {
    BlueException error =
        assertThrows(BlueException.class, () -> Bex.create().compileYaml("expr: {$unknown: 1}"));
    assertEquals("BEX_COMPILE_ERROR", error.code());
  }

  @Test
  void exactIdentityAndLanguageMatchingUseTheCanonicalLanguage() {
    Bex bex = Bex.create();
    Object id = bex.compileYaml("expr: {$nodeBlueId: 42}").execute().requireSuccess().value();
    assertEquals(Language.create().readYaml("42").blueId().value(), id);
    assertEquals(
        true,
        bex.compileYaml("expr: {$is: {node: 42, pattern: {type: Integer}}}")
            .execute()
            .requireSuccess()
            .value());
  }

  @Test
  void undefinedAndNullRemainDifferent() {
    assertTrue(
        Bex.create()
            .compileYaml("expr: {$event: /missing}")
            .execute()
            .requireSuccess()
            .isUndefined());
    Bex.Result result = Bex.create().compileYaml("expr: {$null: true}").execute().requireSuccess();
    assertNull(result.value());
    assertFalse(result.isUndefined());
  }

  @Test
  void functionArgumentMatchingPreservesViewsNestedInObjectsAndLists() {
    ContractBridge bridge = new ContractBridge(Language.create());
    Object view = bridge.view(bridge.node(Map.of("amount", 42)), null);
    BexEngine.Context context = new BexEngine.Context();
    context.document = Map.of("selected", view);
    List<Object> matched = new ArrayList<>();
    context.matcher =
        (value, pattern) -> {
          matched.add(value);
          assertSame(view, ((List<?>) ((Map<?, ?>) value).get("parts")).getFirst());
          return true;
        };
    BexEngine engine = new BexEngine();
    Map<String, Object> program =
        Map.of(
            "expr",
            Map.of(
                "$call",
                Map.of(
                    "function",
                    "accept",
                    "args",
                    Map.of(
                        "payload", Map.of("parts", List.of(Map.of("$document", "/selected")))))));
    Map<String, Object> definition =
        Map.of(
            "functions",
            Map.of("accept", Map.of("args", Map.of("payload", Map.of()), "expr", true)));
    assertEquals(
        true,
        engine
            .execute(engine.compile(program, definition, null), context, new BexGas(10000))
            .value());
    assertEquals(1, matched.size());
  }

  @Test
  void preservingViewsDoesNotAdmitNullOrNonFiniteBlueValues() {
    assertThrows(BexFailure.class, () -> BexValues.blueInput(java.util.Arrays.asList(1, null)));
    assertThrows(BexFailure.class, () -> BexValues.blueInput(Map.of("value", Double.NaN)));
    assertEquals(Map.of("value", 2), BexValues.blueInput(Map.of("value", 2)));
  }

  @Test
  void freezingReusesOnlyOwnedImmutableTreesAndRejectsMutableCycles() {
    var input = new HashMap<String, Object>();
    var values = new ArrayList<Object>(List.of(1, 2));
    input.put("list", values);
    Object frozen = BexValues.freeze(input);
    assertSame(frozen, BexValues.freeze(frozen));
    values.add(3);
    assertEquals(2, ((List<?>) ((Map<?, ?>) frozen).get("list")).size());
    input.put("self", input);
    assertThrows(BexFailure.class, () -> BexValues.freeze(input));
  }
}
