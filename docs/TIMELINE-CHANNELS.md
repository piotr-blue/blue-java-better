# Published Timeline channel bindings

`TimelineChannels` installs the two exact native channel bindings from Coordination
`1.0.0-deterministic.2`, plus Document Dispatch, before initialization. It connects an explicit
host history verifier to ordinary Contracts execution. It does not create a timeline service,
fetch history, discover required sources or authorize private reads.

The bundled `TimelineAccess` implementation uses this same adapter after authenticating its
provider control history and confirming the exact access program. Its complete working example
is in [Timeline Access](TIMELINE-ACCESS.md). General receiving documents still need their own
protocol-complete host verifier; the library does not provide that service yet.

[Receiving-state inspection](RECEIVING-STATE.md) exposes the same native declarations and channel
relationships used by execution, without running a verifier. MyOS Better's ingress uses it for signed source preparation, access, original-order and target
checks. The local and AWS hosts retain independent owner histories. The campaign report identifies
which private-source, historical and cyclic cases have been tested; this adapter alone does not
establish complete Coordination conformance.

## Configure once, execute independent sessions

This factory receives a verified publication and an implemented host verifier. There is no
accept-all default or caller-supplied `ready` flag that substitutes for that verifier.

```java
static Contracts configure(
    BluePackage coordination,
    TimelineChannels.HistoryVerifier historyVerifier) {
  Language language = Language.newBuilder()
      .mount("Coordination", coordination)
      .mount("Contracts", Contracts.publication())
      .build();
  TimelineChannels channels = TimelineChannels.verifiedBy(coordination, historyVerifier);
  return Contracts.newBuilder()
      .language(language)
      .timelineChannels(channels)
      .build();
}
```

`Contracts.newBuilder().timelineChannels(channels)` installs the same profile for canonical atomic
graph sessions. One registration is reusable across independent sessions. Both bindings are
preselected even if the initial document uses only one; adding the other later does not change the
execution profile. Conflicts are checked before either registration mutates the builder.

The publication must equal `ProviderSignatures.COORDINATION_PACKAGE`. Mounting it merely supplies
definitions. Selecting these channels does not assert support for every other Coordination capability.
Existing snapshots still require their exact selected bindings when restored.

When the host has acquired and verified an original entry, it can use the existing scoped overload:

```java
static Contracts.Transition consume(
    Contracts.Session session,
    TimelineVerifier.Entry entry,
    AdmissionEvidence evidence) {
  return session.process(entry, evidence);
}
```

The opaque `entry` supplies its original identity and timestamp. This saves repeated Language
history validation; it does **not** bypass the history verifier. The configured Language provider supplies retained content.
`evidence` carries untrusted proof data keyed by the exact channel type. The host must separately
bind its command, selected source, receiver state and source progress in durable publication.

## What the host verifier must establish

`HistoryVerifier.verify(Request)` receives immutable snapshot/input/original-entry identities,
timestamp, channel type, receiver declarations and untrusted evidence. A receiver includes its
document identity, member, channel, generation, declaration, header and previous checkpoint.
The input identity can be a local command envelope; `entryId()` remains the original source entry.

For every receiver, the verifier must establish authenticated source membership, exact required-source
closure and ordering, historical access, established target lineage, channel lifetime and operation
audience. Public Timeline channels require the exact provider/URL source with `publicRead: true`;
they do not confer extra business or write authority. The Timeline channel's holder/actor rules are
separate. An opaque verified prefix alone does not establish all these receiving-document facts.

Return exactly one `Assessment` for each unchanged receiver from the request, in any order:

```java
TimelineChannels.Assessment assessment = new TimelineChannels.Assessment(
    receiver,
    TimelineChannels.Disposition.ELIGIBLE,
    addressed,
    "Authenticated direct source participation");
```

This constructs a result **inside a verifier that has already established those facts**. It does
not authenticate anything by itself. `INAPPLICABLE` means established nonparticipation. Missing
history, unresolved lineage or pending access must reject the attempt with `request.reject(reason)`;
they must never become fabricated negative assessments or source-completion claims. All rows for
one member must agree on its operation audience.

The callback is trusted Java, must be deterministic and thread-safe, and must work against frozen
history. It must not mutate documents or perform external effects. The adapter bounds materialized
inputs and output, but cannot preempt arbitrary callback CPU or constrain all host allocations.
Acquisition and resource admission belong outside it. A bad host verifier can violate the protocol;
the presence of a callback is not proof of correct authority checking.

## Exact commitment and gas

The bindings match Coordination section14.6: exact type/rules identities, external-channel role,
published headers/namespaces, no inherited subtype behavior, no body/peer fields, live metering,
and only `accept: 1`. The generic `NativeChannel` builder's invocation/admission tariff is unchanged;
it cannot be used to reproduce this exact published profile by changing a counter.

The adapter constructs a closed commitment with `snapshot`, `entry`, `timestamp`, `runtime`,
`decisions`, `assessments` and `operationAudience`. Decision keys are compact JSON arrays of
member/document/channel/generation. Assessments are sorted by member then channel using Contracts
code-point order. Each contains exactly those four identifiers, disposition and reason. Negative
decisions and their reasons affect the receipt. Raw credentials, signatures and transport pages
are omitted. Deterministic reasons must therefore describe the established decision, not local
cache state or transport details.

The public `blue.coordination.TimelineChannelsTest` covers accepted/rejected decisions, restored
sessions, unchanged state on invalid assessment, atomic configuration conflicts and commitment
sensitivity. `blue.TimelineChannelBindingTest` owns exact wire fields and Unicode ordering. Real
signed execution remains in `blue.coordination.TimelineAccessTest`; the adapter fixtures alone do
not establish general feeder or private-access conformance. All run in the normal full build.
