package blue;

import static blue.LanguageCore.identity;
import static blue.LanguageSemantics.child;
import static blue.LanguageSemantics.exact;
import static blue.LanguageSemantics.head;
import static blue.LanguageSemantics.recipe;
import static blue.TimelineContent.require;

import blue.ExactNode.Ref;
import blue.LanguageModel.Head;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared published access-document shape and program checks; this class grants no authority. */
final class TimelineAccessDefinition {
  final BluePackage publication;
  final LanguageDocument provider, initial, control;
  final Language language;
  final String publicType, declaration;

  record Permissions(Set<BlueId> timelines, Set<String> keys) {}

  TimelineAccessDefinition(
      BluePackage publication,
      LanguageDocument provider,
      LanguageContent descriptor,
      LanguageDocument initial) {
    this.publication = publication;
    this.provider = provider;
    this.initial = initial;
    ProviderSignatures.adopt(publication, provider);
    language =
        Language.newBuilder()
            .mount("Coordination", publication)
            .content(provider)
            .content(descriptor)
            .content(initial)
            .build();
    TimelineContent fields =
        new TimelineContent(language.context(initial, descriptor), publication);
    ExactNode descriptorBody =
        fields.required(descriptor.semantic, "Provider Attestation", "object");
    ExactNode timeline = fields.required(descriptorBody, "Provider Descriptor", "controlTimeline");
    control =
        language.admit(
            new LanguageContent(
                language,
                timeline,
                new Language.Snapshot(fields.context.bodies, fields.context.proofs)),
            Language.Goal.VALUE);
    TimelineVerifier verifier = TimelineVerifier.adopt(publication, provider, control);
    TimelineReader reader = TimelineReader.adopt(verifier, descriptor);
    require(
        reader.controlTimelineId().equals(control.blueId()),
        "ACCESS_CONTROL_TIMELINE",
        "descriptor");
    publicType = fields.type("Public Timeline Channel");
    declaration = Language.run(() -> validateInitial(fields));
  }

  ContractProcessor processor(TimelineChannels.HistoryVerifier verifier) {
    ContractProcessor processor = new ContractProcessor(language, ContractBindings.dispatch());
    processor.installInterpretedType(
        publication.export("Update Timeline Access").value(),
        publication.protocol().orElseThrow().rules().value());
    TimelineChannels.verifiedBy(publication, verifier)
        .registrations()
        .forEach(channel -> channel.install(processor));
    return processor;
  }

  private String validateInitial(TimelineContent fields) {
    require(initial.goal() == Language.Goal.VALUE, "ACCESS_VALUE_REQUIRED", "initial");
    Head access = fields.typed(initial.semantic, "Timeline Access");
    require(
        fields.supplied(initial.semantic, "Timeline Access", "provider") == null,
        "ACCESS_REPEATED_PROVIDER",
        "provider belongs in the channel");
    require(access.contracts != null, "ACCESS_CONTRACTS", "contracts");
    Head contracts = head(access.contracts, fields.context);
    require(
        contracts.fields.keySet().equals(Set.of("provider", "update")),
        "ACCESS_CONTRACTS",
        "only the published provider/update declarations");
    ExactNode channel = exact(child(contracts, "provider"), fields.context);
    require(
        publicType.equals(head(recipe(channel), fields.context).type),
        "ACCESS_CHANNEL",
        "exact public channel");
    require(
        identity(fields.required(channel, "Public Timeline Channel", "provider"))
                .equals(provider.blueId().value())
            && fields
                .text(channel, "Public Timeline Channel", "url")
                .equals(control.at("/url").text()),
        "ACCESS_CONTROL_TIMELINE",
        "provider channel");
    ExactNode update = exact(child(contracts, "update"), fields.context);
    require(
        identity(update)
            .equals(
                identity(
                    ExactNode.value(
                        Map.of("type", new Ref(fields.type("Update Timeline Access")))))),
        "ACCESS_CHANGED_PROGRAM",
        "update");
    permissions(fields, initial.semantic);
    return identity(channel);
  }

  Permissions permissions(TimelineContent fields, ExactNode document) {
    Set<BlueId> timelines = new LinkedHashSet<>();
    Set<String> keys = new LinkedHashSet<>();
    for (ExactNode value : list(fields, document, "allowedTimelines")) {
      fields.typed(value, "Signed Timeline");
      require(
          identity(fields.required(value, "Timeline", "provider"))
              .equals(provider.blueId().value()),
          "ACCESS_FOREIGN_TIMELINE",
          "allowedTimelines");
      TimelineVerifier.canonicalUrl(fields.text(value, "Timeline", "url"));
      require(timelines.add(TimelineVerifier.id(value)), "ACCESS_DUPLICATE_VALUE", "timeline");
    }
    for (ExactNode value : list(fields, document, "allowedKeys")) {
      Object key = head(recipe(value), fields.context).value;
      require(
          key instanceof String text && text.matches("[0-9a-f]{64}"),
          "ACCESS_READER_KEY",
          "allowedKeys");
      require(keys.add((String) key), "ACCESS_DUPLICATE_VALUE", "key");
    }
    return new Permissions(timelines, keys);
  }

  private List<ExactNode> list(TimelineContent fields, ExactNode document, String field) {
    Head list = head(child(fields.typed(document, "Timeline Access"), field), fields.context);
    require(list.items != null, "ACCESS_LIST_REQUIRED", field);
    return list.items.stream().map(item -> exact(item, fields.context)).toList();
  }
}
