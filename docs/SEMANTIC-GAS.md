# Semantic gas: the only execution policy

This implementation uses `contracts-semantic-gas` for every Contracts invocation. There is no
published/prototype/compositional selector. This is an intentional accounting change relative to
older Blue releases; it does not claim that their historical gas totals or receipts are unchanged.
Original source documents, type identities, business results and ordered event payloads remain
subject to the canonical Language/BEX/Contracts tests.

## Meaning

Gas measures semantic work in an invocation, not the CPU time or storage traffic needed by a host.
Canonical identity, representation expansion/collapse, inherited-definition loading, immutable
record decoding, topology bookkeeping, proof encoding and persistence are uncharged.

For an acyclic successful causal execution with the same original input and predecessor states:

```
gas(rooted A with B) = gas(own work in A) + gas(work attributed to B)
```

A retained child result is charged exactly once in the containing invocation. `gasByMember` is
that disjoint attribution ledger; its entries sum to `gas`. A parent's reported total already
includes its adopted child's work. Do not add that total to the child's separately reported total.
Quiet sibling documents contribute no gas. A real broadcast that executes every sibling still
costs gas for every executed document. Cycles can execute multiple semantic turns and charge each.

The equality requires the same semantic trace. A parent that removes a child before its input,
changes its predecessor state, exhausts a shared/local budget, or participates in a cycle cannot
blindly install a previously successful independent result. These cases use canonical execution
with the shared budget and atomic rollback. The host may retain an independently committed child
result even when its containing invocation rolls back; that is a separate history.

## Charges

All counts below use the current implementation, not the discarded limited BEX prototype.

| Contracts action | Charge |
|---|---:|
| Open a semantic document turn | 1 `TURN` |
| Initialize a processing scope | 1 `INIT` |
| Invoke a handler | 1 `HANDLER` |
| Check a patch boundary or traverse a patch pointer segment | 1 `LOOKUP` per occurrence |
| Add, replace or remove a logical value | 1 `WRITE` |
| Enqueue an internal event | 1 `EMIT` + 1 `NEW_SLOT` |
| Deliver an update, triggered event, embedded event, lifecycle event or completion batch | 1 `DELIVER` + 1 `NEW_SLOT` |
| Construct a Contracts runtime object with N fields | 1 `NEW_CONTAINER` + N `NEW_SLOT` |
| Validate a BEX output at a Contracts effect boundary | 1 `VALIDATE` |
| Invoke/evaluate a Contracts Compute step | 1 `EVAL`, plus BEX below |
| Native business extension | Exact declared tariff; no arbitrary free callback |

The complete BEX evaluator retains its published tariffs. `BexGas.DEFAULT` is the executable table:

| BEX counter | Units charged |
|---|---:|
| expressionBase, statementBase | 1 |
| documentRead | 2 |
| eventRead, stepsRead, currentContractRead, varRead | 1 |
| resultValueRead | 2 |
| pointerGetBase | 1 |
| pointerSetBase | 3 |
| objectSetBase | 2 |
| appendChangeBase, appendEventBase | 5 |
| forEachItem | 1 |
| functionCall | 2 |
| integerWork, textWork, valueMember | 1 per specified work unit |
| nodeIdentityRequested | 5; hashing/expansion itself is free |

Primitive-specific integer limbs, Unicode/text chunks, traversals and short-circuit decisions are
implemented by `BexEngine`, `BexMachine` and `BexValues`, and checked by the canonical BEX vectors.
An unselected branch is not evaluated. Expression/statement continuations use a heap work stack.
The full operator language is available; ordinary clauses do not require switching gas policies.

## Limits and first failure

The maximum shared invocation budget is 10,000,000. Callers may select a lower shared budget and
per-member caps. Charges are checked before the corresponding semantic action. On failure the
admitted prefix and first rejected charge are retained, and business state rolls back atomically.
A retained result must not reset the containing invocation's budget. Budget-sensitive receiving
falls back to canonical execution to preserve charge order and the first failure.

Tariff multiplication checks overflow. Wide candidate-cost tests ensure a positive charge cannot
wrap to a negative/small number. Native timeline admission is separate bounded host work: an
exhausted verification allowance produces `HOST_INCOMPLETE`, not a committed business gas failure.

Gas-free representation work is not unlimited host work. Source sizes, compiler limits, continuation
counts, effect sizes, provider evidence and host attempts have independent physical bounds. These
bounds can hold a job without a semantic receipt. They must never silently turn incomplete work
into a successful transition. Deep-expression, deep-statement and deep-equality tests cover the
new evaluator; this is not a claim that every library/presentation routine is unbounded or stack-free.

## Persistence and compatibility

Every checkpoint and receipt binds the accounting identity. A missing or retired identity is rejected.
Do not rewrite the identity on an existing snapshot. Replay original inputs into a fresh history if
migration is wanted. Archived reports describe the policy that actually produced their numbers.

Whole-graph and independent-owner processing compare document BlueId, status, gas, gasByMember,
ordered events, public events, diagnostics and rejection. Their physical receipts and protected
storage boundaries need not be byte-identical. Protected local records are not portable graph proofs.

## Requirement-to-test map

| Requirement | Implementation | Primary tests |
|---|---|---|
| Full BEX behavior and published primitive gas | BexMachine, BexEngine, BexValues | CanonicalBexConformanceTest, SemanticGasTest |
| Independent reference for small expression costs/failures | SemanticGas/BexGas | SemanticGasTest.independentSmallExpressionReferenceSelectsChargesAndValues |
| Deep selected execution without JVM recursion | BexMachine, iterative block compiler/equality | SemanticGasTest deep operands/statements/value comparisons |
| Inline/collapsed/inherited representation invariance | SemanticGas, ContractWork | GasCompositionTest |
| Child + own parent gas, quiet sibling independence | ContractCompletions, ContractLocal | GasCompositionTest, LocalOwnerExecutionTest |
| Shared/local cap and first rejected charge | ContractModel.Meter, completion eligibility | GasCompositionTest budget/cap tests; local CompositionBoundaryTest |
| Cycles, rollback, retained completion checks | ContractRun, ContractCompletions | GasCompositionTest, OwnerCycleTest, GraphShapeParityTest |
| No unrelated sibling-body restoration | ContractLocal and indexed attachments | LocalOwnerExecutionTest with 1/20/1000 siblings and a rejecting provider |
| Ordinary inherited data patches preserve semantics | SemanticGas.localWrite, ContractRun.patch | LocalOwnerExecutionTest add/replace/remove × own/inherited |
| Durable restart and current-policy binding | host OwnerRecords; local LocalOwners | SemanticPolicyTest, RootedCompositionTest, ConsoleGasPolicyTest |
| Published clauses, unchanged business outcomes | canonical engine and Blue package clauses | CanonicalClauseTutorialTest; ClauseWorkflowProbe + uncached OwnerReplay |

The tests establish their stated workloads and boundaries. They do not establish a universal latency
bound for arbitrary BEX, structural graph mutation, one million documents or a production SLA.
