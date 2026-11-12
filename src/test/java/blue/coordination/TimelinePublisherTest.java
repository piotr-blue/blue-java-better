package blue.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.BlueException;
import blue.BlueId;
import blue.BluePackage;
import blue.Contracts;
import blue.Language;
import blue.LanguageContent;
import blue.LanguageDocument;
import blue.LanguageException;
import blue.PackageLoader;
import blue.ProviderSignatures;
import blue.TimelinePublisher;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Publisher recovery is a protected local validation fact, never consumer evidence. */
@Tag("coordination")
class TimelinePublisherTest {
  private static final BluePackage COORDINATION =
      PackageLoader.create()
          .resource(
              TimelinePublisherTest.class.getClassLoader(),
              "coordination/coordination.bluepkg",
              ProviderSignatures.COORDINATION_PACKAGE);

  @Test
  void messageValidationReusesOnlyThisPublishersVerifiedImmutableContent() {
    Fixture f = new Fixture();
    LanguageDocument target = Language.create().readYaml("name: Target\nstatus: ready");
    Language complete =
        Language.newBuilder().mount("Contracts", Contracts.publication()).content(target).build();
    String source =
        "type: Contracts/Operation Request\nchannel: input\noperation: run\n"
            + "document: {blueId: '"
            + target.blueId()
            + "'}\nrequest: {}";
    LanguageContent message = complete.prepare(Language.Input.yaml(source));
    assertTrue(f.publisher.validateMessage(message));
    Language detached = Language.newBuilder().mount("Contracts", Contracts.publication()).build();
    LanguageContent same = detached.prepare(Language.Input.exactJson(message.toJson()));
    assertTrue(f.publisher.validateMessage(same));
    assertTrue(
        assertThrows(LanguageException.class, () -> f.publisher().validateMessage(same))
            .incomplete());
    assertTrue(
        assertThrows(LanguageException.class, () -> detached.admit(same, Language.Goal.DEFINITION))
            .incomplete());
    LanguageContent invalid =
        complete.prepare(Language.Input.yaml(source.replace("operation: run", "operation: 123")));
    assertThrows(LanguageException.class, () -> f.publisher.validateMessage(invalid));
    assertTrue(f.publisher.validateMessage(message));
    assertFalse(
        f.publisher.validateMessage(
            Language.create().prepare(Language.Input.yaml("ordinary: event"))));
  }

  @Test
  void admittedValuesProvideBoundedSchemaEvidenceButDefinitionsAndEditsDoNot() {
    Fixture f = new Fixture();
    LanguageDocument target = Language.create().readYaml("name: Target\nstatus: ready");
    assertTrue(f.publisher.retainValue(target));
    Language detached = Language.newBuilder().mount("Contracts", Contracts.publication()).build();
    LanguageContent message =
        detached.prepare(
            Language.Input.yaml(
                "type: Contracts/Operation Request\nchannel: input\noperation: run\n"
                    + "document: {blueId: '"
                    + target.blueId()
                    + "'}\nrequest: {}"));
    assertTrue(f.publisher.validateMessage(message));
    assertThrows(LanguageException.class, () -> f.publisher().validateMessage(message));
    assertThrows(LanguageException.class, () -> detached.admit(message, Language.Goal.VALUE));
    LanguageDocument changed = target.edit(edit -> edit.set("/status/value", "changed"));
    LanguageContent other =
        detached.prepare(
            Language.Input.exactJson(
                message.toJson().replace(target.blueId().value(), changed.blueId().value())));
    assertThrows(LanguageException.class, () -> f.publisher.validateMessage(other));
    assertThrows(
        IllegalArgumentException.class,
        () -> f.publisher.retainValue(Language.create().defineYaml("type: Integer")));
    assertFalse(
        f.publisher.retainValue(Language.create().readYaml("value: large literal")),
        "Literal payloads are not retained in detached target headers");
  }

  @Test
  void preparedEntriesReuseTheExactRetainedTargetWithoutTeachingOrdinaryAdmission() {
    Fixture f = new Fixture();
    LanguageDocument target = Language.create().readYaml("name: Target\nstatus: ready");
    Language language = f.language.toBuilder().mount("Contracts", Contracts.publication()).build();
    LanguageContent message =
        language.prepare(
            Language.Input.yaml(
                """
                type: Contracts/Operation Request
                channel: input
                operation: run
                document: {blueId: '%s'}
                request: {}
                """
                    .formatted(target.blueId())));
    LanguageContent entry =
        language.toBuilder()
            .content(message)
            .build()
            .prepare(
                Language.Input.yaml(
                    """
                    type: Coordination/Timeline Entry
                    timeline: {blueId: '%s'}
                    actor: {type: Coordination/Principal Actor, givenName: Alice}
                    timestamp: 1
                    message: {blueId: '%s'}
                    operation: {blueId: '%s'}
                    """
                        .formatted(f.timeline.blueId(), message.blueId(), message.blueId())));
    assertThrows(LanguageException.class, () -> f.publisher.prepare(f.publisher.initial(), entry));
    assertTrue(f.publisher.retainValue(target));
    assertEquals(
        Optional.of(entry.blueId()), f.publisher.prepare(f.publisher.initial(), entry).entryId());
    assertThrows(LanguageException.class, () -> language.admit(entry, Language.Goal.VALUE));
    TimelinePublisher isolated = f.publisher();
    assertThrows(LanguageException.class, () -> isolated.prepare(isolated.initial(), entry));
  }

  @Nested
  class SourceOrder {
    @Test
    void onlyTheExactIncreasingSuccessorCanAdvanceAPreparedHead() {
      Fixture f = new Fixture();
      LanguageContent first = f.entry(null, 10);
      TimelinePublisher.Prepared one = f.publisher.prepare(f.publisher.initial(), first);
      assertEquals(Optional.of(first.blueId()), one.entryId());
      rejected("TIMELINE_CHAIN", () -> f.publisher.prepare(one, f.entry(null, 20)));
      rejected("TIMELINE_TIME", () -> f.publisher.prepare(one, f.entry(first.blueId(), 10)));
      LanguageContent second = f.entry(first.blueId(), 20);
      assertEquals(Optional.of(second.blueId()), f.publisher.prepare(one, second).entryId());
      assertEquals(Optional.of(first.blueId()), one.entryId());
      TimelinePublisher peer = f.publisher();
      rejected("PUBLISHER_CHECKPOINT_OWNER", () -> peer.prepare(one, second));
    }

    @Test
    void candidateSchemaStillRejectsAnInvalidActor() {
      Fixture f = new Fixture();
      LanguageContent valid = f.entry(null, 1);
      String text = valid.toJson();
      String actor = COORDINATION.export("Principal Actor").value();
      LanguageContent invalid =
          f.language.prepare(
              Language.Input.exactJson(
                  text.replace(actor, COORDINATION.export("Timeline Provider").value())));
      assertThrows(BlueException.class, () -> f.publisher.prepare(f.publisher.initial(), invalid));
      assertEquals(
          Optional.of(valid.blueId()), f.publisher.prepare(f.publisher.initial(), valid).entryId());
    }
  }

  @Nested
  class ProtectedRecovery {
    @Test
    void coldRecoveryNeedsOnlyTheCurrentHeadAndDoesNotTeachOrdinaryAdmission() {
      Fixture f = new Fixture();
      TimelinePublisher.Prepared head = f.publisher.initial();
      BlueId previous = null;
      for (int i = 0; i < 24; i++) {
        LanguageContent entry = f.entry(previous, i);
        head = f.publisher.prepare(head, entry);
        previous = entry.blueId();
      }
      assertTrue(head.checkpoint().toString().length() < 2000);
      TimelinePublisher restarted = f.publisher();
      TimelinePublisher.Prepared recovered =
          restarted.restore(head.checkpoint(), head.identity(), id -> Optional.empty());
      assertEquals(head.identity(), recovered.identity());
      assertEquals(head.entryId(), recovered.entryId());
      LanguageContent next = f.entry(previous, 24);
      assertEquals(Optional.of(next.blueId()), restarted.prepare(recovered, next).entryId());
      LanguageException missing =
          assertThrows(LanguageException.class, () -> f.language.admit(next, Language.Goal.VALUE));
      assertTrue(missing.incomplete());
    }

    @Test
    void changedDataWrongCommitmentAndOtherSourcesCannotRestore() {
      Fixture f = new Fixture();
      TimelinePublisher.Prepared head =
          f.publisher.prepare(f.publisher.initial(), f.entry(null, 1));
      rejected(
          "UNTRUSTED_PUBLISHER_CHECKPOINT",
          () -> f.publisher.restore(head.checkpoint(), "untrusted", id -> Optional.empty()));
      Map<String, Object> changed = new HashMap<>(head.checkpoint());
      changed.put("entry", f.entry(null, 2).toJson());
      rejected(
          "UNTRUSTED_PUBLISHER_CHECKPOINT",
          () -> f.publisher.restore(changed, head.identity(), id -> Optional.empty()));
      LanguageDocument other =
          f.timeline.edit(e -> e.set("/url/value", "https://provider.example/other"));
      TimelinePublisher wrong = TimelinePublisher.forTimeline(COORDINATION, f.provider, other);
      rejected(
          "PUBLISHER_CHECKPOINT_SOURCE",
          () -> wrong.restore(head.checkpoint(), head.identity(), id -> Optional.empty()));
      TimelinePublisher.Prepared empty = f.publisher.initial();
      assertTrue(
          f.publisher
              .restore(empty.checkpoint(), empty.identity(), id -> Optional.empty())
              .entryId()
              .isEmpty());
    }
  }

  private static void rejected(String code, Runnable action) {
    assertEquals(code, assertThrows(BlueException.class, action::run).code());
  }

  private static final class Fixture {
    final LanguageDocument provider, timeline;
    final Language language;
    final TimelinePublisher publisher;

    Fixture() {
      Language base = Language.newBuilder().mount("Coordination", COORDINATION).build();
      provider =
          base.readYaml(
              """
              type: Coordination/Timeline Provider
              endpoint: https://provider.example
              publicKey: d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a
              """);
      timeline =
          Language.newBuilder()
              .mount("Coordination", COORDINATION)
              .content(provider)
              .build()
              .readYaml(
                  """
                  type: Coordination/Signed Timeline
                  provider: {blueId: '%s'}
                  url: https://provider.example/history
                  publicRead: true
                  """
                      .formatted(provider.blueId()));
      language =
          Language.newBuilder()
              .mount("Coordination", COORDINATION)
              .content(provider)
              .content(timeline)
              .build();
      publisher = publisher();
    }

    TimelinePublisher publisher() {
      return TimelinePublisher.forTimeline(COORDINATION, provider, timeline);
    }

    LanguageContent entry(BlueId previous, long time) {
      return language.prepare(
          Language.Input.yaml(
              """
              type: Coordination/Timeline Entry
              timeline: {blueId: '%s'}
              actor: {type: Coordination/Principal Actor, givenName: Alice}
              timestamp: %s
              message: {amount: 7}
              %s
              """
                  .formatted(
                      timeline.blueId(),
                      time,
                      previous == null ? "" : "prevEntry: {blueId: '" + previous + "'}")));
    }
  }
}
