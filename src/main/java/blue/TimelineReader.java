package blue;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Authenticates provider discovery and paginated reads that extend an established timeline prefix.
 * No HTTP requests, reader credentials or private-access decisions are made by this class. Use the
 * same exact request for transport and response verification; private transport must separately
 * supply the reader's proof and satisfy the provider's current access policy.
 *
 * <p>Pages expose authenticated occurrences, but usable completeness advances only after all
 * contiguous pages reach the signed checkpoint head. Continuations retain a boundary, not earlier
 * pages. The caller retains content and evidence, serializes progress and handles conflicts.
 */
public final class TimelineReader {
  final TimelineReads protocol;

  private TimelineReader(TimelineReads protocol) {
    this.protocol = protocol;
  }

  /**
   * Adopts one timeline using a signed descriptor from its already trusted provider.
   *
   * @param coordination exact supported Coordination package
   * @param provider admitted provider selected by trusted configuration
   * @param timeline admitted Signed Timeline selected by trusted configuration
   * @param descriptor inline Provider Attestation of Provider Descriptor
   * @return immutable reader with the verified provider page-size limit
   */
  public static TimelineReader adopt(
      BluePackage coordination,
      LanguageDocument provider,
      LanguageDocument timeline,
      LanguageContent descriptor) {
    return adopt(TimelineVerifier.adopt(coordination, provider, timeline), descriptor);
  }

  /**
   * Adds provider discovery and paginated reads to an already adopted history verifier. Prefixes
   * issued by that verifier, including authenticated-successor progress, remain usable here.
   *
   * @param verifier explicitly adopted timeline verifier
   * @param descriptor inline signed Provider Descriptor
   * @return reader sharing the verifier's prefix authority
   */
  public static TimelineReader adopt(TimelineVerifier verifier, LanguageContent descriptor) {
    return new TimelineReader(
        new TimelineReads(Objects.requireNonNull(verifier), Objects.requireNonNull(descriptor)));
  }

  /**
   * Reports the provider's signed page-size limit without an invented protocol-wide cap.
   *
   * @return positive arbitrary-precision advertised limit
   */
  public BigInteger maximumPageSize() {
    return protocol.maximumPageSize;
  }

  /**
   * Identifies the provider's signed reserved public control timeline.
   *
   * @return exact control history identity; a customer timeline cannot replace it
   */
  public BlueId controlTimelineId() {
    return protocol.controlTimeline;
  }

  /**
   * Starts with the empty prefix before timestamp zero.
   *
   * @return opaque prefix issued by this reader's verifier
   */
  public TimelineVerifier.Prefix initial() {
    return protocol.verifier.initial();
  }

  /**
   * Validates a bare typed Read Timeline Request before transport. Timestamp seeks must reconcile
   * their returned predecessor with this known prefix; an arbitrary seek cannot prove skipped
   * history.
   *
   * @param prefix last published prefix issued by this reader
   * @param request exact typed request; signing for private transport is separate
   * @return immutable outstanding read, bound to that exact request
   */
  public Read begin(TimelineVerifier.Prefix prefix, LanguageContent request) {
    Objects.requireNonNull(prefix);
    TimelineContent.require(prefix.owner == protocol.verifier, "TIMELINE_PREFIX_OWNER", "prefix");
    return protocol.begin(
        this,
        new TimelineReads.Progress(prefix, prefix.head, prefix.successor, null),
        Objects.requireNonNull(request),
        null);
  }

  /**
   * Validates a bare Resolve Timeline Content Request for this selected source. Returned content
   * still needs ordinary Language validation; this operation grants no history or access authority.
   *
   * @param request exact typed request; signing for private transport is separate
   * @return immutable read bound to the requested exact identity
   */
  public ContentRead content(LanguageContent request) {
    return protocol.content(Objects.requireNonNull(request));
  }

  /**
   * Validates a public Get Timeline Access Request for this adopted provider. The response supplies
   * authenticated discovery data, never a permission grant.
   *
   * @param request exact unsigned typed access request
   * @return immutable read bound to the original access identity
   */
  public AccessRead access(LanguageContent request) {
    return protocol.access(Objects.requireNonNull(request));
  }

  /** An exact provider-scoped access discovery request. */
  public static final class AccessRead {
    final TimelineReads protocol;
    final LanguageContent request;
    final BlueId target;

    AccessRead(TimelineReads protocol, LanguageContent request, BlueId target) {
      this.protocol = protocol;
      this.request = request;
      this.target = target;
    }

    /**
     * Returns the exact unsigned request used for response correlation.
     *
     * @return immutable request
     */
    public LanguageContent request() {
      return request;
    }

    /**
     * Verifies the signed response and its control-timeline entry attestations. The initial
     * document still needs strict admission and {@link TimelineAccess} replay through a complete
     * verified control prefix. The claimed current document is not authority.
     *
     * @param response inline Provider Attestation of Timeline Access Response or typed failure
     * @return authenticated discovery claims without history completeness or access authority
     * @throws Failure if the provider signed a correlated failure
     * @throws BlueException if the response does not match the request or trusted provider
     * @throws LanguageException if required transport evidence is invalid or unavailable
     */
    public AccessDescription accept(LanguageContent response) {
      return protocol.acceptAccess(this, Objects.requireNonNull(response));
    }
  }

  /**
   * Authenticated discovery data; none of these fields grants access without control replay.
   *
   * @param initial exact original access document, not yet strictly admitted
   * @param claimedCurrent provider's claimed current document, not a replay result
   * @param publication signed original publication entry on the reserved control timeline
   * @param lastEntry signed claimed last affecting entry on that same timeline
   */
  public record AccessDescription(
      LanguageContent initial,
      LanguageContent claimedCurrent,
      LanguageContent publication,
      LanguageContent lastEntry) {
    /** Requires all immutable discovery fields. Construction grants no authority. */
    public AccessDescription {
      Objects.requireNonNull(initial);
      Objects.requireNonNull(claimedCurrent);
      Objects.requireNonNull(publication);
      Objects.requireNonNull(lastEntry);
    }
  }

  /** An exact content lookup, independent of any claim about timeline completeness. */
  public static final class ContentRead {
    final TimelineReads protocol;
    final LanguageContent request;
    final BlueId target;

    ContentRead(TimelineReads protocol, LanguageContent request, BlueId target) {
      this.protocol = protocol;
      this.request = request;
      this.target = target;
    }

    /**
     * Returns the original request whose identity must appear in the signed response.
     *
     * @return exact unsigned request, suitable for serialization
     */
    public LanguageContent request() {
      return request;
    }

    /**
     * Verifies the provider, signature, request correlation and returned inline content identity.
     * Nested references are not resolved here, and definitions need not be complete values.
     *
     * @param response inline Provider Attestation of Timeline Content Response or typed failure
     * @return prepared exact content, without an admission or completeness certificate
     * @throws Failure if the provider signed a correlated failure
     * @throws BlueException if the signature, content, provider or request differs
     * @throws LanguageException if transport headers are invalid or unavailable
     */
    public LanguageContent accept(LanguageContent response) {
      return protocol.acceptContent(this, Objects.requireNonNull(response));
    }
  }

  /** An exact outstanding request and its previously verified continuity boundary. */
  public static final class Read {
    final TimelineReader reader;
    final TimelineReads.Progress progress;
    final TimelineReads.Query query;

    Read(TimelineReader reader, TimelineReads.Progress progress, TimelineReads.Query query) {
      this.reader = reader;
      this.progress = progress;
      this.query = query;
    }

    /**
     * Returns the request whose identity must appear in the signed response.
     *
     * @return original immutable request, suitable for exact serialization
     */
    public LanguageContent request() {
      return query.request();
    }

    /**
     * Verifies a response without mutating this outstanding read or its prior prefix.
     *
     * @param response inline Provider Attestation of the response or a typed failure
     * @return authenticated page and optional completed prefix
     * @throws Failure if the provider signed a correlated failure; this never advances history
     * @throws BlueException if protocol evidence is invalid or conflicts with the known prefix
     * @throws LanguageException if content is invalid or required evidence is unavailable
     */
    public Page accept(LanguageContent response) {
      return reader.protocol.accept(this, Objects.requireNonNull(response));
    }
  }

  /** An authenticated read result. A checkpoint-only response is distinct from an empty page. */
  public static final class Page {
    final TimelineReader reader;
    final TimelineReads.Progress progress;
    private final boolean includesEntries;
    private final List<TimelineVerifier.Entry> entries;
    private final LanguageContent cursor;
    private final BlueId cursorId;
    private final TimelineVerifier.Prefix completed;

    Page(
        TimelineReader reader,
        TimelineReads.Progress progress,
        boolean includesEntries,
        List<TimelineVerifier.Entry> entries,
        ExactNode cursor,
        TimelineVerifier.Prefix completed) {
      this.reader = reader;
      this.progress = progress;
      this.includesEntries = includesEntries;
      this.entries = List.copyOf(entries);
      this.cursor = cursor == null ? null : TimelineContent.detachedContent(cursor);
      cursorId = TimelineVerifier.id(cursor);
      this.completed = completed;
    }

    /**
     * Distinguishes a requested page from a checkpoint-only query.
     *
     * @return true exactly when the request had a positive limit
     */
    public boolean includesEntries() {
      return includesEntries;
    }

    /**
     * Returns this page's authenticated original occurrences, without retaining earlier pages.
     *
     * @return ordered entries; their presence alone does not close the selected prefix
     */
    public List<TimelineVerifier.Entry> entries() {
      return entries;
    }

    /**
     * Identifies the checkpoint fixed throughout this read.
     *
     * @return exact Timeline Attestation identity
     */
    public BlueId checkpointId() {
      return progress.checkpoint().id();
    }

    /**
     * Reports the provider's signed claim, even if its entries have not all been acquired.
     *
     * @return exclusive checkpoint bound; use completedPrefix to obtain usable completeness
     */
    public long claimedCompleteBefore() {
      return progress.checkpoint().before();
    }

    /**
     * Identifies the signed checkpoint's claimed final entry, whose body may arrive on a later
     * page. This does not establish that the entry or preceding history has been acquired.
     *
     * @return claimed head identity, absent for an empty checkpoint
     */
    public Optional<BlueId> claimedHeadId() {
      return Optional.ofNullable(progress.checkpoint().head());
    }

    /**
     * Returns the exact next cursor, if another page is required.
     *
     * @return immutable exact cursor content for the next request
     */
    public Optional<LanguageContent> nextCursor() {
      return Optional.ofNullable(cursor);
    }

    /**
     * Returns usable completeness only after continuity and the final head were verified. A
     * checkpoint-only reply can advance an already known identical head, but cannot invent its
     * data.
     *
     * @return completed prefix, absent while more entries or evidence are needed
     */
    public Optional<TimelineVerifier.Prefix> completedPrefix() {
      return Optional.ofNullable(completed);
    }

    /**
     * Binds a continuation request to exactly the cursor this page returned. The limit may change
     * within the provider's advertised bound. Reader authorization must be rechecked by the server.
     *
     * @param request next exact bare Read Timeline Request, containing this page's cursor
     * @return next outstanding read retaining only this verified boundary
     */
    public Read continueWith(LanguageContent request) {
      TimelineContent.require(cursorId != null, "TIMELINE_NO_CONTINUATION", "nextCursor");
      return reader.protocol.begin(reader, progress, Objects.requireNonNull(request), cursorId);
    }

    /**
     * Requests a reusable acceptance attestation for the last entry verified in this page. A host
     * can use that attestation in ordinary Timeline Successor Evidence to commit a bounded chunk
     * without claiming the unread remainder of this checkpoint is complete.
     *
     * @param request exact Fetch Timeline Entry Request naming this timeline and last page entry
     * @return outstanding exact-entry read; transport and reader authorization remain with the host
     * @throws BlueException if this page has no entries or the request names another entry
     */
    public EntryRead fetchLastEntry(LanguageContent request) {
      TimelineContent.require(!entries.isEmpty(), "TIMELINE_ENTRY_REQUIRED", "last page entry");
      return reader.protocol.fetchLastEntry(this, Objects.requireNonNull(request));
    }
  }

  /** A request-bound acceptance read retaining one already verified entry header. */
  public static final class EntryRead {
    final TimelineReads protocol;
    final TimelineReads.Progress progress;
    private final LanguageContent request;

    EntryRead(TimelineReads protocol, TimelineReads.Progress progress, LanguageContent request) {
      this.protocol = protocol;
      this.progress = progress;
      this.request = request;
    }

    /**
     * Returns the exact request whose identity must appear in the signed response.
     *
     * @return immutable request, suitable for transport serialization
     */
    public LanguageContent request() {
      return request;
    }

    /**
     * Verifies both the correlated response and the reusable entry acceptance attestation. This
     * does not advance completeness: the host must still submit its contiguous entries and this
     * attestation through TimelineVerifier before publishing a prefix. Retain the response's
     * content records when the inner attestation references its separately returned entry body.
     *
     * @param response exact Provider Attestation of Timeline Entry Response or Provider Failure
     * @return verified Provider Attestation of the requested entry, retaining its exact wire body
     * @throws Failure if the provider returns a signed, correlated failure
     * @throws BlueException if the entry, provider, request or signature differs
     */
    public LanguageContent accept(LanguageContent response) {
      return protocol.acceptEntry(this, Objects.requireNonNull(response));
    }
  }

  /**
   * A provider-authenticated, request-correlated failure; it never proves absence or revocation.
   */
  public static final class Failure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** Signed failure category. */
    private final String code;

    /** Signed retry classification. */
    private final boolean retryable;

    /** Canonical text of the attestation identity. */
    private final String response;

    Failure(String code, boolean retryable, String detail, BlueId response) {
      super(detail == null ? code : detail);
      this.code = code;
      this.retryable = retryable;
      this.response = response.value();
    }

    /**
     * Reports the signed failure category independently of diagnostic prose or HTTP status.
     *
     * @return published provider failure code
     */
    public String code() {
      return code;
    }

    /**
     * Reports the provider's retry classification without scheduling any work.
     *
     * @return signed retryable flag
     */
    public boolean retryable() {
      return retryable;
    }

    /**
     * Identifies the authenticated response retained by the caller.
     *
     * @return exact Provider Attestation identity
     */
    public BlueId responseId() {
      return new BlueId(response);
    }
  }
}
