package blue;

import static blue.LanguageFailure.require;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.math.*;
import java.util.*;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.*;

/** Strict scalar parsing and canonical helper encoding (Language A02–A03). */
final class LanguageCodec {
  static final ObjectMapper JSON =
      new ObjectMapper(
              JsonFactory.builder()
                  .disable(JsonFactory.Feature.INTERN_FIELD_NAMES)
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxNestingDepth(Integer.MAX_VALUE)
                          .maxNumberLength(Integer.MAX_VALUE)
                          .maxStringLength(Integer.MAX_VALUE)
                          .maxNameLength(Integer.MAX_VALUE)
                          .build())
                  .build())
          .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
  static final BigInteger SAFE = new BigInteger("9007199254740991");

  private LanguageCodec() {}

  public static Object json(String text) {
    try {
      return JSON.readValue(text, Object.class);
    } catch (IOException e) {
      throw new LanguageFailure("InvalidSyntax", e.getMessage());
    }
  }

  static Object parse(String text, String format) {
    return parse(text, format, null);
  }

  static Object parse(String text, String format, LanguageContext context) {
    try {
      var budget = new InputBudget(context);
      Object result;
      if (format.equals("yaml")) {
        var opts = new LoaderOptions();
        opts.setAllowDuplicateKeys(false);
        opts.setCodePointLimit(Integer.MAX_VALUE);
        opts.setNestingDepthLimit(Integer.MAX_VALUE);
        var yaml = new Yaml(opts);
        var containers = new ArrayDeque<YamlFrame>();
        for (var event : yaml.parse(new StringReader(text))) {
          if (event instanceof org.yaml.snakeyaml.events.CollectionEndEvent) {
            containers.pop();
          } else if (event instanceof org.yaml.snakeyaml.events.ScalarEvent scalar) {
            if (!yamlKey(containers)) {
              budget.value(
                  containers.size() + 1,
                  scalar.isPlain() && LanguageSyntax.NUMBER.matcher(scalar.getValue()).matches()
                      ? scalar.getValue().length()
                      : 0);
            }
          } else if (event instanceof org.yaml.snakeyaml.events.CollectionStartEvent collection) {
            require(!yamlKey(containers), "InvalidSyntax", "YAML key");
            budget.value(containers.size() + 1, 0);
            containers.push(
                new YamlFrame(collection instanceof org.yaml.snakeyaml.events.MappingStartEvent));
          }
          if (event instanceof org.yaml.snakeyaml.events.DocumentStartEvent startEvent) {
            require(
                startEvent.getVersion() == null
                    && (startEvent.getTags() == null || startEvent.getTags().isEmpty()),
                "InvalidSyntax",
                "YAML directives are forbidden");
          }
          if (event instanceof org.yaml.snakeyaml.events.NodeEvent nodeEvent) {
            require(
                nodeEvent.getAnchor() == null,
                "InvalidSyntax",
                "YAML anchors and aliases are forbidden");
          }
          if (event instanceof org.yaml.snakeyaml.events.ScalarEvent scalarEvent) {
            require(scalarEvent.getTag() == null, "InvalidSyntax", "YAML tags are forbidden");
          }
          if (event instanceof org.yaml.snakeyaml.events.CollectionStartEvent collectionEvent) {
            require(collectionEvent.getTag() == null, "InvalidSyntax", "YAML tags are forbidden");
          }
        }
        var docs = yaml.composeAll(new StringReader(text)).iterator();
        require(docs.hasNext(), "InvalidRoot");
        result = yaml(docs.next(), Collections.newSetFromMap(new IdentityHashMap<>()));
        require(!docs.hasNext(), "InvalidSyntax", "multiple YAML documents");
      } else {
        require(format.equals("json"), "UnsupportedFormat");
        try (JsonParser p = JSON.getFactory().createParser(text)) {
          p.nextToken();
          result = read(p, budget, 1);
          require(p.nextToken() == null, "InvalidSyntax", "trailing content");
        }
      }
      require(result != null, "InvalidRoot");
      return result;
    } catch (LanguageFailure f) {
      throw f;
    } catch (StackOverflowError e) {
      throw new LanguageFailure("OperationLimitExceeded", "parser recursion");
    } catch (Exception e) {
      throw new LanguageFailure(
          "InvalidSyntax", e.getMessage() == null ? e.toString() : e.getMessage());
    }
  }

  private static final class InputBudget {
    final LanguageContext context;
    long values;

    InputBudget(LanguageContext context) {
      this.context = context;
    }

    void value(int depth, int numericCharacters) {
      if (context == null) {
        return;
      }
      context.bound("values", ++values);
      context.bound("depth", depth);
      if (numericCharacters > 0) {
        context.bound("numeric_chars", numericCharacters);
      }
    }
  }

  private static final class YamlFrame {
    final boolean map;
    boolean key = true;

    YamlFrame(boolean map) {
      this.map = map;
    }
  }

  private static boolean yamlKey(Deque<YamlFrame> stack) {
    if (stack.isEmpty() || !stack.peek().map) {
      return false;
    }
    boolean key = stack.peek().key;
    stack.peek().key = !key;
    return key;
  }

  private static Object read(JsonParser p, InputBudget budget, int depth) throws IOException {
    if (p.currentToken() == null) {
      throw new LanguageFailure("InvalidSyntax");
    }
    budget.value(depth, p.currentToken().isNumeric() ? p.getTextLength() : 0);
    return switch (p.currentToken()) {
      case START_OBJECT -> {
        var out = new TreeMap<String, Object>();
        while (p.nextToken() != JsonToken.END_OBJECT) {
          require(p.currentToken() == JsonToken.FIELD_NAME, "InvalidSyntax");
          String k = unicode(p.currentName());
          require(!out.containsKey(k), "DuplicateKey", k);
          p.nextToken();
          out.put(k, read(p, budget, depth + 1));
        }
        yield out;
      }
      case START_ARRAY -> {
        var out = new ArrayList<Object>();
        while (p.nextToken() != JsonToken.END_ARRAY) {
          out.add(read(p, budget, depth + 1));
        }
        yield out;
      }
      case VALUE_STRING -> unicode(p.getText());
      case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> new ExactNode.NumberToken(p.getText());
      case VALUE_TRUE -> true;
      case VALUE_FALSE -> false;
      case VALUE_NULL -> null;
      default -> throw new LanguageFailure("InvalidSyntax");
    };
  }

  private static Object yaml(
      org.yaml.snakeyaml.nodes.Node node, Set<org.yaml.snakeyaml.nodes.Node> active) {
    require(active.add(node), "InvalidSyntax", "cyclic YAML alias");
    try {
      if (node instanceof ScalarNode s) {
        String v = unicode(s.getValue());
        if (!s.isPlain()) {
          return v;
        }
        if (v.equals("null") || v.isEmpty()) {
          return null;
        }
        if (v.equals("true")) {
          return true;
        }
        if (v.equals("false")) {
          return false;
        }
        if (LanguageSyntax.NUMBER.matcher(v).matches()) {
          return new ExactNode.NumberToken(v);
        }
        return v;
      }
      if (node instanceof SequenceNode s) {
        var out = new ArrayList<>();
        for (var n : s.getValue()) {
          out.add(yaml(n, active));
        }
        return out;
      }
      require(node instanceof MappingNode, "InvalidSyntax");
      var out = new TreeMap<String, Object>();
      for (var entry : ((MappingNode) node).getValue()) {
        Object key = yaml(entry.getKeyNode(), active);
        require(
            key instanceof String
                && !(key.equals("<<")
                    && entry.getKeyNode() instanceof ScalarNode scalar
                    && scalar.isPlain()),
            "InvalidSyntax",
            "YAML key");
        require(!out.containsKey(key), "DuplicateKey", key.toString());
        out.put((String) key, yaml(entry.getValueNode(), active));
      }
      return out;
    } finally {
      active.remove(node);
    }
  }

  static String unicode(String s) {
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (Character.isHighSurrogate(c)) {
        if (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(++i))) {
          throw new LanguageFailure("InvalidSyntax", "unpaired surrogate");
        }
      } else if (Character.isLowSurrogate(c)) {
        throw new LanguageFailure("InvalidSyntax", "unpaired surrogate");
      }
    }
    return s;
  }

  static String quote(String s) {
    var out = new StringBuilder(s.length() + 2);
    try {
      appendQuote(s, out);
    } catch (IOException impossible) {
      throw new AssertionError(impossible);
    }
    return out.toString();
  }

  private static void appendQuote(String s, Appendable out) throws IOException {
    if (out instanceof CanonicalOutput stream && s.length() <= 256) {
      stream.quote(s);
      return;
    }
    out.append('"');
    int start = 0;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      // Validate while finding escape boundaries; ordinary text needs only one scan.
      if (Character.isHighSurrogate(c)) {
        if (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(++i))) {
          throw new LanguageFailure("InvalidSyntax", "unpaired surrogate");
        }
      } else if (Character.isLowSurrogate(c)) {
        throw new LanguageFailure("InvalidSyntax", "unpaired surrogate");
      }
      if (c >= 32 && c != '"' && c != '\\') {
        continue;
      }
      out.append(s, start, i);
      start = i + 1;
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 32) {
            out.append("\\u00");
            out.append("0123456789abcdef".charAt(c >>> 4));
            out.append("0123456789abcdef".charAt(c & 15));
          } else {
            out.append(c);
          }
        }
      }
    }
    out.append(s, start, s.length());
    out.append('"');
  }

  public static String write(Object x) {
    return render(x, false);
  }

  static String wire(Object x) {
    return render(x, true);
  }

  /** Write the retained representation directly, without an intermediate export tree. */
  static String exact(ExactNode value) {
    var out = new StringBuilder();
    try {
      appendExact(value, out);
    } catch (IOException impossible) {
      throw new AssertionError(impossible);
    }
    return out.toString();
  }

  private static void appendExact(ExactNode node, StringBuilder out) throws IOException {
    if (node instanceof ExactNode.Ref ref) {
      out.append("{\"blueId\":");
      appendQuote(ref.id(), out);
      out.append('}');
    } else if (node instanceof ExactNode.Local local) {
      out.append("{\"blueId\":\"this#").append(local.index()).append("\"}");
    } else {
      var fields = ((ExactNode.Value) node).fields();
      if (fields.size() == 1 && fields.containsKey("items")) {
        appendExactItems(fields.get("items"), out);
        return;
      }
      out.append('{');
      boolean first = true;
      // Exact fields are immutable and already in canonical key order.
      for (var field : fields.entrySet()) {
        if (!first) out.append(',');
        first = false;
        String key = field.getKey();
        appendQuote(key, out);
        out.append(':');
        if (LanguageCore.LITERALS.contains(key) || key.equals("mergePolicy")) {
          appendRendered(field.getValue(), true, out);
        } else if (key.equals("items")) {
          appendExactItems(field.getValue(), out);
        } else {
          appendExact((ExactNode) field.getValue(), out);
        }
      }
      out.append('}');
    }
  }

  private static void appendExactItems(Object value, StringBuilder out) throws IOException {
    out.append('[');
    boolean first = true;
    for (ExactNode item : LanguageCore.exactItems(value)) {
      if (!first) out.append(',');
      first = false;
      appendExact(item, out);
    }
    out.append(']');
  }

  /** YAML emitter preserves scalar categories, including quoted numeric-looking Text. */
  static String yamlText(Object value) {
    var options = new org.yaml.snakeyaml.DumperOptions();
    options.setDefaultFlowStyle(org.yaml.snakeyaml.DumperOptions.FlowStyle.BLOCK);
    options.setIndent(2);
    options.setIndicatorIndent(0);
    options.setSplitLines(false);
    options.setWidth(120);
    var representer =
        new org.yaml.snakeyaml.representer.Representer(options) {
          {
            representers.put(
                ExactNode.NumberToken.class,
                data -> {
                  var token = (ExactNode.NumberToken) data;
                  return representScalar(token.decimal() ? Tag.FLOAT : Tag.INT, token.text());
                });
          }
        };
    return new Yaml(representer, options).dump(value);
  }

  private static String render(Object x, boolean wire) {
    var out = new StringBuilder();
    try {
      appendRendered(x, wire, out);
    } catch (IOException impossible) {
      throw new AssertionError(impossible);
    }
    return out.toString();
  }

  /** Append canonical JSON without creating a temporary string for a helper field. */
  static void append(Object value, StringBuilder output) {
    try {
      appendRendered(value, false, output);
    } catch (IOException impossible) {
      throw new AssertionError(impossible);
    }
  }

  static void writeUtf8(Object value, OutputStream output) throws IOException {
    var writer = new CanonicalOutput(output);
    appendRendered(value, false, writer);
    writer.flush();
  }

  private static void appendRendered(Object x, boolean wire, Appendable out) throws IOException {
    if (x == null) {
      out.append("null");
      return;
    }
    if (x instanceof ExactNode.NumberToken n) {
      out.append(n.text());
      return;
    }
    if (x instanceof String s) {
      appendQuote(s, out);
      return;
    }
    if (x instanceof Boolean) {
      out.append(x.toString());
      return;
    }
    if (x instanceof Double || x instanceof Float) {
      double d = ((Number) x).doubleValue();
      require(Double.isFinite(d), "InvalidHashHelper");
      String n = number(d);
      out.append(n);
      if (wire && !n.contains(".") && !n.contains("e")) {
        out.append(".0");
      }
      return;
    }
    if (x instanceof Number) {
      if (out instanceof CanonicalOutput stream
          && (x instanceof Long
              || x instanceof Integer
              || x instanceof Short
              || x instanceof Byte)) {
        stream.number(((Number) x).longValue());
      } else {
        out.append(x.toString());
      }
      return;
    }
    if (x instanceof Map<?, ?> m) {
      out.append('{');
      boolean first = true;
      String[] keys = new String[m.size()];
      int index = 0;
      for (Object key : m.keySet()) {
        keys[index++] = key.toString();
      }
      Arrays.sort(keys);
      String previous = null;
      for (String k : keys) {
        if (k.equals(previous)) {
          continue;
        }
        previous = k;
        if (!first) {
          out.append(',');
        }
        first = false;
        appendQuote(k, out);
        out.append(':');
        appendRendered(m.get(k), wire, out);
      }
      out.append('}');
      return;
    }
    if (x instanceof Collection<?> c) {
      out.append('[');
      boolean first = true;
      for (Object a : c) {
        if (!first) {
          out.append(',');
        }
        first = false;
        appendRendered(a, wire, out);
      }
      out.append(']');
      return;
    }
    throw new LanguageFailure("InvalidHashHelper", x.getClass().getName());
  }

  /** Shortest round-tripping binary64 decimal, exact-distance and even ties. */
  static String number(double value) {
    if (value == 0) {
      return "0";
    }
    require(Double.isFinite(value), "InvalidScalar", "nonfinite");
    String sign = value < 0 ? "-" : "";
    double abs = Math.abs(value);
    BigDecimal q = new BigDecimal(abs);
    int e = q.precision() - q.scale() - 1;
    for (int k = 1; k <= 17; k++) {
      BigInteger best = null;
      BigDecimal bestDistance = null;
      int bestN = 0;
      for (int n = e; n <= e + 2; n++) {
        int power = n - k;
        BigDecimal scaled = q.scaleByPowerOfTen(-power);
        BigInteger floor = scaled.toBigInteger();
        for (BigInteger s : List.of(floor, floor.add(BigInteger.ONE))) {
          if (s.toString().length() != k || s.signum() == 0) {
            continue;
          }
          BigDecimal candidate = new BigDecimal(s).scaleByPowerOfTen(power);
          if (candidate.doubleValue() != abs) {
            continue;
          }
          BigDecimal distance = candidate.subtract(q).abs();
          if (best == null
              || distance.compareTo(bestDistance) < 0
              || distance.compareTo(bestDistance) == 0
                  && (best.testBit(0) && !s.testBit(0)
                      || best.testBit(0) == s.testBit(0) && s.compareTo(best) < 0)) {
            best = s;
            bestDistance = distance;
            bestN = n;
          }
        }
      }
      if (best != null) {
        String d = best.toString();
        int n = bestN;
        String result;
        if (k <= n && n <= 21) {
          result = d + "0".repeat(n - k);
        } else if (0 < n && n <= 21 && n < k) {
          result = d.substring(0, n) + "." + d.substring(n);
        } else if (-6 < n && n <= 0) {
          result = "0." + "0".repeat(-n) + d;
        } else {
          result =
              d.charAt(0)
                  + (k > 1 ? "." + d.substring(1) : "")
                  + "e"
                  + (n - 1 >= 0 ? "+" : "-")
                  + Math.abs(n - 1);
        }
        return sign + result;
      }
    }
    throw new LanguageFailure("InternalError", "shortest decimal");
  }
}
