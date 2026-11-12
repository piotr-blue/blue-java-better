> Historical instructions for a retired experiment. These selectors no longer exist.
> Use the single current [semantic policy](../SEMANTIC-GAS.md) and the project README.
> Preserve old histories; migrate by explicit replay into a new history.

# Trying the additive prototype

This is the bounded audit implementation, not the replacement schedule for arbitrary production
clauses. Read [the exact supported bindings](POLICY.md) first. Existing applications retain their
current schedule unless they explicitly select this one.

## Existing Java API

```java
import blue.Contracts;
import blue.GasSchedule;
import java.util.Map;

Contracts contracts = Contracts.builder()
    .gasSchedule(GasSchedule.ADDITIVE_PROTOTYPE)
    .build();

try (Contracts.Session counter = contracts.openYaml("""
    count: 0
    contracts:
      input: {type: Contracts/External Channel}
      increment:
        type: Contracts/Sequential Workflow Operation
        channel: input
        steps:
          - type: Contracts/Compute
            expr:
              changeset:
                - op: replace
                  path: /count
                  val: {$add: [{$document: /count}, 1]}
              events:
                - {kind: Incremented}
    """)) {
    counter.initialize().requireApplied();
    Contracts.Transition result =
        counter.call("input", "increment", Map.of(), 1L).requireApplied();
    System.out.println(counter.read("/count"));
    System.out.println(result.gas());
    System.out.println(result.events());
}
```

Selection happens on `Contracts`, including its durable `Execution` adapter. The standalone
`Bex` facade retains its current published accounting; this iteration introduces no second public
BEX facade or authored execution object.

## Reproduce the local measurements

From `myos-simplified`, with JDK 25 and its normal sibling repositories:

```bash
./gradlew --offline --no-parallel orderScale \
  -PpackagesDir=../blue-packages \
  -PgasSchedule=ADDITIVE_PROTOTYPE \
  -PqualificationOrders=10,100,1000 \
  -PqualificationSamples=5 \
  -PqualificationCounters=true \
  -PqualificationOutput=build/qualification/additive-review-01
```

Use a fresh output directory for each run. Omit `--offline` on a first build if dependencies have
not already been downloaded. For a reverse-order repetition, choose `1000,100,10` and another
fresh output directory.

The command creates one Agreement with N Orders and one Payment per Order. Every Payment has its
own local timeline. Initialization emits Order Initialized and increments the Agreement's count.
For each measured operation, the runtime is closed/reopened, one Payment records 42, its Order
reacts, and the Agreement reacts. The Order uses a real Compute body with Integer/Text validation
outcomes and a Text prefix check. Each invocation must finish three durable owner jobs.

After timing, an uncached whole-root execution of the same input checks the root BlueId, gas and
ordered public events. The reference run is outside the measured interval. `report.json` contains
individual timings and physical counters. The program throws instead of reporting PASS if a
required equality fails.

## Start the local service

```bash
./gradlew run --args='--data .local/additive-review --gas-schedule ADDITIVE_PROTOTYPE --manifest examples/coordination-packages.json --packages-dir ../blue-packages'
```

Always reopen this directory with the same schedule. A directory containing another schedule is
rejected. The existing HTTP API is unchanged; the qualification command above is the shortest
complete reproduction of the measured story.

Unsupported operations return operational incompleteness. The accepted input remains on hold;
it is not rewritten as a business rejection. A persistent three-attempt allowance prevents
repeated recovery/retry from resetting the host-work limit. This prototype intentionally does not
provide an API that clears that allowance. Use the published schedule in a separate history for
operations whose additive binding has not been implemented.

## Focused regressions

```bash
# In blue-java-better:
./gradlew test --tests blue.AdditiveBexTest --tests blue.AdditiveCompositionTest

# In myos-simplified:
./gradlew test --tests myos.local.AdditivePolicyTest -PpackagesDir=../blue-packages
```

The tests are organized by responsibility: primitive lowering/reference checks, Contracts
composition/budgets, and durable local restart/hold behavior. Large measurements belong to the
existing qualification source set, not the default unit-test suite.
