package blue;

import static blue.ContractModel.*;
import static blue.ContractRun.*;
import static blue.ContractRunFork.data;

import java.util.*;

/** A thread-confined atomic continuation. Accepted chunks never publish partial business state. */
@SuppressWarnings({"unchecked", "serial", "try"})
final class ContractAttempt {
  ContractRun run;
  private Outcome outcome;
  private ChunkToken token;
  private final Map<String, Candidate> candidates = new HashMap<>();
  private final List<Object> history = new ArrayList<>();
  private boolean finished;
  private Map<String, Object> origin;

  private record Candidate(Map<String, Object> wire, ContractRun run, Outcome outcome) {}

  ContractAttempt(ContractRun run) {
    this.run = run;
    outcome = (Outcome) run.guard(run::beginWork);
  }

  /** Issuer-bound, one-use native capability; explicitly export to cross a process boundary. */
  public static final class ChunkToken {
    private final ContractAttempt issuer;
    private final Map<String, Object> descriptor;

    private ChunkToken(ContractAttempt issuer, Map<String, Object> descriptor) {
      this.issuer = issuer;
      this.descriptor = data(descriptor);
    }

    public String identity() {
      return str(descriptor.get("identity"));
    }

    public Map<String, Object> descriptor() {
      return data(descriptor);
    }

    public Map<String, Object> toData() {
      require(issuer.token == this && !issuer.finished, "STALE_CHUNK_TOKEN");
      return m(
          "format",
          "contracts.chunk-input/completion-1",
          "token",
          descriptor(),
          "input",
          issuer.inputData(),
          "history",
          data(issuer.history));
    }
  }

  public static final class ChunkResult {
    private final Map<String, Object> wire;

    private ChunkResult(Map<String, Object> wire) {
      this.wire = data(wire);
    }

    public String identity() {
      return str(wire.get("identity"));
    }

    public Map<String, Object> toData() {
      return data(wire);
    }
  }

  /** Returns null only after all units have sealed or the attempt has failed atomically. */
  public ChunkToken nextChunk() {
    require(!finished, "ATTEMPT_FINISHED");
    if (outcome != null) return null;
    if (token != null) return token;
    Object ready = run.guard(run::ready);
    if (ready instanceof Outcome o) {
      outcome = o;
      return null;
    }
    if (ready == null) return null;
    @SuppressWarnings("unchecked")
    var members = (Set<String>) ready;
    var p = run.phase;
    var pending = new TreeMap<String, Object>();
    var lifecycle = new TreeMap<String, Object>();
    var seeds = new TreeMap<String, Object>();
    var routes = new TreeMap<String, Object>();
    for (String k : ContractCanonical.keys(members)) {
      pending.put(
          k,
          p.pending.getOrDefault(k, List.of()).stream()
              .map(
                  b ->
                      m(
                          "source",
                          b.source,
                          "serial",
                          b.serial,
                          "completion",
                          b.completion,
                          "output",
                          b.output,
                          "tickets",
                          b.tickets.stream().map(Ticket::wire).toList(),
                          "after",
                          b.after,
                          "roundSeal",
                          b.roundSeal))
              .toList());
      if (p.initialization.containsKey(k)) lifecycle.put(k, p.initialization.get(k));
      seeds.put(
          k, p.seeds.getOrDefault(k, List.of()).stream().map(ContractChannels.Seed::wire).toList());
      routes.put(k, run.initialRoutes(p, k).stream().map(Ticket::wire).toList());
    }
    var deps = new TreeSet<String>(ContractCanonical.CODE_POINTS);
    run.graph.topology().groupEdges(members).stream().forEach(e -> deps.add(e.target()));
    token =
        new ChunkToken(
            this,
            committed(
                "chunk",
                m(
                    "attempt",
                    run.invocation,
                    "unit",
                    new ArrayList<>(ContractCanonical.keys(members)),
                    "phase",
                    p.name,
                    "position",
                    history.size(),
                    "beforeViews",
                    vector(run.state, members),
                    "dependencies",
                    vector(run.state, deps),
                    "pending",
                    pending,
                    "lifecycle",
                    lifecycle,
                    "seeds",
                    seeds,
                    "routes",
                    routes,
                    "activations",
                    new TreeMap<>(run.activation),
                    "profile",
                    run.meter.policy(),
                    "logicalPrefix",
                    run.logicalCommitment(),
                    "runtimeBindings",
                    run.reg.runtimeBindings())));
    return token;
  }

  public ChunkResult execute(ChunkToken selected) {
    require(selected != null && selected == token && !finished, "STALE_CHUNK_TOKEN");
    var candidate = ContractRunFork.copy(run);
    var unit = new TreeSet<String>(ContractCanonical.CODE_POINTS);
    arr(selected.descriptor.get("unit")).forEach(k -> unit.add(str(k)));
    var failed =
        (Outcome)
            candidate.guard(
                () -> {
                  candidate.runUnit(candidate.phase, unit);
                  return null;
                });
    var body =
        m(
            "token",
            selected.identity(),
            "outcome",
            failed == null ? null : failed.observations(),
            "snapshot",
            candidate.state.wire(),
            "events",
            candidate.events,
            "work",
            candidate.work,
            "changes",
            candidate.changes,
            "checkpoints",
            candidate.writes,
            "gas",
            candidate.meter.used,
            "gasTrace",
            candidate.meter.trace,
            "rejected",
            candidate.meter.rejected,
            "turns",
            candidate.turns,
            "rounds",
            candidate.rounds,
            "units",
            candidate.units,
            "logicalPrefix",
            candidate.logicalCommitment());
    if (candidate.meter.finalGas()) body.remove("gasTrace");
    var wire = committed("chunk-result", body);
    candidates.put(str(wire.get("identity")), new Candidate(data(wire), candidate, failed));
    return new ChunkResult(wire);
  }

  public ContractAttempt accept(ChunkToken selected, ChunkResult result) {
    return accept(selected, result.toData());
  }

  public ContractAttempt accept(ChunkToken selected, Map<String, Object> result) {
    require(!finished && outcome == null, "ATTEMPT_FINISHED");
    require(selected != null && selected == token, "STALE_CHUNK_TOKEN");
    return acceptVerified(result);
  }

  public ContractAttempt accept(Map<String, Object> selected, Map<String, Object> result) {
    require(!finished && outcome == null, "ATTEMPT_FINISHED");
    closed(selected, Set.of("format", "token", "input", "history"), Set.of());
    require(
        token != null
            && ContractWire.write(selected.get("token"))
                .equals(ContractWire.write(token.descriptor)),
        "STALE_CHUNK_TOKEN");
    return acceptVerified(result);
  }

  private ContractAttempt acceptVerified(Map<String, Object> result) {
    var fields =
        new HashSet<>(
            Set.of(
                "identity",
                "token",
                "outcome",
                "snapshot",
                "events",
                "work",
                "changes",
                "checkpoints",
                "gas",
                "gasTrace",
                "rejected",
                "turns",
                "rounds",
                "units",
                "logicalPrefix"));
    if (run.meter.finalGas()) fields.remove("gasTrace");
    var wire = data(closed(result, fields, Set.of()));
    require(token.identity().equals(wire.get("token")), "CHUNK_INPUT_MISMATCH");
    var body = new TreeMap<>(wire);
    body.remove("identity");
    require(
        ContractWire.write(committed("chunk-result", body)).equals(ContractWire.write(wire)),
        "INVALID_CHUNK_COMMITMENT");
    var candidate = candidates.get(wire.get("identity"));
    if (candidate == null) {
      var verified = execute(token);
      require(
          ContractWire.write(verified.wire).equals(ContractWire.write(wire)),
          "INVALID_CHUNK_EXECUTION");
      candidate = candidates.get(verified.identity());
    }
    require(
        ContractWire.write(candidate.wire).equals(ContractWire.write(wire)),
        "INVALID_CHUNK_EXECUTION");
    history.add(m("token", token.identity(), "result", wire.get("identity")));
    run = candidate.run;
    outcome = candidate.outcome;
    token = null;
    candidates.clear();
    return this;
  }

  public ContractProcessor.Result finish() {
    var result = new ContractProcessor.Result(finishOutcome());
    result.admissionVerified = run.nativeAdmissionVerified;
    return result;
  }

  private Outcome finishOutcome() {
    require(!finished, "ATTEMPT_FINISHED");
    require(outcome != null || nextChunk() == null, "UNFINISHED_ATTEMPT");
    if (outcome == null) outcome = (Outcome) run.guard(run::finishWork);
    finished = true;
    return outcome;
  }

  public ContractProcessor.Result finishLocal() {
    var result = new ContractProcessor.Result(finishLocalOutcome());
    result.admissionVerified = run.nativeAdmissionVerified;
    return result;
  }

  Outcome finishLocalOutcome() {
    require(!finished, "ATTEMPT_FINISHED");
    require(
        token == null && history.isEmpty() && candidates.isEmpty(),
        "CHUNKED_ATTEMPT_ALREADY_STARTED");
    while (advanceLocal()) {}
    if (outcome == null) outcome = (Outcome) run.guard(run::finishWork);
    finished = true;
    return outcome;
  }

  /** One canonical scheduling unit, without exporting or replaying a local continuation. */
  boolean advanceLocal() {
    require(!finished, "ATTEMPT_FINISHED");
    require(
        token == null && history.isEmpty() && candidates.isEmpty(),
        "CHUNKED_ATTEMPT_ALREADY_STARTED");
    if (outcome != null) return false;
    // Portable chunk descriptors retain their existing unit sequence. Only in-process
    // execution may omit the physical queue visits for zero-work branches.
    run.sparseScheduling = true;
    Object ready = run.guard(run::ready);
    if (ready instanceof Outcome failure) {
      outcome = failure;
      return false;
    }
    if (ready == null) return false;
    @SuppressWarnings("unchecked")
    var unit = (Set<String>) ready;
    outcome =
        (Outcome)
            run.guard(
                () -> {
                  run.runUnit(run.phase, unit);
                  return null;
                });
    return outcome == null;
  }

  private Map<String, Object> inputData() {
    require(run.receiving.isEmpty(), "RECEIVING_PORTABLE_EXPORT_UNSUPPORTED");
    if (origin == null) {
      var views = new ArrayList<Object>();
      for (String sid : run.views.values())
        views.add(ContractStorage.export(run.engine, run.engine.archive.versions.get(sid)));
      origin =
          committed(
              "chunk-origin",
              m(
                  "snapshot",
                  ContractStorage.export(run.engine, run.input, false),
                  "event",
                  run.eventId,
                  "timestamp",
                  run.timestamp,
                  "admission",
                  run.admission,
                  "views",
                  views,
                  "content",
                  new TreeMap<>(run.blue.context.evidence()),
                  "policy",
                  run.meter.policy(),
                  "externalEvidence",
                  run.admissionEvidence));
    }
    return data(origin);
  }

  static ChunkResult execute(ContractProcessor engine, ChunkToken token) {
    require(token.issuer.run.engine == engine, "WRONG_CHUNK_EXECUTOR");
    return token.issuer.execute(token);
  }

  static ChunkResult execute(ContractProcessor engine, Map<String, Object> token) {
    closed(token, Set.of("format", "token", "input", "history"), Set.of());
    require(
        "contracts.chunk-input/completion-1".equals(token.get("format")),
        "INCOMPATIBLE_CHUNK_FORMAT");
    var attempt = ContractPortable.restoreOrigin(engine, obj(token.get("input")));
    for (Object raw : arr(token.get("history"))) {
      var row = closed(raw, Set.of("token", "result"), Set.of());
      var current = attempt.nextChunk();
      require(
          current != null && current.identity().equals(row.get("token")), "INVALID_CHUNK_HISTORY");
      var result = attempt.execute(current);
      require(result.identity().equals(row.get("result")), "INVALID_CHUNK_HISTORY");
      attempt.accept(current, result);
    }
    var current = attempt.nextChunk();
    require(
        current != null
            && ContractWire.write(current.descriptor)
                .equals(ContractWire.write(token.get("token"))),
        "CHUNK_INPUT_MISMATCH");
    return attempt.execute(current);
  }
}
