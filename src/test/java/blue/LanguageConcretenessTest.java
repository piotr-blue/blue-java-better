package blue;

import static blue.ExactNode.scalar;
import static blue.ExactNode.value;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.ExactNode.Ref;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Presence facts are complete immutable-closure results, not guesses about missing evidence. */
@Tag("language")
class LanguageConcretenessTest {
  @Test
  void capturedViewsRetainCompletedPresenceFactsWithoutSharingNewWrites() {
    ExactNode node = value(Map.of("type", new Ref(LanguageCore.CORE.get("Integer"))));
    for (int i = 0; i < 300; i++) node = value(Map.of("child", node));
    LanguageContext parent = new LanguageContext();
    assertFalse(LanguageSemantics.concreteNode(node, parent));
    LanguageContext copy = new LanguageContext();
    parent.snapshotHeadsTo(copy);
    long before = copy.work.get();
    assertFalse(LanguageSemantics.concreteNode(node, copy));
    assertEquals(before, copy.work.get());
    ExactNode added = value(Map.of("fresh", scalar("Text", "new")));
    assertTrue(LanguageSemantics.concreteNode(added, copy));
    assertFalse(parent.concreteFacts.containsKey(LanguageCore.identity(added)));
    copy.concreteFacts.clear();
    assertFalse(parent.concreteFacts.isEmpty());
  }

  @Test
  void presenceCacheRemainsBoundedAndLiteralFastPathPreservesHostWork() {
    LanguageContext context = new LanguageContext();
    for (int i = 0; i < 8300; i++)
      assertTrue(
          LanguageSemantics.concreteNode(
              scalar("Integer", java.math.BigInteger.valueOf(i)), context));
    assertTrue(context.concreteFacts.size() <= 8192);
    LanguageContext limited = new LanguageContext(Map.of("steps", 100L));
    long before = limited.work.get();
    assertTrue(LanguageSemantics.concreteNode(scalar("Text", "hello"), limited));
    assertEquals(2, limited.work.get() - before);
  }

  @Test
  void completedNegativeClosureMakesAllItsSubtreeQueriesConstantWork() {
    List<ExactNode> nodes = new ArrayList<>();
    ExactNode node = value(Map.of("type", new Ref(LanguageCore.CORE.get("Integer"))));
    nodes.add(node);
    for (int i = 0; i < 300; i++) {
      node = value(Map.of("child", node));
      nodes.add(node);
    }
    LanguageContext context = new LanguageContext();
    assertFalse(LanguageSemantics.concreteNode(node, context));
    long work = context.work.get();
    for (ExactNode child : nodes) assertFalse(LanguageSemantics.concreteNode(child, context));
    assertEquals(work, context.work.get(), "No subtree must be traversed again");
  }

  @Test
  void aPositiveSiblingDoesNotCertifyAnAbstractBranch() {
    ExactNode abstractNode = value(Map.of("type", new Ref(LanguageCore.CORE.get("Text"))));
    ExactNode concrete = scalar("Integer", java.math.BigInteger.ONE);
    LanguageContext context = new LanguageContext();
    assertTrue(
        LanguageSemantics.concreteNode(value(Map.of("a", abstractNode, "b", concrete)), context));
    assertFalse(LanguageSemantics.concreteNode(abstractNode, context));
  }

  @Test
  void incompleteTraversalDoesNotInstallNegativeFacts() {
    LanguageContext context = new LanguageContext();
    ExactNode absent = new Ref(Language.create().readYaml("unavailable: 1").blueId().value());
    ExactNode parent = value(Map.of("child", absent));
    assertThrows(LanguageFailure.class, () -> LanguageSemantics.concreteNode(parent, context));
    assertFalse(context.concreteFacts.containsKey(LanguageCore.identity(parent)));
    assertFalse(context.concreteFacts.containsKey(LanguageCore.identity(absent)));
  }

  @Test
  void publicLimitsRemainIndependentOfWarmth() {
    ExactNode node =
        value(Map.of("field", value(Map.of("type", new Ref(LanguageCore.CORE.get("Text"))))));
    LanguageContext context = new LanguageContext(Map.of("steps", 100L));
    assertFalse(LanguageSemantics.concreteNode(node, context));
    long first = context.work.get();
    assertFalse(LanguageSemantics.concreteNode(node, context));
    assertEquals(first * 2, context.work.get());
  }
}
