> Historical measurement/review. Experimental APIs mentioned below were removed on 2026-11-08; see [migration](../CANONICAL-MIGRATION.md).

# Canonical execution verification

Verified 2026-11-04 with Java 25.0.3. `./gradlew format build canonicalDemo canonicalClausesDemo`
passes: **1,964 tests, zero failures, errors or skips**, strict compiler warnings, formatting,
Javadoc, source/Javadoc JARs and benchmark compilation.

The existing 1,728 tests remain; 236 canonical execution tests were added. This includes all 191
unchanged published BEX cases and 23 stored Contracts reference cases with exact receipts/gas.
The remainder checks public APIs, immutable inputs, null/undefined, failure boundaries, portable
replay/tampering, changed shared definitions, cycles, cold restoration, concurrent sessions,
embedded order/payment propagation, public Language handles, encoding and real clause installation.
No test invokes Python, reads a sibling source tree or requires network access.

Runtime code is not an old-library dependency: its canonical BEX/Contracts algorithms were
adapted from the local Java implementation and connected directly to this library's Language
kernel. That is substantially more proven behavior, but also more implementation code than the
Local-1 experiment. This report does not rate the internals as equal to Caffeine or claim complete
hostile-input, multi-process, native-service or application qualification.

The immutable data and compiled-program changes were checked with caches enabled/disabled and
with selected definitions changed. Direct, portable-chunk and cold execution match. Nine complete
business operation comparisons against old Java match both gas and receipt, including at 128
existing orders. See [measurements and limitations](CANONICAL-PERFORMANCE.md).

The reported bulk-initialization limitation is not a passing performance claim. The old canonical
algorithm exhausted its 10-million-gas budget with 128 initially present, event-emitting children.
The improved runtime retains that selected algorithm; fixing its semantics requires a versioned
specification decision and new reference cases. The demonstrated scalable construction path adds
children through ordinary operations instead.

Published input SHA-256 checksums and exact rules identities are recorded in
[CANONICAL-SOURCES.json](CANONICAL-SOURCES.json). The bundled Connections archive is test/demo data,
not a production dependency in the main library JAR. Standard runtime publications are packaged
in the library so it does not need a sibling checkout at startup.
