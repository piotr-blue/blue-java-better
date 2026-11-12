> Historical experiment, retired 2026-11-08. This is not a supported API guide.

# Local-1 experiment — historical API guide

This is the earlier `Blue` / `Program` / `Coordinator` experiment. Its identities, execution profiles,
gas and timings are not canonical Contracts results. It remains available for existing experiments;
new Blue applications should start with [the current README](../../../README.md), canonical `Language`,
`Bex`, `Contracts`, or the explicitly separate `DocumentRuntime` host protocol.

## Build and run the Local-1 examples

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
./gradlew build
./gradlew example
./gradlew profileExample
./gradlew languageExample
./gradlew tutorialExample
./gradlew packageExample
```

The example creates 128 orders, 12 parent clauses, three audit clauses per order and two nested
fulfillment documents per order: **385 managed documents**. It initializes them, submits an
order, reacts in the parent, runs workflow parameters and adds another order. States and events
are exported under `build/reports/example/`.

```java
Blue blue = Blue.newBuilder()
    .packages(PackageRepository.open(
        Path.of("examples/packages/commerce/package.yaml")))
    .build();

Node document = blue.readYaml(Path.of("examples/agreement.blue"));
ContractState state = blue.initialize(document).requireApplied().state();

Node order = blue.readYaml(Path.of("examples/order.blue"));
Node request = Node.object(Map.of("key", Node.text("first"), "order", order));
state = blue.call(state, "addOrder", request).requireApplied().state();

ProcessingResult result = blue.call(state, "/orders/first", "submit", Node.object());
state = result.requireApplied().state();
System.out.println(state.document().at("/submittedOrders").integer());
System.out.println(state.revision());
System.out.println(result.events());
System.out.println(blue.writeYaml(state.document()));
```

Keep `ContractState` between calls. Exporting YAML is an inspection boundary, not a substitute
for preserving admitted runtime state. `Blue` and immutable values can be shared across threads;
`Coordinator` owns and serializes live root updates when that is useful to the application.
Different roots can progress independently; a root and its embedded documents share one atomic
transaction scope. Store the returned snapshot after success. Persistence and durable event
delivery belong to the host; returning events does not itself deliver them outside the engine.

## Exact execution environment

`ExecutionProfile.local()` selects the experimental core editions shipped here. Executable YAML
must declare those IDs in `contracts`; the checked-in examples already do. For programmatic
authoring, `ExecutionProfile.local().applyTo(source)` explicitly adds the header. Initializing an
unprofiled contracts block fails. This is not an implicit default or a claim of canonical parity.

A profile inherited from a type is sufficient. Clauses and BEX packages can declare additional
requirements; unavailable or undeclared native features fail before effects are published.
`Blue.newBuilder().capability(implementation)` installs an exact native capability edition.
Multiple native editions can coexist, selected per document. See the runnable examples and
[design/usage guide](EXECUTION-PROFILES.md).

## Read the implementation

- `Blue`: configuration and the public facade.
- `BluePackage`, `PackageLoader`: verified canonical publications and bounded local loading.
- `Language`, `LanguageDocument`: scoped canonical definitions, values and retained evidence.
- `Node`, `Pointer`: immutable values and persistent edits.
- `BexCompiler`, `Program`, `Frame`: compile once; each invocation owns its execution state.
- `ExecutionProfile`, `ExecutionRequirements`: exact semantics and additive code requirements.
- `NativeCapability`, `StepContext`: registered native workflow steps and metered effects.
- `DocumentCompiler`: validates profiles and definitions, then compiles workflows at admission.
- `DocumentPlan`: immutable subscription indexes and compiled workflow metadata.
- `Execution`: atomic scheduling, patches, events and clause changes.
- `Memo`: fixed-size, thread-safe reuse of recomputable artifacts; no background maintenance.
- `Coordinator`: in-memory transaction owner.

There are no Java handlers specific to orders, ledgers or agreements. See `examples/` and
`examples/packages/commerce/`. Demo and benchmark code is not shipped inside the library JAR.

## Measure

```bash
./gradlew benchmark -PbenchmarkArgs='-f 1 -wi 2 -i 3 -w 500ms -r 500ms -prof gc -jvmArgs -Xmx1g'
```

JMH measures continuing submits and isolated additions at 1, 128, 1,000 and 10,000 orders.
It measures execution and result construction, including admission of the added order. Initial
admission of the pre-existing tree, YAML export and explicit canonical BlueId calculation are excluded. Execution creates no history hash. Short measurements are diagnostic, not production latency SLOs.

The old engine is an optional measurement dependency only:

```bash
# In the existing blue-java checkout:
./gradlew :benchmarks:embeddedGrowthClasspath

# Back here:
./gradlew compare -PbaselineClasspath=/absolute/path/blue-java/build/embedded-growth-classpath.txt
```

The comparison checks shared BEX outputs and basic Language identities. Its counter comparison
has the same business effect but different protocol guarantees; it is not full Coordination
parity. No sibling checkout is needed by `build`, `test`, `example` or `benchmark`.

