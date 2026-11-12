package blue;

import static blue.ContractModel.arr;
import static blue.ContractModel.integer;
import static blue.ContractModel.m;
import static blue.ContractModel.obj;
import static blue.ContractModel.str;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("canonical-bex")
@Tag("conformance")
class CanonicalBexConformanceTest {
  record Case(Map<String, Object> row) {
    @Override
    public String toString() {
      return str(row.get("id"));
    }
  }

  static Stream<Case> cases() throws Exception {
    try (var input = CanonicalBexConformanceTest.class.getResourceAsStream("/canonical/bex.json")) {
      return arr(
              obj(ContractWire.json(
                      new String(
                          Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8)))
                  .get("vectors"))
          .stream()
          .map(value -> new Case(obj(value)));
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void matchesPublishedValueEffectsGasAndRejection(Case test) {
    Map<String, Object> request = obj(decode(test.row.get("request")));
    Map<String, Object> data = obj(request.get("context"));
    BexEngine.Context context = new BexEngine.Context();
    context.document = data.get("document");
    context.event = data.get("event");
    context.currentContract = data.get("currentContract");
    context.resolvedDocument = data.getOrDefault("resolvedDocument", BexValues.UNDEFINED);
    context.steps = obj(data.getOrDefault("steps", Map.of()));
    context.bindings = obj(data.getOrDefault("bindings", Map.of()));
    context.scope = str(data.getOrDefault("scope", "/"));
    ContractBridge bridge = new ContractBridge(Language.create());
    context.matcher =
        request.containsKey("matcher") ? (a, b) -> true : (a, b) -> bridge.matches(a, b, null);
    Map<String, BexEngine.Intrinsic> intrinsics =
        request.containsKey("intrinsics")
            ? Map.of(
                "exact-type",
                (payload, gas) -> {
                  gas.amount("host", 7);
                  return BexValues.integer(payload.get("x")).add(java.math.BigInteger.ONE);
                })
            : Map.of();
    BexEngine engine = new BexEngine(intrinsics);
    BexGas gas = new BexGas(integer(request.getOrDefault("gasLimit", 10_000_000L)));
    Map<String, Object> actual;
    try {
      BexPlanCompiler.Program plan =
          engine.compile(
              obj(request.get("program")),
              request.get("definition") == null ? null : obj(request.get("definition")),
              (String) request.get("entry"));
      BexEngine.Result result = engine.execute(plan, context, gas);
      actual =
          m(
              "outcome",
              "Established",
              "value",
              result.value(),
              "changeset",
              result.changeset(),
              "events",
              result.events(),
              "gasUsed",
              result.gasUsed());
    } catch (BexFailure failure) {
      actual =
          m(
              "outcome",
              "Invalid",
              "code",
              failure.code(),
              "gasUsed",
              gas.used(),
              "rejected",
              gas.rejected());
    }
    assertEquals(
        ContractWire.write(BexTransport.encode(decode(test.row.get("expected")))),
        ContractWire.write(BexTransport.encode(actual)));
  }

  private static Object decode(Object value) {
    if (value instanceof List<?> items)
      return items.stream().map(CanonicalBexConformanceTest::decode).toList();
    if (value instanceof Map<?, ?> map) {
      if (map.keySet().equals(Set.of("$undefined"))) return BexValues.UNDEFINED;
      if (map.keySet().equals(Set.of("$decimal")))
        return new java.math.BigDecimal((String) map.get("$decimal"));
      Map<String, Object> decoded = new TreeMap<>();
      map.forEach((key, child) -> decoded.put((String) key, decode(child)));
      return decoded;
    }
    return value;
  }
}
