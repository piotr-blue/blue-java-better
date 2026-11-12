package blue;

import static blue.ContractLanguage.ABSENT;
import static blue.ContractLanguage.identity;
import static blue.ContractModel.arr;
import static blue.ContractModel.closed;
import static blue.ContractModel.digest;
import static blue.ContractModel.integer;
import static blue.ContractModel.m;
import static blue.ContractModel.obj;
import static blue.ContractModel.require;
import static blue.ContractModel.str;
import static blue.ContractModel.token;
import static blue.ContractRegistry.EXTERNAL;

import blue.ContractModel.Document;
import blue.ContractModel.EdgeKey;
import blue.ContractModel.Life;
import blue.ExactNode.Ref;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

final class ContractChannels {
  record Seed(String document, String channel, long generation, String operation) {
    Map<String, Object> wire() {
      return m(
          "document",
          document,
          "channel",
          channel,
          "generation",
          generation,
          "operation",
          operation);
    }
  }

  record Call(ExactNode event, Map<String, Object> record) {}

  static List<String> leaves(Map<String, Map<String, Object>> declarations, String name) {
    return leaves(declarations, name, new HashSet<>());
  }

  static List<String> leaves(Map<String, Map<String, Object>> cs, String key, Set<String> active) {
    require(
        cs.containsKey(key) && EXTERNAL.contains(cs.get(key).get("_role")),
        "INVALID_COMPOSITE_MEMBER",
        "key",
        key);
    require(!active.contains(key), "CYCLIC_CHANNEL_COMPOSITION");
    String role = str(cs.get(key).get("_role"));
    if (role.equals("external") || role.equals("extension_external")) {
      return List.of(key);
    }
    List<Object> members =
        role.equals("all")
            ? new ArrayList<>(
                cs.entrySet().stream()
                    .filter(
                        e ->
                            Set.of("external", "extension_external")
                                .contains(e.getValue().get("_role")))
                    .map(Map.Entry::getKey)
                    .sorted(ContractCanonical.CODE_POINTS)
                    .toList())
            : arr(cs.get(key).getOrDefault("channels", List.of()));
    require(members.stream().allMatch(v -> v instanceof String), "INVALID_COMPOSITE_MEMBERS");
    require(new HashSet<>(members).size() == members.size(), "DUPLICATE_COMPOSITE_MEMBER");
    var next = new HashSet<>(active);
    next.add(key);
    var out = new TreeSet<String>(ContractCanonical.CODE_POINTS);
    for (Object member : members) {
      out.addAll(leaves(cs, (String) member, next));
    }
    return List.copyOf(out);
  }

  static String signature(Map<String, Map<String, Object>> cs, String key) {
    var d = cs.get(key);
    String role = str(d.get("_role"));
    var record = m("type", obj(d.get("type")).get("blueId"), "leaves", leaves(cs, key, Set.of()));
    if (role.equals("external")) {
      if (d.containsKey("event")) {
        record.put("event", d.get("event"));
      }
    } else if (role.equals("extension_external")) {
      var extension = (ContractNative.Installed) d.get("_extension");
      record.put("runtime", extension.binding.wire());
      record.put("header", extension.header(d));
    } else {
      var members = new TreeMap<String, Object>(ContractCanonical.CODE_POINTS);
      for (String k : leaves(cs, key, Set.of())) {
        members.put(k, signature(cs, k));
      }
      record.put("members", members);
    }
    return digest("contracts.channel/completion-1", record);
  }

  static void refresh(ContractGraph g) {
    refresh(g, g.reachable(true, Set.of()));
  }

  static void refresh(ContractGraph g, Collection<String> members) {
    for (String member : ContractCanonical.keys(members)) {
      Document doc = g.state.documents.get(member);
      var cs = g.registry.contracts(doc);
      var headers = new TreeMap<String, String>(ContractCanonical.CODE_POINTS);
      cs.forEach(
          (k, c) -> {
            if (EXTERNAL.contains(c.get("_role"))) {
              headers.put(k, signature(cs, k));
            }
          });
      var lives = doc.channelLives;
      var serials = doc.channelSerials;
      for (String k : doc.channelLives.keySet()) {
        if (!headers.containsKey(k)) {
          lives = lives.minus(k);
          g.resets.add(new EdgeKey(member, k));
        }
      }
      for (var row : headers.entrySet()) {
        String k = row.getKey(), sig = row.getValue();
        Life old = lives.get(k);
        if (old == null || !old.signature().equals(sig)) {
          long generation = integer(serials.getOrDefault(k, 0L) + 1);
          serials = serials.plus(k, generation);
          lives = lives.plus(k, new Life(generation, sig));
          g.resets.add(new EdgeKey(member, k));
        }
      }
      g.state.documents.put(member, doc.withChannels(lives, serials));
    }
  }

  static Map<String, Object> checkpoint(ContractRegistry reg, Document doc, String key) {
    ExactNode marker = reg.marker(doc, "checkpoint");
    if (marker == null) {
      return null;
    }
    var raw = reg.markerRecord(marker, "checkpoint");
    require(Set.of("type", "entries").containsAll(raw.keySet()), "INVALID_CHECKPOINT");
    var entries = obj(raw.getOrDefault("entries", Map.of()));
    if (!entries.containsKey(key)) {
      return null;
    }
    var row = closed(entries.get(key), Set.of("timestamp", "event"), Set.of());
    integer(row.get("timestamp"));
    var event = closed(row.get("event"), Set.of("blueId"), Set.of());
    reg.blue.open(new Ref(str(event.get("blueId"))));
    return row;
  }

  static boolean isNew(
      ContractRegistry reg, Document doc, String key, String event, long timestamp) {
    var previous = checkpoint(reg, doc, key);
    if (previous == null || !obj(previous.get("event")).get("blueId").equals(event)) {
      return true;
    }
    require(integer(previous.get("timestamp")) == timestamp, "EVENT_TIMESTAMP_CONFLICT");
    return false;
  }

  static Map<String, Object> operationRequest(ContractRegistry reg, Object node) {
    if (!"request".equals(reg.role(node))) {
      return null;
    }
    var blue = reg.blue;
    var head = blue.head(node);
    require(
        head.items == null
            && head.value == ABSENT
            && head.contracts == null
            && Set.of(
                    "operation",
                    "channel",
                    "document",
                    "requireExactDocumentVersion",
                    "request",
                    "specification")
                .containsAll(head.fields.keySet()),
        "INVALID_OPERATION_REQUEST");
    var out = m("type", m("blueId", head.type));
    for (String key : List.of("operation", "channel", "requireExactDocumentVersion")) {
      var ch = blue.child(node, key);
      if (ContractLanguage.present(ch, blue.context)) {
        out.put(key, blue.project(ch));
      }
    }
    out.put("operation", requestToken(out.get("operation"), "operation"));
    out.put("channel", requestToken(out.get("channel"), "channel"));
    Object exact = out.getOrDefault("requireExactDocumentVersion", false);
    require(exact instanceof Boolean, "INVALID_OPERATION_REQUEST");
    for (String name : List.of("document", "request")) {
      var ch = blue.child(node, name);
      if (ContractLanguage.present(ch, blue.context)) {
        ExactNode value = blue.exact(ch);
        blue.certify(value);
        blue.remember(value);
        out.put(name, m("blueId", identity(value)));
      }
    }
    require(
        !Boolean.TRUE.equals(exact) || out.containsKey("document"), "INVALID_OPERATION_REQUEST");
    return out;
  }

  private static String requestToken(Object value, String field) {
    try {
      return token(value);
    } catch (ContractFault invalid) {
      if (!invalid.code().equals("INVALID_TOKEN")) {
        throw invalid;
      }
      throw new ContractModel.Error("INVALID_OPERATION_REQUEST", "field", field);
    }
  }

  static Call recognize(ContractRegistry reg, ExactNode event) {
    if ("request".equals(reg.role(event))) {
      return new Call(event, operationRequest(reg, event));
    }
    var candidate = reg.blue.child(event, "operation");
    if (candidate != null && "request".equals(reg.role(candidate))) {
      ExactNode n = reg.blue.exact(candidate);
      return new Call(n, operationRequest(reg, n));
    }
    return new Call(null, null);
  }

  static List<Seed> select(ContractRun run) {
    var out = new ArrayList<Seed>();
    for (var group : run.graph.topology().components(run.graph.reachable(true, Set.of()))) {
      for (String member : group) {
        if (run.receiving.stream()
            .anyMatch(record -> record.outcome().gasByMember.containsKey(member))) {
          continue;
        }
        Document doc = run.input.documents.get(member);
        var cs = run.reg.contracts(doc);
        var primitive = new HashSet<String>();
        run.meter.owner = member;
        var ordered = run.ordered(cs);
        for (var row : ordered) {
          String k = row.getKey();
          var channel = row.getValue();
          if (!Set.of("external", "extension_external").contains(channel.get("_role"))) {
            continue;
          }
          run.meter.contracts("channelCandidateTested");
          boolean accept =
              channel.get("_role").equals("external")
                  ? (!channel.containsKey("event")
                      || run.runtimeMatches(run.event, channel.get("event")))
                  : ((ContractNative.Installed) channel.get("_extension"))
                      .accept(run, member, k, channel, cs);
          if (accept) {
            primitive.add(k);
          }
        }
        var accepted = new HashSet<>(primitive);
        for (var row : ordered) {
          if (Set.of("composite", "all").contains(row.getValue().get("_role"))) {
            run.meter.contracts("channelCandidateTested");
            if (!Collections.disjoint(primitive, leaves(cs, row.getKey(), Set.of()))) {
              accepted.add(row.getKey());
            }
          }
        }
        var fresh = new HashSet<String>();
        for (var row : ordered) {
          if (accepted.contains(row.getKey())) {
            run.meter.contracts("channelAccepted");
            run.meter.contracts("checkpointCompared");
            if (isNew(run.reg, doc, row.getKey(), run.eventId, run.timestamp)) {
              fresh.add(row.getKey());
            }
          }
        }
        if (fresh.isEmpty()) {
          continue;
        }
        String target = null, operation = null;
        if (run.callRecord != null && !primitive.isEmpty()) {
          var request = run.callRecord;
          String selected = str(request.get("channel"));
          boolean addressed = request.containsKey("document"),
              inAudience =
                  !addressed
                      || obj(request.get("document")).get("blueId").equals(doc.blueId)
                      || run.input.documents.size() == 1;
          var audience = new ArrayList<Object>();
          for (var evidence : run.externalAdmissions.values()) {
            var inventory = obj(evidence.getOrDefault("operationAudience", Map.of()));
            if (inventory.containsKey(member)) {
              audience.add(inventory.get(member));
            }
          }
          if (!audience.isEmpty()) {
            inAudience = audience.stream().allMatch(Boolean.TRUE::equals);
          }
          boolean candidate =
              inAudience
                  && (addressed
                      || accepted.contains(selected)
                          && EXTERNAL.contains(cs.get(selected).get("_role")));
          if (candidate) {
            target = selected;
            operation = str(request.get("operation"));
            require(
                cs.containsKey(target)
                    && accepted.contains(target)
                    && EXTERNAL.contains(cs.get(target).get("_role")),
                "INVALID_TARGET_CHANNEL",
                "document",
                member,
                "channel",
                target);
          }
          if (candidate && fresh.contains(target)) {
            require(
                cs.containsKey(operation)
                    && cs.get(operation).get("_role").equals("operation")
                    && cs.get(operation).get("channel").equals(target),
                "INVALID_TARGET_OPERATION",
                "document",
                member,
                "operation",
                operation);
            if (Boolean.TRUE.equals(request.get("requireExactDocumentVersion"))) {
              require(
                  obj(request.get("document")).get("blueId").equals(doc.blueId),
                  "STALE_DOCUMENT_PRECONDITION",
                  "document",
                  member);
            }
            if (cs.get(operation).containsKey("request")) {
              require(
                  request.containsKey("request")
                      && run.blue.matches(
                          request.get("request"), cs.get(operation).get("request"), null),
                  "INVALID_REQUEST",
                  "document",
                  member,
                  "operation",
                  operation);
            }
          }
        }
        for (var row : ordered) {
          if (fresh.contains(row.getKey())) {
            String k = row.getKey();
            out.add(
                new Seed(
                    member,
                    k,
                    doc.channelLives.get(k).generation(),
                    k.equals(target) ? operation : null));
            run.meter.contracts("deliverySnapshotEntry");
          }
        }
      }
    }
    run.meter.owner = "";
    return out;
  }
}
