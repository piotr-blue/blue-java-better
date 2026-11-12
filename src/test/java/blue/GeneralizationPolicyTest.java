package blue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import blue.Generalization.Policy;
import blue.Generalization.Rule;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Rule selection for a Type Generalization Policy, independent of document execution. */
@Tag("contracts")
class GeneralizationPolicyTest {
  private static final String NEAREST = "nearest-valid-ancestor";

  private static String invalid(Runnable construction) {
    return assertThrows(ContractModel.Error.class, construction::run).code;
  }

  @Test
  void aPathWithoutARuleUsesTheDefaultModeAndNoFloor() {
    Policy policy = new Policy(List.of(new Rule("/orders", "reject", "floor")), NEAREST, "id");
    Rule selected = policy.select("/payments/7");
    assertEquals(NEAREST, selected.mode());
    assertEquals("", selected.path());
    assertNull(selected.floor());
  }

  @Test
  void aRuleAppliesToItsPathAndEverythingBeneathIt() {
    Rule orders = new Rule("/orders", NEAREST, "floor");
    Policy policy = new Policy(List.of(orders), "reject", "id");
    assertEquals(orders, policy.select("/orders"));
    assertEquals(orders, policy.select("/orders/7/total"));
  }

  @Test
  void matchingComparesWholeSegmentsNotCharacters() {
    Policy policy = new Policy(List.of(new Rule("/order", NEAREST, null)), "reject", "id");
    assertEquals("reject", policy.select("/orders").mode());
    assertEquals("reject", policy.select("/orderly/1").mode());
    assertEquals(NEAREST, policy.select("/order/1").mode());
  }

  @Test
  void theDeepestMatchingRuleWinsRegardlessOfDeclarationOrder() {
    Rule shallow = new Rule("/orders", "reject", null);
    Rule deep = new Rule("/orders/7", NEAREST, null);
    assertEquals(deep, new Policy(List.of(shallow, deep), "reject", "id").select("/orders/7/x"));
    assertEquals(deep, new Policy(List.of(deep, shallow), "reject", "id").select("/orders/7/x"));
    assertEquals(shallow, new Policy(List.of(deep, shallow), "reject", "id").select("/orders/8"));
  }

  @Test
  void aRootRuleMatchesEveryPath() {
    Rule root = new Rule("", NEAREST, "floor");
    Policy policy = new Policy(List.of(root), "reject", "id");
    assertEquals(root, policy.select(""));
    assertEquals(root, policy.select("/anything/at/all"));
  }

  @Test
  void escapedSegmentsAreComparedAfterDecoding() {
    Rule slash = new Rule("/a~1b", NEAREST, null);
    Policy policy = new Policy(List.of(slash), "reject", "id");
    assertEquals(slash, policy.select("/a~1b/c"));
    assertEquals("reject", policy.select("/a/b/c").mode());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "nearest", "REJECT", "nearest-valid-descendant"})
  void unknownModesAreRejectedForRulesAndDefaults(String mode) {
    assertEquals("INVALID_GENERALIZATION_POLICY", invalid(() -> new Rule("/a", mode, null)));
    assertEquals("INVALID_GENERALIZATION_POLICY", invalid(() -> new Policy(List.of(), mode, "id")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"/", "/a//b", "/a/"})
  void aRulePathMayNotContainAnEmptySegment(String path) {
    assertEquals("INVALID_GENERALIZATION_POLICY", invalid(() -> new Rule(path, "reject", null)));
  }

  @Test
  void aPolicyKeepsItsOwnCopyOfTheRules() {
    List<Rule> rules = new ArrayList<>(List.of(new Rule("/a", NEAREST, null)));
    Policy policy = new Policy(rules, "reject", "id");
    rules.clear();
    assertEquals(NEAREST, policy.select("/a").mode());
    assertThrows(UnsupportedOperationException.class, () -> policy.rules().clear());
  }
}
