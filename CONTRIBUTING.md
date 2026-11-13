# Working on Blue Java Better

Use JDK 25 and the checked-in Gradle wrapper. No sibling repositories or Python installation
are required. The normal development loop is:

```bash
./gradlew format
./gradlew build
./gradlew languageExample
./gradlew tutorialExample
```

`build` checks Java formatting, compiler warnings (`-Xlint:all -Werror`), all tests, complete
Javadoc with warnings as errors, and compilation of the JMH benchmarks. `format` uses pinned
[google-java-format](https://github.com/google/google-java-format). It is build tooling only.

Keep the public API small. Document defaults, null handling, failure behavior, ordering and
thread-safety where callers need them. Use explicit control-flow braces and names that distinguish
compilation from execution. Comments should explain ownership, invariants or non-obvious costs.
Do not add an interface or class merely to wrap a single method.

Import each type and static member by name; do not use on-demand (`.*`) imports. Mark every
override with `@Override`. Suppress a lint warning only on the one method or variable that needs
it, with the reason beside it, and never on a class: `-Xlint:all -Werror` should keep applying to
the rest of the file. Delete a method when its last caller goes. A package-private member that
only tests call should be rare and deliberate.

Keep mutable work inside an invocation. Public values and admitted plans are immutable. Contracts execution belongs in the package-private `Contract*` classes; BEX uses its compiler
and explicit continuation machine. Canonical Language operations are composed by Language; parsing,
Source construction, schema predicates and contextual semantics have separate implementation
classes. LanguageContext owns operation state; LanguageMap publishes persistent snapshots. Domain behavior belongs in authored Blue examples and packages.

Cache only values that can be recomputed. Test with caching disabled and under collisions when
changing reuse behavior. The system properties that turn individual reuse strategies off are
declared and documented in `DiagnosticSwitches`; add a new one there, not as a string at its call
site, and keep the rule that a switch never changes a document, event, identity or gas amount. Providers may receive concurrent repeated requests. Do not use a host
cache hit as evidence of semantic eligibility or as a reason to change logical gas.

Follow the [test strategy and ownership map](docs/TESTING.md). Add behavior coverage to its
capability suite; avoid catch-all API, regression or robustness classes. Performance
claims need a workload that checks results, warmup, and before/after measurements on the same
machine. Report removed work separately from faster implementation. Large traces and generated
YAML belong in ignored build output, not the source tree.

Keep optional comparison code and benchmarks outside the published JAR. Refresh dependency locks
with `./gradlew resolveDependencies --write-locks --no-configuration-cache` after dependency
changes. The library is experimental; completing the semantic and protocol gaps in [the capability map](docs/CAPABILITY-GAPS.md)
is separate from making its code pleasant to read.

## Check production sources with Error Prone

Run `./gradlew errorProne` with JDK 25. CI uses the pinned compiler plug-in’s default error-level checks. To review its lower-severity suggestions as well, run `./gradlew errorProne -PerrorProneWarnings=true`. Both modes compile into a separate analysis directory. Ordinary builds and shipped artifacts use the normal Java compiler inputs. Review the responsible API or invariant before adding a suppression. [Compiler checks and severities](https://errorprone.info/docs/flags)
