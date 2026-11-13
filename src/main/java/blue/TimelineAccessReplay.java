package blue;

import static blue.LanguageCore.identity;
import static blue.TimelineContent.require;

import blue.ExactNode.Ref;
import blue.TimelineAccessDefinition.Permissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Executable access history. Only a verified source suffix may enter the scoped native gate. */
final class TimelineAccessReplay {
  private final TimelineAccessDefinition definition;
  private final BluePackage publication;
  private final LanguageDocument provider, initial, control;
  private final TimelineVerifier verifier;
  private final ContractProcessor processor;
  private final String publicType, declaration;
  private TimelineVerifier.Prefix prefix;
  private ContractProcessor.Document state;
  private long changedAt;
  private Selection selected;
  TimelineAccess.View view;

  private record Selection(String snapshot, BlueId event, long timestamp) {}

  TimelineAccessReplay(
      BluePackage publication,
      LanguageDocument provider,
      LanguageContent descriptor,
      LanguageDocument initial) {
    definition = new TimelineAccessDefinition(publication, provider, descriptor, initial);
    this.publication = publication;
    this.provider = provider;
    this.initial = initial;
    control = definition.control;
    publicType = definition.publicType;
    declaration = definition.declaration;
    verifier = TimelineVerifier.adopt(publication, provider, control);
    prefix = verifier.initial();
    processor = definition.processor(this::verifyAccess);
  }

  TimelineAccess.Update advance(LanguageContent evidence) {
    TimelineVerifier.Result verified = verifier.advance(prefix, evidence);
    if (verified.entries().isEmpty()) {
      // Verification already established the new bound. No contract input means no new
      // execution context, retained content or permission state is needed for this checkpoint.
      prefix = verified.prefix();
      if (view != null) {
        view =
            new TimelineAccess.View(
                view.document(),
                changedAt,
                prefix.completeBefore(),
                view.allowedTimelines(),
                view.allowedKeys());
      }
      return new TimelineAccess.Update(
          prefix.completeBefore(), Optional.ofNullable(view), List.of());
    }
    return Language.run(
        () ->
            Contracts.boundary(
                () -> {
                  TimelineContent fields =
                      new TimelineContent(
                          evidence.owner.context(
                              evidence, initial, control, provider, publication.document()),
                          publication);
                  var scope =
                      processor.blue.context.withProvider(
                          id ->
                              Optional.ofNullable(fields.context.lookup.apply(id.value()))
                                  .map(raw -> new LanguageRecord(LanguageCore.map(raw))));
                  try {
                    return replay(evidence, verified, fields);
                  } finally {
                    scope.close();
                  }
                }));
  }

  @SuppressWarnings("try")
  private TimelineAccess.Update replay(
      LanguageContent evidence, TimelineVerifier.Result verified, TimelineContent fields) {
    processor.blue.context.install(evidence);
    ContractProcessor.Document next = state;
    LanguageDocument document = view == null ? null : view.document();
    Permissions permissions =
        view == null ? null : new Permissions(view.allowedTimelines(), view.allowedKeys());
    long nextChanged = changedAt;
    List<TimelineAccess.Disposition> dispositions = new ArrayList<>();
    for (TimelineVerifier.Entry occurrence : verified.entries()) {
      // The range verifier already admitted this exact original occurrence. Reuse its opaque
      // header for this invocation, just as Contracts.Session.process does; native authority and
      // contextual constraints are still checked. The scope cannot survive the invocation.
      try (var ignored = processor.blue.context.withTimelineEntry(occurrence)) {
        ExactNode entry = new Ref(occurrence.blueId().value());
        // Retain each verified suffix once, including inapplicable entries. Otherwise the first
        // applicable operation's receipt would rediscover the entire imported predecessor chain.
        // This is content retention, not an invocation or a permission decision.
        processor.blue.context.retain(List.of(entry));
        ExactNode message = fields.required(entry, "Timeline Entry", "message");
        TimelineAccess.Kind kind = TimelineAccess.Kind.INAPPLICABLE;
        ContractProcessor.Result outcome = null;
        if (initial.blueId().value().equals(identity(message))) {
          require(next == null, "ACCESS_DUPLICATE_PUBLICATION", "initial");
          agent(fields, entry);
          outcome = processor.initialize(initial.semantic);
          kind = TimelineAccess.Kind.PUBLISHED;
        } else {
          var request = ContractChannels.operationRequest(processor.registry, message);
          if (request != null
              && "provider".equals(request.channel())
              && "update".equals(request.operation())) {
            require(request.document() != null, "ACCESS_UPDATE_TARGET_REQUIRED", "document");
            String target = request.document();
            if (initial.blueId().value().equals(target)) {
              require(next != null, "ACCESS_UPDATE_BEFORE_PUBLICATION", "initial");
              agent(fields, entry);
              selected =
                  new Selection(next.identity(), occurrence.blueId(), occurrence.timestamp());
              try {
                outcome = processor.process(next, entry, occurrence.timestamp(), Map.of());
              } finally {
                selected = null;
              }
              kind = TimelineAccess.Kind.UPDATED;
            }
          }
        }
        Contracts.Transition transition =
            outcome == null ? null : new Contracts.Transition(outcome, processor);
        if (outcome != null) {
          require(
              outcome.commits(),
              "ACCESS_HISTORY_CONTRADICTION",
              "accepted control entry "
                  + occurrence.blueId()
                  + " failed: "
                  + transition.observations().get("diagnostic"));
          next = outcome.document();
          nextChanged = occurrence.timestamp();
          document =
              processor.blue.context.document(next.state.documents.get(next.state.root).body);
          // Historical states are authoritative too. A later replacement cannot conceal an
          // invalid intermediate state merely because the final lists happen to be valid.
          permissions =
              definition.permissions(
                  new TimelineContent(document.owner.context(document), publication),
                  document.semantic);
        }
        dispositions.add(
            new TimelineAccess.Disposition(
                occurrence.blueId(),
                occurrence.timestamp(),
                kind,
                Optional.ofNullable(transition),
                outcome == null ? Optional.empty() : Optional.of(document)));
      }
    }
    TimelineAccess.View nextView = null;
    if (next != null) {
      nextView =
          new TimelineAccess.View(
              document,
              nextChanged,
              verified.prefix().completeBefore(),
              permissions.timelines(),
              permissions.keys());
    }
    // The immutable Contracts operands above are tentative until every accepted source entry
    // agrees.
    state = next;
    changedAt = nextChanged;
    prefix = verified.prefix();
    view = nextView;
    return new TimelineAccess.Update(
        prefix.completeBefore(), Optional.ofNullable(view), dispositions);
  }

  private static void agent(TimelineContent fields, ExactNode entry) {
    fields.typed(fields.required(entry, "Timeline Entry", "actor"), "Agent Actor");
  }

  private List<TimelineChannels.Assessment> verifyAccess(TimelineChannels.Request request) {
    ContractModel.require(
        selected != null && request.channelTypeId().value().equals(publicType),
        "UNBOUND_ACCESS_HISTORY");
    ContractModel.require(
        request.snapshotIdentity().equals(selected.snapshot)
            && request.inputId().equals(selected.event)
            && request.entryId().equals(selected.event)
            && request.timestamp() == selected.timestamp,
        "ACCESS_ADMISSION_CONTEXT");
    List<TimelineChannels.Assessment> assessments = new ArrayList<>();
    for (NativeAdmission.Receiver receiver : request.receivers()) {
      ContractModel.require(
          receiver.declarationId().value().equals(declaration)
              && receiver.channel().equals("provider"),
          "ACCESS_ADMISSION_CHANNEL");
      assessments.add(
          new TimelineChannels.Assessment(
              receiver,
              TimelineChannels.Disposition.ELIGIBLE,
              true,
              "Authenticated direct source participation"));
    }
    return List.copyOf(assessments);
  }
}
