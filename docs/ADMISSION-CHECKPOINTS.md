# Protected local admission recovery

A small operation can refer to a current document whose immutable checkpoint leads to many previous
Timeline entries and document versions. Verifying that reference from scratch follows the complete
dependency closure. Repeating this after every host restart is unnecessary when this same host has
already validated the value and durably retained the complete closure.

`LanguageDocument.checkpoint()` captures that prior completion. It accepts completed values only,
including values returned by Contracts. Its record contains the exact BlueId, Language edition and
checkpoint format. Its commitment is local recovery metadata, **not a BlueId, signed evidence, a
portable proof or execution permission**.

The storage transaction must retain the complete immutable content closure and protect the checkpoint
commitment. Recovery is explicit:

```java
LanguageCheckpoint checkpoint = completedValue.checkpoint();
// Application storage atomically protects checkpoint.record(), checkpoint.identity(), and the
// retained closure. A request must never supply the expected commitment for this boundary.

LanguageDocument recovered = retainedLanguage.restoreCheckpoint(
    protectedRecord, protectedCommitment);
LanguageDocument message = retainedLanguage.read(
    requestInput, Language.Goal.DEFINITION, recovered);
Optional<Contracts.OperationRequest> operation = contracts.operationRequest(message, recovered);
```

The variables `protectedRecord` and `protectedCommitment` must come from the application's previously
committed storage; `retainedLanguage` must resolve its retained immutable content. A remote caller
knowing a digest does not satisfy that requirement. Ordinary `read`, `admit`, portable imports and
provider records do not consume checkpoints implicitly.

Recovery verifies the selected exact body and any owning-set proof, and reconstructs its typed
header. It reuses the previously protected semantic result rather than checking all historical
dependencies again. New identities, requests and contextual refinements still need ordinary
validation. Missing or forged selected bodies fail. The full closure remains required for portable
export or later access; a checkpoint cannot repair lost storage. A different Language edition or
checkpoint commitment is rejected. A presentation whose semantic identity differs cannot be used
to capture another value's completion.

This does not change Language/Contracts semantics or deterministic gas. It changes host recovery
work under an explicit protected-storage trust boundary, analogous to the existing local Timeline
publisher checkpoint. It is unsuitable as a replacement for independent verification of an untrusted
host, or as proof supplied to a third-party verifier. Membership, account access, channel permission,
source history, native admission and conditional publication remain independent checks.

## Host integration and migration

MyOS stores optional per-member admission checkpoints in its content-addressed physical manifest,
published with the same atomic graph head and retained content. Unchanged member metadata reuses the
previous checkpoint. `Contracts.Transaction.member(key)` exposes a completed candidate for this
purpose; a candidate value is valid but does not assert that its transaction committed. Portable
Contracts snapshots omit these local checkpoints and continue to undergo ordinary independent
verification.

Older publications have no checkpoint. They initially retain ordinary validation behavior. An
explicit legacy backfill validates the selected member through its pinned historical provider before
writing an account/page/member/edition-bound cache record. It does not rewrite the original manifest,
graph head, history, input, receipt or business state. Failure writes no reusable fact. The next normal
publication can also capture checkpoints from its already validated session. No migration silently
derives validation authority from an arbitrary BlueId.

`blue.language.CheckpointTest` owns identity/presentation equivalence, selected-read bounds, ordinary
read isolation, schema restrictions, malformed commitments and cyclic ownership. Operation grammar
and temporary evidence lifetime belong to `blue.contracts.OperationRequestTest`. The host's
`CanonicalAdmissionsTest` covers publication, historical and foreign-account isolation, unchanged
checkpoint reuse, explicit old-format backfill, corruption and failed-validation atomicity.

## Recovering a pre-checkpoint Contracts publication

A host that retains its own successful Contracts publication has another explicit source of prior
completion. `Contracts.recoverMember(snapshot, protectedIdentity, memberKey)` authenticates the
complete snapshot metadata against the independently protected publication commitment and checks the
supported Contracts release. It selects a unique member from that authenticated metadata and verifies
the exact member body and cyclic ownership through the configured Language provider. It does not
open an execution session, install envelope content, inspect other bodies or repeat old timeline
validation. It returns a completed Language value from which the host may retain a normal checkpoint.

```java
Contracts retained = Contracts.newBuilder().language(retainedLanguage).build();
LanguageDocument value = retained.recoverMember(
    metadataEnvelope, committedHeadIdentity, memberKey);
LanguageCheckpoint checkpoint = value.checkpoint();
```

This is suitable only for a successful publication made by this host under the installed semantics,
with a complete retained immutable closure. The caller must establish that provenance. A hash supplied
by an untrusted importer, a candidate that never completed, or a claim from another host is not such
provenance. Use ordinary `Contracts.restore` for those independent verification boundaries. A
snapshot digest is a commitment, not a signature or proof of execution. Recovery does not establish
account access or current membership and cannot replace execution restore.

MyOS's explicit legacy migration obtains `expectedIdentity` from the protected HEAD row separately
from the physical manifest. It reconstructs and hashes all document/edge metadata once, so this
migration scales with compact metadata size. The optional account/page/member/edition cache makes
subsequent selected-member recovery independent of that migration. Original snapshots, histories,
heads, receipts and pending operations remain unchanged. The earlier full-validation migration is
still separate and unchanged; failure there never creates completion evidence.

`blue.ContractMemberRecoveryTest` covers independently restored member/record equivalence for trees
and cycles, missing or forged selected bodies, changed commitments and metadata, unsupported release,
unknown/duplicate members, scoped request preparation and unchanged strict ordinary verification.
The host's `CanonicalAdmissionsTest` covers protected migration, account isolation, idempotence,
original portable bytes and no cache write on failed recovery.
