package blue;

import static blue.TimelineContent.require;
import static blue.TimelineVerifier.id;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Parses published read requests for one independently selected provider and timeline. Parsing
 * establishes syntax and exact target binding only. It does not authenticate a reader, grant
 * access, recognize a retained checkpoint or establish the requested anchor's membership. The
 * provider must check current authorization on every use, including cursor continuation.
 */
public final class TimelineRequests {
  private static final String READ = "Read Timeline Request";
  private final TimelineVerifier verifier;
  private final BigInteger maximumPageSize;

  private TimelineRequests(TimelineVerifier verifier, int maximumPageSize) {
    if (maximumPageSize < 1) {
      throw new IllegalArgumentException("Positive maximum page size required");
    }
    this.verifier = verifier;
    this.maximumPageSize = BigInteger.valueOf(maximumPageSize);
  }

  /**
   * Selects the provider, exact owned timeline and host page bound without granting permission.
   *
   * @param coordination supported exact publication
   * @param provider admitted provider selected by trusted configuration
   * @param timeline admitted exact timeline selected through an authorized host lookup
   * @param maximumPageSize positive maximum entries per page
   * @return immutable parser safe to share for this target
   */
  public static TimelineRequests forTimeline(
      BluePackage coordination,
      LanguageDocument provider,
      LanguageDocument timeline,
      int maximumPageSize) {
    return new TimelineRequests(
        TimelineVerifier.adopt(coordination, provider, timeline), maximumPageSize);
  }

  /**
   * Validates a Read Timeline Request without resolving untrusted history selectors. Inherited
   * request values are supported. Missing selector bodies are incomplete, never treated as absent.
   *
   * @param request exact prepared request with its supporting type evidence
   * @return parsed query; authorization and stored-history membership still require checks
   */
  public Read read(LanguageContent request) {
    Objects.requireNonNull(request);
    return Language.run(
        () -> new Read(inspectRead(context(request), verifier, maximumPageSize, request)));
  }

  /**
   * Validates a Fetch Timeline Entry Request; the exact entry reference must be looked up inside
   * the selected timeline after authorization. A known BlueId is not evidence of membership.
   *
   * @param request exact prepared request
   * @return the requested exact entry identity
   */
  public BlueId entry(LanguageContent request) {
    return reference(request, "Fetch Timeline Entry Request", "entry");
  }

  /**
   * Validates a scoped content request without treating a known BlueId as reachable content.
   *
   * @param request exact prepared Resolve Timeline Content Request
   * @return target identity requiring a current permission and source-closure lookup
   */
  public BlueId content(LanguageContent request) {
    return reference(request, "Resolve Timeline Content Request", "content");
  }

  private BlueId reference(LanguageContent request, String type, String field) {
    Objects.requireNonNull(request);
    return Language.run(() -> inspectReference(context(request), verifier, request, type, field));
  }

  static BlueId inspectReference(
      TimelineContent fields,
      TimelineVerifier verifier,
      LanguageContent request,
      String type,
      String field) {
    ExactNode node = request.semantic;
    fields.typed(node, type);
    readOnly(fields, node);
    verifier.sameTimeline(fields.required(node, type, "timeline"));
    ExactNode target = fields.required(node, type, field);
    require(target instanceof ExactNode.Ref, "TIMELINE_WIRE_REFERENCE", field);
    fields.inspect(node, type, Set.of(field));
    return id(target);
  }

  /** The supported Timeline read-only request categories, never owner mutations. */
  public enum Operation {
    /** Paginated entries and completeness. */
    READ,
    /** One original entry and its reusable acceptance. */
    ENTRY,
    /** One exact source-reachable content value. */
    CONTENT
  }

  /**
   * Validates an unsigned read request or authenticates a Signed Reader Request for this source. A
   * supplied signature is always checked, including on public sources. The signed request body must
   * be inline. No lookup or network fetch is performed to obtain a referenced request body.
   *
   * @param wire prepared exact request or signed reader envelope
   * @return exact request and optional authenticated key; this grants no permission
   */
  public Reader reader(LanguageContent wire) {
    Objects.requireNonNull(wire);
    return Language.run(
        () -> {
          TimelineContent fields = context(wire);
          Authenticated authenticated = authenticate(fields, wire);
          LanguageContent request = authenticated.request();
          String key = authenticated.key();
          Operation operation;
          if (isType(fields, request.semantic, READ)) {
            read(request);
            operation = Operation.READ;
          } else if (isType(fields, request.semantic, "Fetch Timeline Entry Request")) {
            entry(request);
            operation = Operation.ENTRY;
          } else if (isType(fields, request.semantic, "Resolve Timeline Content Request")) {
            content(request);
            operation = Operation.CONTENT;
          } else {
            throw new BlueException(
                "TIMELINE_READER_OPERATION", "Only Timeline read operations are allowed");
          }
          return new Reader(request, operation, key);
        });
  }

  /**
   * Parses public access-document discovery and validates any supplied reader signature. The host
   * must look up the original access identity within this provider's committed control history.
   * This does not authorize an owner mutation or confirm an access document's current state.
   *
   * @param wire prepared Get Timeline Access Request or signed reader envelope
   * @return exact inner request and original access identity for a provider-scoped lookup
   */
  public Access access(LanguageContent wire) {
    Objects.requireNonNull(wire);
    return Language.run(
        () -> {
          TimelineContent fields = context(wire);
          LanguageContent request = authenticate(fields, wire).request();
          return inspectAccess(fields, request);
        });
  }

  static Access inspectAccess(TimelineContent fields, LanguageContent request) {
    ExactNode node = request.semantic;
    fields.typed(node, "Get Timeline Access Request");
    readOnly(fields, node);
    ExactNode target = fields.required(node, "Get Timeline Access Request", "access");
    require(target instanceof ExactNode.Ref, "TIMELINE_WIRE_REFERENCE", "access");
    fields.inspect(node, "Get Timeline Access Request", Set.of("access"));
    return new Access(request, id(target));
  }

  /** Public discovery request data, with no owner or permission authority. */
  public static final class Access {
    private final LanguageContent request;
    private final BlueId id;

    private Access(LanguageContent request, BlueId id) {
      this.request = request;
      this.id = id;
    }

    /**
     * Returns the exact inner request used for response correlation.
     *
     * @return original public discovery request
     */
    public LanguageContent request() {
      return request;
    }

    /**
     * Returns the original access-document identity requiring provider-scoped lookup.
     *
     * @return original access identity, not a confirmation of publication
     */
    public BlueId id() {
      return id;
    }
  }

  private record Authenticated(LanguageContent request, String key) {}

  private static Authenticated authenticate(TimelineContent fields, LanguageContent wire) {
    if (!isType(fields, wire.semantic, "Signed Reader Request")) {
      return new Authenticated(wire, null);
    }
    ExactNode body = fields.required(wire.semantic, "Signed Reader Request", "request");
    require(body instanceof ExactNode.Value, "TIMELINE_WIRE_INLINE", "request");
    fields.inspect(wire.semantic, "Signed Reader Request", Set.of("request"));
    LanguageContent request =
        new LanguageContent(
            wire.owner, body, new Language.Snapshot(fields.context.bodies, fields.context.proofs));
    String key = fields.text(wire.semantic, "Signed Reader Request", "publicKey");
    ProviderSignatures.verifyReaderRequest(
        request.blueId(), key, fields.text(wire.semantic, "Signed Reader Request", "signature"));
    return new Authenticated(request, key);
  }

  /** Authenticated request data, not an owner identity or a current permission certificate. */
  public static final class Reader {
    private final LanguageContent request;
    private final Operation operation;
    private final String key;

    private Reader(LanguageContent request, Operation operation, String key) {
      this.request = request;
      this.operation = operation;
      this.key = key;
    }

    /**
     * Returns the exact inner request used for response correlation, excluding its envelope.
     *
     * @return original read-only request
     */
    public LanguageContent request() {
      return request;
    }

    /**
     * Identifies the validated read operation for transport routing.
     *
     * @return read, entry or content
     */
    public Operation operation() {
      return operation;
    }

    /**
     * Identifies a key whose signature was verified for this exact request.
     *
     * @return authenticated key, absent on a bare request; private reads still need current grants
     */
    public Optional<String> publicKey() {
      return Optional.ofNullable(key);
    }
  }

  private static boolean isType(TimelineContent fields, ExactNode value, String type) {
    var head = LanguageSemantics.head(LanguageSemantics.recipe(value), fields.context);
    return head.type != null
        && LanguageSemantics.ancestry(head.type, fields.context).contains(fields.type(type));
  }

  private TimelineContent context(LanguageContent content) {
    return new TimelineContent(
        content.owner.context(
            content, verifier.timeline, verifier.provider, verifier.publication.document()),
        verifier.publication);
  }

  /** Parsed read parameters. Instances are data, never permission or history certificates. */
  public static final class Read {
    private final TimelineReads.Query query;

    private Read(TimelineReads.Query query) {
      this.query = query;
    }

    /**
     * Retains the exact input used for response correlation.
     *
     * @return exact original request, retained for response correlation
     */
    public LanguageContent request() {
      return query.request();
    }

    /**
     * Reports the requested page length.
     *
     * @return requested entry count; zero means checkpoint only
     */
    public int limit() {
      return query.limit().intValueExact();
    }

    /**
     * Reports the requested exclusive completeness bound.
     *
     * @return requested exclusive minimum bound, zero when none was supplied
     */
    public long minimumCompleteBefore() {
      return query.minimum();
    }

    /**
     * Identifies the stored checkpoint selected by a continuation cursor.
     *
     * @return checkpoint to recognize in durable storage, absent on a fresh read
     */
    public Optional<BlueId> checkpoint() {
      return Optional.ofNullable(query.checkpoint());
    }

    /**
     * Identifies the entry immediately preceding the requested interval.
     *
     * @return exact predecessor selector, absent for genesis or timestamp seeking
     */
    public Optional<BlueId> afterEntry() {
      return Optional.ofNullable(query.anchor());
    }

    /**
     * Reports the timestamp seek boundary when one was supplied.
     *
     * @return exclusive lower timestamp, absent for genesis or exact-entry seeking
     */
    public OptionalLong afterTimestamp() {
      return query.afterTime() == null ? OptionalLong.empty() : OptionalLong.of(query.afterTime());
    }
  }

  static TimelineReads.Query inspectRead(
      TimelineContent fields,
      TimelineVerifier verifier,
      BigInteger maximumPageSize,
      LanguageContent request) {
    ExactNode node = request.semantic;
    fields.typed(node, READ);
    for (String forbidden : List.of("requestId", "provider", "createdAt", "expiresAt", "nonce")) {
      require(
          fields.supplied(node, "Provider Request", forbidden) == null,
          "TIMELINE_REQUEST_FIELDS",
          forbidden);
    }
    verifier.sameTimeline(fields.required(node, READ, "timeline"));
    BigInteger limit = fields.bigInteger(node, READ, "limit");
    require(
        limit.signum() >= 0 && limit.compareTo(maximumPageSize) <= 0,
        "TIMELINE_PAGE_LIMIT",
        "limit");
    ExactNode after = fields.supplied(node, READ, "afterEntry");
    Long time =
        optionalTime(fields, node, READ, "afterTimestamp", -1, TimelineVerifier.FINAL_BOUND - 1);
    ExactNode cursor = fields.supplied(node, READ, "cursor");
    Long minimum =
        optionalTime(fields, node, READ, "minimumCompleteBefore", 0, TimelineVerifier.FINAL_BOUND);
    require(
        (after == null ? 0 : 1) + (time == null ? 0 : 1) + (cursor == null ? 0 : 1) <= 1,
        "TIMELINE_SELECTORS",
        "at most one selector");
    require(
        limit.signum() > 0 || after == null && time == null && cursor == null,
        "TIMELINE_SELECTORS",
        "checkpoint-only request");
    require(cursor == null || minimum == null, "TIMELINE_SELECTORS", "cursor with minimum bound");
    BlueId checkpoint = null;
    if (cursor != null) {
      fields.typed(cursor, "Timeline Read Cursor");
      verifier.sameTimeline(fields.required(cursor, "Timeline Read Cursor", "timeline"));
      checkpoint = id(fields.required(cursor, "Timeline Read Cursor", "checkpoint"));
      after = fields.supplied(cursor, "Timeline Read Cursor", "afterEntry");
      time =
          optionalTime(
              fields,
              cursor,
              "Timeline Read Cursor",
              "afterTimestamp",
              -1,
              TimelineVerifier.FINAL_BOUND - 1);
      require(after == null || time == null, "TIMELINE_SELECTORS", "cursor selectors");
      fields.inspect(cursor, "Timeline Read Cursor", Set.of("checkpoint", "afterEntry"));
    }
    fields.inspect(node, READ, Set.of("afterEntry", "cursor"));
    return new TimelineReads.Query(
        request, limit, minimum == null ? 0 : minimum, id(after), time, checkpoint, id(cursor));
  }

  private static void readOnly(TimelineContent fields, ExactNode node) {
    for (String forbidden : List.of("requestId", "provider", "createdAt", "expiresAt", "nonce")) {
      require(
          fields.supplied(node, "Provider Request", forbidden) == null,
          "TIMELINE_REQUEST_FIELDS",
          forbidden);
    }
  }

  private static Long optionalTime(
      TimelineContent fields, ExactNode node, String type, String key, long minimum, long maximum) {
    if (fields.supplied(node, type, key) == null) {
      return null;
    }
    long value = fields.integer(node, type, key);
    require(value >= minimum && value <= maximum, "TIMELINE_TIME", key);
    return value;
  }
}
