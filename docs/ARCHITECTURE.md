# How the Blue runtime executes an embedded document

An Agreement can contain an Order whose Payment changes independently. When the Payment emits an event, the Order may run its own clause, then emit an event that the Agreement receives. The runtime establishes each containing document's new BlueId, the order of its events and the gas it used. The host supplies the original input and publishes the result. This boundary lets a local Java session and a durable service use the same authored clauses.

| If you are changing… | Start here | What the component owns |
|---|---|---|
| A value's identity or the evidence needed to open it | [Language](../src/main/java/blue/Language.java) | Canonical values and content resolution. A BlueId identifies a value. A member key identifies an occurrence in the executing graph. |
| A package or an operator | [PackageVerifier](../src/main/java/blue/PackageVerifier.java), [BexPlanCompiler](../src/main/java/blue/BexPlanCompiler.java), [BexMachine](../src/main/java/blue/BexMachine.java) | Package integrity, compiled programs, then per-invocation evaluation and effects. |
| The result of an authored clause | [Contracts](../src/main/java/blue/Contracts.java), [ContractRun](../src/main/java/blue/ContractRun.java) | Public session and host interfaces, native channel admission, turns, lifecycle, gas and observations. |
| Embedding order or a cycle | [ContractTopology](../src/main/java/blue/ContractTopology.java), [ContractComponents](../src/main/java/blue/ContractComponents.java), [ContractSchedule](../src/main/java/blue/ContractSchedule.java) | Physical edges, component partition and execution order, then the work ready in the current phase. |
| Restoring a protected owner | [ContractLocalCheckpoint](../src/main/java/blue/ContractLocalCheckpoint.java) | Committed member metadata, embedding generations, exact references and preparation for the selected runtime environment. |
| Applying a child completion to a parent | [ContractLocal](../src/main/java/blue/ContractLocal.java) | Generation and predecessor checks, source gas, event order and the parent's native result. |

## What can be shared between executions

A package loader verifies the publication and its dependency pins. A Language context mounts the package's exported names and their evidence. The name in authored YAML therefore resolves through the selected package. Two runtimes that select different definitions cannot silently treat their identically named types as interchangeable. A Language provider supplies content by identity. This allows durable hosts to fetch a referenced value when the operation actually opens it. [Package loading](PACKAGES.md) · [Language identity tests](../src/test/java/blue/LanguageIdentityTest.java)

BEX compiles a program once and can share that immutable program across threads. Each evaluation owns its input, remaining gas and effects. Contracts decides when to apply those effects to a member. A patch first checks managed boundaries and protected state, then reconciles any change to the embedding graph. It emits Document Update events for generalization repairs before the requested update. Mutable continuations and tentative snapshots belong to the current attempt. A host can share immutable program and content caches across attempts and still discard the entire failed attempt. [BEX execution contract](../src/main/java/blue/Bex.java) · [Transaction and rollback rules](CANONICAL-TRANSACTIONS.md) · [Conformance tests](../src/test/java/blue/CanonicalBexConformanceTest.java)

## Why scheduling uses strongly connected components

A parent can observe a child's result. A cycle makes the members mutually dependent, so Contracts schedules them as a group. A single pass over members in map order could expose a different result after a restart. The topology computes components with two iterative graph traversals and orders the resulting acyclic graph children first. Where several components are ready, their canonical member-name sequences break the tie. The ordering compares Unicode scalar values. An explicit stack handles deep chains.

A snapshot can share its physical topology with another snapshot whose member and edge structures have the same identity. The mutable phase still owns readiness, active lifecycle and gas. A topology cache hit therefore saves reconstruction while the invocation performs its own eligibility checks. The topology tests cover cycles, repeated edges, permuted input order, Unicode ties and speculative forks. [Implementation](../src/main/java/blue/ContractComponents.java) · [Ordering and isolation tests](../src/test/java/blue/ContractTopologyTest.java)

## Why a checkpoint binds more than the owner's BlueId

The same child value can occupy two paths. Removing and reattaching a child at the same path creates a new binding generation. The parent can adopt a completion only for the path, generation and prior child BlueId it held when that input was selected. The checkpoint also commits to runtime bindings and preparation. Local recovery verifies its identity and environment before installing those facts. Whole-graph recovery verifies the assembled members and edges, including legacy checkpoints that require ordinary edge validation. A host must control who can supply these checkpoints. The hash authenticates their contents relative to the trusted predecessor. [Checkpoint codec](../src/main/java/blue/ContractLocalCheckpoint.java) · [Recovery and tamper cases](../src/test/java/blue/LocalBindingRecoveryTest.java)

## Following one Payment acknowledgement

1. The host opens a thread-confined `Contracts.Execution` for a pinned predecessor and supplies the admitted original entry. The ordinary native channel rules select the receiving declarations.
2. When advancing the Order, `ContractLocal` checks that each selected Payment path names the same cause and timestamp, generation and previous child BlueId. Multiple paths may carry the same completion. Distinct child completions require whole-graph execution to resolve their relative work.
3. The runtime checks the child's entire gas allocation before charging it, imports its ordered events once and passes its output batches to the Order's native turn. The Order's clause determines the business effect. The Agreement can consume the Order's completion under the same rules.
4. If a creating turn introduces one fresh child, the runtime can resolve its existing leaf references through the host's pinned live-member lookup. The same component owns the rules for projecting a fresh child from an already issued whole-graph result. It verifies that the child initialized and the shared leaves stayed unchanged. Ambiguous identity, stale bindings and unsupported graph changes require recovery of the full graph. The host publishes only a completed tentative result.

[Child creation and completion](../src/main/java/blue/ContractBirth.java) · [Local execution and whole-graph comparisons](../src/test/java/blue/LocalOwnerExecutionTest.java) · [Gas rules](SEMANTIC-GAS.md)

## Which invariant to verify

A change to caching or persistence should preserve the result after a cold restore. A scheduling change should preserve document identity, status, gas by member and ordered observations when the same input is replayed in the ordinary engine. Receipt identities also include the execution boundary, so compare like boundaries for exact receipt equality. An optimization to physical reads must still charge whatever the selected gas schedule requires. The number of visited channels and the bytes required to encode a growing parent map measure different work. [Gas composition tests](../src/test/java/blue/GasCompositionTest.java) · [Validation and binding reuse](../src/test/java/blue/LocalBindingReuseTest.java) · [Test ownership](TESTING.md)

## Where a contract expression gets its inputs

`ContractExpressions` binds a compiled BEX program to the current invocation. It selects compute definitions through the invocation’s logical graph, charges logical reads and prepares the event, document and step bindings. A continuation may replace the run's snapshot and graph. The expression boundary reads those from the current run at evaluation time. A compiled program can be shared because it retains neither the current document nor its authorization.

`ContractRun.guard` brackets a unit of execution with a scoped witness set. Typed work can select its own result type while the rejection path still uses the contract outcome. It restores the previous witness scope even when a host failure escapes.

## Physical weight of a shared graph

The preparation caches bound entries by an estimate of their retained key and payload. A document may refer to the same immutable subtree twice. The estimator counts both paths so an entry cannot get cheaper simply because a decoder shared those objects in memory. It memoizes a subtree only after finishing its children. Each addition is checked against the remaining allowance before it changes the total. This also bounds a compact graph with an exponentially large expanded tree. The estimate is local to the process and contributes no semantic gas. [Estimator](../src/main/java/blue/ContractCache.java) · [Shared-graph regression](../src/test/java/blue/ContractCacheTest.java)

## When a step applies its output

A Compute may return an object containing a changeset and events, or accumulate them while evaluating. An Update Document step supplies a changeset directly. Both can contain lazy Blue values read from another document. The runtime reads the effect container and patch headers, validates every patch and event in order, and preserves the original values for the next step. Its internal patch record carries the decoded operation, pointer segments and value through execution. An append to a list resolves the final index against the list as it exists when the patch runs.

An effect can trigger a workflow that terminates its own member. The executor checks the member's generation before each subsequent patch and event. This check also applies to effects returned by a host extension. Native extensions retain their own wire-level validation and gas ledger. [Effect validation](../src/main/java/blue/ContractEffects.java) · [Lazy values and constructed effects](../src/test/java/blue/CanonicalEffectTest.java)

While opening a processing phase, the invocation groups admitted external channels by their owning member in encounter order. A completed turn records its member and phase in a transient set. Subsequent turns use this set to decide when routes need recapturing. A continuation fork owns its own set. Neither index enters receipts or gas, and both are discarded with the invocation.


A generalization repair returns the changed exact document and an ordered list of type-reference replacements. Each item records its pointer and both references. Contract execution charges each replacement, verifies that protected markers and the retention policy still identify the same values, then emits the individual update events in order. The public receipt remains a wire representation. [Type repairs](../src/main/java/blue/Generalization.java) · [Patch and reaction ordering](../src/main/java/blue/ContractPatches.java)

## When a channel runs a handler

Dispatch fixes the ordered candidate list and checks each candidate’s event and channel lifetime. Each admitted handler owns a fresh step table. A step can read results of earlier named steps, apply document changes or emit events. A terminated or replaced channel lifetime stops the remaining steps. Nested dispatch receives a separate table and restores its enclosing writer afterward. [Channel dispatch](../src/main/java/blue/ContractRun.java) · [Sequential workflow execution](../src/main/java/blue/ContractWorkflow.java) · [Atomic effect and step snapshot tests](../src/test/java/blue/CanonicalEffectTest.java)

A speculative Contracts chunk begins at a closed local frame. `ContractRunFork` copies invocation state. The gas meter copies the accepted debits, and `ContractGraph.fork` gives the candidate independent mutable topology and logical history. Immutable partition rows and exact archive records can be shared. Only acceptance of the checked chunk installs its candidate, so computation on a speculative branch cannot warm the issuer’s logical evidence or change its receipt.


A workflow validates the complete changeset before applying the first patch. Each patch then examines the current document, checks managed ancestors and protected markers, repairs permitted type changes and records its writer. Data-only changes can finalize the owner directly. A structural change reconciles references and initializes newly reachable members before delivering update reactions. Reactions can execute nested changes, so the next patch reads the current graph again. [Effect validation](../src/main/java/blue/ContractEffects.java) · [Patch application](../src/main/java/blue/ContractPatches.java) · [List and rollback tests](../src/test/java/blue/ContractListPatchTest.java)
