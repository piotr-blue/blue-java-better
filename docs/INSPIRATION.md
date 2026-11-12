# Java projects to study

Reviewed 2026-11-03. The recommendations below are engineering judgments, not a claim
that there is one objectively best Java library. None is a drop-in Blue runtime.

## Primary reference: Caffeine

[Caffeine](https://github.com/ben-manes/caffeine) combines a compact builder-based public
API with substantial internal performance engineering. Its own project lists integrations
with Spring, Quarkus and Micronaut, and use by infrastructure including Cassandra and Kafka.
That is concrete adoption evidence beyond a subjective assessment of code beauty.

Use it as the main reference for public API restraint, documented invariants, bounded
memory and performance qualification. Its [design notes](https://github.com/ben-manes/caffeine/wiki/Design)
explain indexed queues, buffered maintenance and concurrency tradeoffs. Read the public
`Cache`, `LoadingCache` and `Caffeine` APIs before studying `BoundedLocalCache`.
Its optimized internals are complex: copy the discipline, not that complexity wholesale.
Lossy bookkeeping suitable for cache policy must never be copied into semantic gas or events.

## Closest data-model analogy: Eclipse JGit

[JGit](https://github.com/eclipse-jgit/jgit) implements Git in Java and is an established
[Eclipse project](https://projects.eclipse.org/projects/technology.jgit). Study `ObjectId`,
`ObjectReader`, `ObjectInserter`, `Repository`, `RevWalk` and `TreeWalk` for the separation
between content identity, storage, scoped reads and graph traversal.

Those boundaries are relevant to Blue's exact immutable content and graph processing.
Git's graph and hashing rules are different: its design does not solve Blue's cyclic
identity or gas semantics for us. Borrow the separation of concerns, not Git's public
API verbatim or its complete module structure.

## Performance internals: Agrona

[Agrona](https://github.com/aeron-io/agrona) supplies primitive collections, buffers,
queues and concurrency utilities used by Aeron and Simple Binary Encoding.
Study `Int2ObjectHashMap`, `Long2ObjectHashMap`, `Agent` and `IdleStrategy` for explicit
ownership, avoiding boxing and making low-level costs visible.

Apply these ideas to measured execution indexes and queues. Start with ordinary Java
structures; only add Agrona or off-heap techniques when profiling justifies them.
Its specialized internals should not dictate the public Blue API.

## Practical choice

Caffeine for library ergonomics and engineering discipline; JGit for content/graph
boundaries; Agrona for measured hot-path implementation. All three remain references,
not runtime dependencies of this library.
The implemented choices and remaining gaps are reviewed in [CAFFEINE-REVIEW.md](CAFFEINE-REVIEW.md).

[JMH](https://github.com/openjdk/jmh) supplies the benchmark harness.
[JUnit 6](https://docs.junit.org/6.0.3/overview.html) supplies tests.
[Gradle's compatibility table](https://docs.gradle.org/current/userguide/compatibility.html)
confirms that the pinned wrapper supports Java 25.
