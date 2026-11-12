package blue;

import static blue.ContractLanguage.finalId;
import static blue.ContractLanguage.identity;
import static blue.ContractModel.DEFAULT_LIMIT;
import static blue.ContractModel.arr;
import static blue.ContractModel.closed;
import static blue.ContractModel.digest;
import static blue.ContractModel.integer;
import static blue.ContractModel.m;
import static blue.ContractModel.obj;
import static blue.ContractModel.require;
import static blue.ContractModel.str;
import static blue.ContractRun.committed;

import blue.ContractModel.Meter;
import blue.ContractModel.Snapshot;
import blue.ExactNode.Ref;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Explicit verified serialization boundaries; no request can install a runtime implementation. */
@SuppressWarnings({"unchecked", "serial", "try"})
final class ContractPortable {
  static Meter policyMeter(long shared, Map<String, Long> locals, Snapshot snapshot) {
    var meter = new Meter(shared, locals, snapshot.documents.keySet());
    meter.deterministic =
        snapshot.release.equals(ContractsEdition.DETERMINISTIC.specificationBlueId());
    meter.schedule = snapshot.gasSchedule;
    return meter;
  }

  static ContractProcessor.GasPolicy policy(Object raw, Snapshot snapshot) {
    var p = closed(raw, Set.of("shared", "local", "profile"), Set.of());
    var locals = new TreeMap<String, Long>();
    obj(p.get("local")).forEach((k, v) -> locals.put(k, integer(v)));
    long maximum = DEFAULT_LIMIT;
    long shared = integer(p.get("shared"), 0, maximum);
    require(
        ContractWire.write(policyMeter(shared, locals, snapshot).policy())
            .equals(ContractWire.write(p)),
        "INVALID_GAS_POLICY");
    return new ContractProcessor.GasPolicy(shared, locals);
  }

  static Map<String, String> restoreViews(ContractProcessor engine, Object raw) {
    var selected = new TreeMap<String, String>();
    for (Object row : arr(raw)) {
      var wire = obj(row);
      var s = ContractStorage.restore(engine, wire, str(wire.get("identity")));
      String id = s.documents.get(s.root).blueId;
      engine.archive.choices.put(id, s.identity());
      selected.put(id, s.identity());
    }
    return selected;
  }

  static ExactNode event(ContractProcessor engine, Object content, String id) {
    engine.blue.context.addEvidence(obj(content));
    var event = new Ref(finalId(id));
    engine.blue.certify(event);
    engine.blue.refresh();
    return event;
  }

  static Map<String, Object> exportInvocation(
      ContractProcessor engine, ContractProcessor.Document document, Object event, long time) {
    integer(time);
    ExactNode original =
        event instanceof ExactNode n ? engine.blue.node(n) : engine.blue.source(event);
    var views = new ArrayList<Object>();
    for (String sid : engine.archive.bindings().values())
      views.add(ContractStorage.export(engine, engine.archive.versions.get(sid)));
    var data =
        m(
            "input",
            ContractStorage.export(engine, document.state),
            "event",
            m("blueId", identity(original)),
            "eventTimestamp",
            time,
            "views",
            views,
            "content",
            engine.blue.context.records(original),
            "gasPolicy",
            policyMeter(engine.gasLimit, engine.localLimits, document.state).policy());
    return m("invocation", data, "identity", digest("contracts.capsule/completion-1", data));
  }

  static ContractProcessor.Invocation importInvocation(
      ContractProcessor engine, Map<String, Object> capsule, String expected) {
    closed(capsule, Set.of("invocation", "identity"), Set.of());
    require(
        expected.equals(capsule.get("identity"))
            && digest("contracts.capsule/completion-1", capsule.get("invocation")).equals(expected),
        "INVALID_CAPSULE_COMMITMENT");
    var data =
        closed(
            capsule.get("invocation"),
            Set.of("input", "event", "eventTimestamp", "views", "content", "gasPolicy"),
            Set.of());
    long time = integer(data.get("eventTimestamp"));
    var e = closed(data.get("event"), Set.of("blueId"), Set.of());
    var original = event(engine, data.get("content"), str(e.get("blueId")));
    restoreViews(engine, data.get("views"));
    var input = obj(data.get("input"));
    var snapshot = ContractStorage.restore(engine, input, str(input.get("identity")));
    return new ContractProcessor.Invocation(
        new ContractProcessor.Document(snapshot),
        original,
        time,
        policy(data.get("gasPolicy"), snapshot));
  }

  static ContractAttempt restoreOrigin(ContractProcessor engine, Map<String, Object> data) {
    closed(
        data,
        Set.of(
            "identity",
            "snapshot",
            "event",
            "timestamp",
            "admission",
            "views",
            "content",
            "policy"),
        Set.of("externalEvidence"));
    var body = new TreeMap<>(data);
    body.remove("identity");
    require(
        ContractWire.write(committed("chunk-origin", body)).equals(ContractWire.write(data)),
        "INVALID_CHUNK_ORIGIN");
    require(data.get("admission") instanceof Boolean, "INVALID_RECORD");
    boolean admission = (Boolean) data.get("admission");
    require(
        admission
            ? data.get("event") == null && data.get("timestamp") == null
            : data.get("event") instanceof String && data.get("timestamp") instanceof Number,
        "INVALID_RECORD");
    var views = restoreViews(engine, data.get("views"));
    var input = obj(data.get("snapshot"));
    var snapshot = ContractStorage.restore(engine, input, str(input.get("identity")), false);
    engine.blue.context.addEvidence(obj(data.get("content")));
    ExactNode event = admission ? null : event(engine, data.get("content"), str(data.get("event")));
    var policy = policy(data.get("policy"), snapshot);
    var run =
        new ContractRun(
            engine,
            snapshot,
            event,
            admission ? null : integer(data.get("timestamp")),
            admission,
            policy.sharedLimit(),
            policy.localLimits());
    run.views = views;
    run.graph =
        new ContractGraph(
            run.state, run.reg, engine.archive.restrict(views), run.meter, run.invocation);
    if (run.eventId != null) run.graph.logical.carriedRoots.add(run.eventId);
    run.graph.changes = run.changes;
    run.admissionEvidence =
        ContractRunFork.data(obj(data.getOrDefault("externalEvidence", Map.of())));
    return new ContractAttempt(run);
  }
}
