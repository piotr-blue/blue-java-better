package blue;

import static blue.ContractLanguage.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Captured views retain immutable projections, never mutable resolver or verification authority.
 */
@SuppressWarnings("try")
@Tag("contracts")
class ContractLanguageSnapshotTest {
  @Test
  void forksReuseCompletedBodyClosuresWithinABoundedPhysicalBudget() {
    Language.Limits defaults = Language.Limits.defaults();
    Language bounded =
        Language.newBuilder()
            .limits(
                new Language.Limits(
                    8,
                    defaults.depth(),
                    defaults.values(),
                    defaults.fetches(),
                    defaults.textBytes(),
                    defaults.numericCharacters(),
                    defaults.decimalScale()))
            .build();
    ContractLanguage source = new ContractLanguage(bounded);
    List<ExactNode> children = new ArrayList<>();
    // Each independently retained child fits the physical budget; walking all 64 does not.
    for (int i = 0; i < 64; i++) {
      ExactNode child = ExactNode.value(Map.of("value", "child-" + i));
      source.refreshed().retain(List.of(child));
      children.add(child);
    }
    ExactNode shared = ExactNode.list(children);
    source.refreshed().retain(List.of(shared));
    ContractLanguage fork = source.fork();
    ExactNode changed =
        ExactNode.value(Map.of("shared", shared, "new", ExactNode.value(Map.of("value", "new"))));
    assertDoesNotThrow(() -> fork.retain(List.of(changed)));
    assertEquals(identity(changed), identity(fork.open(new ExactNode.Ref(identity(changed)))));
    assertThrows(LanguageFailure.class, () -> source.open(new ExactNode.Ref(identity(changed))));
    assertThrows(
        LanguageFailure.class,
        () -> source.readSnapshot().open(new ExactNode.Ref(identity(changed))));
  }

  @Test
  void failedRetentionDoesNotCertifyAPartialClosure() {
    ContractLanguage source = new ContractLanguage(Language.create());
    ExactNode leaf = ExactNode.scalar("Text", "missing");
    ExactNode root = ExactNode.value(Map.of("child", new ExactNode.Ref(identity(leaf))));
    assertThrows(LanguageFailure.class, () -> source.retain(List.of(root)));
    assertThrows(LanguageFailure.class, () -> source.fork().retain(List.of(root)));
    try (var provider =
        source.withProvider(id -> Optional.of(LanguageRecord.json(exactText(leaf))))) {
      source.retain(List.of(root));
    }
    ContractLanguage fork = source.fork();
    assertDoesNotThrow(() -> fork.retain(List.of(root)));
    assertEquals(leaf, fork.open(new ExactNode.Ref(identity(leaf))));
    ExactNode invalid =
        ExactNode.value(Map.of("type", new ExactNode.Ref(CORE.get("Integer")), "value", "wrong"));
    fork.retain(List.of(invalid));
    assertThrows(
        LanguageFailure.class,
        () -> fork.admitLocal(invalid, "value"),
        "Body presence is not semantic admission");
  }

  @Test
  void publisherCutsExpireWithoutCertifyingTheirAncestorsInAnySnapshot() {
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
    Language language = base.toBuilder().content(provider).content(timeline).build();
    TimelinePublisher publisher = TimelinePublisher.forTimeline(coordination, provider, timeline);
    String entry =
        """
        type: Coordination/Timeline Entry
        timeline: {blueId: '%s'}
        actor: {type: Coordination/Principal Actor, givenName: Alice}
        timestamp: %s
        message: {amount: 7}
        %s
        """;
    LanguageContent first =
        language.prepare(Language.Input.yaml(entry.formatted(timeline.blueId(), 1, "")));
    LanguageContent second =
        language.prepare(
            Language.Input.yaml(
                entry.formatted(
                    timeline.blueId(), 2, "prevEntry: {blueId: '" + first.blueId() + "'}")));
    TimelinePublisher.Prepared head =
        publisher.prepare(publisher.prepare(publisher.initial(), first), second);
    ContractLanguage source = new ContractLanguage(language);
    ExactNode root = ExactNode.value(Map.of("head", new ExactNode.Ref(second.blueId().value())));
    ContractLanguage fork;
    try (var scope = source.withPublisherHeads(head)) {
      source.retain(List.of(root));
      fork = source.fork();
      fork.retain(List.of(root));
      source.retain(List.of(root));
    }
    assertThrows(LanguageFailure.class, () -> source.retain(List.of(root)));
    assertThrows(LanguageFailure.class, () -> fork.retain(List.of(root)));
    assertThrows(LanguageFailure.class, () -> source.fork().retain(List.of(root)));
  }

  @Test
  void capturedViewsReleaseInvocationProvidersButKeepVerifiedContent() {
    Language language = Language.create();
    LanguageDocument first = language.readYaml("first: 1");
    LanguageDocument later = language.readYaml("later: 2");
    ContractLanguage source = new ContractLanguage(language);
    AtomicBoolean open = new AtomicBoolean(true);
    ContractLanguage.ProviderScope scope =
        source.withProvider(
            id -> {
              assertTrue(open.get(), "A closed provider must not be retained by a captured view");
              return Optional.of(
                  LanguageRecord.json(
                      id.equals(first.blueId()) ? first.canonicalJson() : later.canonicalJson()));
            });
    ContractLanguage view = source.readSnapshot();
    ExactNode.Ref known = new ExactNode.Ref(first.blueId().value());
    head(recipe(known), view);
    scope.close();
    scope.close();
    open.set(false);
    assertNotNull(head(recipe(known), view));
    assertThrows(
        LanguageFailure.class, () -> head(recipe(new ExactNode.Ref(later.blueId().value())), view));
    assertThrows(
        LanguageFailure.class,
        () -> head(recipe(new ExactNode.Ref(later.blueId().value())), source));
  }

  @Test
  void viewsShareOldHeadsButKeepLaterEvidenceAndMemoEntriesConfined() {
    ContractLanguage source = new ContractLanguage(Language.create());
    ExactNode original = source.prepare(Map.of("count", 1));
    source.admitLocal(original, "value");
    head(recipe(original), source);
    var known = head(recipe(original), source);
    ContractLanguage view = source.readSnapshot();
    assertSame(known, head(recipe(original), view));

    ExactNode later = source.prepare(Map.of("later", 2));
    source.admitLocal(later, "value");
    head(recipe(later), source);
    assertThrows(
        LanguageFailure.class, () -> head(recipe(new ExactNode.Ref(identity(later))), view));

    ExactNode privateValue = view.prepare(Map.of("private", 3));
    view.admitLocal(privateValue, "value");
    assertThrows(
        LanguageFailure.class,
        () -> head(recipe(new ExactNode.Ref(identity(privateValue))), source));
    assertSame(known, head(recipe(original), source));
    assertSame(known, head(recipe(original), view));
  }

  @Test
  void warmedViewsCannotApproveForgedNewEvidence() {
    ContractLanguage source = new ContractLanguage(Language.create());
    ExactNode original = source.prepare(Map.of("count", 1));
    source.admitLocal(original, "value");
    head(recipe(original), source);
    ContractLanguage view = source.readSnapshot();
    String before = identity(exact(recipe(original), view));
    assertThrows(
        LanguageFailure.class,
        () ->
            view.verifyEvidence(
                before, Map.of("mode", "exact", "text", "{\"count\":{\"value\":2}}")));
    assertEquals(before, identity(exact(recipe(original), view)));
    assertEquals(before, identity(exact(recipe(original), source)));
  }
}
