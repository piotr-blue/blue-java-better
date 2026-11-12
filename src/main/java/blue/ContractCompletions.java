package blue;

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
import blue.ExactNode.Ref;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Process-local completion capabilities. Only a locally executed, successful outcome can issue one.
 */
final class ContractCompletions {
  static Outcome restore(Snapshot after, Map<String, Object> o, String receipt) {
    require(
        after.ownerLocal
            ? "owner".equals(o.get("executionBoundary"))
            : !o.containsKey("executionBoundary"),
        "INCOMPATIBLE_EXECUTION_BOUNDARY");
    var result =
        new Outcome(str(o.get("status")), after, str(o.get("input")), str(o.get("invocation")));
    result.publicEvents = arr(o.get("publicEvents"));
    result.events = arr(o.get("events"));
    result.work = arr(o.get("work"));
    result.changes = arr(o.get("changes"));
    result.checkpoints = arr(o.get("checkpoints"));
    result.gas = integer(o.get("gas"));
    {
      require(after.gasSchedule.id().equals(o.get("gasSchedule")), "INCOMPATIBLE_GAS_SCHEDULE");
      var ledger = new TreeMap<String, Long>();
      long total = 0;
      for (var row : obj(o.get("gasByMember")).entrySet()) {
        long amount = integer(row.getValue(), 0, HOST_LIMIT);
        require(amount <= HOST_LIMIT - total, "INVALID_GAS_LEDGER");
        total += amount;
        ledger.put(row.getKey(), amount);
      }
      require(total == result.gas, "INVALID_GAS_LEDGER");
      result.gasByMember = Map.copyOf(ledger);
    }
    if (after.release.equals(ContractsEdition.DETERMINISTIC.specificationBlueId())) {
      require(!o.containsKey("gasTrace"), "UNEXPECTED_GAS_TRACE");
    } else {
      // Trace rows are opaque diagnostics here; consumers that read them check each row.
      @SuppressWarnings("unchecked")
      var trace = (List<Map<String, Object>>) (List<?>) arr(o.get("gasTrace"));
      result.gasTrace = trace;
    }
    result.rejected = o.get("rejected");
    result.diagnostic = o.get("diagnostic");
    result.completions = arr(o.get("completions"));
    result.rounds = arr(o.get("rounds"));
    result.units = arr(o.get("units"));
    result.seal();
    require(result.receipt().equals(receipt), "CORRUPT_LOCAL_COMPLETION");
    return result;
  }

  record Remembered(Snapshot before, String event, Long timestamp) {}

  record Proof(String beforeIdentity, String event, Long timestamp) {}

  // Fixed-size provenance only (128 digest/cause records), never growing snapshots/results.
  // Preserve the existing FIFO expiry policy. Live Result/SourceCompletion handles pin bodies.
  final LinkedHashMap<String, Proof> outcomes = new LinkedHashMap<>();
  final Set<String> capabilities = new LinkedHashSet<>();

  record Record(
      Map<String, Object> descriptor,
      Snapshot before,
      Outcome outcome,
      String event,
      Long timestamp,
      Set<String> members) {}

  void remember(Snapshot before, Outcome out, String event, Long timestamp) {
    out.seal();
    out.established = new Remembered(before.copy(), event, timestamp);
    outcomes.put(out.receipt(), new Proof(out.inputIdentity, event, timestamp));
    while (outcomes.size() > 128) {
      outcomes.remove(outcomes.keySet().iterator().next());
    }
  }

  ContractProcessor.SourceCompletion capture(
      ContractProcessor processor, Outcome out, Collection<String> selected) {
    var r = out.established;
    var proof = outcomes.get(out.receipt());
    require(
        proof != null
            && r != null
            && proof.beforeIdentity.equals(r.before.identity())
            && Objects.equals(proof.event, r.event)
            && Objects.equals(proof.timestamp, r.timestamp),
        "UNESTABLISHED_SOURCE_COMPLETION");
    var members = ContractCanonical.keys(selected);
    require(members.contains(out.snapshot.root), "SOURCE_ROOT_NOT_SELECTED");
    require(
        !members.isEmpty() && out.snapshot.documents.keySet().containsAll(members),
        "INVALID_SOURCE_MEMBERS");
    require(r.before.documents.keySet().containsAll(members), "SOURCE_BIRTH_REQUIRES_OWNER_COMMIT");
    for (Object u : out.units) {
      var unit = new HashSet<>(arr(obj(u).get("members")));
      require(
          Collections.disjoint(unit, members) || members.containsAll(unit), "PARTIAL_SOURCE_UNIT");
    }
    var d =
        m(
            "receipt",
            out.receipt(),
            "before",
            vector(r.before, members),
            "after",
            vector(out.snapshot, members),
            "members",
            List.copyOf(members),
            "event",
            r.event,
            "timestamp",
            r.timestamp,
            "release",
            r.before.release,
            "runtimes",
            r.before.runtimes);
    d.put("identity", digest("contracts.retained-completion/1", d));
    String token = UUID.randomUUID().toString();
    capabilities.add(token);
    while (capabilities.size() > 128) {
      capabilities.remove(capabilities.iterator().next());
    }
    return processor.issue(
        token, new Record(d, r.before, out, r.event, r.timestamp, Set.copyOf(members)));
  }

  Record read(ContractProcessor.SourceCompletion capability) {
    require(capability != null && capability.issuer == this, "FOREIGN_SOURCE_COMPLETION");
    require(capabilities.contains(capability.token), "EXPIRED_SOURCE_COMPLETION");
    return capability.retained;
  }

  static ContractRun run(
      ContractProcessor processor,
      Snapshot snapshot,
      List<ContractProcessor.SourceCompletion> sources,
      boolean live,
      Map<String, Object> evidence) {
    require(!sources.isEmpty(), "MISSING_SOURCE_COMPLETIONS");
    var records = sources.stream().map(processor.completions::read).toList();
    require(
        records.stream().map(r -> r.descriptor.get("identity")).distinct().count()
            == records.size(),
        "DUPLICATE_SOURCE_COMPLETION");
    require(
        records.stream().map(r -> Arrays.asList(r.event, r.timestamp)).distinct().count() == 1,
        "MIXED_SOURCE_CAUSES");
    var owners = new HashSet<String>();
    for (var r : records) {
      require(Collections.disjoint(owners, r.members), "OVERLAPPING_SOURCE_COMPLETIONS");
      owners.addAll(r.members);
      require(
          r.before.gasSchedule == snapshot.gasSchedule
              && r.before.release.equals(snapshot.release)
              && processor.registry.compatibleBindings(r.before.runtimes, snapshot.runtimes),
          "INCOMPATIBLE_SOURCE_RUNTIME");
    }
    require(!owners.contains(snapshot.root), "CYCLIC_RECEIVING_REQUIRES_ATOMIC_INVOCATION");
    var cause = records.getFirst();
    var run =
        new ContractRun(
            processor,
            snapshot,
            cause.event == null ? null : new Ref(cause.event),
            cause.timestamp,
            cause.event == null);
    {
      // Adopt one acyclic source at the default caps, including fresh descendant births.
      // Birth identities belong to their creating occurrence. Retirements, cycles, overlapping
      // causes and non-default budgets execute the original cause to preserve failure order.
      boolean eligible =
          live
              && records.size() == 1
              && run.meter.limit == HOST_LIMIT
              && run.meter.localLimits.isEmpty();
      for (var record : records) {
        eligible &=
            record.outcome.snapshot.documents.keySet().containsAll(record.before.documents.keySet())
                && record.outcome.rounds.isEmpty();
        for (String member : record.outcome.gasByMember.keySet()) {
          eligible &=
              record.before.documents.containsKey(member)
                  ? snapshot.documents.containsKey(member)
                      && vector(snapshot, List.of(member))
                          .equals(vector(record.before, List.of(member)))
                  : !snapshot.documents.containsKey(member);
        }
      }
      var accounted = new HashSet<String>();
      records.forEach(record -> accounted.addAll(record.outcome.gasByMember.keySet()));
      // Required predicates outside the source can interleave with its selection prefix.
      // Until that decomposition is bound, replay rather than treating a member-total union
      // as evidence of canonical action order.
      for (var row : snapshot.documents.entrySet()) {
        if (!accounted.contains(row.getKey())) {
          for (var declaration : processor.registry.contracts(row.getValue()).values()) {
            if ("external".equals(declaration.get("_role")) && declaration.containsKey("event")) {
              eligible = false;
            }
          }
        }
      }
      if (!eligible) {
        ContractMetrics.count("contracts.sourceCompletionReplayed");
        run.admissionEvidence.putAll(evidence);
        return run;
      }
    }
    if (live) {
      // A parent may have rolled back its previous adoption while the independent child
      // advanced. Only an exact predecessor can be reused; otherwise replay the cause.
      if (records.stream()
          .anyMatch(
              record ->
                  record.members.stream()
                      .anyMatch(
                          member ->
                              !snapshot.documents.containsKey(member)
                                  || !vector(snapshot, List.of(member))
                                      .equals(vector(record.before, List.of(member)))))) {
        run.admissionEvidence.putAll(evidence);
        return run;
      }
      var amounts = new TreeMap<String, Long>();
      for (var record : records) {
        for (var row : record.outcome.gasByMember.entrySet()) {
          Long previous = amounts.putIfAbsent(row.getKey(), row.getValue());
          require(previous == null || previous.equals(row.getValue()), "INCOMPATIBLE_SOURCE_GAS");
        }
      }
      long remaining = run.meter.limit;
      for (var row : amounts.entrySet()) {
        if (row.getValue() > remaining
            || row.getValue() > run.meter.localLimits.getOrDefault(row.getKey(), run.meter.limit)) {
          run.admissionEvidence.putAll(evidence);
          return run;
        }
        remaining -= row.getValue();
      }
    }
    run.receiving = records;
    run.liveReceiving = live;
    run.admissionEvidence.putAll(evidence);
    run.invocation =
        digest(
            "contracts.receiving/1",
            m(
                "input",
                snapshot.identity(),
                "sources",
                records.stream().map(r -> r.descriptor.get("identity")).toList(),
                "policy",
                run.meter.policy()));
    run.graph.cause = run.invocation;
    if (processor.bindings.contractsEdition().deterministic()) {
      run.invocation =
          digest(
              "contracts.receiving-attempt/2",
              m(
                  "input",
                  snapshot.identity(),
                  "mode",
                  live ? "LIVE" : "HISTORICAL",
                  "sources",
                  records.stream().map(r -> r.descriptor.get("identity")).toList(),
                  "policy",
                  run.meter.policy()));
      run.graph.cause = run.invocation;
    }
    return run;
  }

  static void install(ContractRun run, List<Record> records, boolean live) {
    var owners = new TreeSet<String>(ContractCanonical.CODE_POINTS);
    records.forEach(r -> owners.addAll(r.members));
    var routes = new TreeMap<String, List<Ticket>>(ContractCanonical.CODE_POINTS);
    owners.forEach(k -> routes.put(k, run.captureRoutes(k, run.state)));
    if (live) {
      run.seeds = run.seeds.stream().filter(s -> !owners.contains(s.document())).toList();
    }
    var carried = new TreeMap<String, Document>(ContractCanonical.CODE_POINTS);
    for (var r : records) {
      var source = r.outcome.snapshot;
      var closure = new TreeSet<String>(ContractCanonical.CODE_POINTS);
      var pending = new ArrayDeque<>(r.members);
      while (!pending.isEmpty()) {
        String k = pending.pop();
        if (!closure.add(k)) {
          continue;
        }
        for (Edge e : source.outgoingEdges(k)) {
          pending.push(e.target());
        }
      }
      require(!closure.contains(run.input.root), "CYCLIC_RECEIVING_REQUIRES_ATOMIC_INVOCATION");
      closure.removeAll(owners);
      for (String k : closure) {
        var d = source.documents.get(k);
        require(
            !carried.containsKey(k)
                || ContractWire.write(carried.get(k).metadata())
                    .equals(ContractWire.write(d.metadata())),
            "INCOMPATIBLE_SOURCE_DEPENDENCIES");
        carried.put(k, d);
        installMember(run.state, source, k);
      }
    }
    for (var r : records) {
      var before = r.before;
      var after = r.outcome.snapshot;
      for (String k : ContractCanonical.keys(r.members)) {
        require(run.input.documents.containsKey(k), "MISSING_RECEIVING_SOURCE");
        require(
            vector(run.input, List.of(k)).equals(vector(before, List.of(k))),
            "SOURCE_PREDECESSOR_MISMATCH",
            "member",
            k);
        installMember(run.state, after, k);
      }
      for (String k : after.documents.keySet()) {
        if (!before.documents.containsKey(k)) {
          require(!run.input.documents.containsKey(k), "CONFLICTING_MEMBER_BIRTH");
          installMember(run.state, after, k);
        }
      }
    }
    {
      ContractMetrics.count("contracts.sourceCompletionReused");
      var imported = new TreeMap<String, Long>(ContractCanonical.CODE_POINTS);
      for (var r : records) {
        for (var row : r.outcome.gasByMember.entrySet()) {
          Long previous = imported.putIfAbsent(row.getKey(), row.getValue());
          require(previous == null || previous.equals(row.getValue()), "INCOMPATIBLE_SOURCE_GAS");
        }
      }
      for (var row : imported.entrySet()) {
        run.meter.owner = row.getKey();
        run.meter.debit("composition", "sourceCompletion", 1, row.getValue());
      }
      run.meter.owner = "";
      var emitted = new HashSet<String>();
      for (var r : records) {
        for (Object raw : r.outcome.events) {
          var occurrence = obj(raw);
          if (emitted.add(str(occurrence.get("identity")))) {
            var copy = new LinkedHashMap<>(occurrence);
            copy.put("ordinal", run.events.size());
            run.events.add(copy);
          }
        }
      }
    }
    run.graph = run.forkGraph(run.state);
    run.graph.refreshAliases();
    var completed = new HashSet<>(owners);
    completed.addAll(carried.keySet());
    if (live) {
      run.seeds = run.seeds.stream().filter(s -> !completed.contains(s.document())).toList();
    }
    run.phase = run.newPhase(live ? "PROCESS" : "RECEIVE", null, false);
    for (var group : run.phase.groups) {
      require(
          Collections.disjoint(group, owners) || owners.containsAll(group),
          "CYCLIC_RECEIVING_REQUIRES_ATOMIC_INVOCATION");
      if (completed.containsAll(group)) {
        run.phase.done.add(List.copyOf(ContractCanonical.keys(group)));
      }
    }
    for (var r : records) {
      for (String k : ContractCanonical.keys(r.members)) {
        // A cyclic source can have several turns. Preserve their batch boundaries and serials:
        // both are semantic inputs to the receiver's event occurrence identities.
        for (Object raw : r.outcome.completions) {
          var turn = obj(raw);
          var input = obj(turn.get("turnInput"));
          if (!input.get("member").equals(k)) {
            continue;
          }
          var batch =
              new Batch(
                  k,
                  ((Number) input.get("turnSerial")).intValue(),
                  str(turn.get("identity")),
                  arr(turn.get("output")),
                  routes.get(k),
                  vector(run.state, List.of(k)),
                  null);
          run.routeBatch(run.phase, batch, owners, false);
        }
      }
    }
  }

  static void installMember(Snapshot destination, Snapshot source, String member) {
    destination.documents.put(member, source.documents.get(member).copy());
    // Installing a child's new body does not remove and re-add its unchanged attachments.
    // Preserve topology ownership and inspect only this member's contiguous outgoing range.
    for (Edge old : destination.outgoingEdges(member)) {
      if (!source.edges.containsKey(old.key())) {
        destination.edges.remove(old.key());
      }
    }
    for (Edge edge : source.outgoingEdges(member)) {
      if (!edge.equals(destination.edges.get(edge.key()))) {
        destination.edges.put(edge.key(), edge);
      }
    }
  }
}
