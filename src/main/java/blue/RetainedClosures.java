package blue;

import java.util.HashMap;
import java.util.Map;

/** Storage availability shared by views within one owner attempt; never validation evidence. */
final class RetainedClosures {
  private final Map<String, Boolean> retained = new HashMap<>();
  private long generation;

  void add(String id) {
    retained.putIfAbsent(id, Boolean.FALSE);
  }

  boolean contains(String id) {
    return retained.containsKey(id);
  }

  boolean isEmpty() {
    return retained.isEmpty();
  }

  boolean expanded(String id) {
    return Boolean.TRUE.equals(retained.get(id));
  }

  void complete(String id) {
    retained.put(id, Boolean.TRUE);
  }

  long generation() {
    return generation;
  }

  void clear() {
    retained.clear();
    generation++;
  }
}
