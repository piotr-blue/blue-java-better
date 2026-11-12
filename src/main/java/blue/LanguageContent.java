package blue;

import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Exact content with its immutable supporting evidence. Prepared content is not necessarily valid;
 * strict admission returns a LanguageDocument. Values and records are safe to share across threads.
 */
public sealed class LanguageContent permits LanguageDocument {
  final Language owner;
  final ExactNode exact;
  // Reference-only type edges for semantics, independent of the chosen presentation.
  final ExactNode semantic;
  final Language.Snapshot snapshot;

  LanguageContent(Language owner, ExactNode exact, Language.Snapshot snapshot) {
    this(owner, exact, exact, snapshot);
  }

  LanguageContent(Language owner, ExactNode exact, ExactNode semantic, Language.Snapshot snapshot) {
    this.owner = owner;
    this.exact = exact;
    this.semantic = semantic;
    this.snapshot = snapshot;
  }

  /**
   * Returns the exact content identity; it does not assert semantic validity.
   *
   * @return the exact identity, independently of validation or expansion
   */
  public final BlueId blueId() {
    return new BlueId(LanguageCore.identity(exact));
  }

  /**
   * Identifies the fixed Language interpretation used for this content.
   *
   * @return the selected immutable Language edition
   */
  public final String edition() {
    return Language.EDITION;
  }

  /**
   * Serializes this materialization as exact JSON.
   *
   * @return exact JSON; it must be reloaded in Exact mode
   */
  public final String toJson() {
    return LanguageCore.exactText(exact);
  }

  /**
   * Serializes this materialization as exact YAML.
   *
   * @return exact YAML; it must be reloaded in Exact mode
   */
  public final String toYaml() {
    return Language.yaml(LanguageCore.export(exact));
  }

  /**
   * Exposes the exact materialization as immutable generic nodes. Lists always retain their /items
   * slot, matching Language pointers even when serialization uses list shorthand.
   *
   * @return exact immutable materialization, retaining references and own type edges
   */
  public final Node node() {
    return Language.node(exact);
  }

  /**
   * Creates a lazy view of inherited and own contributions.
   *
   * @return a lazy contextual view; reading it never rewrites this exact origin
   */
  public final LanguageView resolve() {
    return new LanguageView(owner, this, "");
  }

  /**
   * Selects an inherited or own value without changing the exact document.
   *
   * @param pointer logical JSON pointer; list elements use /items/index
   * @return a lazy contextual view; unavailable evidence throws on observation
   */
  public final LanguageView at(String pointer) {
    return resolve().at(pointer);
  }

  /**
   * Encodes canonical compact JSON, including verified list-prefix encoding where applicable.
   *
   * @return compact exact wire; reload with {@link Language.Input#compactJson(String)}
   */
  public final String canonicalJson() {
    return owner.compactJson(this);
  }

  /**
   * Exports the dependency closure as portable exact evidence.
   *
   * @return self-contained exact records, including cyclic proofs when required
   */
  public final Map<BlueId, LanguageRecord> records() {
    return owner.records(this);
  }

  /**
   * Exports only records whose closures are not already retained by the receiving store. The
   * predicate is consulted lazily; a large store need not copy or enumerate its existing keys. This
   * controls transport only and grants no admission or execution authority.
   *
   * @param available returns true only when the receiver already retains the identity's complete
   *     supporting closure; must remain stable during this call
   * @return immutable incremental exact records, including required cyclic proofs
   */
  public final Map<BlueId, LanguageRecord> records(Predicate<BlueId> available) {
    return owner.records(this, Objects.requireNonNull(available));
  }
}
