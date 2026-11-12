# Package example fixtures

The five small tutorial publications are authored by `blue.PackageFixtureGenerator` in test
sources. They are canonical packages, with their own small Language bootstrap, and are not
replacements for the shared Language release. Normal tests consume these checked-in archives;
they never regenerate the oracle or invoke Python. `preparePackageExamples` writes rebuilds
under `build/generated-package-fixtures/` for deliberate maintainer review.

`contracts.bluepkg` is copied byte-for-byte from the shared `blue-packages/blue-contracts/dist`
publication. It contains its exact Language/BEX dependency evidence and protocol declarations.
`SOURCES.json` records provenance, package BlueIds and physical SHA-256 checksums. Tests pin the
actual Contracts package and Language identities. None of these demo archives are in the main JAR.

The production standard Package and ProtocolSpecification definitions match that Language
edition. Its Unicode 15.1 case-fold table is used solely for archive path collision detection;
it is retained as static data and does not create a Python build or runtime dependency.
