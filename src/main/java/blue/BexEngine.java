package blue;

import static blue.BexFailure.require;
import static blue.BexValues.UNDEFINED;
import static blue.BexValues.blueInput;
import static blue.BexValues.blueValue;
import static blue.BexValues.chunks;
import static blue.BexValues.copy;
import static blue.BexValues.equal;
import static blue.BexValues.get;
import static blue.BexValues.integer;
import static blue.BexValues.keys;
import static blue.BexValues.kind;
import static blue.BexValues.limbs;
import static blue.BexValues.number;
import static blue.BexValues.ordered;
import static blue.BexValues.pointerGet;
import static blue.BexValues.requiredText;
import static blue.BexValues.segments;
import static blue.BexValues.size;
import static blue.BexValues.sizeParts;
import static blue.BexValues.text;
import static blue.BexValues.truthy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

/** Native BEX evaluator. Effects remain data until the Contracts boundary applies them. */
final class BexEngine {
  /**
   * Controls only the result's trace snapshot. The supplied meter decides whether to retain charge
   * rows.
   */
  public enum TraceMode {
    CAPTURE,
    METER_ONLY
  }

  @FunctionalInterface
  public interface DocumentReader {
    Object read(String path, String view, Object document);
  }

  @FunctionalInterface
  public interface Identity {
    String identify(Object value, BexGas gas);
  }

  @FunctionalInterface
  public interface Intrinsic {
    Object execute(Map<String, Object> payload, BexGas gas);
  }

  public static final class Context {
    public Object document = Map.of(),
        event = Map.of(),
        currentContract = Map.of(),
        resolvedDocument = UNDEFINED;
    public Map<String, Object> steps = Map.of(), bindings = Map.of();
    public String scope = "/";
    public DocumentReader readDocument;
    public BiPredicate<Object, Object> matcher;
    public Identity nodeBlueId;
    InputObserver inputObserver;
  }

  @FunctionalInterface
  interface InputObserver {
    Object read(InputRead read, Supplier<Object> action);

    default void opaque() {}

    default Object original(Object value) {
      return value;
    }
  }

  record InputRead(String source, String key, String path, String view) {
    Object read(Context context) {
      return switch (source) {
        case "$document" -> {
          Object document = view.equals("resolved") ? context.resolvedDocument : context.document;
          yield context.readDocument == null
              ? pointerGet(document, path)
              : context.readDocument.read(path, view, document);
        }
        case "$event" -> pointerGet(context.event, path);
        case "$currentContract" -> pointerGet(context.currentContract, path);
        case "$binding" -> pointerGet(context.bindings.getOrDefault(key, UNDEFINED), path);
        case "$steps" -> pointerGet(context.steps.getOrDefault(key, UNDEFINED), path);
        default -> throw new IllegalArgumentException("Unknown calculation input");
      };
    }

    Object observed(Context context) {
      return context.inputObserver == null
          ? read(context)
          : context.inputObserver.read(this, () -> read(context));
    }
  }

  public record Result(
      Object value,
      List<Object> changeset,
      List<Object> events,
      long gasUsed,
      List<Map<String, Object>> gasTrace) {
    @SuppressWarnings("unchecked")
    public Result {
      value = BexValues.freeze(value);
      changeset = (List<Object>) BexValues.freeze(changeset);
      events = (List<Object>) BexValues.freeze(events);
      gasTrace = (List<Map<String, Object>>) (List<?>) BexValues.freeze(gasTrace);
    }

    public Object blueOutput() {
      return blueValue(value);
    }
  }

  private final Map<String, Intrinsic> intrinsics;
  private final boolean bounded;

  public BexEngine() {
    this(Map.of());
  }

  public BexEngine(Map<String, Intrinsic> intrinsics) {
    this(intrinsics, false);
  }

  BexEngine(Map<String, Intrinsic> intrinsics, boolean bounded) {
    this.intrinsics = Map.copyOf(intrinsics);
    this.bounded = bounded;
  }

  public BexPlanCompiler.Program compile(Map<String, Object> source) {
    return new BexPlanCompiler(intrinsics.keySet(), bounded).compile(source);
  }

  public BexPlanCompiler.Program compile(
      Map<String, Object> source, Map<String, Object> definition, String entry) {
    return new BexPlanCompiler(intrinsics.keySet(), bounded).compile(source, definition, entry);
  }

  public Result execute(Map<String, Object> source, Context context, long limit) {
    return execute(source, context, new BexGas(limit));
  }

  public Result execute(Map<String, Object> source, Context context, BexGas gas) {
    return execute(
        new BexPlanCompiler(intrinsics.keySet(), bounded || gas.boundedHost()).compile(source),
        context,
        gas);
  }

  public Result execute(
      Map<String, Object> source, Context context, BexGas gas, TraceMode traceMode) {
    return execute(
        new BexPlanCompiler(intrinsics.keySet(), bounded || gas.boundedHost()).compile(source),
        context,
        gas,
        traceMode);
  }

  public Result execute(BexPlanCompiler.Program program, Context context, BexGas gas) {
    return execute(program, context, gas, TraceMode.CAPTURE);
  }

  public Result execute(
      BexPlanCompiler.Program program, Context context, BexGas gas, TraceMode traceMode) {
    require(
        program.intrinsicIds().equals(intrinsics.keySet()),
        "Compiled intrinsic capabilities differ");
    return new Invocation(program, context, gas, intrinsics, Objects.requireNonNull(traceMode))
        .execute();
  }

  static final class Invocation {
    final Map<String, Object> program, constants, functions;
    final String entry;
    final Context context;
    final BexGas gas;
    final Map<String, Intrinsic> intrinsics;
    final List<Object> changes = new ArrayList<>(), events = new ArrayList<>();
    final Deque<Map<String, Object>> frames = new ArrayDeque<>();
    final long startGas;
    final int startTrace;
    final TraceMode traceMode;

    Invocation(
        BexPlanCompiler.Program program,
        Context context,
        BexGas gas,
        Map<String, Intrinsic> intrinsics,
        TraceMode traceMode) {
      // The compiler owns this deeply immutable tree; only frames and effects are per invocation.
      this.program = program.source();
      entry = program.selectedEntry();
      this.context = context == null ? new Context() : context;
      this.gas = gas;
      this.intrinsics = intrinsics;
      this.traceMode = traceMode;
      constants = map(this.program.getOrDefault("constants", Map.of()));
      functions = map(this.program.getOrDefault("functions", Map.of()));
      frames.push(new TreeMap<>());
      startGas = gas.used();
      startTrace = traceMode == TraceMode.CAPTURE ? gas.traceSize() : 0;
    }

    @SuppressWarnings("unchecked") // BEX objects are decoded with text keys.
    static Map<String, Object> map(Object value) {
      require(value instanceof Map, "object required");
      return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object value) {
      require(value instanceof List, "list required");
      return (List<Object>) value;
    }

    Map<String, Object> frame() {
      return frames.peek();
    }

    BigInteger toInteger(Object value) {
      if (value instanceof BigInteger b) return b;
      long digits;
      if (value instanceof String s) {
        gas.charge("textWork", chunks(s));
        digits = s.startsWith("-") ? s.length() - 1 : s.length();
      } else {
        BigDecimal n = number(value);
        digits = Math.max(n.precision(), n.precision() - n.scale());
      }
      long limbs = Math.max(1, (4L * digits + 31) / 32);
      gas.charge("integerWork", limbs * limbs);
      return integer(value);
    }

    String toText(Object value) {
      if (value instanceof BigInteger n) {
        long limbs = limbs(n);
        gas.charge("integerWork", limbs * limbs);
      }
      return text(value);
    }

    void sized(String counter, Object value, long extra) {
      gas.amount(counter, gas.tariff(counter) + extra);
      sizeParts(value, gas, q -> gas.amount(counter, q));
    }

    Object defaults() {
      gas.charge("valueMember", changes.size() + events.size());
      var out = new TreeMap<String, Object>();
      out.put("changeset", new ArrayList<>(changes));
      out.put("events", new ArrayList<>(events));
      return out;
    }

    Result execute() {
      gas.charge("functionCall");
      Object value = new BexMachine(this).execute();
      return new Result(
          value,
          changes,
          events,
          gas.used() - startGas,
          traceMode == TraceMode.CAPTURE ? gas.traceFrom(startTrace) : List.of());
    }

    boolean matches(Object value, Object pattern) {
      if (value == UNDEFINED) return false;
      Object converted;
      try {
        converted = blueInput(value);
      } catch (BexFailure e) {
        if (e.code().equals("BEX_OUTPUT_INVALID")) return false;
        throw e;
      }
      if (context.inputObserver != null) context.inputObserver.opaque();
      require(context.matcher != null, "Language matching capability unavailable");
      return context.matcher.test(original(converted), original(copy(pattern)));
    }

    Object original(Object value) {
      return context.inputObserver == null ? value : context.inputObserver.original(value);
    }

    Object unary(String op, Object x) {
      if (op.equals("$nodeBlueId")) {
        if (context.inputObserver != null) context.inputObserver.opaque();
        gas.charge("nodeIdentityRequested");
        if (context.nodeBlueId == null)
          throw new BexFailure("BEX_OUTPUT_INVALID", "Language identity boundary unavailable");
        String id = context.nodeBlueId.identify(original(copy(x)), gas);
        if (id == null || id.isEmpty())
          throw new BexFailure(
              "BEX_OUTPUT_INVALID", "Language identity boundary returned invalid identity");
        return id;
      }
      if (op.equals("$unwrap")) {
        while (true) {
          gas.charge("valueMember");
          if (!kind(x).equals("object")) return x;
          Object child = get(x, "value");
          if (child == UNDEFINED) return x;
          x = child;
        }
      }
      String k = kind(x);
      switch (op) {
        case "$kind":
          return k;
        case "$text":
          return toText(x);
        case "$integer":
          return toInteger(x);
        case "$number":
          if (x instanceof BigInteger n) {
            long limbs = limbs(n);
            gas.charge("integerWork", limbs * limbs);
          } else if (x instanceof String s) gas.charge("textWork", chunks(s));
          return number(x);
        case "$boolean":
          if ("true".equals(x)) return true;
          if ("false".equals(x)) return false;
          return truthy(x);
        case "$object", "$list":
          {
            String expected = op.equals("$object") ? "object" : "list";
            if (k.equals("null") || k.equals("undefined"))
              return expected.equals("object")
                  ? new TreeMap<String, Object>()
                  : new ArrayList<Object>();
            require(k.equals(expected), "wrong collection kind");
            return x;
          }
        case "$not", "$empty", "$isEmpty":
          return !truthy(x);
        case "$truthy":
          return truthy(x);
        case "$exists":
          return x != UNDEFINED;
        case "$keys":
          if (!k.equals("object")) return List.of();
          gas.charge("valueMember", size(x));
          return keys(x);
        case "$entries":
          {
            if (!k.equals("object")) return List.of();
            gas.charge("valueMember", size(x));
            var out = new ArrayList<Object>();
            for (String key : keys(x)) {
              var row = new TreeMap<String, Object>();
              row.put("key", key);
              row.put("val", get(x, key));
              out.add(row);
            }
            return out;
          }
        case "$size":
          return BigInteger.valueOf(
              k.equals("object") || k.equals("list")
                  ? size(x)
                  : k.equals("null") || k.equals("undefined") ? 0 : 1);
        case "$objectFromEntries":
          {
            require(k.equals("list"), "entries must be a list");
            var out = new TreeMap<String, Object>();
            for (int i = 0; i < size(x); i++) {
              Object row = get(x, i);
              gas.charge("forEachItem");
              require(kind(row).equals("object"), "entry must be object");
              String key = requiredText(get(row, "key"));
              Object v = get(row, "val");
              if (v == UNDEFINED) out.remove(key);
              else out.put(key, v);
            }
            return out;
          }
      }
      throw new BexFailure("Unknown unary operator " + op);
    }

    Object array(String op, List<Object> args) {
      if (op.equals("$concat") || op.equals("$pointerJoin")) {
        var strings = new ArrayList<String>();
        long len = 0;
        for (Object a : args) {
          String s =
              op.equals("$pointerJoin") && (a == null || a == UNDEFINED)
                  ? requiredText(a)
                  : toText(a);
          strings.add(s);
          len += size(s);
        }
        gas.charge("textWork", (len + (op.equals("$pointerJoin") ? strings.size() : 0) + 63) / 64);
        return op.equals("$concat")
            ? String.join("", strings)
            : "/" + String.join("/", strings.stream().map(BexValues::escape).toList());
      }
      if (op.equals("$startsWith") || op.equals("$sliceAfter")) {
        String s = toText(args.get(0)), p = toText(args.get(1));
        gas.charge("textWork", (size(s) + size(p) + 63L) / 64);
        return op.equals("$startsWith")
            ? s.startsWith(p)
            : s.startsWith(p) ? s.substring(p.length()) : "";
      }
      if (op.equals("$eq") || op.equals("$ne"))
        return equal(args.get(0), args.get(1), gas) == op.equals("$eq");
      if (Set.of("$gt", "$gte", "$lt", "$lte").contains(op)) {
        for (Object v : args)
          if (v instanceof BigInteger n) {
            long limbs = limbs(n);
            gas.charge("integerWork", limbs * limbs);
          } else if (v instanceof String s) gas.charge("textWork", chunks(s));
        int cmp = number(args.get(0)).compareTo(number(args.get(1)));
        return switch (op) {
          case "$gt" -> cmp > 0;
          case "$gte" -> cmp >= 0;
          case "$lt" -> cmp < 0;
          default -> cmp <= 0;
        };
      }
      if (Set.of("$add", "$subtract", "$multiply", "$divide").contains(op)) {
        var ints = args.stream().map(this::toInteger).toList();
        BigInteger r = ints.getFirst();
        for (int i = 1; i < ints.size(); i++) {
          BigInteger n = ints.get(i);
          long a = limbs(r), z = limbs(n);
          gas.charge(
              "integerWork",
              op.equals("$add") || op.equals("$subtract")
                  ? Math.max(a, z)
                  : a * z * (op.equals("$divide") ? 2 : 1));
          r =
              switch (op) {
                case "$add" -> r.add(n);
                case "$subtract" -> r.subtract(n);
                case "$multiply" -> r.multiply(n);
                default -> {
                  require(
                      n.signum() != 0 && r.remainder(n).signum() == 0,
                      "division requires nonzero divisor and exact integer result");
                  yield r.divide(n);
                }
              };
        }
        return r;
      }
      if (op.equals("$listConcat")) {
        for (Object x : args) require(kind(x).equals("list"), "listConcat requires lists");
        var out = new ArrayList<Object>();
        for (Object x : args) {
          for (int i = 0; i < size(x); i++) {
            // The published loop obtains the next source item before charging its append.
            Object item = get(x, i);
            gas.charge("valueMember");
            out.add(item);
          }
        }
        return out;
      }
      if (op.equals("$merge")) {
        var out = new TreeMap<String, Object>();
        for (Object x : args) {
          require(kind(x).equals("object"), "merge requires objects");
          for (String key : ordered(x, gas)) out.put(key, get(x, key));
        }
        return out;
      }
      throw new BexFailure("Unknown array operator " + op);
    }

    record Row(Object key, Object value, BigInteger index) {}

    Iterable<Row> collection(Object value) {
      String kind = kind(value);
      require(
          kind.equals("list") || kind.equals("object"),
          "collection operation needs object or list");
      var orderedKeys = kind.equals("object") ? keys(value) : List.<String>of();
      int count = kind.equals("list") ? size(value) : orderedKeys.size();
      return () ->
          new Iterator<Row>() {
            int index;

            public boolean hasNext() {
              return index < count;
            }

            public Row next() {
              if (!hasNext()) throw new NoSuchElementException();
              int i = index++;
              Object key = kind.equals("list") ? UNDEFINED : orderedKeys.get(i);
              return new Row(key, get(value, kind.equals("list") ? i : key), BigInteger.valueOf(i));
            }
          };
    }

    void appendChange(Object value) {
      require(kind(value).equals("object"), "malformed patch");
      var keys = new HashSet<>(keys(value));
      require(
          Set.of("op", "path", "val").containsAll(keys) && keys.containsAll(Set.of("op", "path")),
          "malformed patch");
      String operation = requiredText(get(value, "op"));
      require(Set.of("add", "replace", "remove").contains(operation), "unsupported patch op");
      String path = requiredText(get(value, "path"));
      if (!path.startsWith("/")) path = context.scope.replaceAll("/$", "") + "/" + path;
      if (path.isEmpty() || path.equals("/")) path = context.scope;
      segments(path);
      Object val = operation.equals("remove") ? UNDEFINED : get(value, "val");
      require(operation.equals("remove") || val != UNDEFINED, "patch val is required");
      sized("appendChangeBase", val, 0);
      var change = new TreeMap<String, Object>();
      change.put("op", operation);
      change.put("path", path);
      if (!operation.equals("remove")) change.put("val", val);
      changes.add(change);
    }

    void appendEvent(Object value) {
      require(value != UNDEFINED, "undefined event");
      sized("appendEventBase", value, 0);
      events.add(value);
    }
  }
}
