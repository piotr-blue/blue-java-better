# Executable examples

Run these commands from the repository root with JDK 25. The Java source for each example lives in [src/demo/java/blue/examples](../src/demo/java/blue/examples). The examples throw on a failed expectation and Gradle reports a failed task.

| Gradle task | What to inspect |
| --- | --- |
| `./gradlew tutorialExample` | Create and expand language values, edit lists, then run a two-step counter and inspect its emitted events. |
| `./gradlew completeTutorialExample` | Compare authoring forms, exact references, projections, lists and checkpoints. Includes invalid inputs and missing evidence. |
| `./gradlew packageExample` | Verify an archive against its pinned BlueId, mount its exports, extend a type and use multiple package versions together. |
| `./gradlew canonicalDemo` | Initialize a published Agreement and inspect the processing result. |
| `./gradlew canonicalClausesDemo` | Run the canonical clauses example and inspect its activity and emitted events. |

The loose `.blue` files in this directory describe the historical Local-1 example format. Use the Java demos and their package archives for current executable contracts.
