package blue;

import java.util.*;

/** Immutable exact graph. References are commitments, not admission evidence. */
sealed interface ExactNode permits ExactNode.Value, ExactNode.Ref, ExactNode.Local {
  final class Value implements ExactNode {
    private final SortedMap<String, Object> fields;
    volatile String identity;
    // UTF-8 helper size for an ordinary identity, never a validation or metering certificate.
    volatile int identityBytes;
    volatile long physicalWeight;
    // 0 unknown, 1 absent, 2 present. This is intrinsic to the immutable tree,
    // independent of providers, validation, identities, or execution limits.
    volatile byte localPresence;
    private final int hashCode;
    private volatile Set<String> ordinaryKeys;

    Value(Map<String, Object> fields) {
      var copy = new TreeMap<String, Object>(fields);
      for (var entry : copy.entrySet()) validate(entry.getKey(), entry.getValue());
      if (copy.get("items") instanceof List<?> items) {
        copy.put("items", List.copyOf(items));
      }
      this.fields = Collections.unmodifiableSortedMap(copy);
      hashCode = this.fields.hashCode();
    }

    // This constructor takes ownership of a fresh map containing only validated immutable values.
    private Value(TreeMap<String, Object> owned, int hash) {
      fields = Collections.unmodifiableSortedMap(owned);
      hashCode = hash;
    }

    private static void validate(String key, Object value) {
      if (value instanceof List<?> values) {
        if (!key.equals("items") || values.stream().anyMatch(x -> !(x instanceof ExactNode)))
          throw new IllegalArgumentException("ExactNode items must contain exact handles");
      } else if (value != null
          && !(value instanceof ExactNode
              || value instanceof String
              || value instanceof Boolean
              || value instanceof java.math.BigInteger
              || value instanceof Double
              || value instanceof NumberToken))
        throw new IllegalArgumentException("Unsupported exact field: " + key);
    }

    public SortedMap<String, Object> fields() {
      return fields;
    }

    @Override
    public boolean equals(Object other) {
      return this == other || other instanceof Value v && fields.equals(v.fields);
    }

    @Override
    public int hashCode() {
      return hashCode;
    }

    @Override
    public String toString() {
      return "Value[fields=" + fields + "]";
    }

    public Object get(String key) {
      return fields.get(key);
    }

    public boolean has(String key) {
      return fields.containsKey(key);
    }

    public Set<String> ordinary() {
      Set<String> keys = ordinaryKeys;
      if (keys == null) {
        var selected = new TreeSet<>(fields.keySet());
        selected.removeAll(LanguageCore.RESERVED);
        ordinaryKeys = keys = Collections.unmodifiableSet(selected);
      }
      return keys;
    }

    public String kind() {
      return has("value")
          ? "scalar"
          : has("items") ? "list" : !ordinary().isEmpty() || fields.isEmpty() ? "object" : "none";
    }

    public Value with(String key, Object value) {
      Objects.requireNonNull(key);
      validate(key, value);
      Object next = value instanceof List<?> items ? List.copyOf(items) : value;
      Object prior = fields.get(key);
      boolean existed = fields.containsKey(key);
      if (next == null && !existed || next != null && existed && next.equals(prior)) return this;
      var copy = new TreeMap<>(fields);
      int hash = hashCode;
      if (existed) hash -= key.hashCode() ^ Objects.hashCode(prior);
      if (next == null) copy.remove(key);
      else {
        copy.put(key, next);
        hash += key.hashCode() ^ next.hashCode();
      }
      return new Value(copy, hash);
    }
  }

  final class Ref implements ExactNode {
    private final String id;
    private final Value body;

    public Ref(String id) {
      this(id, null);
    }

    // Only Language admission/finalization can attach an established cyclic body.
    Ref(String id, Value body) {
      this.id = Objects.requireNonNull(id);
      this.body = body;
    }

    public String id() {
      return id;
    }

    public Value body() {
      return body;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Ref r && id.equals(r.id);
    }

    @Override
    public int hashCode() {
      return id.hashCode();
    }

    @Override
    public String toString() {
      return "Ref[" + id + "]";
    }
  }

  record Local(int index) implements ExactNode {}

  record NumberToken(String text) {
    public boolean decimal() {
      return text.contains(".") || text.contains("e") || text.contains("E");
    }
  }

  static Value value(Map<String, Object> fields) {
    return new Value(fields);
  }

  static Value list(List<ExactNode> items) {
    return value(Map.of("items", List.copyOf(items)));
  }

  static Value scalar(String core, Object value) {
    return value(
        Map.of("type", new Ref(LanguageCore.CORE.getOrDefault(core, core)), "value", value));
  }
}
