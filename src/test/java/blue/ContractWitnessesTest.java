package blue;

import static blue.ExactNode.scalar;
import static blue.ExactNode.value;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.ExactNode.Value;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Child identity inventories follow the same lifetime as their parent evidence. */
@Tag("contracts")
class ContractWitnessesTest {
  @Test
  void repeatedOpenRetainsChildrenButNewInvocationEstablishesItsOwnInventory() {
    Value child = value(Map.of("nested", scalar("Text", "hello")));
    Value parent = value(Map.of("child", child));
    ContractBridge bridge = new ContractBridge(Language.create(), true);
    bridge.note(parent, LanguageCore.identity(parent));
    bridge.open(parent);
    assertTrue(bridge.witnesses.childrenKnown(parent));
    assertTrue(bridge.evidence(child));
    for (int i = 0; i < 100; i++) {
      assertSame(parent, bridge.open(parent));
    }
    ContractLanguage.Witnesses invocation = new ContractLanguage.Witnesses();
    bridge.witnessScope.set(invocation);
    try {
      assertFalse(bridge.evidence(child));
      bridge.open(parent);
      assertFalse(bridge.evidence(child), "A raw open cannot manufacture parent evidence");
      bridge.note(parent, LanguageCore.identity(parent));
      bridge.open(parent);
      assertTrue(bridge.evidence(child));
      assertTrue(invocation.childrenKnown(parent));
    } finally {
      bridge.witnessScope.remove();
    }
  }

  @Test
  void forksKeepInventoriesTogetherAndEvictionRevokesTheShortcut() {
    Value parent = value(Map.of("field", scalar("Text", "retained")));
    ContractLanguage.Witnesses original = new ContractLanguage.Witnesses();
    original.put(parent, LanguageCore.identity(parent));
    original.childrenOpened(parent);
    ContractLanguage.Witnesses copy = original.fork();
    original.clear();
    assertTrue(copy.containsKey(parent));
    assertTrue(copy.childrenKnown(parent));
    assertFalse(original.childrenKnown(parent));
    assertEquals(1, copy.trim(0));
    assertFalse(copy.childrenKnown(parent));
  }
}
