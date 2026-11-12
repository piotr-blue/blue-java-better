# Incremental timeline verification

`TimelineVerifier` verifies the two completeness proof forms in Coordination
1.0.0-deterministic.2: `Timeline Range Evidence` and `Timeline Successor Evidence`.
It uses the same package pin as `ProviderSignatures`. It does not install native
execution, grant access, acquire provider responses or implement a feeder.

```java
BluePackage coordination = PackageLoader.create().open(
    Path.of("coordination.bluepkg"), ProviderSignatures.COORDINATION_PACKAGE);
Language language = Language.newBuilder()
    .mount("Coordination", coordination)
    .provider(contentStore)
    .build();

// These are selected by trusted configuration, not adopted from an arbitrary response.
LanguageDocument provider = language.readYaml(providerYaml);
LanguageDocument timeline = language.readYaml(timelineYaml);
TimelineVerifier verifier = TimelineVerifier.adopt(coordination, provider, timeline);
TimelineVerifier.Prefix prefix = verifier.initial();

// Transport preparation verifies exact content construction without walking all predecessors.
LanguageContent evidence = language.prepare(Language.Input.exactJson(evidenceJson));
TimelineVerifier.Result result = verifier.advance(prefix, evidence);
prefix = result.prefix();

long closedBefore = prefix.completeBefore();
Optional<BlueId> lastClosedEntry = prefix.headId();
for (TimelineVerifier.Entry entry : result.entries()) {
  BlueId originalEvent = entry.blueId();
  BlueId originalMessage = entry.messageId();
  long timestampMicros = entry.timestamp();
  // Retain these facts with their exact content. A separate feeder determines execution eligibility.
}
```

The example assumes the content store can supply the adopted objects and newly needed content.
An unavailable dependency raises `LanguageException` with `incomplete() == true`. It cannot be
interpreted as absence or a valid empty prefix. Invalid protocol evidence raises `BlueException`;
invalid Language content raises `LanguageException`. A failed call returns no new prefix or entries.

The prefix constructor is private, and a prefix belongs to the exact verifier that issued it.
For recovery, `restore(checkpoint, expectedIdentity, retainedContent)` verifies a previously
committed host checkpoint against its independently trusted identity and exact source binding.
`restoreTrusted` adapts an older protected host record. Neither API accepts a remote checkpoint
as authority or proves history to another consumer: their inputs must come from storage whose
integrity the host has established. A bare head hash and unsigned bound are not completeness proof.
The host serializes updates from its latest prefix. Concurrent branches from an old prefix are
independent verification results, not a consensus mechanism.

## What is checked

- Actual published type ancestry, including domain subtypes. Structural resemblance to a provider,
  actor, entry or proof envelope does not select its protocol role.
- Exact provider and timeline identity, canonical HTTPS timeline URL, inherited provider key and
  endpoint, signature domain and signed object identity.
- The exact `after` anchor, every newly required entry, predecessor adjacency, strictly increasing
  timestamps, the previous exclusive bound and the newly promised final head.
- Required actor/message fields, Principal/Agent actor ancestry and optional Source ancestry.
- An Operation Request must use the same exact content as `message`. Its closed fields, tokens,
  request/document references and exact-version flag use the existing Contracts validator.
- Full scoped Language validation of new content and contextual restrictions. Previously verified
  history does not excuse a newly introduced schema or field refinement.
- Positive empty-prefix assertions and conflicts against an already authenticated successor.

The original message is selected from supplied content, including inherited domain defaults.
The base type's `message` description is a declaration, not a supplied message. An explicitly
supplied unvalued definition such as `{type: Text}` is nevertheless a valid original message.
Contextual property documentation is not silently added to that message's signed identity.

## Two proof forms, one prefix

Suppose accepted entry A has timestamp10 and its accepted successor S has timestamp20.

| Evidence | Closed head | Exclusive bound | Newly closed entries |
| --- | --- | --- | --- |
| Signed guarantee with `lastEntry: A`, `completeBefore: 15` | A | 15 | A, if not previously closed |
| Signed accepted S with the contiguous chain through S | A | 20 | Entries before S |
| Later signed guarantee with `lastEntry: S`, `completeBefore: 21` | S | 21 | S |

S is retained as a known successor after the second row; it is not closed at timestamp20.
A later proof cannot omit or replace it while claiming to close beyond20. An accepted genesis
successor proves the empty prefix before its timestamp. Repeating a same-head guarantee at the
same or higher bound is valid; regressions are rejected by this advancement API.

## Why history length does not determine the next verification cost

Each verified entry supplies a private, invocation-local Language validation fact. The next call
receives only the previous head's detached typed header, plus an optional authenticated successor.
Headers contain exact references rather than payloads, snapshots or predecessor bodies. The
prefix retains no growing list of entries. Input content and history remain in the caller's store.

Only unconstrained reuse of an established value can use this fact. Additional contextual schema
or field restrictions follow ordinary validation and may require additional content. The facts
are never imported from wire records, placed in global caches or exposed as a general bypass for
Language admission. Metadata conflicts still go through ordinary head interpretation.

The new suffix still costs work proportional to its entries and required message/type content.
This is not constant time for arbitrarily large messages, definition graphs or newly supplied ranges.
Whole-history admission, enumeration, serialization and network acquisition are separate costs.

## Feeding an original entry without reopening its history

After the host has selected an eligible original occurrence and frozen the required evidence:

```java
TimelineVerifier.Entry original = result.entries().getFirst();
// session is a canonical Contracts.Session with the required native verifiers installed.
Contracts.Transition transition = session.process(original, admissionEvidence);
transition.requireApplied();
```

Here the session's configured Language provider supplies exact message content, and
`admissionEvidence` is that invocation's independently verified authorization input. The Entry
is opaque and issued only after verification; the overload derives its original identity/time and
scopes its validated header to this call. Native verifiers still run. Source coverage, historical
permissions and target selection remain host obligations; an Entry alone grants no business access.
A fresh session can use the header without rereading all predecessors. Message content remains
validated on demand. No scoped facts are serialized as authority or leak into ordinary Language
admission. Retain bodies separately; the header contains references rather than predecessor content.

The local and AWS hosts derive active sources from native declarations, track verified coverage,
and commit results with progress. Internal timelines share a local authority boundary; external
timelines advance independently through verified imports. The [campaign report](CANONICAL-CAMPAIGN-2026-11-08.md)
records the exact replay, rejection and remote-refresh checks and the remaining scaling limits.

## Qualification and remaining integration

`blue.coordination.TimelineVerifierTest` tests the public API, grouped into prefix advancement,
integrity boundaries, original content, document execution and locality. Locality tests delete every old entry body
before advancing after1,128 and1024 entries: exactly one new entry is fetched in each case.
Signature primitives and unrelated-provider rejection remain in `ProviderSignaturesTest`.

Run `./gradlew coordinationTest`, or `./gradlew build` for all semantics and strict checks.
The standalone iteration89 probe additionally compares128 samples after1,128,1024 and10000 prior
entries in three isolated JVMs. See the archived report for raw timings and measurement boundaries.

[TimelineReader](TIMELINE-READING.md) now verifies signed discovery, exact response/request correlation
and paginated reads using the same prefix authority. Reader authorization, historical access, required-source derivation and execution-history obligations
are host concerns and have selected integration coverage in the companion hosts. Full published
Timeline/Coordination/Mandates/Time services and arbitrary dynamic topology changes are not claimed.
A verified prefix supplies source coverage; it does not itself establish document-processing order.
