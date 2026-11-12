package blue;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Immutable observations must preserve every authored facet and isolate later draft mutations. */
@Tag("language")
class LanguageSchemaTest {
  @Test
  void emptyPublishedSchemasShareValuesWithoutSharingMutableDrafts() {
    LanguageSchema draft = new LanguageSchema();
    LanguageSchema published = draft.frozen();
    assertSame(new LanguageSchema().frozen(), published);
    assertSame(published, published.frozen());
    var observations = ContractLanguage.Constraints.of(draft);
    assertSame(observations, ContractLanguage.Constraints.of(new LanguageSchema()));
    draft.required = false;
    draft.counts.put("minItems", BigInteger.ZERO);
    assertNull(published.required);
    assertTrue(published.counts.isEmpty());
    assertEquals(Map.of(), observations.describe());
    assertThrows(
        UnsupportedOperationException.class,
        () -> published.counts.put("minItems", BigInteger.ONE));
    LanguageSchema copy = published.copy();
    copy.unique = true;
    assertNull(published.unique);
  }

  @Test
  void unconstrainedHeadsAcquireIndependentConstraintsWhenCopiedForEditing() {
    LanguageModel.Head original = new LanguageSemantics.HeadDraft().build();
    LanguageModel.Head sibling = new LanguageSemantics.HeadDraft().build();
    LanguageSemantics.HeadDraft changed = original.copy();
    changed.constraints.required = false;
    changed.constraints.counts.put("minItems", BigInteger.ZERO);
    LanguageModel.Head published = changed.build();
    changed.constraints.counts.put("minItems", BigInteger.TEN);
    assertEquals(Map.of(), original.constraints.describe());
    assertEquals(Map.of(), sibling.constraints.describe());
    assertEquals(
        Map.of("required", false, "minItems", BigInteger.ZERO), published.constraints.describe());
  }

  @Test
  void explicitFalseAndZeroRemainObservable() {
    LanguageSchema draft = new LanguageSchema();
    draft.required = false;
    draft.unique = false;
    draft.counts.put("minItems", BigInteger.ZERO);
    var observations = ContractLanguage.Constraints.of(draft);
    assertNotSame(LanguageSchema.EMPTY, draft.frozen());
    assertEquals(
        Map.of("required", false, "uniqueItems", false, "minItems", BigInteger.ZERO),
        observations.describe());
    draft.required = true;
    draft.unique = true;
    draft.counts.put("minItems", BigInteger.TEN);
    assertEquals(false, observations.unique);
    assertEquals(BigInteger.ZERO, observations.counts.get("minItems"));
    assertEquals(false, observations.describe().get("required"));
  }

  @Test
  void constraintCopiesRemainIndependentAfterPublicationAndConjunction() {
    LanguageSchema draft = new LanguageSchema();
    draft.counts.put("minLength", BigInteger.ONE);
    LanguageSchema frozen = draft.frozen();
    LanguageSchema combined = LanguageSchema.conjoin(frozen, LanguageSchema.EMPTY);
    combined.counts.put("minLength", BigInteger.TEN);
    draft.counts.clear();
    assertEquals(Map.of("minLength", BigInteger.ONE), frozen.describe());
    assertEquals(Map.of(), LanguageSchema.EMPTY.describe());
  }
}
