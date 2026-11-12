# Executable Timeline Access

`TimelineAccess` replays one access document on the adopted provider's reserved public control
timeline. It executes the published `Update Timeline Access` BEX program through canonical Contracts.
It does not assign permission lists with a separate Java interpreter.

Its authenticated execution uses the same [TimelineChannels](TIMELINE-CHANNELS.md) adapter available
to hosts. Here the tracker supplies the history verifier after checking the confirmed source,
publication, original entry/time and exact program. General host use still needs its own complete
receiving-history verifier; this access-specific verifier is not exposed as general admission.

The supported publication is Coordination `1.0.0-deterministic.2`, pinned by
`ProviderSignatures.COORDINATION_PACKAGE`. Sections 7, 12 and 14 of its `spec/coordination.md` define
the publication, execution and historical-authority rules. Loading another edition does not silently
select this implementation.

## Define the initial document

The application selects a trusted provider, verifies/retains its signed bootstrap descriptor and
supplies the package and referenced content. For example, with admitted `provider` and `timeline`:

```java
Language language = Language.newBuilder()
    .mount("Coordination", coordination)
    .content(provider)
    .content(timeline)
    .build();
LanguageDocument initial = language.readYaml("""
    type: Coordination/Timeline Access
    allowedTimelines:
      - blueId: '%s'
    allowedKeys:
      - '1111111111111111111111111111111111111111111111111111111111111111'
    contracts:
      provider:
        provider: {blueId: '%s'}
        url: https://provider.example/control
    """.formatted(timeline.blueId(), provider.blueId()));
```

The reader key above is test data. The real key must identify the intended reader. The provider URL
must be the exact reserved public control timeline confirmed by its signed descriptor. Each allowed
timeline belongs to that provider. The operation is inherited from the package; replacing its program,
adding another contract or selecting a different control source is rejected.

Authoring this document grants nothing. Its exact initial identity must appear as an Agent Actor's
published message in verified control history. Its ordinary Contracts initialization then establishes
the first executed permission state.

## Confirm publication and execute subsequent updates

`descriptor` and each `range` below are prepared exact protocol content, with required supporting
records available through the originating `LanguageProvider`. They are not booleans or trusted
current-state snapshots. The host obtains the signed evidence; this API performs no HTTP requests.

```java
try (TimelineAccess access = TimelineAccess.track(
    coordination, provider, descriptor, initial)) {
  Optional<TimelineAccess.View> unconfirmed = access.view(); // empty

  TimelineAccess.Update publication = access.advance(publicationRange);
  TimelineAccess.View first = publication.view().orElseThrow();
  System.out.println(first.document().toYaml());

  TimelineAccess.Update update = access.advance(updateRange);
  TimelineAccess.View current = update.view().orElseThrow();
  Set<BlueId> timelines = current.allowedTimelines();
  Set<String> readerKeys = current.allowedKeys();

  for (TimelineAccess.Disposition entry : update.dispositions()) {
    System.out.println(entry.entry() + " " + entry.kind());
    if (entry.transition().isPresent()) {
      Contracts.Transition result = entry.transition().orElseThrow();
      LanguageDocument after = entry.document().orElseThrow();
      System.out.println(result.events());
      System.out.println(result.gas());
      System.out.println(result.receipt());
      System.out.println(after.toYaml());
    }
  }
}
```

For an actual update, the provider publishes this standard message and copies it exactly into the
Timeline Entry's `operation` field:

```yaml
type: Contracts/Operation Request
document: {blueId: '<original initial access document>'}
channel: provider
operation: update
request:
  type: Coordination/Timeline Access Update
  allowedKeys: []
  allowedTimelines: []
```

Both replacement lists are required. Empty lists revoke the pairs granted by this access document;
they do not revoke independent grants from other documents. The original initial document remains
the stable target for every update. The complete original entry, including its identity and timestamp,
enters Contracts. Successful updates emit the published `Timeline Access Updated` event, including
when the requested lists equal the current lists. Two replacements in one range execute in order;
the later lists win.

## Keep the historical states

`View.completeBefore()` is an authenticated exclusive history bound, not a wall-clock freshness
promise. `View.changedAt()` is the last applicable publication/update timestamp. A shared control
timeline may advance the bound without changing this document.

Each applicable disposition retains its actual after-document and real transition. Earlier returned
views remain immutable. Retain these states and the original evidence: a later revocation must not
erase the proof of an earlier grant, and a later grant cannot authorize an earlier input. This API
does not itself implement arbitrary historical lookup, grant union or current private-read admission.

Unrelated entries are `INAPPLICABLE`, with no invented Contracts invocation. A malformed proof,
wrong source, non-Agent publication/update, duplicate publication or accepted invalid replacement
fails without publishing any part of that newly supplied suffix. Invalid accepted updates are provider
integrity failures; they are not silently skipped. The host must retain/reconcile contradictory claims.

## Execution lifetime and performance

Calls on one tracker are serialized. Its initial source is selected once; opaque verified prefix
facts are scoped to execution and revoked from captured resolvers when the call ends. Ordinary
Language admission still requires its own evidence. New content and additional constraints are fully
validated. Logical gas, receipt calculation and the original input are unchanged.

Verified new content is retained as each suffix arrives, including unrelated entries. This prevents
the first later operation from rediscovering all old predecessors. The tracker is process-local and
retains content/execution history: it is not a bounded durable store, trusted restart API or garbage
collector. Closing it releases its owned state; retained views remain usable. Hosts must bound its
lifetime and persist their own evidence. Do not represent a restored hash or serialized view as an
issued verified prefix.

The published duplicate checks use nested list scans, so large replacement permission lists can
require quadratic BEX work. History locality does not make arbitrarily large new requests constant
time. Changing that program requires an explicit new publication identity.

The public suite `blue.coordination.TimelineAccessTest` groups confirmation, executed replacements
and integrity/atomicity. It runs with `./gradlew coordinationTest` and the ordinary full build. Tests
use the self-contained published package and a public RFC test signing seed, never deployment keys.
The iteration91 report contains paired first-update/warmed measurements, exact gas/receipt/document
comparisons, and the reverted experiment. HTTP enforcement, durable provenance, multi-source ordering,
native feeder services and integration into the deployed host remain separate unfinished work.
