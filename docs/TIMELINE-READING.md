# Authenticated timeline reads

`TimelineReader` verifies signed provider discovery and paginated read responses from the pinned
Coordination 1.0.0-deterministic.2 publication. It shares `TimelineVerifier`'s opaque prefix model.
The application owns HTTP, reader credentials, content storage, evidence retention and serialized
progress. Importing the package or constructing a reader installs no native service.

## Adopt the provider, then verify discovery

```java
BluePackage coordination = PackageLoader.create().open(
    Path.of("coordination.bluepkg"), ProviderSignatures.COORDINATION_PACKAGE);
Language language = Language.newBuilder()
    .mount("Coordination", coordination)
    .provider(contentStore)
    .build();
LanguageDocument provider = language.readYaml(providerYaml);
LanguageDocument timeline = language.readYaml(timelineYaml);

// Exact identity and trust in this provider come from application configuration.
TimelineVerifier verifier = TimelineVerifier.adopt(coordination, provider, timeline);
LanguageContent descriptor = language.prepare(Language.Input.exactJson(descriptorJson));
TimelineReader reader = TimelineReader.adopt(verifier, descriptor);
BigInteger maximumPageSize = reader.maximumPageSize();
BlueId controlTimeline = reader.controlTimelineId();
TimelineVerifier.Prefix prefix = reader.initial();
```

Here `contentStore` implements `LanguageProvider`; it supplies the adopted documents and referenced
definitions/messages. The JSON strings are responses already obtained by the application. Bootstrap
discovery includes its descriptor, provider and public control timeline inline. The reader checks
the signature, provider/endpoint binding, public control source and advertised positive page limit.
Optional-operation advertisements may be empty; mandatory baseline routes do not depend on them.
Advertising an operation does not install executable semantics in the client.

## Bind the response to the exact request

```java
BigInteger limit = reader.maximumPageSize().min(BigInteger.valueOf(100));
LanguageContent request = language.prepare(Language.Input.yaml("""
    type: Coordination/Read Timeline Request
    timeline: {blueId: '%s'}
    limit: %s
    """.formatted(timeline.blueId(), limit)));
TimelineReader.Read read = reader.begin(prefix, request);

// Send read.request().toJson() to the provider's /timelines/read route.
// A private read needs the separately constructed Signed Reader Request envelope.
LanguageContent response = language.prepare(Language.Input.exactJson(responseJson));
TimelineReader.Page page = read.accept(response);
```

The signed response must identify exactly `request.blueId()`, not a request label, URL or a loosely
equivalent JSON object. Read requests do not contain `requestId`, a repeated `provider`, clocks or
nonces. At most one of `afterEntry`, `afterTimestamp` and `cursor` is permitted. The page limit is
arbitrary-precision and bounded by discovery, with no invented global cap of 1,000.

The reader authenticates the response and checkpoint, verifies the source, bounds, inline wire
bodies, contiguous entries and every available field constraint. Every page, including nonfinal
pages, validates the entries list and its item refinements. Unknown old or future entry bodies are
not treated as validated values. Missing required content raises `LanguageException` with
`incomplete() == true`; it never becomes an empty history.

## Continue until the signed head is reached

```java
List<TimelineVerifier.Entry> occurrences = page.entries();
long claimedBound = page.claimedCompleteBefore();
Optional<TimelineVerifier.Prefix> complete = page.completedPrefix();

if (page.nextCursor().isPresent()) {
  LanguageContent cursor = page.nextCursor().orElseThrow();
  LanguageContent nextRequest = language.prepare(Language.Input.yaml("""
      type: Coordination/Read Timeline Request
      timeline: {blueId: '%s'}
      limit: %s
      cursor: %s
      """.formatted(timeline.blueId(), limit, cursor.toJson())));
  read = page.continueWith(nextRequest);
  // Send the new request; accept the next response on this new Read.
}
if (complete.isPresent()) {
  prefix = complete.orElseThrow();
  // Publish progress together with retained content/evidence under the host's own atomic policy.
}
```

Each `Page` owns only its current entries. A continuation retains the original prefix and latest
verified boundary, never previous pages or a growing predecessor list. Retain each page's evidence
and bodies in the application's store before discarding them. The returned cursor owns its exact
body, not the preceding response's complete snapshot; referenced custom definitions still belong
in that store.

`claimedCompleteBefore()` is a signed claim. `completedPrefix()` is available only after all links
reach the checkpoint's exact head. Nonterminal pages must contain entries and advance their cursor;
continuations cannot switch checkpoint or position. A returned page is not permission to execute
its entries: access, cross-source ordering and required-source completeness remain separate checks.

After completion, start a fresh request with `afterEntry` equal to `prefix.headId()` to acquire new
appends. A timestamp seek is supported when its predecessor reconciles with the known prefix.
An arbitrary seek cannot establish skipped history. Prefixes from `verifier.advance(...)` also work
with this reader, including an authenticated but not yet closed successor; a read cannot replace it.

## Commit a bounded chunk before the whole checkpoint arrives

An unread checkpoint does not grant completeness. The protocol also supplies a separately signed
acceptance attestation for an exact entry. Authenticate the last entry of a verified page:

```java
BlueId lastEntry = page.entries().getLast().blueId();
LanguageContent fetchRequest = language.prepare(Language.Input.yaml("""
    type: Coordination/Fetch Timeline Entry Request
    timeline: {blueId: '%s'}
    entry: {blueId: '%s'}
    """.formatted(timeline.blueId(), lastEntry)));
TimelineReader.EntryRead fetch = page.fetchLastEntry(fetchRequest);

// POST fetch.request().toJson() to the provider's /timelines/entry route.
LanguageContent entryResponse = language.prepare(Language.Input.exactJson(entryResponseJson));
LanguageContent acceptance = fetch.accept(entryResponse);
```

This verifies the outer response signature, exact request identity, returned entry identity and
the inner reusable acceptance signature against the adopted provider. It retains one verified
entry header, not earlier page bodies. If the inner attestation references the separately returned
entry, retain the response's content records too. Extra transport fields remain subject to validation;
the checked entry reference does not certify the whole request or response as an admitted document.

Assemble ordinary `Timeline Successor Evidence` from the old anchor, the collected contiguous entries
through that entry, and `acceptance`. Submit it to `verifier.advance(prefix, evidence)` and atomically
publish the returned prefix with its content. It proves completeness **before** the accepted entry's
timestamp; that last entry remains pending. It does not prove the unread checkpoint's bound.

The next attempt starts a fresh read after the newly committed prefix head. The pending successor
appears again and closes only when later evidence permits it. The Better host uses this mechanism
after at most eight pages, or earlier to reserve acquisition resources. It requires at least two
collected entries for a nonterminal chunk so every published chunk closes an original occurrence,
including when a provider advertises one-entry pages. A crash repeats only uncommitted acquisition;
the existing archive verifies and restores the last durable proof. No cursor becomes trusted state.

## Checkpoint-only reads and signed failures

`limit: 0` requests a checkpoint without a page or selector. `includesEntries()` is false. An unknown
checkpoint head may yield a claim with no completed prefix and no attempt to fetch the whole history.
A checkpoint for the already established head can advance its bound. An empty checkpoint cannot
contradict an established entry. Positive-limit empty pages have `includesEntries() == true`.

```java
try {
  TimelineReader.Page result = read.accept(response);
} catch (TimelineReader.Failure failure) {
  String category = failure.code();
  boolean retryable = failure.retryable();
  BlueId retainedResponse = failure.responseId();
  // Application policy decides retry/backoff. This failure advances no prefix.
}
```

A failure must itself be signed and correlated. Its code and retry flag come from the typed body,
not HTTP status or prose. The immutable `Read` remains reusable. Invalid signatures/protocol claims
raise `BlueException`; invalid or unavailable Language content raises `LanguageException`.

## Boundaries and qualification

The reader performs no network I/O or current-reader authorization. Reusable reader signatures and
cursors do not bypass revocation: a private provider must authorize every page. This component
verifies accepted-entry retrieval only for a verified page's last entry; it does not implement
arbitrary exact-entry/content acquisition, private read enforcement or trusted durable
prefix restoration, required-source scheduling or native Timeline/Time/Coordination/Mandates services.
The separate [Timeline Access API](TIMELINE-ACCESS.md) executes one confirmed access history.
Concurrent branches from an old prefix are verification results, not consensus; the host must retain
conflicts and serialize publication from its latest progress.

`blue.coordination.TimelineReaderTest` owns discovery/requests, checkpoint/failure claims, pagination
continuity, wire/selected content, and reusable accepted-entry attestations. Long-pagination cases remove historical
bodies before the final page. Run `./gradlew coordinationTest` or the full `./gradlew build`.
The separate iteration90 probe measures final-page verification with and without wire parsing after
1, 128 and 1,024 pages. It excludes network/storage, previous-page establishment and execution;
raw samples, artifact digest and exact measurement boundaries accompany the iteration report.

The pinned package's `spec/coordination.md` sections 2–3, 6 and 11 define discovery/read transport;
sections 12 and 14–16 define the additional feeder and execution obligations. These are distinct
layers, not optional checks that this reader silently supplies.
