package blue;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Semantic Contracts charges and conservative physical-locality eligibility. */
final class SemanticGas {
  private SemanticGas() {}

  static BexFailure unsupported(String binding) {
    return new BexFailure("HOST_INCOMPLETE", "Physical execution unavailable for " + binding);
  }

  static void unit(BexGas gas, String action) {
    units(gas, action, 1);
  }

  /** Unit runs preserve the admitted prefix, rather than rejecting a fitting prefix as a batch. */
  static void units(BexGas gas, String action, long count) {
    if (count < 0) throw new IllegalArgumentException("Negative units");
    long fitting = Math.min(count, gas.remaining());
    if (fitting > 0) gas.charge(action, fitting, fitting);
    if (count > fitting) gas.charge(action, 1, 1);
  }

  static void integer(BexGas gas, BigInteger cost) {
    if (cost.signum() < 0) throw new IllegalArgumentException("Negative cost");
    // Inputs are bounded by the host admission contract; the candidate arithmetic is exact.
    if (cost.bitLength() > 63) {
      if (gas instanceof ContractModel.Meter meter) {
        meter.rejected =
            new LinkedHashMap<>(
                Map.of(
                    "namespace",
                    "bex",
                    "counter",
                    "INTEGER",
                    "units",
                    cost,
                    "amount",
                    cost,
                    "owner",
                    meter.owner,
                    "remaining",
                    meter.remaining(),
                    "cap",
                    meter.remaining() < meter.limit - meter.used ? "LOCAL" : "SHARED"));
      }
      throw new BexFailure("GAS_LIMIT_EXCEEDED", "INTEGER " + cost);
    }
    gas.charge("INTEGER", cost.longValueExact(), cost.longValueExact());
  }

  static void contracts(BexGas gas, String counter, long units) {
    if (units < 0) throw new IllegalArgumentException("Negative units");
    String action =
        switch (counter) {
          case "localTurnOpened" -> "TURN";
          case "scopeInitialization" -> "INIT";
          case "handlerCall" -> "HANDLER";
          case "pointerSegmentTraversed" -> "LOOKUP";
          case "patchBoundaryChecked" -> "LOOKUP";
          case "patchAddOrReplace", "patchRemove" -> "WRITE";
          case "internalEventEnqueued" -> "EMIT";
          case "documentUpdateDelivered",
              "triggeredEventDelivered",
              "embeddedEventDelivered",
              "lifecycleDelivered",
              "completionBatchInstalled" ->
              "DELIVER";
          case "channelAccepted",
              "channelCandidateTested",
              "checkpointCompared",
              "checkpointWritten",
              "closureExpanded",
              "closureInvocation",
              "closureWorkOccurrenceDequeued",
              "closureWorkOccurrenceEnqueued",
              "completionRouteChecked",
              "componentEdgePartitioned",
              "componentMemberPartitioned",
              "componentPartitionChanged",
              "componentRoundOpened",
              "containingReferenceUpdated",
              "cyclicMemberFinalized",
              "deliverySnapshotEntry",
              "embeddedPathEntryRead",
              "embeddedPathSegmentValidated",
              "handlerCandidateTested",
              "identityInputMember",
              "identityInputTextBlock",
              "integerLimbOperation",
              "internalEventDequeued",
              "listItemRead",
              "localTurnSealed",
              "nodeManifestOpened",
              "objectMemberRead",
              "processEmbeddedEdgeExamined",
              "processInvocation",
              "processorMarkerWritten",
              "rootEventRecorded",
              "scalarComparison",
              "sortComparison",
              "subtypeCandidateTested",
              "tentativeComponentFinalization",
              "terminationRequested",
              "textBlockExamined",
              "typeEdgeFollowed" ->
              null;
          default ->
              throw new IllegalArgumentException("Unclassified Contracts counter: " + counter);
        };
    if (action == null) return; // Exact representation, routing and proof bookkeeping only.
    for (long i = 0; i < units; i++) {
      unit(gas, action);
      if (action.equals("EMIT") || action.equals("DELIVER")) unit(gas, "NEW_SLOT");
    }
  }

  static void construction(BexGas gas, int slots) {
    unit(gas, "NEW_CONTAINER");
    units(gas, "NEW_SLOT", slots);
  }

  static void output(BexGas gas, Object value) {
    // Native Language admission validates arbitrary typed payloads. Charge the explicit
    // boundary once; construction and traversal inside BEX have their own semantic charges.
    unit(gas, "VALIDATE");
  }

  /** A data-only patch cannot change managed topology or channel declarations. */
  static boolean localWrite(ContractRun run, String path, String operation, Object value) {
    if (!Set.of("add", "replace", "remove").contains(operation)) return false;
    List<String> parts = BexValues.segments(path);
    if (parts.isEmpty() || parts.stream().anyMatch(LanguageCore.RESERVED::contains)) return false;
    var document = run.state.documents.get(run.meter.owner);
    var embedded = run.reg.contracts(document).get("embedded");
    if (embedded != null)
      for (String field : List.of("paths", "collectionPaths"))
        for (Object raw : (List<?>) embedded.getOrDefault(field, List.of())) {
          List<String> managed = BexValues.segments((String) raw);
          int common = Math.min(managed.size(), parts.size());
          if (managed.subList(0, common).equals(parts.subList(0, common))) return false;
        }
    // Inspect only the changed path. Contextual constraints can require repairs elsewhere,
    // so they use ordinary processing. Normal Language validation still checks the new value.
    String selected = "";
    for (int i = 0; i <= parts.size(); i++) {
      var recipe = run.blue.select(document.body, selected);
      if (recipe == null) return i == parts.size() && operation.equals("add");
      var head = run.blue.head(recipe);
      if (!head.constraints().describe().isEmpty()
          || head.itemType != null
          || head.keyType != null
          || head.valueType != null) return false;
      if (i == parts.size()) return true;
      selected += "/" + BexValues.escape(parts.get(i));
    }
    return false;
  }
}
