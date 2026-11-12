# Supported API

- `Language`: canonical content, identity, inheritance, list evolution, schemas, references and cyclic sets.
- `PackageLoader` / `BluePackage`: verified publications and namespace mappings.
- `Bex`: canonical compiled programs, values, effects and deterministic gas.
- `Contracts`: canonical initialization, operations, embedded execution, lifecycle events, atomic rollback,
  exact gas/receipts, snapshots and private transactions.
- `Contracts.Execution`: protected durable-record integration for that same canonical engine.
- Native step, channel, timeline and signature APIs: explicit versioned host capabilities. They do not
  provide an implicit scheduler, provider trust policy, storage engine or network authority.

Local-1 and detached document-local execution have been removed. See [migration](../CANONICAL-MIGRATION.md).
The supplied local and AWS owner schedulers use rooted canonical processing. Native receiving is a
separate standard operation and is not a shortcut for rooted gas or rollback.

Full Coordination/provider coverage and production-scale qualification remain incomplete.
