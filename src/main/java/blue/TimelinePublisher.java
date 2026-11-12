package blue;

import static blue.TimelineContent.require;

import blue.LanguageModel.Head;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Incremental schema validation for a timeline's publishing host. Prepared heads are tentative: the
 * host must commit the original occurrence, its source head and this checkpoint atomically. Neither
 * a prepared head nor its checkpoint is consumer history evidence, a read grant or a completeness
 * promise. Consumers continue to use {@link TimelineVerifier}.
 *
 * <p>A checkpoint may be restored only with its identity taken from the publisher's protected,
 * committed storage. That identity certifies that this publisher previously validated the stored
 * head. Accepting a digest supplied by a remote request would violate this trust boundary.
 */
public final class TimelinePublisher {
  private static final String FORMAT = "blue.timeline.publisher/1";
  private final TimelineVerifier verifier;
  private LanguageContext messages;

  /**
   * Validates a message as a Language definition and classifies its Operation Request type.
   * Completed immutable schema work may be reused within this publisher. This neither admits an
   * operation nor grants read access; the consumer still verifies its original occurrence. The
   * retained schema cache is bounded and never contains a provider callback.
   *
   * @param message exact message with its supporting content
   * @return whether its type derives from the published Contracts Operation Request
   */
  public synchronized boolean validateMessage(LanguageContent message) {
    Objects.requireNonNull(message);
    return Language.run(
        () -> {
          LanguageContext context =
              message.owner.context(
                  message, verifier.timeline, verifier.provider, verifier.publication.document());
          if (messages != null) {
            context.bodies.putAll(messages.bodies);
            context.proofs.putAll(messages.proofs);
            messages.snapshotHeadsTo(context);
          }
          context.reuseWithinHostBudget = true;
          context.facts = verifier.facts;
          context.establishedValues = verifier.targetFacts();
          LanguageOperations.admit(message.semantic, context, "definition", "exact");
          Head head = LanguageSemantics.head(LanguageSemantics.recipe(message.semantic), context);
          boolean operation =
              head.type != null
                  && LanguageSemantics.ancestry(head.type, context)
                      .contains(
                          ProtocolPublications.contracts().export("Operation Request").value());
          verifier.facts.remember(context);
          retainMessages(context);
          return operation;
        });
  }

  /**
   * Shares bounded, completed semantic checks and admitted-value headers with a consumer of this
   * exact source. Neither history, signatures, permissions, content nor native execution authority
   * is shared. The consumer still verifies its original signed occurrence and all contextual
   * refinements. Cache eviction or replacing this publisher can only cause additional verification
   * work.
   *
   * @param consumer verifier for the same Coordination edition, provider and timeline
   * @throws BlueException if the consumer selects a different source
   */
  public void shareValidationWith(TimelineVerifier consumer) {
    Objects.requireNonNull(consumer);
    require(
        verifier.publication.blueId().equals(consumer.publication.blueId())
            && verifier.provider.blueId().equals(consumer.provider.blueId())
            && verifier.timeline.blueId().equals(consumer.timeline.blueId()),
        "PUBLISHER_VALIDATION_SOURCE",
        "consumer");
    consumer.facts = verifier.facts;
    verifier.targetFacts().forEach(consumer::rememberTarget);
  }

  /**
   * Retains the semantic admission of an immutable value already issued by Language. Only a bounded
   * detached header is retained; this conveys no document membership, operation permission or
   * source history. New content and additional contextual constraints are checked normally.
   * Definitions cannot stand in for completed values.
   *
   * @param value a completed Language value, not prepared content or a definition
   * @return whether its header fits the bounded cache
   * @throws IllegalArgumentException if the document was admitted only as a definition
   */
  public boolean retainValue(LanguageDocument value) {
    Objects.requireNonNull(value);
    if (value.goal() != Language.Goal.VALUE)
      throw new IllegalArgumentException("Completed Language value required");
    // Expanded/source presentations may have a different semantic identity. Decline reuse
    // rather than attributing one admission to a different exact representation.
    if (!value.blueId().value().equals(LanguageCore.identity(value.semantic))) return false;
    return Language.run(
        () -> {
          LanguageContext context = value.owner.context(value);
          Head head = LanguageSemantics.head(LanguageSemantics.recipe(value.semantic), context);
          return verifier.rememberTarget(value.blueId().value(), head) != null;
        });
  }

  private void retainMessages(LanguageContext context) {
    final long maximum = 64L * 1024 * 1024;
    long weight =
        ContractCache.weight(
            List.of(
                context.bodies,
                context.proofs,
                context.validation,
                context.verifiedClosures,
                context.concreteFacts),
            maximum);
    for (Head head : context.heads.values()) {
      if (weight > maximum) break;
      long extra =
          ContractCache.weight(
              Arrays.asList(
                  head.fields,
                  head.items,
                  head.labels,
                  head.origin,
                  head.origins,
                  head.contracts,
                  head.constraints),
              maximum - weight);
      weight = extra == Long.MAX_VALUE ? Long.MAX_VALUE : weight + extra + 256;
    }
    if (weight > maximum) {
      messages = null;
      return;
    }
    // Retain only immutable content and completed semantic facts. In particular, no sender's
    // provider, native invocation scope, diagnostic root or credential-bearing closure survives.
    LanguageContext retained = new LanguageContext(Map.of(), false);
    retained.bodies.reset(context.bodies);
    retained.proofs.reset(context.proofs);
    context.snapshotHeadsTo(retained);
    messages = retained;
  }

  private TimelinePublisher(TimelineVerifier verifier) {
    this.verifier = verifier;
  }

  /**
   * Selects one publisher-owned source and the exact supported protocol.
   *
   * @param coordination supported Coordination package
   * @param provider publisher's pinned provider identity
   * @param timeline publisher's exact Signed Timeline
   * @return immutable source-specific validation configuration
   */
  public static TimelinePublisher forTimeline(
      BluePackage coordination, LanguageDocument provider, LanguageDocument timeline) {
    return new TimelinePublisher(TimelineVerifier.adopt(coordination, provider, timeline));
  }

  /**
   * Starts an empty tentative source; this makes no claim about durable history.
   *
   * @return initial head owned by this publisher configuration
   */
  public Prepared initial() {
    return new Prepared(null, null, null, -1);
  }

  /**
   * Validates a candidate against the selected previous validated head. It checks the exact
   * predecessor, timestamp, source, actor, operation/message identity and ordinary entry schema.
   * Previous schema validation is reused; no earlier body is revisited.
   *
   * @param previous this publisher's validated or protected-restored previous head
   * @param entry complete candidate and its new dependency evidence
   * @return tentative validated head; publication still requires the host's conditional commit
   */
  public Prepared prepare(Prepared previous, LanguageContent entry) {
    Objects.requireNonNull(previous);
    Objects.requireNonNull(entry);
    require(previous.owner() == this, "PUBLISHER_CHECKPOINT_OWNER", "previous");
    return Language.run(
        () -> {
          LanguageContext context =
              entry.owner.context(
                  entry, verifier.timeline, verifier.provider, verifier.publication.document());
          Map<String, Head> established = new HashMap<>(verifier.targetFacts());
          if (previous.id != null) established.put(previous.id.value(), previous.header);
          context.establishedValues = established;
          try {
            TimelineContent fields = new TimelineContent(context, verifier.publication);
            TimelineVerifier.Fact candidate = verifier.entry(fields, entry.semantic);
            require(
                Objects.equals(candidate.previous(), previous.id), "TIMELINE_CHAIN", "prevEntry");
            require(
                candidate.entry().timestamp() > previous.timestamp, "TIMELINE_TIME", "timestamp");
            return new Prepared(
                entry.toJson(),
                candidate.entry().blueId(),
                candidate.header(),
                candidate.entry().timestamp());
          } finally {
            established.clear();
            context.establishedValues = Map.of();
          }
        });
  }

  /**
   * Restores a previously validated local publisher checkpoint. This is protected-storage recovery,
   * not verification of an untrusted prefix. The expected commitment must have been committed by
   * the host together with the accepted source head, never obtained from the supplied checkpoint.
   * Every selected body/type identity still undergoes ordinary exact-content verification.
   *
   * @param checkpoint original checkpoint exported by a prepared head
   * @param expectedIdentity independently trusted committed checkpoint identity
   * @param retained scoped exact-content resolver for the selected head's type dependencies
   * @return source-bound validated head for further tentative publication
   */
  public Prepared restore(
      Map<String, Object> checkpoint, String expectedIdentity, LanguageProvider retained) {
    Objects.requireNonNull(checkpoint);
    Objects.requireNonNull(expectedIdentity);
    Objects.requireNonNull(retained);
    return Language.run(
        () -> {
          require(
              checkpoint
                  .keySet()
                  .equals(Set.of("format", "coordination", "provider", "timeline", "entry")),
              "PUBLISHER_CHECKPOINT_SHAPE",
              "checkpoint");
          require(
              ContractModel.digest(FORMAT, checkpoint).equals(expectedIdentity),
              "UNTRUSTED_PUBLISHER_CHECKPOINT",
              "identity");
          require(
              FORMAT.equals(checkpoint.get("format"))
                  && verifier.publication.blueId().value().equals(checkpoint.get("coordination"))
                  && verifier.provider.blueId().value().equals(checkpoint.get("provider"))
                  && verifier.timeline.blueId().value().equals(checkpoint.get("timeline")),
              "PUBLISHER_CHECKPOINT_SOURCE",
              "source");
          if (checkpoint.get("entry") == null) return initial();
          require(checkpoint.get("entry") instanceof String, "PUBLISHER_CHECKPOINT_SHAPE", "entry");
          Language language =
              Language.newBuilder()
                  .mount("Coordination", verifier.publication)
                  .content(verifier.provider)
                  .content(verifier.timeline)
                  .provider(retained)
                  .build();
          LanguageContent entry =
              language.prepare(Language.Input.exactJson((String) checkpoint.get("entry")));
          TimelineContent fields =
              new TimelineContent(language.context(entry), verifier.publication);
          fields.typed(entry.semantic, "Timeline Entry");
          verifier.sameTimeline(fields.required(entry.semantic, "Timeline Entry", "timeline"));
          long timestamp = fields.integer(entry.semantic, "Timeline Entry", "timestamp");
          require(
              timestamp >= 0 && timestamp < TimelineVerifier.FINAL_BOUND,
              "TIMELINE_TIME",
              "timestamp");
          // The protected commitment preserves the prior validation result. This header never
          // enters
          // ordinary Language admission or a consumer verifier except through a scoped producer
          // call.
          Head header =
              TimelineContent.detached(
                  entry.semantic, fields.typed(entry.semantic, "Timeline Entry"));
          return new Prepared(entry.toJson(), entry.blueId(), header, timestamp);
        });
  }

  /**
   * A bounded tentative publisher state. It is deliberately not a TimelineVerifier Prefix/Entry.
   */
  public final class Prepared {
    private final String entry;
    private final BlueId id;
    private final Head header;
    private final long timestamp;
    private final Map<String, Object> checkpoint;
    private final String identity;

    private Prepared(String entry, BlueId id, Head header, long timestamp) {
      this.entry = entry;
      this.id = id;
      this.header = header;
      this.timestamp = timestamp;
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("format", FORMAT);
      value.put("coordination", verifier.publication.blueId().value());
      value.put("provider", verifier.provider.blueId().value());
      value.put("timeline", verifier.timeline.blueId().value());
      value.put("entry", entry);
      checkpoint = Collections.unmodifiableMap(value);
      identity = ContractModel.digest(FORMAT, checkpoint);
    }

    /**
     * Returns the current original occurrence, absent for the initial state.
     *
     * @return selected entry identity, not evidence of acceptance
     */
    public Optional<BlueId> entryId() {
      return Optional.ofNullable(id);
    }

    /**
     * Exports bounded local recovery data; no earlier entry bodies are retained.
     *
     * @return immutable checkpoint to commit with its separately stored identity
     */
    public Map<String, Object> checkpoint() {
      return checkpoint;
    }

    /**
     * Commits this local validation state. This digest is not a BlueId or a consumer proof.
     *
     * @return local checkpoint commitment to store only after a successful atomic publication
     */
    public String identity() {
      return identity;
    }

    private TimelinePublisher owner() {
      return TimelinePublisher.this;
    }

    void checkSource(TimelineAccessDefinition definition) {
      require(
          verifier.publication.blueId().equals(definition.publication.blueId())
              && verifier.provider.blueId().equals(definition.provider.blueId())
              && verifier.timeline.blueId().equals(definition.control.blueId()),
          "ACCESS_CONTROL_TIMELINE",
          "publisher checkpoint");
    }

    Map<String, Head> headers() {
      return id == null ? Map.of() : Map.of(id.value(), header);
    }

    LanguageContent content() {
      return entry == null ? null : Language.create().prepare(Language.Input.exactJson(entry));
    }

    long timestamp() {
      return timestamp;
    }
  }
}
