package blue;

import static blue.ExactNode.scalar;
import static blue.ExactNode.value;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Storage reads for borrowed validation depend on changed obligations, not equal siblings. */
class LocalValidationReadsTest {
  @ParameterizedTest
  @ValueSource(ints = {10, 1000, 10000})
  void unchangedSiblingsUseTheCompletedParentProofBeforeQueryingStorage(int count) {
    var context = new LanguageContext();
    Map<String, Object> fields = new TreeMap<>();
    for (int i = 0; i < count; i++) {
      fields.put("row-" + i, scalar("Text", "value-" + i));
    }
    fields.put("changed", scalar("Text", "before"));
    ExactNode before = value(fields);
    LanguageSemantics.strict(before, context, "value");
    Map<String, Boolean> retained = LanguageFacts.export(context, id -> true);
    context.validation.clear();
    context.verifiedClosures.clear();
    context.durableClosures.add(LanguageCore.identity(before));
    AtomicInteger reads = new AtomicInteger();
    context.facts =
        new LanguageFacts(
            key -> {
              reads.incrementAndGet();
              return Optional.ofNullable(retained.get(key));
            });
    fields.put("changed", scalar("Text", "after"));
    ExactNode after = value(fields);
    LanguageSemantics.inheritUnchangedValidation(after, before, context);
    assertTrue(reads.get() <= 12, "Unchanged siblings caused " + reads.get() + " store reads");
    LanguageSemantics.strict(after, context, "value");
    assertEquals(
        "after",
        LanguageSemantics.head(
                LanguageSemantics.child(
                    LanguageSemantics.head(LanguageSemantics.recipe(after), context), "changed"),
                context)
            .value);
  }
}
