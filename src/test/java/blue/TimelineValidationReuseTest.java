package blue;

import static blue.ExactNode.list;
import static blue.ExactNode.scalar;
import static blue.ExactNode.value;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.ExactNode.Ref;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Physical protocol verification reuse never changes input validity or exports authority. */
@Tag("coordination")
class TimelineValidationReuseTest {
  @Test
  void operationGrammarRetainsTheCurrentAttemptsLazyTypeResolver() {
    LanguageDocument type = Language.create().defineYaml("count: {type: Integer}");
    Language author = Language.newBuilder().content(type).build();
    LanguageDocument target = author.readYaml("type: {blueId: '" + type.blueId() + "'}\ncount: 1");
    var records = target.records();
    var demanded = new java.util.HashSet<BlueId>();
    Language reader =
        Language.newBuilder()
            .mount("Contracts", Contracts.publication())
            .provider(
                id -> {
                  demanded.add(id);
                  return java.util.Optional.ofNullable(records.get(id));
                })
            .build();
    LanguageContent request =
        reader.prepare(
            Language.Input.yaml(
                """
                type: Contracts/Operation Request
                channel: input
                operation: tick
                document: {blueId: '%s'}
                request: {}
                """
                    .formatted(target.blueId())));
    assertFalse(demanded.contains(type.blueId()), "Preparing references must stay lazy");
    TimelineContent fields = new TimelineContent(reader.context(request), Contracts.publication());
    var inspected = ContractChannels.operationRequest(fields.operations(request), request.semantic);
    assertEquals(Map.of("blueId", target.blueId().value()), inspected.get("document"));
    assertTrue(demanded.contains(type.blueId()), "The scoped provider supplies the deferred type");

    Language detached = Language.newBuilder().mount("Contracts", Contracts.publication()).build();
    LanguageContent unsupported = detached.prepare(Language.Input.exactJson(request.toJson()));
    TimelineContent missing =
        new TimelineContent(detached.context(unsupported), Contracts.publication());
    assertThrows(
        RuntimeException.class,
        () ->
            ContractChannels.operationRequest(
                missing.operations(unsupported), unsupported.semantic),
        "Another evidence context cannot inherit this attempt's provider or validation facts");
  }

  @Test
  void verifierFactsReuseUnchangedSubtreesButNotChangedConstraintsOrPublicSnapshots() {
    LanguageFacts facts = new LanguageFacts();
    ExactNode shared = document();
    ExactNode first =
        value(Map.of("unchanged", shared, "revision", scalar("Integer", java.math.BigInteger.ONE)));
    LanguageContext original = new LanguageContext(Language.Limits.defaults().wire());
    original.facts = facts;
    TimelineContent checked = new TimelineContent(original, ProtocolPublications.contracts());
    checked.validate(first);
    checked.rememberFacts();

    LanguageContext next = new LanguageContext(Language.Limits.defaults().wire());
    next.facts = facts;
    TimelineContent fields = new TimelineContent(next, ProtocolPublications.contracts());
    fields.validate(
        value(
            Map.of("unchanged", shared, "revision", scalar("Integer", java.math.BigInteger.TWO))));
    assertTrue(next.work.get() * 5 < original.work.get(), "Only changed structure is checked");
    ExactNode invalid =
        value(Map.of("type", new Ref(LanguageCore.CORE.get("Integer")), "value", "invalid"));
    assertThrows(
        LanguageFailure.class,
        () -> fields.validate(value(Map.of("unchanged", shared, "revision", invalid))));

    LanguageContext exported =
        new LanguageContext(
            Map.of("steps", 20L),
            new Language.Snapshot(next.bodies, next.proofs),
            next.aliases,
            next.directiveAliases,
            next.provider);
    assertNull(exported.facts);
    assertThrows(
        LanguageFailure.class,
        () -> new TimelineContent(exported, ProtocolPublications.contracts()).validate(first));
  }

  @Test
  void repeatedClosureChecksReuseOnlyCompletedWorkWithinOneEvidenceView() {
    LanguageContext context = new LanguageContext(Language.Limits.defaults().wire());
    TimelineContent fields = new TimelineContent(context, ProtocolPublications.contracts());
    ExactNode node = document();
    fields.validate(node);
    long before = context.work.get();
    fields.validate(node);
    assertTrue(before > 200);
    assertTrue(context.work.get() - before < 10, "Already verified children are not walked again");
    ExactNode invalid =
        value(Map.of("type", new Ref(LanguageCore.CORE.get("Integer")), "value", "not an integer"));
    assertThrows(LanguageFailure.class, () -> fields.validate(invalid));
    assertThrows(LanguageFailure.class, () -> fields.validate(invalid));
  }

  @Test
  void snapshotsCarryContentButNeverThePreviousRequestsVerificationBudget() {
    LanguageContext original = new LanguageContext(Language.Limits.defaults().wire());
    new TimelineContent(original, ProtocolPublications.contracts()).validate(document());
    LanguageContext next =
        new LanguageContext(
            Map.of("steps", 20L),
            new Language.Snapshot(original.bodies, original.proofs),
            original.aliases,
            original.directiveAliases,
            original.provider);
    TimelineContent fields = new TimelineContent(next, ProtocolPublications.contracts());
    assertTrue(next.verifiedClosures.isEmpty());
    assertThrows(LanguageFailure.class, () -> fields.validate(document()));
  }

  @Test
  void completedPresenceFactsReuseAbstractClosuresWithoutExportingThem() {
    Map<String, Object> fields = new java.util.LinkedHashMap<>();
    for (int i = 0; i < 64; i++)
      fields.put(
          "field" + i,
          value(
              Map.of(
                  "type", new Ref(LanguageCore.CORE.get("Integer")), "description", "field " + i)));
    ExactNode definition = value(fields);
    LanguageFacts facts = new LanguageFacts();
    LanguageContext first = new LanguageContext(Language.Limits.defaults().wire());
    first.facts = facts;
    TimelineContent checked = new TimelineContent(first, ProtocolPublications.contracts());
    checked.validate(definition);
    assertFalse(LanguageSemantics.concreteNode(definition, first));
    checked.rememberFacts();

    LanguageContext next = new LanguageContext(Language.Limits.defaults().wire());
    next.facts = facts;
    new TimelineContent(next, ProtocolPublications.contracts());
    assertFalse(LanguageSemantics.concreteNode(definition, next));
    assertTrue(next.work.get() < 5, "The completed abstract subtree needs no traversal");
    var changed = new java.util.LinkedHashMap<>(fields);
    changed.put("field0", scalar("Integer", java.math.BigInteger.ONE));
    assertTrue(
        LanguageSemantics.concreteNode(value(changed), next),
        "Changed content cannot inherit a negative fact");
    assertTrue(
        LanguageSemantics.concreteNode(
            value(Map.of("child", definition, "valueChild", scalar("Text", "present"))), next));

    LanguageContext ordinary = new LanguageContext(Language.Limits.defaults().wire());
    ordinary.reuseWithinHostBudget = true;
    assertFalse(LanguageSemantics.concreteNode(definition, ordinary));
    assertTrue(ordinary.work.get() > 100, "Ordinary Language does not inherit protocol facts");
    LanguageContext bounded = new LanguageContext(Map.of("steps", 20L));
    bounded.facts = facts;
    assertThrows(
        LanguageFailure.class,
        () -> LanguageSemantics.concreteNode(definition, bounded),
        "A logical-budget invocation cannot reuse physical protocol work");
  }

  @Test
  void publisherSharesOnlyCompletedSourceBoundSemanticChecks() {
    BluePackage coordination =
        PackageLoader.create()
            .resource(
                getClass().getClassLoader(),
                "coordination/coordination.bluepkg",
                ProviderSignatures.COORDINATION_PACKAGE);
    Language base = Language.newBuilder().mount("Coordination", coordination).build();
    LanguageDocument provider =
        base.readYaml(
            """
            type: Coordination/Timeline Provider
            endpoint: https://provider.example
            publicKey: d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a
            """);
    LanguageDocument timeline =
        base.toBuilder()
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
    TimelinePublisher publisher = TimelinePublisher.forTimeline(coordination, provider, timeline);
    TimelineVerifier consumer = TimelineVerifier.adopt(coordination, provider, timeline);
    publisher.shareValidationWith(consumer);
    LanguageContent message = TimelineContent.detachedContent(document());
    assertFalse(publisher.validateMessage(message));
    LanguageContext reused = message.owner.context(message);
    reused.facts = consumer.facts;
    reused.reuseWithinHostBudget = true;
    LanguageSemantics.strict(message.semantic, reused, "definition");
    assertTrue(reused.work.get() < 10, "Publisher completed the exact definition already");
    LanguageSemantics.strict(message.semantic, reused, "value");
    assertTrue(reused.work.get() > 100, "Definition is not a value admission certificate");

    LanguageContent abstractValue = Language.create().prepare(Language.Input.yaml("type: Integer"));
    assertFalse(publisher.validateMessage(abstractValue));
    assertThrows(
        LanguageFailure.class,
        () -> LanguageSemantics.strict(abstractValue.semantic, reused, "value"));
    LanguageContent invalid =
        TimelineContent.detachedContent(
            value(Map.of("type", new Ref(LanguageCore.CORE.get("Integer")), "value", "invalid")));
    assertThrows(LanguageException.class, () -> publisher.validateMessage(invalid));
    assertFalse(consumer.facts.verified(invalid.blueId().value()));
    assertThrows(
        LanguageFailure.class,
        () -> LanguageSemantics.strict(invalid.semantic, reused, "definition"));

    LanguageDocument other =
        timeline.edit(edit -> edit.set("/url/value", "https://provider.example/other"));
    TimelineVerifier wrong = TimelineVerifier.adopt(coordination, provider, other);
    LanguageFacts before = wrong.facts;
    assertEquals(
        "PUBLISHER_VALIDATION_SOURCE",
        assertThrows(BlueException.class, () -> publisher.shareValidationWith(wrong)).code());
    assertSame(before, wrong.facts);
    assertTrue(consumer.initial().headId().isEmpty(), "No source history was shared");
  }

  private static ExactNode document() {
    List<ExactNode> items = new ArrayList<>();
    for (int i = 0; i < 200; i++) items.add(scalar("Text", "item-" + i));
    return list(items);
  }
}
