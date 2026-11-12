package blue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The portable kernel must reverify native evidence and retain the exact admitted gas prefix. */
@Tag("contracts")
class NativeAdmissionReplayTest {
  @Test
  void detachedChunkReverifiesEvidenceAndMatchesDirectReceiptIncludingAdmissionGas() {
    Language language = Language.newBuilder().mount("Contracts", Contracts.publication()).build();
    LanguageDocument type = language.defineYaml("type: Contracts/Channel");
    LanguageDocument semantics = language.readYaml("description: Portable admission test.");
    AtomicInteger verifications = new AtomicInteger();
    NativeChannel channel =
        NativeChannel.newBuilder(type, semantics, "portableadmission")
            .counter("verification", 3)
            .verifyAdmission(
                context -> {
                  verifications.incrementAndGet();
                  context.charge("verification", 2);
                  if (!context
                      .eventId()
                      .value()
                      .equals(((Map<?, ?>) context.evidence()).get("event")))
                    context.reject("Wrong payload commitment");
                  return Map.of("verified", true);
                })
            .accept(context -> Boolean.TRUE.equals(context.admission().get("verified")))
            .build();
    String source =
        """
        count: 0
        contracts:
          input: {type: {blueId: %s}}
          tick:
            type: Contracts/Sequential Workflow
            channel: input
            steps:
              - type: Contracts/Update Document
                changeset: [{op: replace, path: /count, val: 1}]
        """
            .formatted(type.blueId());
    ContractProcessor processor = new ContractProcessor();
    channel.install(processor);
    ContractProcessor.Document before = processor.createYaml(source);
    Map<String, Object> event = Map.of("tick", 1);
    Map<String, Object> evidence =
        Map.of(
            type.blueId().value(), Map.of("event", language.readYaml("tick: 1").blueId().value()));
    ContractProcessor.Result direct = processor.process(before, event, 7, evidence);
    assertEquals("APPLIED", direct.status());
    ContractAttempt attempt =
        processor.begin(
            before, event, 7, new ContractProcessor.GasPolicy(10_000_000, Map.of()), evidence);
    int chunks = 0;
    for (ContractAttempt.ChunkToken token = attempt.nextChunk();
        token != null;
        token = attempt.nextChunk()) {
      ContractProcessor detached = new ContractProcessor();
      channel.install(detached);
      int previous = verifications.get();
      ContractAttempt.ChunkResult result = detached.executeChunk(token.toData());
      assertTrue(verifications.get() > previous);
      attempt.accept(token, result.toData());
      chunks++;
    }
    assertTrue(chunks > 0);
    ContractProcessor.Result replayed = attempt.finish();
    assertEquals(direct.receipt(), replayed.receipt());
    assertEquals(
        ContractWire.write(direct.observations()), ContractWire.write(replayed.observations()));
    assertEquals(direct.gas(), replayed.gas());
  }
}
