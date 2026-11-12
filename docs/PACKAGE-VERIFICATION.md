> Historical measurement/review. Experimental APIs mentioned below were removed on 2026-11-08; see [migration](../CANONICAL-MIGRATION.md).

# Canonical package loading verification

Implemented and checked 2026-11-04 in `blue-java-better`. The original Java implementation,
Python implementation and shared package repository are unchanged.

## Delivered

- `BluePackage`: immutable verified package, exports, direct imports, definition handles,
  documentation, protocol requirements and a `LanguageProvider` for all supplied exact evidence.
- `PackageLoader`: bounded local archive, directory, stream and resource loading, trusted package
  and Language pins, and optional bounded byte-keyed verification reuse.
- `Language.Builder.mount`: explicit namespaces and optional export selection, checks before
  mutation, version coexistence, and no transitive alias leakage.
- Internal transport, portable path, carrier-integrity and semantic-verification components.
  No third-party runtime dependency was added.
- [18 executable package examples](PACKAGES.md), tested independently and as a runnable guide.

The interpretation follows the selected Language edition's package roles (§18) and repository
carrier (Appendix F). The existing implementations were reference material, not dependencies.

## Evidence

`./gradlew build` passes with **1,728 tests**, zero failures, zero errors and zero skips.
That includes the unchanged 1,460-case published Language corpus, existing Local-1 tests,
and **94 new package cases**. The `packageTest` selector runs those same 94 cases; do not add its
count to the full suite. Strict compiler warnings, formatting, Javadoc, source/Javadoc JARs and
benchmark compilation pass as part of the build.

| Boundary | Cases | Evidence |
| --- | ---: | --- |
| Archive safety and bounds | 25 | Portable paths, Unicode case-fold collisions, text/JSON defects, ZIP modes/headers/truncation, symlinks, transfer/expansion/work limits |
| Loading and identity selection | 7 | Five fixture graphs through archive/directory/stream/resource, caller stream ownership, pins and incompatible Language mounts |
| Exact records and package claims | 15 | Independent record verification, forged primitive response, tampered claims/witnesses/docs, missing evidence, version grammar |
| Package/protocol roles | 7 | Explicit Language import, cross-Language graph rejection, native rules association and identified JSON assets |
| Namespace and content semantics | 5 | Shared definition across publications, explicit import selection, versions, atomic rejection and arbitrary/cyclic provider content |
| Verification reuse | 3 | Same-path changed bytes, eviction/clearing, concurrent repeated loads |
| Independent carrier spellings | 13 | Finite binary64 JSON spellings, negative zero, subnormal/large exponents and Unicode scalar key ordering |
| Unchanged published artifact | 1 | Actual Contracts archive, fixed ZIP SHA-256, package/Language pins, BEX import, protocol descriptors and mounted native type |
| Executable guide | 18 | Each documented workflow executes independently; all three list edits and all three Point source forms agree |

The published Contracts fixture's archive SHA-256 is
`405bcc2749e45ae6cee435c42043a5e7dae11d7f9acb8a6acca19d7ac3170f8d`.
Its package BlueId is `4Jmza3Z7eEDah9qtyYq5i2AqX5tKfUnLNKYAoAPoWzmF`.
The five tiny publications are authored Java fixtures, not independent conformance oracles;
identity laws compare independent construction paths, while the unchanged publication and
original Language corpus provide external reference data. No claim is made that a complete
independent package corpus, fuzzer or exhaustive security campaign has run.

The documented Gradle included-build setup was also exercised in a separate minimal application.
It loaded the fixture through the application classpath, compiled using only the published public
API and produced the expected Point BlueId `FZR5zkfBs51ffUE7DvdGPqLY6jHuTNo7M9f8G7CcQAPx`.

The main library JAR was inspected: no demo classes, tests or `.bluepkg` fixtures are included.
Its source JAR matches current production Java sources. Original conformance and tutorial archive
fixtures remain byte-for-byte unchanged. The test/build path invokes no Python and does not
read a sibling repository.

## Limits and follow-up

Loading validates data and declarations. It does not execute BEX, activate native capabilities,
certify a publisher, or make Local-1 canonical. A public package builder/exporter, project
restore/publish tools and registry acquisition remain absent. Canonical BEX/Contracts/Coordination
execution is still the next major functional gap.

Cache limits are package count and expanded source bytes, not an exact heap allowance. Every load
still reads and checks input structure. Concurrent cache misses can repeat semantic verification.
No latency claim or runtime benchmark improvement is inferred from these functional checks.
