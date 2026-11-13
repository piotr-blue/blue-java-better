package blue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Borrowed content is resolved lazily, verified normally, and released with its invocation. */
@Tag("contracts")
@SuppressWarnings("try")
class ContractContentScopeTest {
  @Test
  void oneMessageDoesNotImportThePublishersUnrelatedCatalogue() {
    Language language = Language.create();
    Language.Builder catalogue = language.toBuilder();
    for (int i = 0; i < 10_000; i++) {
      catalogue.content(language.prepare(Language.Input.yaml("unrelated: item-" + i)));
    }
    LanguageContent target = language.prepare(Language.Input.yaml("selected: yes"));
    LanguageContent unread = language.prepare(Language.Input.yaml("unread: private"));
    Language sender = catalogue.content(target).content(unread).build();
    LanguageContent message = sender.reference(target.blueId());
    ContractLanguage receiver = new ContractLanguage(language);
    int before = receiver.evidence().size();
    ContractLanguage captured;
    try (var scope = receiver.withContent(message)) {
      assertEquals(
          before, receiver.evidence().size(), "Opening a scope must not copy its catalogue");
      assertEquals(target.semantic, receiver.open(message.semantic));
      assertEquals(before + 1, receiver.evidence().size());
      captured = receiver.readSnapshot();
    }
    assertEquals(target.semantic, receiver.open(message.semantic));
    assertEquals(target.semantic, captured.open(message.semantic));
    assertThrows(
        LanguageFailure.class, () -> receiver.open(new ExactNode.Ref(unread.blueId().value())));
    assertThrows(
        LanguageFailure.class, () -> captured.open(new ExactNode.Ref(unread.blueId().value())));
  }

  @Test
  void neededExternalRecordsAreStillVerifiedAndFailuresDoNotPoisonTheNextScope() {
    Language base = Language.create();
    LanguageContent target = base.prepare(Language.Input.yaml("answer: 42"));
    LanguageContent wrong = base.prepare(Language.Input.yaml("answer: 41"));
    AtomicInteger calls = new AtomicInteger();
    Language bad =
        Language.newBuilder()
            .provider(
                id -> {
                  calls.incrementAndGet();
                  return Optional.of(
                      LanguageRecord.fromJson(wrong.records().get(wrong.blueId()).toJson()));
                })
            .build();
    ContractLanguage receiver = new ContractLanguage(base);
    try (var scope = receiver.withContent(bad.reference(target.blueId()))) {
      assertEquals(0, calls.get());
      assertThrows(
          LanguageFailure.class, () -> receiver.open(new ExactNode.Ref(target.blueId().value())));
      assertEquals(1, calls.get());
    }
    try (var scope = receiver.withContent(target)) {
      assertEquals(target.semantic, receiver.open(new ExactNode.Ref(target.blueId().value())));
    }
  }

  @Test
  void aCapturedViewCanRecoverFromRejectedRawEvidenceAfterItsProviderIsReplaced() {
    var language = Language.create();
    var correct = language.readYaml("answer: 42");
    var counterfeit = language.readYaml("answer: 41");
    var query = new ExactNode.Ref(correct.blueId().value());
    var receiver = new ContractLanguage(language);
    ContractLanguage view;
    try (var scope =
        receiver.withProvider(
            id -> Optional.of(LanguageRecord.json(counterfeit.canonicalJson())))) {
      view = receiver.readSnapshot();
      assertEquals(
          "ProviderBlueIdMismatch",
          assertThrows(LanguageFailure.class, () -> view.open(query)).category());
    }
    try (var scope =
        view.withProvider(id -> Optional.of(LanguageRecord.json(correct.canonicalJson())))) {
      assertEquals(correct.blueId().value(), ContractLanguage.identity(view.open(query)));
    }
  }

  @Test
  void cyclicContentKeepsItsProofAndCanBeExportedAfterTheScopeCloses() {
    Language base = Language.create();
    LanguageDocument member =
        base.finalizeSet(
                List.of(
                    Language.Input.exactJson(
                        "{\"name\":\"Loop\",\"self\":{\"blueId\":\"this#0\"}}")),
                Language.Goal.VALUE)
            .members()
            .getFirst();
    ContractLanguage receiver = new ContractLanguage(base);
    ExactNode reference = new ExactNode.Ref(member.blueId().value());
    try (var scope = receiver.withContent(member)) {
      receiver.open(reference);
    }
    Language.Builder exported = Language.newBuilder();
    receiver
        .records(reference)
        .forEach(
            (id, raw) ->
                exported.record(new BlueId(id), new LanguageRecord(LanguageCore.map(raw))));
    assertEquals(member.blueId(), exported.build().read(member.blueId()).blueId());
  }
}
