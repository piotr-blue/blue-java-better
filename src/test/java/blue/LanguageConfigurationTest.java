package blue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Immutable configuration forks retain evidence without sharing mutable validation state. */
@Tag("language")
class LanguageConfigurationTest {
  @Test
  void configurationForkSharesEvidenceAndIsolatesBuilderEdits() {
    LanguageDocument point = Language.create().defineYaml("x: {type: Integer}");
    Language source = Language.newBuilder().type("Shapes/Point", point).build();
    Language.Builder fork = source.toBuilder();
    Language copied = fork.build();
    assertSame(
        LanguageMap.immutable(source.context().bodies),
        LanguageMap.immutable(copied.context().bodies));
    fork.alias("Shapes/Other", point.blueId());
    Language changed = fork.build();
    assertEquals(
        source.readYaml("type: Shapes/Point\nx: 3").blueId(),
        changed.readYaml("type: Shapes/Other\nx: 3").blueId());
    assertThrows(LanguageException.class, () -> copied.readYaml("type: Shapes/Other\nx: 3"));
    assertThrows(LanguageException.class, () -> source.readYaml("type: Shapes/Other\nx: 3"));
    LanguageContext first = copied.context();
    first.validation.add(new LanguageContext.ValidationKey("test", "value", true));
    assertTrue(copied.context().validation.isEmpty());
  }

  @Test
  void retainedContentDoesNotRebuildItsCatalogueDuringProtocolReads() {
    LanguageDocument definition = Language.create().defineYaml("x: {type: Integer}");
    Language reader = Language.newBuilder().content(definition).build().toBuilder().build();
    LanguageContent input = reader.prepare(Language.Input.yaml("value: 7"));
    LanguageContext context = reader.context(input, definition);
    assertSame(input.snapshot.bodies(), LanguageMap.immutable(context.bodies));
    LanguageDocument foreign = Language.create().readYaml("separate: true");
    assertTrue(reader.context(input, foreign).bodies.containsKey(foreign.blueId().value()));
    assertFalse(input.snapshot.bodies().containsKey(foreign.blueId().value()));
  }

  @Test
  void forksPreserveLimitsAndOrderedProviders() {
    LanguageDocument value = Language.create().readYaml("answer: 42");
    Language.Limits limits = new Language.Limits(100_000, 256, 10_000, 100, 1024, 128, 128);
    Language source =
        Language.newBuilder()
            .limits(limits)
            .provider(
                id ->
                    id.equals(value.blueId())
                        ? Optional.of(LanguageRecord.json(value.toJson()))
                        : Optional.empty())
            .build();
    Language fork =
        source.toBuilder()
            .provider(
                id -> {
                  throw new AssertionError("Existing provider must be consulted first");
                })
            .build();
    assertEquals(value.blueId(), fork.define(value.blueId()).blueId());
    assertThrows(LanguageException.class, () -> fork.readYaml("x: " + "a".repeat(1024)));
  }
}
