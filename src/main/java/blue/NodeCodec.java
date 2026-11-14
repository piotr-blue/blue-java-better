package blue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

final class NodeCodec {
  private static final ObjectMapper JSON =
      new ObjectMapper(
              com.fasterxml.jackson.core.JsonFactory.builder()
                  .disable(com.fasterxml.jackson.core.JsonFactory.Feature.INTERN_FIELD_NAMES)
                  .disable(com.fasterxml.jackson.core.JsonFactory.Feature.CANONICALIZE_FIELD_NAMES)
                  .build())
          .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

  Node readYaml(String text) {
    var options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    options.setMaxAliasesForCollections(0);
    options.setNestingDepthLimit(128);
    options.setCodePointLimit(16 * 1024 * 1024);
    try {
      return node(new Yaml(new SafeConstructor(options)).load(text), 0);
    } catch (BlueException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new BlueException("INVALID_YAML", e.getMessage());
    }
  }

  Node readJson(String text) {
    try {
      return node(JSON.readValue(text, Object.class), 0);
    } catch (JsonProcessingException e) {
      throw new BlueException("INVALID_JSON", e.getOriginalMessage());
    }
  }

  String writeJson(Node node) {
    return json(raw(node));
  }

  String writeYaml(Node node) {
    var options = new DumperOptions();
    options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    options.setIndent(2);
    options.setSplitLines(false);
    return new Yaml(options).dump(raw(node));
  }

  static String json(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new BlueException("INVALID_JSON", e.getOriginalMessage());
    }
  }

  static Node node(Object value, int depth) {
    if (depth > 128) {
      throw new BlueException("DEPTH_LIMIT", "Content exceeds 128 levels");
    }
    if (value == null) {
      return Node.nil();
    }
    if (value instanceof String text) {
      return Node.text(text);
    }
    if (value instanceof Boolean flag) {
      return Node.bool(flag);
    }
    if (value instanceof BigInteger number) {
      return Node.integer(number);
    }
    if (value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long) {
      return Node.integer(((Number) value).longValue());
    }
    if (value instanceof List<?> items) {
      var children = new ArrayList<Node>();
      for (Object item : items) {
        children.add(node(item, depth + 1));
      }
      return Node.list(children);
    }
    if (value instanceof Map<?, ?> fields) {
      var children = new LinkedHashMap<String, Node>();
      for (var entry : fields.entrySet()) {
        if (!(entry.getKey() instanceof String key)) {
          throw new BlueException("INVALID_KEY", "Object keys must be text");
        }
        children.put(key, node(entry.getValue(), depth + 1));
      }
      return Node.object(children);
    }
    throw new BlueException(
        "UNSUPPORTED_SCALAR", "This edition supports text, integers, booleans and null");
  }

  static Object raw(Node node) {
    return switch (node.kind()) {
      case TEXT -> node.text();
      case INTEGER -> node.bigInteger();
      case DOUBLE -> node.number();
      case BOOLEAN -> node.bool();
      case NULL -> null;
      case MISSING -> throw new BlueException("MISSING_VALUE", "Missing is not serializable");
      case LIST -> node.items().stream().map(NodeCodec::raw).toList();
      case OBJECT -> {
        var fields = new LinkedHashMap<String, Object>();
        node.fields().forEach((key, child) -> fields.put(key, raw(child)));
        yield fields;
      }
    };
  }
}
