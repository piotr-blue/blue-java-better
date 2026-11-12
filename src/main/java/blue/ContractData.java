package blue;

import java.math.BigInteger;
import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.RandomAccess;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

/** Stack-safe copies at Java input and wire-data boundaries. Exact graphs are already immutable. */
final class ContractData {
  static Object wireCopy(Object v) {
    if (v == null
        || v instanceof Boolean
        || v.getClass() == BigInteger.class
        || v.getClass() == java.math.BigDecimal.class) {
      return v;
    }
    if (v instanceof String s) {
      return LanguageCodec.unicode(s);
    }
    if (v instanceof Byte || v instanceof Short || v instanceof Integer || v instanceof Long) {
      return BigInteger.valueOf(((Number) v).longValue());
    }
    if (v instanceof Map<?, ?> map) {
      var result = new LinkedHashMap<String, Object>();
      var keys = new TreeSet<String>();
      for (Object key : map.keySet()) {
        keys.add(key.toString());
      }
      for (String key : keys) {
        result.put(LanguageCodec.unicode(key), wireCopy(map.get(key)));
      }
      return result;
    }
    if (v instanceof Collection<?> values) {
      var result = new ArrayList<Object>(values.size());
      for (Object value : values) {
        result.add(wireCopy(value));
      }
      return result;
    }
    // Preserve wire numeric normalization, including binary64 and NumberToken.
    return LanguageCodec.json(LanguageCodec.wire(v));
  }

  private record Pointer(Pointer parent, String part) {
    String text() {
      var parts = new ArrayDeque<String>();
      for (Pointer p = this; p != null; p = p.parent) {
        parts.addFirst(p.part);
      }
      return parts.isEmpty() ? "" : "/" + String.join("/", parts);
    }
  }

  private record Work(
      Object value, Pointer path, int depth, Consumer<Object> accept, boolean exit) {}

  private static final class FrozenMap extends AbstractMap<String, Object> {
    private final Map<String, Object> values;
    private volatile Boolean wireImmutable;

    FrozenMap(Map<String, Object> values) {
      this.values = Collections.unmodifiableMap(values);
    }

    @Override
    public Set<Entry<String, Object>> entrySet() {
      return values.entrySet();
    }

    @Override
    public Object get(Object key) {
      return values.get(key);
    }

    @Override
    public boolean containsKey(Object key) {
      return values.containsKey(key);
    }

    @Override
    public int size() {
      return values.size();
    }
  }

  private static final class FrozenList extends AbstractList<Object> implements RandomAccess {
    private final List<Object> values;
    private volatile Boolean wireImmutable;

    FrozenList(List<Object> values) {
      this.values = Collections.unmodifiableList(values);
    }

    @Override
    public Object get(int index) {
      return values.get(index);
    }

    @Override
    public int size() {
      return values.size();
    }
  }

  static Object evidence(Object value) {
    return value instanceof FrozenMap ? value : freeze(value);
  }

  static Object freeze(Object value) {
    return copy(value, Map.of(), false, true);
  }

  static final class RecordLayout {
    final String[] keys;
    final int[] canonicalOrder;
    final String[] prefixes;
    final byte[][] utf8Prefixes;
    final boolean contractOrder;
    final long encodingWeight;

    RecordLayout(String[] keys) {
      this.keys = keys;
      long characters = 0;
      for (String key : keys) {
        characters += key.length();
      }
      if (characters > 1024 || keys.length > 128) {
        canonicalOrder = null;
        prefixes = null;
        utf8Prefixes = null;
        contractOrder = false;
        encodingWeight = 64;
        return;
      }
      Integer[] order = new Integer[keys.length];
      for (int i = 0; i < order.length; i++) {
        order[i] = i;
      }
      Arrays.sort(order, Comparator.comparing(i -> keys[i]));
      canonicalOrder = new int[keys.length];
      prefixes = new String[keys.length];
      utf8Prefixes = new byte[keys.length][];
      long weight = 128L + 24L * keys.length;
      boolean ordered = true;
      for (int i = 0; i < order.length; i++) {
        if (i > 0 && ContractCanonical.compare(keys[order[i - 1]], keys[order[i]]) > 0) {
          ordered = false;
        }
        canonicalOrder[i] = order[i];
        prefixes[i] = (i == 0 ? "" : ",") + LanguageCodec.quote(keys[order[i]]) + ":";
        utf8Prefixes[i] = prefixes[i].getBytes(java.nio.charset.StandardCharsets.UTF_8);
        weight += 96L + 2L * prefixes[i].length() + utf8Prefixes[i].length;
      }
      encodingWeight = weight;
      contractOrder = ordered;
    }
  }

  static final class ScalarRecord extends AbstractMap<String, Object> {
    final RecordLayout layout;
    private final String[] keys;
    final Object[] values;

    ScalarRecord(RecordLayout layout, Object[] values) {
      this.layout = layout;
      this.keys = layout.keys;
      this.values = values;
    }

    @Override
    public Object get(Object key) {
      for (int i = 0; i < keys.length; i++) {
        if (keys[i].equals(key)) {
          return values[i];
        }
      }
      return null;
    }

    @Override
    public boolean containsKey(Object key) {
      for (String name : keys) {
        if (name.equals(key)) {
          return true;
        }
      }
      return false;
    }

    @Override
    public int size() {
      return keys.length;
    }

    @Override
    public Set<Entry<String, Object>> entrySet() {
      return new AbstractSet<>() {
        @Override
        public int size() {
          return keys.length;
        }

        @Override
        public Iterator<Entry<String, Object>> iterator() {
          return new Iterator<>() {
            int next;

            @Override
            public boolean hasNext() {
              return next < keys.length;
            }

            @Override
            public Entry<String, Object> next() {
              if (!hasNext()) {
                throw new NoSuchElementException();
              }
              int i = next++;
              return new SimpleImmutableEntry<>(keys[i], values[i]);
            }
          };
        }
      };
    }
  }

  static Map<String, Object> scalarRecord(RecordLayout layout, Object[] values) {
    if (layout.keys.length != values.length) {
      throw new IllegalArgumentException("Record arity mismatch");
    }
    Object[] owned = values.clone();
    for (Object value : owned) {
      validateScalar(value);
    }
    return new ScalarRecord(layout, owned);
  }

  private static void validateScalar(Object value) {
    if (value instanceof Map<?, ?> || value instanceof Collection<?> || !immutableValue(value)) {
      throw new IllegalArgumentException("Record values must be immutable scalars");
    }
    if (value instanceof String text) {
      LanguageCodec.unicode(text);
    }
  }

  static Map<String, Object> replaceScalar(
      RecordLayout layout, Map<String, Object> record, String key, Object value) {
    if (!(record instanceof ScalarRecord row) || row.layout != layout) {
      throw new IllegalArgumentException("Record belongs to another shape");
    }
    validateScalar(value);
    for (int i = 0; i < layout.keys.length; i++) {
      if (!layout.keys[i].equals(key)) {
        continue;
      }
      Object[] values = row.values.clone();
      values[i] = value;
      return new ScalarRecord(layout, values);
    }
    throw new IllegalArgumentException("Unknown record field: " + key);
  }

  static boolean isImmutableContainer(Object value) {
    if (value instanceof ScalarRecord || value instanceof ContractCanonical.EncodedRecord) {
      return true;
    }
    if (value instanceof FrozenMap map) {
      return Boolean.TRUE.equals(map.wireImmutable);
    }
    if (value instanceof FrozenList list) {
      return Boolean.TRUE.equals(list.wireImmutable);
    }
    return false;
  }

  private static boolean immutableValue(Object value) {
    if (value == null || value instanceof String || value instanceof Boolean) {
      return true;
    }
    Class<?> type = value.getClass();
    if (type == BigInteger.class
        || type == java.math.BigDecimal.class
        || type == Long.class
        || type == Integer.class
        || type == Short.class
        || type == Byte.class
        || type == Double.class
        || type == Float.class
        || type == ExactNode.NumberToken.class) {
      return true;
    }
    return isImmutableContainer(value);
  }

  static Object detached(Object value) {
    return copy(value, Map.of(), false, false);
  }

  static Object host(Object value, Map<String, Long> limits) {
    return copy(value, limits, true, false);
  }

  private static Object copy(
      Object root, Map<String, Long> limits, boolean host, boolean immutable) {
    var active = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
    // A receipt may repeat the same trace entries in several projections. Share only
    // completed, privately owned immutable copies within this one freeze operation.
    var frozenCopies = immutable ? new IdentityHashMap<Object, Object>() : null;
    var layouts = immutable ? new HashMap<List<String>, RecordLayout>() : null;
    var todo = new ArrayDeque<Work>();
    Object[] result = new Object[1];
    todo.push(new Work(root, null, 1, v -> result[0] = v, false));
    long values = 0;
    while (!todo.isEmpty()) {
      Work work = todo.pop();
      Object value = work.value instanceof Node n ? NodeCodec.raw(n) : work.value;
      if (work.exit) {
        active.remove(value);
        work.accept.accept(null);
        continue;
      }
      if (immutable && frozenCopies.containsKey(value)) {
        work.accept.accept(frozenCopies.get(value));
        continue;
      }
      // Only our privately owned, already traversed containers are safe to share.
      // Host input and detached copies must still walk every value and enforce their limits.
      if (immutable && isImmutableContainer(value)) {
        work.accept.accept(value);
        continue;
      }
      try {
        bound(limits, "depth", work.depth);
        bound(limits, "values", ++values);
        if (value instanceof Map<?, ?> map) {
          // Most runtime records contain only scalar values or already frozen subtrees.
          // They need one owned map, not a work item and path object for every field.
          if (immutable && map.values().stream().allMatch(ContractData::immutableValue)) {
            var out = new LinkedHashMap<String, Object>();
            for (var entry : map.entrySet()) {
              if (!(entry.getKey() instanceof String key)) {
                throw new LanguageFailure("InvalidNodeShape", "Object keys must be strings");
              }
              LanguageCodec.unicode(key);
              if (entry.getValue() instanceof String s) {
                try {
                  LanguageCodec.unicode(s);
                } catch (LanguageFailure failure) {
                  failure.data.putIfAbsent("path", child(work.path, key).text());
                  throw failure;
                }
              }
              out.put(key, entry.getValue());
            }
            Object frozen;
            if (out.size() <= 16
                && out.values().stream()
                    .noneMatch(v -> v instanceof Map<?, ?> || v instanceof Collection<?>)) {
              var names = List.copyOf(out.keySet());
              var layout = layouts.get(names);
              if (layout == null) {
                layout = new RecordLayout(names.toArray(String[]::new));
                if (layouts.size() < 128) {
                  layouts.put(names, layout);
                }
              }
              frozen = new ScalarRecord(layout, out.values().toArray());
            } else {
              var container = new FrozenMap(out);
              container.wireImmutable = true;
              frozen = container;
            }
            frozenCopies.put(map, frozen);
            work.accept.accept(frozen);
            continue;
          }
          if (!active.add(value)) {
            throw new LanguageFailure(
                "InvalidNodeShape", "Cyclic Java container; use explicit Blue cyclic-set syntax");
          }
          var out = new LinkedHashMap<String, Object>();
          var frozen = immutable ? new FrozenMap(out) : null;
          work.accept.accept(immutable ? frozen : out);
          // Children finish first, so this ownership check is stack-safe as well.
          todo.push(
              new Work(
                  value,
                  work.path,
                  work.depth,
                  ignored -> {
                    if (frozen != null) {
                      frozen.wireImmutable =
                          out.values().stream().allMatch(ContractData::immutableValue);
                      if (Boolean.TRUE.equals(frozen.wireImmutable)) {
                        frozenCopies.put(map, frozen);
                      }
                    }
                  },
                  true));
          var entries = new ArrayList<>(map.entrySet());
          for (int i = entries.size() - 1; i >= 0; i--) {
            var entry = entries.get(i);
            if (!(entry.getKey() instanceof String key)) {
              throw new LanguageFailure("InvalidNodeShape", "Object keys must be strings");
            }
            LanguageCodec.unicode(key);
            if (out.containsKey(key)) {
              throw new LanguageFailure("DuplicateKey", key);
            }
            out.put(key, null);
            todo.push(
                new Work(
                    entry.getValue(),
                    child(work.path, key),
                    work.depth + 1,
                    v -> out.put(key, v),
                    false));
          }
        } else if (value instanceof List<?> list) {
          if (!active.add(value)) {
            throw new LanguageFailure(
                "InvalidNodeShape", "Cyclic Java container; use explicit Blue cyclic-set syntax");
          }
          var out = new ArrayList<Object>(Collections.nCopies(list.size(), null));
          var frozen = immutable ? new FrozenList(out) : null;
          work.accept.accept(immutable ? frozen : out);
          todo.push(
              new Work(
                  value,
                  work.path,
                  work.depth,
                  ignored -> {
                    if (frozen != null) {
                      frozen.wireImmutable = out.stream().allMatch(ContractData::immutableValue);
                      if (Boolean.TRUE.equals(frozen.wireImmutable)) {
                        frozenCopies.put(list, frozen);
                      }
                    }
                  },
                  true));
          for (int i = list.size() - 1; i >= 0; i--) {
            int index = i;
            todo.push(
                new Work(
                    list.get(i),
                    child(work.path, Integer.toString(i)),
                    work.depth + 1,
                    v -> out.set(index, v),
                    false));
          }
        } else {
          if (value instanceof String s) {
            LanguageCodec.unicode(s);
          }
          if (host) {
            if (value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long) {
              value = BigInteger.valueOf(((Number) value).longValue());
            } else if (value instanceof Float f) {
              value = (double) f;
            }
            if (value instanceof BigInteger number) {
              bound(limits, "numeric_chars", number.toString().length());
            } else if (value instanceof Double number) {
              if (!Double.isFinite(number)) {
                throw new LanguageFailure("InvalidScalar", "nonfinite");
              }
              bound(limits, "numeric_chars", number.toString().length());
            } else if (value instanceof ExactNode.NumberToken number) {
              bound(limits, "numeric_chars", number.text().length());
            } else if (value != null && !(value instanceof String) && !(value instanceof Boolean)) {
              throw new LanguageFailure(
                  "InvalidNodeShape", "Unsupported Java value kind: " + value.getClass().getName());
            }
          }
          work.accept.accept(value);
        }
      } catch (LanguageFailure failure) {
        failure.data.putIfAbsent("path", work.path == null ? "" : work.path.text());
        failure.data.putIfAbsent("phase", host ? "construction" : "input");
        throw failure;
      }
    }
    return result[0];
  }

  private static Pointer child(Pointer parent, String key) {
    return new Pointer(parent, key.replace("~", "~0").replace("/", "~1"));
  }

  private static void bound(Map<String, Long> limits, String key, long count) {
    if (limits.containsKey(key) && count > limits.get(key)) {
      throw new LanguageFailure("OperationLimitExceeded", key);
    }
  }
}
