package blue.language;

import static org.junit.jupiter.api.Assertions.*;

import blue.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Protected admission recovery is separate from ordinary untrusted content verification. */
@Tag("language")
class CheckpointTest {
  @Test
  void mountingRecoveredContentKeepsItsLazyProvidersWithoutTransferringAdmission() {
    Language issuer = Language.create();
    LanguageDocument child = issuer.readYaml("name: deferred child\ncount: 7");
    LanguageDocument parent =
        issuer.toBuilder()
            .content(child)
            .build()
            .readYaml("child: {blueId: '" + child.blueId() + "'}");
    var records = parent.records();
    var reads = new AtomicInteger();
    Language lazy =
        Language.newBuilder()
            .provider(
                id -> {
                  reads.incrementAndGet();
                  return Optional.ofNullable(records.get(id));
                })
            .build();
    var checkpoint = parent.checkpoint();
    LanguageDocument recovered = lazy.restoreCheckpoint(checkpoint.record(), checkpoint.identity());
    assertEquals(1, reads.get());
    Language receiver = Language.newBuilder().content(recovered).content(recovered).build();
    assertEquals(1, reads.get(), "Mounting must not fetch the deferred closure");
    LanguageContent reference = receiver.reference(parent.blueId());
    assertEquals(7, reference.at("/child/count").integer());
    assertTrue(reads.get() > 1, "The deferred body remains available from its provider");
    assertEquals(parent.blueId(), receiver.read(parent.blueId()).blueId());
    assertEquals(parent.records().keySet(), reference.records().keySet());
    Language unrelated = Language.create();
    LanguageDocument request =
        unrelated.read(
            Language.Input.yaml("document: {blueId: '" + parent.blueId() + "'}"),
            Language.Goal.DEFINITION,
            recovered);
    assertEquals(7, request.at("/document/child/count").integer());
    assertEquals(parent.blueId(), unrelated.admit(recovered, Language.Goal.VALUE).blueId());
  }

  @Test
  void recoversPriorAdmissionWithoutReopeningAnUnrelatedHistory() {
    Language issuer = Language.create();
    LanguageDocument value = issuer.readYaml("sequence: 0");
    for (int i = 1; i < 32; i++) {
      issuer = issuer.toBuilder().content(value).build();
      value = issuer.readYaml("sequence: " + i + "\nprevious: {blueId: '" + value.blueId() + "'}");
    }
    var checkpoint = value.checkpoint();
    var records = value.records();
    var reads = new AtomicInteger();
    Language recovered =
        Language.newBuilder()
            .provider(
                id -> {
                  reads.incrementAndGet();
                  return Optional.ofNullable(records.get(id));
                })
            .build();
    LanguageDocument restored =
        recovered.restoreCheckpoint(checkpoint.record(), checkpoint.identity());
    assertEquals(value.blueId(), restored.blueId());
    assertEquals(Language.Goal.VALUE, restored.goal());
    assertEquals(value.expand("").toJson(), restored.expand("").toJson());
    assertEquals(checkpoint.identity(), restored.checkpoint().identity());
    assertEquals(1, reads.get(), "Only the selected immutable body is needed for recovery");
    var input = Language.Input.yaml("document: {blueId: '" + value.blueId() + "'}\nrequest: hello");
    LanguageDocument admitted = recovered.read(input, Language.Goal.DEFINITION, restored);
    assertEquals(1, reads.get(), "Prior completion is reusable within this new admission");
    assertEquals(31, admitted.at("/document/sequence").integer());
    reads.set(0);
    LanguageDocument independent = recovered.read(value.blueId());
    assertEquals(restored.blueId(), independent.blueId());
    assertTrue(reads.get() >= 32, "Ordinary read never consumes a local checkpoint implicitly");
    assertEquals(wire(value), wire(restored));
    assertThrows(
        UnsupportedOperationException.class, () -> checkpoint.record().put("blueId", "changed"));
  }

  @Test
  void rejectsChangedCommitmentsEditionsAndShape() {
    Language language = Language.create();
    LanguageDocument value = language.readYaml("status: ready");
    var checkpoint = value.checkpoint();
    Language source = language.toBuilder().content(value).build();
    assertThrows(
        IllegalArgumentException.class,
        () -> source.restoreCheckpoint(checkpoint.record(), "sha256:untrusted"));
    for (var change :
        Map.of(
                "language",
                "future-edition",
                "blueId",
                language.readYaml("status: other").blueId().value(),
                "format",
                "another-format",
                "unexpected",
                "field")
            .entrySet()) {
      var modified = new HashMap<>(checkpoint.record());
      modified.put(change.getKey(), change.getValue());
      assertThrows(
          IllegalArgumentException.class,
          () -> source.restoreCheckpoint(modified, checkpoint.identity()));
    }
    assertThrows(
        IllegalArgumentException.class, () -> language.defineYaml("type: Integer").checkpoint());
    assertThrows(
        NullPointerException.class, () -> source.restoreCheckpoint(null, checkpoint.identity()));
    assertThrows(
        NullPointerException.class, () -> source.restoreCheckpoint(checkpoint.record(), null));
  }

  @Test
  void missingOrForgedSelectedContentStillFails() {
    Language language = Language.create();
    LanguageDocument value = language.readYaml("count: 1");
    var checkpoint = value.checkpoint();
    assertThrows(
        LanguageException.class,
        () -> language.restoreCheckpoint(checkpoint.record(), checkpoint.identity()));
    Language forged =
        Language.newBuilder()
            .provider(id -> Optional.of(LanguageRecord.json("{\"count\":2}")))
            .build();
    assertThrows(
        LanguageException.class,
        () -> forged.restoreCheckpoint(checkpoint.record(), checkpoint.identity()));
  }

  @Test
  void recoveryDoesNotBlessNewRequestsOrContextualRefinements() {
    Language language = Language.create();
    LanguageDocument value = language.readYaml("status: ready");
    var checkpoint = value.checkpoint();
    Language source = language.toBuilder().content(value).build();
    LanguageDocument restored =
        source.restoreCheckpoint(checkpoint.record(), checkpoint.identity());
    LanguageDocument restriction = language.defineYaml("document: {status: closed}");
    Language current = source.toBuilder().content(restriction).build();
    assertThrows(
        LanguageException.class,
        () ->
            current.read(
                Language.Input.yaml(
                    "type: {blueId: '"
                        + restriction.blueId()
                        + "'}\ndocument: {blueId: '"
                        + value.blueId()
                        + "'}"),
                Language.Goal.VALUE,
                restored));
    assertThrows(
        LanguageException.class,
        () ->
            current.read(
                Language.Input.yaml(
                    "document: {blueId: '"
                        + value.blueId()
                        + "'}\nrequest: {type: Integer, value: wrong}"),
                Language.Goal.DEFINITION,
                restored));
    assertEquals(value.blueId(), restored.blueId());
  }

  @Test
  void cyclicRecoveryKeepsOwningProofAndPortableContentObligations() {
    Language language = Language.create();
    var cycle =
        language.finalizeSet(
            List.of(
                Language.Input.exactJson("{\"label\":\"cycle\",\"peer\":{\"blueId\":\"this#0\"}}")),
            Language.Goal.VALUE);
    var member = cycle.members().getFirst();
    var checkpoint = member.checkpoint();
    var records = member.records();
    Language stored =
        Language.newBuilder().provider(id -> Optional.ofNullable(records.get(id))).build();
    LanguageDocument restored =
        stored.restoreCheckpoint(checkpoint.record(), checkpoint.identity());
    assertEquals(member.blueId(), restored.blueId());
    assertEquals("cycle", restored.at("/peer/label").text());
    assertEquals(wire(member), wire(restored));
    Language forged =
        Language.newBuilder()
            .record(
                member.blueId(),
                LanguageRecord.cyclic(cycle.proofJson(), "{\"label\":\"changed\"}"))
            .build();
    assertThrows(
        LanguageException.class,
        () -> forged.restoreCheckpoint(checkpoint.record(), checkpoint.identity()));
  }

  private static Map<BlueId, String> wire(LanguageContent content) {
    var result = new HashMap<BlueId, String>();
    content.records().forEach((id, record) -> result.put(id, record.toJson()));
    return result;
  }
}
