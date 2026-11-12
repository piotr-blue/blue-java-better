package blue;

import java.util.Objects;

/**
 * A final Language identity: a plain digest or an owning-set digest with a member index.
 *
 * @param value a base58-encoded 32-byte identity, optionally followed by #index
 */
public record BlueId(String value) {
  /** Validates the base58 alphabet and decoded length; does not verify any content. */
  public BlueId {
    Objects.requireNonNull(value);
    String original = value;
    int separator = value.indexOf('#');
    if (separator >= 0) {
      if (!BlueIdSyntax.member(value, separator + 1)) {
        throw new BlueException("INVALID_BLUE_ID", original);
      }
      value = value.substring(0, separator);
    }
    if (!BlueIdSyntax.digest(value)) {
      throw new BlueException("INVALID_BLUE_ID", value);
    }
    value = original;
  }

  @Override
  public String toString() {
    return value;
  }
}
