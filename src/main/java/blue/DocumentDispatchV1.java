package blue;

import static blue.ContractLanguage.identity;
import static blue.ContractModel.closed;
import static blue.ContractModel.m;
import static blue.ContractModel.obj;
import static blue.ContractModel.require;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** An explicitly selected native policy; the runtime knows no Activation fields or party rules. */
final class DocumentDispatchV1 {
  private DocumentDispatchV1() {}

  private static final ContractNative.Implementation IMPLEMENTATION =
      new ContractNative.Implementation() {};

  public static String installPublished(ContractProcessor processor) {
    var publication = DeterministicPublications.publication("blue-dispatch");
    // The canonical archive was already verified and pinned by the package loader. Install its
    // immutable evidence directly, just as the core Contracts publication does.
    processor.blue.context.install(publication);
    processor.blue.context.installAliases(
        Map.of(
            "Dispatch/Document Dispatch Policy",
            publication.export("Document Dispatch Policy").value()));
    processor.blue.refresh();
    String type = publication.export("Document Dispatch Policy").value();
    processor.installRuntime(
        new ContractNative.Binding(
            type,
            DeterministicPublications.id("blue-dispatch", "rules"),
            "dispatch-policy",
            List.of(),
            List.of("expr", "do", "constants", "functions", "entry", "definition"),
            "documentDispatchV1",
            Map.of("dispatchPolicyCheck", 1L),
            false,
            List.of(),
            "live"),
        IMPLEMENTATION);
    return type;
  }

  public static String install(ContractProcessor processor) {
    require(processor.bindings.dispatchEnabled(), "UNSUPPORTED_DISPATCH_BINDING");
    return installPublished(processor);
  }

  static boolean admit(
      ContractRun run,
      String member,
      String channel,
      ExactNode payload,
      String kind,
      String operation,
      ExactNode call,
      Map<String, Map<String, Object>> declarations) {
    if (declarations.values().stream()
        .noneMatch(d -> "extension_dispatch_policy".equals(d.get("_role")))) {
      return true;
    }
    boolean allowed = true;
    for (var row : run.ordered(declarations)) {
      if (!"extension_dispatch_policy".equals(row.getValue().get("_role"))) {
        continue;
      }
      var policy = run.reg.declaration(row.getValue().get("_recipe"));
      var extension = (ContractNative.Installed) policy.get("_extension");
      var gas = new ContractNative.RuntimeMeter(run.meter, extension.binding, member);
      gas.charge("dispatchPolicyCheck");
      gas.merge(null, false);
      var definition = run.computeDefinition(member, policy);
      var program = ContractRun.program(policy);
      var overlay = new LinkedHashMap<>(program);
      var functions = new LinkedHashMap<String, Object>();
      if (definition != null && definition.program().get("functions") instanceof Map<?, ?> f) {
        functions.putAll(obj(f));
      }
      if (policy.get("functions") instanceof Map<?, ?> f) {
        functions.putAll(obj(f));
      }
      overlay.put("functions", functions);
      var context = run.context(member, payload, policy, Map.of(), call, overlay);
      context.bindings.put("dispatch", m("kind", kind, "channel", channel, "operation", operation));
      var compiled = run.reg.compiled(run.engine.bex, policy, program, definition);
      var result =
          run.engine.bex.execute(compiled, context, run.meter, BexEngine.TraceMode.METER_ONLY);
      require(
          result.changeset().isEmpty() && result.events().isEmpty(), "EFFECTFUL_DISPATCH_POLICY");
      var decision = closed(result.value(), Set.of("allowed"), Set.of("reason"));
      require(decision.get("allowed") instanceof Boolean, "INVALID_DISPATCH_DECISION");
      require(
          !decision.containsKey("reason") || decision.get("reason") instanceof String,
          "INVALID_DISPATCH_REASON");
      boolean accept = Boolean.TRUE.equals(decision.get("allowed"));
      allowed &= accept;
      var evidence =
          m(
              "policy",
              row.getKey(),
              "declaration",
              identity(run.blue.exact(policy.get("_recipe"))),
              "channel",
              channel,
              "kind",
              kind,
              "operation",
              operation,
              "allowed",
              accept,
              "reason",
              decision.getOrDefault("reason", accept ? "" : "DISPATCH_REJECTED"));
      if (run.engine.bindings.contractsEdition().deterministic()) {
        evidence.put("delivery", obj(run.work.getLast()).get("identity"));
      }
      run.frame.dispatchDecisions.add(evidence);
    }
    return allowed;
  }
}
