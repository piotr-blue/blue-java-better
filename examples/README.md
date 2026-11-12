# Executable examples

Run `./gradlew example`. Sources are ordinary Blue/BEX data interpreted by the experimental
Local-1 engine. They are not canonical Agreements package publications.

- `order.blue`: initialization, submit operation and distinct lifecycle/business events.
- `agreement.blue`: keyed embedded orders, parent reactions, add/remove operations.
- `packages/commerce/ledger.blue`: a reusable parent listener with a field pointer parameter.
- `packages/commerce/audit.blue`: a reusable local listener accepting an entire workflow parameter.
- `packages/commerce/guarded.blue`: an amendment veto based on the current document's locked flag.

Samples.java composes twelve ledgers, three audit clauses per order and two nested fulfillment
documents per order. Its Java code only authors data; all business updates/events are BEX.

The `exhaust` operation in agreement.blue is an intentional budget-failure diagnostic. It emits
an event loop; the gas limit rejects and rolls back the transaction. It is not called by the demo.

Clause amendment behavior is exercised in `blue.runtime.ClauseAmendmentTest`: add a clause, initialize its field, remove
it when unlocked and reject removal when locked. Replacement changes future behavior without
rerunning initialization; state migration/cleanup must be authored explicitly.
