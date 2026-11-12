# Reusing a completed value during admission

A small request can refer to a very large document. Ordinary strict admission follows that reference
and checks its complete definition. If the application already holds a completed immutable value
issued by Language or Contracts, it can explicitly provide that value for the current admission:

```java
Language language = Language.newBuilder()
    .mount("Contracts", Contracts.publication())
    .build();
LanguageDocument target = session.document();
Language.Input request = Language.Input.yaml("""
    type: Contracts/Operation Request
    document: {blueId: '%s'}
    channel: buyer
    operation: submit
    request: {quantity: 2}
    """.formatted(target.blueId()));
LanguageDocument message = language.read(request, Language.Goal.DEFINITION, target);
```

The same overload accepts a parsed `Node`, preserving exact integers. Input roles are unchanged:
Source performs ordinary duplicate omission, and Exact retains its exact representation. The result
has the same canonical identity and content as ordinary admission with the target's complete evidence.

Only completed `LanguageDocument` values qualify. A prepared `LanguageContent`, an arbitrary BlueId,
a raw provider response, or a document admitted only as a definition cannot assert completion. A
changed target identity needs its own evidence. New request fields and additional contextual type or
schema restrictions still undergo normal validation. Physical work limits apply to the work performed
by this call; reusing an established subtree deliberately avoids charging its validation again. This
is host admission work, not a change to Contracts' deterministic gas.

The evidence is invocation-local. It does not modify the reusable `Language`, add a cache, establish
membership, grant an operation, prove Timeline history, or substitute for a signature. The returned
handle retains portable supporting content. Serializing it does not serialize permission to omit
verification. Cyclic values keep their ordinary owning-set proof obligations when exported or reloaded.
A presentation whose exact and semantic identities differ remains usable as supporting content but
cannot reuse a completed-value header for a different identity.

Hosts needing recovery across process restarts can explicitly use a
[protected admission checkpoint](ADMISSION-CHECKPOINTS.md). This is a separate protected-storage
boundary; ordinary serialized content still carries no permission to omit semantic verification.

The MyOS adapter supplies an already retained current target only after account-scoped document
lookup. A cold operation instead borrows the pinned publication's record provider and admits the
actual request dependencies; obtaining optional reusable evidence does not itself restore or admit
the current whole graph. It continues to check the operation target and current publication
conditions before appending the signed entry. A current target with a large dependency history
still needs that history checked when no completed-value evidence is available.
Repeated submission previously validated that target's entire closure before reaching the Timeline
publisher, even though the publisher already supported equivalent completed-value headers.

`blue.language.AdmissionEvidenceTest` owns bounded-work, Source/Exact/Node equivalence, restrictions,
changed identities, missing evidence, cyclic portability and native-value lifetime checks. Host ingress
checks remain in `CoreContentsTest`. The separate MyOS `OperationAdmissionProbe` compares ordinary
and reused admission against identical current native member versions; it measures preparation only,
not execution, publication, HTTP or AWS latency.

`contracts.operationRequest(message)` recognizes the same exact operation grammar as
`session.operationRequest(message)` without opening an execution session. Both certify the
document/request values. An unavailable or forged target is still rejected. The returned address
and channel are descriptions: the host must independently check account access, committed lineage,
channel authority, original occurrence and conditional publication.
