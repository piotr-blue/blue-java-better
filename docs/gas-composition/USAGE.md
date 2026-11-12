> Historical instructions for a retired experiment. These selectors no longer exist.
> Use the single current [semantic policy](../SEMANTIC-GAS.md) and the project README.
> Preserve old histories; migrate by explicit replay into a new history.

# Using the draft compositional gas schedule

This is an explicit experimental semantics revision. Existing code continues to use published gas-6. Do not switch an existing history's schedule halfway through processing.

```java
Contracts contracts = Contracts.newBuilder()
    .language(language)
    .gasSchedule(GasSchedule.COMPOSITIONAL_V1)
    .build();

try (Contracts.Session agreement = contracts.openYaml(yaml)) {
  agreement.initialize().requireApplied();
  Contracts.Transition result = agreement.call("input", "submit", Map.of(), 1);
  result.requireApplied();
  System.out.println(result.gas());
  System.out.println(result.observations().get("gasByMember"));
  System.out.println(agreement.document().canonicalJson());
}
```

The channel and operation above must exist in the supplied document. The schedule changes accounting and composition rules, not how operations are declared.

`gasByMember` contains each member's own semantic work for the invocation. Its sum is the inclusive invocation gas. A parent reusing a child's completion incorporates that ledger once and adds its own work. Expansion, collapse, provider/cache warmth and the number of untouched siblings do not add semantic gas. Actual fan-out, loops and parent reactions do.

For `myos-simplified`, choose the schedule when opening a **fresh** storage directory:

```java
try (LocalRuntime runtime = new LocalRuntime(
    storageDirectory, packageCatalog, GasSchedule.COMPOSITIONAL_V1)) {
  // Use the ordinary timeline and document APIs.
}
```

The directory pins its schedule; reopening it with a different schedule is rejected. The local HTTP server exposes the same explicit choice:

```bash
./gradlew run --args='--data .local/compositional --gas-schedule COMPOSITIONAL_V1'
```

For the bounded cold-reopen benchmark:

```bash
./gradlew orderScale \
  -PpackagesDir=../blue-packages \
  -PgasSchedule=COMPOSITIONAL_V1 \
  -PqualificationOrders=10,1000 \
  -PqualificationSamples=3 \
  -PqualificationOutput=/tmp/blue-gas-fresh-run
```

Choose a new output path for each run. The qualifier compares separately processed children and parents against whole-root execution, including root BlueId, gas and ordered public events. The documents with 10 and 1000 siblings naturally have different whole-document identities; the claim is that their selected operation performs the same business work and charges the same gas.

For the AWS host, `ownerGasSchedule` in runtime configuration, or `MYOS_OWNER_GAS_SCHEDULE=COMPOSITIONAL_V1`, selects the default for **new independent owner histories**. Saved histories use their persisted schedule. `/health` reports `newOwnerGasSchedule`. Ordinary legacy graph execution is not silently switched.

After building the service distribution and qualification JAR, run the temporary DynamoDB worker:

```bash
python3 scripts/qualify_owners.py \
  --orders 10,1000 --cold --gas-schedule COMPOSITIONAL_V1
```

The helper requires the existing AWS qualification configuration and terminates its temporary worker. Keep its complete report. Reused-result receipts have different provenance from whole-root receipts in this draft; the qualifier checks the semantic result rather than pretending the receipts are identical. Refer to [STRATEGY.md](STRATEGY.md) for overflow protection, retryable host-work limits, dynamic identity changes and remaining physical graph costs.

## Directly compare child reuse with whole-root processing

The durable API uses protected local completion records. The host supplies these records after
committing them; it must not accept arbitrary uploaded observations as trusted completions.
With an initialized `rootBefore`, an embedded member key, and the same eligible original input:

```java
Map<String, Contracts.Execution.Retained> committed = new HashMap<>();
try (Contracts.Execution execution = Contracts.Execution.using(contracts, committed::get)) {
  Contracts.Execution.State rootBefore = execution.initialize(
      execution.prepare("agreement", document)).requireApplied().after();
  Contracts.Execution.State childBefore = rootBefore.rooted(childMemberKey);
  Contracts.Execution.Outcome child = execution.process(
      childBefore, input, timestamp, AdmissionEvidence.empty()).requireApplied();

  String receipt = child.transition().receipt();
  committed.put(receipt, new Contracts.Execution.Retained(
      child.before().checkpoint(), child.after().checkpoint(),
      child.transition().observations(), receipt, child.event(), child.timestamp()));

  Contracts.Execution.Outcome composed = execution.receive(
      rootBefore,
      List.of(new Contracts.Execution.Source(receipt, List.of(childMemberKey))),
      true, AdmissionEvidence.empty()).requireApplied();
  Contracts.Execution.Outcome whole = execution.process(
      rootBefore, input, timestamp, AdmissionEvidence.empty()).requireApplied();

  assert composed.after().blueId().equals(whole.after().blueId());
  assert composed.transition().gas() == whole.transition().gas();
  assert composed.transition().observations().get("publicEvents")
      .equals(whole.transition().observations().get("publicEvents"));
}
```

Here `document` is a `LanguageDocument`, `input` is `LanguageContent`, `timestamp` is a `long`,
and `childMemberKey` is the retained member key of the selected embedded child. For native Timeline
channels, supply the verified admission evidence instead of `AdmissionEvidence.empty()`. Selecting
one member from inside a cyclic source is invalid: the entire atomic source unit is required.

`whole.transition().gas()` is the root's inclusive total. To obtain the root's own contribution,
read its entry from `gasByMember`. Receiving a source does not make the child's logical gas free;
it makes its already-established execution reusable.
