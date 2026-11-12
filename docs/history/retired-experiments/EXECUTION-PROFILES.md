> Historical experiment, retired 2026-11-08. This is not a supported API guide.

# Exact execution profiles

The design is right: executable content should select its semantics, and the processor should
refuse unsupported requirements. A package's content identity pins its data and code; it does
not, on its own, select the native interpretation of that code.

This implementation adds exact profiles to the experimental Java engine. It does not change the
canonical packages, Python, or the previous Java implementation.

## Rules

An executable `contracts` block must contain a complete profile. A profile inherited through a
node's type is sufficient. An enclosing parent is not a type: an embedded child does not silently
borrow the parent's profile. Missing declarations do not fall back to the latest runtime.

```yaml
contracts:
  type: Coordination/Execution
  language:
    blueId: B8qQWuqNEWwPdYHuaMdKBNEr8xpsB8KeJCa4M2EeTezq
  bex:
    blueId: J13749eVM7Z6NgDhb6JY2d6RyyofijcdXDEiELkuj73f
  contract:
    blueId: 9eAmCke6PnSwPp5YjUuLLYeXvCFh86JNEKmbTQaKY2s
  coordination:
    blueId: 47mAMfUrnx4Wz6EPMbMkvqtkPB8uBFCuozUZjwH7sGag
  capabilities: []

  input:
    type: Local/Operation Channel
  increment:
    type: Contracts/Sequential Workflow Operation
    channel: input
    steps:
      - type: Contracts/Compute
        do:
          - $appendChange:
              op: replace
              path: /count
              val: {$add: [{$document: /count}, 1]}
```

These are real content identities of the experimental declarations in
`src/main/resources/blue/semantics/`. They are not canonical Language/BEX/Contracts/Coordination
release IDs and are not proofs of implementation conformance. The engine can resolve their
content. The full runnable document is `examples/profiles/counter.blue`.

The four components are required. Capabilities are optional (omission means an empty set);
each listed capability must be installed. Entries are exact `{blueId: ...}` references, not
names, version ranges, URLs or claims that an engine should download and trust arbitrary code.
The header keys are reserved; other entries in `contracts` remain named channels and workflows.
The old `runtime: Blue/Local 1` marker is optional and never substitutes for a profile.

Validation occurs when definitions become executable: document admission, clause installation,
and code compilation/use. Parsing or storing arbitrary data does not execute it. Inert data may
contain quoted document templates; the engine does not recursively execute or profile-check such
payloads merely because a field happens to be named `contracts`.

## Java authoring and execution

```java
Blue blue = Blue.create();
Node source = blue.readYaml(Path.of("examples/profiles/counter.blue"));
ContractState state = blue.initialize(source).requireApplied().state();
ProcessingResult result = blue.call(state, "increment", Node.object()).requireApplied();

System.out.println(result.state().document().get("count").integer()); // 1
System.out.println(state.profile());
```

For programmatic authoring, use `ExecutionProfile.local().applyTo(source)`. This explicitly
returns a copy with the selected header and preserves its workflow entries. `initialize` never
invents the declaration. Calling `applyTo` deliberately changes authored content, so it is not
an operation for upgrading an admitted session.

`blue.supportedProfile()` describes engine availability. It does not automatically grant every
installed feature to every document. `state.profile(path)` reads the profile retained for an
admitted child without re-resolving it.

## Inheritance

The example package exports `Examples/Counter`, whose definition contains the complete profile.
The derived document contains only:

```yaml
type: Examples/Counter
name: My inherited counter
```

```java
PackageRepository repository = PackageRepository.open(
    Path.of("examples/profiles/package.yaml"));
Blue blue = Blue.newBuilder().packages(repository).build();
Node source = blue.readYaml(Path.of("examples/profiles/derived-counter.blue"));
ContractState state = blue.initialize(source).requireApplied().state();
blue.call(state, "increment", Node.object()).requireApplied();
```

The header itself may also be a reusable type: `contracts: {type: Example/Execution}`
works when that package export inherits `Coordination/Execution` and supplies its editions.
Inline header definitions and exact references work as well.

Inherited component requirements cannot be weakened or silently rebound. Capability requirements
are additive through inheritance. Replacing a core edition is a new executable definition or
explicit migration, not a refinement of an existing fixed edition.

## Clauses, code packages, and data packages

| Content | Requirement |
| --- | --- |
| An admitted document with contracts | Complete profile, directly or inherited |
| A reusable clause with contracts | Complete profile; core tuple must match the owner and its capabilities must be declared by the owner |
| BEX code or a non-executable type | Optional partial `executionRequires` |
| A plain data catalogue | No execution declaration required |
| A package manifest | Optional `executionRequires`, materialized into its exports |

A clause's declared capabilities also limit its own code. Merely placing it in an owner with
more features does not grant those features to an undeclared clause. Embedded children have
their own scopes and may select different installed native editions.

Code that depends only on a BEX edition can say:

```yaml
executionRequires:
  bex:
    blueId: J13749eVM7Z6NgDhb6JY2d6RyyofijcdXDEiELkuj73f
expr: {$add: [20, 22]}
```

Requirements on functions are included when compiling their containing program. Checks also
apply on cached-program use and when moving a compiled program to a different engine. A missing
component in a partial requirement means it imposes no constraint; it does not select a default.

A source package manifest can carry the same `executionRequires` object. The local package loader
merges it into object-valued exports **before calculating their BlueIds**. The exported bytes
therefore include the requirements. Conflicting inherited requirements fail. A scalar data export
cannot carry an execution requirement; use a definition object or leave the data package unbound.
This is the experiment's `blue-local-package/1` source format, not canonical `.bluepkg` support.

## Installing native features

The first extension point is a native workflow step. The host registers its implementation under
a capability semantics ID and exact step-type IDs:

```java
NativeCapability increment = NativeCapability.newBuilder(capabilityId)
    .step(stepTypeId, definition -> context -> {
      context.charge(2); // Native work must have an explicit deterministic tariff.
      long current = context.read("/count").integer();
      context.change(new Change("replace", Pointer.parse("/count"),
          Node.integer(Math.addExact(current, 1))));
      context.emit(Node.object(Map.of("kind", Node.text("Counter Incremented"))));
    })
    .build();

Blue blue = Blue.newBuilder()
    .alias("Example/Increment", stepTypeId)
    .capability(increment)
    .build();

ExecutionProfile profile = ExecutionProfile.local().withCapability(increment.id());
Node executable = profile.applyTo(source);
```

`capabilityId` identifies the feature's semantics; `stepTypeId` identifies its source syntax.
Both are actual BlueIds chosen from published definitions, not arbitrary Java class names.
The complete example computes them from inspectable definitions and handles integer overflow
with a stable Blue failure code: `src/demo/java/blue/examples/ProfileExample.java`.

Compilers validate immutable source once at admission. Instructions use invocation-local
StepContext objects. Reads, patches and events use ordinary gas and atomic publication. Native
code must account for its remaining work. There is no wall-clock, network or persistence API in
StepContext. Java callbacks are trusted host code, however, not sandboxed code: the host must
ensure determinism, thread-safety and the absence of untracked external effects.

Two registered capability editions may implement the same step type. Each document's declaration
selects one. Declaring both for that type is rejected as ambiguous. Duplicate capability IDs and
attempts to override core native types are rejected at registration. This avoids install-order
semantics. The SPI does not yet add BEX opcodes, channels, timers, or an external scheduler.

## Working version-selection example

```bash
./gradlew profileExample
```

Verified output:

```text
v1: count=1; gas=28; profile retained=true
v2: count=10; gas=28; profile retained=true
Without implementation: UNSUPPORTED_CAPABILITY
Without declaration: UNDECLARED_CAPABILITY
Inherited profile: true
```

Both versions run in the same engine. The example writes their source, resulting states, events,
capability declarations, and an inherited example into `build/reports/profiles/`.

## Failure and locality

Unknown editions, malformed references, unavailable capabilities and incompatible clause/code
requirements reject admission. Failures during adding a child or amending clauses roll back the
whole transaction. No effects are published. An admitted document cannot patch its profile or
its `executionRequires`; changing its semantic environment needs explicit re-admission/migration.

Validated immutable headers are memoized per engine. The selected profile and native instructions
are retained in the document plan. Processing one child does not traverse sibling profiles or
rediscover its capabilities. Clearing caches changes physical work, never permission or logical
gas. Profile parsing/compilation is admission work and is not included in this experimental gas
model; hostile-input admission metering remains work.

## Versions, proofs, and limits

Supporting old versions means retaining their implementations and selecting them by exact IDs.
It does not mean interpreting an unknown old ID using today's code. Multiple core editions must
be matched as supported **tuples**, not an arbitrary Cartesian product of independently familiar
Language/BEX/Contracts versions. This release implements one experimental core tuple. It rejects
other tuples and demonstrates simultaneous native capability versions; it does not implement all
historical core semantics.

The profile format also needs a stable bootstrap interpretation. This experiment uses its existing
acyclic parser/resolver before checking the tuple. A future multi-Language processor needs an
explicit common envelope and identity/lookup rules, rather than guessing the Language version
needed to read its own version selector. In canonical packaging, keep that envelope in a shared
bootstrap layer; the `Coordination/Execution` name should not create a Contracts↔Coordination
package dependency cycle.

Profiles identify required semantics, not correct execution. In RISC Zero, a receipt is verified
against an expected program image and its public journal. See the
[receipt verification contract](https://docs.rs/risc0-zkvm/latest/risc0_zkvm/struct.Receipt.html)
and [proof-system overview](https://dev.risczero.com/proof-system/).

A future Blue proof statement should bind the exact execution profile/capability definitions,
approved verifier program image, initial state identity, input identity, deterministic budgets
and policies, and resulting state/effects. Capability implementation conformance also needs
verification; a host registration alone does not establish it. Timers should consume authenticated
time inputs, not read a local wall clock during proven execution. No RISC Zero integration or
proof production is claimed here. Its public-output journal is separate from a mandatory runtime
history log; no runtime journal has been reintroduced.


## Verification and timing

The delivered project passes **91 tests, zero failures/errors/skips**. The build also checks
formatting, strict compiler warnings, complete Javadoc and benchmark compilation. Tests include
missing/malformed/unsupported profiles, native version isolation, inherited headers, additive
requirements, clause capability boundaries, function requirements after cache hits, protected
profiles, child admission rollback, clause amendment rollback, and native-effect budget rollback.

The regular example still initializes 385 managed documents and executes a submit in the selected
order and its parent: 17 workflows, 17 events, gas 706. Adding an order and its two children uses
gas 219. These logical charges are unchanged; profile compilation remains admission overhead.

Bounded JMH p50 measurements, in microseconds:

| Existing orders | Submit before | Submit with profiles | Add before | Add with profiles |
| ---: | ---: | ---: | ---: | ---: |
| 1 | 22.14 | 21.34 | 13.33 | 15.66 |
| 128 | 25.86 | 24.16 | 14.21 | 17.28 |
| 10,000 | 27.90 | 26.94 | 15.41 | 18.43 |

One fork, 2 × 500 ms warmup, 3 × 500 ms measurement, 1 GiB heap, Apple M3 Max/JDK 25.0.3.
These are diagnostic samples, not latency guarantees or statistically established speedups.
Before means the preceding polished experiment, not the old full-protocol Java engine. Initial
setup of the existing tree, serialization, explicit identity calculation and external I/O are
excluded; adding includes admission of the new order and its two nested documents.

Normal calls stay in the previous range. Additions pay roughly 2–3 µs more for the stricter
admission path. Larger persistent maps and memory locality still have a modest cost; this is
not a claim of literally constant physical latency. No sibling-wide profile validation occurs
on ordinary calls.

Compact measurements and source fingerprints are in
[execution-profiles.json](../../measurements/execution-profiles.json) and
[execution-profile-sources.json](../../measurements/execution-profile-sources.json).
Reproduce the bounded run with:

```bash
./gradlew benchmark -PbenchmarkFilter=LocalityBenchmark \
  -PbenchmarkArgs='-p orders=1,128,10000 -p complex=true -f 1 -wi 2 -i 3 -w 500ms -r 500ms -prof gc -jvmArgs -Xmx1g'
```
