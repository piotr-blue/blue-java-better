> Historical experiment, retired 2026-11-08. This is not a supported API guide.

# Blue Local-1: executable experiment

Status: experimental rules, not a canonical Blue publication. The Java implementation here
implements this bounded edition. Existing Java, Python and `blue-packages` remain unchanged.

## Selection and scope

Every executable contracts block declares a complete exact `Coordination/Execution` profile.
Inherited profiles suffice; the old runtime marker is optional and never substitutes for a profile.
See EXECUTION-PROFILES.md and the content-addressed declarations in src/main/resources/blue/semantics/. Recognized Contracts type names are
syntax in this edition, not a claim of canonical Contracts/Coordination conformance. The native
identity table was extracted from Contracts `1.0.0-deterministic.4` for content addressing.
Execution is selected by the exact execution profile, not the optional legacy runtime marker.

The graph is a tree of managed occurrences. Repeating immutable content creates independent
occurrences. Shared managed identities, cyclic components, external timelines, signatures,
canonical completion proofs and durable recovery are outside this edition. Type-reference
cycles fail. A returned ContractState is not a portable proof.

## Canonical Language API

`Language` and `Blue.language()` implement the fixed canonical Language edition described in
[LANGUAGE.md](../../LANGUAGE.md). They provide full Language construction, admission and exact graph
operations, including list evolution, schema constraints and cyclic owning sets. These operations
are separate from the Local-1 `Node`-based runtime described below. A canonical Language document
is not automatically an executable Local-1 document; `contracts` is ordinary Language content
until an execution processor selects and validates its declared environment.

## Values and types (Local-1 runtime)

Values are immutable objects, lists, arbitrary-precision integers, Unicode text, booleans and
null. Missing is distinct from null. Duplicate keys, YAML collection aliases, invalid Unicode
and floating-point values fail. JSON pointers use RFC 6901: empty string is root; `/` is the
empty key. Parser depth is bounded to 128 and YAML source length to 16 Mi code points.

Language identity covers acyclic values, integer/text/boolean scalars, labels, lists, references
and type aliases. Null has no standalone Language identity. This is not full Language admission:
schema validation, generalization, recursive types, doubles and all restriction/merge rules
are absent. Inheritance supports nested field refinement, primitive slots and fixed inherited
values. Do not substitute it for the canonical validator.

`blue-local-package/1` loads local source exports with acyclic type dependencies. It is an
experimental authoring format, not `.bluepkg`. Provider content is checked against its claimed
BlueId; a cache hit never grants trust.

## Execution and ordering

Admission resolves a document, compiles workflows/clauses and indexes subscriptions.
`Contracts/Process Embedded` declares `paths` and object-valued `collectionPaths`. List-based
managed collections and overlapping embedding declarations fail. Managed depth is bounded.

Lifecycle, triggered and embedded channels route sequential workflows. Local operation
channels route `call`: this is trusted host ingress, not an authorization system. Clause
operation names are `clauses/<key>/<operation>`; root operation names are unqualified.
Unknown operation, channel and step kinds fail.

Initialization queues parent then children in key order. Deliveries are FIFO; matching workflows
run in name order. Embedded events expose `{event, path}` to the receiver. Optional `sourcePath`
is a JSON-pointer prefix filter. Emitted events also queue local triggered-event deliveries.
Deliveries capture occurrence generations, so removing and reinserting at a path cannot revive
queued work for the removed occurrence.

A compute step reads its entry document via `$document`; `$resultValue` reads tentative changes.
Changes apply before the next step. Events are delivered after the current workflow. A parent
may replace/add/remove an embedded occurrence, but cannot patch inside a live child.

All affected child and parent work commits atomically. A failure, including budget exhaustion
during workflow execution, publishes no changes/events and returns the original state.
An initialization failure has `state == null`.

## Clauses

`clauses` maps keys to inline or typed definitions. Each clause's `contracts` receive its
`parameters` as `$binding: params/...` and explicit `bindings` by name. Bindings may hold pointers
to existing fields. `Local/Run Workflow` with `parameter: action` compiles the supplied step
sequence at admission/amendment. Parameter expansion is bounded to 32 levels.

Changing `/clauses` recompiles that document's plan. Each changed/removed old clause can define
`validateChange`, evaluated with current document and bindings `previous`, `candidate`, `params`.
It must return boolean true with no effects. Otherwise the whole transaction is rejected.
This is validation, not external authorization; the owning workflow decides whether to request
an amendment.

Added clauses receive initialization. Replacement updates future behavior and parameters
without reinitializing or deleting state. Removal unregisters behavior and preserves business
fields. State migration/cleanup must be explicit workflow changes. Direct modification of
`/contracts`, `/runtime`, `/type` and `/executionRequires` fails.

## Local gas

Gas models an incremental abstract machine. Admission facts are retained in opaque immutable
state. Unchanged sibling headers/bindings are neither revisited nor charged per call. Eligibility
comes from the admitted snapshot, never cache warmth.

| Work | Charge |
| --- | ---: |
| Managed admission | 20 |
| External entry | 5 |
| Delivery | 2 |
| Workflow | 5 |
| Statement / expression | 1 each |
| Pointer read | 1 + segments |
| Patch | 4 + segments |
| Event emission | 4 |
| Pattern node comparison | 1 |
| Loop iteration / collection entry materialized | 1 |
| Occurrence removal | 5 |

Logical size: 1 per node, plus UTF-16 units for text, floor(bitLength/8) for integers, and
1 + key.length per object entry; collections include child sizes. Each node retains this
intrinsic aggregate. Edits update it along changed paths, without scanning siblings.
Addition/subtraction charge the larger operand's 64-bit word count; multiplication charges
the product. Arithmetic exceeding 65,536 estimated bits fails. Text concatenation and explicit
collection operations have additional per-element charges in BexCompiler.

Charges precede effects. Caches can be cleared without changing results or gas.
Compilation remains an unmetered admission activity with bounded source depth/size; hostile
input qualification is unfinished. This is not canonical gas accounting.

## Results and identity

BlueId keeps its Language meaning within the implemented fragment. Calculating full content
identity after changing a large object/list can still be linear. That operation is explicit.

Execution returns a new immutable snapshot, ordered changes and events. It creates no history
commitment and persists no journal. `ContractState.revision()` starts at zero and increments on
each successful continuation of that branch. It is not a globally unique version or a proof.
The host decides how to persist snapshots, authenticate inputs and reliably deliver events.

Revision note (2026-11-03, polish iteration): the earlier unversioned experiment exposed CommitId
and charged for its history hashing. That API and those charges have been removed. This edition
has never been a canonical immutable publication; earlier measurement files retain their original
meaning. Workflow behavior is retained, while gas totals intentionally exclude the removed work.
Public adoption still requires semantic completion, review and cross-language qualification.
