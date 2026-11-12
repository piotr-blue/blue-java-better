package blue.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import blue.BlueException;
import blue.Contracts;
import blue.Language;
import blue.LanguageDocument;
import blue.ProjectionLimits;
import blue.examples.CanonicalAgreement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Explicit output bounds cover business data, literal contracts and collapsed cyclic references.
 */
@Tag("contracts")
class ProjectionLimitsTest {
  @Test
  void exactBoundsPreserveDataAndRejectOneUnitLess() {
    for (String source :
        List.of(
            "name: 'World 🌍'\ndescription: Sample\nrows: [{x: 1}, {x: 1}]\nempty: []",
            CanonicalAgreement.source("agreement"))) {
      try (Contracts.Session session = Contracts.create().openYaml(source)) {
        assertBounds(session);
        session.initialize().requireApplied();
        assertBounds(session);
      }
    }
  }

  @Test
  void typedAndCyclicReferencesAreCountedAsTheirReturnedShape() {
    Language language = Language.create();
    LanguageDocument type = language.defineYaml("name: Point\nx: {type: Integer}");
    LanguageDocument instance = type.specializeYaml("x: 3");
    LanguageDocument loop =
        language
            .finalizeSet(
                List.of(
                    Language.Input.exactJson(
                        "{\"name\":\"Loop\",\"self\":{\"blueId\":\"this#0\"}}")),
                Language.Goal.VALUE)
            .members()
            .getFirst();
    for (LanguageDocument document : List.of(instance, loop)) {
      try (Contracts.Session session = Contracts.create().open(document)) {
        assertBounds(session);
      }
    }
  }

  @Test
  void missingValuesAndLocalSessionsHaveTheSameInclusiveBounds() {
    try (Contracts.Session session = Contracts.create().openYaml("count: 3")) {
      Object expected = session.read("");
      assertEquals(expected, session.read("", new ProjectionLimits(3, 5, 1)));
      limited(() -> session.read("", new ProjectionLimits(2, 5, 1)));
      assertSame(blue.Bex.undefined(), session.read("/missing", new ProjectionLimits(1, 0, 0)));
      limited(() -> session.read("/missing", new ProjectionLimits(0, 0, 0)));
      assertEquals(expected, session.read(""));
    }
  }

  @Test
  void sharedReferencesCannotExpandPastAnExplicitSmallOutputAllowance() {
    LanguageDocument node = Language.create().readYaml("leaf: 7");
    for (int i = 0; i < 18; i++) {
      Language language = Language.newBuilder().content(node).build();
      String ref = "{\"blueId\":\"" + node.blueId().value() + "\"}";
      node = language.readJson("{\"left\":" + ref + ",\"right\":" + ref + "}");
    }
    try (Contracts.Session session = Contracts.create().open(node)) {
      limited(() -> session.read("", new ProjectionLimits(100, 1000, 256)));
      assertEquals(
          Map.of("leaf", java.math.BigInteger.valueOf(7)),
          session.read("/left".repeat(18), new ProjectionLimits(3, 4, 1)));
    }
  }

  @Test
  void negativeBoundsAreRejected() {
    assertThrows(IllegalArgumentException.class, () -> new ProjectionLimits(-1, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> new ProjectionLimits(0, -1, 0));
    assertThrows(IllegalArgumentException.class, () -> new ProjectionLimits(0, 0, -1));
  }

  private static void assertBounds(Contracts.Session session) {
    Object expected = session.read("");
    long[] shape = shape(expected, 0);
    ProjectionLimits exact = new ProjectionLimits(shape[0], shape[1], Math.toIntExact(shape[2]));
    assertEquals(expected, session.read("", exact));
    limited(() -> session.read("", new ProjectionLimits(shape[0] - 1, shape[1], exact.depth())));
    limited(() -> session.read("", new ProjectionLimits(shape[0], shape[1] - 1, exact.depth())));
    limited(() -> session.read("", new ProjectionLimits(shape[0], shape[1], exact.depth() - 1)));
    assertEquals(expected, session.read("", exact));
  }

  private static void limited(Runnable action) {
    assertEquals(
        "PROJECTION_LIMIT_EXCEEDED", assertThrows(BlueException.class, action::run).code());
  }

  private static long[] shape(Object value, int depth) {
    long[] result = {1, value instanceof String text ? text.length() : 0, depth};
    if (value instanceof Map<?, ?> map) {
      for (var entry : map.entrySet()) {
        result[0]++;
        result[1] += ((String) entry.getKey()).length();
        merge(result, shape(entry.getValue(), depth + 1));
      }
    } else if (value instanceof List<?> list) {
      for (Object item : list) {
        merge(result, shape(item, depth + 1));
      }
    }
    return result;
  }

  private static void merge(long[] into, long[] child) {
    into[0] += child[0];
    into[1] += child[1];
    into[2] = Math.max(into[2], child[2]);
  }
}
