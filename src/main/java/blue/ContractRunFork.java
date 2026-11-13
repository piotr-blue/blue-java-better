package blue;

import static blue.ContractModel.arr;
import static blue.ContractModel.obj;
import static blue.ContractModel.require;

import blue.ContractRun.Batch;
import blue.ContractRun.Phase;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Copy only mutable continuation state. Exact Language nodes and installed capabilities are shared.
 */
final class ContractRunFork {
  // Wire copies retain BEX integer normalization, nested aliases and host values.
  static Map<String, Object> copyRecord(Map<String, Object> record) {
    return record == null ? null : obj(BexValues.copy(record));
  }

  static List<Object> copyValues(List<Object> values) {
    return arr(BexValues.copy(values));
  }

  static ContractRun copy(ContractRun r) {
    require(r.frame == null && r.suspended.isEmpty(), "OPEN_LOCAL_FRAME");
    var n =
        new ContractRun(
            r.engine,
            r.input,
            r.event,
            r.timestamp,
            r.admission,
            r.meter.limit,
            r.meter.localLimits);
    // A speculative chunk inherits the accepted prefix without warming its issuer.
    n.invocationWitnesses = r.invocationWitnesses.fork();
    n.state = r.state.copy();
    n.invocation = r.invocation;
    n.call = r.call;
    n.callRecord = copyRecord(r.callRecord);
    n.seeds = List.copyOf(r.seeds);
    n.admissionEvidence = copyRecord(r.admissionEvidence);
    n.nativeAdmissionVerified = r.nativeAdmissionVerified;
    n.externalAdmissions = new TreeMap<>(r.externalAdmissions);
    n.views = new TreeMap<>(r.views);
    n.meter.owner = r.meter.owner;
    n.meter.used = r.meter.used;
    n.meter.hostUsed = r.meter.hostUsed;
    n.meter.trace = new ArrayList<>(r.meter.trace.size());
    r.meter.trace.forEach(row -> n.meter.trace.add(copyRecord(row)));
    n.meter.rejected = copyRecord(r.meter.rejected);
    n.meter.copyLocalUsed(r.meter);
    n.events = copyValues(r.events);
    n.work = new ArrayList<>(r.work);
    n.publicEvents = copyValues(r.publicEvents);
    n.writes = copyValues(r.writes);
    n.changes = copyValues(r.changes);
    n.initializing = new HashSet<>(r.initializing);
    n.terminating = new HashSet<>(r.terminating);
    n.initialized = new HashMap<>(r.initialized);
    n.initializationCandidates =
        r.initializationCandidates == null ? null : new HashSet<>(r.initializationCandidates);
    n.termination = new HashMap<>(r.termination);
    n.activation = new TreeMap<>(r.activation);
    n.effectCount = r.effectCount;
    n.receiving = r.receiving;
    n.liveReceiving = r.liveReceiving;
    n.begun = r.begun;
    n.sparseScheduling = r.sparseScheduling;
    n.serials = new TreeMap<>(r.serials);
    n.turns = copyValues(r.turns);
    n.completedTurns = new HashSet<>(r.completedTurns);
    n.rounds = copyValues(r.rounds);
    n.units = copyValues(r.units);
    n.routed = new HashSet<>(r.routed);
    n.consumedObservations = new HashSet<>(r.consumedObservations);
    n.turnCount = r.turnCount;
    n.roundCount = r.roundCount;
    n.allocation = r.allocation;
    n.topologyChanged = r.topologyChanged;
    n.phase = phase(r.phase);
    var g = new ContractGraph(n.state, r.reg, r.graph.archive, n.meter, r.graph.cause);
    g.aliases = r.graph.aliases.copy();
    g.resets = new HashSet<>(r.graph.resets);
    g.added = new ArrayList<>(r.graph.added);
    g.changes = n.changes;
    g.topologyChanged = r.graph.topologyChanged;
    g.allocation = r.graph.allocation;
    g.dirty = new HashSet<>(r.graph.dirty);
    g.finalized = r.graph.finalized.copy();
    // Topology components contain immutable member keys. The fork replaces the partition as a unit.
    g.partition = r.graph.partition;
    g.pathHeaders = new HashSet<>(r.graph.pathHeaders);
    g.verifiedBindings = new HashSet<>(r.graph.verifiedBindings);
    var l = new ContractWork(r.blue, n.meter);
    var old = r.graph.logical;
    l.manifests.addAll(old.manifests);
    l.established.addAll(old.established);
    l.cyclicEstablished.addAll(old.cyclicEstablished);
    l.proofs.addAll(old.proofs);
    l.carriedRoots.addAll(old.carriedRoots);
    l.boundIdentities.addAll(old.boundIdentities);
    l.copyExactValues(old);
    l.partition(old.partition());
    g.logical = l;
    n.graph = g;
    return n;
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

  static Phase phase(Phase p) {
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
