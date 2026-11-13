package blue;

import static blue.ContractModel.m;
import static blue.ContractModel.obj;
import static blue.ContractModel.require;
import static blue.ContractModel.str;

import java.util.Map;
import java.util.TreeMap;

/** One admitted handler invocation, including its ordered step slots and channel lifetime. */
final class ContractWorkflow {
  private final ContractRun run;
  private final String key;
  private final ExactNode payload;
  private final ExactNode call;
  private final int generation;
  private final boolean allowTerminating;
  private final Map<String, Object> steps = new TreeMap<>(ContractCanonical.CODE_POINTS);

  ContractWorkflow(
      ContractRun run,
      String key,
      ExactNode payload,
      ExactNode call,
      int generation,
      boolean allowTerminating) {
    this.run = run;
    this.key = key;
    this.payload = payload;
    this.call = call;
    this.generation = generation;
    this.allowTerminating = allowTerminating;
  }

  void execute(Map<String, Object> handler) {
    if ("extension_handler".equals(handler.get("_role"))) {
      executeExtension(handler);
      return;
    }
    int ordinal = 0;
    for (var recipe : run.reg.steps(handler)) {
      if (!run.continuationLive(key, generation, allowTerminating)) {
        return;
      }
      run.meter.owner = key;
      var step = run.reg.declaration(recipe);
      String name = str(step.getOrDefault("name", Integer.toString(ordinal)));
      ordinal++;
      String role = str(step.get("_role"));
      require(!steps.containsKey(name), "DUPLICATE_STEP_NAME");
      SemanticGas.unit(run.meter, "EVAL");
      switch (role) {
        case "compute" -> compute(name, step);
        case "patch_step" -> update(name, step);
        case "emit_step" -> emit(name, step);
        case "terminate_step" -> {
          run.terminate(key, str(step.get("cause")), (String) step.get("reason"));
          return;
        }
        case "extension_step" -> {
          executeExtension(step);
          steps.put(name, Map.of());
        }
        default -> throw new ContractModel.Error("UNSUPPORTED_STEP");
      }
    }
  }

  private void compute(String name, Map<String, Object> step) {
    var definition =
        ContractMetrics.measured(
            "compute.definition", () -> run.expressions.computeDefinition(key, step));
    var program = ContractExpressions.program(step);
    var overlay = new TreeMap<>(program);
    var functions = new TreeMap<String, Object>(ContractCanonical.CODE_POINTS);
    if (definition != null) {
      functions.putAll(obj(definition.program().getOrDefault("functions", Map.of())));
    }
    functions.putAll(obj(step.getOrDefault("functions", Map.of())));
    overlay.put("functions", functions);
    var executionContext =
        ContractMetrics.measured(
            "compute.context",
            () -> run.expressions.context(key, payload, step, steps, call, overlay));
    var compiled =
        ContractMetrics.measured(
            "compute.compile", () -> run.reg.compiled(run.engine.bex, step, program, definition));
    var result =
        ContractMetrics.measured(
            "compute.execute",
            () ->
                run.expressions.calculate(
                    key,
                    payload,
                    step,
                    call,
                    compiled,
                    executionContext,
                    definition == null ? "" : definition.identity()));
    Object value = result.value();
    var effects = ContractEffects.compute(run, result);
    SemanticGas.unit(run.meter, "NEW_SLOT");
    steps.put(name, BexValues.freeze(value));
    run.applyEffects(key, generation, allowTerminating, effects);
  }

  private void update(String name, Map<String, Object> step) {
    require(step.containsKey("changeset"), "MISSING_CHANGESET");
    Object changes =
        run.expressions.evaluate(key, payload, step, steps, step.get("changeset"), call);
    var effects = ContractEffects.update(run, changes);
    SemanticGas.construction(run.meter, 1);
    SemanticGas.unit(run.meter, "NEW_SLOT");
    steps.put(name, BexValues.freeze(m("changeset", effects.changeset())));
    run.applyEffects(key, generation, allowTerminating, effects);
  }

  private void emit(String name, Map<String, Object> step) {
    require(step.containsKey("event"), "MISSING_EVENT");
    Object e = run.expressions.evaluate(key, payload, step, steps, step.get("event"), call);
    SemanticGas.output(run.meter, e);
    run.emit(key, run.runtimeNode(e));
    SemanticGas.unit(run.meter, "NEW_SLOT");
    steps.put(name, BexValues.freeze(e));
  }

  private void executeExtension(Map<String, Object> declaration) {
    var extension = (ContractNative.Installed) declaration.get("_extension");
    var binding = extension.binding;
    var selected = run.reg.declaration(declaration.get("_recipe"));
    ExactNode body = run.state.documents.get(key).body;
    var gas = new ContractNative.RuntimeMeter(run.meter, binding, key);
    var fields = new TreeMap<String, Object>(ContractCanonical.CODE_POINTS);
    for (String k : binding.bodyFields()) {
      if (selected.containsKey(k)) {
        fields.put(k, selected.get(k));
      }
    }
    var context =
        new ContractNative.Context(
            ContractNative.frozen(run.blue.view(payload, null)),
            extension.header(selected),
            obj(ContractNative.frozen(fields)),
            obj(ContractNative.frozen(steps)),
            run.eventId,
            run.timestamp,
            path ->
                ContractNative.access(
                    () -> run.graph.logical.read(body, path.equals("/") ? "" : path)),
            gas,
            call == null ? null : ContractNative.frozen(run.blue.view(call, null)),
            expression -> {
              require(binding.meterMode().equals("live"), "EXPRESSION_REQUIRES_LIVE_METER");
              return ContractNative.access(
                  () ->
                      ContractNative.frozen(
                          run.expressions.evaluate(
                              key, payload, selected, steps, expression, call)));
            });
    Object raw = invoke(extension, context, gas);
    gas.merge(raw instanceof Map ? obj(raw).get("runtimeLedger") : null, false);
    var result = ContractNative.result(raw);
    var effects = ContractEffects.validate(run, result.get("patches"), result.get("events"));
    run.applyEffects(key, generation, allowTerminating, effects);
    if (result.containsKey("termination")
        && run.continuationLive(key, generation, allowTerminating)) {
      var end = obj(result.get("termination"));
      run.terminate(key, str(end.get("cause")), (String) end.get("reason"));
    }
  }

  /** Account for failed callback work before restoring the native or host diagnostic. */
  private static Object invoke(
      ContractNative.Installed extension,
      ContractNative.Context context,
      ContractNative.RuntimeMeter gas) {
    try {
      return extension.implementation.execute(context);
    } catch (ContractNative.AccessFailure e) {
      gas.merge(null, true);
      throw e.failure;
    } catch (BexFailure | ContractModel.Error e) {
      gas.merge(null, true);
      throw e;
    } catch (ContractFault e) {
      gas.merge(null, true);
      throw new ContractModel.Error(e.code(), "detail", e.context());
    } catch (RuntimeException e) {
      gas.merge(null, true);
      throw new ContractModel.Error("RUNTIME_FAILURE", "exception", e.getClass().getSimpleName());
    }
  }
}
