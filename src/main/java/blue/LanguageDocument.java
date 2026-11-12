package blue;

import java.util.List;
import java.util.function.Consumer;

/** A successfully admitted definition or completed value. Only Language can issue this result. */
public final class LanguageDocument extends LanguageContent {
  private final Language.Goal goal;
  private final BlueId originalBlueId;
  private final List<String> removedFields;

  LanguageDocument(
      Language owner,
      ExactNode exact,
      Language.Snapshot snapshot,
      Language.Goal goal,
      String originalBlueId,
      List<String> removedFields) {
    this(owner, exact, exact, snapshot, goal, originalBlueId, removedFields);
  }

  LanguageDocument(
      Language owner,
      ExactNode exact,
      ExactNode semantic,
      Language.Snapshot snapshot,
      Language.Goal goal,
      String originalBlueId,
      List<String> removedFields) {
    super(owner, exact, semantic, snapshot);
    this.goal = goal;
    this.originalBlueId = new BlueId(originalBlueId);
    this.removedFields = List.copyOf(removedFields);
  }

  /**
   * Reports whether admission certified a definition or a completed value.
   *
   * @return the validation intent certified by admission
   */
  public Language.Goal goal() {
    return goal;
  }

  /**
   * Captures this completed value's admission for protected local recovery. The host must retain
   * the complete immutable closure and independently protect the returned commitment. A definition
   * or a presentation with a different semantic identity cannot assert completed-value admission.
   *
   * @return local admission metadata, not a portable proof or execution permission
   */
  public LanguageCheckpoint checkpoint() {
    return LanguageCheckpoint.capture(this);
  }

  /**
   * Identifies the content before permitted Source duplicate omission.
   *
   * @return the prepared identity before Source duplicate normalization
   */
  public BlueId originalBlueId() {
    return originalBlueId;
  }

  /**
   * Reports redundant own fields omitted during Source admission.
   *
   * @return exactly redundant own fields removed during Source admission
   */
  public List<String> removedFields() {
    return removedFields;
  }

  /**
   * Creates a reusable subtype from authored YAML, using this document as its type. An explicit
   * type is accepted only when it preserves this parent. This document is unchanged.
   *
   * @param text authored refinement; the type may be omitted
   * @return the admitted specialization, retaining this document's dependencies
   */
  public LanguageDocument extendYaml(String text) {
    return owner.specialize(this, Language.Input.yaml(text), Language.Goal.DEFINITION);
  }

  /**
   * Creates a reusable subtype from authored JSON, using this document as its type. An explicit
   * type is accepted only when it preserves this parent. This document is unchanged.
   *
   * @param text authored refinement; the type may be omitted
   * @return the admitted specialization, retaining this document's dependencies
   */
  public LanguageDocument extendJson(String text) {
    return owner.specialize(this, Language.Input.json(text), Language.Goal.DEFINITION);
  }

  /**
   * Creates a completed value from authored YAML, using this document as its type. An explicit type
   * is accepted only when it preserves this parent. This document is unchanged.
   *
   * @param text authored refinement; the type may be omitted
   * @return the admitted specialization, retaining this document's dependencies
   */
  public LanguageDocument specializeYaml(String text) {
    return owner.specialize(this, Language.Input.yaml(text), Language.Goal.VALUE);
  }

  /**
   * Creates a completed value from authored JSON, using this document as its type. An explicit type
   * is accepted only when it preserves this parent. This document is unchanged.
   *
   * @param text authored refinement; the type may be omitted
   * @return the admitted specialization, retaining this document's dependencies
   */
  public LanguageDocument specializeJson(String text) {
    return owner.specialize(this, Language.Input.json(text), Language.Goal.VALUE);
  }

  /**
   * Edits own exact content and validates the complete result under the original admission goal.
   * References on edited ancestor paths are opened as needed; other branches stay collapsed.
   * Inherited restrictions still apply. Neither this document nor provider content is mutated.
   *
   * <p>Paths address exact content: scalar payloads use /value and list elements /items/index.
   * Missing ancestors are not created. Cyclic members require a new complete symbolic owning set.
   * This is an authoring operation, not a managed-document transition or an authorization check.
   *
   * @param changes synchronous editing callback
   * @return a newly admitted immutable document; no result is published if validation fails
   */
  public LanguageDocument edit(Consumer<Node.Editor> changes) {
    return owner.edit(this, changes);
  }

  /**
   * Expands selected complete nodes without flattening inheritance.
   *
   * @param paths complete-node JSON pointers; an empty string selects the root
   * @return a presentation with the same identity and admission
   */
  public LanguageDocument expand(String... paths) {
    return owner.expand(this, paths);
  }

  /**
   * Replaces selected complete nodes with references while retaining verified evidence.
   *
   * @param paths complete-node JSON pointers
   * @return a presentation with the same identity and admission
   */
  public LanguageDocument collapse(String... paths) {
    return owner.collapse(this, paths);
  }
}
