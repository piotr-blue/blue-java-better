> Historical measurement/review. Experimental APIs mentioned below were removed on 2026-11-08; see [migration](../CANONICAL-MIGRATION.md).

# Language implementation and verification

The implemented core is `blue-language/2026-09-26-specification-metadata-1`, using the six
published primitive definitions and the two unmodified published conformance corpora.
[LANGUAGE-SOURCES.json](LANGUAGE-SOURCES.json) records their SHA-256 hashes and the specification
hash. The build and tests require neither a sibling checkout nor Python.

## Correctness evidence

The staged clean build checks production/test compilation with warnings as errors, formatting,
Javadoc, benchmark compilation and all **1,572 tests**:

- **1,460 published Language cases:** 1,408 primary cases and 52 edge cases. Most primary cases
  exercise identity and numeric encoding; other cases exercise source controls, validation,
  schema refinement/conjunction, inheritance/subtyping, selected/complete reads, graph cuts,
  minimization and cyclic identities/proofs. Expectations are not regenerated from this engine.
- **21 public Language API tests:** typed lists/dictionaries, positional edits, successive
  append-only versions, no repeated appends on reload, exact versus inherited observations,
  Definition/Value admission, identity-preserving expansion/collapse/split, verified providers,
  provider/proof resource bounds, cyclic proof/body mismatch, metadata, exact refinement,
  independent evidence export/reload, concurrency, persistent snapshot ownership and ID syntax.
- **91 existing tests:** Local-1 execution, clauses, BEX, profiles, providers, identity and
  immutable state regressions.

`languageExample` exercises the public API and checks its results before exporting exact YAML,
inspection JSON, compact list wire and a cyclic proof. The existing `example` and `profileExample`
remain acceptance checks for the unchanged experimental execution surface.

The corpus checks its published observations; it is not a mathematical proof of every possible
input. This is Language-core coverage, not qualification of the separate package-processor role
or canonical BEX, Contracts and Coordination execution.

## Bounded registry-size measurements

JMH 1.37, JDK 25.0.3, one fork/thread, three 1-second warmup and three 1-second measurement
iterations, with allocation profiling. Setup registers unrelated content plus a Person type.
Each admission parses the same short YAML document, constructs exact content and strictly
validates it. Each selected read resolves its admitted age field. Setup is outside measurement.

| Unrelated definitions | Small-document admission | Selected inherited read | Admission allocation | Read allocation |
| ---: | ---: | ---: | ---: | ---: |
| 1 | 49.037 µs | 7.354 µs | 261,737 B | 57,240 B |
| 128 | 50.451 µs | 7.901 µs | 261,221 B | 57,208 B |
| 4,096 | 50.683 µs | 7.866 µs | 264,863 B | 57,776 B |

[Captured JMH summary](measurements/language-registry-scaling.txt) includes the error estimates.
These are bounded local measurements, not service-level latency guarantees. Allocation is still
substantial and is a clear target for subsequent optimization.

An earlier diagnostic run exposed an avoidable scan and copy of all registered aliases during
every Source preprocessing call. That run used shorter warmup/measurement intervals, so its
absolute numbers are not a controlled speedup comparison. Its native output is retained in
[language-before-alias-reuse.json](measurements/language-before-alias-reuse.json).

Changes made in response:

1. Validate public alias bindings at builder registration; do not rescan a fixed, verified registry
   for every source. Raw conformance contexts still validate untrusted bindings.
2. Use persistent alias and evidence maps. Ordinary selected reads start from immutable roots
   without copying all registered content; imports create local persistent refinements.
3. Register list payload identities when constructing typed lists, so compact prefix lookup does
   not search unrelated stored bodies.
4. Keep wire export explicit; admission does not eagerly serialize all dependency records.

This removes registry-wide work from the measured paths. It does not remove the work needed to
parse or validate the document itself. Wide ancestor plans, deeper inheritance, larger list
payloads, full export and complete closure validation can cost more. Incremental execution and
its gas model are a separate concern.

## Reproduce

```bash
./gradlew clean build example profileExample languageExample
./gradlew benchmark -PbenchmarkFilter=blue.bench.LanguageBenchmark \
  '-PbenchmarkArgs=-f 1 -wi 3 -i 3 -w 1s -r 1s -prof gc'
```

The implementation remains in one Gradle library. No reference engine, Caffeine runtime,
external package checkout, network resolver or persistent journal is required.

## Tutorial API refinement

The complete build now passes 1,589 tests, including the unchanged 1,460 published conformance
cases. Sixteen focused fluent-API regressions and the executable tutorial were added. The build
also checks formatting, compiler warnings, Javadoc and benchmark compilation.

The new checks cover named definition/value ingress, YAML and JSON subtypes/instances, explicit
compatible and incompatible types, lazy references, missing evidence and retry, provider identity
verification, immutable atomic editing, schema rejection, collapsed ancestor editing, consistent
list paths, append-only preservation, canonical equality after three deletion routes, large integer
and Double editing, input limits, cyclic edit rejection, and expanded type/specification semantics.

`tutorialExample` uses selected YAML from the supplied tutorial archive and
writes inspectable output under `build/reports/tutorial/`. Original fixtures remain unchanged;
the curated Point examples now use plain display labels with separate export bindings.
The separate Local-1 counter adaptation
verifies 0 → 3 → 2 and its emitted events. `languageExample`, `example` and `profileExample` also pass.
The existing agreement example still initializes 385 managed documents and routes a submit to the
selected order and root with 17 workflows, 17 events and gas 706. This is not MyOS protocol parity.

No Language specification or published conformance fixture was changed. `node()` now consistently
exposes a list's `/items` slot even when serialization uses array shorthand. Specialization now
preserves an explicit compatible type instead of rejecting every explicit type. Existing advanced
Goal/Input entry points remain available.

Direct editing avoids JSON serialization and reparsing, but still validates the complete candidate.
No new throughput or latency claim is made from these functional checks.

## Package export names

After the package-name clarification, the complete build passes 1,591 tests, including the same
1,460 published conformance cases. Both tutorial executables pass, including all 18 sections of
the complete tutorial. Two new regressions verify that multiple qualified aliases and direct
specialization produce the same instance identity and canonical form, and that supplying content
does not register its optional `name` metadata as an alias. Relabeling changes content identity;
adding an export binding does not. These checks exercise resolved alias bindings, not canonical
package artifact verification or loading.

## Test-organization iteration

The subsequent iteration retains these checks and runs 1,634 cases across named capability suites.
See [test ownership and strategy](TESTING.md) and [the current review](QUALITY-REVIEW.md). All 18
complete-tutorial sections now run under the ordinary `test`/`build` gate. The Language fixture
corpus is unchanged; no new runtime parity or latency claim follows from this reorganization.
