# Blue Java Better — source qualification, 10 November 2026

This iteration keeps one canonical Language/BEX/Contracts implementation and one semantic gas
policy. It adds no Caffeine dependency, alternate authored Blue types or Python dependency.

## Retained changes

- Preserve deferred content providers when mounting a recovered value or moving it through
  another Language reader/admission. Mounting stays lazy and does not transfer admission authority.
- Export native preparation deltas for births and active-lifetime changes, with explicit added-member
  declarations. Existing unchanged rows are shared; stronger boundaries still use complete export.
- Recover a structural graph from protected exact owner checkpoints without repeating their
  completed language admission. The advanced host API verifies hashes, runtime bindings, root and
  actual attachment edges and requires a revocable validation scope. It does not authorize an
  invented full-graph preparation predecessor. Portable restoration remains strict.
- Refresh LRU order without replacing an identical immutable cache entry. Weighted capacity,
  replacement, disabled-cache and overflow behavior are covered.
- Update contribution/test ownership guidance and fix a strict Javadoc warning.

Tests belong to the owning suites: `CheckpointTest`, `ContractPreparationTopologyTest`,
`ContractCacheTest`, `LocalOwnerExecutionTest` and the existing Timeline Access/Contracts suites.
See [test strategy](../TESTING.md) and [remaining capability boundaries](../CAPABILITY-GAPS.md).

## Verification

`./gradlew build` passes with 2,511 tests, formatting, compiler warnings as errors, strict Javadoc,
source/Javadoc JARs and JMH compilation. Binary, source and Javadoc JARs reproduce byte for byte.
The documented Language, tutorial, package and canonical-clause examples also run successfully.
The host qualifications compare final document identity, semantic gas and ordered native event
occurrences. Same-boundary preparation/cache tests continue to compare complete receipt identity.

## Measured effect and limits

In the local host, seven matched samples at 1,000 existing Orders reduce median structural addition
from 1,288 ms to 673 ms; Payment propagation remains about 36 ms. These are reopened SQLite
application measurements with shared immutable packages and JVM, not AWS or full-process startup.
The structural path still visits graph metadata. The improvement does not establish constant-time
creation or unrestricted cyclic ownership migration.

The [complete three-project report](../../../myos-aws-test-better/docs/polish-2026-11-10/REPORT.md)
contains raw measurements, iteration decisions, artifact hashes and explicit exclusions. Its source
candidate is separate from the still-running AWS benchmark's older deployment.

I rate the public API and test organization around 9/10, but the complete library remains below a
9+ release claim while structural scalability, wider compatibility qualification and publication
coordinates/license remain open. These are engineering judgments, not external audit results.

The resumed iteration is recorded in the [startup and deployment follow-up](../../../myos-aws-test-better/docs/polish-2026-11-10/FOLLOWUP.md).
It further reduces 1,000-Order initialization while preserving canonical commitments.
