package blue;

import blue.ExactNode.Ref;
import java.util.List;
import java.util.Map;

final class LanguageModel {
  private LanguageModel() {}

  /**
   * Immutable recipe normalized by LanguageSemantics.recipeWith: last representation per identity,
   * first occurrence per requirement. Its diagnostic/cache key is computed at most once.
   */
  static final class Recipe {
    final List<ExactNode> parts;
    final List<String> requirements;
    private volatile String cachedKey;
    private volatile String cachedHeadKey;

    Recipe(List<ExactNode> parts, List<String> requirements) {
      this.parts = List.copyOf(parts);
      this.requirements = List.copyOf(requirements);
    }

    public List<ExactNode> parts() {
      return parts;
    }

    public List<String> requirements() {
      return requirements;
    }

    public String key() {
      String key = cachedKey;
      if (key == null) {
        cachedKey =
            key =
                LanguageCodec.write(
                    List.of(parts.stream().map(LanguageCore::identity).toList(), requirements));
      }
      return key;
    }

    // Retain the namespaced lookup key as well: rebuilding it on every head
    // read also forces repeated String hashing for the same immutable recipe.
    String headKey() {
      String key = cachedHeadKey;
      if (key == null) {
        cachedHeadKey = key = "recipe:" + key();
      }
      return key;
    }

    @Override
    public boolean equals(Object other) {
      return this == other
          || other instanceof Recipe r
              && parts.equals(r.parts)
              && requirements.equals(r.requirements);
    }

    @Override
    public int hashCode() {
      return 31 * parts.hashCode() + requirements.hashCode();
    }

    @Override
    public String toString() {
      return "Recipe[parts=" + parts + ", requirements=" + requirements + "]";
    }
  }

  /**
   * Immutable interpretation result; construction and mutation are confined to
   * LanguageSemantics.HeadDraft.
   */
  static final class Head {
    private volatile ContractLanguage.Constraints nativeConstraints;

    ContractLanguage.Constraints constraints() {
      ContractLanguage.Constraints result = nativeConstraints;
      if (result == null) {
        nativeConstraints = result = ContractLanguage.Constraints.of(constraints);
      }
      return result;
    }

    final String type, core, kind, policy, itemType, keyType, valueType;
    final Map<String, Object> labels;
    final Ref specification;
    final LanguageSchema constraints, authoredSchema;
    final Object value;
    final Map<String, Recipe> fields;
    final List<Recipe> items;
    final Recipe contracts;
    final boolean emptyObject;
    final ExactNode origin;
    final List<ExactNode> origins;

    Head() {
      this(new LanguageSemantics.HeadDraft());
    }

    Head(LanguageSemantics.HeadDraft d) {
      type = d.type;
      core = d.core;
      kind = d.kind;
      policy = d.policy;
      itemType = d.itemType;
      keyType = d.keyType;
      valueType = d.valueType;
      labels = HeadMap.freeze(d.labels);
      specification = d.specification;
      constraints = d.constraints.frozen();
      authoredSchema = d.authoredSchema == null ? null : d.authoredSchema.frozen();
      value = d.value;
      fields = HeadMap.freeze(d.fields);
      items = d.items == null ? null : List.copyOf(d.items);
      contracts = d.contracts;
      emptyObject = d.emptyObject;
      origin = d.origin;
      origins = d.origins == null ? null : List.copyOf(d.origins);
    }

    LanguageSemantics.HeadDraft copy() {
      return copy(true);
    }

    LanguageSemantics.HeadDraft copy(boolean copyConstraints) {
      LanguageSemantics.HeadDraft h = new LanguageSemantics.HeadDraft(false);
      h.type = type;
      h.core = core;
      h.kind = kind;
      h.policy = policy;
      h.itemType = itemType;
      h.keyType = keyType;
      h.valueType = valueType;
      h.labels = new HeadMap<>(labels);
      h.specification = specification;
      h.constraints = copyConstraints ? constraints.copy() : constraints;
      h.authoredSchema = authoredSchema;
      h.value = value;
      h.fields = new HeadMap<>(fields);
      h.items = items;
      h.contracts = contracts;
      h.emptyObject = emptyObject;
      h.origin = origin;
      h.origins = origins;
      return h;
    }
  }
}
