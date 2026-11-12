package blue;

import static blue.ContractModel.*;

import java.math.*;
import java.util.*;
import java.util.function.Supplier;

/**
 * A bounded, private replay of pure calculations. Scalar blocks replace arithmetic and control
 * flow; selected input reads run again against the receiver's logical ledger. No accepted-charge
 * trace, document session, external admission decision or native state transition is retained here.
 */
final class ContractCalculations {
  // Change this namespace whenever implementation details affecting replay change.
  private static final String VERSION = "blue-java-better/calculation-3";
  private static final int MAX_READS = 256, MAX_VALUES = 4096, MAX_BYTES = 64 * 1024;
  private static final int MAX_ATTEMPT_BYTES = 1024 * 1024;
  private final Contracts.Execution.CalculationReader reader;
  private final Map<String, Map<String, Object>> completed = new LinkedHashMap<>();
  private int bytes;
  long reused, executed, budgetFallback;

  ContractCalculations(Contracts.Execution.CalculationReader reader) {
    this.reader = reader;
  }

  Map<String, Map<String, Object>> completed() {
    return Map.copyOf(completed);
  }

  BexEngine.Result execute(
      ContractRun run,
      BexEngine.Context context,
      Object binding,
      Supplier<BexEngine.Result> evaluate) {
    Meter meter = run.meter;
    if (!meter.finalGas() || meter.calculation != null) return evaluate.get();
    String key;
    try {
      key =
          digest(
              VERSION,
              List.of(run.engine.bindings.identities(), binding, encode(context.steps, true)));
    } catch (NotReusable ignored) {
      return evaluate.get();
    }
    Map<String, Object> retained = completed.get(key);
    if (retained == null) retained = reader.find(key).orElse(null);
    if (retained != null
        && VERSION.equals(retained.get("version"))
        && key.equals(retained.get("key"))) {
      long bound = integer(retained.get("bound"));
      if (meter.canAdmit(bound)) {
        reused++;
        ContractMetrics.count("calculation.reused");
        long start = meter.used();
        List<Object> inputs = new ArrayList<>();
        for (Object raw : arr(retained.get("reads"))) {
          Map<String, Object> row = obj(raw);
          meter.mergeAdmitted(integer(row.get("gas")));
          var read =
              new BexEngine.InputRead(
                  str(row.get("source")),
                  str(row.get("key")),
                  str(row.get("path")),
                  str(row.get("view")));
          Object actual =
              read.source().equals("$view")
                  ? viewRead((BexValues.HostValue) inputs.get(Integer.parseInt(read.key())), read)
                  : read.read(context);
          inputs.add(actual);
          // Input identity binds the result; this check also detects corrupt protected storage.
          require(encode(actual, true).equals(row.get("value")), "INVALID_LOCAL_CALCULATION");
        }
        meter.mergeAdmitted(integer(retained.get("tail")));
        return new BexEngine.Result(
            decode(retained.get("value"), inputs),
            arr(decode(retained.get("changes"), inputs)),
            arr(decode(retained.get("events"), inputs)),
            meter.used() - start,
            List.of());
      }
      budgetFallback++;
      ContractMetrics.count("calculation.budgetFallback");
    }
    Recording recording = new Recording(meter);
    Recording previous = meter.calculation;
    BexEngine.InputObserver previousObserver = context.inputObserver;
    meter.calculation = recording;
    context.inputObserver = recording;
    BexEngine.Result result;
    try {
      executed++;
      ContractMetrics.count("calculation.executed");
      result = evaluate.get();
    } finally {
      meter.calculation = previous;
      context.inputObserver = previousObserver;
    }
    try {
      if (recording.invalid || bytes >= MAX_ATTEMPT_BYTES)
        return recording.wrapped == 0 ? result : unwrap(result);
      long tail = meter.used() - recording.position;
      Map<String, Object> artifact =
          m(
              "version",
              VERSION,
              "key",
              key,
              "bound",
              Math.addExact(recording.bound, tail),
              "reads",
              recording.reads,
              "tail",
              tail,
              "value",
              encode(result.value()),
              "changes",
              encode(result.changeset()),
              "events",
              encode(result.events()));
      long weight = ContractCache.weight(artifact, MAX_BYTES);
      if (weight <= MAX_BYTES)
        weight =
            ContractWire.write(artifact).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
      if (weight <= MAX_BYTES && bytes + weight <= MAX_ATTEMPT_BYTES) {
        completed.put(key, obj(ContractWire.immutable(artifact)));
        bytes += Math.toIntExact(weight);
        ContractMetrics.count("calculation.retained");
      }
    } catch (NotReusable ignored) {
      // Lazy views and large values remain ordinary computations, with no new semantic restriction.
    }
    return recording.wrapped == 0 ? result : unwrap(result);
  }

  private static Object viewRead(BexValues.HostValue value, BexEngine.InputRead read) {
    return switch (read.view()) {
      case "size" -> value.size();
      case "keys" -> value.keys();
      case "get" ->
          value.get(
              read.path().startsWith("i:")
                  ? Integer.valueOf(read.path().substring(2))
                  : read.path().substring(2));
      default -> throw new IllegalArgumentException("Invalid calculation view read");
    };
  }

  private static BexEngine.Result unwrap(BexEngine.Result result) {
    return new BexEngine.Result(
        unwrap(result.value()),
        arr(unwrap(result.changeset())),
        arr(unwrap(result.events())),
        result.gasUsed(),
        result.gasTrace());
  }

  private static Object unwrap(Object value) {
    return BexValues.copy(value, host -> host instanceof ReadView view ? view.value : host);
  }

  /** Lazy values keep their exact native handles; every selected read is replayed in order. */
  private static final class ReadView implements BexValues.HostValue {
    private final Recording recording;
    private final BexValues.HostValue value;
    private final int input;

    ReadView(Recording recording, BexValues.HostValue value, int input) {
      this.recording = recording;
      this.value = value;
      this.input = input;
    }

    private Object select(String operation, String path) {
      var read = new BexEngine.InputRead("$view", Integer.toString(input), path, operation);
      return recording.read(read, () -> viewRead(value, read));
    }

    public String kind() {
      return value.kind();
    }

    public int size() {
      return ((Number) select("size", "")).intValue();
    }

    @SuppressWarnings("unchecked")
    public List<String> keys() {
      return (List<String>) select("keys", "");
    }

    public Object get(Object key) {
      return select("get", (key instanceof Integer ? "i:" : "s:") + key);
    }

    public Object blueOutput() {
      recording.opaque();
      return value.blueOutput();
    }

    public Object accountingView() {
      recording.opaque();
      return value.accountingView();
    }
  }

  static final class Recording implements BexEngine.InputObserver {
    private final Meter meter;
    private final List<Object> reads = new ArrayList<>();
    private final Set<String> manifests = new HashSet<>();
    private long position, bound;
    private boolean reading, invalid;
    private int wrapped;

    Recording(Meter meter) {
      this.meter = meter;
      position = meter.used();
    }

    private void invalidate() {
      invalid = true;
    }

    @Override
    public Object original(Object value) {
      return wrapped == 0 ? value : unwrap(value);
    }

    @Override
    public void opaque() {
      invalidate();
      ContractMetrics.count("calculation.opaque");
    }

    void charge(String namespace) {
      if (!reading && !namespace.equals("bex")) invalidate();
    }

    void manifest(String identity) {
      if (reading) manifests.add(identity);
      else invalidate();
    }

    @Override
    public Object read(BexEngine.InputRead read, Supplier<Object> action) {
      if (invalid) return action.get();
      if (reads.size() >= MAX_READS) {
        invalidate();
        return action.get();
      }
      long fixed = meter.used() - position;
      long before = meter.used();
      manifests.clear();
      Object result;
      reading = true;
      try {
        result = action.get();
      } finally {
        reading = false;
      }
      position = meter.used();
      try {
        reads.add(
            m(
                "source",
                read.source(),
                "key",
                read.key(),
                "path",
                read.path(),
                "view",
                read.view(),
                "gas",
                fixed,
                "value",
                encode(result, true)));
        // Only once-per-invocation manifest opens can vary for these plain, read-only inputs.
        // Count all visited manifests, including already open ones, for a conservative bound.
        bound =
            Math.addExact(
                bound,
                fixed
                    + position
                    - before
                    + manifests.size() * ((Number) TARIFF.get("nodeManifestOpened")).longValue());
      } catch (NotReusable ignored) {
        invalidate();
      }
      if (!invalid && result instanceof ContractBridge.ExactView view) {
        wrapped++;
        return new ReadView(this, view, reads.size() - 1);
      }
      return result;
    }
  }

  private static final class NotReusable extends RuntimeException {
    private static final long serialVersionUID = 1L;

    NotReusable() {
      super(null, null, false, false);
    }
  }

  /**
   * Tagged private values preserve integer/double and undefined distinctions across JSON storage.
   */
  private static Object encode(Object value) {
    return encode(value, false);
  }

  private static Object encode(Object value, boolean input) {
    return encode(value, new int[] {MAX_VALUES, MAX_BYTES}, 0, input);
  }

  private static Object encode(Object value, int[] remaining, int depth, boolean input) {
    if (value instanceof ReadView view)
      return input
          ? encode(view.value, remaining, depth, true)
          : List.of("input", Integer.toString(view.input));
    if (input && value instanceof ContractBridge.ExactView view) {
      if (view.recipe.parts().size() > MAX_VALUES) throw new NotReusable();
      return List.of(
          "exact", view.recipe.parts().stream().map(ContractLanguage::identity).toList());
    }
    if (--remaining[0] < 0 || depth > 64 || value instanceof BexValues.HostValue)
      throw new NotReusable();
    if (value == BexValues.UNDEFINED) return List.of("undefined");
    if (value == null) return List.of("null");
    if (value instanceof String text) {
      remaining[1] -= Math.min(MAX_BYTES + 1, text.length());
      if (remaining[1] < 0) throw new NotReusable();
      return List.of("literal", text);
    }
    if (value instanceof Boolean) return List.of("literal", value);
    if (value instanceof BigInteger number && number.bitLength() > MAX_BYTES)
      throw new NotReusable();
    if (value instanceof BigInteger || value instanceof Long || value instanceof Integer)
      return List.of("integer", value.toString());
    if (value instanceof BigDecimal || value instanceof Double || value instanceof Float)
      return List.of("double", value.toString());
    if (value instanceof List<?> values) {
      var encoded = new ArrayList<>();
      for (Object item : values) encoded.add(encode(item, remaining, depth + 1, input));
      return List.of("list", encoded);
    }
    if (value instanceof Map<?, ?> values) {
      var encoded = new TreeMap<String, Object>();
      for (var item : values.entrySet()) {
        String key = (String) item.getKey();
        remaining[1] -= Math.min(MAX_BYTES + 1, key.length());
        if (remaining[1] < 0) throw new NotReusable();
        encoded.put(key, encode(item.getValue(), remaining, depth + 1, input));
      }
      return List.of("object", encoded);
    }
    throw new NotReusable();
  }

  private static Object decode(Object encoded, List<Object> inputs) {
    var value = arr(encoded);
    return switch (str(value.getFirst())) {
      case "input" -> inputs.get(Integer.parseInt(str(value.get(1))));
      case "undefined" -> BexValues.UNDEFINED;
      case "null" -> null;
      case "literal" -> value.get(1);
      case "integer" -> new BigInteger(str(value.get(1)));
      case "double" -> new BigDecimal(str(value.get(1)));
      case "list" -> arr(value.get(1)).stream().map(item -> decode(item, inputs)).toList();
      case "object" -> {
        var decoded = new TreeMap<String, Object>();
        obj(value.get(1)).forEach((key, item) -> decoded.put(key, decode(item, inputs)));
        yield decoded;
      }
      default -> throw new IllegalArgumentException("Invalid local calculation value");
    };
  }
}
