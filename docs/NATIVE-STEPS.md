# Explicit native steps

`NativeStep` extends canonical `Contracts`. Most business rules should remain ordinary BEX in
packages. A native
step is useful when a host deliberately supplies behavior that the BEX language cannot express.
A package import supplies data and identities; it never authorizes executable Java.

## Define and install one step

```java
Language language = Language.newBuilder()
    .mount("Contracts", Contracts.publication())
    .build();
LanguageDocument stepType = language.defineYaml("""
    type: Contracts/Contract
    expression: {}
    """);
LanguageDocument semantics = language.readYaml("""
    description: Evaluate the read-only expression and replace count, edition 1.
    """);
NativeStep increment = NativeStep.newBuilder(stepType, semantics, "example")
    .bodyFields("expression")
    .counter("work", 3)
    .execute(context -> {
        context.charge("work", 2);
        Object amount = context.evaluate(context.body().get("expression"));
        context.replace("/count", amount);
        context.emit(Map.of("kind", "Incremented", "amount", amount));
    })
    .build();
Language application = Language.newBuilder()
    .type("Example/Increment", stepType)
    .build();
Contracts contracts = Contracts.newBuilder()
    .language(application)
    .nativeStep(increment)
    .build();
```

The example semantics document is a deliberately small demonstration identity. A real native
protocol should publish its complete normative behavior and use that exact document. Registering an
identity is the host's explicit promise; the library cannot prove arbitrary Java honors it.
The `Example/Increment` alias belongs to the application mount, not to the document's `name`.

```java
try (Contracts.Session counter = contracts.openYaml("""
    count: 0
    contracts:
      input: {type: Contracts/External Channel}
      increment:
        type: Contracts/Sequential Workflow Operation
        channel: input
        steps:
          - type: Example/Increment
            expression: {$add: [{$document: /count}, 1]}
    """)) {
    counter.initialize().requireApplied();
    Contracts.Transition result = counter.call("input", "increment", Map.of(), 1).requireApplied();
    System.out.println(counter.read("/count"));  // 1
    System.out.println(result.events());        // [{kind=Incremented, amount=1}]
}
```

A later distinct external call needs distinct event content, for example a request sequence field.
Canonical event identity cannot be replayed at a different timestamp. The host's command identifier
and the Blue event identity serve different purposes.

## Same registration in the durable host boundary

```java
// Reuse the same Contracts configuration, including its native registrations.
try (Contracts.Execution execution = Contracts.Execution.using(contracts, completions)) {
    Contracts.Execution.State before = execution.restore(checkpoint);
    Contracts.Execution.Outcome outcome =
        execution.process(before, original, timestamp, admissionEvidence);
    // Persist the outcome and input progress atomically through the host's storage boundary.
}
```

`completions`, `checkpoint`, `original`, `timestamp` and `admissionEvidence` come from the
host's protected storage and selected input. The host owns atomic publication, authentication,
deduplication, ordering and parent delivery.
`context.read(pointer)` goes through the same logical/pinned host view as BEX; it cannot accidentally
read an empty detached placeholder instead of an initialized child. Projection limits apply before
calling the host reader, and a host resource refusal retains its original failure category.
`context.evaluate(expression)` is read-only BEX with the current workflow's bindings and gas budget.
`context.event("/request/amount")` selects a bounded immutable event value without expanding other
fields. Its pointers follow RFC 6901; an absent value returns `Bex.undefined()`.

## Contract of the callback

- `headerFields` are admitted eagerly; `bodyFields` are resolved when executing the step. Standard
  metadata and `type` are not custom fields. The two declared field sets must be disjoint.
- Inputs and read results are detached immutable Java values; no internal lazy view escapes.
  Materialization uses `ProjectionLimits.defaults()` and fails atomically before oversized output
  is built. Repeated input access reuses the callback snapshot. Effects copy caller data immediately. Patches are applied
  after the callback returns and before the next workflow step; reads within the callback observe
  its input state. Ordinary event listeners run as part of the enclosing atomic invocation.
- `add`, `replace`, `remove`, `emit`, `terminate` and `reject` record or reject document effects.
  `reject(reason)` produces `NATIVE_STEP_REJECTED`; an unexpected Java runtime exception produces
  `RUNTIME_FAILURE`. Neither publishes partial effects.
- Every callback charges the declared `invocation` counter, one unit by default and always positive.
  Other counters are explicit. BEX evaluation charges its canonical tariff against the same budget.
  `remainingGas()` includes intervening BEX charges. This is deterministic accounting, not a sandbox
  or an enforced CPU timeout for arbitrary trusted Java.
- Every context method rejects use from another thread or after the callback. Closing releases its
  references to the processor and host callbacks. Handlers must be thread-safe because independent
  sessions may share one registration.
- Subtypes require `.inherited(true)`; the default is exact type only. Snapshots record the exact type,
  semantics, fields and tariff. Restoring without matching installed bindings fails. Building a new
  registration from a mutated builder never changes an existing runtime.

The public API also supplies [native external channels](NATIVE-CHANNELS.md). Admission verifiers,
timeline ordering, a timer scheduler and the full Coordination/Mandates services remain separate work. Adding
a step does not make `require(package)` accept an otherwise unsupported protocol or silently turn a
native data publication into an installed service.

`blue.contracts.NativeStepTest` covers execution with BEX, emitted listeners, rollback, rejection,
shared gas, immutable effects, callback lifetime, host reads/output bounds, inheritance opt-in,
builder snapshots and exact checkpoint binding. Tests use only the public API.

Canonical `Contracts.Session.compact()` rebuilds through the original Contracts configuration.
Installed native registrations, their semantics identities and channel checkpoints are retained;
compaction does not silently replace the execution environment.
