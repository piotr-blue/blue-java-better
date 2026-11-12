package blue;

import java.util.Objects;

/** Counts output before allocating its next container or inserting its next entry. */
final class ProjectionBudget {
  private long values;
  private long characters;
  private final int depth;

  ProjectionBudget(ProjectionLimits limits) {
    Objects.requireNonNull(limits);
    values = limits.values();
    characters = limits.characters();
    depth = limits.depth();
  }

  void value(int level) {
    if (level > depth) throw exceeded("nesting depth");
    if (values == 0) throw exceeded("value count");
    values--;
  }

  void entry(String key) {
    if (values == 0) throw exceeded("value count");
    values--;
    text(key);
  }

  void text(Object value) {
    if (value instanceof String text) {
      if (text.length() > characters) throw exceeded("text length");
      characters -= text.length();
    }
  }

  Object scalar(Object value, int level) {
    value(level);
    text(value);
    return value;
  }

  private static BlueException exceeded(String bound) {
    return new BlueException(
        "PROJECTION_LIMIT_EXCEEDED", "Projected read exceeds the " + bound + " limit");
  }
}
