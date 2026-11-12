package blue.contracts;

import static org.junit.jupiter.api.Assertions.*;

import blue.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Stateless request recognition shares execution grammar, without granting routing authority. */
class OperationRequestTest {
  private static final Language LANGUAGE =
      Language.newBuilder().mount("Contracts", Contracts.publication()).build();

  @Test
  void recognizesPrivateTargetAndCustomRequestTypeWithoutOpeningASession() {
    LanguageDocument target = LANGUAGE.readYaml("name: Private target\ncount: 3");
    LanguageDocument type =
        LANGUAGE.defineYaml("type: Contracts/Operation Request\nname: Custom request");
    Language owner = LANGUAGE.toBuilder().content(target).content(type).build();
    LanguageContent message =
        owner.prepare(
            Language.Input.json(
                """
                {"type":{"blueId":"%s"},"document":{"blueId":"%s"},"channel":"owner",
                 "operation":"increment","requireExactDocumentVersion":true,"request":{"delta":1}}
                """
                    .formatted(type.blueId(), target.blueId())));
    Contracts contracts = Contracts.newBuilder().language(LANGUAGE).build();
    var parsed = contracts.operationRequest(message).orElseThrow();
    assertEquals("increment", parsed.operation());
    assertEquals("owner", parsed.channel());
    assertEquals(Optional.of(target.blueId()), parsed.document());
    assertTrue(parsed.requireExactDocumentVersion());
    try (var session = contracts.openYaml("count: 0")) {
      assertEquals(Optional.of(parsed), session.operationRequest(message));
      assertEquals(0, session.document().at("/count").integer());
    }
  }

  @Test
  void distinguishesOtherMessagesAndAllowsAnUnaddressedGrammarWithoutAuthorizingIt() {
    Contracts contracts = Contracts.newBuilder().language(LANGUAGE).build();
    assertTrue(contracts.operationRequest(LANGUAGE.readYaml("kind: Something else")).isEmpty());
    var parsed =
        contracts
            .operationRequest(
                LANGUAGE.prepare(
                    Language.Input.yaml(
                        """
                        type: Contracts/Operation Request
                        channel: owner
                        operation: increment
                        """)))
            .orElseThrow();
    assertTrue(parsed.document().isEmpty());
    assertFalse(parsed.requireExactDocumentVersion());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "operation: ''",
        "channel: ''",
        "requireExactDocumentVersion: wrong",
        "unexpected: 1"
      })
  void rejectsMalformedOperationGrammarLikeSessionInspection(String invalid) {
    String input = "type: Contracts/Operation Request\nchannel: owner\noperation: increment\n";
    String key = invalid.substring(0, invalid.indexOf(':'));
    input = input.replaceAll("(?m)^" + key + ":.*\\n", "") + invalid + "\n";
    LanguageContent message = LANGUAGE.prepare(Language.Input.yaml(input));
    Contracts contracts = Contracts.newBuilder().language(LANGUAGE).build();
    BlueException failure =
        assertThrows(BlueException.class, () -> contracts.operationRequest(message));
    try (var session = contracts.openYaml("count: 0")) {
      assertEquals(
          assertThrows(BlueException.class, () -> session.operationRequest(message)).code(),
          failure.code());
    }
  }

  @Test
  void unavailableOrForgedTargetEvidenceCannotBecomeAnAdmissionShortcut() {
    LanguageDocument target = LANGUAGE.readYaml("count: 1");
    String input =
        "type: Contracts/Operation Request\n"
            + "channel: owner\n"
            + "operation: increment\n"
            + "document: {blueId: '"
            + target.blueId()
            + "'}";
    Contracts contracts = Contracts.newBuilder().language(LANGUAGE).build();
    LanguageContent missing = LANGUAGE.prepare(Language.Input.yaml(input));
    assertThrows(BlueException.class, () -> contracts.operationRequest(missing));
    Language forged =
        LANGUAGE.toBuilder()
            .provider(
                id ->
                    id.equals(target.blueId())
                        ? Optional.of(LanguageRecord.json(LANGUAGE.readYaml("count: 2").toJson()))
                        : Optional.empty())
            .build();
    assertThrows(
        BlueException.class,
        () -> contracts.operationRequest(forged.prepare(Language.Input.yaml(input))));
    assertThrows(NullPointerException.class, () -> contracts.operationRequest(null));
  }

  @Test
  void recoveredValuesCanBeBorrowedWithoutGivingAnotherCallTheirAuthority() {
    Language issuer = LANGUAGE;
    LanguageDocument value = issuer.readYaml("sequence: 0");
    for (int i = 1; i < 16; i++) {
      issuer = issuer.toBuilder().content(value).build();
      value = issuer.readYaml("sequence: " + i + "\nprevious: {blueId: '" + value.blueId() + "'}");
    }
    var records = value.records();
    var reads = new java.util.concurrent.atomic.AtomicInteger();
    Language stored =
        LANGUAGE.toBuilder()
            .provider(
                id -> {
                  reads.incrementAndGet();
                  return Optional.ofNullable(records.get(id));
                })
            .build();
    var checkpoint = value.checkpoint();
    LanguageDocument recovered =
        stored.restoreCheckpoint(checkpoint.record(), checkpoint.identity());
    LanguageContent message =
        LANGUAGE.prepare(
            Language.Input.yaml(
                "type: Contracts/Operation Request\n"
                    + "channel: owner\n"
                    + "operation: increment\n"
                    + "document: {blueId: '"
                    + value.blueId()
                    + "'}"));
    Contracts contracts = Contracts.newBuilder().language(LANGUAGE).build();
    var parsed = contracts.operationRequest(message, recovered).orElseThrow();
    assertEquals(Optional.of(value.blueId()), parsed.document());
    assertEquals(1, reads.get(), "Recognition must not reopen the certified target history");
    assertThrows(BlueException.class, () -> contracts.operationRequest(message));
    assertThrows(
        IllegalArgumentException.class,
        () -> contracts.operationRequest(message, LANGUAGE.defineYaml("type: Integer")));
    LanguageContent invalid =
        LANGUAGE.prepare(
            Language.Input.yaml(
                message.toYaml() + "\nrequest: {type: Integer, value: 1, schema: {minimum: 5}}"));
    assertThrows(BlueException.class, () -> contracts.operationRequest(invalid, recovered));
    assertEquals(Optional.of(parsed), contracts.operationRequest(message, recovered));
  }
}
