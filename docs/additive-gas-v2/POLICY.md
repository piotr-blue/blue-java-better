# Additive gas: review revision 2 implementation boundary

9 November 2026. **Opt-in prototype, not a published replacement specification.**

This implements the next bounded vertical slice requested in the auditor's amendment to the
9 November gas draft. It is selected with `GasSchedule.ADDITIVE_PROTOTYPE`, persisted as
`contracts-additive-prototype-2`. `PUBLISHED` remains the default. `COMPOSITIONAL_V1` retains its
previous tariff and identity rules. Historical records are not rewritten.

The existing `Contracts` APIs, Language values, BEX programs, Timeline channels and durable
completion APIs are used. There is no new authored document type, public execution model or gas
journal. The prototype has a deliberately closed internal BEX lowering; it is not full BEX
support under a new price table.

## Accounting law

For a prescribed semantic trace `X` followed by `Y`, starting `Y` at the continuation produced by
`X`, `C(X · Y) = C(X) + C(Y)`. Physical cuts do not insert actions or replenish allowances.

The inclusive bill is the sum of distinct required work contributions and each receiver's own
work. An independently retained result is not globally “already paid.” A new receiving attempt
accounts for its required source contribution again. Do not add overlapping inclusive totals.

Example measured by this implementation:

| Work | Own gas | Inclusive gas |
|---|---:|---:|
| Payment | 59 | 59 |
| Order reacting to Payment | 123 | 182 |
| Agreement reacting to Order | 91 | 273 |

Thus `59 + 123 + 91 = 273`, not `59 + 182 + 273`. These prices describe the exact qualification
fixture, including its matching and validation work. They are not universal prices for Payments
or Orders.

## Allowances and debit order

* Default and maximum shared allowance: **10,000,000**.
* A chosen shared allowance is in `[0, 10,000,000]`.
* A member allowance is in `[0, chosen shared allowance]`.
* Candidate Integer work uses exact `BigInteger` arithmetic. A large candidate is rejected;
  it does not enlarge the permitted allowance or wrap a counter.
* Charges precede the protected semantic action. Admission updates shared and owner totals
  together. A rejected action updates neither.
* If both caps reject an action, select the smaller remaining allowance; ties select shared.
* A run of unit actions admits its fitting prefix, then rejects one unit. It is not an atomic
  aggregate simply because the implementation debits a fitting run in bulk.
* `CALL` costs two and is atomic. An Integer work envelope is atomic.

No authored gas-remaining query is introduced. A failed atomic invocation keeps its predecessor
state. Independently committed child results retain the existing Contracts rollback boundary.

## Enabled prices and lowering

All prices below are prototype values. The complete proposed tariff remains subject to resource
calibration and publication review. An absent lowering is not a zero-cost operation.

| Action | Price | Implemented boundary |
|---|---:|---|
| `EVAL` | 1 | Enter a selected expression, supported statement, or Contracts workflow step |
| `CALL` | 2 | Enter the initial BEX body |
| `FRAME` | 1 | Its lexical frame |
| `LOOKUP` | 1 | Source lookup and each visited path edge; selected matching/output field |
| `COMPARE` | 1 | Enter a scalar equality, matching or supported validation obligation |
| `INTEGER` | 1/unit | Exact arithmetic/comparison limb envelope |
| `TEXT` | 1/byte | Each UTF-8 operand byte examined |
| `NEW_CONTAINER` | 1 | Explicit object/list, supported native payload or default result wrapper |
| `NEW_SLOT` | 1 | Constructor insertion, step-result binding, outbox/inbox reference |
| `WRITE` | 1 | Applicable scalar document replacement, including an equal replacement |
| `TURN` | 1 | An actual initialized/processing member turn |
| `INIT` | 1 | Actual member initialization |
| `HANDLER` | 1 | Enter an actual handler |
| `EMIT` | 1 | An emitted occurrence; its outbox slot costs a further one |
| `DELIVER` | 1 | A semantic delivery; its inbox slot costs a further one |

A completion delivery is charged whether the source ran inline or was restored. Physical transport
adds no second delivery. Unused scheduler units, sealing proofs, expansion, collapse, decoding,
restoration, immutable spine copies and identity hashing add no representation surcharge.

Required authored predicates are different: a false result still pays for its attempted work.
The verified Timeline history adapter is an operational prerequisite. Other native callbacks,
including dispatch policies, are not enabled under this prototype without an explicit binding.

### BEX subset

Supported expression bindings:

* Scalar literals and `$literal` share admitted values; no recursive construction charge.
* Plain object/list constructors and `$emptyObject`/`$emptyList` charge container and insertion
  actions. Object values follow Unicode scalar key order. Each value is evaluated before its
  insertion. Static keys are existing Text.
* `$document`, `$event`, `$currentContract`, `$binding`, `$steps`, `$const` support static Text
  paths. Reads stop at the first absent prefix. A successful selected read records its selected
  dependency, not an additional full-document dependency. Ambiguous missing-prefix calculation
  reuse is disabled.
* `$add`, `$subtract`, `$multiply`, `$divide` accept Integer operands. Operands are evaluated
  left to right. Conversions are excluded. Set `L(n)=max(1,ceil(bitLength(abs(n))/32))`.
  Add/subtract charge `max(L(a),L(b))`; multiplication `L(a)L(b)`; division `2L(a)L(b)` before
  checking nonzero/exact division. Variadic arithmetic applies these in order.
* Scalar `$eq`/`$ne` pay `COMPARE`, then Integer `L(a)+L(b)` or Text work where applicable.
  Mixed Integer/Double, collection equality and conversions are excluded.
* `$startsWith` accepts Text operands. Check exact derived UTF-8 lengths first, then paired
  left/right bytes until mismatch or prefix completion. Byte sizes are derived from trusted
  Java Text, not supplied by a reference-shaped object's metadata.
* `$not`, `$truthy`, `$exists`, `$kind`, `$empty`, `$isEmpty` use established kind/value facts.
* `$and`, `$or`, `$coalesce`, `$default` and `$choose` schedule selected operands lazily.
* `$is` uses the closed Language obligations below.
* Straight `$return` and `$fail` statements, and an empty body, are supported. Named functions,
  query operators, other statements, dynamic paths and generated executable content are held.

Text scans use scalar available/admitted counts and one bounded byte cursor. There is no object,
callback, journal row or database write per byte. An odd remaining allowance admits the left-byte
unit before rejecting the right-byte unit. A known fitting run is debited in bulk. Actual bytes
are inspected only within reserved remaining capacity.

The expression evaluator and expression compiler traversal are iterative. Pending operands are
scheduled lazily. This does not claim that all legacy BEX statements or Language helpers became
iterative.

### Language obligations: explicit, narrow binding

This is a required part of the prototype, not an exemption for “validation preparation.”

1. `$is` and required channel/handler matching enter one `COMPARE` obligation.
2. Supported patterns are literal scalars, flat partial plain-object patterns and primitive
   `Integer`, `Text`, `Boolean` declarations.
3. For a flat object pattern, visit required fields in canonical key order: `LOOKUP` actual field,
   `COMPARE` child obligation, then scalar equality work. Stop at the first failure.
4. Primitive declarations compare established kinds. Both the matching and nonmatching outcome
   pay the obligation. Raw typed/reference-shaped actual values require a binding and are held.
5. Supported document writes are existing scalar replacements of unchanged kind, with no
   inherited target obligation, schema, collection typing, contextual retyping or path into a
   Language/Contracts declaration. Charge root
   and selected-edge lookups, target `COMPARE`, then `WRITE` before mutation. Unsupported contexts
   stop operationally; the implementation does not guess a validation price or call them free.
6. Supported emitted payloads are plain flat objects with Integer/Text/Boolean fields. Their
   admission pays one object `COMPARE`, then selected `LOOKUP` and `COMPARE` per field.
7. Contracts' fixed initialization/update payloads have explicit container/slot construction
   charges. Exact reference encodings of established values are physical representations.

Tests compare this matching subset directly with canonical Language matching, as well as checking
its own debit order. General inherited constraints, retyping, nested/list patterns, disjunctions,
cyclic matching and arbitrary typed output admission remain **release blockers** for a complete
additive implementation. They are not silently enabled.

### Worked small vectors

`expr: {$add: [2, 3]}` costs `CALL 2 + FRAME 1 + EVAL 3 + INTEGER 1 = 7`.
Allowance 6 rejects the Integer action with 6 admitted, before addition.

`expr: {$startsWith: [abx, aby]}` returns false and costs `2 + 1 + 3 + 6 = 12`.
At allowance 11 it has admitted 11 and rejects one `TEXT` unit. False is not a free outcome.

`expr: {$is: {node: 13, pattern: {type: Integer}}}` costs 6 and returns true.
Changing the required type to Text also costs 6 and returns false.

A 20,000-level `$not` expression around `true` costs 20,004 and executes without Java recursion
proportional to expression depth.

## Physical scalar-update optimization

Once the binding above has proved that an existing ordinary scalar is replaced with the same kind,
without constraints or declaration changes, the graph and channel lifetimes cannot change.
The engine therefore keeps its existing topology and skips reconciliation, reachability deltas,
channel refresh and the creation barrier for that update. Identity finalization and Document Update
delivery still occur. A regression forces the full reconciliation and full attachment scan and
compares complete observations with the optimized path.

Incoming completions also select live affected attachment tickets directly instead of scanning all
outgoing edges to install two changed references. This is a physical optimization with no gas discount.

## Persisted reuse and identity

The implementation adopts only a protected successful local completion, with the same original
cause/timestamp, selected runtime/schedule and exact predecessor vectors. Retained snapshots,
observations and receipts are validated by the existing protected completion reader. Hash equality
alone is not authority to submit arbitrary totals.

This first prototype deliberately adopts a **single acyclic, static source** at default caps.
It checks all accounted source-owner predecessors. Allocations/births, cyclic source records,
multiple source summaries, incompatible predecessors, custom caps and interleaved outside
predicates take the ordinary execution path. Unsupported dynamic graph edits are held earlier.
Before installation it also checks remaining shared capacity and whether another independently
seeded branch could interleave. Ambiguous cases replay the original cause.

Consequences:

* Member totals are sufficient summaries only inside this restricted, protected single-source
  path. They are not presented as a general work-occurrence deduplication algorithm.
* Diamond/overlapping summaries are not merged by unioning member IDs. General occurrence-ordered
  decomposition is still required before enabling that reuse optimization.
* Custom shared/member caps conservatively replay, even when a more sophisticated cursor could
  safely reuse part or all of a successful result. Exact and insufficient budget tests cover the
  resulting failure prefix and rollback.
* In ordinary execution, cycles retain the existing SCC/round semantics and are metered by each
  actual turn. The bounded cycle regression exhausts its cap and rolls back.
* No allocator branch was changed. No worker UUID or storage address becomes a semantic identity.
  A new schedule is explicitly part of execution context, so receipts/event commitments from
  different schedules are not asserted equal. Comparisons use the same selected schedule.

Portable invocation chunks retain context, tentative state and meter together through the existing
protected API. Local durable cuts remain at completed owner transactions. No arbitrary submitted
BEX continuation or checkpoint after a debit/before an effect is accepted.

## Host protection and publication boundary

Prototype compiler admission bounds: 250,000 visited input positions, eight million Text/key
characters, at most 128 functions and bounded statement nesting. Expression depth itself is
iterative. An invocation's lowering has a one-million-continuation-action scheduling quantum;
a Text operand is limited to one million UTF-16 code units before UTF-8 materialization.
Existing Language request limits remain in force.

In `myos-simplified`, the prototype also records a durable **three-attempt host allowance per
(owner,input)** before restoration/execution. Resource-driven rebuilds consume the same allowance.
Restart and manual retry do not reset it. Exhaustion holds the accepted input; it does not write a
semantic rejection receipt or advance past that input. Unrelated owners continue. This is a bounded
attempt policy, not an exact accounting of CPU nanoseconds, database pages or retained heap.

The overall 10-million-gas tariff is not yet resource-calibrated for every proposed operation.
There is no claim of an overall constant-memory or constant-time bound for snapshot reconstruction,
proof creation, hashing or all storage activity. Those are separately measured host costs.
