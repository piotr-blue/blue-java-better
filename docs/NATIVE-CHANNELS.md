# Explicit native channels

`NativeChannel` lets a host supply a deterministic external-event acceptance rule to canonical
`Contracts`. Accepted events still pass through the normal checkpoint,
operation dispatch and workflow machinery. The handler cannot patch state or emit events.
A Java registration is explicit host authority; loading a package never installs executable code.

For the exact published Coordination channel profile, use
[TimelineChannels](TIMELINE-CHANNELS.md). Its mandatory host verifier and published `accept: 1`
tariff differ from the generic callback tariff described here.

## Define the type and its behavior

```java
Language language = Language.newBuilder()
    .mount("Contracts", Contracts.publication())
    .build();
LanguageDocument channelType = language.defineYaml("""
    type: Contracts/Channel
    kind: {type: Text}
    """);
LanguageDocument semantics = language.readYaml("""
    description: Accept events whose kind equals the channel's kind, edition 1.
    """);
NativeChannel channel = NativeChannel.newBuilder(channelType, semantics, "example")
    .headerFields("kind")
    .accept(context -> Objects.equals(context.event("/kind"), context.header().get("kind")))
    .build();
Language application = Language.newBuilder()
    .type("Example/Kind Channel", channelType)
    .build();
Contracts contracts = Contracts.newBuilder()
    .language(application)
    .nativeChannel(channel)
    .build();
```

The alias is external to the type's own metadata. The small semantics document is illustrative;
a published native protocol should identify its full normative behavior. An identity records the
host's promise, not proof that arbitrary Java fulfills that promise.

## Process an event

```java
try (Contracts.Session session = contracts.openYaml("""
    count: 0
    contracts:
      input: {type: Example/Kind Channel, kind: Accepted}
      onEvent:
        type: Contracts/Sequential Workflow
        channel: input
        steps:
          - type: Contracts/Update Document
            changeset:
              - {op: replace, path: /count, val: {$add: [{$document: /count}, 1]}}
          - type: Contracts/Trigger Event
            event: {kind: Counted}
    """)) {
    session.initialize().requireApplied();
    Map<String, Object> event = Map.of("kind", "Accepted", "sequence", 1);
    Contracts.Transition result = session.process(event, 7).requireApplied();
    System.out.println(session.read("/count")); // 1
    System.out.println(result.events());        // [{kind=Counted}]
    session.process(event, 7);                 // duplicate; count remains 1
    session.process(Map.of("kind", "Ignored", "sequence", 2), 8);
    System.out.println(session.read("/count")); // still 1
}
```

An accepted event may also contain a normal `Contracts/Operation Request` under `operation`.
The normal dispatcher validates the channel and operation before executing it. A channel acceptance
callback runs before checkpoint duplicate detection, so it must remain deterministic and free of
external effects even for repeated inputs. Different canonical calls need different event content;
the host separately deduplicates its durable command identifiers.

The host still supplies authentication, durable ordering, atomic publication and parent propagation.
This acceptance callback is not a Timeline service or timer scheduler. Install an explicit
[admission verifier](NATIVE-ADMISSION.md) when acceptance requires snapshot-bound host evidence.

## Callback boundaries

- `header()` and `event()` return detached immutable Java maps, lists and scalar values. Integers
  are `BigInteger`; null and `Bex.undefined()` remain distinct. Repeated access to the same input
  reuses its snapshot for that callback. No internal lazy runtime view escapes.
- `event("/request/amount")` selects only that field, without projecting unrelated payloads.
  Pointers follow RFC 6901; empty selects the root, `~1` escapes `/`, and `~0` escapes `~`. Missing
  fields and unavailable list positions return `Bex.undefined()`. Malformed pointers abort the
  invocation with `INVALID_POINTER`. Native steps offer the same overload.
- Each materialized value uses `ProjectionLimits.defaults()`: 262,144 values/entries, 4,194,304 UTF-16
  code units and depth 256. Limits are checked while expanding, including compact shared references.
  Exceeding a limit raises `PROJECTION_LIMIT_EXCEEDED` without publishing state or events. This bounds
  returned structure, not all verifier/provider/Java-handler memory. Native steps use the same boundary.
- `checkpoint()` returns the last consumed event identity and logical timestamp, or empty. Channel
  name, generation and input document identity are explicit. No wall-clock time is supplied.
- `.peerFields("peers")` permits lookup of names in that header field's **list**. Declare it with
  `.headerFields(...)` too. `context.peer(name)` distinguishes absent declarations, non-channels and
  channel headers; it does not expose a mutable peer or unrestricted document access.
- The invocation tariff is at least one unit. Declare additional counters with `.counter(name, cost)`
  and charge them with `context.charge(name, occurrences)`. Counters share the execution allowance.
  Trusted Java CPU work is not preempted automatically; handlers must account for their work.
- Handlers must be deterministic and thread-safe. Every context method rejects use after return or
  from another thread; detached values may be retained. Callback exceptions and exhausted gas abort
  the invocation. Previously committed state is unchanged.
- Bindings are exact by default. `.inherited(true)` explicitly accepts subtypes. Snapshots bind type,
  semantics and tariff; missing or different registrations cannot restore them. Builder mutations do
  not change existing registrations. One type cannot be registered as both a native channel and step.

`blue.contracts.NativeChannelTest` owns acceptance/rejection, deduplication, restoration, inherited
bindings, operation envelopes, peers, lifetime, atomic failure and bounded shared-event expansion.
`NativeStepTest` also checks the shared public-value boundary, including values returned by BEX.

Canonical `Contracts.Session.compact()` rebuilds through the original Contracts configuration.
Installed native registrations, their semantics identities and channel checkpoints are retained;
compaction does not silently replace the execution environment.
