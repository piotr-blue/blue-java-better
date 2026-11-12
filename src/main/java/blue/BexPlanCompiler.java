package blue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Data-only compile-time validation. Compilation consumes no semantic gas. */
final class BexPlanCompiler {
  static final Set<String> RESERVED =
      words(
          "name description type itemType keyType valueType value items blueId blue schema"
              + " constraints mergePolicy properties contracts $previous $previousItems $pos"
              + " $replace $empty");
  static final Set<String> STATIC = words("type itemType keyType valueType blue schema"),
      UNARY =
          words(
              "$nodeBlueId $unwrap $kind $text $integer $number $boolean $object $list $not $truthy"
                  + " $empty $isEmpty $exists $keys $entries $size $objectFromEntries"),
      ARRAY =
          words(
              "$concat $pointerJoin $startsWith $sliceAfter $eq $ne $gt $gte $lt $lte $and $or"
                  + " $coalesce $default $add $subtract $multiply $divide $listConcat $merge"),
      CONSTANT = words("$null $emptyObject $emptyList $changeset $events"),
      READS = words("$document $event $currentContract $resultValue $binding $steps $var $const"),
      QUERY = words("$map $filter $flatMap $reduce $some $find $findEntry"),
      STMT =
          words(
              "$let $set $if $forEach $appendChange $appendChanges $appendEvent $appendEvents $call"
                  + " $return $returnIf $fail $failIf"),
      KINDS = words("undefined null text integer double boolean object list");
  static final Set<String> EXPR = new HashSet<>();

  static {
    for (var set :
        List.of(
            UNARY,
            ARRAY,
            CONSTANT,
            READS,
            QUERY,
            words(
                "$literal $is $isKind $join $split $listGet $get $objectSet $pointerGet $pointerSet"
                    + " $includes $hasKey $choose $call $intrinsic"))) {
      EXPR.addAll(set);
    }
  }

  static Set<String> words(String s) {
    return Set.of(s.split(" "));
  }

  /** Validated immutable program. Only the compiler can construct one. */
  public static final class Program {
    private final Map<String, Object> source;
    private final String selectedEntry;
    private final List<String> operatorInventory;
    private final Set<String> intrinsicIds;

    private Program(
        Map<String, Object> source, String entry, List<String> inventory, Set<String> ids) {
      // compile owns the root and freezes every child before assembling its overlays.
      this.source = Collections.unmodifiableMap(source);
      selectedEntry = entry;
      operatorInventory = List.copyOf(inventory);
      intrinsicIds = Set.copyOf(ids);
    }

    public Map<String, Object> source() {
      return source;
    }

    public String selectedEntry() {
      return selectedEntry;
    }

    public List<String> operatorInventory() {
      return operatorInventory;
    }

    Set<String> intrinsicIds() {
      return intrinsicIds;
    }
  }

  private final Set<String> intrinsicIds;
  private final boolean bounded;
  private Map<String, Object> constants, functions;
  private final Map<String, Set<String>> calls = new TreeMap<>();
  private final Set<String> inventory = new TreeSet<>();

  public BexPlanCompiler() {
    this(Set.of());
  }

  public BexPlanCompiler(Set<String> ids) {
    this(ids, false);
  }

  BexPlanCompiler(Set<String> ids, boolean bounded) {
    intrinsicIds = Set.copyOf(ids);
    this.bounded = bounded;
  }

  static void check(boolean test, String message) {
    if (!test) {
      throw new BexFailure("BEX_COMPILE_ERROR", message);
    }
  }

  @SuppressWarnings("unchecked") // BEX objects are decoded with text keys.
  static Map<String, Object> map(Object value) {
    check(value instanceof Map, "operator body must be an object");
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  static List<Object> list(Object value) {
    check(value instanceof List, "operand must be list");
    return (List<Object>) value;
  }

  static String operator(Object value) {
    if (value instanceof Map<?, ?> m && m.size() == 1) {
      Object key = m.keySet().iterator().next();
      if (key instanceof String s && s.startsWith("$")) {
        return s;
      }
    }
    return null;
  }

  static void shape(Object value, Set<String> required, Set<String> optional) {
    var m = map(value);
    check(m.keySet().containsAll(required), "missing required fields");
    var allowed = new HashSet<>(required);
    allowed.addAll(optional);
    check(allowed.containsAll(m.keySet()), "unknown operand fields");
  }

  static String name(Object value, boolean container) {
    check(value instanceof String s && !s.isEmpty(), "nonempty static name required");
    String n = (String) value;
    check(!container || !RESERVED.contains(n), "reserved user-container name: " + n);
    return n;
  }

  static void staticValue(Object root) {
    var pending = new ArrayList<Object>();
    pending.add(root);
    while (!pending.isEmpty()) {
      Object v = pending.removeLast();
      check(operator(v) == null, "executable form in static Blue position");
      if (v instanceof Map<?, ?> m) {
        pending.addAll(m.values());
      } else if (v instanceof List<?> a) {
        pending.addAll(a);
      }
    }
  }

  static void staticPositions(Object root) {
    var pending = new ArrayList<Object>();
    pending.add(root);
    while (!pending.isEmpty()) {
      Object v = pending.removeLast();
      if (v instanceof Map<?, ?> m) {
        for (var e : m.entrySet()) {
          check(e.getKey() instanceof String, "object keys must be Text");
          if (STATIC.contains(e.getKey())) {
            staticValue(e.getValue());
          }
        }
        pending.addAll(m.values());
      } else if (v instanceof List<?> a) {
        pending.addAll(a);
      }
    }
  }

  /**
   * Physical admission limits, separate from semantic gas. Hosts hold exhausted jobs rather than
   * retrying indefinitely.
   */
  private static void hostBounds(Object root) {
    record Input(Object value, int statements) {}
    var pending = new ArrayDeque<Input>();
    var visited = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
    pending.push(new Input(root, 0));
    long nodes = 0, characters = 0;
    while (!pending.isEmpty()) {
      Input item = pending.pop();
      Object value = item.value();
      if (++nodes > 250_000 || characters > 8_000_000 || item.statements() > 16_384) {
        throw new BexFailure("HOST_INCOMPLETE", "BEX compiler input exceeds host quantum");
      }
      if (value instanceof String text) {
        characters += text.length();
      } else if (value instanceof Map<?, ?> map) {
        if (!visited.add(value)) {
          continue; // freeze() still rejects actual cycles.
        }
        if (map.size() > 250_000 - nodes) {
          throw new BexFailure("HOST_INCOMPLETE", "BEX compiler width");
        }
        for (var row : map.entrySet()) {
          if (row.getKey() instanceof String key) {
            characters += key.length();
          }
          int depth =
              item.statements() + (Set.of("$if", "$forEach").contains(row.getKey()) ? 1 : 0);
          pending.push(new Input(row.getValue(), depth));
        }
      } else if (value instanceof List<?> list && visited.add(value)) {
        if (list.size() > 250_000 - nodes) {
          throw new BexFailure("HOST_INCOMPLETE", "BEX compiler width");
        }
        for (Object child : list) {
          pending.push(new Input(child, item.statements()));
        }
      }
    }
    if (characters > 8_000_000) {
      throw new BexFailure("HOST_INCOMPLETE", "BEX compiler Text exceeds host quantum");
    }
  }

  public Program compile(Map<String, Object> source) {
    return compile(source, null, null);
  }

  public Program compile(Map<String, Object> source, Map<String, Object> definition, String entry) {
    if (bounded) {
      hostBounds(source);
      if (definition != null) {
        hostBounds(definition);
      }
    }
    Map<String, Object> merged = new TreeMap<>(map(BexValues.freeze(source)));
    if (definition != null) {
      for (String k : List.of("constants", "functions")) {
        var values = new TreeMap<>(map(BexValues.freeze(definition.getOrDefault(k, Map.of()))));
        values.putAll(map(merged.getOrDefault(k, Map.of())));
        merged.put(k, Collections.unmodifiableMap(values));
      }
    }
    constants = map(merged.getOrDefault("constants", Map.of()));
    functions = map(merged.getOrDefault("functions", Map.of()));
    if (bounded && functions.size() > 128) {
      throw new BexFailure("HOST_INCOMPLETE", "BEX compiler function-depth bound");
    }
    constants.keySet().forEach(k -> name(k, true));
    functions.keySet().forEach(k -> name(k, true));
    constants.values().forEach(BexPlanCompiler::staticValue);
    staticPositions(merged);
    calls.clear();
    inventory.clear();
    functions.keySet().forEach(k -> calls.put(k, new TreeSet<>()));
    calls.put("<root>", new TreeSet<>());
    for (var e : functions.entrySet()) {
      var b = map(e.getValue());
      var args = map(b.getOrDefault("args", Map.of()));
      args.forEach(
          (k, v) -> {
            name(k, true);
            staticValue(v);
          });
      body(b, new HashSet<>(args.keySet()), e.getKey());
    }
    String selected =
        entry != null
            ? entry
            : merged.get("entry") == null ? null : name(merged.get("entry"), true);
    if (selected != null) {
      name(selected, true);
      check(functions.containsKey(selected), "unknown entry function");
      check(
          map(map(functions.get(selected)).getOrDefault("args", Map.of())).isEmpty(),
          "entry function must have no arguments");
    }
    body(merged, new HashSet<>(), "<root>");
    var done = new HashSet<String>();
    for (String f : calls.keySet()) {
      visit(f, done, new HashSet<>());
    }
    return new Program(merged, selected, List.copyOf(inventory), intrinsicIds);
  }

  void visit(String f, Set<String> done, Set<String> active) {
    check(!active.contains(f), "recursive function call graph");
    if (done.contains(f)) {
      return;
    }
    active.add(f);
    for (String g : calls.get(f)) {
      visit(g, done, active);
    }
    active.remove(f);
    done.add(f);
  }

  void body(Map<String, Object> b, Set<String> slots, String current) {
    if (b.containsKey("expr")) {
      expr(b.get("expr"), slots, current);
    } else if (b.containsKey("do")) {
      block(b.get("do"), slots, current);
    }
  }

  void call(Object raw, Set<String> slots, String current) {
    shape(raw, Set.of("function"), Set.of("args"));
    var b = map(raw);
    String f = name(b.get("function"), true);
    check(functions.containsKey(f), "unknown function " + f);
    var args = map(b.getOrDefault("args", Map.of()));
    args.keySet().forEach(k -> name(k, true));
    check(
        args.keySet().equals(map(map(functions.get(f)).getOrDefault("args", Map.of())).keySet()),
        "call argument set mismatch");
    args.values().forEach(v -> expr(v, slots, current));
    calls.get(current).add(f);
  }

  private record ExpressionCheck(Object value, Set<String> slots, String current) {}

  private List<ExpressionCheck> children;

  void expr(Object v, Set<String> slots, String current) {
    var check = new ExpressionCheck(v, slots, current);
    if (children != null) {
      children.add(check);
      return;
    }
    var pending = new ArrayDeque<ExpressionCheck>();
    pending.push(check);
    try {
      while (!pending.isEmpty()) {
        var next = pending.pop();
        children = new ArrayList<>();
        expression(next.value(), next.slots(), next.current());
        for (int i = children.size() - 1; i >= 0; i--) {
          pending.push(children.get(i));
        }
      }
    } finally {
      children = null;
    }
  }

  void expression(Object v, Set<String> slots, String current) {
    String op = operator(v);
    if (op == null) {
      if (v instanceof List<?> a) {
        a.forEach(x -> expr(x, slots, current));
      } else if (v instanceof Map<?, ?> m) {
        m.forEach(
            (k, x) -> {
              if (!STATIC.contains(k)) {
                expr(x, slots, current);
              }
            });
      } else {
        check(
            v == null || v instanceof String || v instanceof Number || v instanceof Boolean,
            "invalid literal value");
      }
      return;
    }
    check(EXPR.contains(op), "unknown expression operator " + op);
    inventory.add(op);
    Object raw = map(v).get(op);
    if (op.equals("$literal") || CONSTANT.contains(op)) {
      return;
    }
    if (UNARY.contains(op)) {
      expr(raw, slots, current);
      return;
    }
    if (ARRAY.contains(op)) {
      var b = list(raw);
      if (Set.of("$startsWith", "$sliceAfter", "$eq", "$ne", "$gt", "$gte", "$lt", "$lte")
          .contains(op)) {
        check(b.size() == 2, "exactly two operands required");
      }
      if (Set.of("$add", "$subtract", "$multiply", "$divide").contains(op)) {
        check(!b.isEmpty(), "at least one arithmetic operand required");
      }
      b.forEach(x -> expr(x, slots, current));
      return;
    }
    if (op.equals("$var") || op.equals("$const")) {
      String n;
      if (raw instanceof String s) {
        n = s;
      } else {
        shape(raw, Set.of("name"), Set.of("path"));
        n = name(map(raw).get("name"), false);
        expr(map(raw).getOrDefault("path", ""), slots, current);
      }
      check(
          (op.equals("$var") ? slots : constants.keySet()).contains(n), "unknown " + op + " " + n);
      return;
    }
    if (READS.contains(op)) {
      if (raw instanceof Map && operator(raw) == null) {
        Set<String> allowed =
            Set.of("$document", "$resultValue").contains(op)
                ? Set.of("path", "view")
                : op.equals("$binding")
                    ? Set.of("name", "path")
                    : op.equals("$steps") ? Set.of("step", "path") : Set.of("path");
        shape(raw, Set.of(), allowed);
        map(raw).values().forEach(x -> expr(x, slots, current));
      } else {
        expr(raw, slots, current);
      }
      return;
    }
    var b = map(raw);
    if (op.equals("$is")) {
      shape(b, Set.of("node", "pattern"), Set.of());
      staticValue(b.get("pattern"));
      expr(b.get("node"), slots, current);
      return;
    }
    if (op.equals("$isKind")) {
      shape(b, Set.of("val", "kind"), Set.of());
      Object k = b.get("kind");
      List<?> kinds = k instanceof List<?> a ? a : Collections.singletonList(k);
      check(
          !kinds.isEmpty()
              && kinds.stream().allMatch(x -> x instanceof String && KINDS.contains(x)),
          "invalid kind test");
      expr(b.get("val"), slots, current);
      return;
    }
    if (op.equals("$call")) {
      call(b, slots, current);
      return;
    }
    if (op.equals("$intrinsic")) {
      check(
          b.get("type") instanceof Map<?, ?> t && t.keySet().equals(Set.of("blueId")),
          "intrinsic needs static exact type BlueId");
      check(
          intrinsicIds.contains(map(b.get("type")).get("blueId")), "unknown intrinsic exact type");
      b.forEach(
          (k, x) -> {
            if (!k.equals("type")) {
              expr(x, slots, current);
            }
          });
      return;
    }
    if (QUERY.contains(op)) {
      String bodyKey =
          Set.of("$filter", "$some", "$find", "$findEntry").contains(op) ? "where" : "expr";
      var required = new HashSet<>(Set.of("in", "item", bodyKey));
      if (op.equals("$reduce")) {
        required.addAll(Set.of("acc", "init"));
      }
      shape(b, required, Set.of("key", "index"));
      expr(b.get("in"), slots, current);
      if (op.equals("$reduce")) {
        expr(b.get("init"), slots, current);
      }
      var names = new HashSet<String>();
      for (String k : List.of("item", "key", "index", "acc")) {
        if (b.containsKey(k)) {
          check(names.add(name(b.get(k), true)), "collection bindings must differ");
        }
      }
      var inner = new HashSet<>(slots);
      inner.addAll(names);
      expr(b.get(bodyKey), inner, current);
      return;
    }
    String[] required, optional;
    switch (op) {
      case "$join" -> {
        required = new String[] {"list", "separator"};
        optional = new String[] {};
      }
      case "$split" -> {
        required = new String[] {"text", "separator"};
        optional = new String[] {"limit"};
      }
      case "$listGet" -> {
        required = new String[] {"list", "index"};
        optional = new String[] {"default"};
      }
      case "$get", "$hasKey" -> {
        required = new String[] {"object", "key"};
        optional = new String[] {};
      }
      case "$objectSet" -> {
        required = new String[] {"object", "key", "val"};
        optional = new String[] {};
      }
      case "$pointerGet" -> {
        required = new String[] {"object", "path"};
        optional = new String[] {"default"};
      }
      case "$pointerSet" -> {
        required = new String[] {"object", "path"};
        optional = new String[] {"op", "val"};
      }
      case "$includes" -> {
        required = new String[] {"list", "val"};
        optional = new String[] {};
      }
      case "$choose" -> {
        required = new String[] {"cond", "then"};
        optional = new String[] {"else"};
      }
      default -> throw new BexFailure("BEX_COMPILE_ERROR", "unsupported shape " + op);
    }
    shape(b, Set.of(required), Set.of(optional));
    b.values().forEach(x -> expr(x, slots, current));
  }

  void block(Object raw, Set<String> slots, String current) {
    Deque<Runnable> pending = new ArrayDeque<>();
    enqueueBlock(raw, slots, current, pending);
    while (!pending.isEmpty()) {
      pending.pop().run();
    }
  }

  private void enqueueBlock(
      Object raw, Set<String> slots, String current, Deque<Runnable> pending) {
    Iterator<?> statements = list(raw).iterator();
    class Next implements Runnable {
      public void run() {
        if (!statements.hasNext()) {
          return;
        }
        Object statement = statements.next();
        pending.push(this);
        statement(statement, slots, current, pending);
      }
    }
    pending.push(new Next());
  }

  private void statement(
      Object statement, Set<String> slots, String current, Deque<Runnable> pending) {
    String op = operator(statement);
    check(op != null && STMT.contains(op), "unknown or malformed statement");
    inventory.add(op);
    Object value = map(statement).get(op);
    if (op.equals("$let")) {
      var b = map(value);
      if (b.containsKey("vars")) {
        shape(b, Set.of("vars"), Set.of("order"));
        var variables = map(b.get("vars"));
        variables.keySet().forEach(n -> name(n, true));
        if (b.get("order") != null) {
          var order = list(b.get("order"));
          check(
              order.size() == variables.size() && new HashSet<>(order).equals(variables.keySet()),
              "order must name every variable exactly once");
          for (Object name : order) {
            expr(variables.get(name), slots, current);
            slots.add((String) name);
          }
        } else {
          variables.values().forEach(x -> expr(x, slots, current));
          slots.addAll(variables.keySet());
        }
      } else {
        shape(b, Set.of("name", "expr"), Set.of());
        String n = name(b.get("name"), true);
        expr(b.get("expr"), slots, current);
        slots.add(n);
      }
    } else if (op.equals("$set")) {
      shape(value, Set.of("name", "expr"), Set.of());
      var b = map(value);
      check(slots.contains(b.get("name")), "$set requires declared variable");
      expr(b.get("expr"), slots, current);
    } else if (op.equals("$if")) {
      shape(value, Set.of("cond"), Set.of("then", "else"));
      var b = map(value);
      expr(b.get("cond"), slots, current);
      var a = new HashSet<>(slots);
      var z = new HashSet<>(slots);
      pending.push(
          () -> {
            a.retainAll(z);
            slots.addAll(a);
          });
      enqueueBlock(b.getOrDefault("else", List.of()), z, current, pending);
      enqueueBlock(b.getOrDefault("then", List.of()), a, current, pending);
    } else if (op.equals("$forEach")) {
      shape(value, Set.of("in", "item", "do"), Set.of("key", "index"));
      var b = map(value);
      expr(b.get("in"), slots, current);
      var names = new HashSet<String>();
      for (String k : List.of("item", "key", "index")) {
        if (b.containsKey(k)) {
          check(names.add(name(b.get(k), true)), "loop bindings must differ");
        }
      }
      slots.addAll(names);
      enqueueBlock(b.get("do"), slots, current, pending);
    } else if (op.equals("$appendChange")) {
      shape(value, Set.of("op", "path"), Set.of("val"));
      map(value).values().forEach(x -> expr(x, slots, current));
    } else if (op.equals("$call")) {
      call(value, slots, current);
    } else if (op.equals("$returnIf") || op.equals("$failIf")) {
      String key = op.equals("$returnIf") ? "expr" : "message";
      shape(value, Set.of("cond"), Set.of(key));
      var b = map(value);
      expr(b.get("cond"), slots, current);
      if (b.containsKey(key)) {
        expr(b.get(key), slots, current);
      }
    } else if (op.equals("$fail") && value instanceof Map<?, ?> m && m.containsKey("message")) {
      shape(value, Set.of("message"), Set.of());
      expr(m.get("message"), slots, current);
    } else {
      expr(value, slots, current);
    }
  }
}
