package blue;

/** Canonical wire data utilities for adapters. No admission, trust or mutable runtime state. */
final class ContractWire {
  private ContractWire() {}

  public static String encode(Object value) {
    return LanguageCodec.wire(value);
  }

  public static Object decode(String json) {
    return LanguageCodec.json(json);
  }

  public static Object copy(Object value) {
    return ContractData.wireCopy(value);
  }

  public static Object immutable(Object value) {
    return ContractData.freeze(value);
  }

  /**
   * True only for containers whose recursive immutable ownership was established by this library.
   */
  public static boolean isImmutableContainer(Object value) {
    return ContractData.isImmutableContainer(value);
  }

  public static Object detached(Object value) {
    return ContractData.detached(value);
  }

  /** Exact JSON rendering, preserving the selected numeric categories. */
  public static String write(Object value) {
    return LanguageCodec.write(value);
  }

  /**
   * Writes precisely the UTF-8 bytes of write(value), without building the whole string. Flushes
   * but does not close the caller's stream.
   */
  public static void writeUtf8(Object value, java.io.OutputStream output)
      throws java.io.IOException {
    output.write(LanguageCodec.write(value).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    output.flush();
  }

  /** An immutable key layout for compact records containing immutable scalar values. */
  public static final class RecordShape {
    private final ContractData.RecordLayout layout;

    private RecordShape(String[] names) {
      String[] keys = names.clone();
      var unique = new java.util.HashSet<String>();
      for (String key : keys) {
        LanguageCodec.unicode(java.util.Objects.requireNonNull(key));
        if (!unique.add(key)) {
          throw new IllegalArgumentException("Duplicate record key: " + key);
        }
      }
      layout = new ContractData.RecordLayout(keys);
    }

    public java.util.Map<String, Object> record(Object... values) {
      return ContractData.scalarRecord(layout, values);
    }

    /**
     * Replace one field of this shape's immutable record, retaining its validated scalar leaves.
     */
    public java.util.Map<String, Object> with(
        java.util.Map<String, Object> record, String key, Object value) {
      return ContractData.replaceScalar(layout, record, key, value);
    }

    public boolean owns(java.util.Map<String, Object> record) {
      return record instanceof ContractData.ScalarRecord row && row.layout == layout;
    }
  }

  public static RecordShape recordShape(String... keys) {
    return new RecordShape(keys);
  }

  public static String wire(Object value) {
    return LanguageCodec.wire(value);
  }

  public static Object json(String value) {
    ContractMetrics.count("wire.jsonDecodeCalls");
    ContractMetrics.add("wire.jsonDecodedCharacters", value.length());
    return LanguageCodec.json(value);
  }

  public static Object parse(String text, String format) {
    return LanguageCodec.parse(text, format);
  }

  public static Object data(Object value) {
    return PackageIntegrity.data(value);
  }

  public static String yamlText(Object value) {
    return LanguageCodec.yamlText(value);
  }

  public static String sha256(byte[] bytes) {
    return PackageIntegrity.sha(bytes);
  }
}
