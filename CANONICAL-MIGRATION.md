# One canonical execution engine

This iteration removes the Local-1 interpreter and the detached `DocumentRuntime` interpreter.
Use `Language` for content, `Bex` for expression evaluation and `Contracts` for execution. All
contract processing uses the installed published Contracts rules, reported by `Contracts.rulesId()`.
Package aliases remain mappings to exact identities. No `Core/*` or `Local/*` type is introduced.

## Direct processing

```java
Contracts contracts = Contracts.create();
try (Contracts.Session document = contracts.openYaml(source)) {
  document.initialize().requireApplied();
  Contracts.Transition result = document.call("input", "submit", Map.of(), timestamp);
  if (result.applied()) {
    System.out.println(document.document().canonicalJson());
  }
  System.out.println(result.gas());
  System.out.println(result.events());
  System.out.println(result.receipt());
}
```

Use `process(original, timestamp, evidence)` for a selected original input. Hosts must establish
source completeness and ordering before processing. `call` creates an ordinary Contracts Operation
Request; it does not invent an input identity from a database command identifier.

## Durable execution

`ContractOwners` was a Java adapter, not a Blue document or type. Its functionality is now nested
under `Contracts.Execution`. The local scheduler and AWS owner scheduler use it. It invokes the
same canonical processor as `Contracts.Session`; a host can create and close it for every attempt.

```java
try (Contracts.Execution execution = Contracts.Execution.using(contracts, completions)) {
  Contracts.Execution.State before = execution.restore(checkpoint);
  Contracts.Execution.Outcome outcome =
      execution.process(before, original, timestamp, admissionEvidence);
  // Atomically persist outcome, input progress and any resulting scheduling obligations.
}
```

Optional validation, preparation and calculation readers accept only records previously issued
by this engine and committed in protected storage. They are not arbitrary assertions from clients.
They change physical work, not logical gas, the resulting document or emitted events.
`Execution.receive` remains the explicitly distinct receiving operation defined by the standard;
it must not substitute for rooted `process`. Both supplied owner schedulers use rooted processing.
The gas-boundary regression test demonstrates why this distinction matters.

## Removed APIs and data

Removed: `Blue`, `Coordinator`, `ContractState`, `ExecutionProfile`, the old `Program`/`Evaluation`
engine, `PackageRepository`, `DocumentRuntime` and its detached delivery/host-read overlay protocol.
Use `Bex.Program`, `PackageLoader` and canonical `Contracts` instead. Native channel/operation
inspection records now belong to `Contracts`.

There is no automatic conversion of Local-1 or `blue.document-local/*` checkpoints. Their gas,
input identity, initialization and rollback can differ. Replay the original document and inputs
into a fresh canonical history. Old receipts cannot honestly be renamed. No deployed data is
rewritten by these source changes.

Direct portable snapshots require complete retained evidence. An opaque verified timeline entry
can save repeated semantic validation, but does not fabricate its missing predecessor bodies.
Durable execution can use protected retained records; portable export must include the required
closure or use an explicitly supported external-content boundary.

## Failure policy

A deterministic `FAILED` or `GAS_LIMIT_EXCEEDED` result rolls back that rooted invocation and emits
no tentative events. The local scheduler now records that completed outcome and advances the input
cursor, as the AWS owner scheduler does. A later valid input can proceed. `NEEDS_RESOURCES` and host
failures remain pending; they are not silently consumed. Independent child publications remain
independent of a parent's failed rooted invocation.

## Verification and limits

Current test ownership is documented in `docs/TESTING.md`. Retired experimental tests are not
canonical conformance evidence. The canonical suite checks pinned observations and receipts,
initialization, replay, cycles, gas-limit rollback, references, lazy preparation and native authority.
The local suite compares independently scheduled execution with the canonical whole-document
oracle, including failures and the exact ordered event list.

This is a source/API consolidation, not a claim that the entire Coordination protocol or every
historical `blue-java` application is supported. Nor does it claim constant AWS latency with arbitrary
graph size: loading snapshot metadata, publication, and exact rooted accounting still have costs.
Measured performance and remaining work belong in the iteration report, separately from semantics.

## Canonical identities and durable owners

A direct comparison with the original Java implementation found that the newer library had
reset allocation identity to each local turn. This has been corrected: new native member keys
use the sealed invocation context and allocation counter defined by the published Contracts
rules. The checked-in compatibility receipts cover 1, 10 and 20 dynamically added Orders and
nested Payment processing; the optional comparison tool runs both Java implementations.

A host must not use those invocation-relative keys as a universal database identity. Both hosts
retain a private mapping for a child's creation occurrence (creator owner, attachment path,
generation and original input) to the existing durable owner. That mapping does not rewrite
native snapshots, document values, gas, emissions or receipts. It prevents parent replay from
creating a second database owner for the same child. It is not a Blue type or a new interpreter.

Previously recorded experimental histories need explicit replay. In particular, earlier
`normalized-rooted/1` local directories and `blue.independent-owners/rooted-1` host records were
created before this correction. New directories/publications are tagged with new private storage
formats so that they cannot silently reinterpret earlier results.
