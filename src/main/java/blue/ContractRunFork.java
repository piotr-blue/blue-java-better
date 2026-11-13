package blue;

import static blue.ContractModel.arr;
import static blue.ContractModel.obj;
import static blue.ContractModel.require;

import blue.ContractRun.Batch;
import blue.ContractRun.Phase;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Copy only mutable continuation state. Exact Language nodes and installed capabilities are shared.
 */
@NullMarked
final class ContractRunFork {
  // Wire copies retain BEX integer normalization, nested aliases and host values.
  static @Nullable Map<String, Object> copyRecord(@Nullable Map<String, Object> record) {
    return record == null ? null : obj(BexValues.copy(record));
  }

  static List<Object> copyValues(List<Object> values) {
    return arr(BexValues.copy(values));
  }

  static ContractRun copy(ContractRun accepted) {
    require(accepted.frame == null && accepted.suspended.isEmpty(), "OPEN_LOCAL_FRAME");
    var candidate =
        new ContractRun(
            accepted.engine,
            accepted.input,
            accepted.event,
            accepted.timestamp,
            accepted.admission,
            accepted.meter.limit,
            accepted.meter.localLimits);
    // A speculative chunk inherits the accepted prefix without warming its issuer.
    candidate.invocationWitnesses = accepted.invocationWitnesses.fork();
    candidate.state = accepted.state.copy();
    candidate.invocation = accepted.invocation;
    candidate.call = accepted.call;
    candidate.seeds = List.copyOf(accepted.seeds);
    candidate.admissionEvidence = copyRecord(accepted.admissionEvidence);
    candidate.nativeAdmissionVerified = accepted.nativeAdmissionVerified;
    candidate.externalAdmissions = new TreeMap<>(accepted.externalAdmissions);
    candidate.views = new TreeMap<>(accepted.views);
    candidate.meter.inheritAcceptedPrefix(accepted.meter);
    candidate.events = copyValues(accepted.events);
    candidate.work = new ArrayList<>(accepted.work);
    candidate.publicEvents = copyValues(accepted.publicEvents);
    candidate.writes = copyValues(accepted.writes);
    candidate.changes = copyValues(accepted.changes);
    candidate.lifecycle = accepted.lifecycle.fork();
    candidate.activation = new TreeMap<>(accepted.activation);
    candidate.effectCount = accepted.effectCount;
    candidate.receiving = accepted.receiving;
    candidate.liveReceiving = accepted.liveReceiving;
    candidate.begun = accepted.begun;
    candidate.sparseScheduling = accepted.sparseScheduling;
    candidate.serials = new TreeMap<>(accepted.serials);
    candidate.turns = copyValues(accepted.turns);
    candidate.completedTurns = new HashSet<>(accepted.completedTurns);
    candidate.rounds = copyValues(accepted.rounds);
    candidate.units = copyValues(accepted.units);
    candidate.routed = new HashSet<>(accepted.routed);
    candidate.consumedObservations = new HashSet<>(accepted.consumedObservations);
    candidate.allocation = accepted.allocation;
    candidate.topologyChanged = accepted.topologyChanged;
    candidate.phase = phase(accepted.phase);
    candidate.graph = accepted.graph.fork(candidate.state, candidate.meter, candidate.changes);
    return candidate;
  }

  static Batch batch(Batch b) {
    return new Batch(
        b.source,
        b.serial,
        b.completion,
        copyValues(b.output),
        new ArrayList<>(b.tickets),
        copyRecord(b.after),
        b.roundSeal);
  }

  static @Nullable Phase phase(@Nullable Phase p) {
    if (p == null) {
      return null;
    }
    var n = new Phase();
    n.name = p.name;
    n.allowed = ContractCanonical.keys(p.allowed);
    p.groups.forEach(g -> n.groups.add(ContractCanonical.keys(g)));
    p.initialRoutes.forEach((k, v) -> n.initialRoutes.put(k, new ArrayList<>(v)));
    n.initialization.putAll(p.initialization);
    p.seeds.forEach((k, v) -> n.seeds.put(k, new ArrayList<>(v)));
    p.pending.forEach(
        (k, v) ->
            n.pending.put(k, new ArrayList<>(v.stream().map(ContractRunFork::batch).toList())));
    n.done.addAll(p.done);
    n.idle.addAll(p.idle);
    p.held.forEach(b -> n.held.add(batch(b)));
    p.outside.forEach(b -> n.outside.add(batch(b)));
    n.barrier = p.barrier;
    return n;
  }
}
