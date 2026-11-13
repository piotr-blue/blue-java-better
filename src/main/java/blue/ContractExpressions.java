package blue;

import static blue.ContractLanguage.ABSENT;
import static blue.ContractLanguage.identity;
import static blue.ContractLanguage.recipe;
import static blue.ContractModel.m;
import static blue.ContractModel.obj;
import static blue.ContractModel.require;
import static blue.ContractModel.segments;
import static blue.ContractModel.str;

import blue.LanguageModel.Recipe;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Binds BEX to an invocation's current document graph, evidence scope and gas meter. */
final class ContractExpressions {
  private final ContractRun run;

  ContractExpressions(ContractRun run) {
    this.run = run;
  }

  static Map<String, Object> program(Map<String, Object> step) {
    var out = new TreeMap<String, Object>(ContractCanonical.CODE_POINTS);
    for (String k : List.of("expr", "do", "constants", "functions")) {
      if (step.containsKey(k)) {
        out.put(k, step.get(k));
      }
    }
    return out;
  }

  /** Exact identity also binds the compiled program cache. */
  record Definition(String identity, Map<String, Object> program) {}

  Definition computeDefinition(String key, Map<String, Object> step) {
    if (!step.containsKey("definition")) {
      return null;
    }
    Object operand = step.get("definition");
    var h = run.blue.head(operand);
    if (h.value != ABSENT) {
      require(
          h.value instanceof String && !((String) h.value).isEmpty(), "INVALID_COMPUTE_DEFINITION");
      String name = str(h.value);
      String path = name.startsWith("/") ? name : "/contracts/" + BexValues.escape(name);
      Recipe selected = recipe(run.state.documents.get(key).body);
      for (String part : segments(path)) {
        for (ExactNode node : selected.parts()) {
          run.graph.logical.open(node);
        }
        run.meter.contracts("objectMemberRead");
        selected = run.blue.child(selected, part);
        require(selected != null, "MISSING_COMPUTE_DEFINITION", "path", path);
      }
      operand = selected;
    }
    run.engine.requireComputeDefinition(operand);
    h = run.blue.head(operand);
    require(
        h.value == ABSENT
            && h.items == null
            && Set.of("constants", "functions", "specification", "order")
                .containsAll(h.fields.keySet()),
        "INVALID_COMPUTE_DEFINITION");
    var out = new TreeMap<String, Object>(ContractCanonical.CODE_POINTS);
    for (String field : List.of("constants", "functions")) {
      var ch = run.blue.child(operand, field);
      if (ch != null) {
        // Resolving a function definition is representation work; executed BEX is metered.
        Object v = run.reg.definitionValue(ch);
        require(v instanceof Map, "INVALID_COMPUTE_DEFINITION", "field", field);
        out.put(field, v);
      }
    }
    return new Definition(identity(run.blue.exact(operand)), out);
  }

  private static boolean readsResultValue(Object v) {
    if (v instanceof List<?> a) {
      return a.stream().anyMatch(ContractExpressions::readsResultValue);
    }
    if (v instanceof Map<?, ?>) {
      var m = obj(v);
      return m.containsKey("$resultValue")
          || m.entrySet().stream()
              .anyMatch(e -> !e.getKey().equals("$literal") && readsResultValue(e.getValue()));
    }
    return false;
  }

  BexEngine.Context context(
      String key,
      ExactNode payload,
      Map<String, Object> contract,
      Map<String, Object> steps,
      ExactNode call,
      Object overlayProgram) {
    ExactNode body = run.state.documents.get(key).body;
    Object view =
        readsResultValue(overlayProgram == null ? program(contract) : overlayProgram)
            ? run.blue.view(body, run.graph.logical)
            : body;
    var c = new BexEngine.Context();
    c.document = view;
    c.resolvedDocument = view;
    c.event = run.blue.view(payload, run.graph.logical);
    c.bindings =
        m(
            "processingEvent",
            run.event == null ? BexValues.UNDEFINED : run.blue.view(run.event, run.graph.logical),
            "timestamp",
            run.timestamp == null
                ? BexValues.UNDEFINED
                : java.math.BigInteger.valueOf(run.timestamp),
            "call",
            call == null ? BexValues.UNDEFINED : run.blue.view(call, run.graph.logical));
    var current = new TreeMap<String, Object>(ContractCanonical.CODE_POINTS);
    contract.forEach(
        (k, v) -> {
          if (!k.startsWith("_")) {
            current.put(k, k.equals("definition") ? run.blue.view(v, run.graph.logical) : v);
          }
        });
    c.currentContract = current;
    c.steps = steps;
    c.scope = "/";
    c.readDocument =
        (p, v, d) -> {
          return d instanceof ExactNode n
              ? run.graph.logical.read(n, p.equals("/") ? "" : p)
              : run.blue.readBex(d, p);
        };
    c.matcher = run::runtimeMatches;
    c.nodeBlueId = run.graph.logical::nodeBlueId;
    return c;
  }

  Object evaluate(
      String key,
      ExactNode payload,
      Map<String, Object> contract,
      Map<String, Object> steps,
      Object expression,
      ExactNode call) {
    run.meter.owner = key;
    var compiled = run.engine.bex.compile(m("expr", expression));
    var result =
        calculate(
            key,
            payload,
            contract,
            call,
            compiled,
            context(key, payload, contract, steps, call, null),
            m("expr", expression));
    require(result.changeset().isEmpty() && result.events().isEmpty(), "EFFECTFUL_EXPRESSION");
    return result.value();
  }

  BexEngine.Result calculate(
      String key,
      ExactNode payload,
      Map<String, Object> contract,
      ExactNode call,
      BexPlanCompiler.Program compiled,
      BexEngine.Context context,
      Object programKey) {
    java.util.function.Supplier<BexEngine.Result> evaluate =
        () -> run.engine.bex.execute(compiled, context, run.meter, BexEngine.TraceMode.METER_ONLY);
    if (run.engine.calculations == null) {
      return evaluate.get();
    }
    Object declaration = contract.get("_recipe");
    if (declaration == null) {
      return evaluate.get();
    }
    Object binding =
        Arrays.asList(
            key,
            identity(run.state.documents.get(key).body),
            identity(payload),
            run.eventId,
            run.timestamp,
            identity(run.blue.exact(declaration)),
            call == null ? null : identity(call),
            programKey);
    return run.engine.calculations.execute(run, context, binding, evaluate);
  }
}
