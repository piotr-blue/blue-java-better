# Current capability boundaries

Updated 2026-11-10. This library has one canonical Contracts processor. The earlier
Local-1 and detached document-local interpreters are removed; their measurements
and migration-free checkpoint compatibility are not claims about this processor.

| Area | Implemented | Limit |
| --- | --- | --- |
| Language | Canonical identities, inheritance, list revisions, expansion, providers, cyclic sets | Not a promise of all future Language editions |
| Packages | Verified canonical archives, imports, exact requirements, explicit aliases | No publisher or automatic GitHub acquisition |
| BEX | Published operator/statement implementation, functions, queries, effects, matching, logical gas | No JIT; qualification is bounded by checked-in conformance and scenario coverage |
| Contracts | Initialization, channels, operations, workflows, patches, nested/shared/cyclic graphs, rollback, gas, receipts, restore | Semantic accounting is additive; structural graph execution and snapshot metadata still grow with graph size |
| Java API | `Language`, `Bex`, `Contracts`; `Contracts.Session` for direct use and `Contracts.Execution` for durable host attempts | The durable API exposes storage handles, not extra Blue document types |
| Native integrations | Explicit registered steps/channels, signed Timeline verification, Timeline Access and Document Dispatch | Requires trusted host configuration; package data cannot install Java code |
| Timeline processing | Original identities and timestamps; verified prefixes, completeness and source evidence | A host must establish scheduling order; the library alone is not a distributed scheduler |
| Local scheduler | Durable backlog, indexed dependencies, restart, shared/cyclic graph tests, whole-document parity tests | Closed local authority or verified external completeness is required |
| AWS host | Isolated canonical deployment; selected signed-input, clause, console and API-key flows qualified | Rooted graph growth and seconds-long real clause completion remain; full MyOS parity and production HA are not established |

The original `blue-java`, Python implementation and published package semantics are
not changed by this consolidation. Business compatibility is qualified by the checked-in conformance corpus and selected application
scenarios, not every existing MyOS application. Current whole-versus-independent comparisons require
identical state, ordered events and semantic gas. This is not a claim that the new semantic gas
tariff equals the original implementation’s older tariff, nor Java source compatibility or
automatic conversion of old histories.

See [migration](../CANONICAL-MIGRATION.md), [test organization](TESTING.md), and the
iteration report for measured evidence and explicit exclusions.
