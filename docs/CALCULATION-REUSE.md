# Protected calculation reuse

`Contracts.Execution.reuseCalculations(CalculationReader)` is an optional, attempt-scoped
optimization. It does not change Contracts gas, native result identities, or the meaning
of `process` and `receive`.

A host may publish `attempt.calculations()` atomically with a successful native outcome,
then supply those artifacts through a protected `CalculationReader` in a later attempt.
An empty lookup executes normally. Uploaded documents and externally supplied artifacts
must never implement that reader: this is trusted implementation storage, not a proof API.

The key binds the exact native implementation namespace, execution identities, member,
current body, original event/time, selected program, call and step values. The artifact
retains selected read descriptors and scalar BEX gas blocks. Reuse performs native input
reads in the current logical accounting context and charges the unchanged calculation.
It does not substitute a standalone child's final gas bill for a containing rooted bill.

A conservative admission bound covers varying once-per-invocation manifest reads. If it
cannot fit both remaining shared and member-local budgets, normal evaluation preserves
the precise first rejected charge, diagnostic and rollback. Native matching, identity,
intrinsics and unsupported lazy/native operations invalidate retention of the calculation.
There is no separate function-body cache. Complex published clause programs may therefore
have no cache hits, even though the simple calculation conformance fixture does.

Artifacts are bounded to 256 reads, 4,096 encoded values, 64 KiB each and 1 MiB per adapter.
Oversized/unsupported calculations continue normally. These are optimization limits,
not new document limits. A host must independently limit total historical cache storage.
Deleting the artifacts only removes reuse. They are not a canonical gas trace or portable
execution evidence.

`calculationStatistics()` reports physical `executed`, `reused` and `budgetFallback`
counts. Those are diagnostics, not gas or proof fields. Native scheduling, mutation,
rounds and finalization remain authoritative; this facility is not complete local-turn
or SCC result reuse.

The `ContractCalculationReuseTest` conformance family checks cross-root reuse, every
bounded shared/member-local gas boundary, changed inputs and native-function fallback.
The sibling MyOS runner additionally tests persisted reuse after close/reopen and exact
whole-root observations/receipts for 0, 10 and 20 embedded published-clause documents.
