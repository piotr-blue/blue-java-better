package blue;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Immutable host inspection of exact content using canonical Contracts selectors. List elements use
 * {@code /0}; labels, types, schema and contracts are navigable. No workflow runs and no gas is
 * charged. Each call has an independent Language work allowance; provider validation still applies.
 */
public final class ContractInspection {
  private final LanguageContent content;
  private final String path;

  ContractInspection(LanguageContent content, String path) {
    this.content = content;
    this.path = path;
  }

  /**
   * Selects a relative canonical pointer, retaining the original content and provider snapshot.
   *
   * @param pointer relative JSON pointer, with list indices such as {@code /orders/0}
   * @return the selected inspector, or empty if the path is absent
   */
  public Optional<ContractInspection> at(String pointer) {
    Objects.requireNonNull(pointer);
    String selected = path + pointer;
    return read(
        bridge -> {
          ContractModel.segments(pointer);
          return bridge.select(content.exact, selected) == null
              ? Optional.empty()
              : Optional.of(new ContractInspection(content, selected));
        });
  }

  /**
   * Lists canonical child selectors, including metadata, in deterministic order. Bare scalar values
   * do not list a redundant {@code value} selector back to themselves.
   *
   * @return immutable unescaped child names and list indices
   */
  public List<String> children() {
    return read(bridge -> bridge.childSelectors(bridge.select(content.exact, path)));
  }

  /**
   * Reads the selected literal with default output bounds, preserving existing nested references.
   *
   * @return detached immutable exact presentation, with lossless scalar/list shorthand
   */
  public Object literal() {
    return literal(Set.of(), ProjectionLimits.defaults());
  }

  /**
   * Reads a bounded literal, optionally replacing selected direct child fields by their BlueIds.
   * This does not recursively expand references or change the original materialization.
   *
   * @param referenceFields direct exact child fields to represent by reference
   * @param limits output bounds checked while constructing the result
   * @return detached immutable exact presentation
   * @throws BlueException with code {@code PROJECTION_LIMIT_EXCEEDED} if a bound is exceeded
   */
  public Object literal(Set<String> referenceFields, ProjectionLimits limits) {
    Set<String> fields = Set.copyOf(referenceFields);
    Objects.requireNonNull(limits);
    return read(
        bridge ->
            BexValues.freeze(bridge.literal(bridge.select(content.exact, path), fields, limits)));
  }

  /**
   * Reads an exact literal with referenced primitive scalar leaves inlined. Container, type, cyclic
   * and explicitly excluded field references remain references. This gives stored scalar content
   * the same shorthand as inline scalars without recursively expanding linked documents. Referenced
   * leaves are verified before use; unavailable evidence fails the read.
   *
   * @param referenceFields direct exact child fields to retain by identity without opening them
   * @param limits output bounds checked before returning each value
   * @return detached immutable exact presentation with lossless scalar shorthand
   * @throws BlueException if evidence is unavailable, invalid or a projection bound is exceeded
   */
  public Object readableLiteral(Set<String> referenceFields, ProjectionLimits limits) {
    Set<String> fields = Set.copyOf(referenceFields);
    Objects.requireNonNull(limits);
    return read(
        bridge ->
            BexValues.freeze(
                bridge.literal(bridge.select(content.exact, path), fields, limits, true)));
  }

  /**
   * Projects the selected value exactly as a session read, without restoring execution state.
   * Language work limits apply; use {@link #value(ProjectionLimits)} to bound the returned
   * structure.
   *
   * @return detached immutable BEX data, preserving null and the undefined sentinel
   */
  public Object value() {
    return read(bridge -> BexValues.freeze(bridge.project(bridge.select(content.exact, path))));
  }

  /**
   * Projects only the selected value with explicit output bounds. Provider verification and
   * Language work limits still apply; this does not admit or execute unrelated content.
   *
   * @param limits output bounds, independent of execution gas
   * @return detached immutable BEX data
   * @throws BlueException if verification fails or a projection bound is exceeded
   */
  public Object value(ProjectionLimits limits) {
    Objects.requireNonNull(limits);
    return read(
        bridge -> BexValues.freeze(bridge.project(bridge.select(content.exact, path), limits)));
  }

  private <T> T read(Function<ContractBridge, T> action) {
    return Contracts.boundary(
        () -> {
          ContractBridge bridge = new ContractBridge(content.owner);
          bridge.context.install(content);
          return action.apply(bridge);
        });
  }
}
