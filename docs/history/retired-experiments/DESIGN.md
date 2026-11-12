> Historical experiment, retired 2026-11-08. This is not a supported API guide.

# Design and implementation decisions

The experiment is one Java library. Public classes and package-private implementation classes
share `blue`; this keeps internal collaboration internal without publishing extra APIs just
to cross package boundaries. No Spring, web server or database is part of the kernel.

## Public boundary

Blue is configured once and shared. Node is immutable; its maps/vectors are persistent
collections. ContractState owns immutable admitted plans and the current document tree.
ProcessingResult explicitly returns state, events, changes, gas, visited occurrences and failure.
A Coordinator serializes updates per root for applications that want a state owner. Each executable
definition declares a complete exact profile; admitted plans retain it. See EXECUTION-PROFILES.md.

API examples compile in `src/demo/java/blue/examples`, outside the library source set.

## Internal responsibilities

| Class | Responsibility |
| --- | --- |
| Resolver | Verify provider content, open references, refine and expand types |
| ContentIds | Supported acyclic Language identity rules |
| BexCompiler | Turn source operators, static paths and functions into reusable instructions |
| ValueOperators | Typed operators, short circuiting, arithmetic limits |
| Frame | Mutable working data owned by one BEX invocation |
| ExecutionProfile / ExecutionRequirements | Exact editions and content-bound requirements |
| NativeCapability / StepContext | Explicit native step implementations and invocation-local effects |
| DocumentCompiler | Admission-time profile validation and compilation |
| DocumentPlan | Immutable event indexes, clauses and compiled workflows |
| Execution | Affected-document queue, topology changes, validation and atomic publication |
| Memo | Fixed-size reuse of pure computations |
| NativeTypes | Built-in catalogue, initialized once |

Business behavior is authored data in examples and source packages. No Java Agreement/Order
handler exists. Workflows supplied as parameters are compiled at admission, not interpreted
as source maps on every event.

## Locality

A changed path copies persistent collection paths, not sibling subtrees. Compiled plans are
retained in ContractState. Inputs address an occurrence directly; event indexes select handlers.
New/deleted occurrence metadata is updated locally. Replacing an entire collection deliberately
pays for comparing that collection. A global business read still has a global cost.

One private transaction owns pending deliveries, patches and events. Nothing is exposed until
all affected execution succeeds. Old immutable snapshots remain valid and
can be branched. Occurrence generations prevent stale queued initialization from reaching a
replacement at the same path.

## Reuse and publication

Execution returns a state, changes and events. It does not calculate a root hash or history
commitment. Canonical content identity is an explicit `blue.blueId(node)` operation with its own
cost. A revision is just a branch-local counter, not a content or history identifier.

Five internal `Memo` tables reuse compiled programs, resolved values, verified provider content,
canonical identities, and validated profile headers. Every hash maps to one slot in an atomic array. A collision replaces
the earlier entry. Node keys use identity comparison, so lookup never hashes a whole tree;
BlueId keys use value comparison. No policy queue, global loading lock, or maintenance thread is
needed. Computations run outside the table, supporting recursive resolution/hashing.

Capacity bounds entry count, not retained bytes. Entries strongly retain their keys and values;
each may reference a large tree. Zero disables caching. Concurrent misses may duplicate pure
computations or provider reads. Providers must be thread-safe and stable for a given identity.
These are explicit tradeoffs of a small memoization utility, not an adaptive caching algorithm.

Admitted snapshots own their plans independently of these tables. Clearing or evicting artifacts
changes physical work, not business output or gas. A concurrent load may repopulate a cleared
slot. Cache clearing is not a lifecycle or synchronization boundary.

The optional Coordinator retains roots and serializes each root separately. It provides neither
durable storage nor cross-root atomicity. A host can process immutable snapshots directly with
Blue and supply its own ownership, persistence and event-delivery policy.

## Qualification boundary

Safe parser limits, arithmetic/call-depth limits, budgets, atomic rollback, concurrent immutable
program use, stale deliveries, pointer escaping, package identity checks and clause vetoes have
focused tests. Shared BEX vectors are also compared with the previous Java evaluator.

The remaining work is semantic breadth and protocol qualification: package publishing/tooling, complete
BEX/Language semantics, cycles/shared graph identities, authorization, durable replay, weighted
cache policies and portable proof validation. Microsecond timings do not establish those
properties. See SUPPORTED.md and RESULTS.md.
