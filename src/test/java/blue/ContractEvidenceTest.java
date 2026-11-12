package blue;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Restored evidence is verified independently and published only after the entire batch succeeds.
 */
@Tag("contracts")
@Tag("conformance")
class ContractEvidenceTest {
  @Test
  void providerDecodingDoesNotBecomeDocumentConstruction() {
    LanguageDocument definition =
        Language.create().defineYaml("name: Supplied Type\nmessage: {type: Text}");
    String id = definition.blueId().value();
    LanguageContext context = new LanguageContext();
    context.provider.put(id, LanguageRecord.json(definition.toJson()).response());
    var remembered = new java.util.ArrayList<ExactNode>();
    var constructed = new java.util.ArrayList<ExactNode>();
    context.onRemember = remembered::add;
    context.onConstruct = constructed::add;
    LanguageCore.open(new ExactNode.Ref(id), context);
    assertFalse(remembered.isEmpty(), "Provider verification must retain normalized evidence");
    assertTrue(
        constructed.isEmpty(), "Fetching supplied evidence cannot charge native construction");
    assertEquals(0, context.providerDepth);
    LanguageCore.normalize(Map.of("name", "Authored"), context, "exact", false);
    assertFalse(constructed.isEmpty(), "Actual authored construction must remain observable");
  }

  @Test
  void failedProviderDecodingRestoresTheConstructionBoundary() {
    LanguageContext context = new LanguageContext();
    String id = Language.create().readYaml("name: Expected").blueId().value();
    context.provider.put(id, LanguageRecord.json("name: [").response());
    var constructed = new java.util.ArrayList<ExactNode>();
    context.onConstruct = constructed::add;
    assertThrows(LanguageFailure.class, () -> LanguageCore.open(new ExactNode.Ref(id), context));
    assertEquals(0, context.providerDepth);
    assertTrue(constructed.isEmpty());
    LanguageCore.normalize(Map.of("name", "Following construction"), context, "exact", false);
    assertFalse(constructed.isEmpty());
  }

  @Test
  void canonicalInlineEvidenceCanVerifyASeparateResponseWithoutChangingItsShape() {
    Language language = Language.create();
    LanguageDocument child = language.readYaml("nested: {amount: 314159, list: [1, 2]}");
    LanguageDocument parent = language.readJson("{\"child\":" + child.toJson() + "}");
    Map<String, Object> batch = new LinkedHashMap<>();
    batch.put(parent.blueId().value(), LanguageRecord.json(parent.toJson()).response());
    batch.put(child.blueId().value(), LanguageRecord.json(child.toJson()).response());
    ContractLanguage access = new ContractLanguage(language);
    access.addEvidence(batch);
    assertEquals(
        child.toJson(),
        LanguageCore.exactText(access.open(new ExactNode.Ref(child.blueId().value()))));
    assertEquals(
        parent.toJson(),
        LanguageCore.exactText(access.open(new ExactNode.Ref(parent.blueId().value()))));
  }

  @Test
  void precedingInlineEvidenceCannotHideAnOversizedEquivalentResponse() {
    Language language = Language.create();
    LanguageDocument parent = language.readYaml("child: 271828");
    LanguageDocument child = language.readJson("271828");
    Map<String, Object> batch = new LinkedHashMap<>();
    batch.put(parent.blueId().value(), LanguageRecord.json(parent.toJson()).response());
    batch.put(
        child.blueId().value(), LanguageRecord.json(" ".repeat(5000) + child.toJson()).response());
    Language limited =
        Language.newBuilder()
            .limits(new Language.Limits(100000, 256, 100000, 100, 1024, 1000, 1000))
            .build();
    ContractLanguage access = new ContractLanguage(limited);
    Map<String, Object> before = access.evidence();
    assertEquals(
        "OperationLimitExceeded",
        assertThrows(LanguageFailure.class, () -> access.addEvidence(batch)).category());
    assertEquals(before, access.evidence());
  }

  @Test
  void canonicalDoubleTokensCannotBypassDecimalScaleLimits() {
    Language language = Language.create();
    String raw = "1000000000000000000000.0";
    LanguageDocument parent = language.readJson("{\"child\":" + raw + "}");
    LanguageDocument child = language.readJson(raw);
    Map<String, Object> batch = new LinkedHashMap<>();
    batch.put(parent.blueId().value(), LanguageRecord.json("{\"child\":" + raw + "}").response());
    batch.put(child.blueId().value(), LanguageRecord.json(child.toJson()).response());
    ContractLanguage access =
        new ContractLanguage(
            Language.newBuilder()
                .limits(new Language.Limits(100000, 256, 100000, 100, 1024, 1000, 2))
                .build());
    Map<String, Object> before = access.evidence();
    assertEquals(
        "OperationLimitExceeded",
        assertThrows(LanguageFailure.class, () -> access.addEvidence(batch)).category());
    assertEquals(before, access.evidence());
  }

  @Test
  void reusedInlineBodiesReplaceLaterEquivalentRepresentationsInBatchOrder() {
    Language language = Language.create();
    LanguageDocument nested = language.readYaml("amount: 968112233");
    LanguageDocument inline = language.readJson("{\"nested\":" + nested.toJson() + "}");
    Language supplied = Language.newBuilder().content(nested).build();
    LanguageDocument reference =
        supplied.readJson("{\"nested\":{\"blueId\":\"" + nested.blueId().value() + "\"}}");
    assertEquals(inline.blueId(), reference.blueId());
    LanguageDocument first = language.readJson("{\"first\":" + inline.toJson() + "}");
    LanguageDocument second = supplied.readJson("{\"second\":" + reference.toJson() + "}");
    Map<String, Object> batch = new LinkedHashMap<>();
    batch.put(first.blueId().value(), LanguageRecord.json(first.toJson()).response());
    batch.put(
        second.blueId().value(),
        Map.of("text", second.toJson(), "format", "yaml", "mode", "exact"));
    batch.put(inline.blueId().value(), LanguageRecord.json(inline.toJson()).response());
    ContractLanguage access = new ContractLanguage(language);
    access.addEvidence(batch);
    assertEquals(
        inline.toJson(),
        LanguageCore.exactText(access.open(new ExactNode.Ref(inline.blueId().value()))));
  }

  @Test
  void canonicalTextLimitsAreRecheckedWhenInlineEvidenceUsedShorthand() {
    Language language = Language.create();
    LanguageDocument parent = language.readJson("{\"child\":271828}");
    LanguageDocument child = language.readJson("271828");
    Map<String, Object> batch = new LinkedHashMap<>();
    batch.put(parent.blueId().value(), LanguageRecord.json("{\"child\":271828}").response());
    batch.put(child.blueId().value(), LanguageRecord.json(child.toJson()).response());
    for (Language.Limits limits :
        List.of(
            new Language.Limits(100000, 256, 100000, 100, 64, 1000, 1000),
            new Language.Limits(100000, 256, 2, 100, 1024, 1000, 1000),
            new Language.Limits(100000, 2, 100000, 100, 1024, 1000, 1000))) {
      ContractLanguage access = new ContractLanguage(Language.newBuilder().limits(limits).build());
      Map<String, Object> before = access.evidence();
      assertEquals(
          "OperationLimitExceeded",
          assertThrows(LanguageFailure.class, () -> access.addEvidence(batch)).category());
      assertEquals(before, access.evidence());
    }
  }

  @Test
  void aVerifiedInlineChildDoesNotAuthenticateItsForgedSeparateResponse() {
    Language language = Language.create();
    LanguageDocument parent = language.readYaml("count: 7");
    LanguageDocument child = language.readJson("7");
    ContractLanguage access = new ContractLanguage(language);
    Map<String, Object> before = access.evidence();
    Map<String, Object> batch = new LinkedHashMap<>();
    batch.put(parent.blueId().value(), LanguageRecord.json(parent.toJson()).response());
    batch.put(child.blueId().value(), LanguageRecord.json("99").response());
    assertEquals(
        "ProviderBlueIdMismatch",
        assertThrows(LanguageFailure.class, () -> access.addEvidence(batch)).category());
    assertEquals(before, access.evidence());
  }

  @Test
  void suppliedBuiltinsAreNotAcceptedFromTheAlreadyInstalledRegistry() {
    Language language = Language.create();
    ContractLanguage access = new ContractLanguage(language);
    String integer = language.readJson("7").at("").type().orElseThrow().value();
    assertEquals(
        "ProviderBlueIdMismatch",
        assertThrows(
                LanguageFailure.class,
                () -> access.addEvidence(Map.of(integer, LanguageRecord.json("99").response())))
            .category());
  }

  @Test
  void aPreviouslyVerifiedCyclicMemberCannotHideAnotherMembersFalseBodyClaim() {
    Language language = Language.create();
    Language.CyclicSet set =
        language.finalizeSet(
            List.of(
                Language.Input.exactJson("{\"name\":\"First\",\"next\":{\"blueId\":\"this#1\"}}"),
                Language.Input.exactJson("{\"name\":\"Second\",\"next\":{\"blueId\":\"this#0\"}}")),
            Language.Goal.VALUE);
    Map<String, Object> batch = new LinkedHashMap<>();
    batch.put(
        set.members().getFirst().blueId().value(),
        LanguageRecord.cyclic(set.proofJson()).response());
    batch.put(
        set.members().getLast().blueId().value(),
        LanguageRecord.cyclic(set.proofJson(), "{\"name\":\"Forged\"}").response());
    ContractLanguage access = new ContractLanguage(language);
    Map<String, Object> before = access.evidence();
    assertThrows(LanguageFailure.class, () -> access.addEvidence(batch));
    assertEquals(before, access.evidence());
    batch.put(
        set.members().getLast().blueId().value(),
        LanguageRecord.cyclic(set.proofJson()).response());
    access.addEvidence(batch);
    assertNotNull(access.open(new ExactNode.Ref(set.members().getLast().blueId().value())));
  }

  @Test
  void validRecordsCanReuseVerifiedDependenciesAcrossBatchesWithoutLosingOlderEvidence() {
    Language language = Language.create();
    ContractLanguage access = new ContractLanguage(language);
    LanguageDocument first = language.readYaml("first: 1");
    LanguageDocument second = language.readYaml("second: 2");
    for (LanguageDocument document : List.of(first, second, first)) {
      Map<String, Object> records = new LinkedHashMap<>();
      document.records().forEach((id, record) -> records.put(id.value(), record.response()));
      access.addEvidence(records);
    }
    assertEquals(
        first.blueId().value(),
        ContractLanguage.identity(access.open(new ExactNode.Ref(first.blueId().value()))));
    assertEquals(
        second.blueId().value(),
        ContractLanguage.identity(access.open(new ExactNode.Ref(second.blueId().value()))));
  }

  @Test
  void aWarmResponseCacheNeverAuthenticatesDifferentTextOrProviderModes() {
    Language language = Language.create();
    LanguageDocument document = language.readYaml("cached: 314159");
    String id = document.blueId().value();
    Object valid = LanguageRecord.json(document.toJson()).response();
    new ContractLanguage(language).addEvidence(Map.of(id, valid));
    ContractLanguage next = new ContractLanguage(language);
    assertEquals(
        "ProviderBlueIdMismatch",
        assertThrows(
                LanguageFailure.class,
                () -> next.addEvidence(Map.of(id, LanguageRecord.json("99").response())))
            .category());
    assertEquals(
        "InvalidProviderMode",
        assertThrows(
                LanguageFailure.class,
                () ->
                    next.addEvidence(
                        Map.of(id, Map.of("text", document.toJson(), "mode", "source"))))
            .category());
    assertFalse(next.evidence().containsKey(id));
    next.addEvidence(Map.of(id, valid));
    assertTrue(next.evidence().containsKey(id));
  }

  @Test
  void warmEvidenceDoesNotBypassAStricterHostLimit() {
    Language language = Language.create();
    LanguageDocument document = language.readYaml("text: '" + "x".repeat(5000) + "'");
    Map<String, Object> records =
        Map.of(document.blueId().value(), LanguageRecord.json(document.toJson()).response());
    new ContractLanguage(language).addEvidence(records);
    Language limited =
        Language.newBuilder()
            .limits(new Language.Limits(100000, 256, 100000, 100, 1024, 1000, 1000))
            .build();
    ContractLanguage next = new ContractLanguage(limited);
    assertEquals(
        "OperationLimitExceeded",
        assertThrows(LanguageFailure.class, () -> next.addEvidence(records)).category());
    assertFalse(next.evidence().containsKey(document.blueId().value()));
  }

  @Test
  void warmExactResponsesRetainInlineTypeBodiesAndTypedListPayloads() {
    Language language = Language.create();
    LanguageDocument type = language.defineYaml("count: {type: Integer}");
    LanguageDocument value = type.specializeYaml("count: 5");
    String text = "{\"type\":" + type.toJson() + ",\"count\":5}";
    LanguageDocument list = language.readYaml("name: Cached List\nitems: [1, 2]");
    String payload = language.readYaml("[1, 2]").blueId().value();
    Map<String, Object> records =
        Map.of(
            value.blueId().value(), LanguageRecord.json(text).response(),
            list.blueId().value(), LanguageRecord.json(list.toJson()).response());
    for (int attempt = 0; attempt < 2; attempt++) {
      ContractLanguage access = new ContractLanguage(language);
      access.addEvidence(records);
      assertEquals(
          type.blueId().value(),
          ContractLanguage.identity(access.open(new ExactNode.Ref(type.blueId().value()))));
      assertEquals(payload, ContractLanguage.identity(access.open(new ExactNode.Ref(payload))));
    }
  }
}
