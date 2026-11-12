package blue;

import static blue.ExactNode.list;
import static blue.ExactNode.scalar;
import static blue.ExactNode.value;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.ExactNode.Ref;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Completed facts follow an immutable evidence snapshot; later work and temporary facts do not. */
@Tag("language")
class LanguageFactSnapshotTest {
  @Test
  void verifiedSnapshotRetainsCompletedChecksWithoutRewalkingTheDocument() {
    List<ExactNode> items = new ArrayList<>();
    for (int i = 0; i < 200; i++) items.add(scalar("Text", "value-" + i));
    ExactNode original = list(items);
    LanguageContext source = new LanguageContext();
    LanguageSemantics.strict(original, source, "value");
    LanguageContext snapshot = copy(source);
    LanguageSemantics.strict(original, snapshot, "value");
    assertTrue(snapshot.work.get() < 10, "Completed immutable checks must not walk the children");
    ExactNode later = scalar("Text", "not in the snapshot");
    LanguageSemantics.strict(later, source, "value");
    assertFalse(snapshot.verifiedClosures.contains(LanguageCore.identity(later)));
    ExactNode privateNode = scalar("Text", "private to the snapshot");
    LanguageSemantics.strict(privateNode, snapshot, "value");
    assertFalse(source.verifiedClosures.contains(LanguageCore.identity(privateNode)));
  }

  @Test
  void warmValidationKeepsItsPhysicalBudgetAndDoesNotAcceptChangedContent() {
    LanguageContext source = new LanguageContext();
    ExactNode original = scalar("Text", "known");
    LanguageSemantics.strict(original, source, "value");
    LanguageContext enough = copy(source);
    enough.reuseWithinHostBudget = true;
    enough.limits.put("steps", 5L);
    LanguageSemantics.strict(original, enough, "value");
    assertEquals(5, enough.work.get());
    LanguageContext shortBudget = copy(source);
    shortBudget.reuseWithinHostBudget = true;
    shortBudget.limits.put("steps", 4L);
    assertEquals(
        "OperationLimitExceeded",
        assertThrows(
                LanguageFailure.class,
                () -> LanguageSemantics.strict(original, shortBudget, "value"))
            .category());
    ExactNode changed =
        value(Map.of("type", new Ref(LanguageCore.CORE.get("Integer")), "value", "invalid"));
    assertThrows(
        LanguageFailure.class, () -> LanguageSemantics.strict(changed, copy(source), "value"));
  }

  @Test
  void failedAndTemporaryChecksAreNotCopied() {
    LanguageContext source = new LanguageContext();
    ExactNode bad =
        value(Map.of("type", new Ref(LanguageCore.CORE.get("Integer")), "value", "wrong"));
    assertThrows(LanguageFailure.class, () -> LanguageSemantics.strict(bad, source, "value"));
    LanguageContext snapshot = copy(source);
    assertThrows(LanguageFailure.class, () -> LanguageSemantics.strict(bad, snapshot, "value"));
    ExactNode temporary = scalar("Text", "temporary");
    LanguageSemantics.strict(temporary, source, "value");
    source.establishedValues = Map.of("temporary-cut-point", new LanguageModel.Head());
    LanguageContext captured = copy(source);
    assertTrue(captured.validation.isEmpty());
    assertTrue(captured.verifiedClosures.isEmpty());
  }

  @Test
  void nativeMemoBranchesReuseCompletedChecksOnlyUntilTheirSharedScopeCloses() {
    LanguageContext source = new LanguageContext();
    ExactNode stable = scalar("Text", "stable");
    LanguageSemantics.strict(stable, source, "value");
    LanguageContext issuer = new LanguageContext();
    ExactNode hidden = value(Map.of("payload", scalar("Text", "only known to issuer")));
    LanguageSemantics.strict(hidden, issuer, "value");
    String id = LanguageCore.identity(hidden);
    Map<String, LanguageModel.Head> facts = new HashMap<>();
    facts.put(id, LanguageSemantics.head(LanguageSemantics.recipe(hidden), issuer));
    LanguageMemoScope scope = new LanguageMemoScope();
    scope.attach(source);
    source.establishedValues = facts;
    ExactNode reference = new Ref(id);
    LanguageSemantics.strict(reference, source, "value");
    LanguageContext captured = copy(source);
    captured.establishedValues = facts;
    LanguageSemantics.strict(reference, captured, "value");
    assertTrue(captured.work.get() < 10, "Active scoped facts must not be recomputed in a view");
    LanguageContext nested = copy(captured);
    nested.establishedValues = facts;
    LanguageSemantics.strict(reference, nested, "value");
    assertTrue(nested.work.get() < 10);
    facts.clear();
    scope.close();
    scope.close();
    for (LanguageContext context : List.of(source, captured, nested)) {
      assertNull(context.memoScope);
      LanguageFailure missing =
          assertThrows(
              LanguageFailure.class, () -> LanguageSemantics.strict(reference, context, "value"));
      assertEquals("ProviderUnavailable", missing.category());
    }
    source.work.set(0);
    LanguageSemantics.strict(stable, source, "value");
    assertTrue(source.work.get() < 10, "Pre-scope independent facts must survive revocation");
    assertThrows(IllegalStateException.class, () -> scope.attach(new LanguageContext()));
  }

  private static LanguageContext copy(LanguageContext source) {
    LanguageContext copy =
        new LanguageContext(
            source.limits,
            new Language.Snapshot(source.bodies, source.proofs),
            source.aliases,
            source.directiveAliases,
            source.provider);
    source.snapshotHeadsTo(copy);
    return copy;
  }
}
