package blue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Bounded semantic facts owned by a verifier or one protected owner attempt. Keys bind exact
 * recipes, goals and the Language edition. No content bodies or authority decisions are cached.
 */
final class LanguageFacts {
  private Function<String, Optional<Boolean>> retained;

  LanguageFacts() {}

  LanguageFacts(Function<String, Optional<Boolean>> retained) {
    this.retained = Objects.requireNonNull(retained);
  }

  private static String key(String kind, String detail) {
    return Language.EDITION + "/" + kind + "/" + detail;
  }

  private static String validationKey(LanguageContext.ValidationKey value) {
    return key("validate", value.goal() + "/" + value.root() + "/" + value.recipe());
  }

  private Boolean retained(String key) {
    return retained == null ? null : retained.apply(key).orElse(null);
  }

  void revoke() {
    retained = null;
    validation.clear();
    closures.clear();
    concreteness.clear();
  }

  static Map<String, Boolean> export(LanguageContext context, Predicate<String> retained) {
    var records = new LinkedHashMap<String, Boolean>();
    for (var item : context.validation) {
      if (context.facts != null && context.facts.validated(item)) continue;
      // A recipe binds both its exact parts and requirement types. Validation alone does not
      // establish that transient intermediate content was included in a durable publication.
      List<?> recipe = (List<?>) LanguageCodec.parse(item.recipe(), "json");
      boolean available = true;
      for (Object group : recipe)
        for (Object id : (List<?>) group) available &= retained.test((String) id);
      if (available) records.put(validationKey(item), true);
    }
    for (String id : context.verifiedClosures)
      if ((context.facts == null || !context.facts.verified(id)) && retained.test(id))
        records.put(key("closure", id), true);
    context.concreteFacts.forEach(
        (id, value) -> {
          if ((context.facts == null || !Objects.equals(context.facts.concrete(id), value))
              && retained.test(id)) records.put(key("concrete", id), value);
        });
    return Map.copyOf(records);
  }

  private final ContractCache<LanguageContext.ValidationKey, Boolean> validation =
      new ContractCache<>("timeline.schema", 4L * 1024 * 1024, 16384, 4096);
  private final ContractCache<String, Boolean> closures =
      new ContractCache<>("timeline.closure", 2L * 1024 * 1024, 8192, 512);

  private final ContractCache<String, Boolean> concreteness =
      new ContractCache<>("timeline.concreteness", 2L * 1024 * 1024, 8192, 512);

  Boolean concrete(String id) {
    Boolean value = concreteness.get(id);
    if (value == null) {
      value = retained(key("concrete", id));
      if (value != null) concreteness.put(id, value, 96L + id.length() * 2L);
    }
    return value;
  }

  boolean validated(LanguageContext.ValidationKey key) {
    Boolean cached = validation.get(key);
    if (cached != null) return cached;
    boolean found = Boolean.TRUE.equals(retained(validationKey(key)));
    // A miss only selects ordinary validation. A later successful local check replaces it.
    validation.put(key, found, 128L + key.recipe().length() * 2L);
    return found;
  }

  boolean verified(String id) {
    Boolean cached = closures.get(id);
    if (cached != null) return cached;
    boolean found = Boolean.TRUE.equals(retained(key("closure", id)));
    closures.put(id, found, 96L + id.length() * 2L);
    return found;
  }

  /** Protected preparation has already retained these exact completed checks. */
  void restored(LanguageContext.ValidationKey key) {
    validation.put(key, Boolean.TRUE, 128L + key.recipe().length() * 2L);
  }

  void restoredConcrete(String id, boolean value) {
    concreteness.put(id, value, 96L + id.length() * 2L);
  }

  void restoredClosure(String id) {
    closures.put(id, Boolean.TRUE, 96L + id.length() * 2L);
  }

  /** Called only after successful protocol validation; failed attempts publish no new facts. */
  void remember(LanguageContext context) {
    for (var key : context.validation)
      validation.put(key, Boolean.TRUE, 128L + key.recipe().length() * 2L);
    for (String id : context.verifiedClosures)
      closures.put(id, Boolean.TRUE, 96L + id.length() * 2L);
    context.concreteFacts.forEach(
        (id, concrete) -> concreteness.put(id, concrete, 96L + id.length() * 2L));
  }
}
