package blue;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

/** A selected contextual view bound to exact content. It is not an exact-content replacement. */
public final class LanguageView {
  private final Language language;
  private final LanguageContent content;
  private final String path;

  LanguageView(Language language, LanguageContent content, String path) {
    this.language = language;
    this.content = content;
    this.path = path;
  }

  /**
   * Selects a relative JSON pointer using Language's logical paths (including /items/index).
   *
   * @param pointer the relative pointer, or empty for this view
   * @return the lazy selected view
   */
  public LanguageView at(String pointer) {
    Pointer.parse(pointer);
    return new LanguageView(language, content, path + pointer);
  }

  /**
   * Selects a literal field name without interpreting slash or tilde characters as pointer syntax.
   * Selection is lazy and preserves the same contextual rules as {@link #at(String)}.
   *
   * @param name the literal field name; an empty name selects an empty key, not this view
   * @return the lazy child view; observing it still distinguishes absence from unavailable evidence
   * @throws NullPointerException if the name is null
   */
  public LanguageView field(String name) {
    return at("/" + Pointer.escape(name));
  }

  /**
   * Selects a list element using Blue's logical {@code /items/index} path. Selection performs no
   * provider lookup or length check. An out-of-range element is absent once evidence establishes
   * the list's length; unavailable evidence is never treated as an empty list.
   *
   * @param index the nonnegative, zero-based element index
   * @return the lazy element view
   * @throws IllegalArgumentException if the index is negative
   */
  public LanguageView item(int index) {
    if (index < 0) {
      throw new IllegalArgumentException("Negative list index");
    }
    return at("/items/" + index);
  }

  /**
   * Determines concrete-value presence without treating unavailable evidence as absence. Non-root
   * unfilled declarations and metadata-only nodes are not concrete values.
   *
   * @return true for a present value; missing evidence throws instead of returning false
   */
  public boolean exists() {
    return !observation("present").isMissing();
  }

  /**
   * Tests whether this node contributes a concrete value, including at the root. A root definition
   * can exist without being concrete; inherited concrete contributions count.
   *
   * @return true for a concrete value; unavailable evidence throws instead of returning false
   */
  public boolean isConcrete() {
    return language.concrete(content, path);
  }

  /**
   * Reads the selected effective payload kind.
   *
   * @return effective kind: scalar, list, object, or none
   */
  public String kind() {
    return required("kind").text();
  }

  /**
   * Reads a Text payload.
   *
   * @return the resolved text payload
   */
  public String text() {
    return required("value").get("value").text();
  }

  /**
   * Reads an Integer payload without narrowing its range.
   *
   * @return the resolved arbitrary-precision integer
   */
  public BigInteger bigInteger() {
    return new BigInteger(required("value").get("decimal").text());
  }

  /**
   * Reads an Integer payload that fits in a signed 64-bit value.
   *
   * @return the resolved integer, failing if it does not fit in a long
   */
  public long integer() {
    return bigInteger().longValueExact();
  }

  /**
   * Reads a finite binary64 Double payload.
   *
   * @return the resolved binary64 number
   */
  public double number() {
    return Double.longBitsToDouble(
        Long.parseUnsignedLong(required("value").get("bits").text(), 16));
  }

  /**
   * Reads a Boolean payload.
   *
   * @return the resolved Boolean payload
   */
  public boolean bool() {
    return required("value").get("value").bool();
  }

  /**
   * Enumerates concrete ordinary fields of the selected value.
   *
   * @return concrete ordinary field names, in specified order
   */
  public List<String> keys() {
    return required("keys").items().stream().map(Node::text).toList();
  }

  /**
   * Enumerates inherited and own ordinary field declarations, including unfilled fields. Unlike
   * {@link #keys()}, this is authoring inspection, not a concrete-value observation.
   *
   * @return immutable field names in Language order, or an empty list for an absent path
   */
  public List<String> declaredFields() {
    return language.declaredFields(content, path);
  }

  /**
   * Reads the established length of the selected list.
   *
   * @return the established list length
   */
  public int size() {
    return Math.toIntExact(required("length").integer());
  }

  /**
   * Reads the effective type identity of the selected value.
   *
   * @return the effective type, if present
   */
  public Optional<BlueId> type() {
    Node value = observation("type");
    return value.isMissing() ? Optional.empty() : Optional.of(new BlueId(value.text()));
  }

  /**
   * Reads the selected node's effective name metadata.
   *
   * @return the name, if declared; metadata-only nodes need not have a concrete payload
   */
  public Optional<String> name() {
    Node value = observation("name");
    return value.isMissing() ? Optional.empty() : Optional.of(value.text());
  }

  /**
   * Reads the selected node's effective description metadata.
   *
   * @return the description, if declared
   */
  public Optional<String> description() {
    Node value = observation("description");
    return value.isMissing() ? Optional.empty() : Optional.of(value.text());
  }

  /**
   * Describes the effective conjunction of schema predicates.
   *
   * @return the effective schema constraints
   */
  public Node constraints() {
    return required("constraints");
  }

  /**
   * Reads specification metadata declared on this node itself.
   *
   * @return the own specification metadata, if present; metadata is not inherited
   */
  public Optional<BlueId> specification() {
    Node value = observation("specification");
    return value.isMissing()
        ? Optional.empty()
        : Optional.of(new BlueId(value.get("blueId").text()));
  }

  /**
   * Materializes the selected node's effective own and inherited declarations as independent exact
   * content. This does not rewrite the owning document or admit the selected content. Only complete
   * nodes are selectable; literal metadata and the list-items container are not nodes.
   *
   * @return selected content, or empty when the node is absent
   */
  public Optional<LanguageContent> content() {
    return language.select(content, path);
  }

  /**
   * Reads one specification observation without certifying unrelated siblings.
   *
   * @param question observation name, such as kind, value, name, or exactId
   * @return the value or missing when absence is established
   */
  public Node observation(String question) {
    return language.observe(content, path, question);
  }

  private Node required(String question) {
    Node value = observation(question);
    if (value.isMissing()) {
      throw new BlueException("PATH_ABSENT", path);
    }
    return value;
  }
}
