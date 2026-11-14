package blue.language;

import static blue.Language.Goal.VALUE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.BlueId;
import blue.Language;
import blue.LanguageContent;
import blue.LanguageDocument;
import blue.LanguageException;
import blue.LanguageRecord;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("language")
class ProviderTest {
  private final Language language = Language.create();

  @Test
  void externalProvidersAreLazyVerifiedAndDoNotConfuseUnavailableWithAbsent() {
    var supplied = language.readYaml("price: 42");
    var requests = new AtomicInteger();
    var reader =
        Language.newBuilder()
            .provider(
                id -> {
                  requests.incrementAndGet();
                  return Optional.of(LanguageRecord.json(supplied.toJson()));
                })
            .build();
    var ref =
        reader.prepare(Language.Input.exactJson("{\"blueId\":\"" + supplied.blueId() + "\"}"));
    assertEquals(0, requests.get());
    assertEquals(42, ref.resolve().at("/price").integer());
    assertEquals(1, requests.get());
    var wrong =
        Language.newBuilder().provider(id -> Optional.of(LanguageRecord.json("99"))).build();
    assertEquals(
        "ProviderBlueIdMismatch",
        assertThrows(LanguageException.class, () -> wrong.admit(ref, VALUE)).category());
    var missing =
        language.prepare(Language.Input.exactJson("{\"blueId\":\"" + supplied.blueId() + "\"}"));
    assertTrue(
        assertThrows(LanguageException.class, () -> missing.resolve().at("/price").exists())
            .incomplete());
  }

  @Test
  void referenceCreationIsLazyMissingEvidenceCanBeRetriedAndAdmissionRetainsEvidence() {
    LanguageDocument offered = language.readYaml("price: 42");
    Map<BlueId, LanguageRecord> store = new ConcurrentHashMap<>();
    AtomicInteger requests = new AtomicInteger();
    Language reader =
        Language.newBuilder()
            .provider(
                id -> {
                  requests.incrementAndGet();
                  return Optional.ofNullable(store.get(id));
                })
            .build();
    LanguageContent reference = reader.reference(offered.blueId());
    assertEquals(offered.blueId(), reference.blueId());
    assertEquals(0, requests.get());
    LanguageException missing =
        assertThrows(LanguageException.class, () -> reference.at("/price").exists());
    assertTrue(missing.incomplete());
    store.putAll(offered.records());
    LanguageDocument received = reader.read(offered.blueId());
    store.clear();
    int before = requests.get();
    assertEquals(42, received.at("/price").integer());
    assertFalse(received.at("/absent").exists());
    assertEquals(before, requests.get());
  }

  @Test
  void wrongProviderEvidenceRemainsRejected() {
    LanguageDocument offered = language.readYaml("price: 42");
    Language reader =
        Language.newBuilder().provider(id -> Optional.of(LanguageRecord.json("99"))).build();
    assertEquals(
        "ProviderBlueIdMismatch",
        assertThrows(LanguageException.class, () -> reader.read(offered.blueId())).category());
  }

  @Test
  void providerOrderAllowsAbsenceButDoesNotHideIncorrectEvidence() {
    var offered = language.readYaml("price: 42");
    var emptyCalls = new AtomicInteger();
    var laterCalls = new AtomicInteger();
    Language reader =
        Language.newBuilder()
            .provider(
                id -> {
                  emptyCalls.incrementAndGet();
                  return Optional.empty();
                })
            .provider(id -> Optional.of(LanguageRecord.json("99")))
            .provider(
                id -> {
                  laterCalls.incrementAndGet();
                  return Optional.of(LanguageRecord.json(offered.toJson()));
                })
            .build();
    LanguageException failure =
        assertThrows(LanguageException.class, () -> reader.read(offered.blueId()));
    assertEquals("ProviderBlueIdMismatch", failure.category());
    assertFalse(failure.incomplete());
    assertEquals(1, emptyCalls.get());
    assertEquals(0, laterCalls.get(), "A later provider must not mask a claimed body's mismatch");
  }

  @Test
  void providerFailureRetainsItsCauseAndDoesNotPreventALaterIndependentRetry() {
    var offered = language.readYaml("price: 42");
    var available = new AtomicBoolean();
    var unavailable = new UncheckedIOException(new IOException("temporarily unavailable"));
    var calls = new AtomicInteger();
    Language reader =
        Language.newBuilder()
            .provider(
                id -> {
                  calls.incrementAndGet();
                  if (!available.get()) {
                    throw unavailable;
                  }
                  return Optional.of(LanguageRecord.json(offered.toJson()));
                })
            .provider(
                id -> {
                  throw new AssertionError(
                      "An exception must not fall through to another provider");
                })
            .build();
    LanguageException failure =
        assertThrows(LanguageException.class, () -> reader.read(offered.blueId()));
    assertEquals("ProviderUnavailable", failure.category());
    assertTrue(failure.incomplete());
    assertSame(unavailable, failure.getCause().getCause());
    available.set(true);
    assertEquals(42, reader.read(offered.blueId()).at("/price").integer());
    assertEquals(2, calls.get());
  }
}
