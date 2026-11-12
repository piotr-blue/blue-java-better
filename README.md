# Blue Java Better

[Architecture and design decisions](docs/ARCHITECTURE.md) · [10 November qualification report](docs/polish-2026-11-10/REPORT.md)

A Java 25 library for canonical Blue Language, package loading, BEX and Contracts. Immutable
Language values retain their supporting evidence; execution sessions own their mutable state.
Caffeine is a design reference. There is no Caffeine dependency, Python runtime dependency, or
dependency on the original Java engine. Business behavior stays in Blue/BEX.

This remains an experiment under qualification. The [capability boundaries](docs/CAPABILITY-GAPS.md)
and [current campaign and iteration decisions](docs/CANONICAL-CAMPAIGN-2026-11-08.md) distinguish implemented behavior, measured
improvements and remaining work. A successful build is not a claim of full MyOS or production parity.

## One execution model

| API | Responsibility |
| --- | --- |
| `Language`, `LanguageDocument` | Canonical identity, definitions, inheritance, lists, schemas and cyclic content |
| `PackageLoader`, `BluePackage` | Verify publications and mount package export names |
| `Bex`, `Bex.Program` | Compile and evaluate expressions with isolated inputs, effects and gas |
| `Contracts.Session` | Process a document graph atomically under the published Contracts rules |
| `Contracts.Execution` | Use that same engine with a host's protected durable completion records |

There is one Contracts implementation. Local-1, `Blue`, `Coordinator` and `DocumentRuntime` have
been removed. The durable adapter is a Java API; it introduces no Blue type or authored contract.
All execution uses one accounting policy, `contracts-semantic-gas`. It charges full BEX and
semantic Contracts actions, including child work exactly once in a rooted invocation. Expansion,
canonical hashing and physical proof/storage work consume no gas. There is no experimental policy
selector. See [the policy and qualification map](docs/SEMANTIC-GAS.md).

Old accounting histories require explicit replay from their original source and inputs. They are
rejected rather than silently relabelled. The owner path handles admitted acyclic data changes and can initialize one fresh child
with pinned, unchanged leaf references. Cycles and other unsupported edits use whole-graph execution.
Timeline ordering, persistence and independently
published documents are host responsibilities. `myos-simplified` and `myos-core-better` use that
canonical engine.

Old experimental snapshots require replay from their original source and inputs. They cannot be
relabelled as canonical snapshots. See [the migration notes](CANONICAL-MIGRATION.md).

## Language and packages

```java
Language language = Language.create();
LanguageDocument pointType = language.defineYaml("x: {type: Integer}\ny: {type: Integer}");
LanguageDocument point = pointType.specializeYaml("x: 2\ny: 3");
BlueId identity = point.blueId();
LanguageDocument changed = point.edit(editor -> editor.set("/x/value", 4));
System.out.println(changed.canonicalJson());
```

`name` is document metadata. Package export names such as `Geometry/Point` are separate bindings to
content identities. One document can be exported under several names without changing its identity.
`Language.newBuilder().mount("Geometry", publication)` installs a verified `BluePackage`'s exports.
Loading a publication never installs executable host code or silently selects another semantics edition.

The [Language guide](docs/LANGUAGE.md), [package guide](docs/PACKAGES.md), and
[canonical tutorial](docs/TUTORIAL.md) include complete runnable examples, versioned imports,
provider-backed references, arbitrary content and list identity laws. No package publisher is included.

See [private canonical transactions](docs/CANONICAL-TRANSACTIONS.md) for staged graph
publication, cyclic rollback, and the opt-in Incremental Documents package.

## BEX and Contracts

```java
Bex.Program sum = Bex.create().compileYaml("expr: {$add: [2, 3]}");
Bex.Result five = sum.execute().requireSuccess();
System.out.println(five.value());

Contracts contracts = Contracts.create();
try (Contracts.Session counter = contracts.openYaml("""
    count: 0
    contracts:
      input: {type: Contracts/External Channel}
      increment:
        type: Contracts/Sequential Workflow Operation
        channel: input
        steps:
          - type: Contracts/Update Document
            changeset:
              - op: replace
                path: /count
                val: {$add: [{$document: /count}, 1]}
    """)) {
    counter.initialize().requireApplied();
    Contracts.Transition update = counter.call("input", "increment", Map.of(), 1L).requireApplied();
    System.out.println(counter.read("/count"));
    System.out.println(update.events());
    System.out.println(counter.document().canonicalJson());
}
```

Configuration is reusable; each session owns its execution lifetime. Keep the session or its verified
checkpoint between calls. YAML/JSON exports are inspection boundaries. Published core requirements
are pinned; optional native capabilities must be installed explicitly. Native Timeline, Coordination,
Mandates and Time services are not all provided by this library.

See [canonical execution](docs/CANONICAL-EXECUTION.md) for operations, events, packages, checkpoints
and graph behavior. The actual published Agreements/Connections installer and Hello/Activation
scenario have focused qualification; this does not establish compatibility with every application.

Explicit host extensions use [NativeStep](docs/NATIVE-STEPS.md) for atomic document effects and
[NativeChannel](docs/NATIVE-CHANNELS.md) for deterministic event acceptance. Registrations bind exact
type/semantics identities and tariffs; scoped callbacks return bounded immutable Java values.
[Native admission](docs/NATIVE-ADMISSION.md) verifies separately supplied evidence before channel selection;
it is an extension mechanism, not a bundled Timeline/Time authority or scheduler.
[Receiving-state inspection](docs/RECEIVING-STATE.md) exposes exact native declarations and routing
without running business code.
[Published Timeline channels](docs/TIMELINE-CHANNELS.md) select the exact Coordination bindings and
Dispatch with a mandatory host history verifier. The adapter constructs the admission commitment;
general source acquisition, historical authorization and feeder services remain host integration work.
[Provider signature authentication](docs/PROVIDER-AUTHENTICATION.md) binds the published signature
domains to an explicitly adopted provider. [Timeline verification](docs/TIMELINE-VERIFICATION.md)
establishes incremental history, and [timeline reads](docs/TIMELINE-READING.md) verify signed discovery,
request correlation and pagination. [Timeline Access](docs/TIMELINE-ACCESS.md) replays the published
permission program through Contracts and retains actual historical after-documents. The host joins source ordering and progress with document execution, including explicit trusted-source
configuration and HTTP acquisition/step processing. The sibling host implements automatic acquisition
and durable historical access enforcement for its selected Timeline profile. Complete Coordination
protocol coverage and production-scale host qualification remain unfinished.

For inspection of untrusted or very large data, pass `ProjectionLimits.defaults()` as the second
argument to `session.read(pointer, limits)`. Limits apply while constructing the result, including
literal Contracts data and cyclic references. Exceeding one throws `BlueException` with code
`PROJECTION_LIMIT_EXCEEDED`; it does not change the document or charge execution gas. Ordinary
`read(pointer)` retains the configured Language work bounds, independently for each call.

## Build and run

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
./gradlew build
./gradlew languageExample
./gradlew packageExample
./gradlew canonicalDemo
./gradlew canonicalClausesDemo
```

`canonicalDemo` adds orders and nested payments, calls their operations and checks parent reactions.
`canonicalClausesDemo` runs the published clause installer. The normal build includes all tests,
formatting checks, strict compiler warnings, Javadoc and JMH compilation. It does not contact AWS,
invoke Python or read test data from sibling checkouts. `./gradlew format` applies the standard style.
Dependencies are pinned and locked; source/Javadoc artifacts are included. Publication is configured
only to the local `build/repository/` directory.

## Implementation and evidence

Public facades are `Language`, `Bex` and `Contracts`. Package-private classes implement
admission, evaluation, scheduling and snapshot verification. Hosts can discard their execution
object after every durable attempt. Immutable package configuration can be shared; user documents
need not remain resident. Demo and benchmark code are excluded from the published JAR.

- [Test strategy and ownership](docs/TESTING.md): locate tests by responsibility and see verification gaps.
- [Current canonical campaign](docs/CANONICAL-CAMPAIGN-2026-11-08.md): current gates, measurements, retained/reverted changes and remaining gaps.
- [Historical iteration log](docs/ITERATIONS-2026-11.md): earlier experiments, including retired execution models.
- [Canonical performance](docs/CANONICAL-PERFORMANCE.md): original comparisons and inefficient specification cases.
- [Contributing](CONTRIBUTING.md): build and code conventions.
- [Historical quality review](docs/QUALITY-REVIEW.md): dated assessment, not a current maturity certificate.

For a bounded public-BEX comparison benchmark:

```bash
./gradlew benchmark -PbenchmarkArgs='blue.bench.TextComparisonBenchmark -p codePoints=16,4096 -p alphabet=ascii -p position=first,equal -f 2 -wi 3 -i 3 -w 200ms -r 200ms -prof gc -jvmArgs -Xmx1g'
```

Report warm-up, allocation, storage and completion separately. Compare the same documents, inputs, gas and durable boundary. Historical experimental timings
do not establish canonical or AWS end-to-end performance.

[Incremental timeline verification](docs/TIMELINE-VERIFICATION.md) validates signed ranges and
authenticated successors without rereading established history. Protected host checkpoints retain
verified prefixes across restarts. The companion hosts acquire external coverage independently and
merge eligible originals with local timelines; the campaign report qualifies selected replay and
refresh scenarios. Full Coordination/Mandates/Time service coverage remains outside these claims.

Protected host integrations can optionally reuse [bounded BEX calculations](docs/CALCULATION-REUSE.md)
while preserving ordinary rooted accounting and exact gas-limit failures.
