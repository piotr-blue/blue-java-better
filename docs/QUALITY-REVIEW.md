> Historical measurement/review. Experimental APIs mentioned below were removed on 2026-11-08; see [migration](../CANONICAL-MIGRATION.md).

# Quality review — 2026-11-04

**Historical assessment.** Canonical package loading, BEX/Contracts, independent-document execution
and a durable AWS host were added after this review. The scores below have not been reissued as
current certification. See [capability boundaries](CAPABILITY-GAPS.md), [test ownership](TESTING.md)
and [the current iteration log](ITERATIONS-2026-11.md) for the implemented and measured surface.

This is an engineering assessment of the inspected code and evidence, not a certification or a
benchmark between interchangeable products. Caffeine is a caching library with a narrower domain;
Blue is a document/language/runtime stack. Scores reflect quality within each project's scope.
The original Java engine has much more protocol functionality than this experiment.

## Scores after this iteration

| Category (0–10) | Caffeine | blue-java-better | blue-java |
| --- | ---: | ---: | ---: |
| Public API clarity | 9.5 | 8.5 | 7.0 |
| Implementation readability | 9.0 | 8.0 | 6.5 |
| Separation of responsibilities | 9.0 | 8.0 | 6.5 |
| Test organization and discoverability | 9.5 | 8.5 | 6.5 |
| Verification depth | 9.5 | 7.0 | 8.0 |
| Performance engineering and evidence | 9.5 | 7.5 | 7.5 |
| Documentation and onboarding | 9.0 | 8.0 | 6.5 |
| Production maturity within scope | 9.5 | 4.5 | 7.0 |
| Overall code-quality judgment | 9.3 | 7.8 | 7.0 |

These are subjective judgments, not measured percentages or averages. In particular, 8.5 for test
organization does not mean 85% code coverage. The overall score weighs the remaining semantic
split and verification gaps, not only how pleasant the small API looks. Functionality coverage
is described separately in [CAPABILITY-GAPS.md](CAPABILITY-GAPS.md).

Caffeine remains the stronger engineering reference. Its public contracts explain atomicity,
null handling and concurrency; its tests exercise configuration matrices and implementation
invariants; its build configures deeper tools including mutation tests, fuzzing and concurrency
stress/model exploration. This review inspected those sources; it did not run Caffeine's suite.
Sources: [Cache contract](https://github.com/ben-manes/caffeine/blob/master/caffeine/src/main/java/com/github/benmanes/caffeine/cache/Cache.java),
[bounded-cache tests](https://github.com/ben-manes/caffeine/blob/master/caffeine/src/test/java/com/github/benmanes/caffeine/cache/BoundedLocalCacheTest.java),
[verification build](https://github.com/ben-manes/caffeine/blob/master/caffeine/build.gradle.kts).
These links track upstream; they are not dependency pins. No Caffeine implementation was copied
or added as a dependency.

## What improved here

- Replaced broad API/fluent/robustness buckets with capability owners: ingress, inheritance,
  graph evidence, lists, editing, limits, profiles, routing, isolation and ownership.
- Moved public contract tests into consumer packages. Package-private structure tests and the
  conformance adapter retain access to their implementation boundary. Production visibility
  was not widened for tests.
- Extracted shared runtime fixtures; tests no longer call helpers on other test classes.
- Added named Gradle selectors without removing anything from the normal `test`/`build` gate.
- Made all 18 tutorial sections independent entries in the normal report, executing the same
  methods as the runnable guide. The selector is demo code, outside the published library.
- Extended the list identity example into 16 representation/boundary cases, and the embedded-order
  story into nine sibling-count/cache-capacity cases. These test invariants rather than timing.
- Strengthened the existing generated edit check to retain old snapshots and inspect them after
  subsequent mutations; added a diagnostic timeout to catch hung tests.
- Ordered artifact assembly after formatting when both are requested, so the source JAR cannot
  capture an earlier, unformatted version of the source.
- Corrected README/API guidance so canonical Language and Local-1 execution are clearly separated.

The [test strategy](TESTING.md) explains both ownership and the missing kinds of qualification.
The suite contains 1,634 executed cases: the previous 1,591 are retained, plus 18 tutorial cases,
16 list-identity cases and nine order lifecycle cases. Counts are not used as a substitute for
verification depth. The published Language corpus and archived tutorial source files are unchanged.

## Why the improved library is not yet Caffeine-level

The fluent Language API is compact, but Language and Local-1 still have separate interpretation
paths. That is useful during experimentation and confusing as a permanent product boundary.
`Language` and `LanguageSemantics` are both over 1,100 source lines; decomposition and naming still
need review. The original has larger state-machine hotspots (the inspected CoordinationEngine is
about 2,900 lines and ContractRun about 1,800), but also solves substantially more of the problem.
Line counts include documentation and do not determine quality by themselves.

At the time of this review, the improved project lacked a canonical package loader/runtime, durable recovery, systematic
mutation/fuzz/concurrency campaigns, nullness checking, binary compatibility gates and independent
semantic review. Public release coordinates, compatibility policy and deployment qualification
remain decisions to make. A small, readable engine with excellent happy-path examples is not yet
a robust replacement for all the original engine's responsibilities.

The original scores better for verification breadth and operational features: it has canonical
protocol comparisons, SQLite/process/recovery tests and a functioning workbench. Its weaknesses
are mixed historical documentation, broader mutable state-machine code and more difficult test
navigation. Old reports describe different milestones; this iteration did not rerun the original
engine's historical campaigns or claim its current checkout is release-certified.

## Performance interpretation

No latency improvement is claimed from this iteration. The new matrix verifies identical
Local-1 add/submit gas and logical work across 0, 1 and 128 pre-existing siblings and the selected
cache capacities; unchanged sibling nodes remain shared. This does not prove constant elapsed
time or allocations for arbitrary graphs. Full initialization, serialization and canonical
identity calculations have different cost models.

Previous bounded JMH observations remain in [CAFFEINE-REVIEW.md](CAFFEINE-REVIEW.md) and
[RESULTS.md](RESULTS.md), with their exclusions. The original engine produces protocol artifacts
that Local-1 omits. Those measurements cannot justify a universal speed ranking, and comparing
Blue's execution latency to cache operations would not be meaningful.

The next high-value step identified here was canonical package loading with conformance tests, then BEX/runtime
integration behind explicit edition boundaries. Keep the public API simple while adding the
missing semantics, rather than measuring speed after silently dropping required work.

## Verification for this iteration

`build` passed formatting, strict compilation, Javadoc, artifact assembly, JMH compilation and
all 1,634 test cases with no failures, errors or skips. The selector wiring was also exercised:
`languageTest` 1,515, `bexTest` 40, `runtimeTest` 50, `conformanceTest` 1,496,
`scenarioTest` 21 and `concurrencyTest` six. These overlap intentionally; do not add their counts.
The default `build` runs each case once. The published source archive was compared with the
current Java sources, and the library JAR was checked for accidental demo/test classes.

## Subsequent package iteration

Canonical loading/mounting has now been added without a new runtime dependency. The public boundary
is `BluePackage`, reusable `PackageLoader`, and `Language.Builder.mount`. See
[the package guide](PACKAGES.md) and [its verification report](PACKAGE-VERIFICATION.md).
The scores and test counts above describe the preceding review; they are not silently rerated.
Canonical BEX/Contracts execution, publisher tooling and broader adversarial qualification remain.
