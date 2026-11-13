package blue;

import static blue.BexFailure.require;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.RandomAccess;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** BEX null, undefined, integers and decimal values remain distinct. */
final class BexValues {
  private static final Pattern NUMBER_TEXT =
      Pattern.compile("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");

  public static final Object UNDEFINED =
      new Object() {
        @Override
        public String toString() {
          return "UNDEFINED";
        }
      };

  public interface HostValue {
    String kind();

    int size();

    Object get(Object key);

    List<String> keys();

    Object blueOutput();

    default Object accountingView() {
      return blueOutput();
    }
  }

  // Only freeze() can construct these containers. Their descendants are already checked,
  // so reusing a program, input, or result never copies its immutable tree again.
  private static final class FrozenMap extends AbstractMap<String, Object> {
    private final Map<String, Object> values;

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

  /** Immutable host-input snapshot. Rejects cycles, non-text keys and non-BEX host objects. */
  public static Object freeze(Object value) {
    if (value instanceof FrozenMap || value instanceof FrozenList) {
      return value;
    }
    record Visit(Object value, boolean finish) {}
    var todo = new ArrayDeque<Visit>();
    var frozen = new IdentityHashMap<Object, Object>();
    var active = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
    todo.push(new Visit(value, false));
    while (!todo.isEmpty()) {
      var visit = todo.pop();
      Object v = visit.value();
      if (v == null
          || v == UNDEFINED
          || v instanceof FrozenMap
          || v instanceof FrozenList
          || v instanceof HostValue
          || v instanceof String
          || v instanceof Boolean
          || v instanceof BigInteger
          || v instanceof BigDecimal
          || v instanceof Double
          || v instanceof Float
          || v instanceof Byte
          || v instanceof Short
          || v instanceof Integer
          || v instanceof Long) {
        continue;
      }
      require(v instanceof Map<?, ?> || v instanceof List<?>, "Unsupported BEX host input");
      if (frozen.containsKey(v)) {
        continue;
      }
      if (visit.finish()) {
        Object result;
        if (v instanceof Map<?, ?> m) {
          var target = new TreeMap<String, Object>();
          m.forEach((k, x) -> target.put((String) k, frozen.getOrDefault(x, scalar(x))));
          result = new FrozenMap(target);
        } else {
          var target = new ArrayList<Object>();
          ((List<?>) v).forEach(x -> target.add(frozen.getOrDefault(x, scalar(x))));
          result = new FrozenList(target);
        }
        active.remove(v);
        frozen.put(v, result);
      } else {
        require(active.add(v), "Cyclic BEX host input");
        todo.push(new Visit(v, true));
        if (v instanceof Map<?, ?> m) {
          for (var e : m.entrySet()) {
            require(e.getKey() instanceof String, "BEX object keys must be Text");
            todo.push(new Visit(e.getValue(), false));
          }
        } else {
          for (Object x : (List<?>) v) {
            todo.push(new Visit(x, false));
          }
        }
      }
    }
    return frozen.getOrDefault(value, scalar(value));
  }

  private static Object scalar(Object v) {
    return v instanceof Byte || v instanceof Short || v instanceof Integer || v instanceof Long
        ? BigInteger.valueOf(((Number) v).longValue())
        : v;
  }

  public static Object copy(Object value) {
    return copy(value, null);
  }

  @SuppressWarnings("unchecked") // Every pending target is a container created by this method.
  static Object copy(Object value, java.util.function.UnaryOperator<HostValue> replace) {
    record Pending(Object target, Object key, Object source) {}
    var holder = new ArrayList<Object>(Collections.singletonList(null));
    var pending = new ArrayDeque<Pending>();
    pending.push(new Pending(holder, 0, value));
    var memo = new IdentityHashMap<Object, Object>();
    while (!pending.isEmpty()) {
      var row = pending.pop();
      Object source = row.source();
      if (replace != null && source instanceof HostValue host) {
        source = replace.apply(host);
      }
      Object out = source;
      if (!(source instanceof HostValue)
          && (source instanceof Map<?, ?> || source instanceof List<?>)) {
        out = memo.get(source);
        if (out == null) {
          if (source instanceof Map<?, ?> map) {
            var target = new TreeMap<String, Object>();
            out = target;
            memo.put(source, out);
            for (var entry : map.entrySet()) {
              pending.push(new Pending(target, entry.getKey().toString(), entry.getValue()));
            }
          } else {
            var list = (List<?>) source;
            var target = new ArrayList<Object>(Collections.nCopies(list.size(), null));
            out = target;
            memo.put(source, out);
            for (int i = list.size() - 1; i >= 0; i--) {
              pending.push(new Pending(target, i, list.get(i)));
            }
          }
        }
      } else if (source instanceof Byte
          || source instanceof Short
          || source instanceof Integer
          || source instanceof Long) {
        out = BigInteger.valueOf(((Number) source).longValue());
      }
      if (row.target() instanceof Map<?, ?> map) {
        ((Map<String, Object>) map).put((String) row.key(), out);
      } else {
        ((List<Object>) row.target()).set((Integer) row.key(), out);
      }
    }
    return holder.getFirst();
  }

  public static String kind(Object v) {
    if (v instanceof HostValue h) {
      return h.kind();
    }
    if (v == UNDEFINED) {
      return "undefined";
    }
    if (v == null) {
      return "null";
    }
    if (v instanceof Boolean) {
      return "boolean";
    }
    if (v instanceof BigInteger || v instanceof Integer || v instanceof Long) {
      return "integer";
    }
    if (v instanceof BigDecimal || v instanceof Double || v instanceof Float) {
      number(v);
      return "double";
    }
    if (v instanceof String) {
      return "text";
    }
    if (v instanceof Map) {
      return "object";
    }
    if (v instanceof List) {
      return "list";
    }
    throw new BexFailure("not a BEX value: " + v.getClass());
  }

  public static int size(Object v) {
    if (v instanceof HostValue h) {
      return h.size();
    }
    if (v instanceof Map<?, ?> m) {
      return m.size();
    }
    if (v instanceof List<?> a) {
      return a.size();
    }
    if (v instanceof String s) {
      return s.codePointCount(0, s.length());
    }
    return 0;
  }

  public static Object get(Object v, Object key) {
    if (v instanceof HostValue h) {
      return h.get(key);
    }
    if (v instanceof Map<?, ?> m) {
      return m.containsKey(key) ? m.get(key) : UNDEFINED;
    }
    if (v instanceof List<?> a && key instanceof Integer i) {
      return i >= 0 && i < a.size() ? a.get(i) : UNDEFINED;
    }
    return UNDEFINED;
  }

  public static List<String> keys(Object v) {
    List<String> keys;
    if (v instanceof HostValue h) {
      keys = new ArrayList<>(h.keys());
    } else if (v instanceof Map<?, ?> m) {
      keys = new ArrayList<>(m.keySet().stream().map(Object::toString).toList());
    } else {
      return List.of();
    }
    keys.sort(BexValues::compareKeys);
    return keys;
  }

  private static int compareKeys(String a, String b) {
    int i = 0, j = 0;
    while (i < a.length() && j < b.length()) {
      int left = a.codePointAt(i), right = b.codePointAt(j);
      if (left != right) {
        return Integer.compare(left, right);
      }
      i += Character.charCount(left);
      j += Character.charCount(right);
    }
    return Integer.compare(a.length() - i, b.length() - j);
  }

  public static List<String> ordered(Object v, BexGas gas) {
    int n = size(v), rounds = n == 0 ? 0 : 32 - Integer.numberOfLeadingZeros(n - 1);
    if (gas != null) {
      gas.charge("valueMember", n);
    }
    var keys = keys(v);
    if (gas != null) {
      gas.charge("valueMember", (long) n * rounds);
      long work = 0;
      for (String k : keys) {
        work += chunks(k);
      }
      gas.charge("textWork", work * (rounds + 1));
    }
    return keys;
  }

  public static long chunks(String text) {
    return (size(text) + 63L) / 64;
  }

  public static boolean truthy(Object v) {
    return switch (kind(v)) {
      case "undefined", "null" -> false;
      case "boolean" -> (Boolean) v;
      case "text", "object", "list" -> size(v) != 0;
      default -> true;
    };
  }

  public static BigDecimal number(Object v) {
    try {
      BigDecimal d;
      if (v instanceof BigDecimal b) {
        d = b;
      } else if (v instanceof BigInteger b) {
        d = new BigDecimal(b);
      } else if (v instanceof Byte
          || v instanceof Short
          || v instanceof Integer
          || v instanceof Long) {
        d = BigDecimal.valueOf(((Number) v).longValue());
      } else if (v instanceof Double || v instanceof Float) {
        double value = ((Number) v).doubleValue();
        require(Double.isFinite(value), "non-finite Double");
        d = BigDecimal.valueOf(value);
      } else if (v instanceof String s) {
        require(NUMBER_TEXT.matcher(s).matches(), "invalid numeric text");
        d = new BigDecimal(s);
      } else {
        throw new BexFailure("numeric conversion requires Integer, Double or Text");
      }
      return d;
    } catch (NumberFormatException e) {
      throw new BexFailure("invalid numeric text");
    }
  }

  public static BigInteger integer(Object v) {
    if (v instanceof String s) {
      require(LanguageSyntax.INTEGER.matcher(s).matches(), "integer text is not canonical");
      return new BigInteger(s);
    }
    try {
      return number(v).toBigIntegerExact();
    } catch (ArithmeticException e) {
      throw new BexFailure("non-integral number");
    }
  }

  public static String text(Object v) {
    return switch (kind(v)) {
      case "undefined", "null" -> "";
      case "boolean", "integer", "text" -> v.toString();
      case "double" ->
          v instanceof Double || v instanceof Float ? number(v).toString() : v.toString();
      default -> throw new BexFailure("cannot implicitly render a collection as Text");
    };
  }

  public static String requiredText(Object v) {
    require(v != null && v != UNDEFINED, "missing/null path, name, operation or key");
    return text(v);
  }

  public static int limbs(BigInteger n) {
    return Math.max(1, (n.abs().bitLength() + 31) / 32);
  }

  /** Compares values in canonical traversal order without using the Java call stack. */
  public static boolean equal(Object a, Object b, BexGas gas) {
    Set<Pair> active = new HashSet<>();
    Deque<Comparison> pending = new ArrayDeque<>();
    pending.push(new Comparison(a, b));
    while (!pending.isEmpty()) {
      Comparison current = pending.peek();
      a = current.a;
      b = current.b;
      if (current.children != null) {
        if (!current.children.hasNext()) {
          active.remove(new Pair(a, b));
          pending.pop();
        } else {
          Object key = current.children.next();
          pending.push(new Comparison(get(a, key), get(b, key)));
        }
        continue;
      }
      if (gas != null) {
        gas.charge("valueMember");
      }
      String ka = kind(a), kb = kind(b);
      if (Set.of("integer", "double").contains(ka) && Set.of("integer", "double").contains(kb)) {
        boolean both = ka.equals("integer") && kb.equals("integer");
        long work = 0;
        for (Object x : new Object[] {a, b}) {
          if (kind(x).equals("integer")) {
            int n = limbs(integer(x));
            work += both ? n : (long) n * n;
          }
        }
        if (gas != null) {
          gas.charge("integerWork", work);
        }
        if (number(a).compareTo(number(b)) != 0) {
          return false;
        }
      } else if (!ka.equals(kb)) {
        return false;
      } else if (ka.equals("object") || ka.equals("list")) {
        if (active.add(new Pair(a, b))) {
          if (ka.equals("list")) {
            int length = size(a);
            if (length != size(b)) {
              return false;
            }
            current.children = java.util.stream.IntStream.range(0, length).iterator();
          } else {
            List<String> left = ordered(a, gas), right = ordered(b, gas);
            if (!left.equals(right)) {
              return false;
            }
            current.children = left.iterator();
          }
          continue;
        }
      } else if (ka.equals("text") && gas != null) {
        if (size(a) != size(b)
            || MeteredText.compare((String) a, (String) b, () -> gas.charge("textWork", 2)) != 0) {
          return false;
        }
      } else if (!ka.equals("undefined") && !Objects.equals(a, b)) {
        return false;
      }
      pending.pop();
    }
    return true;
  }

  private static final class Comparison {
    final Object a, b;
    Iterator<?> children;

    Comparison(Object a, Object b) {
      this.a = a;
      this.b = b;
    }
  }

  private record Pair(Object a, Object b) {
    @Override
    public boolean equals(Object o) {
      return o instanceof Pair p && p.a == a && p.b == b;
    }

    @Override
    public int hashCode() {
      return System.identityHashCode(a) * 31 + System.identityHashCode(b);
    }
  }

  public static void sizeParts(Object root, BexGas gas, java.util.function.LongConsumer consume) {
    var pending = new ArrayList<Object>();
    pending.add(root);
    while (!pending.isEmpty()) {
      Object v = pending.removeLast();
      if (v instanceof HostValue h) {
        v = h.accountingView();
      }
      String k = kind(v);
      if (k.equals("undefined") || k.equals("null")) {
        continue;
      }
      if (k.equals("list")) {
        consume.accept(size(v));
        for (int i = size(v) - 1; i >= 0; i--) {
          pending.add(get(v, i));
        }
      } else if (k.equals("object")) {
        consume.accept(size(v));
        var keys = ordered(v, gas);
        for (String key : keys) {
          if (gas != null) {
            gas.charge("textWork", chunks(key));
          }
          consume.accept(key.length());
        }
        for (int i = keys.size() - 1; i >= 0; i--) {
          pending.add(get(v, keys.get(i)));
        }
      } else {
        if (gas != null && k.equals("integer")) {
          long limbs = limbs(integer(v));
          gas.charge("integerWork", limbs * limbs);
        }
        String rendered = text(v);
        if (gas != null) {
          gas.charge("textWork", chunks(rendered));
        }
        consume.accept(Math.max(1, rendered.length()));
      }
    }
  }

  public static List<String> segments(String path) {
    if (path.isEmpty() || path.equals("/")) {
      return List.of();
    }
    require(path.startsWith("/"), "absolute value pointer required");
    var out = new ArrayList<String>();
    for (String part : path.substring(1).split("/", -1)) {
      require(
          !LanguageSyntax.INVALID_POINTER_ESCAPE.matcher(part).matches(),
          "invalid JSON pointer escape");
      out.add(part.replace("~1", "/").replace("~0", "~"));
    }
    return out;
  }

  public static String escape(String key) {
    return key.replace("~", "~0").replace("/", "~1");
  }

  public static int index(String segment, int length, boolean add) {
    if (add && segment.equals("-")) {
      return length;
    }
    require(LanguageSyntax.LIST_INDEX.matcher(segment).matches(), "noncanonical list index");
    try {
      int i = new BigInteger(segment).intValueExact();
      require(i < length || i == length && add, "list index outside bounds");
      return i;
    } catch (ArithmeticException e) {
      throw new BexFailure("list index outside bounds");
    }
  }

  public static Object pointerGet(Object value, String path) {
    Object at = value;
    for (String s : segments(path)) {
      if (kind(at).equals("object")) {
        at = get(at, s);
      } else if (kind(at).equals("list")) {
        try {
          at = get(at, index(s, size(at), false));
        } catch (BexFailure e) {
          return UNDEFINED;
        }
      } else {
        return UNDEFINED;
      }
    }
    return at;
  }

  public static Object overlay(
      Object value, String path, Object replacement, boolean remove, BexGas gas) {
    List<String> parts = segments(path);
    if (parts.isEmpty()) {
      return remove ? UNDEFINED : copy(replacement);
    }
    return overlayAt(value, parts, replacement, remove, gas);
  }

  private record PointerParent(Object container, Object key, boolean last) {}

  @SuppressWarnings("unchecked") // Every parent container is a copy created by this method.
  private static Object overlayAt(
      Object at, List<String> parts, Object replacement, boolean remove, BexGas gas) {
    var parents = new ArrayDeque<PointerParent>();
    for (int pos = 0; pos < parts.size(); pos++) {
      if (at == null || at == UNDEFINED) {
        at = Map.of();
      }
      String kind = kind(at), segment = parts.get(pos);
      boolean last = pos == parts.size() - 1;
      require(kind.equals("object") || kind.equals("list"), "scalar pointer target parent");
      if (kind.equals("object")) {
        var out = new LinkedHashMap<String, Object>();
        for (String key : ordered(at, gas)) {
          out.put(key, get(at, key));
        }
        parents.push(new PointerParent(out, segment, last));
        at = out.getOrDefault(segment, UNDEFINED);
      } else {
        if (gas != null) {
          gas.charge("valueMember", size(at));
        }
        var out = new ArrayList<Object>();
        for (int i = 0; i < size(at); i++) {
          out.add(get(at, i));
        }
        int key = index(segment, out.size(), last && !remove);
        parents.push(new PointerParent(out, key, last));
        at = last ? UNDEFINED : out.get(key);
      }
    }
    Object result = remove ? UNDEFINED : copy(replacement);
    while (!parents.isEmpty()) {
      PointerParent parent = parents.pop();
      if (parent.container() instanceof Map<?, ?>) {
        var object = (Map<String, Object>) parent.container();
        if (parent.last() && result == UNDEFINED) {
          object.remove(parent.key());
        } else {
          object.put((String) parent.key(), result);
        }
      } else {
        var list = (List<Object>) parent.container();
        int index = (Integer) parent.key();
        if (index == list.size()) {
          list.add(result);
        } else {
          list.set(index, result);
        }
      }
      result = parent.container();
    }
    return result;
  }

  /** Preserve exact document views until the Language boundary accounts for their evidence. */
  static Object blueInput(Object value) {
    return blueValue(value, true);
  }

  public static Object blueValue(Object value) {
    return blueValue(value, false);
  }

  private static Object blueValue(Object v, boolean preserveViews) {
    if (v instanceof HostValue h) {
      return preserveViews ? h : h.blueOutput();
    }
    return switch (kind(v)) {
      case "undefined", "null" ->
          throw new BexFailure("BEX_OUTPUT_INVALID", "null or undefined is not a Blue value");
      case "object" -> {
        var out = new LinkedHashMap<String, Object>();
        for (String key : keys(v)) {
          Object child = get(v, key);
          if (child != UNDEFINED) {
            out.put(key, blueValue(child, preserveViews));
          }
        }
        yield out;
      }
      case "list" -> {
        var out = new ArrayList<Object>();
        for (int i = 0; i < size(v); i++) {
          out.add(blueValue(get(v, i), preserveViews));
        }
        yield out;
      }
      case "double" -> {
        double d = number(v).doubleValue();
        if (!Double.isFinite(d)) {
          throw new BexFailure("BEX_OUTPUT_INVALID", "Double output overflow");
        }
        yield d;
      }
      default -> v;
    };
  }
}
