package blue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Copies transport containers at the boundary; exact nodes are already immutable. */
final class LanguageValues {
  private LanguageValues() {}

  static Object copy(Object value) {
    return transform(value, false);
  }

  static Object freeze(Object value) {
    return transform(value, true);
  }

  private static Object transform(Object value, boolean immutable) {
    if (value instanceof Map<?, ?> fields) {
      var result = new LinkedHashMap<String, Object>();
      fields.forEach((key, item) -> result.put((String) key, transform(item, immutable)));
      return immutable ? Collections.unmodifiableMap(result) : result;
    }
    if (value instanceof List<?> items) {
      var result = new ArrayList<Object>();
      items.forEach(item -> result.add(transform(item, immutable)));
      return immutable ? Collections.unmodifiableList(result) : result;
    }
    return value;
  }
}
