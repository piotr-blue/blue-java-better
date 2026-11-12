package blue.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.BlueException;
import blue.BlueId;
import blue.Contracts;
import blue.Language;
import blue.LanguageDocument;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Type Generalization Policy: when an update leaves a typed value invalid for its declared type,
 * the policy either rejects the update or re-types the value as its nearest ancestor that admits
 * it. Base ← Mid ← Leaf each add one required field, so removing a field selects the ancestor.
 */
@Tag("contracts")
class TypeGeneralizationTest {
  private static final Language PROTOCOL =
      Language.newBuilder().mount("Contracts", Contracts.publication()).build();
  private static final LanguageDocument BASE =
      PROTOCOL.defineYaml("name: Base\nphase: {type: Text}");
  private static final LanguageDocument MID =
      BASE.extendYaml("name: Mid\ndatum: {type: Text, schema: {required: true}}");
  private static final LanguageDocument LEAF =
      MID.extendYaml("name: Leaf\nextra: {type: Text, schema: {required: true}}");
  private static final Contracts CONTRACTS =
      Contracts.newBuilder()
          .language(
              PROTOCOL.toBuilder().type("Base", BASE).type("Mid", MID).type("Leaf", LEAF).build())
          .build();

  private static final String NO_POLICY = "";
  private static final String REMOVE_EXTRA = "[{op: remove, path: /item/extra}]";
  private static final String REMOVE_DATUM = "[{op: remove, path: /item/datum}]";

  private static final String DOCUMENT =
      """
      item: {type: Leaf, phase: initial, datum: present, extra: more}
      other: {type: Leaf, phase: initial, datum: present, extra: more}
      nested:
        item: {type: Leaf, phase: initial, datum: present, extra: more}
      contracts:
      %s  input: {type: Contracts/External Channel}
        change:
          type: Contracts/Sequential Workflow Operation
          channel: input
          steps:
            - type: Contracts/Update Document
              changeset: %s
      """;

  /** The outcome of one update, with the affected members read back from the session. */
  private record Outcome(
      Contracts.Transition transition, Object item, Object other, Object nested, BlueId identity) {
    String diagnostic() {
      return String.valueOf(((Map<?, ?>) transition.observations().get("diagnostic")).get("code"));
    }
  }

  private static String policy(String defaultMode, String... rules) {
    StringBuilder text =
        new StringBuilder("  generalization:\n")
            .append("    type: Contracts/Type Generalization Policy\n")
            .append("    defaultMode: ")
            .append(defaultMode)
            .append('\n');
    if (rules.length > 0) {
      text.append("    rules:\n");
      for (String rule : rules) {
        text.append("      - ").append(rule).append('\n');
      }
    }
    return text.toString();
  }

  /** A rule whose floor is written as a type reference, the form the published rule expects. */
  private static String floorAtMid() {
    return policy(
        "reject",
        "{path: /item, mode: nearest-valid-ancestor, mustRemainSubtypeOf: {blueId: "
            + MID.blueId()
            + "}}");
  }

  private static Outcome update(String policy, String changeset) {
    try (Contracts.Session session = CONTRACTS.openYaml(DOCUMENT.formatted(policy, changeset))) {
      session.initialize().requireApplied();
      Contracts.Transition transition = session.call("input", "change", Map.of(), 1L);
      return new Outcome(
          transition,
          session.read("/item"),
          session.read("/other"),
          session.read("/nested/item"),
          session.document().blueId());
    }
  }

  private static Object leaf() {
    return member(LEAF, true, true);
  }

  private static Object member(LanguageDocument type, boolean datum, boolean extra) {
    var fields = new TreeMap<String, Object>();
    fields.put("type", Map.of("blueId", type.blueId().value()));
    fields.put("phase", "initial");
    if (datum) {
      fields.put("datum", "present");
    }
    if (extra) {
      fields.put("extra", "more");
    }
    return fields;
  }

  private static void assertRejected(Outcome outcome, String code) {
    assertEquals("FAILED", outcome.transition().status());
    assertFalse(outcome.transition().applied());
    assertEquals(code, outcome.diagnostic());
    assertEquals(leaf(), outcome.item());
    assertEquals(leaf(), outcome.other());
    assertEquals(leaf(), outcome.nested());
  }

  @Test
  void withoutAPolicyAnInvalidatingUpdateIsRejectedAndNothingIsWritten() {
    assertRejected(update(NO_POLICY, REMOVE_EXTRA), "LANGUAGE_SchemaViolation");
  }

  @Test
  void theRejectDefaultBehavesLikeNoPolicy() {
    Outcome rejected = update(policy("reject"), REMOVE_EXTRA);
    assertRejected(rejected, "LANGUAGE_SchemaViolation");
  }

  @Test
  void nearestValidAncestorKeepsTheClosestTypeThatAdmitsTheValue() {
    Outcome outcome = update(policy("nearest-valid-ancestor"), REMOVE_EXTRA);
    assertTrue(outcome.transition().applied());
    assertEquals(member(MID, true, false), outcome.item());
    assertEquals(leaf(), outcome.other());
    assertEquals(leaf(), outcome.nested());
  }

  @Test
  void ancestorsThatAreStillInvalidAreSkipped() {
    // Mid also requires datum, so only Base admits the value; the unrelated field is retained.
    Outcome outcome = update(policy("nearest-valid-ancestor"), REMOVE_DATUM);
    assertTrue(outcome.transition().applied());
    assertEquals(member(BASE, false, true), outcome.item());
    assertEquals(leaf(), outcome.other());
  }

  @Test
  void laterChangesInOneChangesetContinueFromTheGeneralizedType() {
    Outcome outcome =
        update(
            policy("nearest-valid-ancestor"),
            "[{op: remove, path: /item/extra}, {op: remove, path: /item/datum}]");
    assertTrue(outcome.transition().applied());
    assertEquals(member(BASE, false, false), outcome.item());
  }

  @Test
  void anUpdateThatStaysValidIsNeverGeneralized() {
    Outcome outcome =
        update(policy("nearest-valid-ancestor"), "[{op: replace, path: /item/phase, val: next}]");
    assertTrue(outcome.transition().applied());
    Map<?, ?> item = (Map<?, ?>) outcome.item();
    assertEquals(Map.of("blueId", LEAF.blueId().value()), item.get("type"));
    assertEquals("next", item.get("phase"));
  }

  @Test
  void repairIsDeterministicAcrossIndependentSessions() {
    Outcome first = update(policy("nearest-valid-ancestor"), REMOVE_EXTRA);
    Outcome second = update(policy("nearest-valid-ancestor"), REMOVE_EXTRA);
    assertEquals(first.identity(), second.identity());
    assertEquals(first.transition().gas(), second.transition().gas());
    assertEquals(first.transition().receipt(), second.transition().receipt());
  }

  @Test
  void aRuleEnablesGeneralizationOnlyBeneathItsPath() {
    String onlyItem = policy("reject", "{path: /item, mode: nearest-valid-ancestor}");
    assertEquals(member(MID, true, false), update(onlyItem, REMOVE_EXTRA).item());
    assertRejected(
        update(onlyItem, "[{op: remove, path: /other/extra}]"), "LANGUAGE_SchemaViolation");
  }

  @Test
  void aRejectRuleOverridesAPermissiveDefault() {
    String exceptItem = policy("nearest-valid-ancestor", "{path: /item, mode: reject}");
    assertRejected(update(exceptItem, REMOVE_EXTRA), "LANGUAGE_SchemaViolation");
    Outcome elsewhere = update(exceptItem, "[{op: remove, path: /other/extra}]");
    assertEquals(member(MID, true, false), elsewhere.other());
    assertEquals(leaf(), elsewhere.item());
  }

  @Test
  void theDeepestMatchingRuleWinsWhateverItsPosition() {
    String removeNested = "[{op: remove, path: /nested/item/extra}]";
    String deepAllows =
        policy(
            "reject",
            "{path: /nested/item, mode: nearest-valid-ancestor}",
            "{path: /nested, mode: reject}");
    assertEquals(member(MID, true, false), update(deepAllows, removeNested).nested());
    String deepRejects =
        policy(
            "reject",
            "{path: /nested/item, mode: reject}",
            "{path: /nested, mode: nearest-valid-ancestor}");
    assertRejected(update(deepRejects, removeNested), "LANGUAGE_SchemaViolation");
  }

  @Test
  void aFloorAdmitsGeneralizationDownToTheFloorItself() {
    Outcome outcome = update(floorAtMid(), REMOVE_EXTRA);
    assertTrue(outcome.transition().applied());
    assertEquals(member(MID, true, false), outcome.item());
  }

  @Test
  void aFloorStopsGeneralizationThatWouldPassIt() {
    Outcome outcome = update(floorAtMid(), REMOVE_DATUM);
    assertRejected(outcome, "TYPE_GENERALIZATION_FLOOR");
    assertEquals(
        "/item", ((Map<?, ?>) outcome.transition().observations().get("diagnostic")).get("path"));
  }

  @Test
  void aValueNoAncestorAdmitsFailsTheWholeUpdate() {
    // Every type in the chain declares phase as Text, so no generalization can admit an integer.
    Outcome outcome =
        update(policy("nearest-valid-ancestor"), "[{op: replace, path: /item/phase, val: 5}]");
    assertRejected(outcome, "TYPE_GENERALIZATION_FAILURE");
  }

  @Test
  void anUnknownModeIsRejectedBeforeAnyUpdateRuns() {
    BlueException failure =
        assertThrows(
            BlueException.class,
            () -> {
              String source = DOCUMENT.formatted(policy("sideways"), REMOVE_EXTRA);
              try (Contracts.Session session = CONTRACTS.openYaml(source)) {
                session.initialize();
              }
            });
    assertEquals("INVALID_GENERALIZATION_POLICY", failure.code());
  }
}
