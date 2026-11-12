package blue.language;

import static blue.Language.Goal.*;
import static org.junit.jupiter.api.Assertions.*;

import blue.Language;
import blue.LanguageException;
import blue.LanguageRecord;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("language")
class LanguageLimitsTest {
  private final Language language = Language.create();

  @Test
  void resourceExhaustionIsIncompleteAndIndependentOfPriorSuccess() {
    var small = new Language.Limits(0, 256, 10000, 100, 100000, 1000, 1000);
    var limited = Language.newBuilder().limits(small).build();
    assertTrue(assertThrows(LanguageException.class, () -> limited.readJson("1")).incomplete());
    assertEquals(1, language.readJson("1").resolve().integer());
  }

  @Test
  void providerAndCyclicProofParsingHonorHostInputBounds() {
    var large = language.readYaml("value: '" + "x".repeat(5000) + "'");
    var limited =
        Language.newBuilder()
            .limits(new Language.Limits(100000, 256, 100000, 100, 1024, 1000, 1000))
            .provider(id -> Optional.of(LanguageRecord.json(large.toJson())))
            .build();
    var ref = limited.prepare(Language.Input.exactJson("{\"blueId\":\"" + large.blueId() + "\"}"));
    assertEquals(
        "OperationLimitExceeded",
        assertThrows(LanguageException.class, () -> ref.resolve().text()).category());
    var set =
        language.finalizeSet(
            List.of(
                Language.Input.exactJson("{\"name\":\"Loop\",\"self\":{\"blueId\":\"this#0\"}}")),
            VALUE);
    var proof = " ".repeat(5000) + set.proofJson();
    var cyclic =
        Language.newBuilder()
            .limits(new Language.Limits(100000, 256, 100000, 100, 1024, 1000, 1000))
            .record(set.members().getFirst().blueId(), LanguageRecord.cyclic(proof))
            .build();
    assertTrue(
        assertThrows(
                LanguageException.class,
                () ->
                    cyclic.read(Language.Input.exactJson(set.members().getFirst().toJson()), VALUE))
            .incomplete());
  }
}
