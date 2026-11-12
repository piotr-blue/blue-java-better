# Native admission evidence

A native channel may require a verifier before any external channel or workflow executes. This is
an extension mechanism for protocols such as authenticated Timeline inputs; it is not a bundled
Timeline, Time, Mandates or Coordination implementation.

The host supplies a business event and separate `AdmissionEvidence`. Evidence remains untrusted.
An explicitly installed verifier checks it against the exact invocation and returns a small verified
projection. Channel handlers see that projection through `context.admission()`, never the raw proof.

## Register the protocol

The following uses an application-owned `verifyPermit` method implementing the selected protocol:

```java
NativeChannel channel = NativeChannel.newBuilder(channelType, semantics, "permits")
    .counter("signature", 100)
    .verifyAdmission(admission -> verifyPermit(admission))
    .accept(context -> context.channel().equals(context.admission().get("channel")))
    .build();

Contracts contracts = Contracts.newBuilder()
    .language(language)
    .nativeChannel(channel)
    .build();
```

`channelType` is an admitted definition extending `Contracts/Channel`; `semantics` identifies the
complete native protocol. They are Language documents, not strings used as package aliases.
`verifyPermit` is trusted deterministic Java code. It must verify the signature or other authority,
compare all required bindings, charge declared work before performing it, and reject invalid input.
Merely comparing fields supplied inside a proof to other fields from that proof grants no authority.

The verifier's `NativeAdmission` exposes:

| Method | Meaning |
| --- | --- |
| `snapshotIdentity()` | Exact input snapshot, including processing metadata and runtime bindings |
| `eventId()` | Unchanged business payload identity |
| `inputId()` | Execution input identity; a command-identified local call uses its command envelope |
| `timestamp()` | Explicit logical input time, never a wall-clock observation |
| `channelTypeId()`, `semanticsId()` | Exact installed binding |
| `receivers()` | Deterministically ordered receivers of this binding: graph member, document identity, channel name/generation, declaration identity, header and previous checkpoint |
| `evidence()` | Immutable untrusted proof for this type |
| `charge(name, units)`, `remainingGas()` | Shared execution allowance, attributed to the invocation root |
| `reject(reason)` | Abort before any channel executes, with `ADMISSION_REJECTED` |

A typical verifier derives its expected signed message from these authoritative inputs, verifies the
signature using its installed trusted key, and returns only verified fields needed by channel selection.
The test suite includes a real HMAC verification example, including forged and stale permits. Its
fixed fixture key is strictly test data, not a deployment key or recommended authentication protocol.

## Supply evidence explicitly

```java
AdmissionEvidence evidence = AdmissionEvidence.of(channel.typeId(), signedPermit);
Contracts.Transition transition = session.process(event, logicalTimestamp, evidence);
transition.requireApplied();
```

For multiple native types, use immutable composition:

```java
AdmissionEvidence evidence = AdmissionEvidence.of(timelineChannel.typeId(), timelineProof)
    .with(timerChannel.typeId(), timerProof);
```

Proof transport uses the private Contracts record domain: integers, text, booleans, null, lists and
text-keyed maps. Encode binary signatures as text. Floating-point values, undefined and arbitrary Java
objects are rejected at construction, so accepted evidence can survive portable replay.

`.with` returns a new value; previous evidence sets and supplied mutable containers remain independent.
Missing evidence for a verifying type fails with `MISSING_ADMISSION_EVIDENCE`. Evidence for a type
with no active verifier is rejected as `UNUSED_ADMISSION_EVIDENCE`. Importing package data never
registers a verifier or enables its channel.

The ordinary `session.process(event, timestamp)` API is unchanged. It succeeds without evidence only
when the selected native bindings do not require it. Initialization itself is not an external-input
admission and does not run this verifier.

For a durable owner, use the same canonical engine through its storage adapter:

```java
try (Contracts.Execution execution = Contracts.Execution.using(contracts, completions)) {
  Contracts.Execution.State before = execution.restore(checkpoint);
  Contracts.Execution.Outcome outcome =
      execution.process(before, original, logicalTimestamp, evidence);
  Contracts.Transition transition = outcome.transition();
  // Atomically publish the selected original, its disposition, outcome and scheduling obligations.
}
```

`completions` resolves this host's protected committed native records. It is not an untrusted
request assertion. Original identity and timestamp remain unchanged; a database retry key is not
a replacement Blue event. The host fences the selected before-state and source prefix at commit.

## Verification and resource behavior

Verification runs once per active registered type before external selection, including when ordinary
channel checkpoint handling would subsequently recognize a duplicate. Results are attempt-local;
there is no implicit permit cache across calls, snapshots or independent sessions. A stale permit is
not made valid by a matching business event. Restored/compacted sessions retain their configured
verifiers. Detached portable chunk execution rechecks the original evidence and preserves exact gas
and receipt results.

The deterministic verified projection participates in the invocation commitment. Different raw proof
transport fields may yield the same receipt only when the verifier produces the same projection and
the invocation has otherwise identical metered behavior. Protocol designers must include every
verified claim required for replay/receipt interpretation in that projection. The current public
remote-continuation API remains unfinished; the internal chunk behavior is separately tested.

The automatic `admission` tariff is at least one unit, in addition to the channel's `invocation`
tariff. Override its positive cost with `.counter("admission", cost)`. Additional counters share the
same root-local/shared gas allowance as the later channel callbacks and workflows. Trusted native
computation is not preempted automatically. This extension does not calibrate a production tariff
for a particular signature algorithm or proof system.

Proof sets, materialized receivers and verified projections use `ProjectionLimits.defaults()`.
They are copied into immutable containers; oversized output refuses the attempt without publishing
state or events. Those bounds do not reserve all process memory or limit arbitrary trusted Java
computation. Every context method rejects cross-thread use and access after callback return.

## Host obligations still to implement

A snapshot identity is not a durable managed-document/account identity. Graph member names are local
to the snapshot. A host-specific protocol must independently bind its trusted engine/account/document
scope; this API does not infer those identities, authenticate a caller, or establish timeline closure.
A verifier must not treat a caller-asserted account/document field as trusted merely because it appears
in `AdmissionEvidence`. The companion host now provides a scope commitment and durable evidence transport. Complete native
service integration, protocol-specific preparation and production authorization remain next work.

Time additionally requires pending-input ordering, authoritative time, closed relevant histories,
channel generations, exact effect provenance, atomic scheduling/cancellation/disposition and recovery.
A timer-shaped event or a successful generic verifier alone does not satisfy those requirements.
This API neither changes the published Time package nor advertises its native support.

`blue.contracts.NativeAdmissionTest` owns public evidence, signed binding rejection, atomic failure,
immutability, metering, canonical processing, restoration, compaction and session isolation.
`blue.NativeAdmissionReplayTest` owns detached chunk re-verification and exact receipt/gas parity.


## Durable admission disposition

A durable host should distinguish unverified input from an authorized workflow failure:

```java
Contracts.Transition transition = session.process(original, logicalTimestamp, evidence);
if (!transition.admissionVerified()) {
    // A configured native verifier did not establish admission. Do not consume the input.
} else {
    // Examine the native status, then publish the authorized result and cursor atomically.
}
```

The flag describes verifier completion for this attempt, not transferable permission. Configure
the required verifier and inspect the receiving declarations before admitting source work. A verified
business failure still has rollback and cursor-disposition rules. Missing resources remain pending.
See [the canonical migration policy](../CANONICAL-MIGRATION.md#failure-policy) for the two hosts.

For durable evidence capture, `AdmissionEvidence.copyOf(Map<BlueId, ?>)` applies one bound to the
complete set. `asMap()` exposes immutable typed-key record data. Convert BlueId keys to their text
values for a JSON transport; do not infer package aliases from them.

The standard's explicit receiving operation has a different invocation boundary from rooted
processing. Both owner hosts use rooted `process` to preserve whole-document gas, rollback and
ordered events. They must not substitute an independently received child merely because its final
business value matches.
