package blue;

import static blue.ContractModel.require;

import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Private Contracts records. This codec is never used to calculate a BlueId. */
@SuppressWarnings({"unchecked", "serial", "try"})
final class ContractCanonical {
  private ContractCanonical() {}

  static final Comparator<String> CODE_POINTS = ContractCanonical::compare;

  static int compare(String a, String b) {
    if (a == b) return 0;
    int lexical = a.compareTo(b);
    // String comparison is intrinsified by the VM. Its order can differ from scalar
    // order only across a high surrogate and a BMP character above the surrogate
    // range: the smallest possible code-unit gap is 0xE000 - 0xDBFF = 1025.
    // Smaller differences (including all ASCII keys) need no second prefix scan.
    if (lexical >= -1024 && lexical <= 1024) return lexical;
    int length = Math.min(a.length(), b.length());
    for (int i = 0; i < length; i++) {
      // Equal UTF-16 prefixes need no decoding. At the first differing code unit,
      // decode supplementary characters so BMP/private-use ordering stays canonical.
      if (a.charAt(i) != b.charAt(i)) return Integer.compare(a.codePointAt(i), b.codePointAt(i));
    }
    return Integer.compare(a.length(), b.length());
  }

  static int compareSegments(List<String> a, List<String> b) {
    for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
      int c = ContractCanonical.compare(a.get(i), b.get(i));
      if (c != 0) return c;
    }
    return Integer.compare(a.size(), b.size());
  }

  static <V> TreeMap<String, V> map(Map<String, V> source) {
    var result = new TreeMap<String, V>(CODE_POINTS);
    result.putAll(source);
    return result;
  }

  static TreeSet<String> keys(Collection<String> source) {
    var result = new TreeSet<String>(CODE_POINTS);
    result.addAll(source);
    return result;
  }

  static String text(Object value) {
    return new String(bytes(value), StandardCharsets.UTF_8);
  }

  private static byte[] bytes(Object value) {
    var output = new ByteArrayOutputStream();
    try {
      write(value, output);
    } catch (IOException impossible) {
      throw new UncheckedIOException(impossible);
    }
    return output.toByteArray();
  }

  /** Pre-encoded private commitment data. Constructed only from validated record values. */
  static final class Fragment {
    private final byte[] bytes;

    Fragment(Object value) {
      bytes = ContractCanonical.bytes(value);
    }
  }

  /** One pre-encoded object field; its surrounding object remains owned by the caller. */
  static final class Field {
    private final byte[] bytes;

    Field(String name, Object value) {
      byte[] object = ContractCanonical.bytes(Collections.singletonMap(name, value));
      bytes = Arrays.copyOfRange(object, 1, object.length - 1);
    }
  }

  /** A synchronous canonical-order view of private fields, never a public receipt value. */
  record Fields(Collection<Field> fields) {}

  /** A synchronous view of privately ordered fragments; never exported as a receipt value. */
  record Sequence(Collection<Fragment> fragments) {}

  /** A privately owned immutable record with its exact commitment bytes prepared once. */
  static final class EncodedRecord extends AbstractMap<String, Object> {
    private final Map<String, Object> values;
    private final byte[] bytes;

    EncodedRecord(Map<String, Object> source) {
      values = ContractModel.obj(ContractWire.immutable(source));
      bytes = ContractCanonical.bytes(values);
    }

    public Set<Entry<String, Object>> entrySet() {
      return values.entrySet();
    }

    public Object get(Object key) {
      return values.get(key);
    }

    @Override
    public boolean containsKey(Object key) {
      return values.containsKey(key);
    }

    public int size() {
      return values.size();
    }

    int encodedSize() {
      return bytes.length;
    }
  }

  static void write(Object value, OutputStream output) throws IOException {
    CanonicalOutput out = new CanonicalOutput(output);
    write(value, out);
    out.flush();
  }

  private static void write(Object value, CanonicalOutput out) throws IOException {
    if (value instanceof ContractData.ScalarRecord record && record.layout.contractOrder) {
      // Reuse the immutable row's already escaped keys only when UTF-16 and Contracts scalar
      // ordering agree. Supplementary/BMP mixtures retain the general canonical ordering path.
      out.append('{');
      for (int i = 0; i < record.layout.canonicalOrder.length; i++) {
        out.appendUtf8(record.layout.utf8Prefixes[i]);
        write(record.values[record.layout.canonicalOrder[i]], out);
      }
      out.append('}');
      return;
    }
    if (value instanceof EncodedRecord record) {
      out.appendUtf8(record.bytes);
      return;
    }
    if (value instanceof Fragment fragment) {
      out.appendUtf8(fragment.bytes);
      return;
    }
    if (value instanceof Fields fields) {
      out.append('{');
      boolean first = true;
      for (Field field : fields.fields()) {
        if (!first) out.append(',');
        out.appendUtf8(field.bytes);
        first = false;
      }
      out.append('}');
      return;
    }
    if (value instanceof Sequence sequence) {
      out.append('[');
      boolean first = true;
      for (Fragment fragment : sequence.fragments()) {
        if (!first) out.append(',');
        out.appendUtf8(fragment.bytes);
        first = false;
      }
      out.append(']');
      return;
    }
    if (value == null) {
      ascii(out, "null");
      return;
    }
    if (value instanceof Integer || value instanceof Long) {
      out.number(((Number) value).longValue());
      return;
    }
    if (value instanceof Boolean || value instanceof BigInteger) {
      ascii(out, value.toString());
      return;
    }
    if (value instanceof String text) {
      // The shared string escaper already uses exact Unicode and lower-case JSON escapes.
      try {
        out.quote(text);
      } catch (LanguageFailure invalid) {
        require(false, "INVALID_RECORD");
      }
      return;
    }
    if (value instanceof List<?> list) {
      out.append('[');
      for (int i = 0; i < list.size(); i++) {
        if (i != 0) out.append(',');
        write(list.get(i), out);
      }
      out.append(']');
      return;
    }
    require(value instanceof Map<?, ?>, "INVALID_RECORD");
    var map = (Map<?, ?>) value;
    require(map.keySet().stream().allMatch(k -> k instanceof String), "INVALID_RECORD");
    var keys = map.keySet().stream().map(k -> (String) k).sorted(CODE_POINTS).toList();
    out.append('{');
    for (int i = 0; i < keys.size(); i++) {
      if (i != 0) out.append(',');
      String key = keys.get(i);
      write(key, out);
      out.append(':');
      write(map.get(key), out);
    }
    out.append('}');
  }

  private static void ascii(CanonicalOutput out, String value) throws IOException {
    out.append(value);
  }
}
