package blue;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import org.pcollections.PVector;
import org.pcollections.TreePMap;
import org.pcollections.TreePVector;

/**
 * An immutable Blue value. Edits share unchanged branches; they never mutate this instance.
 *
 * <p>Object fields are ordered by Java string order. Missing is an absence sentinel, distinct from
 * null: it removes object fields, cannot be a list element, and cannot be serialized. Scalar
 * accessors reject incompatible kinds with {@link BlueException}. Null reference arguments are not
 * supported. Instances and collection views are safe to share across threads.
 */
public final class Node {
  /** The value categories supported by the immutable source representation. */
  public enum Kind {
    /** A named collection of values. */
    OBJECT,
    /** An ordered collection of values. */
    LIST,
    /** Unicode text. */
    TEXT,
    /** An arbitrary-precision integer. */
    INTEGER,
    /** A finite IEEE-754 binary64 value. */
    DOUBLE,
    /** True or false. */
    BOOLEAN,
    /** Explicit null. */
    NULL,
    /** The absence sentinel. */
    MISSING
  }

  private static final Node NULL = new Node(Kind.NULL, "null");
  private static final Node MISSING = new Node(Kind.MISSING, "missing");
  private static final Node EMPTY_OBJECT = new Node(Kind.OBJECT, TreePMap.<String, Node>empty());
  private static final Node EMPTY_LIST = new Node(Kind.LIST, TreePVector.<Node>empty());
  private static final Node TRUE = new Node(Kind.BOOLEAN, true);
  private static final Node FALSE = new Node(Kind.BOOLEAN, false);
  private static final Node[] SMALL_INTEGERS =
      java.util.stream.IntStream.range(-16, 256)
          .mapToObj(value -> new Node(Kind.INTEGER, BigInteger.valueOf(value)))
          .toArray(Node[]::new);
  private final Kind kind;
  private final Object value;
  private final long logicalSize;

  private Node(Kind kind, Object value) {
    this.kind = kind;
    this.value = value;
    this.logicalSize =
        switch (kind) {
          case TEXT -> 1L + ((String) value).length();
          case INTEGER -> 1L + ((BigInteger) value).bitLength() / 8;
          case LIST -> 1L + items().stream().mapToLong(Node::logicalSize).sum();
          default -> 1;
        };
  }

  private Node(Kind kind, Object value, long logicalSize) {
    this.kind = kind;
    this.value = value;
    this.logicalSize = logicalSize;
  }

  /**
   * Returns the empty object.
   *
   * @return an immutable object
   */
  public static Node object() {
    return EMPTY_OBJECT;
  }

  /**
   * Copies fields into an immutable object, omitting missing values.
   *
   * @param fields the field names and values
   * @return the object
   */
  public static Node object(Map<String, Node> fields) {
    Node node = object();
    for (var entry : fields.entrySet()) {
      node = node.with(entry.getKey(), entry.getValue());
    }
    return node;
  }

  /**
   * Copies items into an immutable list.
   *
   * @param items values in list order
   * @return the list
   * @throws BlueException if an item is missing
   */
  public static Node list(List<Node> items) {
    if (items.isEmpty()) {
      return EMPTY_LIST;
    }
    for (Node item : items) {
      if (item.isMissing()) {
        throw new BlueException("MISSING_VALUE", "A list cannot contain missing");
      }
    }
    return new Node(Kind.LIST, TreePVector.from(items));
  }

  /**
   * Creates text after checking that surrogate pairs are well formed.
   *
   * @param value the text
   * @return the text value
   * @throws BlueException if the text has an unpaired surrogate
   */
  public static Node text(String value) {
    unicode(Objects.requireNonNull(value));
    return new Node(Kind.TEXT, value);
  }

  /**
   * Creates an exact integer.
   *
   * @param value the integer
   * @return the integer value
   */
  public static Node integer(long value) {
    return integer(BigInteger.valueOf(value));
  }

  /**
   * Creates an arbitrary-precision integer.
   *
   * @param value the integer
   * @return the integer value
   */
  public static Node integer(BigInteger value) {
    Objects.requireNonNull(value);
    if (value.bitLength() < 31) {
      int integer = value.intValue();
      if (integer >= -16 && integer < 256) {
        return SMALL_INTEGERS[integer + 16];
      }
    }
    return new Node(Kind.INTEGER, value);
  }

  /**
   * Creates a finite binary64 value. Language canonicalization normalizes negative zero.
   *
   * @param value the number
   * @return the immutable value
   * @throws IllegalArgumentException if the number is not finite
   */
  public static Node number(double value) {
    if (!Double.isFinite(value)) {
      throw new IllegalArgumentException("Nonfinite number");
    }
    return new Node(Kind.DOUBLE, value == 0 ? 0.0 : value);
  }

  /**
   * Reads a binary64 value.
   *
   * @return the number
   * @throws BlueException if this is not a double
   */
  public double number() {
    require(Kind.DOUBLE);
    return (double) value;
  }

  /**
   * Returns a Boolean value.
   *
   * @param value the Boolean
   * @return the Boolean value
   */
  public static Node bool(boolean value) {
    return value ? TRUE : FALSE;
  }

  /**
   * Returns the explicit null value.
   *
   * @return null as a Node, never a Java null reference
   */
  public static Node nil() {
    return NULL;
  }

  /**
   * Returns the absence sentinel.
   *
   * @return the missing value
   */
  public static Node missing() {
    return MISSING;
  }

  /**
   * Creates an unresolved reference to exact content.
   *
   * @param id the referenced identity
   * @return an object containing only blueId
   */
  public static Node reference(BlueId id) {
    return object(Map.of("blueId", text(id.value())));
  }

  /**
   * Returns the value category.
   *
   * @return the kind
   */
  public Kind kind() {
    return kind;
  }

  /**
   * Returns deterministic logical payload weight, not retained bytes.
   *
   * <p>Edits update this aggregate along changed paths without scanning unchanged siblings.
   *
   * @return the logical weight
   */
  public long logicalSize() {
    return logicalSize;
  }

  /**
   * Tests whether this is the absence sentinel.
   *
   * @return true for missing, false for explicit null and all other values
   */
  public boolean isMissing() {
    return kind == Kind.MISSING;
  }

  /**
   * Reads text without coercion.
   *
   * @return the text
   * @throws BlueException if this is not text
   */
  public String text() {
    require(Kind.TEXT);
    return (String) value;
  }

  /**
   * Reads an integer without loss of precision.
   *
   * @return the integer
   * @throws BlueException if this is not an integer
   */
  public BigInteger bigInteger() {
    require(Kind.INTEGER);
    return (BigInteger) value;
  }

  /**
   * Reads an integer as an exact long.
   *
   * @return the integer
   * @throws ArithmeticException if it does not fit in a long
   * @throws BlueException if this is not an integer
   */
  public long integer() {
    return bigInteger().longValueExact();
  }

  /**
   * Reads a Boolean without coercion.
   *
   * @return the Boolean
   * @throws BlueException if this is not a Boolean
   */
  public boolean bool() {
    require(Kind.BOOLEAN);
    return (Boolean) value;
  }

  /**
   * Returns an immutable view of object fields in string order.
   *
   * @return the fields
   * @throws BlueException if this is not an object
   */
  public Map<String, Node> fields() {
    return objectValue();
  }

  /**
   * Returns an immutable view of list elements.
   *
   * @return the items
   * @throws BlueException if this is not a list
   */
  public List<Node> items() {
    return listValue();
  }

  /**
   * Counts object fields, list elements, or Unicode code points in text.
   *
   * @return the size
   * @throws BlueException for other value kinds
   */
  public int size() {
    return switch (kind) {
      case OBJECT -> fields().size();
      case LIST -> items().size();
      case TEXT -> text().codePointCount(0, text().length());
      default -> throw new BlueException("TYPE_MISMATCH", "Expected a collection or text");
    };
  }

  /**
   * Selects an object field, returning missing for an absent field or non-object.
   *
   * @param key the field name
   * @return the field or missing
   */
  public Node get(String key) {
    Objects.requireNonNull(key);
    return kind == Kind.OBJECT ? fields().getOrDefault(key, MISSING) : MISSING;
  }

  /**
   * Selects a list item, returning missing for an invalid index or non-list.
   *
   * @param index the zero-based index
   * @return the item or missing
   */
  public Node get(int index) {
    return kind == Kind.LIST && index >= 0 && index < size() ? items().get(index) : MISSING;
  }

  /**
   * Selects a descendant using a JSON pointer.
   *
   * @param pointer the pointer; empty selects this value
   * @return the descendant, or missing when the path is absent
   * @throws BlueException if the pointer or a list index is malformed
   */
  public Node at(String pointer) {
    return at(Pointer.parse(pointer));
  }

  /**
   * Selects a descendant using a previously parsed pointer.
   *
   * @param pointer the pointer
   * @return the descendant, or missing when the path is absent
   * @throws BlueException if a list index is malformed
   */
  public Node at(Pointer pointer) {
    Node cursor = this;
    for (String segment : pointer.segments()) {
      cursor = cursor.kind == Kind.LIST ? cursor.get(index(segment)) : cursor.get(segment);
      if (cursor.isMissing()) {
        break;
      }
    }
    return cursor;
  }

  /**
   * Returns an object with a field added or replaced; missing removes the field.
   *
   * @param key the field name
   * @param child the replacement value
   * @return the updated object
   * @throws BlueException if this is not an object
   */
  public Node with(String key, Node child) {
    require(Kind.OBJECT);
    unicode(Objects.requireNonNull(key));
    Objects.requireNonNull(child);
    if (child.isMissing()) {
      return without(key);
    }
    if (get(key) == child) {
      return this;
    }
    Node old = get(key);
    long delta = child.logicalSize - (old.isMissing() ? 0 : old.logicalSize);
    if (old.isMissing()) {
      delta += key.length() + 1L;
    }
    return new Node(Kind.OBJECT, objectValue().plus(key, child), logicalSize + delta);
  }

  /**
   * Returns an object without a field; an absent field is a no-op.
   *
   * @param key the field name
   * @return the updated object
   * @throws BlueException if this is not an object
   */
  public Node without(String key) {
    require(Kind.OBJECT);
    return get(key).isMissing()
        ? this
        : new Node(
            Kind.OBJECT,
            objectValue().minus(key),
            logicalSize - get(key).logicalSize - key.length() - 1);
  }

  /**
   * Replaces an existing list item.
   *
   * @param index the zero-based index
   * @param child the replacement value
   * @return the updated list
   * @throws IndexOutOfBoundsException if the index is outside the list
   * @throws BlueException if this is not a list or the child is missing
   */
  public Node with(int index, Node child) {
    if (child.isMissing()) {
      throw new BlueException("MISSING_VALUE", "A list cannot contain missing");
    }
    return new Node(
        Kind.LIST,
        listValue().with(index, child),
        logicalSize - get(index).logicalSize + child.logicalSize);
  }

  /**
   * Appends a list item.
   *
   * @param child the new value
   * @return the updated list
   * @throws BlueException if this is not a list or the child is missing
   */
  public Node append(Node child) {
    if (child.isMissing()) {
      throw new BlueException("MISSING_VALUE", "A list cannot contain missing");
    }
    return new Node(Kind.LIST, listValue().plus(child), logicalSize + child.logicalSize);
  }

  /**
   * Applies a batch of authoring edits to a private cursor.
   *
   * <p>The source remains unchanged even if the callback throws. The editor is confined to this
   * callback: subsequent edits through a retained editor throw {@link IllegalStateException}. The
   * editor is not thread-safe.
   *
   * @param changes the synchronous editing callback
   * @return the value after the callback completes
   */
  public Node edit(Consumer<Editor> changes) {
    return edit(changes, null);
  }

  Node edit(Consumer<Editor> changes, UnaryOperator<Node> open) {
    Objects.requireNonNull(changes);
    var editor = new Editor(this, open);
    try {
      changes.accept(editor);
      return editor.node;
    } finally {
      // An escaped cursor must not retain the authored graph or its provider/context closure.
      editor.node = null;
      editor.open = null;
    }
  }

  Node patch(Pointer pointer, Node child, String operation) {
    return patch(pointer.segments(), 0, child, operation);
  }

  private Node patch(List<String> path, int offset, Node child, String operation) {
    if (offset == path.size()) {
      return child;
    }
    String key = path.get(offset);
    boolean leaf = offset + 1 == path.size();
    if (kind == Kind.OBJECT) {
      Node old = get(key);
      if (old.isMissing() && (!leaf || !operation.equals("add"))) {
        throw new BlueException("PATH_ABSENT", key);
      }
      if (leaf) {
        return operation.equals("remove") ? without(key) : with(key, child);
      }
      return with(key, old.patch(path, offset + 1, child, operation));
    }
    if (kind == Kind.LIST) {
      int index = key.equals("-") && leaf && operation.equals("add") ? size() : index(key);
      if (index < 0 || index > size() || (index == size() && !(leaf && operation.equals("add")))) {
        throw new BlueException("PATH_ABSENT", key);
      }
      if (!leaf) {
        return with(index, get(index).patch(path, offset + 1, child, operation));
      }
      return switch (operation) {
        case "add" ->
            new Node(Kind.LIST, listValue().plus(index, child), logicalSize + child.logicalSize);
        case "remove" ->
            new Node(Kind.LIST, listValue().minus(index), logicalSize - get(index).logicalSize);
        default -> with(index, child);
      };
    }
    throw new BlueException("PATH_ABSENT", key);
  }

  static int index(String value) {
    if (value.isEmpty() || (value.length() > 1 && value.charAt(0) == '0')) {
      throw new BlueException("INVALID_INDEX", value);
    }
    for (int i = 0; i < value.length(); i++) {
      if (value.charAt(i) < '0' || value.charAt(i) > '9') {
        throw new BlueException("INVALID_INDEX", value);
      }
    }
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw new BlueException("INVALID_INDEX", value);
    }
  }

  @SuppressWarnings("unchecked")
  private TreePMap<String, Node> objectValue() {
    require(Kind.OBJECT);
    return (TreePMap<String, Node>) value;
  }

  @SuppressWarnings("unchecked")
  private PVector<Node> listValue() {
    require(Kind.LIST);
    return (PVector<Node>) value;
  }

  private void require(Kind expected) {
    if (kind != expected) {
      throw new BlueException("TYPE_MISMATCH", "Expected " + expected + ", got " + kind);
    }
  }

  private static void unicode(String value) {
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (Character.isHighSurrogate(c)) {
        if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) {
          throw new BlueException("INVALID_UNICODE", "Unpaired high surrogate");
        }
      } else if (Character.isLowSurrogate(c)) {
        throw new BlueException("INVALID_UNICODE", "Unpaired low surrogate");
      }
    }
  }

  @Override
  public boolean equals(Object other) {
    return this == other
        || other instanceof Node node && kind == node.kind && value.equals(node.value);
  }

  @Override
  public int hashCode() {
    return 31 * kind.hashCode() + value.hashCode();
  }

  @Override
  public String toString() {
    return kind + ":" + value;
  }

  /**
   * An invocation-scoped authoring cursor. Never modifies the source node; not thread-safe. Every
   * mutation rejects use after its editing callback has returned or thrown.
   */
  public static final class Editor {
    private Node node;

    private UnaryOperator<Node> open;

    private Editor(Node node, UnaryOperator<Node> open) {
      this.node = node;
      this.open = open;
    }

    private void checkActive() {
      if (node == null) {
        throw new IllegalStateException("Node editor is no longer active");
      }
    }

    private Node ancestors(Node value, List<String> path, int offset) {
      if (offset == path.size()) {
        return value;
      }
      Node parent = open.apply(value);
      if (offset + 1 == path.size()) {
        return parent;
      }
      String key = path.get(offset);
      if (parent.kind == Kind.OBJECT) {
        Node child = parent.get(key);
        Node next = ancestors(child, path, offset + 1);
        return child == next ? parent : parent.with(key, next);
      }
      if (parent.kind == Kind.LIST) {
        int index = index(key);
        Node child = parent.get(index);
        Node next = ancestors(child, path, offset + 1);
        return child == next ? parent : parent.with(index, next);
      }
      return parent; // patch() reports invalid paths with the existing error categories.
    }

    /**
     * Adds or replaces a value at a pointer, without creating missing ancestors.
     *
     * @param path the JSON pointer; a final list segment of - appends
     * @param value the replacement value
     * @return this editor
     * @throws BlueException if the pointer is invalid or an ancestor is absent
     * @throws IllegalStateException if the editing callback has ended
     * @throws NullPointerException if the replacement is null; use {@link Node#nil()} for a null
     *     value
     */
    public Editor set(String path, Node value) {
      checkActive();
      Objects.requireNonNull(value);
      Pointer pointer = Pointer.parse(path);
      if (open != null) {
        node = ancestors(node, pointer.segments(), 0);
      }
      String operation = path.endsWith("/-") || node.at(pointer).isMissing() ? "add" : "replace";
      node = node.patch(pointer, value, operation);
      return this;
    }

    /**
     * Adds or replaces text at a pointer.
     *
     * @param path JSON pointer; a final list segment of - appends
     * @param value replacement value
     * @return this editor
     */
    public Editor set(String path, String value) {
      return set(path, Node.text(value));
    }

    /**
     * Adds or replaces an integer at a pointer.
     *
     * @param path JSON pointer; a final list segment of - appends
     * @param value replacement value
     * @return this editor
     */
    public Editor set(String path, long value) {
      return set(path, Node.integer(value));
    }

    /**
     * Adds or replaces a finite binary64 number at a pointer.
     *
     * @param path JSON pointer; a final list segment of - appends
     * @param value replacement value
     * @return this editor
     */
    public Editor set(String path, double value) {
      return set(path, Node.number(value));
    }

    /**
     * Adds or replaces a boolean at a pointer.
     *
     * @param path JSON pointer; a final list segment of - appends
     * @param value replacement value
     * @return this editor
     */
    public Editor set(String path, boolean value) {
      return set(path, Node.bool(value));
    }

    /**
     * Removes an existing value at a pointer.
     *
     * @param path the JSON pointer
     * @return this editor
     * @throws BlueException if the pointer is invalid or absent
     * @throws IllegalStateException if the editing callback has ended
     */
    public Editor remove(String path) {
      checkActive();
      Pointer pointer = Pointer.parse(path);
      if (open != null) {
        node = ancestors(node, pointer.segments(), 0);
      }
      node = node.patch(pointer, MISSING, "remove");
      return this;
    }
  }
}
