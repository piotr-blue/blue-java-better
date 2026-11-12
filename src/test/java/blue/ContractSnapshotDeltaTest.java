package blue;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Transport omission never changes the canonical result and never grants missing evidence. */
@Tag("contracts")
class ContractSnapshotDeltaTest {
  private static final String SOURCE =
      """
      count: 0
      contracts:
        input: {type: Contracts/External Channel}
        update:
          type: Contracts/Sequential Workflow
          channel: input
          steps:
            - type: Contracts/Update Document
              changeset:
                - op: replace
                  path: /count
                  val: {$event: /request}
      """;

  @Test
  @SuppressWarnings("unchecked")
  void retainedContentAndDeltaRestoreTheSamePortableSnapshotAndNextResult() {
    try (var session = Contracts.create().openYaml(SOURCE)) {
      session.initialize().requireApplied();
      var before = session.snapshot();
      var retained = (Map<String, Object>) before.get("content");
      try (var transaction = session.begin(Map.of("request", 1), 1)) {
        transaction.finish().requireApplied();
        var full = transaction.snapshot();
        var delta = transaction.snapshot(id -> retained.containsKey(id.value()));
        var additions = (Map<String, Object>) delta.get("content");
        assertTrue(additions.size() < ((Map<?, ?>) full.get("content")).size());
        var combined = new HashMap<>(retained);
        combined.putAll(additions);
        var envelope = new HashMap<>(delta);
        envelope.put("content", combined);
        try (var restored = Contracts.create().restore(envelope, transaction.identity())) {
          assertEquals(full, restored.snapshot());
          transaction.commit();
          var event = Map.of("request", 2);
          assertEquals(session.process(event, 2).receipt(), restored.process(event, 2).receipt());
        }
      }
    }
  }

  @Test
  void claimingRetentionWithoutSupplyingItStillFailsVerification() {
    try (var session = Contracts.create().openYaml(SOURCE)) {
      session.initialize().requireApplied();
      try (var transaction = session.begin(Map.of("request", 1), 1)) {
        var incomplete = transaction.snapshot(id -> true);
        assertTrue(((Map<?, ?>) incomplete.get("content")).isEmpty());
        assertThrows(
            BlueException.class,
            () -> Contracts.create().restore(incomplete, transaction.identity()));
      }
    }
  }
}
