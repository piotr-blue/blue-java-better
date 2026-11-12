package blue;

import static blue.ExactNode.list;
import static blue.ExactNode.scalar;
import static blue.ExactNode.value;
import static blue.LanguageCore.identity;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.ExactNode.Value;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RetainedClosureExpansionTest {
  private static final class CountingSet extends HashSet<String> {
    private static final long serialVersionUID = 1L;
    int additions;

    @Override
    public boolean add(String value) {
      additions++;
      return super.add(value);
    }
  }

  private static Value rows(int count) {
    var fields = new TreeMap<String, Object>();
    for (int i = 0; i < count; i++) fields.put("row-" + i, scalar("Text", "value-" + i));
    return value(fields);
  }

  @ParameterizedTest
  @ValueSource(ints = {10, 1000, 10000})
  void repeatedReadsDoNotWalkUnchangedSiblings(int count) {
    var context = new LanguageContext();
    var evidence = new CountingSet();
    context.verifiedClosures = evidence;
    Value body = rows(count);
    evidence.add(identity(body));
    context.durableClosures.add(identity(body));
    evidence.additions = 0;
    for (int i = 0; i < 100; i++) assertSame(body, context.retainedBody(body));
    assertEquals(count, evidence.additions, "Only the first read may expand child evidence");
    for (Object field : body.fields().values()) {
      String child = identity((ExactNode) field);
      assertTrue(context.durableClosures.contains(child));
      assertTrue(context.verifiedClosure(child));
    }
  }

  @Test
  void storageExpansionDoesNotEstablishAuthorityButLaterValidationDoes() {
    var context = new LanguageContext();
    Value body = rows(2);
    context.facts = new LanguageFacts();
    context.durableClosures.add(identity(body));
    context.retainedBody(body);
    String child = identity((ExactNode) body.get("row-0"));
    assertTrue(context.durableClosures.contains(child));
    assertFalse(context.verifiedClosure(child));
    context.verifiedClosures.add(identity(body));
    context.retainedBody(body);
    assertTrue(context.verifiedClosure(child));
    assertTrue(context.facts.verified(child));
  }

  @Test
  void sharedStorageExpansionCannotSkipAnIndependentViewsValidation() {
    var left = new LanguageContext();
    var right = new LanguageContext();
    right.durableClosures = left.durableClosures;
    Value body = rows(3);
    left.durableClosures.add(identity(body));
    left.verifiedClosures.add(identity(body));
    left.retainedBody(body);
    right.retainedBody(body);
    assertTrue(right.verifiedClosures.isEmpty());
    right.verifiedClosures.add(identity(body));
    right.retainedBody(body);
    assertEquals(left.verifiedClosures, right.verifiedClosures);
  }

  @Test
  void clearingSharedRetentionInvalidatesEveryViewsExpansion() {
    var left = new LanguageContext();
    var right = new LanguageContext();
    right.durableClosures = left.durableClosures;
    var evidence = new CountingSet();
    right.verifiedClosures = evidence;
    Value body = rows(3);
    evidence.add(identity(body));
    left.durableClosures.add(identity(body));
    right.retainedBody(body);
    left.durableClosures.clear();
    left.durableClosures.add(identity(body));
    evidence.additions = 0;
    right.retainedBody(body);
    assertEquals(3, evidence.additions);
    assertTrue(right.durableClosures.contains(identity((ExactNode) body.get("row-1"))));
  }

  @Test
  void temporaryEvidenceAndItsMemoAreBothRevoked() {
    var context = new LanguageContext();
    Value body = rows(2);
    String root = identity(body);
    String child = identity((ExactNode) body.get("row-0"));
    context.durableClosures.add(root);
    try (var scope = new LanguageMemoScope()) {
      scope.attach(context);
      context.verifiedClosures.add(root);
      context.retainedBody(body);
      assertTrue(context.verifiedClosure(child));
    }
    context.retainedBody(body);
    assertFalse(context.verifiedClosure(root));
    assertFalse(context.verifiedClosure(child));
    try (var scope = new LanguageMemoScope()) {
      scope.attach(context);
      context.verifiedClosures.add(root);
      context.retainedBody(body);
      assertTrue(context.verifiedClosure(child));
    }
    assertFalse(context.verifiedClosure(child));
  }

  @Test
  void replacementFactsReceiveTheirOwnEvidence() {
    var context = new LanguageContext();
    Value body = rows(1);
    String child = identity((ExactNode) body.get("row-0"));
    context.durableClosures.add(identity(body));
    context.verifiedClosures.add(identity(body));
    context.facts = new LanguageFacts();
    context.retainedBody(body);
    assertTrue(context.facts.verified(child));
    context.facts = new LanguageFacts();
    assertFalse(context.facts.verified(child));
    context.retainedBody(body);
    assertTrue(context.facts.verified(child));
  }

  @Test
  void listsAndNestedBodiesExpandLazily() {
    var context = new LanguageContext();
    Value nested = rows(2);
    Value body = list(List.of(nested, scalar("Text", "other")));
    context.durableClosures.add(identity(body));
    context.verifiedClosures.add(identity(body));
    context.retainedBody(body);
    String leaf = identity((ExactNode) nested.get("row-0"));
    assertTrue(context.verifiedClosure(identity(nested)));
    assertFalse(context.verifiedClosure(leaf));
    context.retainedBody(nested);
    assertTrue(context.verifiedClosure(leaf));
    assertTrue(context.durableClosures.contains(leaf));
  }

  @Test
  void anUnretainedBodyCannotLendItsValidationToChildren() {
    var context = new LanguageContext();
    Value body = rows(1);
    context.verifiedClosures.add(identity(body));
    context.retainedBody(body);
    assertEquals(Set.of(identity(body)), context.verifiedClosures);
    assertTrue(context.durableClosures.isEmpty());
  }
}
