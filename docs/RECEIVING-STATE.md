# Preparing native input from the actual receiving state

A host must determine which sources an external input depends on before executing it. Reading raw
YAML is insufficient: declarations may be inherited, composite channels have primitive members,
and a raw workflow may observe an operation addressed to another document.

`Contracts.Session.reception()` uses the Contracts registry and channel-composition rules
that execution itself uses. It runs neither initialization, native verifiers nor business workflows.
The returned immutable data describes a snapshot; it does not grant authority.

```java
Contracts.Reception reception = session.reception();
String snapshot = reception.snapshotIdentity();

for (Contracts.ReceivingChannel channel : reception.channels()) {
    BlueId binding = channel.typeId();
    NativeAdmission.Receiver receiver = channel.receiver();
    String name = receiver.channel();
    long generation = receiver.generation();
    Set<String> selectors = channel.selectors();
    boolean hasRawObserver = channel.observed();
}
```

Only active external native channels with admission verifiers appear. A terminated document returns
an empty list. `selectors()` contains the primitive channel's own name and each composite/all-channel
name containing it. `observed()` describes raw workflows and native handlers, excluding workflow
operations. Each receiver includes its exact declaration, input document identity and prior checkpoint.

For identity-bearing headers, use `headerIdentities()`. These identify **supplied** values, including
values inherited from an authored definition. A published field's contextual type and description
must not accidentally become part of the provider, timeline or actor identity. Ordinary interpreted
header data remains in `receiver.header()`.

```java
BlueId provider = channel.headerIdentities().get("provider");
String url = (String) channel.receiver().header().get("url");
```

The exact installed binding determines which headers exist and how to interpret them. A generic
host must not assume every native channel is a Timeline channel merely because it has similar fields.

Operation recognition also reuses Contracts grammar:

```java
Optional<Contracts.OperationRequest> request = session.operationRequest(originalMessage);
if (request.isPresent()) {
    Contracts.OperationRequest operation = request.orElseThrow();
    String selectedChannel = operation.channel();
    Optional<BlueId> target = operation.document();
    boolean exactVersion = operation.requireExactDocumentVersion();
}
```

Pass the original message with its immutable content dependencies/provider. Reads of lazily supplied
content are scoped to this inspection. Missing content fails explicitly. The method does not turn
an arbitrary object into a request, validate target history, authorize the operation, or run it.

A host still must freeze verified source coverage, establish target lineage and access, then fence
the receiving snapshot/version when committing execution. Native callbacks must see the same exact
receivers used during preparation. Inspection data copied or deserialized by a client is not a permit.

`NativeReceptionTest` owns supplied identities, inherited declarations, composite routing, raw
observers, immutability, callback agreement, restoration, termination and request recognition.
The MyOS Better `TimelineIngressTest` owns real signed source integration and durable publication.
