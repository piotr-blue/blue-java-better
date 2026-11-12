package blue;

import static blue.BexEngine.Invocation.*;
import static blue.BexFailure.require;
import static blue.BexValues.*;

import java.math.BigInteger;
import java.util.*;
import java.util.function.Consumer;

/** Explicit continuations for the complete BEX language; user nesting never uses the Java stack. */
final class BexMachine {
  private final BexEngine.Invocation call;
  private final BexGas gas;
  private final Deque<Runnable> pending = new ArrayDeque<>();
  private final Deque<Boundary> returns = new ArrayDeque<>();
  private Object value;

  BexMachine(BexEngine.Invocation call) {
    this.call = call;
    gas = call.gas;
  }

  Object execute() {
    if (call.entry == null) body(call.program, false);
    else function(call.entry, Map.of(), false);
    while (!pending.isEmpty()) {
      if (pending.size() > 100_000 || returns.size() > 16_384)
        throw new BexFailure("HOST_INCOMPLETE", "BEX continuation capacity exceeded");
      pending.pop().run();
    }
    return value;
  }

  private void evaluate(Object source, Runnable next) {
    pending.push(next);
    pending.push(() -> expression(source));
  }

  private final class Boundary implements Runnable {
    final boolean frame;
    boolean returned;

    Boundary(boolean frame) {
      this.frame = frame;
    }

    public void run() {
      if (!returned) value = call.defaults();
      if (frame) call.frames.pop();
      require(returns.pop() == this, "Invalid BEX return boundary");
    }
  }

  private void body(Map<String, Object> source, boolean frame) {
    Boundary boundary = new Boundary(frame);
    returns.push(boundary);
    pending.push(boundary);
    if (source.containsKey("expr")) {
      boundary.returned = true;
      pending.push(() -> expression(source.get("expr")));
    } else block(source.getOrDefault("do", List.of()));
  }

  private void returnValue() {
    Boundary boundary = returns.peek();
    while (pending.peek() != boundary) pending.pop();
    boundary.returned = true;
  }

  private void function(String name, Map<String, Object> args, boolean charged) {
    if (charged) gas.charge("functionCall");
    var definition = map(call.functions.get(name));
    for (String key : keys(definition.getOrDefault("args", Map.of())))
      require(
          call.matches(args.get(key), map(definition.get("args")).get(key)),
          "function argument pattern rejected: " + key);
    call.frames.push(new TreeMap<>(args));
    body(definition, true);
  }

  private void text(Object source, Consumer<String> next) {
    if (source instanceof String s) next.accept(s);
    else evaluate(source, () -> next.accept(requiredText(value)));
  }

  private String path(String p) {
    if (p.isEmpty() || p.equals("/")) return call.context.scope;
    String selected = p.startsWith("/") ? p : call.context.scope.replaceAll("/$", "") + "/" + p;
    segments(selected);
    return selected;
  }

  private void values(Iterator<?> inputs, List<Object> result, Consumer<List<Object>> next) {
    if (!inputs.hasNext()) {
      next.accept(result);
      return;
    }
    evaluate(
        inputs.next(),
        () -> {
          result.add(value);
          values(inputs, result, next);
        });
  }

  private void fields(
      Object source,
      Iterator<String> names,
      Map<String, Object> result,
      boolean staticPositions,
      boolean omitUndefined,
      Consumer<Map<String, Object>> next) {
    if (!names.hasNext()) {
      next.accept(result);
      return;
    }
    String key = names.next();
    Runnable done =
        () -> {
          if (!omitUndefined || value != UNDEFINED) result.put(key, value);
          fields(source, names, result, staticPositions, omitUndefined, next);
        };
    if (staticPositions && BexPlanCompiler.STATIC.contains(key)) {
      value = get(source, key);
      pending.push(done);
    } else evaluate(get(source, key), done);
  }

  private void expression(Object source) {
    gas.charge("expressionBase");
    String op = BexPlanCompiler.operator(source);
    if (op == null) {
      if (source instanceof Map<?, ?>)
        fields(source, keys(source).iterator(), new TreeMap<>(), true, true, x -> value = x);
      else if (source instanceof List<?> list)
        values(list.iterator(), new ArrayList<>(), x -> value = x);
      else {
        kind(source);
        value = source;
      }
      return;
    }
    Object raw = get(source, op);
    switch (op) {
      case "$literal" -> value = raw;
      case "$null" -> value = null;
      case "$emptyObject" -> value = new TreeMap<String, Object>();
      case "$emptyList" -> value = new ArrayList<>();
      case "$changeset", "$events" -> {
        var values = op.equals("$changeset") ? call.changes : call.events;
        gas.charge("valueMember", values.size());
        value = new ArrayList<>(values);
      }
      case "$and", "$or", "$coalesce", "$default" -> lazy(op, list(raw).iterator());
      default -> {
        if (BexPlanCompiler.READS.contains(op)) read(op, raw);
        else if (BexPlanCompiler.UNARY.contains(op))
          evaluate(raw, () -> value = call.unary(op, value));
        else if (BexPlanCompiler.ARRAY.contains(op))
          values(list(raw).iterator(), new ArrayList<>(), args -> value = call.array(op, args));
        else compound(op, map(raw));
      }
    }
  }

  private void lazy(String op, Iterator<Object> inputs) {
    if (!inputs.hasNext()) {
      value = op.equals("$and") ? true : op.equals("$or") ? false : UNDEFINED;
      return;
    }
    evaluate(
        inputs.next(),
        () -> {
          boolean truth = truthy(value);
          if (op.equals("$and") && !truth) value = false;
          else if (op.equals("$or") && truth) value = true;
          else if (!(truth && (op.equals("$coalesce") || op.equals("$default")))) lazy(op, inputs);
        });
  }

  private void read(String op, Object raw) {
    if (Set.of("$var", "$const", "$binding", "$steps").contains(op)) {
      if (!op.equals("$const")) gas.charge(op.equals("$steps") ? "stepsRead" : "varRead");
      if (raw instanceof String s) {
        String key = s, pointer = "";
        if (op.equals("$binding") && s.contains("/")) {
          int i = s.indexOf('/');
          key = s.substring(0, i);
          pointer = s.substring(i);
        } else if (op.equals("$steps") && s.contains(".")) {
          int i = s.indexOf('.');
          key = s.substring(0, i);
          pointer = "/" + s.substring(i + 1).replace('.', '/');
        }
        namedRead(op, key, pointer);
      } else {
        var body = map(raw);
        text(
            body.getOrDefault(op.equals("$steps") ? "step" : "name", UNDEFINED),
            key -> text(body.getOrDefault("path", ""), pointer -> namedRead(op, key, pointer)));
      }
      return;
    }
    if (op.equals("$event") || op.equals("$currentContract")) {
      gas.charge(op.equals("$event") ? "eventRead" : "currentContractRead");
      Object p =
          raw instanceof Map && BexPlanCompiler.operator(raw) == null
              ? map(raw).getOrDefault("path", "")
              : raw;
      text(
          p,
          pointer -> value = new BexEngine.InputRead(op, "", pointer, "").observed(call.context));
      return;
    }
    gas.charge(op.equals("$document") ? "documentRead" : "resultValueRead");
    var body =
        raw instanceof Map && BexPlanCompiler.operator(raw) == null
            ? map(raw)
            : Map.of("path", raw);
    text(
        body.getOrDefault("path", ""),
        p ->
            text(
                body.getOrDefault("view", "canonical"),
                view -> {
                  String pointer = path(p);
                  Object doc =
                      view.equals("resolved")
                          ? call.context.resolvedDocument
                          : call.context.document;
                  require(doc != UNDEFINED, "resolved document capability unavailable");
                  if (op.equals("$resultValue")) {
                    if (call.context.inputObserver != null) call.context.inputObserver.opaque();
                    for (Object item : call.changes) {
                      var patch = map(item);
                      doc =
                          overlay(
                              doc,
                              (String) patch.get("path"),
                              patch.getOrDefault("val", UNDEFINED),
                              patch.get("op").equals("remove"),
                              gas);
                    }
                  }
                  if (op.equals("$document") && call.context.inputObserver != null)
                    value = new BexEngine.InputRead(op, "", pointer, view).observed(call.context);
                  else
                    value =
                        call.context.readDocument == null
                            ? pointerGet(doc, pointer)
                            : call.context.readDocument.read(pointer, view, doc);
                }));
  }

  private void namedRead(String op, String key, String pointer) {
    if (op.equals("$binding") || op.equals("$steps"))
      value = new BexEngine.InputRead(op, key, pointer, "").observed(call.context);
    else
      value =
          pointerGet(
              (op.equals("$var") ? call.frame() : call.constants).getOrDefault(key, UNDEFINED),
              pointer);
  }

  private void arguments(Map<String, Object> body, Consumer<Map<String, Object>> next) {
    Object args = body.getOrDefault("args", Map.of());
    fields(args, keys(args).iterator(), new TreeMap<>(), false, false, next);
  }

  private void compound(String op, Map<String, Object> b) {
    if (BexPlanCompiler.QUERY.contains(op)) {
      evaluate(
          b.get("in"),
          () -> {
            Object source = value;
            var rows = call.collection(source).iterator();
            if (op.equals("$reduce"))
              evaluate(b.get("init"), () -> new Query(op, b, source, rows, value).next());
            else new Query(op, b, source, rows, UNDEFINED).next();
          });
      return;
    }
    switch (op) {
      case "$choose" ->
          evaluate(
              b.get("cond"),
              () -> {
                String branch = truthy(value) ? "then" : "else";
                if (b.containsKey(branch)) pending.push(() -> expression(b.get(branch)));
                else value = UNDEFINED;
              });
      case "$is" -> evaluate(b.get("node"), () -> value = call.matches(value, b.get("pattern")));
      case "$isKind" ->
          evaluate(
              b.get("val"),
              () -> {
                String kind = kind(value);
                Object kinds = b.get("kind");
                value =
                    kinds instanceof List<?> list
                        ? list.contains(kind)
                        : Objects.equals(kind, kinds);
              });
      case "$call" -> arguments(b, args -> function((String) b.get("function"), args, true));
      case "$intrinsic" -> {
        if (call.context.inputObserver != null) call.context.inputObserver.opaque();
        String id = (String) map(b.get("type")).get("blueId");
        var processor = call.intrinsics.get(id);
        require(processor != null, "intrinsic exact type is not installed");
        var names = new ArrayList<>(keys(b));
        names.remove("type");
        fields(
            b,
            names.iterator(),
            new TreeMap<>(),
            false,
            true,
            payload -> {
              @SuppressWarnings("unchecked")
              var original = (Map<String, Object>) call.original(copy(payload));
              value = processor.execute(original, gas);
              require(value != null, "intrinsic returned null");
              kind(value);
            });
      }
      case "$join" ->
          evaluate(
              b.get("list"),
              () -> {
                Object list = value;
                text(
                    b.get("separator"),
                    separator -> {
                      require(kind(list).equals("list"), "join requires list");
                      gas.charge("valueMember", size(list));
                      List<String> strings = new ArrayList<>();
                      long length = 0;
                      for (int i = 0; i < size(list); i++) {
                        String s = call.toText(get(list, i));
                        strings.add(s);
                        length += size(s);
                      }
                      gas.charge(
                          "textWork",
                          (length + (long) size(separator) * Math.max(0, strings.size() - 1) + 63)
                              / 64);
                      value = String.join(separator, strings);
                    });
              });
      case "$split" ->
          text(
              b.get("text"),
              text ->
                  text(
                      b.get("separator"),
                      separator -> {
                        Runnable done =
                            () -> {
                              BigInteger limit =
                                  b.containsKey("limit")
                                      ? call.toInteger(value)
                                      : BigInteger.valueOf(-1);
                              require(
                                  !separator.isEmpty()
                                      && limit.signum() != 0
                                      && limit.compareTo(BigInteger.valueOf(-1)) >= 0,
                                  "invalid split separator/limit");
                              gas.charge("textWork", (size(text) + size(separator) + 63L) / 64);
                              gas.charge("valueMember", size(text) + 1L);
                              int count =
                                  limit.signum() < 0
                                      ? -1
                                      : limit.min(BigInteger.valueOf(Integer.MAX_VALUE)).intValue();
                              value =
                                  new ArrayList<>(
                                      Arrays.asList(
                                          text.split(
                                              java.util.regex.Pattern.quote(separator), count)));
                            };
                        if (b.containsKey("limit")) evaluate(b.get("limit"), done);
                        else done.run();
                      }));
      case "$listGet" ->
          evaluate(
              b.get("list"),
              () -> {
                Object list = value;
                evaluate(
                    b.get("index"),
                    () -> {
                      BigInteger index = call.toInteger(value);
                      require(
                          kind(list).equals("list") && index.signum() >= 0,
                          "listGet requires a list and nonnegative index");
                      if (index.compareTo(BigInteger.valueOf(size(list))) < 0)
                        value = get(list, index.intValue());
                      else fallback(b);
                    });
              });
      case "$get", "$hasKey" ->
          evaluate(
              b.get("object"),
              () -> {
                Object object = value;
                text(
                    b.get("key"),
                    key -> {
                      Object found = kind(object).equals("object") ? get(object, key) : UNDEFINED;
                      value = op.equals("$hasKey") ? found != UNDEFINED : found;
                    });
              });
      case "$objectSet" ->
          text(
              b.get("key"),
              key ->
                  evaluate(
                      b.get("val"),
                      () -> {
                        Object selected = value;
                        call.sized("objectSetBase", selected, 0);
                        evaluate(
                            b.get("object"),
                            () -> {
                              Object object =
                                  value == null || value == UNDEFINED ? Map.of() : value;
                              require(kind(object).equals("object"), "objectSet requires object");
                              var result = new TreeMap<String, Object>();
                              for (String name : ordered(object, gas))
                                result.put(name, get(object, name));
                              if (selected == UNDEFINED) result.remove(key);
                              else result.put(key, selected);
                              value = result;
                            });
                      }));
      case "$pointerGet" ->
          evaluate(
              b.get("object"),
              () -> {
                Object object = value;
                text(
                    b.get("path"),
                    pointer -> {
                      gas.amount(
                          "pointerGetBase",
                          gas.tariff("pointerGetBase") + segments(pointer).size());
                      value = pointerGet(object, pointer);
                      if (value == UNDEFINED) fallback(b);
                    });
              });
      case "$pointerSet" ->
          text(
              b.getOrDefault("op", "set"),
              operation ->
                  text(
                      b.get("path"),
                      pointer -> {
                        require(
                            Set.of("set", "remove").contains(operation),
                            "pointerSet op must be set/remove");
                        Runnable selected =
                            () -> {
                              Object replacement = value;
                              evaluate(
                                  b.get("object"),
                                  () -> {
                                    call.sized(
                                        "pointerSetBase", replacement, segments(pointer).size());
                                    value =
                                        overlay(
                                            value,
                                            pointer,
                                            replacement,
                                            operation.equals("remove"),
                                            gas);
                                  });
                            };
                        if (!operation.equals("remove") && b.containsKey("val"))
                          evaluate(b.get("val"), selected);
                        else {
                          value = UNDEFINED;
                          selected.run();
                        }
                      }));
      case "$includes" ->
          evaluate(
              b.get("list"),
              () -> {
                Object list = value;
                evaluate(
                    b.get("val"),
                    () -> {
                      Object searched = value;
                      require(kind(list).equals("list"), "includes requires list");
                      value = false;
                      for (int i = 0; i < size(list); i++) {
                        Object item = get(list, i);
                        gas.charge("forEachItem");
                        if (equal(item, searched, gas)) {
                          value = true;
                          break;
                        }
                      }
                    });
              });
      default -> throw new BexFailure("unimplemented operator " + op);
    }
  }

  private void fallback(Map<String, Object> body) {
    if (body.containsKey("default")) pending.push(() -> expression(body.get("default")));
    else value = UNDEFINED;
  }

  private final class Query {
    final String op;
    final Map<String, Object> body, old = new HashMap<>();
    final Set<String> present = new HashSet<>();
    final Iterator<BexEngine.Invocation.Row> rows;
    final Object output;
    Object accumulator;

    Query(
        String op,
        Map<String, Object> body,
        Object source,
        Iterator<BexEngine.Invocation.Row> rows,
        Object initial) {
      this.op = op;
      this.body = body;
      this.rows = rows;
      accumulator = initial;
      output =
          op.equals("$filter") && kind(source).equals("object")
              ? new TreeMap<String, Object>()
              : new ArrayList<>();
      for (String key : List.of("item", "key", "index", "acc"))
        if (body.containsKey(key)) {
          String name = (String) body.get(key);
          old.put(name, call.frame().getOrDefault(name, UNDEFINED));
          if (call.frame().containsKey(name)) present.add(name);
        }
    }

    void finish(Object result) {
      for (var entry : old.entrySet()) {
        if (present.contains(entry.getKey())) call.frame().put(entry.getKey(), entry.getValue());
        else call.frame().remove(entry.getKey());
      }
      value = result;
    }

    void next() {
      if (!rows.hasNext()) {
        finish(
            op.equals("$reduce")
                ? accumulator
                : op.equals("$some")
                    ? false
                    : op.equals("$find") || op.equals("$findEntry") ? UNDEFINED : output);
        return;
      }
      var row = rows.next();
      gas.charge("forEachItem");
      call.frame().put((String) body.get("item"), row.value());
      if (body.containsKey("key")) call.frame().put((String) body.get("key"), row.key());
      if (body.containsKey("index")) call.frame().put((String) body.get("index"), row.index());
      if (body.containsKey("acc")) call.frame().put((String) body.get("acc"), accumulator);
      evaluate(
          body.get(body.containsKey("where") ? "where" : "expr"),
          () -> {
            if (op.equals("$reduce")) accumulator = value;
            else if (op.equals("$map")) list(output).add(value);
            else if (op.equals("$flatMap")) {
              require(kind(value).equals("list"), "flatMap item is not a list");
              gas.charge("valueMember", size(value));
              for (int i = 0; i < size(value); i++) list(output).add(get(value, i));
            } else if (truthy(value)) {
              if (op.equals("$some")) {
                finish(true);
                return;
              }
              if (op.equals("$find")) {
                finish(row.value());
                return;
              }
              if (op.equals("$findEntry")) {
                var result = new TreeMap<String, Object>();
                result.put("val", row.value());
                result.put("index", row.index());
                if (row.key() != UNDEFINED) result.put("key", row.key());
                finish(result);
                return;
              }
              if (output instanceof Map) map(output).put((String) row.key(), row.value());
              else list(output).add(row.value());
            }
            pending.push(this::next);
          });
    }
  }

  private void block(Object statements) {
    Iterator<Object> remaining = list(statements).iterator();
    pending.push(
        new Runnable() {
          public void run() {
            if (!remaining.hasNext()) return;
            Object next = remaining.next();
            pending.push(this);
            statement(next);
          }
        });
  }

  private void statement(Object source) {
    gas.charge("statementBase");
    String op = BexPlanCompiler.operator(source);
    Object raw = get(source, op);
    switch (op) {
      case "$let", "$set" -> {
        var body = map(raw);
        if (body.containsKey("vars")) {
          var vars = map(body.get("vars"));
          if (body.containsKey("order")) {
            var names = list(body.get("order")).iterator();
            pending.push(
                new Runnable() {
                  public void run() {
                    if (!names.hasNext()) return;
                    String name = (String) names.next();
                    evaluate(
                        vars.get(name),
                        () -> {
                          call.frame().put(name, value);
                          pending.push(this);
                        });
                  }
                });
          } else
            fields(
                vars, keys(vars).iterator(), new TreeMap<>(), false, false, call.frame()::putAll);
        } else evaluate(body.get("expr"), () -> call.frame().put((String) body.get("name"), value));
      }
      case "$if" -> {
        var body = map(raw);
        evaluate(
            body.get("cond"),
            () -> block(body.getOrDefault(truthy(value) ? "then" : "else", List.of())));
      }
      case "$forEach" -> {
        var body = map(raw);
        evaluate(
            body.get("in"),
            () -> {
              Object collection = value;
              var rows = call.collection(collection).iterator();
              pending.push(
                  new Runnable() {
                    public void run() {
                      if (!rows.hasNext()) return;
                      var row = rows.next();
                      gas.charge("forEachItem");
                      Object item = row.value();
                      if (!kind(collection).equals("list") && !body.containsKey("key")) {
                        var pair = new TreeMap<String, Object>();
                        pair.put("key", row.key());
                        pair.put("val", row.value());
                        item = pair;
                      }
                      call.frame().put((String) body.get("item"), item);
                      if (body.containsKey("key"))
                        call.frame().put((String) body.get("key"), row.key());
                      if (body.containsKey("index"))
                        call.frame()
                            .put(
                                (String) body.get("index"),
                                kind(collection).equals("list") ? row.index() : UNDEFINED);
                      pending.push(this);
                      block(body.get("do"));
                    }
                  });
            });
      }
      case "$appendChange" -> patch(raw);
      case "$appendChanges" ->
          evaluate(
              raw,
              () -> {
                require(kind(value).equals("list"), "appendChanges needs list");
                for (int i = 0; i < size(value); i++) call.appendChange(get(value, i));
              });
      case "$appendEvent" -> evaluate(raw, () -> call.appendEvent(value));
      case "$appendEvents" ->
          evaluate(
              raw,
              () -> {
                require(kind(value).equals("list"), "appendEvents needs list");
                for (int i = 0; i < size(value); i++) call.appendEvent(get(value, i));
              });
      case "$call" -> {
        var body = map(raw);
        arguments(body, args -> function((String) body.get("function"), args, true));
      }
      case "$return" -> {
        if (raw == null) {
          value = call.defaults();
          returnValue();
        } else evaluate(raw, this::returnValue);
      }
      case "$returnIf" -> {
        var body = map(raw);
        evaluate(
            body.get("cond"),
            () -> {
              if (!truthy(value)) return;
              if (body.containsKey("expr")) evaluate(body.get("expr"), this::returnValue);
              else {
                value = call.defaults();
                returnValue();
              }
            });
      }
      case "$fail" ->
          evaluate(
              raw instanceof Map<?, ?> m && m.containsKey("message") ? m.get("message") : raw,
              () -> {
                throw new BexFailure("EXECUTION_REJECTED", call.toText(value));
              });
      case "$failIf" -> {
        var body = map(raw);
        evaluate(
            body.get("cond"),
            () -> {
              if (truthy(value))
                evaluate(
                    body.getOrDefault("message", "BEX failure"),
                    () -> {
                      throw new BexFailure("EXECUTION_REJECTED", call.toText(value));
                    });
            });
      }
      default -> throw new BexFailure("unknown statement " + op);
    }
  }

  private void patch(Object raw) {
    require(kind(raw).equals("object"), "malformed patch");
    var names = new HashSet<>(keys(raw));
    require(
        Set.of("op", "path", "val").containsAll(names) && names.containsAll(Set.of("op", "path")),
        "malformed patch");
    text(
        get(raw, "op"),
        operation -> {
          require(Set.of("add", "replace", "remove").contains(operation), "unsupported patch op");
          text(
              get(raw, "path"),
              pointer -> {
                String selected = path(pointer);
                Runnable done =
                    () -> {
                      require(
                          operation.equals("remove") || value != UNDEFINED,
                          "patch val is required");
                      call.sized("appendChangeBase", value, 0);
                      var change = new TreeMap<String, Object>();
                      change.put("op", operation);
                      change.put("path", selected);
                      if (!operation.equals("remove")) change.put("val", value);
                      call.changes.add(change);
                    };
                if (!operation.equals("remove") && names.contains("val"))
                  evaluate(get(raw, "val"), done);
                else {
                  value = UNDEFINED;
                  done.run();
                }
              });
        });
  }
}
