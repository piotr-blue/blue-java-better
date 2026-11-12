package blue;

import static blue.ContractLanguage.identity;
import static blue.ContractModel.HOST_LIMIT;
import static blue.ContractModel.arr;
import static blue.ContractModel.digest;
import static blue.ContractModel.integer;
import static blue.ContractModel.m;
import static blue.ContractModel.obj;
import static blue.ContractModel.require;
import static blue.ContractModel.str;
import static blue.ContractRun.vector;

import blue.ContractModel.Document;
import blue.ContractModel.Edge;
import blue.ContractModel.Outcome;
import blue.ContractModel.Snapshot;
import blue.ContractRun.Batch;
import blue.ContractRun.Ticket;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Protected owner execution. The ordinary interpreter runs; unrelated owners are never restored.
 */
final class ContractLocal {
  record Result(Snapshot before, Outcome outcome) {}

  static Result process(
      ContractProcessor engine,
      Map<String, Object> checkpoint,
      List<Map<String, Object>> attachments,
      ExactNode event,
      long timestamp) {
    return process(engine, checkpoint, attachments, event, timestamp, Map.of());
  }

  static Result process(
      ContractProcessor engine,
      Map<String, Object> checkpoint,
      List<Map<String, Object>> attachments,
      ExactNode event,
      long timestamp,
      Map<String, Object> evidence) {
    return process(engine, checkpoint, attachments, event, timestamp, evidence, null);
  }

  static Result process(
      ContractProcessor engine,
      Map<String, Object> checkpoint,
      List<Map<String, Object>> attachments,
      ExactNode event,
      long timestamp,
      Map<String, Object> evidence,
      java.util.function.Function<BlueId, Map<String, Object>> liveMember) {
    require(
        engine.gasLimit == HOST_LIMIT && engine.localLimits.isEmpty(),
        "LOCAL_EXECUTION_UNAVAILABLE");
    Snapshot before = ContractLocalCheckpoint.restore(engine, checkpoint);
    engine.blue.context.retainLocalClosure(identity(event));
    String owner = before.root;
    var run = new ContractRun(engine, before, event, timestamp, false);
    if (attachments.isEmpty() && liveMember != null) {
      run.graph.append = new ContractBirth(engine, owner, liveMember);
    }
    run.admissionEvidence.putAll(evidence);
    Outcome outcome = (Outcome) run.guard(() -> processOwner(run, before, checkpoint, attachments));
    // Failure/rollback across independently advanced sources needs the ordinary whole invocation.
    require(
        outcome.status.equals("APPLIED"),
        "LOCAL_EXECUTION_UNAVAILABLE",
        "diagnostic",
        outcome.diagnostic);
    require(
        engine.registry.marker(outcome.snapshot.documents.get(owner), "terminated") == null,
        "LOCAL_EXECUTION_UNAVAILABLE");
    return new Result(before, outcome);
  }

  private static Outcome processOwner(
      ContractRun run,
      Snapshot before,
      Map<String, Object> checkpoint,
      List<Map<String, Object>> attachments) {
    String owner = before.root;
    // The host establishes ordering. Native channels still authorize the original entry.
    run.prepareAdmissions();
    run.sealInvocation();
    var call = ContractChannels.recognize(run.engine.registry, run.event);
    run.call = call.event();
    run.callRecord = call.record();
    run.seeds = ContractChannels.select(run);
    List<Batch> incoming = sourceBatches(run, before, attachments);
    run.graph = run.forkGraph(run.state);
    var after = before.copy();
    if (!incoming.isEmpty() || !run.seeds.isEmpty()) {
      var local =
          run.runLocal(run.state, owner, incoming, null, run.seeds, "PROCESS", List.of(), true);
      after.documents.put(owner, local.documents().get(owner));
      if (run.graph.append != null && run.graph.append.child() != null) {
        run.graph.append.finish(local, after);
      }
    }
    after.revision++;
    var result = new Outcome("APPLIED", after, str(checkpoint.get("identity")), run.invocation);
    result.gas = run.meter.used;
    result.gasByMember = Map.copyOf(run.meter.localUsed);
    result.events = run.events;
    result.publicEvents =
        run.events.stream().filter(e -> owner.equals(obj(e).get("document"))).toList();
    result.completions = run.turns;
    result.checkpoints = run.writes;
    result.changes = run.changes;
    result.work = run.work;
    return result;
  }

  /**
   * Pin every selected edge to its original generation and child predecessor before importing a
   * completion.
   */
  private static List<Batch> sourceBatches(
      ContractRun run, Snapshot before, List<Map<String, Object>> attachments) {
    var engine = run.engine;
    String owner = before.root;
    var selected = new LinkedHashMap<String, Map<String, Object>>();
    var tickets = new HashMap<String, List<Ticket>>();
    Set<String> paths = new HashSet<>();
    for (var attachment : attachments) {
      String path = str(attachment.get("path"));
      require(paths.add(path), "DUPLICATE_LOCAL_ATTACHMENT");
      var source = obj(attachment.get("completion"));
      require(
          identity(run.event).equals(source.get("event"))
              && integer(source.get("timestamp")) == run.timestamp,
          "DIFFERENT_SOURCE_CAUSE");
      var sourceBefore = obj(source.get("before"));
      var sourceAfter = obj(source.get("after"));
      var old = obj(sourceBefore.get("member"));
      var next = obj(sourceAfter.get("member"));
      String child = str(next.get("key"));
      require(child.equals(old.get("key")) && !child.equals(owner), "LOCAL_EXECUTION_UNAVAILABLE");
      long generation = integer(attachment.get("generation"));
      Document parent = before.documents.get(owner);
      require(parent.edgeSerials.getOrDefault(path, 0L) == generation, "STALE_LOCAL_ATTACHMENT");
      require(
          identity(engine.blue.exact(engine.blue.select(parent.body, path)))
              .equals(old.get("blueId")),
          "SOURCE_PREDECESSOR_MISMATCH");
      Map<String, Object> previous = selected.putIfAbsent(child, source);
      require(previous == null || previous.equals(source), "INCOMPATIBLE_SOURCE_COMPLETIONS");
      tickets
          .computeIfAbsent(child, key -> new ArrayList<>())
          .add(new Ticket(owner, 0, path, child, 0, generation));
      var edge = new Edge(owner, path, child, generation);
      run.state.edges.put(edge.key(), edge);
    }
    // Multiple distinct sources require whole-graph replay to resolve overlap and ordering.
    require(selected.size() <= 1, "LOCAL_EXECUTION_UNAVAILABLE");
    if (selected.isEmpty()) {
      return List.of();
    }
    var entry = selected.entrySet().iterator().next();
    return adoptSource(run, owner, entry.getKey(), entry.getValue(), tickets.get(entry.getKey()));
  }

  private static List<Batch> adoptSource(
      ContractRun run,
      String owner,
      String child,
      Map<String, Object> source,
      List<Ticket> tickets) {
    var incoming = new ArrayList<Batch>();
    var engine = run.engine;
    var after = ContractLocalCheckpoint.restore(engine, obj(source.get("after")));
    run.state.documents.put(child, after.documents.get(child));
    var observations = obj(source.get("observations"));
    require(
        "APPLIED".equals(observations.get("status"))
            && ("owner".equals(observations.get("executionBoundary"))
                || ContractBirth.canAdopt(source, observations, owner, child))
            && run.input.gasSchedule.id().equals(observations.get("gasSchedule")),
        "LOCAL_EXECUTION_UNAVAILABLE");
    chargeSourceGas(run, owner, observations);
    for (Object raw : arr(observations.get("events"))) {
      var occurrence = new LinkedHashMap<>(obj(raw));
      occurrence.put("ordinal", run.events.size());
      engine.blue.context.retainLocalClosure(str(occurrence.get("event")));
      run.events.add(occurrence);
    }
    for (Object raw : arr(observations.get("completions"))) {
      var turn = obj(raw);
      var input = obj(turn.get("turnInput"));
      if (!child.equals(input.get("member"))) {
        continue;
      }
      incoming.add(
          new Batch(
              child,
              (int) integer(input.get("turnSerial")),
              str(turn.get("identity")),
              arr(turn.get("output")),
              tickets,
              vector(after, List.of(child)),
              null));
    }
    require(!incoming.isEmpty(), "LOCAL_EXECUTION_UNAVAILABLE");
    ContractMetrics.count("contracts.local.adoptedSources");
    return incoming;
  }

  /** Verify the entire allocation before debiting any part of the source completion. */
  private static void chargeSourceGas(
      ContractRun run, String owner, Map<String, Object> observations) {
    var amounts = obj(observations.get("gasByMember"));
    require(!amounts.containsKey(owner), "LOCAL_EXECUTION_UNAVAILABLE");
    long total = 0;
    for (var amount : amounts.entrySet()) {
      long units = integer(amount.getValue(), 0, HOST_LIMIT);
      require(units <= HOST_LIMIT - total, "LOCAL_EXECUTION_UNAVAILABLE");
      total += units;
    }
    require(
        total == integer(observations.get("gas")) && total <= run.meter.remaining(),
        "LOCAL_EXECUTION_UNAVAILABLE");
    for (var amount : amounts.entrySet()) {
      run.meter.owner = amount.getKey();
      run.meter.debit("composition", "sourceCompletion", 1, integer(amount.getValue()));
    }
  }

  static String sourceTurn(
      Snapshot state,
      String member,
      String phase,
      int serial,
      Map<String, Object> descriptor,
      List<Batch> incoming) {
    var batches =
        incoming.stream()
            .sorted(
                Comparator.comparing((Batch b) -> b.source, ContractCanonical.CODE_POINTS)
                    .thenComparingInt(b -> b.serial))
            .map(
                b -> {
                  var source = obj(obj(b.after.get(b.source)).get("document"));
                  return m(
                      "source",
                      b.source,
                      "serial",
                      b.serial,
                      "blueId",
                      source.get("blueId"),
                      "output",
                      b.output,
                      "tickets",
                      b.tickets.stream().map(Ticket::wire).toList(),
                      "roundSeal",
                      b.roundSeal);
                })
            .toList();
    return digest(
        "contracts.semantic-turn/1",
        m(
            "member",
            member,
            "before",
            state.documents.get(member).blueId,
            "phase",
            phase,
            "serial",
            serial,
            "cause",
            descriptor.get("cause"),
            "seeds",
            descriptor.get("seeds"),
            "lifecycle",
            descriptor.get("lifecycle"),
            "incoming",
            batches));
  }
}
