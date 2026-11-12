package blue.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import blue.BlueException;
import blue.Contracts;
import blue.Language;
import blue.LanguageDocument;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Host inspection limits bound one read, while reusable sessions can serve many reads. */
@Tag("contracts")
class ReadBudgetTest {
  private static Language boundedLanguage() {
    return Language.newBuilder()
        .limits(new Language.Limits(100_000, 256, 1_000_000, 100_000, 16L << 20, 65_536, 65_536))
        .build();
  }

  @Test
  void independentReadsDoNotExhaustALifetimeAllowance() {
    String source = "a: {b: {c: {d: {e: {value: 7}}}}}";
    try (Contracts.Session canonical =
            Contracts.newBuilder().language(boundedLanguage()).build().openYaml(source);
        Contracts.Session local =
            Contracts.newBuilder()
                .language(boundedLanguage())
                .build()
                .open(Language.create().readYaml(source))) {
      Object expected = canonical.read("");
      for (int i = 0; i < 10_000; i++) {
        assertEquals(expected, canonical.read(""));
        assertEquals(expected, local.read(""));
      }
    }
  }

  @Test
  void oneExpensiveReadStillFailsAndDoesNotPoisonALaterSelectedRead() {
    LanguageDocument node = Language.create().readYaml("leaf: 7");
    for (int i = 0; i < 16; i++) {
      Language language = Language.newBuilder().content(node).build();
      String reference = "{\"blueId\":\"" + node.blueId().value() + "\"}";
      node = language.readJson("{\"left\":" + reference + ",\"right\":" + reference + "}");
    }
    try (Contracts.Session session =
        Contracts.newBuilder().language(boundedLanguage()).build().open(node)) {
      BlueException failure = assertThrows(BlueException.class, () -> session.read(""));
      assertEquals("HOST_INCOMPLETE", failure.code());
      assertEquals(
          Map.of("leaf", java.math.BigInteger.valueOf(7)), session.read("/left".repeat(16)));
    }
  }
}
