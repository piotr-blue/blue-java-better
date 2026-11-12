package blue;

import static blue.LanguageCore.finalId;
import static blue.LanguageCore.identity;
import static blue.LanguageSemantics.ancestry;
import static blue.LanguageSemantics.head;
import static blue.LanguageSemantics.recipe;
import static blue.TimelineContent.require;
import static blue.TimelineVerifier.id;

import blue.ExactNode.Ref;
import blue.ExactNode.Value;
import blue.TimelineVerifier.Fact;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Request-bound transport verification. Partial claims never seed Language or prefix authority. */
final class TimelineReads {
  private static final Set<String> FAILURES =
      Set.of(
          "INVALID_REQUEST",
          "UNAUTHENTICATED",
          "ACCESS_DENIED",
          "NOT_FOUND",
          "HEAD_CONFLICT",
          "IDEMPOTENCY_CONFLICT",
          "BOUND_UNAVAILABLE",
          "LIMIT_EXCEEDED",
          "TIME_EXHAUSTED",
          "UNAVAILABLE",
          "INTEGRITY_ERROR");

  final TimelineVerifier verifier;
  final BigInteger maximumPageSize;
  final BlueId controlTimeline;

  record Checkpoint(BlueId id, long before, BlueId head) {}

  record Progress(TimelineVerifier.Prefix start, Fact head, Fact pending, Checkpoint checkpoint) {}

  record Query(
      LanguageContent request,
      BigInteger limit,
      long minimum,
      BlueId anchor,
      Long afterTime,
      BlueId checkpoint,
      BlueId cursor) {}

  TimelineReads(TimelineVerifier verifier, LanguageContent descriptor) {
    this.verifier = verifier;
    record Discovery(BigInteger limit, BlueId control) {}
    Discovery discovery =
        Language.run(
            () -> {
              TimelineContent fields = context(descriptor, null);
              ExactNode body = attested(fields, descriptor.semantic);
              fields.typed(body, "Provider Descriptor");
              require(
                  identity(
                          inline(
                              fields.required(body, "Provider Descriptor", "provider"),
                              "descriptor.provider"))
                      .equals(verifier.provider.blueId().value()),
                  "TIMELINE_PROVIDER_MISMATCH",
                  "descriptor.provider");
              require(
                  fields
                      .text(body, "Provider Descriptor", "endpoint")
                      .equals(verifier.provider.at("/endpoint").text()),
                  "TIMELINE_PROVIDER_MISMATCH",
                  "descriptor.endpoint");
              ExactNode control =
                  inline(
                      fields.required(body, "Provider Descriptor", "controlTimeline"),
                      "controlTimeline");
              fields.typed(control, "Signed Timeline");
              require(
                  identity(fields.required(control, "Timeline", "provider"))
                      .equals(verifier.provider.blueId().value()),
                  "TIMELINE_PROVIDER_MISMATCH",
                  "controlTimeline.provider");
              require(
                  fields.bool(control, "Timeline", "publicRead"),
                  "TIMELINE_PRIVATE_CONTROL",
                  "controlTimeline");
              TimelineVerifier.canonicalUrl(fields.text(control, "Timeline", "url"));
              BigInteger limit = fields.bigInteger(body, "Provider Descriptor", "maxPageSize");
              require(limit.signum() > 0, "TIMELINE_PAGE_LIMIT", "maxPageSize");
              var operations =
                  head(
                      recipe(fields.required(body, "Provider Descriptor", "operations")),
                      fields.context);
              require(operations.items != null, "TIMELINE_OPERATIONS", "operations");
              for (var operation : operations.items) {
                Object value = head(operation, fields.context).value;
                require(value instanceof String, "TIMELINE_OPERATIONS", "operation identity");
                finalId(value);
              }
              // Baseline routes are mandatory without advertisement. The list may be empty;
              // advertised optional operation identities do not install their semantics here.
              fields.validate(descriptor.semantic);
              return new Discovery(limit, id(control));
            });
    maximumPageSize = discovery.limit;
    controlTimeline = discovery.control;
  }

  TimelineReader.Read begin(
      TimelineReader reader, Progress progress, LanguageContent request, BlueId expectedCursor) {
    return Language.run(
        () -> {
          TimelineContent fields = context(request, progress);
          Query query = TimelineRequests.inspectRead(fields, verifier, maximumPageSize, request);
          if (expectedCursor != null)
            require(
                expectedCursor.equals(query.cursor),
                "TIMELINE_CURSOR",
                "exact issued cursor required");
          require(
              progress.checkpoint == null || progress.checkpoint.id.equals(query.checkpoint),
              "TIMELINE_CHECKPOINT_CHANGED",
              "continuation checkpoint");
          return new TimelineReader.Read(reader, progress, query);
        });
  }

  TimelineReader.Page accept(TimelineReader.Read read, LanguageContent response) {
    return Language.run(
        () -> {
          Query query = read.query;
          Progress prior = read.progress;
          TimelineContent fields = context(response, prior, query.request);
          ExactNode result = response(fields, response, query.request);
          fields.typed(result, "Timeline Read Response");
          ExactNode attestation =
              inline(fields.required(result, "Timeline Read Response", "checkpoint"), "checkpoint");
          Checkpoint checkpoint = checkpoint(fields, attestation);
          require(
              checkpoint.before >= query.minimum && checkpoint.before >= prior.start.before,
              "TIMELINE_BOUND",
              "minimumCompleteBefore");
          require(
              checkpoint.head != null || prior.head == null,
              "TIMELINE_EQUIVOCATION",
              "empty checkpoint after an established entry");
          require(
              query.checkpoint == null || query.checkpoint.equals(checkpoint.id),
              "TIMELINE_CHECKPOINT_CHANGED",
              "request cursor");
          require(
              prior.checkpoint == null || prior.checkpoint.equals(checkpoint),
              "TIMELINE_CHECKPOINT_CHANGED",
              "prior page");
          ExactNode page = fields.supplied(result, "Timeline Read Response", "page");
          if (query.limit.signum() == 0) {
            require(page == null, "TIMELINE_UNEXPECTED_PAGE", "limit=0");
            inspectResponse(fields, response.semantic, result);
            TimelineVerifier.Prefix completed = null;
            if (Objects.equals(
                checkpoint.head, prior.head == null ? null : prior.head.entry().blueId())) {
              fields.validate(response.semantic);
              completed =
                  verifier.completed(
                      prior.start, prior.head, prior.pending, checkpoint.before, checkpoint.head);
              if (prior.checkpoint == null)
                verifier.rememberRead(prior.start, completed, checkpoint.id, List.of());
            }
            fields.rememberFacts();
            return new TimelineReader.Page(
                read.reader,
                new Progress(prior.start, prior.head, prior.pending, checkpoint),
                false,
                List.of(),
                null,
                completed);
          }
          require(page != null, "TIMELINE_PAGE_REQUIRED", "positive limit");
          inline(page, "page");
          fields.typed(page, "Timeline Entries Page");
          verifier.sameTimeline(fields.required(page, "Timeline Entries Page", "timeline"));
          require(
              id(fields.required(page, "Timeline Entries Page", "checkpoint"))
                  .equals(checkpoint.id),
              "TIMELINE_CHECKPOINT_CHANGED",
              "page.checkpoint");
          ExactNode predecessor = fields.supplied(page, "Timeline Entries Page", "predecessor");
          BlueId predecessorId = id(predecessor);
          BlueId knownHead = prior.head == null ? null : prior.head.entry().blueId();
          require(
              Objects.equals(predecessorId, knownHead),
              "TIMELINE_ANCHOR",
              "unestablished predecessor");
          if (predecessor != null) wireEntry(fields, inline(predecessor, "predecessor"));
          long predecessorTime = prior.head == null ? -1 : prior.head.entry().timestamp();
          if (query.anchor != null)
            require(query.anchor.equals(predecessorId), "TIMELINE_ANCHOR", "afterEntry");
          else if (query.afterTime != null)
            require(predecessorTime <= query.afterTime, "TIMELINE_SEEK", "predecessor timestamp");
          else require(predecessor == null, "TIMELINE_ANCHOR", "genesis");
          List<ExactNode> entries = fields.entries(page, "Timeline Entries Page");
          require(
              BigInteger.valueOf(entries.size()).compareTo(query.limit) <= 0,
              "TIMELINE_PAGE_LIMIT",
              "entries");
          Fact last = prior.head;
          Fact pending = prior.pending;
          var verified = new ArrayList<TimelineVerifier.Entry>();
          for (ExactNode node : entries) {
            wireEntry(fields, inline(node, "entry"));
            Fact entry = verifier.entry(fields, node);
            require(
                Objects.equals(entry.previous(), last == null ? null : last.entry().blueId()),
                "TIMELINE_CHAIN",
                "prevEntry");
            require(
                entry.entry().timestamp() >= prior.start.before
                    && entry.entry().timestamp() > (last == null ? -1 : last.entry().timestamp())
                    && entry.entry().timestamp() < checkpoint.before,
                "TIMELINE_TIME",
                "page timestamp");
            if (query.afterTime != null)
              require(
                  entry.entry().timestamp() > query.afterTime, "TIMELINE_SEEK", "entry timestamp");
            if (pending != null)
              require(
                  pending.entry().blueId().equals(entry.entry().blueId()),
                  "TIMELINE_EQUIVOCATION",
                  "known successor");
            pending = null;
            last = entry;
            fields.context.establishedValues.put(entry.entry().blueId().value(), entry.header());
            verified.add(entry.entry());
          }
          boolean hasMore = fields.bool(page, "Timeline Entries Page", "hasMore");
          ExactNode cursor = fields.supplied(page, "Timeline Entries Page", "nextCursor");
          require(hasMore == (cursor != null), "TIMELINE_CURSOR", "nextCursor presence");
          if (hasMore) {
            require(
                !entries.isEmpty()
                    && checkpoint.head != null
                    && !checkpoint.head.equals(last.entry().blueId()),
                "TIMELINE_NONADVANCING_PAGE",
                "hasMore");
            inline(cursor, "nextCursor");
            fields.typed(cursor, "Timeline Read Cursor");
            verifier.sameTimeline(fields.required(cursor, "Timeline Read Cursor", "timeline"));
            require(
                checkpoint.id.equals(
                        id(fields.required(cursor, "Timeline Read Cursor", "checkpoint")))
                    && last.entry()
                        .blueId()
                        .equals(id(fields.required(cursor, "Timeline Read Cursor", "afterEntry")))
                    && fields.supplied(cursor, "Timeline Read Cursor", "afterTimestamp") == null,
                "TIMELINE_CURSOR",
                "next position");
            fields.inspect(cursor, "Timeline Read Cursor", Set.of("checkpoint", "afterEntry"));
          }
          fields.inspect(page, "Timeline Entries Page", Set.of("checkpoint", "nextCursor"));
          inspectResponse(fields, response.semantic, result);
          TimelineVerifier.Prefix completed = null;
          if (!hasMore) {
            completed =
                verifier.completed(prior.start, last, pending, checkpoint.before, checkpoint.head);
            fields.validate(response.semantic);
            if (prior.checkpoint == null)
              verifier.rememberRead(prior.start, completed, checkpoint.id, verified);
          }
          fields.rememberFacts();
          return new TimelineReader.Page(
              read.reader,
              new Progress(prior.start, last, pending, checkpoint),
              true,
              verified,
              cursor,
              completed);
        });
  }

  TimelineReader.EntryRead fetchLastEntry(TimelineReader.Page page, LanguageContent request) {
    return Language.run(
        () -> {
          TimelineContent fields = context(request, page.progress);
          ExactNode node = request.semantic;
          fields.typed(node, "Fetch Timeline Entry Request");
          for (String forbidden :
              List.of("requestId", "provider", "createdAt", "expiresAt", "nonce"))
            require(
                fields.supplied(node, "Provider Request", forbidden) == null,
                "TIMELINE_REQUEST_FIELDS",
                forbidden);
          verifier.sameTimeline(fields.required(node, "Fetch Timeline Entry Request", "timeline"));
          require(
              fields.required(node, "Fetch Timeline Entry Request", "entry") instanceof Ref,
              "TIMELINE_WIRE_REFERENCE",
              "entry");
          require(
              page.progress
                  .head
                  .entry()
                  .blueId()
                  .equals(id(fields.required(node, "Fetch Timeline Entry Request", "entry"))),
              "TIMELINE_WRONG_ENTRY",
              "last page entry");
          fields.inspect(node, "Fetch Timeline Entry Request", Set.of("entry"));
          return new TimelineReader.EntryRead(this, page.progress, request);
        });
  }

  LanguageContent acceptEntry(TimelineReader.EntryRead read, LanguageContent response) {
    return Language.run(
        () -> {
          TimelineContent fields = context(response, read.progress, read.request());
          ExactNode result = response(fields, response, read.request());
          fields.typed(result, "Timeline Entry Response");
          ExactNode entry =
              inline(fields.required(result, "Timeline Entry Response", "entry"), "entry");
          require(
              id(entry).equals(read.progress.head.entry().blueId()),
              "TIMELINE_WRONG_ENTRY",
              "response.entry");
          wireEntry(fields, entry);
          ExactNode acceptance =
              inline(
                  fields.required(result, "Timeline Entry Response", "entryAttestation"),
                  "entryAttestation");
          ExactNode accepted = attestedObject(fields, acceptance);
          require(
              id(accepted).equals(id(entry)), "TIMELINE_WRONG_ENTRY", "entryAttestation.object");
          // These exact identities were checked above against the privately verified page entry.
          // Inspect transport headers and extras without reopening its predecessor or payload.
          fields.inspect(acceptance, "Provider Attestation", Set.of("object"));
          fields.inspect(
              result, "Timeline Entry Response", Set.of("request", "entry", "entryAttestation"));
          fields.inspect(response.semantic, "Provider Attestation", Set.of("object"));
          return TimelineContent.detachedContent(acceptance);
        });
  }

  TimelineReader.ContentRead content(LanguageContent request) {
    BlueId target =
        Language.run(
            () ->
                TimelineRequests.inspectReference(
                    context(request, null),
                    verifier,
                    request,
                    "Resolve Timeline Content Request",
                    "content"));
    return new TimelineReader.ContentRead(this, request, target);
  }

  LanguageContent acceptContent(TimelineReader.ContentRead read, LanguageContent response) {
    return Language.run(
        () -> {
          TimelineContent fields = context(response, null, read.request);
          ExactNode result = response(fields, response, read.request);
          fields.typed(result, "Timeline Content Response");
          ExactNode content =
              inline(fields.required(result, "Timeline Content Response", "content"), "content");
          require(id(content).equals(read.target), "TIMELINE_WRONG_CONTENT", "response.content");
          // Content may be a definition or contain unresolved references. Check the transport
          // without pretending that a signed body is an admitted value or a verified prefix.
          fields.inspect(result, "Timeline Content Response", Set.of("request", "content"));
          fields.inspect(response.semantic, "Provider Attestation", Set.of("object"));
          return TimelineContent.detachedContent(content);
        });
  }

  TimelineReader.AccessRead access(LanguageContent request) {
    return Language.run(
        () -> {
          var parsed = TimelineRequests.inspectAccess(context(request, null), request);
          return new TimelineReader.AccessRead(this, request, parsed.id());
        });
  }

  TimelineReader.AccessDescription acceptAccess(
      TimelineReader.AccessRead read, LanguageContent response) {
    return Language.run(
        () -> {
          TimelineContent fields = context(response, null, read.request);
          ExactNode result = response(fields, response, read.request);
          fields.typed(result, "Timeline Access Response");
          ExactNode initial =
              inline(fields.required(result, "Timeline Access Response", "access"), "access");
          ExactNode current =
              inline(fields.required(result, "Timeline Access Response", "current"), "current");
          require(id(initial).equals(read.target), "TIMELINE_WRONG_ACCESS", "response.access");
          fields.typed(initial, "Timeline Access");
          fields.typed(current, "Timeline Access");
          ExactNode publication =
              inline(
                  fields.required(result, "Timeline Access Response", "publication"),
                  "publication");
          ExactNode latest =
              inline(fields.required(result, "Timeline Access Response", "lastEntry"), "lastEntry");
          ExactNode firstEntry = accessEntry(fields, publication);
          ExactNode lastEntry = accessEntry(fields, latest);
          require(
              id(fields.required(firstEntry, "Timeline Entry", "message")).equals(read.target),
              "TIMELINE_WRONG_ACCESS",
              "publication.message");
          require(
              fields.integer(lastEntry, "Timeline Entry", "timestamp")
                  >= fields.integer(firstEntry, "Timeline Entry", "timestamp"),
              "TIMELINE_ACCESS_ORDER",
              "lastEntry precedes publication");
          fields.inspect(
              result,
              "Timeline Access Response",
              Set.of("request", "access", "current", "publication", "lastEntry"));
          fields.inspect(response.semantic, "Provider Attestation", Set.of("object"));
          return new TimelineReader.AccessDescription(
              TimelineContent.detachedContent(initial),
              TimelineContent.detachedContent(current),
              TimelineContent.detachedContent(publication),
              TimelineContent.detachedContent(latest));
        });
  }

  private ExactNode accessEntry(TimelineContent fields, ExactNode attestation) {
    ExactNode entry = attested(fields, attestation);
    fields.typed(entry, "Timeline Entry");
    require(
        id(fields.required(entry, "Timeline Entry", "timeline")).equals(controlTimeline),
        "TIMELINE_WRONG_CONTROL",
        "access entry timeline");
    long timestamp = fields.integer(entry, "Timeline Entry", "timestamp");
    require(
        timestamp >= 0 && timestamp < TimelineVerifier.FINAL_BOUND,
        "TIMELINE_TIMESTAMP",
        "access entry timestamp");
    wireEntry(fields, entry);
    fields.inspect(
        entry, "Timeline Entry", Set.of("timeline", "prevEntry", "message", "operation"));
    fields.inspect(attestation, "Provider Attestation", Set.of("object"));
    return entry;
  }

  private ExactNode response(
      TimelineContent fields, LanguageContent response, LanguageContent request) {
    ExactNode result = attested(fields, response.semantic);
    fields.typed(result, "Provider Response");
    require(
        identity(fields.required(result, "Provider Response", "request"))
            .equals(request.blueId().value()),
        "TIMELINE_WRONG_REQUEST",
        "response.request");
    if (ancestry(head(recipe(result), fields.context).type, fields.context)
        .contains(fields.type("Provider Failure"))) {
      String code = fields.text(result, "Provider Failure", "code");
      require(FAILURES.contains(code), "TIMELINE_FAILURE_CODE", code);
      boolean retryable = fields.bool(result, "Provider Failure", "retryable");
      String detail =
          fields.supplied(result, "Provider Failure", "detail") == null
              ? null
              : fields.text(result, "Provider Failure", "detail");
      fields.inspect(result, "Provider Failure", Set.of("request"));
      fields.inspect(response.semantic, "Provider Attestation", Set.of("object"));
      throw new TimelineReader.Failure(code, retryable, detail, response.blueId());
    }
    return result;
  }

  private TimelineContent context(
      LanguageContent content, Progress progress, LanguageContent... more) {
    var contents = new ArrayList<LanguageContent>();
    contents.add(content);
    contents.add(verifier.timeline);
    contents.add(verifier.provider);
    contents.add(verifier.publication.document());
    contents.addAll(List.of(more));
    LanguageContext context = content.owner.context(contents.toArray(LanguageContent[]::new));
    context.establishedValues = verifier.targetFacts();
    context.facts = verifier.facts;
    if (progress != null) {
      if (progress.head != null)
        context.establishedValues.put(
            progress.head.entry().blueId().value(), progress.head.header());
      if (progress.start.head != null)
        context.establishedValues.put(
            progress.start.head.entry().blueId().value(), progress.start.head.header());
    }
    return new TimelineContent(context, verifier.publication);
  }

  private ExactNode attested(TimelineContent fields, ExactNode attestation) {
    return inline(attestedObject(fields, attestation), "response object");
  }

  private ExactNode attestedObject(TimelineContent fields, ExactNode attestation) {
    fields.typed(attestation, "Provider Attestation");
    require(
        identity(fields.required(attestation, "Provider Attestation", "provider"))
            .equals(verifier.provider.blueId().value()),
        "TIMELINE_PROVIDER_MISMATCH",
        "attestation.provider");
    ExactNode object = fields.required(attestation, "Provider Attestation", "object");
    verifier.signatures.verifyObject(
        id(object), fields.text(attestation, "Provider Attestation", "signature"));
    return object;
  }

  private Checkpoint checkpoint(TimelineContent fields, ExactNode attestation) {
    fields.typed(attestation, "Timeline Attestation");
    verifier.sameTimeline(fields.required(attestation, "Timeline Attestation", "timeline"));
    ExactNode guarantee =
        inline(fields.required(attestation, "Timeline Attestation", "guarantee"), "guarantee");
    fields.typed(guarantee, "Timeline Completeness Guarantee");
    verifier.sameTimeline(
        fields.required(guarantee, "Timeline Completeness Guarantee", "timeline"));
    verifier.signatures.verifyGuarantee(
        id(guarantee), fields.text(attestation, "Timeline Attestation", "signature"));
    long before = fields.integer(guarantee, "Timeline Completeness Guarantee", "completeBefore");
    require(
        before >= 0 && before <= TimelineVerifier.FINAL_BOUND, "TIMELINE_BOUND", "completeBefore");
    ExactNode last = fields.supplied(guarantee, "Timeline Completeness Guarantee", "lastEntry");
    require(last == null || last instanceof Ref, "TIMELINE_WIRE_REFERENCE", "lastEntry");
    require(before != 0 || last == null, "TIMELINE_BOUND", "entry before timestamp zero");
    fields.inspect(guarantee, "Timeline Completeness Guarantee", Set.of("lastEntry"));
    fields.inspect(attestation, "Timeline Attestation", Set.of("guarantee"));
    return new Checkpoint(id(attestation), before, id(last));
  }

  private static ExactNode inline(ExactNode node, String field) {
    require(node instanceof Value, "TIMELINE_WIRE_INLINE", field);
    return node;
  }

  private static void wireEntry(TimelineContent fields, ExactNode node) {
    ExactNode previous = fields.supplied(node, "Timeline Entry", "prevEntry");
    require(previous == null || previous instanceof Ref, "TIMELINE_WIRE_REFERENCE", "prevEntry");
  }

  private static void inspectResponse(
      TimelineContent fields, ExactNode envelope, ExactNode response) {
    fields.inspect(response, "Timeline Read Response", Set.of("request", "checkpoint", "page"));
    fields.inspect(envelope, "Provider Attestation", Set.of("object"));
  }
}
