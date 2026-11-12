package blue;

import static blue.LanguageCore.identity;
import static blue.LanguageSemantics.ancestry;
import static blue.LanguageSemantics.head;
import static blue.LanguageSemantics.recipe;
import static blue.TimelineContent.require;

import blue.LanguageModel.Head;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Verifies contiguous history for one explicitly adopted signed timeline. Both published range and
 * successor evidence advance the same exclusive prefix. Verification reads new entries and their
 * required content, without reopening an established predecessor chain.
 *
 * <p>This is a history verifier, not a reader authorization service, provider, feeder or native
 * runtime. A complete prefix alone does not authorize document execution. Instances share bounded
 * schema facts safely; the host must serialize publication of successive prefixes and retain
 * conflicts and source data.
 */
public final class TimelineVerifier {
  private static final String CHECKPOINT = "blue.timeline.consumer/1";
  static final long FINAL_BOUND = 9_007_199_254_740_991L;
  final BluePackage publication;
  final LanguageDocument timeline;
  final LanguageDocument provider;
  final ProviderSignatures signatures;
  volatile LanguageFacts facts = new LanguageFacts();
  // Schema facts for exact operation targets already validated by this verifier. No permission,
  // source-order or signature result is cached; only detached headers, never document bodies.
  private final Map<String, Head> targets = new LinkedHashMap<>(16, .75f, true);
  private int targetFields;
  // One completed, single-page read can immediately become the same ordinary range evidence.
  // This retains only bounded verified headers, never a reader permission or source body.
  private CompletedRead completedRead;

  private record CompletedRead(Prefix start, String evidence, Result result) {}

  synchronized void rememberRead(Prefix start, Prefix end, BlueId checkpoint, List<Entry> entries) {
    if (entries.size() > 64) return;
    long fields = 0;
    for (Entry entry : entries) {
      fields += targetWeight(entry.header);
      for (Head value : entry.values.values()) fields += targetWeight(value);
      if (fields > 16384) return;
    }
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("type", new ExactNode.Ref(publication.export("Timeline Range Evidence").value()));
    envelope.put("checkpoint", new ExactNode.Ref(checkpoint.value()));
    envelope.put(
        "entries",
        ExactNode.list(
            entries.stream()
                .map(entry -> (ExactNode) new ExactNode.Ref(entry.id.value()))
                .toList()));
    if (start.head != null) envelope.put("after", new ExactNode.Ref(start.head.entry.id.value()));
    completedRead =
        new CompletedRead(start, identity(new ExactNode.Value(envelope)), new Result(end, entries));
  }

  private synchronized Result completedRead(Prefix previous, LanguageContent evidence) {
    CompletedRead known = completedRead;
    return known != null
            && known.start == previous
            && known.evidence.equals(evidence.blueId().value())
        ? known.result
        : null;
  }

  synchronized Map<String, Head> targetFacts() {
    return new HashMap<>(targets);
  }

  synchronized Head rememberTarget(String id, Head head) {
    int weight = targetWeight(head);
    if (weight > 8192) return null;
    Head previous = targets.remove(id);
    if (previous != null) targetFields -= targetWeight(previous);
    while (!targets.isEmpty() && (targets.size() >= 256 || targetFields + weight > 8192)) {
      String oldest = targets.keySet().iterator().next();
      targetFields -= targetWeight(targets.remove(oldest));
    }
    Head detached = TimelineContent.detached(new ExactNode.Ref(id), head);
    targets.put(id, detached);
    targetFields += weight;
    return detached;
  }

  private static int targetWeight(Head head) {
    // Avoid retaining arbitrary literal payloads or large schema/label data in a header cache.
    if (head.value != LanguageCore.ABSENT
        || head.items != null
        || head.constraints != LanguageSchema.EMPTY) return 8193;
    long weight = 1;
    for (Object label : head.labels.values()) weight += 1L + label.toString().length();
    for (var field : head.fields.entrySet())
      weight += 1L + field.getKey().length() + field.getValue().parts.size() * 64L;
    return (int) Math.min(8193, weight);
  }

  private TimelineVerifier(
      BluePackage publication, LanguageDocument provider, LanguageDocument timeline) {
    this.publication = publication;
    this.provider = provider;
    this.timeline = timeline;
    signatures = ProviderSignatures.adopt(publication, provider);
    require(timeline.goal() == Language.Goal.VALUE, "TIMELINE_VALUE_REQUIRED", "timeline");
    Language.run(
        () -> {
          var fields = new TimelineContent(timeline.owner.context(timeline, provider), publication);
          fields.typed(timeline.semantic, "Signed Timeline");
          require(
              identity(fields.required(timeline.semantic, "Timeline", "provider"))
                  .equals(signatures.providerId().value()),
              "TIMELINE_PROVIDER_MISMATCH",
              "provider");
          canonicalUrl(fields.text(timeline.semantic, "Timeline", "url"));
          return null;
        });
  }

  /**
   * Selects a timeline and its provider through the caller's trust policy. No network I/O occurs.
   *
   * @param coordination exact supported Coordination publication
   * @param provider admitted provider whose key the caller trusts
   * @param timeline admitted Signed Timeline, including inherited provider and URL fields
   * @return verifier bound to these exact identities
   */
  public static TimelineVerifier adopt(
      BluePackage coordination, LanguageDocument provider, LanguageDocument timeline) {
    return new TimelineVerifier(
        Objects.requireNonNull(coordination),
        Objects.requireNonNull(provider),
        Objects.requireNonNull(timeline));
  }

  /**
   * Starts at the necessarily empty prefix before timestamp zero. This asserts no later absence.
   *
   * @return an opaque initial prefix owned by this verifier
   */
  public Prefix initial() {
    return new Prefix(this, 0, null, null);
  }

  /**
   * Verifies exact Timeline Range Evidence or Timeline Successor Evidence against an issued prefix.
   * The evidence's after anchor must exactly match that prefix. Previously known entries need not
   * be resupplied. Missing required new content throws; it never means an empty timeline.
   *
   * <p>Prepared content is accepted deliberately: fully admitting a transport envelope beforehand
   * can reopen its entire predecessor history. This method performs scoped strict Language
   * validation using only privately issued facts for the established predecessor. Arbitrary wire
   * hashes cannot seed those facts. Returned prefixes retain headers, not source history.
   *
   * @param previous prefix issued by this verifier
   * @param evidence exact prepared evidence with its available supporting content
   * @return new prefix and the entries newly inside it, in timeline order
   * @throws BlueException if content, signatures, source, chain, bounds or retained history
   *     conflict
   * @throws LanguageException if content is invalid or required evidence or resources are
   *     unavailable
   */
  public Result advance(Prefix previous, LanguageContent evidence) {
    Objects.requireNonNull(previous);
    Objects.requireNonNull(evidence);
    require(previous.owner == this, "TIMELINE_PREFIX_OWNER", "prefix");
    Result completed = completedRead(previous, evidence);
    return completed != null ? completed : Language.run(() -> advanceVerified(previous, evidence));
  }

  /**
   * Inspects the operation marker of an original already verified and retained by the host. Target
   * bodies and request payloads are not reopened. This grants no source membership, permission or
   * validation evidence. The caller must establish the occurrence independently before using a
   * missing marker to skip operation-only receivers. Raw observers still receive it.
   *
   * @param original protected original from an authenticated archive
   * @return whether this occurrence supplies the exact message as its operation marker
   */
  public boolean hasOperationMarker(LanguageContent original) {
    Objects.requireNonNull(original);
    return Language.run(
        () -> {
          LanguageContext context =
              original.owner.context(original, timeline, provider, publication.document());
          TimelineContent fields = new TimelineContent(context, publication);
          ExactNode node = original.semantic;
          sameTimeline(fields.required(node, "Timeline Entry", "timeline"));
          ExactNode marker = fields.supplied(node, "Timeline Entry", "operation");
          if (marker == null) return false;
          ExactNode message = fields.required(node, "Timeline Entry", "message");
          require(
              identity(marker).equals(identity(message)), "TIMELINE_OPERATION", "operation marker");
          return true;
        });
  }

  /**
   * Reads the exact target selector of a protected original without admitting the target body. The
   * caller must already have authenticated and validated this occurrence. This grants no execution,
   * membership or validation authority and leaves targetless requests unclassified.
   *
   * @param original protected original from an authenticated archive
   * @return its explicit target identity, when an operation marker and selector are present
   */
  public Optional<BlueId> protectedOperationTarget(LanguageContent original) {
    if (!hasOperationMarker(original)) return Optional.empty();
    var target = original.at("/operation/document").observation("exactId");
    return target.isMissing() ? Optional.empty() : Optional.of(new BlueId(target.text()));
  }

  /**
   * Recovers this consumer's previously verified prefix from protected host storage. The expected
   * identity must come from the committed host record, never from a request or remote provider.
   * This preserves prior verification; it does not prove history to another consumer or grant
   * access. Ordinary Language admission receives none of the recovered validation facts.
   *
   * @param checkpoint checkpoint previously exported by a verified prefix
   * @param expectedIdentity independently trusted commitment stored with the source head
   * @param retained exact content for the selected head, successor and their type dependencies
   * @return a source-bound prefix with the same established bound and anchors
   */
  public Prefix restore(
      Map<String, Object> checkpoint, String expectedIdentity, LanguageProvider retained) {
    Objects.requireNonNull(checkpoint);
    Objects.requireNonNull(expectedIdentity);
    Objects.requireNonNull(retained);
    return Language.run(
        () -> {
          require(
              checkpoint
                  .keySet()
                  .equals(
                      Set.of(
                          "format",
                          "coordination",
                          "provider",
                          "timeline",
                          "before",
                          "head",
                          "successor")),
              "TIMELINE_CHECKPOINT_SHAPE",
              "checkpoint");
          require(
              ContractModel.digest(CHECKPOINT, checkpoint).equals(expectedIdentity),
              "UNTRUSTED_TIMELINE_CHECKPOINT",
              "identity");
          require(
              CHECKPOINT.equals(checkpoint.get("format"))
                  && publication.blueId().value().equals(checkpoint.get("coordination"))
                  && provider.blueId().value().equals(checkpoint.get("provider"))
                  && timeline.blueId().value().equals(checkpoint.get("timeline")),
              "TIMELINE_CHECKPOINT_SOURCE",
              "source");
          long before = ContractModel.integer(checkpoint.get("before"), 0, FINAL_BOUND);
          require(
              checkpoint.get("head") instanceof String
                  && checkpoint.get("successor") instanceof String,
              "TIMELINE_CHECKPOINT_SHAPE",
              "anchors");
          return restoreTrusted(
              before,
              optionalId((String) checkpoint.get("head")),
              optionalId((String) checkpoint.get("successor")),
              retained);
        });
  }

  /**
   * Adapts an existing protected host record of this consumer's successful verification. The host
   * must establish the record's integrity and exact source binding before calling. This is useful
   * when migrating an older committed storage format; these arguments must never come from an
   * untrusted request. No signatures or earlier history are reverified by this recovery operation.
   *
   * @param before previously verified exclusive completeness bound
   * @param head final closed occurrence, or null for an empty prefix
   * @param successor authenticated unclosed successor, or null when none is known
   * @param retained exact content for the selected anchors and their type dependencies
   * @return recovered local verification state, not portable evidence for another consumer
   */
  public Prefix restoreTrusted(
      long before, BlueId head, BlueId successor, LanguageProvider retained) {
    Objects.requireNonNull(retained);
    require(before >= 0 && before <= FINAL_BOUND, "TIMELINE_CHECKPOINT_BOUND", "before");
    return Language.run(
        () -> {
          Language language =
              Language.newBuilder()
                  .mount("Coordination", publication)
                  .content(provider)
                  .content(timeline)
                  .provider(retained)
                  .build();
          TimelineContent fields = new TimelineContent(language.context(), publication);
          Fact closed = recover(fields, head == null ? "" : head.value());
          Fact pending = recover(fields, successor == null ? "" : successor.value());
          require(
              closed == null || closed.entry.timestamp < before,
              "TIMELINE_CHECKPOINT_BOUND",
              "head");
          require(
              pending == null
                  || pending.entry.timestamp >= before && Objects.equals(pending.previous, head),
              "TIMELINE_CHECKPOINT_BOUND",
              "successor");
          return new Prefix(this, before, closed, pending);
        });
  }

  private static BlueId optionalId(String value) {
    return value.isEmpty() ? null : new BlueId(value);
  }

  private Fact recover(TimelineContent fields, Object identity) {
    require(identity instanceof String, "TIMELINE_CHECKPOINT_SHAPE", "entry");
    if (((String) identity).isEmpty()) return null;
    ExactNode node = new ExactNode.Ref(new BlueId((String) identity).value());
    Head header = fields.typed(node, "Timeline Entry");
    sameTimeline(fields.required(node, "Timeline Entry", "timeline"));
    long timestamp = fields.integer(node, "Timeline Entry", "timestamp");
    require(timestamp >= 0 && timestamp < FINAL_BOUND, "TIMELINE_TIME", "timestamp");
    BlueId actor = id(fields.required(node, "Timeline Entry", "actor"));
    BlueId message = id(fields.required(node, "Timeline Entry", "message"));
    Head detached = TimelineContent.detached(node, header);
    // The protected commitment preserves full prior validation. Reopening the predecessor or
    // recursively validating the message here would replay the history we are recovering.
    return new Fact(
        new Entry(id(node), timestamp, actor, message, detached, Map.of(), true),
        id(fields.supplied(node, "Timeline Entry", "prevEntry")),
        detached);
  }

  private Result advanceVerified(Prefix previous, LanguageContent evidence) {
    LanguageContext context =
        evidence.owner.context(evidence, timeline, provider, publication.document());
    context.establishedValues = targetFacts();
    if (previous.head != null)
      context.establishedValues.put(previous.head.entry.id.value(), previous.head.header);
    TimelineContent fields = new TimelineContent(context, publication);
    ExactNode source = evidence.semantic;
    Head root = head(recipe(source), context);
    boolean successor =
        root.type != null
            && ancestry(root.type, context).contains(fields.type("Timeline Successor Evidence"));
    String kind = successor ? "Timeline Successor Evidence" : "Timeline Range Evidence";
    fields.typed(source, kind);
    ExactNode after = fields.supplied(source, kind, "after");
    require(Objects.equals(id(after), previous.headId().orElse(null)), "TIMELINE_ANCHOR", "after");
    List<ExactNode> nodes = fields.entries(source, kind);

    ExactNode envelope = fields.required(source, kind, successor ? "successor" : "checkpoint");
    ExactNode claim;
    long bound;
    BlueId promisedHead;
    if (successor) {
      fields.typed(envelope, "Provider Attestation");
      require(
          identity(fields.required(envelope, "Provider Attestation", "provider"))
              .equals(provider.blueId().value()),
          "TIMELINE_PROVIDER_MISMATCH",
          "attestation.provider");
      claim = fields.required(envelope, "Provider Attestation", "object");
      fields.typed(claim, "Timeline Entry");
      signatures.verifyObject(
          id(claim), fields.text(envelope, "Provider Attestation", "signature"));
      bound = fields.integer(claim, "Timeline Entry", "timestamp");
      promisedHead = id(fields.supplied(claim, "Timeline Entry", "prevEntry"));
      require(
          !nodes.isEmpty() && identity(nodes.getLast()).equals(identity(claim)),
          "TIMELINE_FINAL_HEAD",
          "successor");
    } else {
      fields.typed(envelope, "Timeline Attestation");
      sameTimeline(fields.required(envelope, "Timeline Attestation", "timeline"));
      claim = fields.required(envelope, "Timeline Attestation", "guarantee");
      fields.typed(claim, "Timeline Completeness Guarantee");
      sameTimeline(fields.required(claim, "Timeline Completeness Guarantee", "timeline"));
      signatures.verifyGuarantee(
          id(claim), fields.text(envelope, "Timeline Attestation", "signature"));
      bound = fields.integer(claim, "Timeline Completeness Guarantee", "completeBefore");
      promisedHead = id(fields.supplied(claim, "Timeline Completeness Guarantee", "lastEntry"));
    }
    require(bound >= previous.before && bound <= FINAL_BOUND, "TIMELINE_BOUND", "completeBefore");
    var closed = new ArrayList<Entry>();
    Fact head = previous.head;
    Fact pending = previous.successor;
    for (int i = 0; i < nodes.size(); i++) {
      ExactNode node = nodes.get(i);
      Fact entry = entry(fields, node);
      require(
          Objects.equals(entry.previous, head == null ? null : head.entry.id),
          "TIMELINE_CHAIN",
          "prevEntry");
      require(
          entry.entry.timestamp >= previous.before
              && (head == null || entry.entry.timestamp > head.entry.timestamp),
          "TIMELINE_TIME",
          "timestamp");
      if (i == 0 && pending != null)
        require(
            entry.entry.id.equals(pending.entry.id), "TIMELINE_EQUIVOCATION", "known successor");
      boolean lastSuccessor = successor && i == nodes.size() - 1;
      require(
          lastSuccessor ? entry.entry.timestamp == bound : entry.entry.timestamp < bound,
          "TIMELINE_BOUND",
          "entry.timestamp");
      context.establishedValues.put(entry.entry.id.value(), entry.header);
      if (lastSuccessor) pending = entry;
      else {
        head = entry;
        pending = null;
        closed.add(entry.entry);
      }
    }
    require(
        Objects.equals(promisedHead, head == null ? null : head.entry.id),
        "TIMELINE_FINAL_HEAD",
        "lastEntry");
    require(
        pending == null || bound <= pending.entry.timestamp,
        "TIMELINE_EQUIVOCATION",
        "bound passes known successor");
    fields.validate(source);
    return new Result(new Prefix(this, bound, head, pending), closed);
  }

  Fact entry(TimelineContent fields, ExactNode node) {
    fields.typed(node, "Timeline Entry");
    sameTimeline(fields.required(node, "Timeline Entry", "timeline"));
    long timestamp = fields.integer(node, "Timeline Entry", "timestamp");
    require(timestamp >= 0 && timestamp < FINAL_BOUND, "TIMELINE_TIME", "timestamp");
    ExactNode actor = fields.required(node, "Timeline Entry", "actor");
    Head actorHead = fields.typed(actor, "Actor");
    List<String> categories = ancestry(actorHead.type, fields.context);
    require(
        categories.contains(fields.type("Principal Actor"))
            || categories.contains(fields.type("Agent Actor")),
        "TIMELINE_ACTOR",
        "actor category");
    ExactNode message = fields.required(node, "Timeline Entry", "message");
    ExactNode operation = fields.supplied(node, "Timeline Entry", "operation");
    ExactNode provenance = fields.supplied(node, "Timeline Entry", "source");
    if (provenance != null) fields.typed(provenance, "Source");
    fields.validate(node);
    Map<String, Head> values = validateOperation(fields, message, operation);
    Head header = TimelineContent.detached(node, fields.typed(node, "Timeline Entry"));
    var entry =
        new Entry(id(node), timestamp, id(actor), id(message), header, values, operation != null);
    return new Fact(entry, id(fields.supplied(node, "Timeline Entry", "prevEntry")), header);
  }

  private Map<String, Head> validateOperation(
      TimelineContent fields, ExactNode message, ExactNode operation) {
    Head head = head(recipe(message), fields.context);
    String requestType =
        ProtocolPublications.contracts().exports().get("Operation Request").value();
    boolean recognized =
        head.type != null && ancestry(head.type, fields.context).contains(requestType);
    if (!recognized) {
      require(operation == null, "TIMELINE_OPERATION", "unexpected operation");
      return Map.of();
    }
    require(
        operation != null && identity(operation).equals(identity(message)),
        "TIMELINE_OPERATION",
        "operation must identify the exact message");
    Map<String, Head> previous = fields.operationRequests.get(identity(message));
    if (previous != null) return previous;
    var content =
        new LanguageContent(
            timeline.owner,
            message,
            new Language.Snapshot(fields.context.bodies, fields.context.proofs));
    ContractRegistry registry = fields.operations(content);
    try {
      Map<String, Object> request = ContractChannels.operationRequest(registry, message);
      require(request != null, "TIMELINE_OPERATION", "unrecognized request");
      Map<String, Head> values = Map.of();
      if (request.get("document") instanceof Map<?, ?> document) {
        String target = (String) document.get("blueId");
        Head verified = head(recipe(new ExactNode.Ref(target)), fields.context);
        Head detached = rememberTarget(target, verified);
        if (detached != null) {
          values = Map.of(target, detached);
          fields.context.establishedValues.put(target, detached);
        }
      }
      fields.operationRequests.put(identity(message), values);
      return values;
    } catch (ContractFault invalid) {
      throw new BlueException(invalid.code(), invalid.getMessage());
    }
  }

  void sameTimeline(ExactNode value) {
    require(
        identity(value).equals(timeline.blueId().value()), "TIMELINE_SOURCE_MISMATCH", "timeline");
  }

  static BlueId id(ExactNode value) {
    return value == null ? null : new BlueId(identity(value));
  }

  static void canonicalUrl(String value) {
    try {
      URI uri = URI.create(value);
      require(
          "https".equals(uri.getScheme())
              && uri.getHost() != null
              && uri.getUserInfo() == null
              && uri.getQuery() == null
              && uri.getFragment() == null
              && uri.normalize().equals(uri)
              && !value.endsWith("/"),
          "TIMELINE_URL",
          "url");
      for (String segment : uri.getPath().split("/", -1))
        require(!segment.equals(".") && !segment.equals(".."), "TIMELINE_URL", "url");
    } catch (IllegalArgumentException invalid) {
      throw new BlueException("TIMELINE_URL", "url");
    }
  }

  record Fact(Entry entry, BlueId previous, Head header) {}

  Prefix completed(Prefix previous, Fact head, Fact successor, long before, BlueId promisedHead) {
    require(previous.owner == this, "TIMELINE_PREFIX_OWNER", "prefix");
    require(before >= previous.before && before <= FINAL_BOUND, "TIMELINE_BOUND", "completeBefore");
    require(
        Objects.equals(promisedHead, head == null ? null : head.entry.id),
        "TIMELINE_FINAL_HEAD",
        "lastEntry");
    require(
        successor == null || before <= successor.entry.timestamp,
        "TIMELINE_EQUIVOCATION",
        "bound passes known successor");
    return new Prefix(this, before, head, successor);
  }

  /** Constant-history-size progress, issued only after verification by its adopting verifier. */
  public static final class Prefix {
    final TimelineVerifier owner;
    final long before;
    final Fact head;
    final Fact successor;

    private Prefix(TimelineVerifier owner, long before, Fact head, Fact successor) {
      this.owner = owner;
      this.before = before;
      this.head = head;
      this.successor = successor;
    }

    /**
     * Exports bounded recovery metadata. Commit its identity and the corresponding source head
     * atomically in protected storage; this object alone is not portable history evidence.
     *
     * @return immutable source-bound checkpoint, independent of history length
     */
    public Map<String, Object> checkpoint() {
      return Map.of(
          "format",
          CHECKPOINT,
          "coordination",
          owner.publication.blueId().value(),
          "provider",
          owner.provider.blueId().value(),
          "timeline",
          owner.timeline.blueId().value(),
          "before",
          before,
          "head",
          head == null ? "" : head.entry.id.value(),
          "successor",
          successor == null ? "" : successor.entry.id.value());
    }

    /**
     * Commits to the complete local recovery metadata; it is not a provider attestation.
     *
     * @return commitment to store independently with the accepted source head
     */
    public String checkpointIdentity() {
      return ContractModel.digest(CHECKPOINT, checkpoint());
    }

    /**
     * Returns the last fully verified occurrence, including after protected recovery.
     *
     * @return the final closed occurrence, absent for an empty prefix
     */
    public Optional<Entry> headEntry() {
      return Optional.ofNullable(head == null ? null : head.entry);
    }

    /**
     * Identifies the adopted source.
     *
     * @return exact adopted timeline identity
     */
    public BlueId timelineId() {
      return owner.timeline.blueId();
    }

    /**
     * Reports how far history is closed.
     *
     * @return exclusive authenticated bound; it is not a wall-clock freshness assertion
     */
    public long completeBefore() {
      return before;
    }

    /**
     * Identifies the final closed occurrence.
     *
     * @return last entry strictly inside the prefix, absent when the prefix is empty
     */
    public Optional<BlueId> headId() {
      return Optional.ofNullable(head == null ? null : head.entry.id);
    }

    /**
     * Identifies the authenticated successor without claiming its time group is closed.
     *
     * @return authenticated next entry, if learned from successor evidence; it is not closed yet
     */
    public Optional<BlueId> successorId() {
      return Optional.ofNullable(successor == null ? null : successor.entry.id);
    }
  }

  /**
   * A verified original occurrence. Content remains in the caller's separate exact-content store.
   */
  public static final class Entry {
    private final BlueId id;
    private final long timestamp;
    private final BlueId actor;
    private final BlueId message;
    final Head header;
    final Map<String, Head> values;
    private final boolean mayInvokeOperation;

    private Entry(
        BlueId id,
        long timestamp,
        BlueId actor,
        BlueId message,
        Head header,
        Map<String, Head> values,
        boolean mayInvokeOperation) {
      this.id = id;
      this.timestamp = timestamp;
      this.actor = actor;
      this.message = message;
      this.header = header;
      this.values = Map.copyOf(values);
      this.mayInvokeOperation = mayInvokeOperation;
    }

    /**
     * Whether this occurrence may invoke an operation. False identifies an ordinary event after
     * complete message and operation-marker verification. Such an event still belongs to raw event
     * observers. A recovered header without the classification returns true. This is a conservative
     * scheduling hint, never permission or receiver membership.
     *
     * @return false only for a verified non-operation event
     */
    public boolean mayInvokeOperation() {
      return mayInvokeOperation;
    }

    /**
     * Returns a verified explicit operation target when retained by this bounded verifier. This is
     * a scheduling hint, never receiver membership or permission. Empty results require
     * conservative routing; an ordinary event or a target outside the retention bound may be empty.
     *
     * @return optional exact target identity
     */
    public Optional<BlueId> operationTarget() {
      return values.size() == 1
          ? Optional.of(new BlueId(values.keySet().iterator().next()))
          : Optional.empty();
    }

    /**
     * Identifies the complete original event.
     *
     * @return original occurrence identity, which must be preserved when feeding Contracts
     */
    public BlueId blueId() {
      return id;
    }

    /**
     * Reports the publication position.
     *
     * @return provider-assigned integer microsecond timestamp
     */
    public long timestamp() {
      return timestamp;
    }

    /**
     * Identifies the attributed actor.
     *
     * @return exact actor content identity
     */
    public BlueId actorId() {
      return actor;
    }

    /**
     * Identifies the supplied message independently of its contextual declaration.
     *
     * @return exact original message identity, without inherited property documentation
     */
    public BlueId messageId() {
      return message;
    }
  }

  /**
   * An immutable advancement result; no partial result is issued after any verification failure.
   */
  public static final class Result {
    private final Prefix prefix;
    private final List<Entry> entries;

    private Result(Prefix prefix, List<Entry> entries) {
      this.prefix = prefix;
      this.entries = List.copyOf(entries);
    }

    /**
     * Returns the immutable result of successful verification.
     *
     * @return new verified prefix, leaving the previous prefix unchanged
     */
    public Prefix prefix() {
      return prefix;
    }

    /**
     * Lists the newly complete occurrences for a separate consumer.
     *
     * @return newly closed entries in order, excluding the still-open authenticated successor
     */
    public List<Entry> entries() {
      return entries;
    }
  }
}
