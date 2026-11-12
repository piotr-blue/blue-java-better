package blue;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Admission evidence survives inline/reference transport without changing the logical ledger. */
@Tag("contracts")
@Tag("conformance")
class CanonicalInputEvidenceTest {
  private static final String SOURCE =
      """
      seen: 0
      child:
        children: {}
        contracts:
          input: {type: Contracts/External Channel}
          embedded: {type: Contracts/Process Embedded, collectionPaths: [/children]}
          create:
            type: Contracts/Sequential Workflow
            channel: input
            event: {kind: Create}
            steps:
              - type: Contracts/Update Document
                changeset:
                  - op: add
                    path: /children/payment
                    val:
                      $literal:
                        paid: 0
                        contracts:
                          input: {type: Contracts/External Channel}
                          pay:
                            type: Contracts/Sequential Workflow
                            channel: input
                            event: {kind: Pay}
                            steps:
                              - type: Contracts/Update Document
                                changeset: [{op: replace, path: /paid, val: 1}]
                              - type: Contracts/Trigger Event
                                event: {kind: Paid}
          events: {type: Contracts/Embedded Node Channel, event: {kind: Paid}}
          forward:
            type: Contracts/Sequential Workflow
            channel: events
            steps:
              - type: Contracts/Trigger Event
                event: {kind: Paid}
      contracts:
        embedded: {type: Contracts/Process Embedded, paths: [/child]}
        events: {type: Contracts/Embedded Node Channel, event: {kind: Paid}}
        react:
          type: Contracts/Sequential Workflow
          channel: events
          steps:
            - type: Contracts/Update Document
              changeset: [{op: replace, path: /seen, val: {$add: [{$document: /seen}, 1]}}]
      """;

  @Test
  void creatingAndCallingADescendantHasIdenticalObservationsAcrossInputRepresentations() {
    Language language = Language.create();
    Contracts contracts = Contracts.create();
    try (Contracts.Session inline = contracts.openYaml(SOURCE);
        Contracts.Session referenced = contracts.openYaml(SOURCE)) {
      assertEquals(inline.initialize().receipt(), referenced.initialize().receipt());
      long timestamp = 0;
      for (String kind : List.of("Create", "Pay")) {
        LanguageContent event = language.prepare(Language.Input.yaml("kind: " + kind));
        LanguageContent reference =
            language.toBuilder().content(event).build().reference(event.blueId());
        Contracts.Transition a = inline.process(event, ++timestamp, AdmissionEvidence.empty());
        Contracts.Transition b =
            referenced.process(reference, timestamp, AdmissionEvidence.empty());
        a.requireApplied();
        b.requireApplied();
        assertEquals(a.gas(), b.gas());
        assertEquals(a.observations(), b.observations());
        assertEquals(a.receipt(), b.receipt());
      }
      assertEquals(java.math.BigInteger.ONE, inline.read("/seen"));
    }
  }

  @Test
  void inputTransportPreservesGasExhaustionAndRollbackAtBoundary() {
    LanguageContent event = Language.create().prepare(Language.Input.yaml("kind: Create"));
    LanguageContent reference =
        Language.newBuilder().content(event).build().reference(event.blueId());
    Map<String, Object> checkpoint;
    long gas;
    try (Contracts.Session session = Contracts.create().openYaml(SOURCE)) {
      session.initialize().requireApplied();
      checkpoint = session.snapshot();
      gas = session.process(event, 1, AdmissionEvidence.empty()).requireApplied().gas();
    }
    for (long limit : new long[] {1, gas / 2, gas - 1, gas, gas + 1}) {
      Contracts limited = Contracts.newBuilder().gasLimit(limit).build();
      try (Contracts.Session a = limited.restore(checkpoint, (String) checkpoint.get("identity"));
          Contracts.Session b = limited.restore(checkpoint, (String) checkpoint.get("identity"))) {
        Contracts.Transition x = a.process(event, 1, AdmissionEvidence.empty());
        Contracts.Transition y = b.process(reference, 1, AdmissionEvidence.empty());
        assertEquals(x.observations(), y.observations(), "limit=" + limit);
        assertEquals(x.receipt(), y.receipt(), "limit=" + limit);
        assertEquals(x.applied(), limit >= gas);
        if (!x.applied()) {
          assertEquals(checkpoint.get("identity"), a.identity());
          assertTrue(x.events().isEmpty());
        }
      }
    }
  }
}
