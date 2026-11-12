package blue;

import static blue.ContractLanguage.ABSENT;
import static blue.ContractLanguage.identity;
import static blue.ContractLanguage.recipe;
import static blue.ContractModel.arr;
import static blue.ContractModel.closed;
import static blue.ContractModel.digest;
import static blue.ContractModel.integer;
import static blue.ContractModel.m;
import static blue.ContractModel.obj;
import static blue.ContractModel.pointer;
import static blue.ContractModel.prefix;
import static blue.ContractModel.require;
import static blue.ContractModel.segments;
import static blue.ContractModel.str;
import static blue.ContractRegistry.EXECUTABLE;
import static blue.ContractRegistry.MARKERS;

import blue.ContractModel.Document;
import blue.ContractModel.Edge;
import blue.ContractModel.EdgeKey;
import blue.ContractModel.Life;
import blue.ContractModel.Meter;
import blue.ContractModel.Outcome;
import blue.ContractModel.Snapshot;
import blue.ExactNode.Ref;
import blue.ExactNode.Value;
import blue.LanguageModel.Recipe;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

final class ContractRun {
  final ContractProcessor engine;
  final ContractBridge blue;
  // Survives continuation boundaries, but never crosses an invocation boundary.
  ContractLanguage.Witnesses invocationWitnesses = new ContractLanguage.Witnesses();
  final ContractRegistry reg;
  final Snapshot input;
  Snapshot state;
  final ExactNode event;
  final Long timestamp;
  final String eventId;
  String invocation;
  ExactNode call;
  Map<String, Object> callRecord;
  List<ContractChannels.Seed> seeds = List.of();
  final boolean admission;
  Map<String, Object> admissionEvidence = new TreeMap<>();
  // Each verified projection is deeply immutable and belongs only to this invocation.
  Map<String, Map<String, Object>> externalAdmissions = new TreeMap<>();
  boolean requireNativeAdmission;
  boolean nativeAdmissionVerified;
  final Meter meter;
  Map<String, String> views;
  ContractGraph graph;
  List<Object> events = new ArrayList<>(),
      work = new ArrayList<>(),
      publicEvents = new ArrayList<>(),
      writes = new ArrayList<>();
  Set<String> initializing = new HashSet<>(), terminating = new HashSet<>();
  Map<String, String> initialized = new HashMap<>();
  Set<String> initializationCandidates;
  Map<String, List<String>> termination = new HashMap<>();
  Map<String, Integer> activation = new TreeMap<>();
  int effectCount;

  ContractRun(
      ContractProcessor engine,
      Snapshot state,
      ExactNode event,
      Long timestamp,
      boolean admission) {
    this(engine, state, event, timestamp, admission, engine.gasLimit, engine.localLimits);
  }

  ContractRun(
      ContractProcessor engine,
      Snapshot state,
      ExactNode event,
      Long timestamp,
      boolean admission,
      long gasLimit,
      Map<String, Long> localLimits) {
    this(engine, state, event, timestamp, admission, gasLimit, localLimits, event);
  }

  ContractRun(
      ContractProcessor engine,
      Snapshot state,
      ExactNode event,
      Long timestamp,
      ExactNode commandRecord) {
    this(
        engine, state, event, timestamp, false, engine.gasLimit, engine.localLimits, commandRecord);
  }

  private ContractRun(
      ContractProcessor engine,
      Snapshot state,
      ExactNode event,
      Long timestamp,
      boolean admission,
      long gasLimit,
      Map<String, Long> localLimits,
      ExactNode eventRecord) {
    integer(gasLimit, 0, engine.maximumGas());
    this.engine = engine;
    blue = engine.blue;
    reg = engine.registry;
    input = state;
    this.state = state.copy();
    this.event = event;
    this.timestamp = timestamp;
    eventId = eventRecord == null ? null : identity(eventRecord);
    if (eventRecord != null && eventRecord != event) blue.remember(eventRecord);
    this.admission = admission;
    meter =
        new Meter(
            gasLimit,
            localLimits,
            state.documents.keySet(),
            !engine.bindings.contractsEdition().finalGas());
    meter.deterministic = engine.bindings.contractsEdition().deterministic();
    meter.schedule = engine.bindings.gasSchedule();
    views = new TreeMap<>(engine.archive.bindings());
    invocation =
        digest(
            engine.bindings.contractsEdition().deterministic()
                ? "contracts.attempt/2"
                : "contracts.invocation/completion-1",
            m(
                "input",
                state.identity(),
                "event",
                eventId,
                "timestamp",
                timestamp,
                "gas",
                meter.policy(),
                "release",
                reg.specification));
    graph = new ContractGraph(this.state, reg, engine.archive.restrict(views), meter, invocation);
    if (eventId != null) {
      graph.logical.carriedRoots.add(eventId);
      // Admission binds the original identity before this invocation opens its scope.
      // Preserve that witness for an inline event exactly as opening its Ref would.
      if (event instanceof Value) invocationWitnesses.put(event, eventId);
    }
    // Unchanged members have activation zero; store only actual lifetime changes.
    graph.changes = changes;
  }

  void sealInvocation() {
    if (!engine.bindings.contractsEdition().deterministic()) return;
    if (receiving.isEmpty()) {
      invocation =
          digest(
              "contracts.invocation/2",
              m(
                  "input",
                  input.identity(),
                  "event",
                  eventId,
                  "timestamp",
                  timestamp,
                  "gas",
                  meter.policy(),
                  "release",
                  reg.specification,
                  "admissions",
                  externalAdmissions));
    } else {
      invocation =
          digest(
              "contracts.receiving/2",
              m(
                  "input",
                  input.identity(),
                  "mode",
                  liveReceiving ? "LIVE" : "HISTORICAL",
                  "sources",
                  receiving.stream().map(r -> r.descriptor().get("identity")).toList(),
                  "policy",
                  meter.policy(),
                  "admissions",
                  externalAdmissions));
    }
    graph.cause = invocation;
  }

  ExactNode runtimeNode(Object value) {
    return runtimeNode(value, true);
  }

  ExactNode runtimeNode(Object value, boolean certify) {
    if (frame != null) meter.owner = frame.key;
    return blue.node(value, "value", certify, graph.logical);
  }

  boolean runtimeMatches(Object value, Object pattern) {
    if (frame != null) meter.owner = frame.key;
    SemanticGas.unit(meter, "MATCH");
    return blue.matches(value, pattern, graph.logical);
  }

  Outcome fail(String status, Object diagnostic) {
    var out = new Outcome(status, input.copy(), input.identity(), invocation);
    out.events = new ArrayList<>(events);
    out.work = new ArrayList<>(work);
    out.gas = meter.used;
    out.gasByMember = Map.copyOf(meter.localUsed);
    out.gasTrace = new ArrayList<>(meter.trace);
    out.rejected = meter.rejected;
    out.diagnostic = diagnostic;
    return out;
  }

  List<ContractCompletions.Record> receiving = List.of();
  boolean liveReceiving;
  boolean begun;
  boolean sparseScheduling;

  void begin() {
    if (begun) return;
    begun = true;
    meter.contracts("processInvocation");
    meter.contracts("closureInvocation");

    engine.validate(input, false);
    if (!admission && (receiving.isEmpty() || liveReceiving)) {
      prepareAdmissions();
      require(!requireNativeAdmission || nativeAdmissionVerified, "MISSING_ADMISSION_VERIFIER");
      sealInvocation();
      var c = ContractChannels.recognize(reg, event);
      call = c.event();
      callRecord = c.record();
      seeds = ContractChannels.select(this);
    } else sealInvocation();
    meter.owner = "";
  }

  Outcome attempt() {
    return new ContractAttempt(this).finishLocalOutcome();
  }

  Outcome beginWork() {
    begin();
    if (!receiving.isEmpty()) {
      long total = 0;
      var covered = new HashSet<String>();
      for (var source : receiving) {
        total = Math.addExact(total, source.outcome().gas);
        covered.addAll(source.outcome().gasByMember.keySet());
      }
      var ancestors = new HashSet<String>(covered);
      var pendingAncestors = new ArrayDeque<>(covered);
      while (!pendingAncestors.isEmpty()) {
        String member = pendingAncestors.pop();
        for (Edge edge : graph.topology().incomingEdges(member))
          if (ancestors.add(edge.source())) pendingAncestors.push(edge.source());
      }
      boolean interleaved = seeds.stream().anyMatch(seed -> !ancestors.contains(seed.document()));
      if (total > meter.remaining() || interleaved) {
        ContractMetrics.count("contracts.sourceCompletionReplayed");
        var replay = new ContractRun(engine, input, event, timestamp, admission);
        replay.admissionEvidence.putAll(admissionEvidence);
        return replay.attempt();
      }
    }
    // Historical receiving is seedless structural adoption. Terminated handlers
    // stay silent; ordinary PROCESS and LIVE still reject terminal roots.
    if (graph.terminated(state.root) && (receiving.isEmpty() || liveReceiving))
      return fail("TERMINATED", null);
    if (receiving.isEmpty()) phase = newPhase("INITIALIZE", null, false);
    else ContractCompletions.install(this, receiving, liveReceiving);
    return null;
  }

  Set<String> ready() {
    while (phase != null) {
      var unit = nextUnit(phase);
      if (unit != null) return unit;
      phase =
          phase.name.equals("INITIALIZE") && !admission && receiving.isEmpty()
              ? newPhase("PROCESS", null, false)
              : null;
    }
    return null;
  }

  Outcome finishWork() {
    graph.finalizeGraph();

    state.revision = integer(input.revision + 1);
    if (topologyChanged) state.generation = integer(input.generation + 1);
    engine.validate(state, true);
    for (Object e : events)
      if (obj(e).get("document").equals(state.root)) {
        meter.owner = state.root;
        meter.contracts("rootEventRecorded");
        publicEvents.add(e);
      }
    var out = new Outcome("APPLIED", state, input.identity(), invocation);
    out.publicEvents = publicEvents;
    out.events = events;
    out.work = work;
    out.changes = changes;
    out.checkpoints = writes;
    out.gas = meter.used;
    out.gasByMember = Map.copyOf(meter.localUsed);
    out.gasTrace = meter.trace;
    out.completions = turns;
    out.rounds = rounds;
    out.units = units;
    blue.retain(state.documents.values().stream().map(d -> d.body).toList());
    engine.archive.add(state);
    engine.completions.remember(input, out, eventId, timestamp);
    return out;
  }

  Object guard(java.util.function.Supplier<?> action) {
    var previous = blue.witnessScope.get();
    if (meter.deterministic) blue.witnessScope.set(invocationWitnesses);
    try {
      return action.get();
    } catch (BexFailure e) {
      if (e.code().equals("HOST_INCOMPLETE"))
        throw new ContractModel.Error("HOST_INCOMPLETE", "resource", e.getMessage());
      return fail(
          e.code().equals("GAS_LIMIT_EXCEEDED") ? "GAS_LIMIT_EXCEEDED" : "FAILED",
          m("code", e.code().equals("GAS_LIMIT_EXCEEDED") ? e.code() : "BEX_" + e.code()));
    } catch (ContractModel.Error e) {
      if (e.code.equals("HOST_INCOMPLETE")) throw e;
      var diagnostic = m("code", e.code);
      diagnostic.putAll(e.context);
      if (e.code.equals("NEEDS_RESOURCES")) {
        var out = new Outcome("NEEDS_RESOURCES", input.copy(), input.identity(), invocation);
        out.diagnostic = diagnostic;
        return out;
      }
      return fail("FAILED", diagnostic);
    } catch (LanguageFailure e) {
      var translated = blue.translate(e);
      if (translated.code.equals("HOST_INCOMPLETE")) throw translated;
      if (translated.code.equals("NEEDS_RESOURCES")) {
        var out = new Outcome("NEEDS_RESOURCES", input.copy(), input.identity(), invocation);
        out.diagnostic = m("code", translated.code);
        obj(out.diagnostic).putAll(translated.context);
        return out;
      }
      var diagnostic = m("code", translated.code);
      diagnostic.putAll(translated.context);
      return fail("FAILED", diagnostic);
    } finally {
      if (previous == null) blue.witnessScope.remove();
      else blue.witnessScope.set(previous);
    }
  }

  List<Map.Entry<String, Map<String, Object>>> ordered(Map<String, Map<String, Object>> cs) {
    return graph.logical.ordered(cs);
  }

  static Map<String, Object> program(Map<String, Object> step) {
    var out = new TreeMap<String, Object>(ContractCanonical.CODE_POINTS);
    for (String k : List.of("expr", "do", "constants", "functions"))
      if (step.containsKey(k)) out.put(k, step.get(k));
    return out;
  }

  record Definition(String identity, Map<String, Object> program) {}

  Definition computeDefinition(String key, Map<String, Object> step) {
    if (!step.containsKey("definition")) return null;
    Object operand = step.get("definition");
    var h = blue.head(operand);
    if (h.value != ABSENT) {
      require(
          h.value instanceof String && !((String) h.value).isEmpty(), "INVALID_COMPUTE_DEFINITION");
      String name = str(h.value);
      require(!name.isEmpty(), "INVALID_COMPUTE_DEFINITION");
      String path = name.startsWith("/") ? name : "/contracts/" + BexValues.escape(name);
      Recipe selected = recipe(state.documents.get(key).body);
      for (String part : segments(path)) {
        for (ExactNode node : selected.parts()) graph.logical.open(node);
        meter.contracts("objectMemberRead");
        selected = blue.child(selected, part);
        require(selected != null, "MISSING_COMPUTE_DEFINITION", "path", path);
      }
      operand = selected;
    }
    engine.requireComputeDefinition(operand);
    h = blue.head(operand);
    require(
        h.value == ABSENT
            && h.items == null
            && Set.of("constants", "functions", "specification", "order")
                .containsAll(h.fields.keySet()),
        "INVALID_COMPUTE_DEFINITION");
    var out = new TreeMap<String, Object>(ContractCanonical.CODE_POINTS);
    for (String field : List.of("constants", "functions")) {
      var ch = blue.child(operand, field);
      if (ch != null) {
        // Resolving a function definition is representation work; executed BEX is metered.
        Object v = reg.definitionValue(ch);
        require(v instanceof Map, "INVALID_COMPUTE_DEFINITION", "field", field);
        out.put(field, v);
      }
    }
    return new Definition(identity(blue.exact(operand)), out);
  }

  boolean overlays(Object v) {
    if (v instanceof List<?> a) return a.stream().anyMatch(this::overlays);
    if (v instanceof Map<?, ?>) {
      var m = obj(v);
      return m.containsKey("$resultValue")
          || m.entrySet().stream()
              .anyMatch(e -> !e.getKey().equals("$literal") && overlays(e.getValue()));
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
    ExactNode body = state.documents.get(key).body;
    Object view =
        overlays(overlayProgram == null ? program(contract) : overlayProgram)
            ? blue.view(body, graph.logical)
            : body;
    var c = new BexEngine.Context();
    c.document = view;
    c.resolvedDocument = view;
    c.event = blue.view(payload, graph.logical);
    c.bindings =
        m(
            "processingEvent",
            event == null ? BexValues.UNDEFINED : blue.view(event, graph.logical),
            "timestamp",
            timestamp == null ? BexValues.UNDEFINED : java.math.BigInteger.valueOf(timestamp),
            "call",
            call == null ? BexValues.UNDEFINED : blue.view(call, graph.logical));
    var current = new TreeMap<String, Object>(ContractCanonical.CODE_POINTS);
    contract.forEach(
        (k, v) -> {
          if (!k.startsWith("_"))
            current.put(k, k.equals("definition") ? blue.view(v, graph.logical) : v);
        });
    c.currentContract = current;
    c.steps = steps;
    c.scope = "/";
    c.readDocument =
        (p, v, d) -> {
          Object value =
              d instanceof ExactNode n
                  ? graph.logical.read(n, p.equals("/") ? "" : p)
                  : blue.readBex(d, p);
          return value;
        };
    c.matcher = this::runtimeMatches;
    c.nodeBlueId = graph.logical::nodeBlueId;
    return c;
  }

  private Object readerValue(Object value, ProjectionBudget budget, int depth) {
    if (value instanceof ContractBridge.ExactView view)
      return view.bridge.project(view.recipe, budget, depth);
    if (budget != null) budget.value(depth);
    if (value instanceof Map<?, ?> map) {
      var result = new LinkedHashMap<String, Object>();
      map.forEach(
          (key, child) -> {
            if (budget != null) budget.entry(key.toString());
            result.put(key.toString(), readerValue(child, budget, depth + 1));
          });
      return result;
    }
    if (value instanceof List<?> list) {
      var result = new ArrayList<Object>();
      for (Object child : list) result.add(readerValue(child, budget, depth + 1));
      return result;
    }
    if (budget != null) budget.text(value);
    return value;
  }

  Object evaluate(
      String key,
      ExactNode payload,
      Map<String, Object> contract,
      Map<String, Object> steps,
      Object expression,
      ExactNode call) {
    meter.owner = key;
    var compiled = engine.bex.compile(m("expr", expression));
    var result =
        calculate(
            key,
            payload,
            contract,
            steps,
            call,
            compiled,
            context(key, payload, contract, steps, call, null),
            m("expr", expression));
    require(result.changeset().isEmpty() && result.events().isEmpty(), "EFFECTFUL_EXPRESSION");
    return result.value();
  }

  private BexEngine.Result calculate(
      String key,
      ExactNode payload,
      Map<String, Object> contract,
      Map<String, Object> steps,
      ExactNode call,
      BexPlanCompiler.Program compiled,
      BexEngine.Context context,
      Object programKey) {
    java.util.function.Supplier<BexEngine.Result> evaluate =
        () -> engine.bex.execute(compiled, context, meter, BexEngine.TraceMode.METER_ONLY);
    if (engine.calculations == null) return evaluate.get();
    Object declaration = contract.get("_recipe");
    if (declaration == null) return evaluate.get();
    Object binding =
        Arrays.asList(
            key,
            identity(state.documents.get(key).body),
            identity(payload),
            eventId,
            timestamp,
            identity(blue.exact(declaration)),
            call == null ? null : identity(call),
            programKey);
    return engine.calculations.execute(this, context, binding, evaluate);
  }

  void workRecord(String key, String channel, ExactNode payload, String kind) {
    meter.owner = key;
    meter.contracts("closureWorkOccurrenceEnqueued");
    meter.contracts("closureWorkOccurrenceDequeued");
    var row =
        m(
            "ordinal",
            work.size(),
            "document",
            key,
            "channel",
            channel,
            "kind",
            kind,
            "payload",
            identity(payload));
    var commitment = m("invocation", invocation);
    commitment.putAll(row);
    row.put("identity", digest("contracts.work/completion-1", commitment));
    work.add(new ContractCanonical.EncodedRecord(row));
    String counter =
        switch (kind) {
          case "UPDATE" -> "documentUpdateDelivered";
          case "TRIGGERED" -> "triggeredEventDelivered";
          case "EMBEDDED" -> "embeddedEventDelivered";
          case "INITIALIZATION", "TERMINATION" -> "lifecycleDelivered";
          default -> null;
        };
    if (counter != null) meter.contracts(counter);
  }

  void dispatch(String key, String channel, ExactNode payload, String kind) {
    dispatch(key, channel, payload, kind, null, false, null);
  }

  void dispatch(
      String key,
      String channel,
      ExactNode payload,
      String kind,
      String operation,
      boolean allowTerminating,
      ExactNode call) {
    Ticket ticket = frame.ticket;
    var writer = frame.writer;
    if (!kind.equals("EMBEDDED")) frame.ticket = null;
    try {
      dispatchLocal(key, channel, payload, kind, operation, allowTerminating, call);
    } finally {
      frame.ticket = ticket;
      frame.writer = writer;
    }
  }

  void dispatchLocal(
      String key,
      String channel,
      ExactNode payload,
      String kind,
      String operation,
      boolean allowTerminating,
      ExactNode call) {
    if (!live(key) && !(allowTerminating && terminating.contains(key))) return;
    workRecord(key, channel, payload, kind);
    var cs = reg.contracts(state.documents.get(key));
    require(cs.containsKey(channel), "MISSING_HANDLER_CHANNEL");
    if (!DocumentDispatchV1.admit(this, key, channel, payload, kind, operation, call, cs)) return;
    var frozen = new ArrayList<Map.Entry<String, Map<String, Object>>>();
    for (var row : ordered(cs)) {
      var h = row.getValue();
      if (!EXECUTABLE.contains(h.get("_role")) || !h.get("channel").equals(channel)) continue;
      if (h.get("_role").equals("operation")
          && (!kind.equals("EXTERNAL") || operation == null || !row.getKey().equals(operation)))
        continue;
      frozen.add(row);
    }
    int generation = activation.getOrDefault(key, 0);
    for (var row : frozen) {
      if (!continuationLive(key, generation, allowTerminating)) break;
      var h = row.getValue();
      meter.owner = key;
      meter.contracts("handlerCandidateTested");
      if (h.containsKey("event") && !runtimeMatches(payload, h.get("event"))) continue;
      ExactNode declaration = blue.exact(h.get("_recipe"));
      blue.remember(declaration);
      frame.writer =
          m(
              "handler",
              row.getKey(),
              "declaration",
              identity(declaration),
              "channel",
              channel,
              "kind",
              kind,
              "event",
              identity(payload),
              "call",
              call == null ? null : identity(call));
      meter.contracts("handlerCall");
      var steps = new TreeMap<String, Object>(ContractCanonical.CODE_POINTS);
      if (h.get("_role").equals("extension_handler")) {
        executeExtension(key, payload, h, steps, generation, allowTerminating, call);
        continue;
      }
      int i = 0;
      for (Recipe recipe : reg.steps(h)) {
        if (!continuationLive(key, generation, allowTerminating)) break;
        meter.owner = key;
        var step = reg.declaration(recipe);
        String name = str(step.getOrDefault("name", "" + i++)), role = str(step.get("_role"));
        require(!steps.containsKey(name), "DUPLICATE_STEP_NAME");
        SemanticGas.unit(meter, "EVAL");
        switch (role) {
          case "compute" -> {
            var definition =
                ContractMetrics.measured("compute.definition", () -> computeDefinition(key, step));
            var program = program(step);
            var overlay = new TreeMap<>(program);
            var functions = new TreeMap<String, Object>(ContractCanonical.CODE_POINTS);
            if (definition != null)
              functions.putAll(obj(definition.program().getOrDefault("functions", Map.of())));
            functions.putAll(obj(step.getOrDefault("functions", Map.of())));
            overlay.put("functions", functions);
            var executionContext =
                ContractMetrics.measured(
                    "compute.context", () -> context(key, payload, step, steps, call, overlay));
            var compiled =
                ContractMetrics.measured(
                    "compute.compile", () -> reg.compiled(engine.bex, step, program, definition));
            var result =
                ContractMetrics.measured(
                    "compute.execute",
                    () ->
                        calculate(
                            key,
                            payload,
                            step,
                            steps,
                            call,
                            compiled,
                            executionContext,
                            definition == null ? "" : definition.identity()));
            Object value = result.value();
            Object changes = selectedEffect(value, "changeset", result.changeset());
            Object events = selectedEffect(value, "events", result.events());
            changes = effectList(changes, true);
            events = effectList(events, false);
            validateEffects(changes, events);
            SemanticGas.unit(meter, "NEW_SLOT");
            steps.put(name, BexValues.freeze(value));
            for (Object p : arr(changes)) {
              if (!continuationLive(key, generation, allowTerminating)) break;
              patch(key, obj(p));
            }
            for (Object e : arr(events)) {
              if (!continuationLive(key, generation, allowTerminating)) break;
              emit(key, runtimeNode(e));
            }
          }
          case "patch_step" -> {
            require(step.containsKey("changeset"), "MISSING_CHANGESET");
            Object changes = evaluate(key, payload, step, steps, step.get("changeset"), call);
            changes = effectList(changes, true);
            validateEffects(changes, List.of());
            {
              SemanticGas.construction(meter, 1);
              SemanticGas.unit(meter, "NEW_SLOT");
            }
            steps.put(name, BexValues.freeze(m("changeset", changes)));
            for (Object p : arr(changes)) {
              if (!continuationLive(key, generation, allowTerminating)) break;
              patch(key, obj(p));
            }
          }
          case "emit_step" -> {
            require(step.containsKey("event"), "MISSING_EVENT");
            Object e = evaluate(key, payload, step, steps, step.get("event"), call);
            SemanticGas.output(meter, e);
            emit(key, runtimeNode(e));
            SemanticGas.unit(meter, "NEW_SLOT");
            steps.put(name, BexValues.freeze(e));
          }
          case "terminate_step" -> {
            terminate(key, str(step.get("cause")), (String) step.get("reason"));
          }
          case "extension_step" -> {
            executeExtension(key, payload, step, steps, generation, allowTerminating, call);
            steps.put(name, Map.of());
          }
          default -> throw new ContractModel.Error("UNSUPPORTED_STEP");
        }
        if (role.equals("terminate_step")) break;
      }
    }
  }

  private static Object selectedEffect(Object value, String field, Object fallback) {
    if (value instanceof Map<?, ?> map) return map.containsKey(field) ? map.get(field) : fallback;
    if (value instanceof BexValues.HostValue host && host.kind().equals("object")) {
      Object selected = host.get(field);
      return selected == BexValues.UNDEFINED ? fallback : selected;
    }
    return fallback;
  }

  /** Read only effect-container structure; keep exact payload views for the Language boundary. */
  private Object effectList(Object value, boolean patches) {
    boolean lazy = value instanceof BexValues.HostValue;
    if (!lazy
        && (!(value instanceof List<?> list)
            || !patches
            || list.stream().noneMatch(item -> item instanceof BexValues.HostValue))) return value;
    require(BexValues.kind(value).equals("list"), "INVALID_EFFECT_RESULT");
    var result = new ArrayList<Object>();
    for (int i = 0; i < BexValues.size(value); i++) {
      Object item = BexValues.get(value, i);
      if (patches && item instanceof BexValues.HostValue) {
        require(BexValues.kind(item).equals("object"), "INVALID_PATCH");
        var fields = new LinkedHashMap<String, Object>();
        for (String name : BexValues.keys(item)) fields.put(name, BexValues.get(item, name));
        item = fields;
      }
      result.add(item);
    }
    return result;
  }

  void validateEffects(Object patches, Object events) {
    require(patches instanceof List && events instanceof List, "INVALID_EFFECT_RESULT");
    for (Object raw : arr(patches)) {
      var p = obj(raw);
      require(
          Set.of("add", "replace", "remove").contains(p.getOrDefault("op", "")), "INVALID_PATCH");
      boolean remove = p.get("op").equals("remove");
      closed(p, remove ? Set.of("op", "path") : Set.of("op", "path", "val"), Set.of());
      require(!segments(str(p.get("path"))).isEmpty(), "INVALID_PATCH_PATH");
      if (!remove) runtimeNode(p.get("val"), false);
    }
    for (Object e : arr(events)) {
      SemanticGas.output(meter, e);
      runtimeNode(e);
    }
  }

  Object protectedState(ExactNode body) {
    var markers = new TreeMap<String, Object>(ContractCanonical.CODE_POINTS);
    for (String k : MARKERS) {
      ExactNode v = reg.marker(body, k);
      markers.put(k, v == null ? null : identity(v));
    }
    return Arrays.asList(reg.policy(body).identity(), markers);
  }

  void patch(String key, Map<String, Object> patch) {
    effectCount++;
    meter.owner = key;
    String path = str(patch.get("path")), op = str(patch.get("op"));
    Document doc = state.documents.get(key);
    var parts = segments(path);

    var parent = blue.select(doc.body, pointer(parts.subList(0, parts.size() - 1)));
    boolean listParent = parent != null && blue.head(parent).items != null;
    if (listParent && parts.getLast().equals("-") && op.equals("add")) {
      parts.set(parts.size() - 1, "" + blue.head(parent).items.size());
      path = pointer(parts);
    }
    boolean localWrite = SemanticGas.localWrite(this, path, op, patch.get("val"));
    boolean localAppend =
        state.ownerLocal
            && !localWrite
            && graph.append != null
            && graph.append.child() == null
            && key.equals(state.root)
            && !listParent
            && op.equals("add")
            && blue.select(doc.body, path) == null
            && graph.collectionAppend(doc, doc, path);
    boolean initializingChild = graph.append != null && key.equals(graph.append.child());
    if (state.ownerLocal && !localWrite && !localAppend && !initializingChild)
      throw new ContractModel.Error(
          "LOCAL_EXECUTION_UNAVAILABLE", "reason", "PATCH_REQUIRES_GRAPH", "path", path);
    chargePatchBoundary(parts, op.equals("remove"));
    checkManagedAncestors(key, path, parts);
    var beforeR = listParent && op.equals("add") ? null : blue.select(doc.body, path);
    ExactNode before = beforeR == null ? null : blue.exact(beforeR);
    Object protection = protectedState(doc.body);
    ExactNode value = op.equals("remove") ? null : runtimeNode(patch.get("val"), false);
    ExactNode next = blue.setAt(doc.body, path, value, op);
    graph.logical.establish(next, doc.body);
    ExactNode requested = next;
    var repaired =
        Generalization.repair(
            blue,
            doc.body,
            next,
            path,
            reg.policy(doc.body),
            meter,
            n -> graph.logical.validate(n, doc.body),
            n -> graph.logical.establish(n, doc.body));
    next = repaired.body();
    require(protectedState(next).equals(protection), "PROTECTED_STATE_MUTATION", "path", path);
    for (var write : repaired.writes()) {
      chargePatchBoundary(segments(str(write.get("path"))), false);
    }
    if (!repaired.writes().isEmpty()) {
      graph.logical.establish(next, requested);
      localWrite = false;
      if (state.ownerLocal && !initializingChild)
        throw new ContractModel.Error(
            "LOCAL_EXECUTION_UNAVAILABLE", "reason", "GENERALIZATION_REPAIR", "path", path);
    }
    blue.remember(doc.body);
    blue.remember(next);
    frame.writes.add(
        m(
            "writer",
            frame.writer,
            "op",
            op,
            "path",
            path,
            "before",
            identity(doc.body),
            "after",
            identity(next)));
    if (localWrite && !Boolean.getBoolean("blue.physical.fullPatch")) {
      // The patch is disjoint from every declared embedding and cannot change declarations.
      // Full Language validation and Document Update reactions still run.
      state.documents.put(key, doc.withBody(next));
      graph.finalizeLocal(key);
      ContractMetrics.count("contracts.dataTopologyReconciliationSkipped");
    } else {
      var reachable = graph.reachable();
      var changed = doc.withBody(next);
      boolean append =
          before == null
              && !listParent
              && op.equals("add")
              && repaired.writes().isEmpty()
              && graph.collectionAppend(doc, changed, path);
      require(!localAppend || append, "LOCAL_EXECUTION_UNAVAILABLE");
      state.documents.put(key, changed);
      graph.reconcile(eventId, key, List.of(path), append);
      var delta = new HashSet<>(reachable);
      for (String k : graph.reachable()) if (!delta.add(k)) delta.remove(k);
      for (String k : delta) activation.merge(k, 1, Integer::sum);
      allocation = Math.max(allocation, graph.allocation);
      var members = ContractCanonical.keys(graph.added);
      members.add(key);
      ContractChannels.refresh(graph, members);
      graph.finalizeLocal(key);

      creationBarrier();
      graph.finalizeLocal(key);
    }
    emitPatchUpdates(key, path, op, before, repaired.writes());
  }

  private void chargePatchBoundary(List<String> parts, boolean remove) {
    meter.contracts("patchBoundaryChecked");
    for (String ignored : parts) meter.contracts("pointerSegmentTraversed");
    meter.contracts(remove ? "patchRemove" : "patchAddOrReplace");
  }

  /** Only strict ancestors can be managed boundaries. Work follows the pointer's depth. */
  private void checkManagedAncestors(String key, String path, List<String> parts) {
    for (int length = 0; length < parts.size(); length++) {
      require(
          !state.edges.containsKey(new EdgeKey(key, pointer(parts.subList(0, length)))),
          "MANAGED_BOUNDARY_VIOLATION",
          "path",
          path);
    }
  }

  /** Repairs emit in their recorded order before the requested update. A no-op emits neither. */
  private void emitPatchUpdates(
      String key, String path, String op, ExactNode before, List<Map<String, Object>> repairs) {
    var afterR = op.equals("remove") ? null : blue.select(state.documents.get(key).body, path);
    ExactNode after = afterR == null ? null : blue.exact(afterR);
    String beforeId = before == null ? null : identity(before),
        afterId = after == null ? null : identity(after);
    if (Objects.equals(beforeId, afterId)) return;
    if (before != null) blue.remember(before);
    if (after != null) blue.remember(after);
    var payload =
        m(
            "op",
            before == null ? "add" : after == null ? "remove" : "replace",
            "path",
            path,
            "beforePresent",
            before != null,
            "afterPresent",
            after != null);
    if (before != null) payload.put("before", m("blueId", beforeId));
    if (after != null) payload.put("after", m("blueId", afterId));
    for (var write : repairs)
      update(
          key,
          m(
              "op",
              "replace",
              "path",
              write.get("path"),
              "beforePresent",
              true,
              "afterPresent",
              true,
              "before",
              m("blueId", identity((ExactNode) write.get("before"))),
              "after",
              m("blueId", identity((ExactNode) write.get("after")))));
    update(key, payload);
  }

  void update(String key, Map<String, Object> payload) {
    if (!live(key)) return;
    int generation = activation.getOrDefault(key, 0);
    var raw = reg.typed("Document Update");
    raw.putAll(payload);
    SemanticGas.construction(meter, raw.size());
    ExactNode n = runtimeNode(raw);
    var cs = reg.contracts(state.documents.get(key));
    var channels = new ArrayList<String>();
    for (var row : ordered(cs)) {
      var c = row.getValue();
      if (c.get("_role").equals("update")
          && prefix(str(c.getOrDefault("path", "")), str(payload.get("path")))
          && (!c.containsKey("event") || runtimeMatches(n, c.get("event"))))
        channels.add(row.getKey());
    }
    for (String k : channels) {
      if (activation.getOrDefault(key, 0) != generation) break;
      dispatch(key, k, n, "UPDATE");
    }
    if (activation.getOrDefault(key, 0) == generation) customInternal(key, "UPDATE", n, cs, false);
  }

  void terminate(String key, String cause, String reason) {
    if (terminating.contains(key) || graph.terminated(key)) return;
    meter.owner = key;
    meter.contracts("terminationRequested");
    terminating.add(key);
    termination.put(key, Arrays.asList(cause, reason));
    var raw = reg.typed("Document Processing Terminated", "cause", cause);
    if (reason != null) raw.put("reason", reason);
    ExactNode n = runtimeNode(raw);
    for (var row : ordered(reg.contracts(state.documents.get(key)))) {
      var c = row.getValue();
      if (c.get("_role").equals("lifecycle")
          && (!c.containsKey("event") || runtimeMatches(n, c.get("event"))))
        dispatch(key, row.getKey(), n, "TERMINATION", null, true, null);
    }
    customInternal(key, "TERMINATION", n, reg.contracts(state.documents.get(key)), true);
  }

  void directMarker(String key, String name, Object value) {
    var doc = state.documents.get(key);
    Value root = blue.open(doc.body);
    Value cs =
        root.has("contracts")
            ? blue.open((ExactNode) root.get("contracts"))
            : ExactNode.value(Map.of());
    var fields = new TreeMap<>(cs.fields());
    if (value == null) fields.remove(name);
    else fields.put(name, runtimeNode(value, false));
    ExactNode candidate = root.with("contracts", ExactNode.value(fields));
    graph.logical.establish(candidate, doc.body);
    state.documents.put(key, doc.withBody(candidate));
  }

  Frame frame;
  Phase phase;
  Map<String, Integer> serials = new TreeMap<>();
  List<Object> turns = new ArrayList<>(),
      rounds = new ArrayList<>(),
      units = new ArrayList<>(),
      changes = new ArrayList<>();
  Set<String> routed = new HashSet<>(), consumedObservations = new HashSet<>();
  int turnCount, roundCount, allocation;
  boolean topologyChanged;
  List<Frame> suspended = new ArrayList<>();

  record Ticket(
      String parent,
      int parentActivation,
      String path,
      String source,
      int sourceActivation,
      long generation) {
    Map<String, Object> wire() {
      return m(
          "parent",
          parent,
          "parentActivation",
          parentActivation,
          "path",
          path,
          "source",
          source,
          "sourceActivation",
          sourceActivation,
          "generation",
          generation);
    }
  }

  static class Batch {
    String source, completion, roundSeal;
    int serial;
    List<Object> output;
    List<Ticket> tickets;
    Map<String, Object> after;

    Batch(
        String source,
        int serial,
        String completion,
        List<Object> output,
        List<Ticket> tickets,
        Map<String, Object> after,
        String roundSeal) {
      this.source = source;
      this.serial = serial;
      this.completion = completion;
      this.output = output;
      this.tickets = tickets;
      this.after = after;
      this.roundSeal = roundSeal;
    }
  }

  record Pending(String kind, Map<String, Object> occurrence, Ticket ticket, String observation) {}

  static class Frame {
    String key, sourceTurn;
    Document before;
    Map<String, Object> input, writer;
    ArrayDeque<Pending> queue = new ArrayDeque<>();
    List<Object> output = new ArrayList<>(), writes = new ArrayList<>();
    Ticket ticket;
    List<ContractChannels.Seed> consumed = new ArrayList<>();
    List<Object> dispatchDecisions = new ArrayList<>();

    Frame(String key, Document before, Map<String, Object> input, String sourceTurn) {
      this.key = key;
      this.before = before;
      this.input = input;
      this.sourceTurn = sourceTurn;
    }
  }

  static class Phase {
    String name;
    Set<String> allowed;
    List<Set<String>> groups = new ArrayList<>();
    Map<String, List<Ticket>> initialRoutes = new TreeMap<>();
    Map<String, String> initialization = new TreeMap<>();
    Map<String, List<ContractChannels.Seed>> seeds = new TreeMap<>();
    Map<String, List<Batch>> pending = new TreeMap<>();
    Set<List<String>> done = new HashSet<>();
    // Physical zero-work completions; reconsider them when topology or pending work changes.
    Set<List<String>> idle = new HashSet<>();
    List<Map.Entry<List<String>, Boolean>> doneChanges = new ArrayList<>();
    ContractSchedule schedule;
    List<Batch> held = new ArrayList<>(), outside = new ArrayList<>();
    boolean barrier;

    void completed(List<String> id, boolean value) {
      if (value ? done.add(id) : done.remove(id)) doneChanges.add(Map.entry(id, value));
    }
  }

  record LocalResult(
      String key,
      Map<String, Document> documents,
      Map<EdgeKey, Edge> edges,
      Map<String, Object> completion,
      List<Ticket> routes) {}

  static Map<String, Object> committed(String domain, Map<String, Object> row) {
    var out = new LinkedHashMap<>(row);
    out.put("identity", digest("contracts." + domain + "/completion-1", row));
    return out;
  }

  static Map<String, Object> vector(Snapshot state, Collection<String> members) {
    var out = new TreeMap<String, Object>(ContractCanonical.CODE_POINTS);
    var topology = ContractTopology.of(state);
    for (String key : ContractCanonical.keys(members)) {
      var edges = topology.outgoingEdges(key);
      ContractMetrics.add("proof.vector.edgeVisits", edges.size());
      out.put(key, state.documents.get(key).view(edges));
    }
    return obj(ContractWire.immutable(out));
  }

  ContractGraph forkGraph(Snapshot state) {
    var next = new ContractGraph(state, reg, graph.archive, meter, invocation, graph.logical);
    next.append = graph.append;
    next.pathHeaders = graph.pathHeaders;
    next.verifiedBindings = graph.verifiedBindings;
    next.resets = graph.resets;
    next.allocation = allocation;
    next.changes = changes;
    return next;
  }

  List<Ticket> initialRoutes(Phase phase, String member) {
    var existing = phase.initialRoutes.get(member);
    return existing == null ? captureRoutes(member, state) : existing;
  }

  List<Ticket> captureRoutes(String source, Snapshot state) {
    var out = new ArrayList<Ticket>();
    ContractMetrics.count("graph.captureRoutes.calls");
    var routes = ContractTopology.of(state).incomingEdges(source);
    ContractMetrics.add("graph.captureRoutes.edgeVisits", routes.size());
    for (Edge e : routes)
      out.add(
          new Ticket(
              e.source(),
              activation.getOrDefault(e.source(), 0),
              e.path(),
              source,
              activation.getOrDefault(source, 0),
              e.generation()));
    return out;
  }

  boolean ticketLive(Ticket ticket, boolean charge) {
    if (charge) {
      meter.owner = ticket.parent;
      meter.contracts("completionRouteChecked");
    }
    Edge edge = state.edges.get(new EdgeKey(ticket.parent, ticket.path));
    return edge != null
        && edge.target().equals(ticket.source)
        && edge.generation() == ticket.generation
        && activation.getOrDefault(ticket.parent, 0) == ticket.parentActivation
        && activation.getOrDefault(ticket.source, 0) == ticket.sourceActivation;
  }

  boolean continuationLive(String key, int generation, boolean allowTerminating) {
    if (frame != null && frame.ticket != null && !ticketLive(frame.ticket, true)) return false;
    return allowTerminating || live(key) && activation.getOrDefault(key, 0) == generation;
  }

  boolean live(String key) {
    if (frame != null && key.equals(frame.key))
      return !terminating.contains(key) && !graph.terminated(key);
    return graph.reachable(true, terminating).contains(key);
  }

  Phase newPhase(String name, Collection<String> allowed, boolean barrier) {
    graph.observePartition();
    Phase p = new Phase();
    p.name = name;
    p.allowed = ContractCanonical.keys(allowed == null ? graph.reachable() : allowed);
    p.barrier = barrier;
    var active = graph.reachable(true, Set.of());
    for (String k : p.allowed) {
      if (name.equals("INITIALIZE")
          && reg.marker(state.documents.get(k), "initialized") == null
          && !initializing.contains(k)
          && (barrier || active.contains(k)))
        p.initialization.put(k, state.documents.get(k).blueId);
      p.initialRoutes.put(k, captureRoutes(k, state));
      if (name.equals("PROCESS"))
        p.seeds.put(k, seeds.stream().filter(s -> s.document().equals(k)).toList());
    }
    initialized.putAll(p.initialization);
    for (var g : graph.topology().components(p.allowed)) p.groups.add(ContractCanonical.keys(g));
    return p;
  }

  List<Batch> drivePhase(String name, Collection<String> allowed, boolean barrier) {
    Phase previous = phase, p = newPhase(name, allowed, barrier);
    phase = p;
    try {
      Set<String> unit;
      while ((unit = nextUnit(p)) != null) runUnit(p, unit);
      return p.outside;
    } finally {
      phase = previous;
    }
  }

  Set<String> promote(Phase p, Set<String> members) {
    ContractMetrics.count("schedule.promoteCalls");
    return schedule(p).containing(members);
  }

  ContractSchedule schedule(Phase p) {
    var topology = graph.topology();
    if (p.schedule == null || p.schedule.topology != topology)
      p.schedule =
          new ContractSchedule(
              p,
              topology,
              sparseScheduling && !Boolean.getBoolean("blue.physical.sparseScheduleDisabled"));
    return p.schedule;
  }

  Set<List<String>> dependencies(Phase p, Collection<String> group) {
    return schedule(p).dependencies(group);
  }

  Set<String> nextUnit(Phase p) {
    return schedule(p).select(p);
  }

  boolean hasInputs(Phase p, String key) {
    return !p.pending.getOrDefault(key, List.of()).isEmpty()
        || p.initialization.containsKey(key)
        || !p.seeds.getOrDefault(key, List.of()).isEmpty();
  }

  void runUnit(Phase p, Set<String> initial) {

    // A ready singleton without inputs or held output has no local turn. Its exact
    // zero-work proof depends only on the current member view, not on scheduler activity.
    if (initial.size() == 1) {
      String key = initial.iterator().next();
      if (!hasInputs(p, key) && p.held.stream().noneMatch(batch -> batch.source.equals(key))) {
        p.completed(List.of(key), true);
        return;
      }
    }
    long start = meter.position();
    int turnStart = turns.size();
    var before = vector(state, initial);
    Set<String> members = initial;
    while (true) {
      members = promote(p, members);
      var inputs =
          members.stream()
              .sorted(ContractCanonical.CODE_POINTS)
              .filter(k -> hasInputs(p, k))
              .toList();
      if (inputs.isEmpty()) break;
      boolean cyclic =
          members.size() > 1 || members.stream().anyMatch(graph.topology()::selfLinked);
      if (cyclic) {
        roundCount++;
        meter.owner = "";
        meter.contracts("componentRoundOpened");
      }
      Snapshot preceding = state.copy();
      var results = new ArrayList<LocalResult>();
      for (String key : inputs) {
        var incoming = p.pending.remove(key);
        if (incoming == null) incoming = List.of();
        String lifecycle = p.initialization.remove(key);
        var selectedSeeds = p.seeds.remove(key);
        if (selectedSeeds == null) selectedSeeds = List.of();
        boolean earlier =
            turns.stream()
                .anyMatch(
                    t -> {
                      var in = obj(obj(t).get("turnInput"));
                      return in.get("member").equals(key) && in.get("phase").equals(p.name);
                    });
        var routes = earlier ? captureRoutes(key, preceding) : initialRoutes(p, key);
        results.add(
            runLocal(preceding, key, incoming, lifecycle, selectedSeeds, p.name, routes, true));
      }
      results.forEach(this::mergeOwned);
      // Value writes cannot change reachability. Reuse the immutable topology instead of
      // rebuilding aliases and comparing every member after every local turn.
      var previousTopology = ContractTopology.of(preceding);
      if (!previousTopology.matches(state)) {
        var delta = new HashSet<>(previousTopology.live);
        for (String k : graph.reachable()) if (!delta.add(k)) delta.remove(k);
        for (String k : delta) activation.merge(k, 1, Integer::sum);
      }
      members = promote(p, members);
      for (var r : results) graph.dirty.add(r.key);
      graph.finalizeGraph(true, members);

      Map<String, Object> roundSeal = null;
      if (cyclic || members.size() > 1) {
        var existing = new HashSet<>(members);
        existing.retainAll(preceding.documents.keySet());
        var bindings = new ArrayList<Object>();
        for (Edge e : graph.topology().groupEdges(members)) bindings.add(e.wire());
        roundSeal =
            committed(
                "round-result",
                m(
                    "beforeViews",
                    vector(preceding, existing),
                    "afterViews",
                    vector(state, members),
                    "ordinal",
                    rounds.size(),
                    "turns",
                    results.stream().map(r -> r.completion.get("identity")).toList(),
                    "members",
                    vector(state, members),
                    "bindings",
                    bindings));
        rounds.add(roundSeal);
      }
      for (var r : results) {
        var completion = r.completion;
        p.held.add(
            new Batch(
                r.key,
                ((Number) obj(completion.get("turnInput")).get("turnSerial")).intValue(),
                str(completion.get("identity")),
                arr(completion.get("output")),
                r.routes,
                vector(state, List.of(r.key)),
                roundSeal == null ? null : str(roundSeal.get("identity"))));
      }
      for (Batch b : new ArrayList<>(p.held)) routeBatch(p, b, members, true);
      if (!p.done.containsAll(dependencies(p, members))) return;
    }
    var id = List.copyOf(ContractCanonical.keys(members));
    p.completed(id, true);
    var output = new ArrayList<Object>();
    for (Batch b : new ArrayList<>(p.held))
      if (members.contains(b.source)) {
        b.after = vector(state, List.of(b.source));
        routeBatch(p, b, members, false);
        output.add(m("source", b.source, "completion", b.completion, "output", b.output));
      }
    var after = vector(state, members);
    if (members.size() == 1
        && turnStart == turns.size()
        && start == meter.position()
        && output.isEmpty()
        && before.equals(after)) {
      return;
    }
    units.add(
        committed(
            "unit-result",
            m(
                "members",
                id,
                "beforeViews",
                before,
                "afterViews",
                after,
                "turnResults",
                turns.subList(turnStart, turns.size()).stream()
                    .map(t -> obj(t).get("identity"))
                    .toList(),
                "output",
                output,
                meter.segmentField(),
                meter.segment(start))));
  }

  void routeBatch(Phase p, Batch b, Set<String> members, boolean internal) {
    var grouped = new TreeMap<String, List<Ticket>>(ContractCanonical.CODE_POINTS);
    for (Ticket t : b.tickets) {
      if (members.contains(t.parent) != internal || internal && b.output.isEmpty()) continue;
      String id = b.completion + digest("ticket", t.wire());
      if (!routed.add(id)) continue;
      grouped.computeIfAbsent(t.parent, k -> new ArrayList<>()).add(t);
    }
    grouped.forEach(
        (parent, tickets) -> {
          Batch selected =
              new Batch(
                  b.source,
                  b.serial,
                  b.completion,
                  b.output,
                  List.copyOf(tickets),
                  b.after,
                  internal ? b.roundSeal : null);
          if (p.allowed.contains(parent)) {
            p.pending.computeIfAbsent(parent, k -> new ArrayList<>()).add(selected);
            if (p.schedule != null) {
              var id = p.schedule.owner.get(parent);
              if (id != null) p.completed(id, false);
            } else
              for (var g : p.groups)
                if (g.contains(parent)) p.completed(List.copyOf(ContractCanonical.keys(g)), false);
          } else p.outside.add(selected);
        });
  }

  List<Batch> sortedBatches(List<Batch> incoming) {
    return incoming.stream()
        .sorted(
            Comparator.comparing((Batch b) -> b.source, ContractCanonical.CODE_POINTS)
                .thenComparingInt(b -> b.serial))
        .toList();
  }

  void installInputs(String key, List<Batch> incoming) {
    Document document = state.documents.get(key);
    ExactNode previous = document.body;
    boolean indexed = !incoming.isEmpty() && !Boolean.getBoolean("blue.physical.inputsFullScan");
    Map<Edge, String> established = new HashMap<>();
    var links = document.finalizedLinks;
    if (!indexed
        && links != null
        && links.body() == previous
        && links.blueId().equals(document.blueId)) {
      for (int i = 0; i < links.edges().size(); i++)
        established.put(links.edges().get(i), links.targets().get(i));
    }
    var replacements = new TreeMap<String, ExactNode>(ContractCanonical.CODE_POINTS);
    Collection<Edge> selectedEdges;
    if (indexed) {
      var affected = new TreeMap<EdgeKey, Edge>();
      for (Batch batch : incoming)
        for (Ticket ticket : batch.tickets) {
          if (!ticket.parent.equals(key) || !ticketLive(ticket, true)) continue;
          Edge edge = state.edges.get(new EdgeKey(key, ticket.path));
          if (edge != null) affected.put(edge.key(), edge);
        }
      selectedEdges = affected.values();
    } else selectedEdges = graph.topology().orderedOutgoingEdges(key);
    for (Edge e : selectedEdges) {
      ContractMetrics.count("contracts.inputEdgesVisited");
      String target = state.documents.get(e.target()).blueId;
      if (target.equals(established.get(e))) continue;
      ExactNode node = blue.exact(blue.select(previous, e.path()));
      if (!identity(node).equals(target)) {
        meter.owner = key;
        meter.contracts("containingReferenceUpdated");
        replacements.put(e.path(), new Ref(target));
      }
    }
    if (!replacements.isEmpty()) {
      ExactNode candidate = blue.substitute(previous, replacements);
      graph.logical.establish(candidate, previous);
      state.documents.put(key, document.withBody(candidate));
    }
    graph.finalizeLocal(key);
    for (Batch b : sortedBatches(incoming)) {
      meter.owner = key;
      meter.contracts("completionBatchInstalled");
      var tickets =
          b.tickets.stream()
              .sorted(
                  (a, z) -> {
                    int c = ContractCanonical.compareSegments(segments(a.path), segments(z.path));
                    return c == 0 ? Long.compare(a.generation, z.generation) : c;
                  })
              .filter(t -> ticketLive(t, true))
              .toList();
      for (Object e : b.output)
        for (Ticket t : tickets) {
          var occurrence = obj(e);
          String observation =
              digest(
                  "contracts.embedded-observation/completion-1",
                  m("source", occurrence.get("identity"), "ticket", t.wire()));
          if (!consumedObservations.contains(observation))
            frame.queue.add(new Pending("EMBEDDED", occurrence, t, observation));
        }
    }
  }

  LocalResult runLocal(
      Snapshot sealed,
      String key,
      List<Batch> incoming,
      String lifecycle,
      List<ContractChannels.Seed> selectedSeeds,
      String phaseName,
      List<Ticket> routes,
      boolean completionRequired) {
    Snapshot savedState = state;
    ContractGraph savedGraph = graph;
    Frame savedFrame = frame;
    boolean savedSemantic = meter.semantic;
    meter.semantic = true;
    var savedActivation = activation;
    activation = new TreeMap<>(activation);
    state = sealed.copyForLocalTurn(key);
    graph = forkGraph(state);
    long start = meter.position();
    int changesStart = changes.size(), serial = serials.getOrDefault(key, 0);
    serials.put(key, serial + 1);
    var dep = new TreeSet<String>(ContractCanonical.CODE_POINTS);
    for (Edge e : ContractTopology.of(sealed).outgoingEdges(key)) dep.add(e.target());
    var inputBatches = new ArrayList<Object>();
    var semanticBatches = new ArrayList<Object>();
    for (Batch b : sortedBatches(incoming)) {
      var row = m("kind", b.roundSeal != null ? "CYCLIC" : "DAG", "sourceCompletion", b.completion);
      if (b.roundSeal != null) row.put("roundSeal", b.roundSeal);
      row.put("tickets", b.tickets.stream().map(Ticket::wire).toList());
      inputBatches.add(row);
      semanticBatches.add(
          m(
              "kind",
              b.roundSeal != null ? "CYCLIC" : "DAG",
              "source",
              b.source,
              "serial",
              b.serial,
              "after",
              b.after,
              "output",
              b.output,
              "tickets",
              b.tickets.stream().map(Ticket::wire).toList()));
    }
    var descriptor =
        m(
            "member",
            key,
            "activation",
            activation.getOrDefault(key, 0),
            "beforeView",
            vector(sealed, List.of(key)),
            "profile",
            meter.policy().get("profile"),
            "cause",
            admission
                ? m("kind", "INITIALIZE")
                : m("kind", "PROCESS", "event", eventId, "timestamp", timestamp),
            "phase",
            phaseName,
            "turnSerial",
            serial,
            "dependencies",
            vector(sealed, dep),
            "incoming",
            inputBatches,
            "seeds",
            selectedSeeds.stream().map(ContractChannels.Seed::wire).toList(),
            "lifecycle",
            lifecycle == null
                ? m("kind", "NONE")
                : m("kind", "INITIALIZE", "document", m("blueId", lifecycle)));
    String sourceTurn =
        ContractLocal.sourceTurn(sealed, key, phaseName, serial, descriptor, incoming);
    if (completionRequired) descriptor.put("logicalContext", logicalCommitment());
    frame = new Frame(key, sealed.documents.get(key).copy(), descriptor, sourceTurn);
    suspended.add(frame);
    try {
      turnCount++;
      meter.owner = key;
      meter.contracts("localTurnOpened");
      if (reg.preparations != null && reg.preparations.get(state.documents.get(key)) != null)
        blue.context.openRetainedMember(state.documents.get(key).body);
      installInputs(key, incoming);
      if (lifecycle != null && live(key)) {
        initializing.add(key);
        meter.owner = key;
        meter.contracts("scopeInitialization");
        SemanticGas.construction(meter, 2);
        ExactNode n =
            runtimeNode(
                reg.typed("Document Processing Initiated", "document", m("blueId", lifecycle)));
        // Appendix C run_local freezes declarations for this initialization delivery.
        var declarations = reg.contracts(state.documents.get(key));
        for (var row : ordered(declarations)) {
          var d = row.getValue();
          if (d.get("_role").equals("lifecycle")
              && (!d.containsKey("event") || runtimeMatches(n, d.get("event"))))
            dispatch(key, row.getKey(), n, "INITIALIZATION");
        }
        customInternal(key, "INITIALIZATION", n, declarations, false);
      }
      drain();

      for (var seed : selectedSeeds) {
        var doc = state.documents.get(key);
        Life life = doc.channelLives.get(seed.channel());
        if (live(key)
            && activation.getOrDefault(key, 0) == 0
            && life != null
            && life.generation() == seed.generation()) {
          dispatch(key, seed.channel(), event, "EXTERNAL", seed.operation(), false, call);
          drain();
          frame.consumed.add(seed);
        }
      }
      settleLocal(key, lifecycle);
      graph.finalizeLocal(key);

      require(frame.queue.isEmpty(), "NONQUIESCENT_LOCAL_RESULT");
      var finalized = state.documents.get(key);
      state.documents.put(key, finalized.finalized(finalized.body));
      meter.owner = key;
      meter.contracts("localTurnSealed");
      var retired = new ArrayList<Object>();
      for (Batch b : incoming)
        for (Ticket t : b.tickets) if (!ticketLive(t, false)) retired.add(t.wire());
      Map<String, Object> completion = Map.of();
      if (completionRequired) {
        completion =
            committed(
                "turn-result",
                dispatchEvidence(
                    m(
                        "turnInput",
                        descriptor,
                        "afterView",
                        vector(state, List.of(key)),
                        "output",
                        new ArrayList<>(frame.output),
                        "memberMetadata",
                        state.documents.get(key).metadata(),
                        "edgeChanges",
                        new ArrayList<>(changes.subList(changesStart, changes.size())),
                        "consumedSeeds",
                        frame.consumed.stream().map(ContractChannels.Seed::wire).toList(),
                        "writes",
                        new ArrayList<>(frame.writes),
                        "retiredTickets",
                        retired,
                        meter.segmentField(),
                        meter.segment(start))));
        turns.add(completion);
      }
      var owned = new HashSet<>(state.documents.changedValueKeys(sealed.documents));
      owned.removeIf(sealed.documents::containsKey);
      owned.add(key);
      var docs = new TreeMap<String, Document>(ContractCanonical.CODE_POINTS);
      var edges = new TreeMap<EdgeKey, Edge>();
      for (String k : owned) {
        docs.put(k, state.documents.get(k).copy());
        for (Edge edge : state.outgoingEdges(k)) edges.put(edge.key(), edge);
      }
      return new LocalResult(key, docs, edges, completion, routes);
    } finally {
      allocation = Math.max(allocation, graph.allocation);
      topologyChanged |= graph.topologyChanged;
      suspended.removeLast();
      state = savedState;
      graph = savedGraph;
      frame = savedFrame;
      meter.semantic = savedSemantic;
      activation = savedActivation;
    }
  }

  Map<String, Object> dispatchEvidence(Map<String, Object> completion) {
    if (!frame.dispatchDecisions.isEmpty())
      completion.put("dispatchDecisions", new ArrayList<>(frame.dispatchDecisions));
    return completion;
  }

  String logicalCommitment() {
    var l = graph.logical;
    return digest(
        "contracts.logical-prefix/completion-1",
        m(
            "gas",
            meter.commitment(),
            "work",
            work,
            "manifests",
            l.manifests.encoding(),
            "established",
            l.established.encoding(),
            "proofs",
            l.proofs.encoding(),
            "partition",
            l.partitionEncoding(),
            "boundIdentities",
            l.boundIdentities.encoding(),
            "exactValues",
            l.exactEncoding(),
            "carried",
            l.carriedRoots.encoding()));
  }

  void mergeOwned(LocalResult r) {
    state.documents.putAll(r.documents);
    for (var d : r.documents.values()) if (d.body instanceof Value) blue.note(d.body, d.blueId);
    for (String member : r.documents.keySet())
      for (Edge edge : state.outgoingEdges(member))
        if (!edge.equals(r.edges.get(edge.key()))) state.edges.remove(edge.key());
    state.edges.putAll(r.edges);
    graph.allocation = allocation;
    graph.refreshAliases();
  }

  void settleLocal(String key, String lifecycle) {
    if (lifecycle != null && reg.marker(state.documents.get(key), "initialized") == null) {
      meter.owner = key;
      meter.contracts("processorMarkerWritten");
      directMarker(
          key,
          "initialized",
          reg.typed("Processing Initialized Marker", "document", m("blueId", lifecycle)));
    }
    if (termination.containsKey(key)
        && reg.marker(state.documents.get(key), "terminated") == null) {
      meter.owner = key;
      meter.contracts("processorMarkerWritten");
      var end = termination.get(key);
      var raw = reg.typed("Processing Terminated Marker", "cause", end.getFirst());
      if (end.get(1) != null) raw.put("reason", end.get(1));
      directMarker(key, "terminated", raw);
    }
    var doc = state.documents.get(key);
    ExactNode marker = reg.marker(doc, "checkpoint");
    var entries =
        new TreeMap<String, Object>(
            marker == null
                ? Map.of()
                : obj(reg.markerRecord(marker, "checkpoint").getOrDefault("entries", Map.of())));
    var before = new TreeMap<>(entries);
    entries
        .keySet()
        .removeIf(
            c -> !doc.channelLives.containsKey(c) || graph.resets.contains(new EdgeKey(key, c)));
    for (var seed : frame.consumed) {
      Life life = doc.channelLives.get(seed.channel());
      if (life != null && life.generation() == seed.generation())
        entries.put(seed.channel(), m("timestamp", timestamp, "event", m("blueId", eventId)));
    }
    var channels = ContractCanonical.keys(before.keySet());
    channels.addAll(entries.keySet());
    for (String c : channels)
      if (!Objects.equals(before.get(c), entries.get(c))) {
        meter.owner = key;
        meter.contracts("checkpointWritten");
        writes.add(
            m("document", key, "channel", c, "before", before.get(c), "after", entries.get(c)));
      }
    if (!before.equals(entries))
      directMarker(
          key,
          "checkpoint",
          entries.isEmpty() ? null : reg.typed("Channel Event Checkpoint", "entries", entries));
  }

  void emit(String key, ExactNode node) {
    meter.owner = key;
    graph.logical.establish(node);
    meter.contracts("internalEventEnqueued");
    var occurrence =
        committed(
            "event",
            m(
                "sourceTurn",
                frame.sourceTurn,
                "ordinal",
                frame.output.size(),
                "event",
                identity(node)));
    frame.output.add(occurrence);
    events.add(
        m(
            "ordinal",
            events.size(),
            "identity",
            occurrence.get("identity"),
            "document",
            key,
            "event",
            occurrence.get("event"),
            "sourceTurn",
            occurrence.get("sourceTurn"),
            "sourceOrdinal",
            occurrence.get("ordinal")));
    frame.queue.add(new Pending("LOCAL", occurrence, null, str(occurrence.get("identity"))));
  }

  void drain() {
    String key = frame.key;
    while (!frame.queue.isEmpty()) {
      Pending p = frame.queue.remove();
      meter.owner = key;
      if (p.kind.equals("LOCAL")) meter.contracts("internalEventDequeued");
      else {
        consumedObservations.add(p.observation);
        if (!ticketLive(p.ticket, true)) continue;
      }
      if (!live(key)) continue;
      ExactNode n = new Ref(str(p.occurrence.get("event")));
      var cs = reg.contracts(state.documents.get(key));
      frame.ticket = p.ticket;
      try {
        if (p.kind.equals("LOCAL")) {
          for (var row : ordered(cs)) {
            if (!live(key)) break;
            var d = row.getValue();
            if (d.get("_role").equals("triggered")
                && (!d.containsKey("event") || runtimeMatches(n, d.get("event"))))
              dispatch(key, row.getKey(), n, "TRIGGERED");
          }
          customInternal(key, "TRIGGERED", n, cs, false);
        } else {
          ExactNode payload =
              runtimeNode(
                  reg.typed(
                      "Embedded Event Delivery",
                      "sourcePath",
                      p.ticket.path,
                      "event",
                      m("blueId", p.occurrence.get("event"))));
          for (var row : ordered(cs)) {
            if (!ticketLive(p.ticket, false) || !live(key)) break;
            var d = row.getValue();
            if (!d.get("_role").equals("embedded_event")
                || d.containsKey("sourcePath") && !d.get("sourcePath").equals(p.ticket.path)
                || d.containsKey("event") && !runtimeMatches(n, d.get("event"))) continue;
            dispatch(key, row.getKey(), payload, "EMBEDDED");
          }
          customInternal(key, "EMBEDDED", payload, cs, false);
        }
      } finally {
        frame.ticket = null;
      }
    }
  }

  private Set<String> initializationCandidates() {
    if (!blue.context.nativeReuse() || Boolean.getBoolean("blue.physical.nativeDisabled"))
      return graph.reachable();
    if (initializationCandidates == null) {
      initializationCandidates = new HashSet<>();
      // Input markers were validated by begin() and cannot be removed by a document patch.
      for (var entry : input.documents.entrySet())
        if (reg.marker(entry.getValue(), "initialized") == null)
          initializationCandidates.add(entry.getKey());
    }
    initializationCandidates.addAll(graph.added);
    // Keep candidates throughout the attempt: private local frames may hide or initialize them
    // before their results are merged into the enclosing snapshot.
    return initializationCandidates;
  }

  void creationBarrier() {
    Set<String> candidates = initializationCandidates();
    if (candidates.isEmpty()) return;
    Frame creator = frame;
    var missing = new TreeSet<String>(ContractCanonical.CODE_POINTS);
    Set<String> reachable = graph.reachable();
    for (String k : candidates)
      if (reachable.contains(k)
          && reg.marker(state.documents.get(k), "initialized") == null
          && !initializing.contains(k)
          && suspended.stream().noneMatch(f -> f.key.equals(k))) missing.add(k);
    if (missing.isEmpty()) return;
    Snapshot privateState = state;
    ContractGraph privateGraph = graph;
    state = privateState.copy();
    for (Frame f : suspended) state.documents.put(f.key, f.before.copy());
    graph = forkGraph(state);
    List<Batch> batches;
    try {
      batches = drivePhase("INITIALIZE", missing, true);
      var owned = new HashSet<>(state.documents.keySet());
      owned.removeAll(privateState.documents.keySet());
      owned.addAll(missing);
      for (String k : owned) privateState.documents.put(k, state.documents.get(k).copy());
      privateState.edges.entrySet().removeIf(e -> owned.contains(e.getValue().source()));
      state.edges.forEach(
          (k, e) -> {
            if (owned.contains(e.source())) privateState.edges.put(k, e);
          });
    } finally {
      state = privateState;
      graph = privateGraph;
      graph.allocation = allocation;
    }
    frame = creator;
    installInputs(
        creator.key,
        batches.stream()
            .filter(b -> b.tickets.stream().anyMatch(t -> t.parent.equals(creator.key)))
            .toList());
    if (phase != null)
      for (Batch b : batches)
        for (String parent : b.tickets.stream().map(t -> t.parent).distinct().toList())
          if (!parent.equals(creator.key))
            phase.pending.computeIfAbsent(parent, k -> new ArrayList<>()).add(b);
    graph.refreshAliases();
  }

  void prepareAdmissions() {
    var rows = new TreeMap<String, List<Object>>(ContractCanonical.CODE_POINTS);
    var runtimes = new TreeMap<String, ContractNative.Installed>(ContractCanonical.CODE_POINTS);
    for (String member : graph.reachable(true, Set.of())) {
      var doc = input.documents.get(member);
      for (var row : reg.contracts(doc).entrySet()) {
        var d = row.getValue();
        if (!d.get("_role").equals("extension_external")) continue;
        var extension = (ContractNative.Installed) d.get("_extension");
        if (!extension.implementation.verifiesAdmission()) continue;
        String id = extension.binding.typeId();
        runtimes.put(id, extension);
        rows.computeIfAbsent(id, k -> new ArrayList<>())
            .add(ContractReception.receiver(reg, member, doc, row.getKey(), d));
      }
    }
    require(runtimes.keySet().containsAll(admissionEvidence.keySet()), "UNUSED_ADMISSION_EVIDENCE");
    for (var row : runtimes.entrySet()) {
      var extension = row.getValue();
      var projection =
          obj(BexValues.freeze(extension.verifyAdmission(this, rows.get(row.getKey()))));
      if (engine.bindings.contractsEdition().deterministic()) ContractCanonical.text(projection);
      externalAdmissions.put(row.getKey(), projection);
    }
    nativeAdmissionVerified = !externalAdmissions.isEmpty();
  }

  void customInternal(
      String key,
      String kind,
      ExactNode node,
      Map<String, Map<String, Object>> cs,
      boolean allowTerminating) {
    for (var row : ordered(cs)) {
      var d = row.getValue();
      if (!d.get("_role").equals("extension_channel")) continue;
      if (!continuationLive(key, activation.getOrDefault(key, 0), allowTerminating)) return;
      var extension = (ContractNative.Installed) d.get("_extension");
      if (!extension.implementation.families().contains(kind)) continue;
      meter.owner = key;
      meter.contracts("channelCandidateTested");
      var header = extension.header(d);
      Object event = ContractNative.frozen(blue.bexData(node));
      var peers = extension.peers(d, cs);
      if (extension.implementation.acceptInternal(header, kind, event, peers)) {
        meter.contracts("channelAccepted");
        Object value = extension.implementation.channelizeInternal(header, kind, event, peers);
        dispatch(key, row.getKey(), runtimeNode(value), kind, null, allowTerminating, null);
      }
    }
  }

  void executeExtension(
      String key,
      ExactNode payload,
      Map<String, Object> declaration,
      Map<String, Object> steps,
      int generation,
      boolean allowTerminating,
      ExactNode call) {
    var extension = (ContractNative.Installed) declaration.get("_extension");
    var binding = extension.binding;
    var selected = reg.declaration(declaration.get("_recipe"));
    ExactNode body = state.documents.get(key).body;
    var gas = new ContractNative.RuntimeMeter(meter, binding, key);
    var fields = new TreeMap<String, Object>(ContractCanonical.CODE_POINTS);
    for (String k : binding.bodyFields())
      if (selected.containsKey(k)) fields.put(k, selected.get(k));
    var context =
        new ContractNative.Context(
            ContractNative.frozen(blue.view(payload, null)),
            extension.header(selected),
            obj(ContractNative.frozen(fields)),
            obj(ContractNative.frozen(steps)),
            eventId,
            timestamp,
            path ->
                ContractNative.access(
                    () ->
                        ContractNative.frozen(
                            readerValue(
                                graph.logical.read(body, path.equals("/") ? "" : path), null, 0))),
            gas,
            call == null ? null : ContractNative.frozen(blue.view(call, null)),
            expression -> {
              require(binding.meterMode().equals("live"), "EXPRESSION_REQUIRES_LIVE_METER");
              return ContractNative.access(
                  () ->
                      ContractNative.frozen(
                          evaluate(key, payload, selected, steps, expression, call)));
            });
    Object raw;
    try {
      raw = extension.implementation.execute(context);
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
    gas.merge(raw instanceof Map ? obj(raw).get("runtimeLedger") : null, false);
    var result = ContractNative.result(raw);
    validateEffects(result.get("patches"), result.get("events"));
    for (Object p : arr(result.get("patches"))) {
      if (!continuationLive(key, generation, allowTerminating)) return;
      patch(key, obj(p));
    }
    for (Object e : arr(result.get("events"))) {
      if (!continuationLive(key, generation, allowTerminating)) return;
      emit(key, runtimeNode(e));
    }
    if (result.containsKey("termination") && continuationLive(key, generation, allowTerminating)) {
      var end = obj(result.get("termination"));
      terminate(key, str(end.get("cause")), (String) end.get("reason"));
    }
  }
}
