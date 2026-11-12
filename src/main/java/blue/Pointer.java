package blue;

import java.util.ArrayList;
import java.util.List;

/**
 * An immutable RFC 6901 JSON pointer. The empty pointer addresses the root.
 *
 * @param segments decoded path segments; the list is copied
 */
public record Pointer(List<String> segments) {
  /** Copies decoded segments; null segments are rejected. */
  public Pointer {
    segments = List.copyOf(segments);
  }

  /**
   * Parses a JSON pointer, validating tilde escapes.
   *
   * @param value the encoded pointer
   * @return the parsed pointer
   * @throws BlueException if the pointer is malformed
   */
  public static Pointer parse(String value) {
    if (value.isEmpty()) {
      return new Pointer(List.of());
    }
    if (!value.startsWith("/")) {
      throw new BlueException("INVALID_POINTER", value);
    }
    var parts = new ArrayList<String>();
    for (String part : value.substring(1).split("/", -1)) {
      int escape = part.indexOf('~');
      if (escape < 0) {
        parts.add(part);
        continue;
      }
      for (int i = escape; i < part.length(); i++) {
        if (part.charAt(i) != '~') {
          continue;
        }
        if (++i >= part.length() || (part.charAt(i) != '0' && part.charAt(i) != '1')) {
          throw new BlueException("INVALID_POINTER", value);
        }
      }
      parts.add(part.replace("~1", "/").replace("~0", "~"));
    }
    return new Pointer(parts);
  }

  /**
   * Appends one decoded segment.
   *
   * @param segment the literal segment, without escaping
   * @return the child pointer
   */
  public Pointer child(String segment) {
    var parts = new ArrayList<>(segments);
    parts.add(segment);
    return new Pointer(parts);
  }

  /**
   * Tests whether this pointer is an ancestor of or equal to another.
   *
   * @param other the candidate descendant
   * @return true if this pointer is a prefix of the other
   */
  public boolean contains(Pointer other) {
    return other.segments.size() >= segments.size()
        && other.segments.subList(0, segments.size()).equals(segments);
  }

  public String toString() {
    var out = new StringBuilder();
    for (String part : segments) {
      out.append('/').append(escape(part));
    }
    return out.toString();
  }

  /**
   * Escapes one literal segment for use in a JSON pointer.
   *
   * @param segment the decoded segment
   * @return its encoded representation
   */
  public static String escape(String segment) {
    return segment.replace("~", "~0").replace("/", "~1");
  }
}
