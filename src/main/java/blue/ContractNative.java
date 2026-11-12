package blue;

import static blue.ContractModel.HOST_LIMIT;
import static blue.ContractModel.arr;
import static blue.ContractModel.closed;
import static blue.ContractModel.integer;
import static blue.ContractModel.m;
import static blue.ContractModel.obj;
import static blue.ContractModel.require;
import static blue.ContractModel.segments;
import static blue.ContractModel.str;
import static blue.ContractModel.token;

import blue.ContractModel.Meter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Explicit host-installed deterministic extensions; package imports do not install executable code.
 */
final class ContractNative {
  private ContractNative() {}

  public record Binding(
      String typeId,
      String semanticsId,
      String role,
      List<String> headerFields,
      List<String> bodyFields,
      String namespace,
      Map<String, Long> tariff,
      boolean inheritsToSubtypes,
      List<String> peerFields,
      String meterMode) {
    public Binding {
      ContractLanguage.finalId(typeId);
      ContractLanguage.finalId(semanticsId);
      token(namespace);
      require(!Set.of("bex", "contracts").contains(namespace), "RESERVED_GAS_NAMESPACE");
      require(
          Set.of(
                  "external-channel",
                  "processor-channel",
                  "handler",
                  "marker",
                  "step",
                  "dispatch-policy")
              .contains(role),
          "INVALID_RUNTIME_ROLE");
      require(Set.of("live", "ledger").contains(meterMode), "INVALID_RUNTIME_METER_MODE");
      headerFields = List.copyOf(headerFields);
      bodyFields = List.copyOf(bodyFields);
      peerFields = List.copyOf(peerFields);
      tariff = Collections.unmodifiableMap(new LinkedHashMap<>(tariff));
      var fields = new ArrayList<>(headerFields);
      fields.addAll(bodyFields);
      require(new HashSet<>(fields).size() == fields.size(), "AMBIGUOUS_RUNTIME_FIELDS");
      for (String name : fields) {
        token(name);
        require(
            !name.startsWith("_")
                && !Set.of("type", "specification", "name", "description", "order").contains(name),
            "INVALID_RUNTIME_FIELD");
      }
      require(
          new HashSet<>(peerFields).size() == peerFields.size()
              && headerFields.containsAll(peerFields),
          "INVALID_PEER_FIELDS");
      tariff.forEach(
          (k, v) -> {
            token(k);
            integer(v);
          });
    }

    public Map<String, Object> wire() {
      return m(
          "type_id",
          typeId,
          "semantics_id",
          semanticsId,
          "role",
          role,
          "header_fields",
          headerFields,
          "body_fields",
          bodyFields,
          "namespace",
          namespace,
          "tariff",
          tariff.entrySet().stream().map(e -> List.of(e.getKey(), e.getValue())).toList(),
          "inherits_to_subtypes",
          inheritsToSubtypes,
          "peer_fields",
          peerFields,
          "meter_mode",
          meterMode);
    }
  }

  public interface Implementation {
    /** Implemented only by the library's verified Timeline admission adapter. */
    default boolean additiveAdmission() {
      return false;
    }

    default Object execute(Context context) {
      throw new ContractModel.Error("MISSING_RUNTIME_EXECUTOR");
    }

    default boolean accept(ExternalContext context) {
      throw new ContractModel.Error("MISSING_RUNTIME_CHANNEL_FUNCTION");
    }

    default Set<String> families() {
      return Set.of();
    }

    default boolean acceptInternal(
        Map<String, Object> header, String family, Object event, Function<String, Object> peers) {
      return false;
    }

    default Object channelizeInternal(
        Map<String, Object> header, String family, Object event, Function<String, Object> peers) {
      return event;
    }

    default boolean verifiesAdmission() {
      return false;
    }

    default Map<String, Object> verifyAdmission(AdmissionContext context) {
      return Map.of();
    }
  }

  /** Preserves host/read failures across the trusted Java callback boundary. */
  static final class AccessFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;
    final RuntimeException failure;

    AccessFailure(RuntimeException failure) {
      super(failure);
      this.failure = failure;
    }
  }

  static Object access(java.util.function.Supplier<Object> action) {
    try {
      return action.get();
    } catch (RuntimeException failure) {
      throw new AccessFailure(failure);
    }
  }

  public record Context(
      Object event,
      Map<String, Object> header,
      Map<String, Object> body,
      Map<String, Object> steps,
      String processingEvent,
      Long timestamp,
      Function<String, Object> read,
      RuntimeMeter gas,
      Object call,
      Function<Object, Object> evaluate) {}

  public record ExternalContext(
      Map<String, Object> header,
      Object event,
      long timestamp,
      Object checkpoint,
      Function<String, Object> peers,
      RuntimeMeter gas,
      String member,
      String document,
      String channel,
      long generation,
      Object evidence) {}

  public record AdmissionContext(
      String snapshot,
      String event,
      String payload,
      long timestamp,
      Map<String, Object> runtime,
      List<Object> members,
      Object evidence,
      RuntimeMeter gas) {}

  public static Object frozen(Object value) {
    if (value instanceof Map<?, ?>) {
      var out = new TreeMap<String, Object>();
      obj(value).forEach((k, v) -> out.put(k, frozen(v)));
      return Collections.unmodifiableMap(out);
    }
    if (value instanceof List<?> a) {
      var out = new ArrayList<Object>();
      for (Object v : a) {
        out.add(frozen(v));
      }
      return Collections.unmodifiableList(out);
    }
    return value;
  }

  public static final class RuntimeMeter {
    private final Meter parent;
    private final Binding binding;
    private final String owner;
    private final long start, allowance;
    private long used;
    private final List<Charge> rows = new ArrayList<>();
    private Charge rejected;
    private boolean finished, merged;
    private final boolean hostWork;

    record Charge(String counter, long units, long amount) {}

    RuntimeMeter(Meter parent, Binding binding, String owner) {
      this(parent, binding, owner, false);
    }

    RuntimeMeter(Meter parent, Binding binding, String owner, boolean admittedAdapter) {
      this.parent = parent;
      hostWork = admittedAdapter && !parent.semantic;
      this.binding = binding;
      this.owner = owner;
      start = parent.used;
      long shared = parent.limit - parent.used,
          local =
              parent.localLimits.getOrDefault(owner, parent.limit)
                  - parent.localUsed.getOrDefault(owner, 0L);
      allowance = parent.localLimits.containsKey(owner) ? Math.min(shared, local) : shared;
    }

    public long remaining() {
      if (hostWork) {
        return HOST_LIMIT - parent.hostUsed;
      }
      if (binding.meterMode.equals("live")) {
        long shared = parent.limit - parent.used;
        return parent.localLimits.containsKey(owner)
            ? Math.min(
                shared, parent.localLimits.get(owner) - parent.localUsed.getOrDefault(owner, 0L))
            : shared;
      }
      return allowance - used;
    }

    public void charge(String counter) {
      charge(counter, 1);
    }

    public void charge(String counter, long units) {
      require(!finished && rejected == null, "CLOSED_RUNTIME_LEDGER");
      integer(units, 0, -1);
      require(
          binding.tariff.containsKey(counter), "UNDECLARED_RUNTIME_COUNTER", "counter", counter);
      long price = binding.tariff.get(counter);
      if (hostWork) {
        require(
            price == 0 || units <= (HOST_LIMIT - parent.hostUsed) / price,
            "HOST_INCOMPLETE",
            "resource",
            "native-admission-work");
      }
      long amount = BexGas.cost(price, units);
      if (hostWork) {
        parent.hostDebit(amount);
      } else if (binding.meterMode.equals("live")) {
        parent.owner = owner;
        parent.debit(binding.namespace, counter, units, amount);
      } else if (amount > remaining()) {
        rejected = new Charge(counter, units, amount);
        throw new BexFailure("GAS_LIMIT_EXCEEDED", counter);
      }
      used += amount;
      if (parent.recordCharges && binding.meterMode.equals("ledger")) {
        rows.add(new Charge(counter, units, amount));
      }
    }

    void merge(Object returned, boolean failed) {
      require(!merged, "DOUBLE_GAS_DEBIT");
      merged = true;
      if (binding.meterMode.equals("live")) {
        require(returned == null, "DOUBLE_GAS_DEBIT");
        return;
      }
      require(failed || returned == this && finished, "INVALID_RUNTIME_LEDGER");
      if (hostWork) {
        return;
      }
      require(parent.used == start, "RUNTIME_LEDGER_ALLOWANCE_CHANGED");
      parent.owner = owner;
      if (parent.finalGas()) {
        parent.mergeAdmitted(used);
      } else {
        for (var row : rows) {
          parent.debit(binding.namespace, row.counter, row.units, row.amount);
        }
      }
      if (rejected != null) {
        parent.debit(binding.namespace, rejected.counter, rejected.units, rejected.amount);
        throw new ContractModel.Error("RUNTIME_LEDGER_REJECTION_MISMATCH");
      }
      finished = true;
    }
  }

  static final class Installed {
    final Binding binding;
    final Implementation implementation;
    final String role;

    Installed(Binding b, Implementation i) {
      binding = b;
      implementation = i;
      role =
          switch (b.role) {
            case "external-channel" -> "extension_external";
            case "processor-channel" -> "extension_channel";
            case "handler" -> "extension_handler";
            case "marker" -> "extension_marker";
            case "dispatch-policy" -> "extension_dispatch_policy";
            default -> "extension_step";
          };
      if (b.role.equals("external-channel")) {
        require(b.meterMode.equals("live"), "EXTERNAL_RUNTIME_REQUIRES_LIVE_METER");
      }
      if (b.role.equals("processor-channel")) {
        require(
            !i.families().isEmpty()
                && Set.of("TRIGGERED", "EMBEDDED", "UPDATE", "INITIALIZATION", "TERMINATION")
                    .containsAll(i.families()),
            "INVALID_RUNTIME_EVENT_FAMILIES");
      }
    }

    Map<String, Object> header(Map<String, Object> d) {
      var out = new TreeMap<String, Object>();
      for (String k : binding.headerFields) {
        if (d.containsKey(k)) {
          out.put(k, d.get(k));
        }
      }
      return obj(frozen(out));
    }

    Function<String, Object> peers(
        Map<String, Object> d, Map<String, Map<String, Object>> contracts) {
      var names = new HashSet<String>();
      for (String field : binding.peerFields) {
        var values = arr(d.getOrDefault(field, List.of()));
        require(values.stream().allMatch(v -> v instanceof String), "INVALID_PEER_DECLARATION");
        for (Object v : values) {
          names.add((String) v);
        }
      }
      return name -> {
        require(names.contains(name), "UNDECLARED_PEER_LOOKUP", "peer", name);
        var value = contracts.get(name);
        if (value == null) {
          return frozen(m("status", "absent"));
        }
        String role = str(value.get("_role"));
        if (!ContractRegistry.EXTERNAL.contains(role)
            && !ContractRegistry.INTERNAL.contains(role)) {
          return frozen(m("status", "non-channel"));
        }
        var header = new TreeMap<String, Object>();
        value.forEach(
            (k, v) -> {
              if (!k.startsWith("_")) {
                header.put(k, v);
              }
            });
        return frozen(m("status", "present", "header", header));
      };
    }

    Map<String, Object> verifyAdmission(ContractRun run, List<Object> members) {
      // Admission is one type-wide gate, attributed to the invocation root. It shares the same
      // allowance as subsequent channel selection and workflows, including root-local limits.
      var gas =
          new RuntimeMeter(run.meter, binding, run.state.root, implementation.additiveAdmission());
      var context =
          new AdmissionContext(
              run.input.identity(),
              run.eventId,
              ContractLanguage.identity(run.event),
              run.timestamp,
              obj(frozen(binding.wire())),
              arr(frozen(members)),
              frozen(run.admissionEvidence.get(binding.typeId)),
              gas);
      Map<String, Object> projection;
      try {
        projection = implementation.verifyAdmission(context);
      } catch (AccessFailure failure) {
        gas.merge(null, true);
        throw failure.failure;
      } catch (BexFailure | ContractModel.Error failure) {
        gas.merge(null, true);
        throw failure;
      } catch (RuntimeException failure) {
        gas.merge(null, true);
        throw new ContractModel.Error(
            "RUNTIME_FAILURE", "exception", failure.getClass().getSimpleName());
      }
      gas.merge(null, false);
      return projection;
    }

    boolean accept(
        ContractRun run,
        String owner,
        String key,
        Map<String, Object> declaration,
        Map<String, Map<String, Object>> cs) {
      var gas = new RuntimeMeter(run.meter, binding, owner, implementation.additiveAdmission());
      var doc = run.input.documents.get(owner);
      var context =
          new ExternalContext(
              header(declaration),
              frozen(run.blue.view(run.event, null)),
              run.timestamp,
              frozen(ContractChannels.checkpoint(run.reg, doc, key)),
              peers(declaration, cs),
              gas,
              owner,
              doc.blueId,
              key,
              doc.channelLives.get(key).generation(),
              run.externalAdmissions.get(binding.typeId));
      boolean accepted;
      try {
        accepted = implementation.accept(context);
      } catch (AccessFailure e) {
        gas.merge(null, true);
        throw e.failure;
      } catch (BexFailure | ContractModel.Error e) {
        gas.merge(null, true);
        throw e;
      } catch (RuntimeException e) {
        gas.merge(null, true);
        throw new ContractModel.Error("RUNTIME_FAILURE", "exception", e.getClass().getSimpleName());
      }
      gas.merge(null, false);
      return accepted;
    }
  }

  static Map<String, Object> result(Object raw) {
    var value = closed(raw, Set.of(), Set.of("patches", "events", "termination", "runtimeLedger"));
    Object patches = value.getOrDefault("patches", List.of()),
        events = value.getOrDefault("events", List.of());
    require(patches instanceof List && events instanceof List, "INVALID_EXECUTION_RESULT");
    for (Object patch : arr(patches)) {
      var p = obj(patch);
      require(
          Set.of("add", "replace", "remove").contains(p.getOrDefault("op", "")), "INVALID_PATCH");
      closed(
          p,
          p.get("op").equals("remove") ? Set.of("op", "path") : Set.of("op", "path", "val"),
          Set.of());
      var parts = segments(str(p.get("path")));
      require(!parts.isEmpty() && !parts.contains(""), "INVALID_PATCH_PATH");
    }
    if (value.containsKey("termination")) {
      var end = closed(value.get("termination"), Set.of("cause"), Set.of("reason"));
      require(
          end.get("cause") instanceof String
              && (!end.containsKey("reason") || end.get("reason") instanceof String),
          "INVALID_TERMINATION");
    }
    var out = new LinkedHashMap<>(value);
    out.put("patches", patches);
    out.put("events", events);
    return out;
  }
}
