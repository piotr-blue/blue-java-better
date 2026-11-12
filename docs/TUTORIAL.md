# Canonical tutorial

Use `Language` for data and `Contracts` for execution. A package alias is separate from the
`name` field. No execution profile is injected into a document by the host.

```java
Language language = Language.create();
LanguageDocument point = language.defineYaml("x: {type: Integer}\ny: {type: Integer}");
LanguageDocument value = point.specializeYaml("x: 2\ny: 3");
System.out.println(value.blueId());
System.out.println(value.canonicalJson());
```

Run `./gradlew tutorialExample` for identity, providers, inheritance, lists and a canonical counter.
Run `./gradlew completeTutorialExample` for the archived tutorial's canonical Language scenarios.
The former Local-1 execution adaptations are retired; they never demonstrated canonical parity.

For execution run `./gradlew canonicalDemo`: initialize Agreement, add Order, submit Order to
create Payment, then settle Payment and observe Order and Agreement reactions. All definitions
are in `src/demo/resources/canonical`. Run `./gradlew canonicalClausesDemo` to execute the pinned
Agreements/Connections clauses. [The execution guide](CANONICAL-EXECUTION.md) explains the API,
state, events, exact gas, snapshots and rollback.
