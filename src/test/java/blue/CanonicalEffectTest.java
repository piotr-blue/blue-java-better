package blue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Effects are an API boundary: lazy event/document views must behave like constructed values. */
@Tag("contracts")
class CanonicalEffectTest {
  @Test
  void updateDocumentAcceptsAChangesetSelectedDirectlyFromTheRequest() {
    try (Contracts.Session session =
        Contracts.create()
            .openYaml(
                """
                count: 0
                contracts:
                  input: {type: Contracts/External Channel}
                  edit:
                    type: Contracts/Sequential Workflow Operation
                    channel: input
                    steps:
                      - type: Contracts/Update Document
                        changeset: {$event: /request/changes}
                """)) {
      session.initialize().requireApplied();
      session
          .call(
              "input",
              "edit",
              Map.of("changes", List.of(Map.of("op", "replace", "path", "/count", "val", 42))),
              1)
          .requireApplied();
      assertEquals(BigInteger.valueOf(42), session.read("/count"));
    }
  }

  @Test
  void computeAcceptsAnEntireEffectObjectSelectedFromTheRequest() {
    try (Contracts.Session session =
        Contracts.create()
            .openYaml(
                """
                count: 0
                contracts:
                  input: {type: Contracts/External Channel}
                  edit:
                    type: Contracts/Sequential Workflow Operation
                    channel: input
                    steps:
                      - type: Contracts/Compute
                        expr: {$event: /request/effect}
                """)) {
      session.initialize().requireApplied();
      Contracts.Transition result =
          session
              .call(
                  "input",
                  "edit",
                  Map.of(
                      "effect",
                      Map.of(
                          "changeset",
                              List.of(Map.of("op", "replace", "path", "/count", "val", 42)),
                          "events", List.of(Map.of("kind", "Changed")))),
                  1)
              .requireApplied();
      assertEquals(BigInteger.valueOf(42), session.read("/count"));
      assertEquals(List.of(Map.of("kind", "Changed")), result.events());
    }
  }

  @Test
  void nativeAndWorkflowWireResultsRejectANullPatchOperationWithTheSameDiagnostic() {
    Map<String, Object> patch = new java.util.LinkedHashMap<>();
    patch.put("op", null);
    patch.put("path", "/count");
    var patches = List.of(patch);
    assertEquals(
        "INVALID_PATCH",
        org.junit.jupiter.api.Assertions.assertThrows(
                ContractFault.class, () -> ContractNative.result(Map.of("patches", patches)))
            .code());
    assertEquals(
        "INVALID_PATCH",
        org.junit.jupiter.api.Assertions.assertThrows(
                ContractFault.class, () -> ContractEffects.validate(null, patches, List.of()))
            .code());
  }

  @ParameterizedTest
  @ValueSource(strings = {"null", "unknown", "missing"})
  void malformedOpProducesAPatchDiagnosticAndPreservesThePredecessor(String variant) {
    var invalid = new java.util.LinkedHashMap<String, Object>();
    if (!variant.equals("missing")) {
      invalid.put("op", variant.equals("null") ? null : variant);
    }
    invalid.put("path", "/count");
    try (var session =
        Contracts.create()
            .openYaml(
                """
                count: 0
                contracts:
                  editChannel: {type: Contracts/External Channel}
                  edit:
                    type: Contracts/Sequential Workflow Operation
                    channel: editChannel
                    steps:
                      - type: Contracts/Update Document
                        changeset: {$event: /request/changes}
                """)) {
      session.initialize().requireApplied();
      var before = session.document().blueId();
      var rejected = session.call("editChannel", "edit", Map.of("changes", List.of(invalid)), 1);
      assertEquals("FAILED", rejected.status());
      assertEquals(
          "INVALID_PATCH", ((Map<?, ?>) rejected.observations().get("diagnostic")).get("code"));
      assertEquals(before, session.document().blueId());
    }
  }

  @Test
  void aMalformedSecondPatchRejectsTheEntireEffectBeforeAnyWriteOrEvent() {
    try (var session =
        Contracts.create()
            .openYaml(
                """
                count: 0
                contracts:
                  input: {type: Contracts/External Channel}
                  edit:
                    type: Contracts/Sequential Workflow Operation
                    channel: input
                    steps:
                      - type: Contracts/Compute
                        expr: {$event: /request/effect}
                """)) {
      session.initialize().requireApplied();
      var first = Map.of("op", "replace", "path", "/count", "val", 1);
      var invalid = Map.of("op", "remove", "path", "");
      var effect =
          Map.of(
              "changeset", List.of(first, invalid), "events", List.of(Map.of("kind", "Changed")));
      var rejected = session.call("input", "edit", Map.of("effect", effect), 1);
      assertEquals("FAILED", rejected.status());
      assertEquals(
          "INVALID_PATCH_PATH",
          ((Map<?, ?>) rejected.observations().get("diagnostic")).get("code"));
      assertEquals(BigInteger.ZERO, session.read("/count"));
      assertTrue(rejected.events().isEmpty());
    }
  }

  @Test
  void constructedListsCanContainLazyPatchRecordsWithoutWeakeningValidation() {
    try (Contracts.Session session =
        Contracts.create()
            .openYaml(
                """
                count: 0
                contracts:
                  input: {type: Contracts/External Channel}
                  edit:
                    type: Contracts/Sequential Workflow Operation
                    channel: input
                    steps:
                      - type: Contracts/Update Document
                        changeset: [{$event: /request/change}]
                """)) {
      session.initialize().requireApplied();
      session
          .call(
              "input",
              "edit",
              Map.of("change", Map.of("op", "replace", "path", "/count", "val", 42)),
              1)
          .requireApplied();
      assertEquals(BigInteger.valueOf(42), session.read("/count"));
      Contracts.Transition invalid =
          session.call(
              "input",
              "edit",
              Map.of(
                  "change",
                  Map.of("op", "replace", "path", "/count", "val", 99, "unexpected", true)),
              2);
      assertFalse(invalid.applied());
      assertEquals(BigInteger.valueOf(42), session.read("/count"));
      assertTrue(invalid.events().isEmpty());
    }
  }

  @Test
  void laterStepsKeepEarlierValuesAndEffectsAsSnapshotsOfTheirOwnStep() {
    try (Contracts.Session session =
        Contracts.create()
            .openYaml(
                """
                payload: {nested: {count: 1}}
                observed: {}
                contracts:
                  input: {type: Contracts/External Channel}
                  edit:
                    type: Contracts/Sequential Workflow Operation
                    channel: input
                    steps:
                      - type: Contracts/Compute
                        name: original
                        expr: {state: {$document: /payload}}
                      - type: Contracts/Update Document
                        name: changed
                        changeset: [{op: replace, path: /payload/nested/count, val: 2}]
                      - type: Contracts/Trigger Event
                        name: emitted
                        event: {kind: Snapshot, state: {$steps: original.state}}
                      - type: Contracts/Update Document
                        changeset:
                          - op: replace
                            path: /observed
                            val:
                              original: {$steps: original.state.nested.count}
                              changed: {$steps: changed.changeset.0.val}
                              emitted: {$steps: emitted.state.nested.count}
                """)) {
      session.initialize().requireApplied();
      Contracts.Transition result = session.call("input", "edit", Map.of(), 1).requireApplied();
      assertEquals(BigInteger.TWO, session.read("/payload/nested/count"));
      assertEquals(
          Map.of("original", BigInteger.ONE, "changed", BigInteger.TWO, "emitted", BigInteger.ONE),
          session.read("/observed"));
      assertEquals(
          List.of(
              Map.of(
                  "kind", "Snapshot", "state", Map.of("nested", Map.of("count", BigInteger.ONE)))),
          result.events());
      try (Contracts.Session restored =
          Contracts.create().restore(session.snapshot(), session.identity())) {
        assertEquals(session.read(""), restored.read(""));
      }
    }
  }
}
