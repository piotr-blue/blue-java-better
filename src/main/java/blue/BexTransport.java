package blue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Explicit JSON transport tags. Single-key tag shapes are reserved in this transport. */
final class BexTransport {
  private BexTransport() {}

  public static Object encode(Object value) {
    if (value == BexValues.UNDEFINED) return Map.of("$undefined", true);
    if (value instanceof BigInteger i
        && i.abs().compareTo(BigInteger.valueOf(9007199254740991L)) > 0)
      return Map.of("$integer", i.toString());
    if (value instanceof BigDecimal d) return Map.of("$decimal", d.toString());
    if (value instanceof Double d) return Map.of("$double", d.toString());
    if (value instanceof Map<?, ?> m) {
      var out = new TreeMap<String, Object>();
      m.forEach((k, v) -> out.put((String) k, encode(v)));
      return out;
    }
    if (value instanceof List<?> a) return a.stream().map(BexTransport::encode).toList();
    return value;
  }

  public static Object decode(Object value) {
    if (value instanceof Map<?, ?> m) {
      if (m.keySet().equals(Set.of("$integer"))) return new BigInteger((String) m.get("$integer"));
      if (m.keySet().equals(Set.of("$undefined")) && Boolean.TRUE.equals(m.get("$undefined")))
        return BexValues.UNDEFINED;
      if (m.keySet().equals(Set.of("$decimal"))) return new BigDecimal((String) m.get("$decimal"));
      if (m.keySet().equals(Set.of("$double"))) return Double.valueOf((String) m.get("$double"));
      var out = new TreeMap<String, Object>();
      m.forEach((k, v) -> out.put((String) k, decode(v)));
      return out;
    }
    if (value instanceof List<?> a) return a.stream().map(BexTransport::decode).toList();
    return value;
  }
}
